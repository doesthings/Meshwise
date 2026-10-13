#include "bvh.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <thread>

namespace fsv {

namespace {

struct BuildTri {
    Vec3 bmin, bmax, centroid;
};

float surfaceArea(const Vec3& mn, const Vec3& mx) {
    Vec3 d = mx - mn;
    if (d.x < 0) return 0;
    return 2.f * (d.x * d.y + d.y * d.z + d.z * d.x);
}

constexpr int kBins = 12;
constexpr uint32_t kLeafSize = 4;
constexpr int kMaxDepth = 56;

}  // namespace

namespace {

struct BuildContext {
    const std::vector<BuildTri>* bt;
    uint32_t* tris;
};

struct Bin { Vec3 bmin, bmax; uint32_t n; };

// Builds the subtree for tris[first, first+count) into `nodes`, rooted at nodes[root].
// When `splitTasks` is non-null, recursion stops at nodes with <= taskSize triangles and
// those are returned as tasks instead (for parallel building).
template <typename NodeT>
void buildRange(const BuildContext& ctx, std::vector<NodeT>& nodes, uint32_t root, uint32_t first, uint32_t count,
                int depth0, std::vector<std::array<uint32_t, 4>>* splitTasks, uint32_t taskSize) {
    const std::vector<BuildTri>& bt = *ctx.bt;
    struct Task { uint32_t node, first, count; int depth; };
    std::vector<Task> stack;
    stack.push_back({root, first, count, depth0});
    while (!stack.empty()) {
        Task task = stack.back();
        stack.pop_back();
        Vec3 nmin(1e30f, 1e30f, 1e30f), nmax(-1e30f, -1e30f, -1e30f);
        Vec3 cmin = nmin, cmax = nmax;
        for (uint32_t i = 0; i < task.count; ++i) {
            const BuildTri& t = bt[ctx.tris[task.first + i]];
            nmin = vmin(nmin, t.bmin);
            nmax = vmax(nmax, t.bmax);
            cmin = vmin(cmin, t.centroid);
            cmax = vmax(cmax, t.centroid);
        }
        std::memcpy(nodes[task.node].bmin, &nmin, 12);
        std::memcpy(nodes[task.node].bmax, &nmax, 12);
        auto makeLeaf = [&]() {
            nodes[task.node].leftOrFirst = task.first;
            nodes[task.node].count = task.count;
        };
        if (task.count <= kLeafSize || task.depth >= kMaxDepth) { makeLeaf(); continue; }
        if (splitTasks && task.count <= taskSize) {
            splitTasks->push_back({task.node, task.first, task.count, (uint32_t)task.depth});
            continue;
        }
        // Binned SAH along the longest centroid axis.
        Vec3 extent = cmax - cmin;
        int axis = extent.x > extent.y ? (extent.x > extent.z ? 0 : 2) : (extent.y > extent.z ? 1 : 2);
        float ext = extent[axis];
        int bestSplit = -1;
        float bestPos = 0, bestCost = 1e30f;
        if (ext > 1e-12f) {
            Bin bins[kBins];
            for (Bin& b : bins) { b.bmin = Vec3(1e30f, 1e30f, 1e30f); b.bmax = Vec3(-1e30f, -1e30f, -1e30f); b.n = 0; }
            float scale = kBins / ext;
            for (uint32_t i = 0; i < task.count; ++i) {
                const BuildTri& t = bt[ctx.tris[task.first + i]];
                int bi = std::min(kBins - 1, (int)((t.centroid[axis] - cmin[axis]) * scale));
                bins[bi].n++;
                bins[bi].bmin = vmin(bins[bi].bmin, t.bmin);
                bins[bi].bmax = vmax(bins[bi].bmax, t.bmax);
            }
            float rightArea[kBins - 1];
            uint32_t rightN[kBins - 1];
            Vec3 rmin(1e30f, 1e30f, 1e30f), rmax(-1e30f, -1e30f, -1e30f);
            uint32_t rn = 0;
            for (int i = kBins - 1; i > 0; --i) {
                rn += bins[i].n;
                rmin = vmin(rmin, bins[i].bmin);
                rmax = vmax(rmax, bins[i].bmax);
                rightN[i - 1] = rn;
                rightArea[i - 1] = rn ? surfaceArea(rmin, rmax) : 0;
            }
            Vec3 lmin(1e30f, 1e30f, 1e30f), lmax(-1e30f, -1e30f, -1e30f);
            uint32_t ln = 0;
            for (int i = 0; i < kBins - 1; ++i) {
                ln += bins[i].n;
                lmin = vmin(lmin, bins[i].bmin);
                lmax = vmax(lmax, bins[i].bmax);
                if (ln == 0 || rightN[i] == 0) continue;
                float cost = ln * surfaceArea(lmin, lmax) + rightN[i] * rightArea[i];
                if (cost < bestCost) {
                    bestCost = cost;
                    bestSplit = i;
                    bestPos = cmin[axis] + (float)(i + 1) / scale;
                }
            }
        }
        float leafCost = task.count * surfaceArea(nmin, nmax);
        if (bestSplit >= 0 && bestCost >= leafCost && task.count <= 16) { makeLeaf(); continue; }
        if (bestSplit < 0 && task.count <= 16) { makeLeaf(); continue; }
        uint32_t* begin = ctx.tris + task.first;
        uint32_t* end = begin + task.count;
        uint32_t* mid = begin + task.count / 2;
        if (bestSplit >= 0) {
            mid = std::partition(begin, end, [&](uint32_t t) { return bt[t].centroid[axis] < bestPos; });
            if (mid == begin || mid == end) mid = begin + task.count / 2;
        }
        uint32_t leftCount = (uint32_t)(mid - begin);
        uint32_t left = (uint32_t)nodes.size();
        nodes.push_back(NodeT{});
        nodes.push_back(NodeT{});
        nodes[task.node].leftOrFirst = left;
        nodes[task.node].count = 0;
        stack.push_back({left, task.first, leftCount, task.depth + 1});
        stack.push_back({left + 1, task.first + leftCount, task.count - leftCount, task.depth + 1});
    }
}

}  // namespace

void PartBVH::build(const Model& m, const Part& part, unsigned threads) {
    nodes_.clear();
    tris_.clear();
    const uint32_t first = part.firstIndex / 3;
    const uint32_t count = part.indexCount / 3;
    if (count == 0) return;
    std::vector<BuildTri> bt(count);
    tris_.resize(count);
    const uint32_t* I = m.indices.data();
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t tri = first + i;
        Vec3 a = m.vertex(I[3 * tri]), b = m.vertex(I[3 * tri + 1]), c = m.vertex(I[3 * tri + 2]);
        bt[i].bmin = vmin(a, vmin(b, c));
        bt[i].bmax = vmax(a, vmax(b, c));
        bt[i].centroid = (bt[i].bmin + bt[i].bmax) * 0.5f;
        tris_[i] = i;  // local index during build
    }
    BuildContext ctx{&bt, tris_.data()};
    nodes_.reserve(count / 2 + 16);
    nodes_.push_back(Node{});
    if (threads <= 1 || count < 50000) {
        buildRange(ctx, nodes_, 0, 0, count, 0, nullptr, 0);
    } else {
        // Split the top of the tree serially, then build subtrees in parallel.
        std::vector<std::array<uint32_t, 4>> tasks;
        uint32_t taskSize = std::max<uint32_t>(4096, count / (threads * 6));
        buildRange(ctx, nodes_, 0, 0, count, 0, &tasks, taskSize);
        std::vector<std::vector<Node>> subs(tasks.size());
        std::atomic<size_t> next{0};
        auto work = [&]() {
            for (;;) {
                size_t k = next.fetch_add(1);
                if (k >= tasks.size()) return;
                auto& t = tasks[k];
                subs[k].reserve(t[2] / 2 + 4);
                subs[k].push_back(Node{});
                buildRange(ctx, subs[k], 0, t[1], t[2], (int)t[3], nullptr, 0);
            }
        };
        std::vector<std::thread> pool;
        for (unsigned i = 1; i < threads; ++i) pool.emplace_back(work);
        work();
        for (auto& th : pool) th.join();
        // Splice subtrees: sub-root replaces the placeholder, the rest is appended.
        for (size_t k = 0; k < tasks.size(); ++k) {
            const std::vector<Node>& sub = subs[k];
            const uint32_t base = (uint32_t)nodes_.size() - 1;  // sub index i>0 maps to base + i
            for (size_t i = 1; i < sub.size(); ++i) {
                Node n = sub[i];
                if (n.count == 0) n.leftOrFirst += base;
                nodes_.push_back(n);
            }
            Node r = sub[0];
            if (r.count == 0) r.leftOrFirst += base;
            nodes_[tasks[k][0]] = r;
        }
    }
    // Convert to global triangle indices.
    for (uint32_t& t : tris_) t += first;
    nodes_.shrink_to_fit();
}

void ModelBVH::build(const Model& m, Progress progress) {
    parts_.assign(m.parts.size(), PartBVH());
    // Largest parts first for better load balancing.
    std::vector<size_t> order(m.parts.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return m.parts[a].indexCount > m.parts[b].indexCount; });
    std::atomic<size_t> next{0};
    std::atomic<uint64_t> doneTris{0};
    std::atomic<bool> cancelled{false};
    const uint64_t total = std::max<uint64_t>(1, m.indices.size() / 3);
    unsigned hw = std::max(1u, std::min(std::thread::hardware_concurrency(), 8u));
    unsigned nThreads = (unsigned)std::min<size_t>(hw, m.parts.size());
    // A dominant part gets the whole pool for its own subtree build.
    const unsigned perPartThreads = nThreads <= 1 ? hw : 1;
    auto worker = [&](bool reporter) {
        for (;;) {
            if (cancelled) return;
            size_t k = next.fetch_add(1);
            if (k >= order.size()) return;
            size_t pi = order[k];
            parts_[pi].build(m, m.parts[pi], perPartThreads);
            doneTris += m.parts[pi].indexCount / 3;
            if (reporter) {
                try {
                    progress.report((float)doneTris.load() / (float)total);
                } catch (const CancelledError&) {
                    cancelled = true;
                }
            }
        }
    };
    std::vector<std::thread> threads;
    for (unsigned i = 1; i < nThreads; ++i) threads.emplace_back(worker, false);
    worker(true);
    for (auto& t : threads) t.join();
    if (cancelled) throw CancelledError();
}

}  // namespace fsv
