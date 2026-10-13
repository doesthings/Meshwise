#include "model.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <numeric>
#include <thread>
#include <unordered_map>

#include "parallel.h"
#include "weld.h"

namespace fsv {

namespace {

bool finite3(const float* p) { return std::isfinite(p[0]) && std::isfinite(p[1]) && std::isfinite(p[2]); }

Vec3 loadVec(const std::vector<float>& a, uint32_t i) { return {a[3 * i], a[3 * i + 1], a[3 * i + 2]}; }

// Output buffers for one processed part.
struct PartOutput {
    std::vector<float> positions, normals;
    std::vector<uint32_t> colors;
    std::vector<uint32_t> indices;  // local
    std::vector<uint32_t> posIds;   // local welded ids
    std::vector<uint32_t> edges;    // local render-vertex line list (CAD)
    uint32_t positionCount = 0;
    uint64_t dropped = 0;
    bool hasColors = false;
};

// Welds the raw positions (unless already unique) and returns triangle indices into `P`.
void weldPart(RawPart& rp, std::vector<float>& P, std::vector<uint32_t>& T, std::vector<uint32_t>& posColors,
              std::vector<uint32_t>& remap, uint64_t& dropped, Progress& prog) {
    const bool soup = rp.indices.empty();
    const size_t nIn = rp.positions.size() / 3;
    const size_t triCount = soup ? nIn / 3 : rp.indices.size() / 3;

    if (rp.welded && !soup) {
        P = std::move(rp.positions);
        T = std::move(rp.indices);
        if (!rp.vertexColors.empty()) posColors = std::move(rp.vertexColors);
        remap.clear();
        return;
    }

    // remap: raw position index -> welded index (UINT32_MAX for invalid).
    remap.assign(nIn, UINT32_MAX);
    PositionWelder welder(P, std::max<size_t>(16, soup ? triCount : nIn));
    const bool hasVC = !rp.vertexColors.empty();
    for (size_t i = 0; i < nIn; ++i) {
        const float* p = &rp.positions[3 * i];
        if (!finite3(p)) continue;
        uint32_t id = welder.insert(p);
        remap[i] = id;
        if (hasVC) {
            if (posColors.size() <= id) posColors.resize(id + 1, 0xFFFFFFFFu);
            posColors[id] = rp.vertexColors[i];
        }
        if ((i & 0xFFFF) == 0) prog.report((float)i / (float)nIn);
    }
    T.resize(triCount * 3);
    for (size_t t = 0; t < triCount; ++t) {
        for (int c = 0; c < 3; ++c) {
            uint32_t src = soup ? (uint32_t)(t * 3 + c) : rp.indices[t * 3 + c];
            T[t * 3 + c] = src < nIn ? remap[src] : UINT32_MAX;
        }
    }
    (void)dropped;
    std::vector<float>().swap(rp.positions);
}

// Removes faces that reference invalid positions or collapse to a line/point. Keeps the
// face colour array in sync.
void dropDegenerate(std::vector<uint32_t>& T, std::vector<uint32_t>& faceColors, uint64_t& dropped) {
    size_t n = T.size() / 3, w = 0;
    const bool fc = !faceColors.empty();
    for (size_t t = 0; t < n; ++t) {
        uint32_t a = T[3 * t], b = T[3 * t + 1], c = T[3 * t + 2];
        if (a == UINT32_MAX || b == UINT32_MAX || c == UINT32_MAX || a == b || b == c || a == c) {
            ++dropped;
            continue;
        }
        T[3 * w] = a; T[3 * w + 1] = b; T[3 * w + 2] = c;
        if (fc) faceColors[w] = faceColors[t];
        ++w;
    }
    T.resize(w * 3);
    if (fc) faceColors.resize(w);
}

// Fast acos for corner weights (max error ~1e-4 rad).
inline float fastAcos(float x) {
    x = std::clamp(x, -1.f, 1.f);
    float ax = std::fabs(x);
    float r = std::sqrt(1.f - ax) * (1.5707288f + ax * (-0.2121144f + ax * (0.0742610f - 0.0187293f * ax)));
    return x < 0 ? kPi - r : r;
}

// Computes crease-aware smooth normals and emits render vertices. Vertex ranges are processed
// in parallel; each corner belongs to exactly one vertex so writes never overlap.
void emitWithCreaseNormals(const std::vector<float>& P, const std::vector<uint32_t>& T,
                           const std::vector<uint32_t>& faceColors, const std::vector<uint32_t>& posColors,
                           const ProcessOptions& opt, PartOutput& out, Progress prog) {
    const size_t nv = P.size() / 3;
    const size_t nf = T.size() / 3;
    const bool fullySmooth = opt.smooth && opt.creaseAngleDeg >= 179.f;
    const float cosT = !opt.smooth ? 0.99999f : std::cos(std::clamp(opt.creaseAngleDeg, 0.f, 180.f) * kPi / 180.f);
    const bool hasFC = !faceColors.empty();
    const bool hasPC = !posColors.empty();
    out.hasColors = hasFC || hasPC;

    // Face unit normals and corner angles.
    std::vector<Vec3> fn(nf);
    std::vector<float> cornerAng(nf * 3);
    parallelFor(nf, 50000, [&](size_t fb, size_t fe, unsigned) {
        for (size_t f = fb; f < fe; ++f) {
            Vec3 a = loadVec(P, T[3 * f]), b = loadVec(P, T[3 * f + 1]), c = loadVec(P, T[3 * f + 2]);
            Vec3 ab = b - a, bc = c - b, ca = a - c;
            Vec3 n = cross(ab, -ca);
            float l = length(n);
            fn[f] = l > 0 ? n / l : Vec3(0, 0, 0);
            float lab = length(ab), lbc = length(bc), lca = length(ca);
            float d0 = lab * lca, d1 = lab * lbc, d2 = lbc * lca;
            cornerAng[3 * f] = d0 > 0 ? fastAcos(-dot(ab, ca) / d0) : 0.f;
            cornerAng[3 * f + 1] = d1 > 0 ? fastAcos(-dot(ab, bc) / d1) : 0.f;
            cornerAng[3 * f + 2] = d2 > 0 ? fastAcos(-dot(bc, ca) / d2) : 0.f;
        }
    });
    prog.report(0.15f);

    // Vertex -> corner adjacency (CSR).
    std::vector<uint32_t> offs(nv + 1, 0);
    for (uint32_t v : T) offs[v + 1]++;
    for (size_t i = 0; i < nv; ++i) offs[i + 1] += offs[i];
    std::vector<uint32_t> adj(T.size());
    {
        std::vector<uint32_t> fill(offs.begin(), offs.end() - 1);
        for (size_t ci = 0; ci < T.size(); ++ci) adj[fill[T[ci]]++] = (uint32_t)ci;
    }
    prog.report(0.3f);

    out.indices.resize(T.size());
    unsigned nThreads = std::max(1u, std::min(std::thread::hardware_concurrency(), 8u));
    if (nv < 60000) nThreads = 1;
    struct Chunk {
        std::vector<float> positions, normals;
        std::vector<uint32_t> colors, posIds;
    };
    std::vector<Chunk> chunks(nThreads);
    const size_t range = (nv + nThreads - 1) / nThreads;
    std::atomic<bool> cancelled{false};

    auto work = [&](unsigned t) {
        Chunk& ck = chunks[t];
        const size_t v0 = t * range, v1 = std::min(nv, v0 + range);
        if (v0 >= v1) return;
        ck.positions.reserve((v1 - v0) * 3 + (v1 - v0) / 2);
        ck.normals.reserve((v1 - v0) * 3 + (v1 - v0) / 2);
        ck.posIds.reserve((v1 - v0) + (v1 - v0) / 4);
        struct LocalVert { Vec3 n; uint32_t color; uint32_t index; };
        std::vector<LocalVert> local;
        std::vector<Vec3> acc;
        struct Cluster { Vec3 rep; Vec3 sum; };
        std::vector<Cluster> clusters;
        std::vector<uint32_t> clusterOf;
        for (size_t v = v0; v < v1; ++v) {
            const uint32_t b = offs[v], e = offs[v + 1];
            const uint32_t k = e - b;
            if (k == 0) continue;
            acc.assign(k, Vec3(0, 0, 0));
            if (fullySmooth) {
                Vec3 sum(0, 0, 0);
                for (uint32_t i = 0; i < k; ++i) sum += fn[adj[b + i] / 3] * cornerAng[adj[b + i]];
                for (uint32_t i = 0; i < k; ++i) acc[i] = sum;
            } else if (k <= 48) {
                for (uint32_t i = 0; i < k; ++i) {
                    const Vec3& ni = fn[adj[b + i] / 3];
                    Vec3 sum(0, 0, 0);
                    for (uint32_t j = 0; j < k; ++j) {
                        const Vec3& nj = fn[adj[b + j] / 3];
                        if (i == j || dot(ni, nj) >= cosT) sum += nj * cornerAng[adj[b + j]];
                    }
                    acc[i] = sum;
                }
            } else {
                // Large fans: greedy clustering keeps this linear in practice.
                clusters.clear();
                clusterOf.resize(k);
                for (uint32_t i = 0; i < k; ++i) {
                    const Vec3& ni = fn[adj[b + i] / 3];
                    uint32_t found = UINT32_MAX;
                    for (uint32_t c = 0; c < clusters.size(); ++c)
                        if (dot(clusters[c].rep, ni) >= cosT) { found = c; break; }
                    if (found == UINT32_MAX) {
                        found = (uint32_t)clusters.size();
                        clusters.push_back({ni, Vec3(0, 0, 0)});
                    }
                    clusters[found].sum += ni * cornerAng[adj[b + i]];
                    clusterOf[i] = found;
                }
                for (uint32_t i = 0; i < k; ++i) acc[i] = clusters[clusterOf[i]].sum;
            }
            const Vec3 pv = loadVec(P, (uint32_t)v);
            local.clear();
            for (uint32_t i = 0; i < k; ++i) {
                uint32_t ci = adj[b + i];
                uint32_t f = ci / 3;
                Vec3 n = acc[i];
                float l = length(n);
                n = l > 1e-20f ? n / l : (length2(fn[f]) > 0 ? fn[f] : Vec3(0, 0, 1));
                uint32_t col = hasFC ? faceColors[f] : (hasPC ? posColors[v] : 0u);
                uint32_t idx = UINT32_MAX;
                for (const LocalVert& lv : local)
                    if (lv.color == col && dot(lv.n, n) > 0.9995f) { idx = lv.index; break; }
                if (idx == UINT32_MAX) {
                    idx = (uint32_t)ck.posIds.size();
                    ck.positions.push_back(pv.x); ck.positions.push_back(pv.y); ck.positions.push_back(pv.z);
                    ck.normals.push_back(n.x); ck.normals.push_back(n.y); ck.normals.push_back(n.z);
                    ck.posIds.push_back((uint32_t)v);
                    if (out.hasColors) ck.colors.push_back(col);
                    local.push_back({n, col, idx});
                }
                out.indices[ci] = idx;  // chunk-local for now
            }
            if (t == 0 && (v & 0x3FFF) == 0) {
                try {
                    prog.report(0.3f + 0.6f * (float)(v - v0) / (float)std::max<size_t>(1, v1 - v0));
                } catch (const CancelledError&) {
                    cancelled = true;
                    return;
                }
            }
            if (cancelled) return;
        }
    };
    std::vector<std::thread> threads;
    for (unsigned t = 1; t < nThreads; ++t) threads.emplace_back(work, t);
    work(0);
    for (auto& th : threads) th.join();
    if (cancelled) throw CancelledError();

    // Concatenate chunks and rebase chunk-local indices.
    std::vector<uint32_t> base(nThreads + 1, 0);
    for (unsigned t = 0; t < nThreads; ++t) base[t + 1] = base[t] + (uint32_t)chunks[t].posIds.size();
    out.positions.reserve((size_t)base[nThreads] * 3);
    out.normals.reserve((size_t)base[nThreads] * 3);
    out.posIds.reserve(base[nThreads]);
    for (unsigned t = 0; t < nThreads; ++t) {
        Chunk& ck = chunks[t];
        out.positions.insert(out.positions.end(), ck.positions.begin(), ck.positions.end());
        out.normals.insert(out.normals.end(), ck.normals.begin(), ck.normals.end());
        out.posIds.insert(out.posIds.end(), ck.posIds.begin(), ck.posIds.end());
        if (out.hasColors) out.colors.insert(out.colors.end(), ck.colors.begin(), ck.colors.end());
        Chunk().positions.swap(ck.positions);
    }
    if (nThreads > 1)
        parallelFor(T.size(), 200000, [&](size_t b0, size_t b1, unsigned) {
            for (size_t ci = b0; ci < b1; ++ci) out.indices[ci] += base[T[ci] / range];
        });
    out.positionCount = (uint32_t)nv;
    prog.report(1.f);
}

// CAD path: normals are provided per position; positions are split per face already.
void emitWithGivenNormals(RawPart& rp, PartOutput& out, Progress& prog) {
    const size_t n = rp.positions.size() / 3;
    out.positions = std::move(rp.positions);
    out.normals = std::move(rp.normals);
    out.indices = std::move(rp.indices);
    out.edges = std::move(rp.edges);
    if (!rp.vertexColors.empty()) {
        out.colors = std::move(rp.vertexColors);
        out.hasColors = true;
    }
    // Normalise normals and fix invalid ones.
    for (size_t i = 0; i < n; ++i) {
        Vec3 v(out.normals[3 * i], out.normals[3 * i + 1], out.normals[3 * i + 2]);
        float l = length(v);
        if (!(l > 1e-20f) || !std::isfinite(l)) v = Vec3(0, 0, 1); else v = v / l;
        out.normals[3 * i] = v.x; out.normals[3 * i + 1] = v.y; out.normals[3 * i + 2] = v.z;
    }
    // Drop invalid triangles.
    size_t nt = out.indices.size() / 3, w = 0;
    for (size_t t = 0; t < nt; ++t) {
        uint32_t a = out.indices[3 * t], b = out.indices[3 * t + 1], c = out.indices[3 * t + 2];
        if (a >= n || b >= n || c >= n || a == b || b == c || a == c) { ++out.dropped; continue; }
        out.indices[3 * w] = a; out.indices[3 * w + 1] = b; out.indices[3 * w + 2] = c;
        ++w;
    }
    out.indices.resize(w * 3);
    // Drop edge segments that reference missing vertices (FSM files come from users).
    size_t ne = out.edges.size() / 2, we = 0;
    for (size_t e = 0; e < ne; ++e) {
        uint32_t a = out.edges[2 * e], b = out.edges[2 * e + 1];
        if (a >= n || b >= n) continue;
        out.edges[2 * we] = a; out.edges[2 * we + 1] = b;
        ++we;
    }
    out.edges.resize(we * 2);
    // Topological welding for analysis (positions at shared face boundaries are identical).
    std::vector<float> uniq;
    PositionWelder welder(uniq, n);
    out.posIds.resize(n);
    for (size_t i = 0; i < n; ++i) out.posIds[i] = welder.insert(&out.positions[3 * i]);
    out.positionCount = (uint32_t)welder.size();
    prog.report(1.f);
}

void computePartStats(const PartOutput& o, Part& part) {
    const size_t nv = o.positions.size() / 3;
    const size_t nt = o.indices.size() / 3;
    struct Acc { Box3 b; double vol = 0, area = 0, cx = 0, cy = 0, cz = 0; };
    unsigned maxThreads = workerCount();
    std::vector<Acc> acc(maxThreads);
    parallelFor(nv, 100000, [&](size_t b, size_t e, unsigned t) {
        for (size_t i = b; i < e; ++i) acc[t].b.expand(Vec3(o.positions[3 * i], o.positions[3 * i + 1], o.positions[3 * i + 2]));
    });
    Box3 box;
    for (auto& a : acc) box.expand(a.b);
    part.bounds = box;
    const Vec3 ref = box.valid() ? box.center() : Vec3(0, 0, 0);  // improves precision
    parallelFor(nt, 100000, [&](size_t b0, size_t b1, unsigned t) {
        Acc& A = acc[t];
        for (size_t tri = b0; tri < b1; ++tri) {
            Vec3 a = loadVec(o.positions, o.indices[3 * tri]) - ref;
            Vec3 bb = loadVec(o.positions, o.indices[3 * tri + 1]) - ref;
            Vec3 c = loadVec(o.positions, o.indices[3 * tri + 2]) - ref;
            Vec3 cr = cross(bb - a, c - a);
            A.area += 0.5 * std::sqrt((double)cr.x * cr.x + (double)cr.y * cr.y + (double)cr.z * cr.z);
            double v6 = (double)a.x * ((double)bb.y * c.z - (double)bb.z * c.y) -
                        (double)a.y * ((double)bb.x * c.z - (double)bb.z * c.x) +
                        (double)a.z * ((double)bb.x * c.y - (double)bb.y * c.x);
            A.vol += v6;
            A.cx += v6 * (a.x + bb.x + c.x);
            A.cy += v6 * (a.y + bb.y + c.y);
            A.cz += v6 * (a.z + bb.z + c.z);
        }
    });
    double vol = 0, area = 0, cx = 0, cy = 0, cz = 0;
    for (auto& a : acc) { vol += a.vol; area += a.area; cx += a.cx; cy += a.cy; cz += a.cz; }
    part.volume = vol / 6.0;
    part.area = area;
    if (std::fabs(vol) > 1e-12) {
        part.centroid = Vec3((float)(cx / (4.0 * vol)), (float)(cy / (4.0 * vol)), (float)(cz / (4.0 * vol))) + ref;
        // A badly non-closed mesh can put the centroid far outside the part; fall back.
        if (!box.valid() || length(part.centroid - box.center()) > box.radius() * 2.f) part.centroid = box.center();
    } else {
        part.centroid = box.valid() ? box.center() : Vec3(0, 0, 0);
    }
}

}  // namespace

uint32_t defaultPartColor(size_t index, size_t count) {
    if (count <= 1) return packRGBA(0.80f, 0.80f, 0.79f);
    static const uint32_t palette[] = {
        packRGBA(0.80f, 0.80f, 0.79f), packRGBA(0.98f, 0.90f, 0.45f), packRGBA(0.62f, 0.22f, 0.17f),
        packRGBA(0.45f, 0.62f, 0.86f), packRGBA(0.56f, 0.80f, 0.55f), packRGBA(0.93f, 0.62f, 0.36f),
        packRGBA(0.70f, 0.58f, 0.86f), packRGBA(0.40f, 0.76f, 0.78f), packRGBA(0.90f, 0.52f, 0.62f),
        packRGBA(0.62f, 0.62f, 0.60f), packRGBA(0.85f, 0.75f, 0.60f), packRGBA(0.35f, 0.45f, 0.55f),
    };
    return palette[index % (sizeof(palette) / sizeof(palette[0]))];
}

double Model::totalVolume() const {
    double v = 0;
    for (const Part& p : parts) v += p.volume;
    return v;
}

double Model::totalArea() const {
    double a = 0;
    for (const Part& p : parts) a += p.area;
    return a;
}

std::unique_ptr<Model> buildModel(RawModel&& raw, const ProcessOptions& opt, Progress progress) {
    auto model = std::make_unique<Model>();
    model->format = raw.format;
    model->warnings = std::move(raw.warnings);

    // Drop empty parts up front.
    std::vector<RawPart*> parts;
    size_t totalWork = 0;
    for (RawPart& rp : raw.parts) {
        if (rp.positions.size() < 9) continue;
        parts.push_back(&rp);
        totalWork += rp.indices.empty() ? rp.positions.size() / 3 : rp.indices.size();
    }
    if (parts.empty()) throw std::runtime_error("The file contains no triangles");
    if (parts.size() > 65535) throw std::runtime_error("Too many parts (limit is 65535)");

    const bool anyColor = std::any_of(parts.begin(), parts.end(), [](const RawPart* p) {
        return !p->faceColors.empty() || !p->vertexColors.empty();
    });

    size_t done = 0;
    uint32_t posBase = 0;
    for (size_t pi = 0; pi < parts.size(); ++pi) {
        RawPart& rp = *parts[pi];
        size_t work = rp.indices.empty() ? rp.positions.size() / 3 : rp.indices.size();
        float b0 = totalWork ? (float)done / (float)totalWork : 0.f;
        float b1 = totalWork ? (float)(done + work) / (float)totalWork : 1.f;
        done += work;
        Progress prog = progress.sub(b0, b1);

        PartOutput out;
        if (!rp.normals.empty() && !rp.indices.empty() && rp.normals.size() == rp.positions.size()) {
            emitWithGivenNormals(rp, out, prog);
        } else {
            std::vector<float> P;
            std::vector<uint32_t> T, posColors, remap;
            Progress weldProg = prog.sub(0.f, 0.4f);
            weldPart(rp, P, T, posColors, remap, out.dropped, weldProg);
            dropDegenerate(T, rp.faceColors, out.dropped);
            if (T.empty()) {
                model->droppedFaces += out.dropped;
                continue;
            }
            emitWithCreaseNormals(P, T, rp.faceColors, posColors, opt, out, prog.sub(0.4f, 1.f));
        }
        if (out.indices.empty()) {
            model->droppedFaces += out.dropped;
            continue;
        }

        Part part;
        part.name = rp.name;
        part.hasFileColor = rp.color != 0;
        part.color = rp.color;
        part.hasVertexColors = out.hasColors;
        computePartStats(out, part);

        const uint32_t vbase = (uint32_t)model->partIds.size();
        const uint16_t pid = (uint16_t)model->parts.size();
        part.firstVertex = vbase;
        part.vertexCount = (uint32_t)(out.positions.size() / 3);
        part.firstIndex = (uint32_t)model->indices.size();
        part.indexCount = (uint32_t)out.indices.size();

        auto append = [](auto& dst, auto& src) {
            if (dst.empty()) dst = std::move(src);
            else dst.insert(dst.end(), src.begin(), src.end());
        };
        append(model->positions, out.positions);
        append(model->normals, out.normals);
        model->partIds.insert(model->partIds.end(), part.vertexCount, pid);
        if (anyColor) {
            if (out.hasColors) append(model->colors, out.colors);
            else model->colors.insert(model->colors.end(), part.vertexCount, 0xFFFFFFFFu);
        }
        if (vbase == 0 && model->indices.empty()) {
            model->indices = std::move(out.indices);
        } else {
            size_t at = model->indices.size();
            model->indices.resize(at + out.indices.size());
            for (size_t i = 0; i < out.indices.size(); ++i) model->indices[at + i] = out.indices[i] + vbase;
        }
        if (posBase == 0 && model->posIds.empty()) {
            model->posIds = std::move(out.posIds);
        } else {
            size_t at = model->posIds.size();
            model->posIds.resize(at + out.posIds.size());
            for (size_t i = 0; i < out.posIds.size(); ++i) model->posIds[at + i] = out.posIds[i] + posBase;
        }
        for (uint32_t i : out.edges) model->cadEdges.push_back(i + vbase);
        posBase += out.positionCount;
        model->droppedFaces += out.dropped;
        model->bounds.expand(part.bounds);
        model->parts.push_back(std::move(part));
    }
    if (model->parts.empty()) throw std::runtime_error("The file contains no valid triangles");
    model->positionCount = posBase;

    // Names and default colours.
    const size_t n = model->parts.size();
    for (size_t i = 0; i < n; ++i) {
        Part& p = model->parts[i];
        if (p.name.empty()) p.name = n == 1 ? "Body" : "Part " + std::to_string(i + 1);
        if (!p.hasFileColor) p.color = defaultPartColor(i, n);
        p.color |= 0xFF000000u;
    }
    progress.report(1.f);
    return model;
}

std::unique_ptr<Model> mergeModels(std::vector<std::unique_ptr<Model>>&& models, bool arrange) {
    if (models.empty()) throw std::runtime_error("Nothing to merge");
    if (models.size() == 1) return std::move(models[0]);

    // Decide whether to arrange: heavy overlap of the per-model boxes.
    std::vector<Vec3> offsets(models.size(), Vec3(0, 0, 0));
    if (arrange) {
        double overlapSum = 0, pairs = 0;
        for (size_t i = 0; i < models.size(); ++i)
            for (size_t j = i + 1; j < models.size(); ++j) {
                const Box3 &a = models[i]->bounds, &b = models[j]->bounds;
                Vec3 lo = vmax(a.min, b.min), hi = vmin(a.max, b.max);
                double inter = std::max(0.f, hi.x - lo.x) * (double)std::max(0.f, hi.y - lo.y) *
                               std::max(0.f, hi.z - lo.z);
                Vec3 sa = a.size(), sb = b.size();
                double va = (double)sa.x * sa.y * sa.z, vb = (double)sb.x * sb.y * sb.z;
                double smaller = std::max(1e-12, std::min(va, vb));
                overlapSum += inter / smaller;
                pairs += 1;
            }
        if (pairs > 0 && overlapSum / pairs > 0.3) {
            // Simple shelf packing along X, wrapping rows to keep the layout roughly square.
            double totalArea = 0;
            float gap = 0;
            for (auto& m : models) {
                Vec3 s = m->bounds.size();
                totalArea += (double)(s.x + 1) * (s.y + 1);
                gap = std::max(gap, std::max(s.x, s.y));
            }
            gap *= 0.08f;
            float rowWidth = (float)std::sqrt(totalArea) * 1.3f;
            float x = 0, y = 0, rowDepth = 0;
            for (size_t i = 0; i < models.size(); ++i) {
                const Box3& b = models[i]->bounds;
                Vec3 s = b.size();
                if (x > 0 && x + s.x > rowWidth) { x = 0; y += rowDepth + gap; rowDepth = 0; }
                offsets[i] = Vec3(x - b.min.x, y - b.min.y, -b.min.z);
                x += s.x + gap;
                rowDepth = std::max(rowDepth, s.y);
            }
        }
    }

    auto out = std::make_unique<Model>();
    out->format = models[0]->format;
    bool anyColors = false;
    size_t totalParts = 0;
    for (auto& m : models) {
        anyColors |= !m->colors.empty();
        totalParts += m->parts.size();
    }
    if (totalParts > 65535) throw std::runtime_error("Too many parts (limit is 65535)");
    uint32_t posBase = 0;
    for (size_t mi = 0; mi < models.size(); ++mi) {
        Model& m = *models[mi];
        const Vec3 off = offsets[mi];
        const uint32_t vbase = (uint32_t)out->partIds.size();
        const uint16_t pbase = (uint16_t)out->parts.size();
        size_t nv = m.partIds.size();
        out->positions.reserve(out->positions.size() + nv * 3);
        for (size_t i = 0; i < nv; ++i) {
            out->positions.push_back(m.positions[3 * i] + off.x);
            out->positions.push_back(m.positions[3 * i + 1] + off.y);
            out->positions.push_back(m.positions[3 * i + 2] + off.z);
        }
        out->normals.insert(out->normals.end(), m.normals.begin(), m.normals.end());
        for (uint16_t p : m.partIds) out->partIds.push_back((uint16_t)(p + pbase));
        if (anyColors) {
            if (!m.colors.empty()) out->colors.insert(out->colors.end(), m.colors.begin(), m.colors.end());
            else out->colors.insert(out->colors.end(), nv, 0xFFFFFFFFu);
        }
        const uint32_t ibase = (uint32_t)out->indices.size();
        for (uint32_t i : m.indices) out->indices.push_back(i + vbase);
        for (uint32_t i : m.posIds) out->posIds.push_back(i + posBase);
        for (uint32_t i : m.cadEdges) out->cadEdges.push_back(i + vbase);
        posBase += m.positionCount;
        for (Part p : m.parts) {
            p.firstVertex += vbase;
            p.firstIndex += ibase;
            p.bounds = p.bounds.translated(off);
            p.centroid += off;
            if (m.parts.size() == 1 && !m.name.empty()) p.name = m.name;
            out->bounds.expand(p.bounds);
            out->parts.push_back(std::move(p));
        }
        out->droppedFaces += m.droppedFaces;
        out->fileSize += m.fileSize;
        for (auto& w : m.warnings) out->warnings.push_back(w);
    }
    out->positionCount = posBase;
    // Re-colour parts that only had the single-part default colour.
    const size_t n = out->parts.size();
    for (size_t i = 0; i < n; ++i)
        if (!out->parts[i].hasFileColor) out->parts[i].color = defaultPartColor(i, n) | 0xFF000000u;
    return out;
}

std::unique_ptr<Topology> computeTopology(const Model& m, float featureAngleDeg, Progress progress) {
    auto topo = std::make_unique<Topology>();
    const size_t nf = m.triangleCount();
    const uint32_t np = m.positionCount;
    if (nf == 0 || np == 0) return topo;
    const uint32_t* I = m.indices.data();
    const uint32_t* PID = m.posIds.data();

    // Position -> incident corner list (CSR). Corner id = face * 3 + corner.
    std::vector<uint32_t> offs(np + 1, 0);
    for (size_t f = 0; f < nf; ++f) {
        uint32_t pa = PID[I[3 * f]], pb = PID[I[3 * f + 1]], pc = PID[I[3 * f + 2]];
        if (pa == pb || pb == pc || pa == pc) { topo->degenerateFaces++; continue; }
        offs[pa + 1]++; offs[pb + 1]++; offs[pc + 1]++;
    }
    for (uint32_t i = 0; i < np; ++i) offs[i + 1] += offs[i];
    std::vector<uint32_t> corners(offs[np]);
    {
        std::vector<uint32_t> fill(offs.begin(), offs.end() - 1);
        for (size_t f = 0; f < nf; ++f) {
            uint32_t pa = PID[I[3 * f]], pb = PID[I[3 * f + 1]], pc = PID[I[3 * f + 2]];
            if (pa == pb || pb == pc || pa == pc) continue;
            corners[fill[pa]++] = (uint32_t)(3 * f);
            corners[fill[pb]++] = (uint32_t)(3 * f + 1);
            corners[fill[pc]++] = (uint32_t)(3 * f + 2);
        }
    }
    progress.report(0.2f);

    const float cosF = std::cos(featureAngleDeg * kPi / 180.f);
    const bool useCadEdges = !m.cadEdges.empty();
    const bool wire = nf <= 4000000;
    auto faceNormal = [&](uint32_t f) {
        Vec3 a = m.vertex(I[3 * f]), b = m.vertex(I[3 * f + 1]), c = m.vertex(I[3 * f + 2]);
        return normalize(cross(b - a, c - a));
    };

    struct Local {
        std::vector<uint32_t> all, open, nonManifold, feature;
        uint64_t edges = 0, flipped = 0;
    };
    unsigned nThreads = std::max(1u, std::min(std::thread::hardware_concurrency(), 8u));
    if (nf < 20000) nThreads = 1;
    std::vector<Local> locals(nThreads);
    std::atomic<uint32_t> next{0}, done{0};
    std::atomic<bool> cancelled{false};
    const uint32_t chunk = 4096;

    // Each edge (a, b) with a < b is handled once, from vertex a.
    auto worker = [&](unsigned tid) {
        Local& L = locals[tid];
        struct HalfEdge { uint32_t w, face, va, vb; int8_t dir; };
        std::vector<HalfEdge> he;
        for (;;) {
            if (cancelled) return;
            uint32_t start = next.fetch_add(chunk);
            if (start >= np) return;
            uint32_t end = std::min(np, start + chunk);
            for (uint32_t v = start; v < end; ++v) {
                he.clear();
                for (uint32_t k = offs[v]; k < offs[v + 1]; ++k) {
                    uint32_t ci = corners[k];
                    uint32_t f = ci / 3, c = ci % 3;
                    uint32_t vSelf = I[3 * f + c];
                    uint32_t vNext = I[3 * f + (c + 1) % 3];
                    uint32_t vPrev = I[3 * f + (c + 2) % 3];
                    uint32_t pn = PID[vNext], pp = PID[vPrev];
                    if (pn > v) he.push_back({pn, f, vSelf, vNext, 1});   // edge v -> next
                    if (pp > v) he.push_back({pp, f, vPrev, vSelf, -1});  // edge prev -> v
                }
                std::sort(he.begin(), he.end(), [](const HalfEdge& a, const HalfEdge& b) { return a.w < b.w; });
                for (size_t i = 0; i < he.size();) {
                    size_t j = i + 1;
                    while (j < he.size() && he[j].w == he[i].w) ++j;
                    const size_t count = j - i;
                    const HalfEdge& e = he[i];
                    L.edges++;
                    if (wire) { L.all.push_back(e.va); L.all.push_back(e.vb); }
                    if (count == 1) {
                        L.open.push_back(e.va);
                        L.open.push_back(e.vb);
                        if (!useCadEdges) { L.feature.push_back(e.va); L.feature.push_back(e.vb); }
                    } else if (count > 2) {
                        L.nonManifold.push_back(e.va);
                        L.nonManifold.push_back(e.vb);
                    } else {
                        if (he[i].dir == he[i + 1].dir) L.flipped++;
                        if (!useCadEdges && dot(faceNormal(he[i].face), faceNormal(he[i + 1].face)) < cosF) {
                            L.feature.push_back(e.va);
                            L.feature.push_back(e.vb);
                        }
                    }
                    i = j;
                }
            }
            uint32_t d = done.fetch_add(end - start) + (end - start);
            if (tid == 0) {
                try {
                    progress.report(0.2f + 0.65f * (float)d / (float)np);
                } catch (const CancelledError&) {
                    cancelled = true;
                }
            }
        }
    };
    std::vector<std::thread> threads;
    for (unsigned t = 1; t < nThreads; ++t) threads.emplace_back(worker, t);
    worker(0);
    for (auto& t : threads) t.join();
    if (cancelled) throw CancelledError();

    if (useCadEdges) topo->featureEdges = m.cadEdges;
    for (Local& L : locals) {
        topo->edgeCount += L.edges;
        topo->flippedEdges += L.flipped;
        topo->allEdges.insert(topo->allEdges.end(), L.all.begin(), L.all.end());
        topo->openEdges.insert(topo->openEdges.end(), L.open.begin(), L.open.end());
        topo->nonManifoldEdges.insert(topo->nonManifoldEdges.end(), L.nonManifold.begin(), L.nonManifold.end());
        topo->featureEdges.insert(topo->featureEdges.end(), L.feature.begin(), L.feature.end());
    }

    // Shells: connected components of positions (union-find).
    std::vector<uint32_t> parent(np);
    std::iota(parent.begin(), parent.end(), 0u);
    auto find = [&](uint32_t x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    };
    auto unite = [&](uint32_t a, uint32_t b) {
        a = find(a); b = find(b);
        if (a != b) parent[a] = b;
    };
    std::vector<uint8_t> used(np, 0);
    for (size_t f = 0; f < nf; ++f) {
        uint32_t pa = PID[I[3 * f]], pb = PID[I[3 * f + 1]], pc = PID[I[3 * f + 2]];
        if (pa == pb || pb == pc || pa == pc) continue;
        unite(pa, pb);
        unite(pb, pc);
        used[pa] = used[pb] = used[pc] = 1;
    }
    uint32_t shells = 0;
    for (uint32_t p = 0; p < np; ++p)
        if (used[p] && find(p) == p) ++shells;
    topo->shellCount = shells;
    progress.report(1.f);
    return topo;
}

}  // namespace fsv
