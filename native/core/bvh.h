// Per-part bounding volume hierarchies for picking and ray-based analysis.
#pragma once

#include <memory>
#include <vector>

#include "model.h"

namespace fsv {

struct Ray {
    Vec3 origin;
    Vec3 dir;  // need not be normalised; t is in units of |dir|
};

struct Hit {
    float t = std::numeric_limits<float>::max();
    uint32_t triangle = UINT32_MAX;  // global triangle index
    int part = -1;
    float u = 0, v = 0;
    bool valid() const { return triangle != UINT32_MAX; }
};

class PartBVH {
public:
    struct Node {
        float bmin[3];
        uint32_t leftOrFirst;
        float bmax[3];
        uint32_t count;  // > 0 => leaf
    };

    void build(const Model& m, const Part& part, unsigned threads = 1);
    // Closest hit with t in (tMin, tMax). `accept` may reject hits (e.g. clipped by a section plane).
    template <typename Accept>
    bool intersect(const Model& m, const Ray& ray, float tMin, float tMax, Hit& hit, Accept&& accept) const;
    bool intersect(const Model& m, const Ray& ray, float tMin, float tMax, Hit& hit) const {
        return intersect(m, ray, tMin, tMax, hit, [](uint32_t, float) { return true; });
    }
    bool empty() const { return nodes_.empty(); }

private:
    std::vector<Node> nodes_;
    std::vector<uint32_t> tris_;  // global triangle indices in leaf order
};

class ModelBVH {
public:
    // Builds all part trees (multi-threaded). Throws CancelledError when cancelled.
    void build(const Model& m, Progress progress);
    const PartBVH& part(size_t i) const { return parts_[i]; }
    size_t partCount() const { return parts_.size(); }

private:
    std::vector<PartBVH> parts_;
};

// Möller–Trumbore, double sided.
inline bool rayTriangle(const Ray& r, const Vec3& a, const Vec3& b, const Vec3& c, float& t, float& u, float& v) {
    Vec3 e1 = b - a, e2 = c - a;
    Vec3 p = cross(r.dir, e2);
    float det = dot(e1, p);
    if (std::fabs(det) < 1e-30f) return false;
    float inv = 1.f / det;
    Vec3 s = r.origin - a;
    u = dot(s, p) * inv;
    if (u < 0.f || u > 1.f) return false;
    Vec3 q = cross(s, e1);
    v = dot(r.dir, q) * inv;
    if (v < 0.f || u + v > 1.f) return false;
    t = dot(e2, q) * inv;
    return true;
}

template <typename Accept>
bool PartBVH::intersect(const Model& m, const Ray& ray, float tMin, float tMax, Hit& hit, Accept&& accept) const {
    if (nodes_.empty()) return false;
    const Vec3 invDir(1.f / ray.dir.x, 1.f / ray.dir.y, 1.f / ray.dir.z);
    auto slab = [&](const Node& n, float tmax) -> float {
        float t0 = tMin, t1 = tmax;
        for (int a = 0; a < 3; ++a) {
            float o = ray.origin[a], id = invDir[a];
            float ta = (n.bmin[a] - o) * id, tb = (n.bmax[a] - o) * id;
            if (ta > tb) std::swap(ta, tb);
            t0 = ta > t0 ? ta : t0;
            t1 = tb < t1 ? tb : t1;
            if (t0 > t1) return -1.f;
        }
        return t0;
    };
    uint32_t stack[64];
    int sp = 0;
    stack[sp++] = 0;
    bool found = false;
    float best = tMax;
    const uint32_t* I = m.indices.data();
    while (sp > 0) {
        const Node& n = nodes_[stack[--sp]];
        if (slab(n, best) < 0.f) continue;
        if (n.count > 0) {
            for (uint32_t i = 0; i < n.count; ++i) {
                uint32_t tri = tris_[n.leftOrFirst + i];
                float t, u, v;
                if (rayTriangle(ray, m.vertex(I[3 * tri]), m.vertex(I[3 * tri + 1]), m.vertex(I[3 * tri + 2]), t, u, v) &&
                    t > tMin && t < best && accept(tri, t)) {
                    best = t;
                    hit.t = t;
                    hit.triangle = tri;
                    hit.u = u;
                    hit.v = v;
                    found = true;
                }
            }
        } else {
            uint32_t l = n.leftOrFirst, r = l + 1;
            // Visit the nearer child first.
            float tl = slab(nodes_[l], best), tr = slab(nodes_[r], best);
            if (tl >= 0 && tr >= 0) {
                if (sp + 2 > 64) continue;
                if (tl < tr) { stack[sp++] = r; stack[sp++] = l; }
                else { stack[sp++] = l; stack[sp++] = r; }
            } else if (tl >= 0) {
                if (sp < 64) stack[sp++] = l;
            } else if (tr >= 0) {
                if (sp < 64) stack[sp++] = r;
            }
        }
    }
    return found;
}

}  // namespace fsv
