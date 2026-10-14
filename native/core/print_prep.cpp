#include "print_prep.h"

#include <algorithm>
#include <cmath>

#include "parallel.h"

namespace fsv {

namespace {

Vec3 posAt(const Model& m, uint32_t v) { return m.vertex(v); }

void setPos(Model& m, uint32_t v, const Vec3& p) {
    m.positions[3 * v] = p.x;
    m.positions[3 * v + 1] = p.y;
    m.positions[3 * v + 2] = p.z;
}

void setNormal(Model& m, uint32_t v, const Vec3& n) {
    m.normals[3 * v] = n.x;
    m.normals[3 * v + 1] = n.y;
    m.normals[3 * v + 2] = n.z;
}

// Bounds, area, signed volume and volume centroid of one part (same maths as loading).
void partStats(const Model& m, Part& part) {
    Box3 box;
    for (uint32_t v = part.firstVertex; v < part.firstVertex + part.vertexCount; ++v) box.expand(posAt(m, v));
    part.bounds = box;
    const Vec3 ref = box.valid() ? box.center() : Vec3(0, 0, 0);
    double vol = 0, area = 0, cx = 0, cy = 0, cz = 0;
    for (uint32_t t = part.firstIndex / 3; t < (part.firstIndex + part.indexCount) / 3; ++t) {
        Vec3 a = posAt(m, m.indices[3 * t]) - ref;
        Vec3 b = posAt(m, m.indices[3 * t + 1]) - ref;
        Vec3 c = posAt(m, m.indices[3 * t + 2]) - ref;
        Vec3 cr = cross(b - a, c - a);
        area += 0.5 * std::sqrt((double)cr.x * cr.x + (double)cr.y * cr.y + (double)cr.z * cr.z);
        double v6 = (double)a.x * ((double)b.y * c.z - (double)b.z * c.y) - (double)a.y * ((double)b.x * c.z - (double)b.z * c.x) +
                    (double)a.z * ((double)b.x * c.y - (double)b.y * c.x);
        vol += v6;
        cx += v6 * (a.x + b.x + c.x);
        cy += v6 * (a.y + b.y + c.y);
        cz += v6 * (a.z + b.z + c.z);
    }
    part.volume = vol / 6.0;
    part.area = area;
    if (std::fabs(vol) > 1e-12) {
        part.centroid = Vec3((float)(cx / (4.0 * vol)), (float)(cy / (4.0 * vol)), (float)(cz / (4.0 * vol))) + ref;
        if (!box.valid() || length(part.centroid - box.center()) > box.radius() * 2.f) part.centroid = box.center();
    } else {
        part.centroid = box.valid() ? box.center() : Vec3(0, 0, 0);
    }
}

// Outward normal of a triangle (parts with negative volume are inside out).
Vec3 outwardNormal(const Model& m, uint32_t t, bool flip, double& area) {
    Vec3 a = m.vertex(m.indices[3 * t]), b = m.vertex(m.indices[3 * t + 1]), c = m.vertex(m.indices[3 * t + 2]);
    Vec3 cr = cross(b - a, c - a);
    float l = length(cr);
    area = 0.5 * (double)l;
    if (l <= 0) return Vec3(0, 0, 0);
    return flip ? -(cr / l) : cr / l;
}

}  // namespace

std::unique_ptr<Model> transformModel(const Model& src, const float A[12], bool placeOnBed, Vec3* applied) {
    auto m = std::make_unique<Model>(src);
    // Normals use the cofactor matrix (inverse transpose up to scale); a mirror flips them,
    // and the winding is flipped below so faces stay outward.
    const float c00 = A[5] * A[10] - A[6] * A[9], c01 = A[6] * A[8] - A[4] * A[10], c02 = A[4] * A[9] - A[5] * A[8];
    const float c10 = A[2] * A[9] - A[1] * A[10], c11 = A[0] * A[10] - A[2] * A[8], c12 = A[1] * A[8] - A[0] * A[9];
    const float c20 = A[1] * A[6] - A[2] * A[5], c21 = A[2] * A[4] - A[0] * A[6], c22 = A[0] * A[5] - A[1] * A[4];
    const float det = A[0] * c00 + A[1] * c01 + A[2] * c02;
    const float sgn = det < 0 ? -1.f : 1.f;
    const size_t nv = m->vertexCount();
    Model& M = *m;
    parallelFor(nv, 100000, [&](size_t b, size_t e, unsigned) {
        for (size_t i = b; i < e; ++i) {
            Vec3 p = M.vertex((uint32_t)i);
            setPos(M, (uint32_t)i, Vec3(A[0] * p.x + A[1] * p.y + A[2] * p.z + A[3], A[4] * p.x + A[5] * p.y + A[6] * p.z + A[7],
                                        A[8] * p.x + A[9] * p.y + A[10] * p.z + A[11]));
            Vec3 n = M.normal((uint32_t)i);
            Vec3 tn(c00 * n.x + c01 * n.y + c02 * n.z, c10 * n.x + c11 * n.y + c12 * n.z, c20 * n.x + c21 * n.y + c22 * n.z);
            setNormal(M, (uint32_t)i, normalize(tn) * sgn);
        }
    });
    if (det < 0)
        for (size_t t = 0; t < M.triangleCount(); ++t) std::swap(M.indices[3 * t + 1], M.indices[3 * t + 2]);
    for (Part& p : M.parts) partStats(M, p);
    M.bounds = Box3();
    for (const Part& p : M.parts) M.bounds.expand(p.bounds);

    Vec3 off(0, 0, 0);
    if (placeOnBed && M.bounds.valid()) {
        Vec3 c = M.bounds.center();
        off = Vec3(-c.x, -c.y, -M.bounds.min.z);
        parallelFor(nv, 100000, [&](size_t b, size_t e, unsigned) {
            for (size_t i = b; i < e; ++i) setPos(M, (uint32_t)i, M.vertex((uint32_t)i) + off);
        });
        for (Part& p : M.parts) {
            p.bounds = p.bounds.translated(off);
            p.centroid += off;
        }
        M.bounds = M.bounds.translated(off);
    }
    if (applied) *applied = off;
    return m;
}

OrientationStats orientationStats(const Model& m, const Vec3& downIn, float overhangDeg) {
    OrientationStats s;
    const Vec3 d = normalize(downIn);
    const Vec3 u = -d;
    s.down = d;
    const size_t nv = m.vertexCount();
    if (nv == 0) return s;
    struct Range { float lo = std::numeric_limits<float>::max(), hi = -std::numeric_limits<float>::max(); };
    std::vector<Range> ranges(workerCount());
    parallelFor(nv, 200000, [&](size_t b, size_t e, unsigned t) {
        Range& r = ranges[t];
        for (size_t i = b; i < e; ++i) {
            float h = dot(m.vertex((uint32_t)i), u);
            r.lo = std::min(r.lo, h);
            r.hi = std::max(r.hi, h);
        }
    });
    float lo = std::numeric_limits<float>::max(), hi = -lo;
    for (const Range& r : ranges) { lo = std::min(lo, r.lo); hi = std::max(hi, r.hi); }
    const float diag = std::max(1e-6f, length(m.bounds.size()));
    const float tol = std::max(1e-3f, diag * 2e-4f);
    const float limit = -std::sin(overhangDeg * kPi / 180.f);
    const float flat = -std::cos(2.f * kPi / 180.f);
    struct Acc { double total = 0, over = 0, contact = 0; };
    std::vector<Acc> acc(workerCount());
    for (const Part& part : m.parts) {
        const bool flip = part.volume < 0;
        const uint32_t t0 = part.firstIndex / 3, t1 = (part.firstIndex + part.indexCount) / 3;
        parallelFor(t1 - t0, 100000, [&](size_t b, size_t e, unsigned th) {
            Acc& A = acc[th];
            for (size_t k = b; k < e; ++k) {
                uint32_t t = t0 + (uint32_t)k;
                double area;
                Vec3 n = outwardNormal(m, t, flip, area);
                if (area <= 0) continue;
                A.total += area;
                float nu = dot(n, u);
                bool onBed = true;
                for (int c = 0; c < 3 && onBed; ++c) onBed = dot(m.vertex(m.indices[3 * t + c]), u) - lo < tol;
                if (onBed && nu < flat) A.contact += area;
                else if (!onBed && nu < limit) A.over += area;
            }
        });
    }
    for (const Acc& a : acc) {
        s.totalArea += a.total;
        s.overhangArea += a.over;
        s.contactArea += a.contact;
    }
    s.height = hi - lo;
    if (s.totalArea > 0) {
        double over = s.overhangArea / s.totalArea, contact = s.contactArea / s.totalArea;
        // Overhangs (support material) dominate; then a solid footprint and a low height.
        s.score = (float)(over + 0.15 * (s.height / diag) + (contact < 0.001 ? 0.25 : 0.0) - 0.05 * std::min(contact, 0.3));
    }
    return s;
}

std::vector<OrientationStats> suggestOrientations(const Model& m, float overhangDeg, int count, Progress progress) {
    // Candidate bottoms: the axis directions plus the directions of the largest flat areas
    // (area-weighted normals binned on an octahedral grid).
    constexpr int G = 32;
    std::vector<double> binArea(G * G, 0.0);
    std::vector<Vec3> binNormal(G * G, Vec3(0, 0, 0));
    double total = 0;
    for (const Part& part : m.parts) {
        const bool flip = part.volume < 0;
        for (uint32_t t = part.firstIndex / 3; t < (part.firstIndex + part.indexCount) / 3; ++t) {
            double area;
            Vec3 n = outwardNormal(m, t, flip, area);
            if (area <= 0) continue;
            total += area;
            float s = std::fabs(n.x) + std::fabs(n.y) + std::fabs(n.z);
            float px = n.x / s, py = n.y / s;
            if (n.z < 0) {
                float ox = (1.f - std::fabs(py)) * (px >= 0 ? 1.f : -1.f);
                float oy = (1.f - std::fabs(px)) * (py >= 0 ? 1.f : -1.f);
                px = ox;
                py = oy;
            }
            int ix = std::clamp((int)((px * 0.5f + 0.5f) * G), 0, G - 1);
            int iy = std::clamp((int)((py * 0.5f + 0.5f) * G), 0, G - 1);
            binArea[iy * G + ix] += area;
            binNormal[iy * G + ix] += n * (float)area;
        }
    }
    std::vector<Vec3> cands = {Vec3(0, 0, -1), Vec3(0, 0, 1), Vec3(1, 0, 0), Vec3(-1, 0, 0), Vec3(0, 1, 0), Vec3(0, -1, 0)};
    std::vector<int> order(G * G);
    for (int i = 0; i < G * G; ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](int a, int b) { return binArea[a] > binArea[b]; });
    const float same = std::cos(3.f * kPi / 180.f);
    for (int i = 0; i < 24 && i < G * G; ++i) {
        int k = order[i];
        if (binArea[k] < 0.005 * total) break;
        Vec3 n = normalize(binNormal[k]);
        bool dup = false;
        for (const Vec3& c : cands) dup = dup || dot(c, n) > same;
        if (!dup) cands.push_back(n);
    }
    std::vector<OrientationStats> all;
    for (size_t i = 0; i < cands.size(); ++i) {
        all.push_back(orientationStats(m, cands[i], overhangDeg));
        progress.report((float)(i + 1) / (float)cands.size());
    }
    std::sort(all.begin(), all.end(), [](const OrientationStats& a, const OrientationStats& b) { return a.score < b.score; });
    std::vector<OrientationStats> out;
    const float distinct = std::cos(10.f * kPi / 180.f);
    for (const OrientationStats& s : all) {
        bool near = false;
        for (const OrientationStats& o : out) near = near || dot(o.down, s.down) > distinct;
        if (near) continue;
        out.push_back(s);
        if ((int)out.size() >= count) break;
    }
    return out;
}

}  // namespace fsv
