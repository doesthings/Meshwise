#include "analysis.h"

#include <algorithm>
#include <atomic>
#include <thread>

namespace fsv {

ThicknessResult computeThickness(const Model& m, const ModelBVH& bvh, Progress progress) {
    ThicknessResult res;
    const size_t nv = m.vertexCount();
    const size_t nf = m.triangleCount();
    res.perVertex.assign(nv, 0.f);
    res.maxDistance = std::max(1e-3f, length(m.bounds.size()));
    if (nv == 0 || nf == 0) return res;

    // One ray per triangle, from its centroid against the inward face normal. Sampling faces
    // (rather than vertices with averaged normals) stays accurate on CAD-style meshes whose
    // vertices all sit on sharp edges.
    std::vector<float> faceT(nf, res.maxDistance);
    std::atomic<uint32_t> next{0};
    std::atomic<uint32_t> done{0};
    std::atomic<bool> cancelled{false};
    const uint32_t chunk = 2048;
    const float eps = res.maxDistance * 2e-6f;
    const uint32_t* I = m.indices.data();
    auto worker = [&](bool reporter) {
        for (;;) {
            if (cancelled) return;
            uint32_t start = next.fetch_add(chunk);
            if (start >= nf) return;
            uint32_t end = std::min((uint32_t)nf, start + chunk);
            for (uint32_t f = start; f < end; ++f) {
                Vec3 a = m.vertex(I[3 * f]), b = m.vertex(I[3 * f + 1]), c = m.vertex(I[3 * f + 2]);
                Vec3 n = cross(b - a, c - a);
                float l = length(n);
                if (!(l > 0.f)) continue;
                n = n / l;
                const int part = m.partIds[I[3 * f]];
                // Inside-out parts (negative volume) have inverted normals.
                if (m.parts[(size_t)part].volume < 0) n = -n;
                Vec3 o = (a + b + c) * (1.f / 3.f);
                Ray ray{o - n * eps, -n};
                Hit hit;
                auto notSelf = [f](uint32_t tri, float) { return tri != f; };
                if (bvh.part((size_t)part).intersect(m, ray, 0.f, res.maxDistance, hit, notSelf)) faceT[f] = hit.t + eps;
            }
            uint32_t d = done.fetch_add(end - start) + (end - start);
            if (reporter) {
                try {
                    progress.report((float)d / (float)nf);
                } catch (const CancelledError&) {
                    cancelled = true;
                }
            }
        }
    };
    unsigned nThreads = std::max(1u, std::min(std::thread::hardware_concurrency(), 8u));
    std::vector<std::thread> threads;
    for (unsigned i = 1; i < nThreads; ++i) threads.emplace_back(worker, false);
    worker(true);
    for (auto& t : threads) t.join();
    if (cancelled) throw CancelledError();

    // Vertex value: thinnest face touching the welded position (conservative for printing).
    std::vector<float> posT(m.positionCount, res.maxDistance);
    for (size_t f = 0; f < nf; ++f)
        for (int k = 0; k < 3; ++k) {
            uint32_t p = m.posIds[I[3 * f + k]];
            posT[p] = std::min(posT[p], faceT[f]);
        }
    for (size_t v = 0; v < nv; ++v) res.perVertex[v] = posT[m.posIds[v]];

    // Statistics over measured faces.
    std::vector<float> vals;
    vals.reserve(nf);
    for (size_t f = 0; f < nf; ++f)
        if (faceT[f] < res.maxDistance * 0.999f) vals.push_back(faceT[f]);
    if (vals.empty()) {
        res.minValue = res.maxValue = res.p05 = res.p95 = res.maxDistance;
        return res;
    }
    auto pick = [&](float q) {
        size_t k = std::min(vals.size() - 1, (size_t)(q * (float)(vals.size() - 1)));
        std::nth_element(vals.begin(), vals.begin() + (long)k, vals.end());
        return vals[k];
    };
    res.p05 = pick(0.02f);
    res.p95 = pick(0.95f);
    res.minValue = *std::min_element(vals.begin(), vals.end());
    res.maxValue = *std::max_element(vals.begin(), vals.end());
    if (res.p95 <= res.p05) res.p95 = res.p05 + std::max(1e-3f, res.p05 * 0.1f);

    // Thinnest spots: greedy selection with a minimum separation.
    std::vector<uint32_t> order;
    order.reserve(nf);
    for (uint32_t f = 0; f < nf; ++f)
        if (faceT[f] < res.maxDistance * 0.999f) order.push_back(f);
    size_t keep = std::min<size_t>(order.size(), 4096);
    std::partial_sort(order.begin(), order.begin() + (long)keep, order.end(),
                      [&](uint32_t a, uint32_t b) { return faceT[a] < faceT[b]; });
    const float sep = res.maxDistance * 0.08f;
    for (size_t i = 0; i < keep && res.thinnest.size() < 3; ++i) {
        uint32_t f = order[i];
        Vec3 a = m.vertex(I[3 * f]), b = m.vertex(I[3 * f + 1]), c = m.vertex(I[3 * f + 2]);
        Vec3 pos = (a + b + c) * (1.f / 3.f);
        bool far = true;
        for (const auto& s : res.thinnest)
            if (length(s.position - pos) < sep) { far = false; break; }
        if (!far) continue;
        res.thinnest.push_back({pos, normalize(cross(b - a, c - a)), faceT[f], (int)m.partIds[I[3 * f]]});
    }
    progress.report(1.f);
    return res;
}

OverhangStats computeOverhang(const Model& m, float angleDeg, const std::vector<uint8_t>& partVisible) {
    OverhangStats s;
    const float limit = -std::sin(angleDeg * kPi / 180.f);  // n.z below this => overhang
    const float bedZ = m.bounds.min.z;
    const float bedTol = std::max(1e-3f, length(m.bounds.size()) * 1e-4f);
    const uint32_t* I = m.indices.data();
    for (size_t pi = 0; pi < m.parts.size(); ++pi) {
        if (!partVisible.empty() && !partVisible[pi]) continue;
        const Part& part = m.parts[pi];
        const bool flip = part.volume < 0;
        for (uint32_t t = part.firstIndex / 3; t < (part.firstIndex + part.indexCount) / 3; ++t) {
            Vec3 a = m.vertex(I[3 * t]), b = m.vertex(I[3 * t + 1]), c = m.vertex(I[3 * t + 2]);
            Vec3 cr = cross(b - a, c - a);
            float l = length(cr);
            if (l <= 0) continue;
            Vec3 n = cr / l;
            if (flip) n = -n;
            double area = 0.5 * l;
            s.totalArea += area;
            bool onBed = a.z - bedZ < bedTol && b.z - bedZ < bedTol && c.z - bedZ < bedTol;
            if (n.z < limit && !onBed) s.overhangArea += area;
        }
    }
    return s;
}

bool circleFrom3(const Vec3& a, const Vec3& b, const Vec3& c, Vec3& center, Vec3& normal, float& radius) {
    Vec3 ab = b - a, ac = c - a;
    Vec3 n = cross(ab, ac);
    float n2 = length2(n);
    float scale = std::max(length2(ab), length2(ac));
    if (n2 <= 1e-12f * scale * scale || scale <= 0) return false;
    // Circumcentre formula.
    Vec3 toCenter = (cross(n, ab) * length2(ac) + cross(ac, n) * length2(ab)) / (2.f * n2);
    center = a + toCenter;
    radius = length(toCenter);
    normal = n / std::sqrt(n2);
    return true;
}

}  // namespace fsv
