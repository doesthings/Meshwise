#include "feature_finder.h"

#include <atomic>
#include <thread>
#include <unordered_set>

namespace fsv {

namespace {

constexpr uint32_t kNone = TriangleAdjacency::kNoNeighbor;
// A chain of edges continues through a joint that bends less than this (so a hole tessellated
// with 10+ segments stays one chain, while the corners of a hexagon or a box split it).
constexpr float kChainTurnDeg = 38.f;
// Neighbouring triangles count as one smooth surface below this bend.
constexpr float kSmoothDeg = 30.f;
constexpr size_t kMaxRegion = 400000;
constexpr size_t kMaxOutline = 20000;  // segments

Vec3 triVertex(const Model& m, uint32_t f, int k) { return m.vertex(m.indices[3 * f + k]); }

Vec3 triNormal(const Model& m, uint32_t f) {
    Vec3 a = triVertex(m, f, 0), b = triVertex(m, f, 1), c = triVertex(m, f, 2);
    return normalize(cross(b - a, c - a));
}

double triArea(const Model& m, uint32_t f) {
    Vec3 a = triVertex(m, f, 0), b = triVertex(m, f, 1), c = triVertex(m, f, 2);
    return 0.5 * (double)length(cross(b - a, c - a));
}

Vec3 triCentroid(const Model& m, uint32_t f) {
    return (triVertex(m, f, 0) + triVertex(m, f, 1) + triVertex(m, f, 2)) / 3.f;
}

int partOfTriangle(const Model& m, uint32_t f) { return m.partIds[m.indices[3 * f]]; }

// Eigen decomposition of a symmetric 3x3 matrix (cyclic Jacobi). Eigenvalues ascending.
void eigenSym3(const double in[3][3], double w[3], Vec3 v[3]) {
    double A[3][3], V[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) A[i][j] = in[i][j];
    for (int sweep = 0; sweep < 60; ++sweep) {
        double off = A[0][1] * A[0][1] + A[0][2] * A[0][2] + A[1][2] * A[1][2];
        if (off < 1e-24) break;
        for (int p = 0; p < 2; ++p) {
            for (int q = p + 1; q < 3; ++q) {
                if (std::fabs(A[p][q]) < 1e-300) continue;
                double theta = (A[q][q] - A[p][p]) / (2.0 * A[p][q]);
                double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                for (int k = 0; k < 3; ++k) {
                    double akp = A[k][p], akq = A[k][q];
                    A[k][p] = c * akp - s * akq;
                    A[k][q] = s * akp + c * akq;
                }
                for (int k = 0; k < 3; ++k) {
                    double apk = A[p][k], aqk = A[q][k];
                    A[p][k] = c * apk - s * aqk;
                    A[q][k] = s * apk + c * aqk;
                }
                for (int k = 0; k < 3; ++k) {
                    double vkp = V[k][p], vkq = V[k][q];
                    V[k][p] = c * vkp - s * vkq;
                    V[k][q] = s * vkp + c * vkq;
                }
            }
        }
    }
    int order[3] = {0, 1, 2};
    std::sort(order, order + 3, [&](int a, int b) { return A[a][a] < A[b][b]; });
    for (int i = 0; i < 3; ++i) {
        int k = order[i];
        w[i] = A[k][k];
        v[i] = normalize(Vec3((float)V[0][k], (float)V[1][k], (float)V[2][k]));
    }
}

// Solves the n x n system in the augmented matrix M (n <= 4). Gaussian elimination.
bool solve(int n, double M[4][5], double x[4]) {
    for (int c = 0; c < n; ++c) {
        int piv = c;
        for (int r = c + 1; r < n; ++r)
            if (std::fabs(M[r][c]) > std::fabs(M[piv][c])) piv = r;
        if (std::fabs(M[piv][c]) < 1e-18) return false;
        if (piv != c)
            for (int k = 0; k <= n; ++k) std::swap(M[c][k], M[piv][k]);
        for (int r = 0; r < n; ++r) {
            if (r == c) continue;
            double f = M[r][c] / M[c][c];
            for (int k = c; k <= n; ++k) M[r][k] -= f * M[c][k];
        }
    }
    for (int i = 0; i < n; ++i) x[i] = M[i][n] / M[i][i];
    return true;
}

void basisFor(const Vec3& n, Vec3& u, Vec3& v) {
    u = std::fabs(n.z) < 0.9f ? normalize(cross(n, Vec3(0, 0, 1))) : normalize(cross(n, Vec3(1, 0, 0)));
    v = cross(n, u);
}

struct CircleFit {
    bool ok = false;
    Vec3 center;
    float radius = 0, rms = 0;
};

// Least-squares (Kasa) circle through points projected on the plane through `origin` with
// normal `n`.
CircleFit fitCircle(const std::vector<Vec3>& pts, const Vec3& origin, const Vec3& n) {
    CircleFit r;
    if (pts.size() < 3) return r;
    Vec3 u, v;
    basisFor(n, u, v);
    double mx = 0, my = 0;
    std::vector<double> xs(pts.size()), ys(pts.size());
    for (size_t i = 0; i < pts.size(); ++i) {
        Vec3 d = pts[i] - origin;
        xs[i] = dot(d, u);
        ys[i] = dot(d, v);
        mx += xs[i];
        my += ys[i];
    }
    mx /= (double)pts.size();
    my /= (double)pts.size();
    double M[4][5] = {};
    for (size_t i = 0; i < pts.size(); ++i) {
        double x = xs[i] - mx, y = ys[i] - my, z = x * x + y * y;
        double row[3] = {x, y, 1.0};
        for (int a = 0; a < 3; ++a) {
            for (int b = 0; b < 3; ++b) M[a][b] += row[a] * row[b];
            M[a][3] -= row[a] * z;
        }
    }
    double s[4];
    if (!solve(3, M, s)) return r;
    double cx = -s[0] / 2.0, cy = -s[1] / 2.0;
    double r2 = cx * cx + cy * cy - s[2];
    if (!(r2 > 0)) return r;
    double rad = std::sqrt(r2);
    double err = 0;
    for (size_t i = 0; i < pts.size(); ++i) {
        double dx = xs[i] - mx - cx, dy = ys[i] - my - cy;
        double e = std::sqrt(dx * dx + dy * dy) - rad;
        err += e * e;
    }
    r.ok = true;
    r.radius = (float)rad;
    r.rms = (float)std::sqrt(err / (double)pts.size());
    r.center = origin + u * (float)(cx + mx) + v * (float)(cy + my);
    return r;
}

struct SphereFit {
    bool ok = false;
    Vec3 center;
    float radius = 0, rms = 0;
};

SphereFit fitSphere(const std::vector<Vec3>& pts) {
    SphereFit r;
    if (pts.size() < 4) return r;
    Vec3 mean;
    for (const Vec3& p : pts) mean += p;
    mean = mean / (float)pts.size();
    double M[4][5] = {};
    for (const Vec3& p0 : pts) {
        Vec3 p = p0 - mean;
        double row[4] = {p.x, p.y, p.z, 1.0};
        double z = (double)p.x * p.x + (double)p.y * p.y + (double)p.z * p.z;
        for (int a = 0; a < 4; ++a) {
            for (int b = 0; b < 4; ++b) M[a][b] += row[a] * row[b];
            M[a][4] -= row[a] * z;
        }
    }
    double s[4];
    if (!solve(4, M, s)) return r;
    Vec3 c((float)(-s[0] / 2), (float)(-s[1] / 2), (float)(-s[2] / 2));
    double r2 = (double)dot(c, c) - s[3];
    if (!(r2 > 0)) return r;
    double rad = std::sqrt(r2), err = 0;
    for (const Vec3& p0 : pts) {
        double e = (double)length(p0 - mean - c) - rad;
        err += e * e;
    }
    r.ok = true;
    r.center = c + mean;
    r.radius = (float)rad;
    r.rms = (float)std::sqrt(err / (double)pts.size());
    return r;
}

Vec3 closestOnSegment(const Vec3& p, const Vec3& a, const Vec3& b) {
    Vec3 ab = b - a;
    float l2 = dot(ab, ab);
    float t = l2 > 0 ? std::clamp(dot(p - a, ab) / l2, 0.f, 1.f) : 0.f;
    return a + ab * t;
}

float modelScale(const Model& m) {
    return std::max(1e-6f, length(m.bounds.max - m.bounds.min));
}

// ---------------------------------------------------------------- regions

struct Region {
    std::vector<uint32_t> tris;
    std::vector<uint8_t> mark;  // per triangle (sized lazily)
};

// Breadth-first growth over the adjacency from `seed`, keeping triangles `accept(from, to)` likes.
template <typename Accept>
void grow(const Model& m, const TriangleAdjacency& adj, uint32_t seed, size_t limit, Region& r, Accept accept) {
    const size_t nf = m.triangleCount();
    r.mark.assign(nf, 0);
    r.tris.clear();
    r.tris.push_back(seed);
    r.mark[seed] = 1;
    const int part = partOfTriangle(m, seed);
    for (size_t head = 0; head < r.tris.size() && r.tris.size() < limit; ++head) {
        uint32_t f = r.tris[head];
        for (int k = 0; k < 3; ++k) {
            uint32_t g = adj.neighbors[3 * f + k];
            if (g == kNone || r.mark[g] || partOfTriangle(m, g) != part) continue;
            if (!accept(f, g)) continue;
            r.mark[g] = 1;
            r.tris.push_back(g);
            if (r.tris.size() >= limit) break;
        }
    }
}

void regionOutline(const Model& m, const TriangleAdjacency& adj, const Region& r, std::vector<Vec3>& out) {
    out.clear();
    for (uint32_t f : r.tris) {
        for (int k = 0; k < 3; ++k) {
            uint32_t g = adj.neighbors[3 * f + k];
            if (g != kNone && r.mark[g]) continue;
            if (out.size() / 2 >= kMaxOutline) return;
            out.push_back(triVertex(m, f, k));
            out.push_back(triVertex(m, f, (k + 1) % 3));
        }
    }
}

std::vector<Vec3> regionVertices(const Model& m, const Region& r) {
    std::unordered_set<uint32_t> seen;
    std::vector<Vec3> pts;
    seen.reserve(r.tris.size() * 2);
    for (uint32_t f : r.tris) {
        for (int k = 0; k < 3; ++k) {
            uint32_t pid = m.posIds[m.indices[3 * f + k]];
            if (seen.insert(pid).second) pts.push_back(triVertex(m, f, k));
        }
    }
    return pts;
}

double regionArea(const Model& m, const Region& r) {
    double a = 0;
    for (uint32_t f : r.tris) a += triArea(m, f);
    return a;
}

// Area-weighted normal covariance: for a cylinder all normals lie in the plane normal to the
// axis, so the axis is the eigenvector with the smallest eigenvalue.
void normalCovariance(const Model& m, const Region& r, double w[3], Vec3 v[3]) {
    double C[3][3] = {};
    for (uint32_t f : r.tris) {
        Vec3 n = triNormal(m, f);
        double a = triArea(m, f);
        for (int i = 0; i < 3; ++i)
            for (int j = 0; j < 3; ++j) C[i][j] += a * n[i] * n[j];
    }
    eigenSym3(C, w, v);
}

// ---------------------------------------------------------------- surfaces

bool planeFeature(const Model& m, const TriangleAdjacency& adj, const FeatureQuery& q, const Vec3& n0, Feature& f) {
    const float cosTol = std::cos(1.0f * kPi / 180.f);
    const float distTol = 1e-4f * modelScale(m) + 1e-6f;
    const float d0 = dot(n0, q.point);
    Region r;
    grow(m, adj, q.triangle, kMaxRegion, r, [&](uint32_t, uint32_t g) {
        return dot(triNormal(m, g), n0) > cosTol && std::fabs(dot(n0, triCentroid(m, g)) - d0) < distTol;
    });
    double area = 0;
    Vec3 c;
    for (uint32_t t : r.tris) {
        double a = triArea(m, t);
        area += a;
        c += triCentroid(m, t) * (float)a;
    }
    if (area <= 0) return false;
    f.kind = FeatureKind::Plane;
    f.area = area;
    f.center = c / (float)area;
    f.axis = dot(n0, q.normal) >= 0 ? n0 : -n0;
    f.normal = f.axis;
    regionOutline(m, adj, r, f.outline);
    return true;
}

bool cylinderFeature(const Model& m, const TriangleAdjacency& adj, const FeatureQuery& q, const Region& patch,
                     const Vec3& seedNormal, Feature& f) {
    double w[3];
    Vec3 ev[3];
    normalCovariance(m, patch, w, ev);
    double total = w[0] + w[1] + w[2];
    if (total <= 0 || w[0] / total > 0.03) return false;
    Vec3 axis = ev[0];
    CircleFit c = fitCircle(regionVertices(m, patch), q.point, axis);
    const float scale = modelScale(m);
    if (!c.ok || c.radius > 20.f * scale || c.rms > 0.05f * c.radius) return false;

    // Grow over the whole surface that matches the first estimate, then refit.
    const float cosSmooth = std::cos(35.f * kPi / 180.f);
    for (int pass = 0; pass < 2; ++pass) {
        const float tol = 0.04f * c.radius + 1e-4f * scale;
        auto radial = [&](const Vec3& p) {
            Vec3 d = p - c.center;
            return length(d - axis * dot(d, axis));
        };
        Region r;
        grow(m, adj, q.triangle, kMaxRegion, r, [&](uint32_t from, uint32_t g) {
            Vec3 ng = triNormal(m, g);
            if (dot(triNormal(m, from), ng) < cosSmooth || std::fabs(dot(ng, axis)) > 0.25f) return false;
            for (int k = 0; k < 3; ++k)
                if (std::fabs(radial(triVertex(m, g, k)) - c.radius) > tol) return false;
            return true;
        });
        normalCovariance(m, r, w, ev);
        total = w[0] + w[1] + w[2];
        if (total > 0 && w[0] / total < 0.05) axis = ev[0];
        std::vector<Vec3> pts = regionVertices(m, r);
        CircleFit c2 = fitCircle(pts, q.point, axis);
        if (!c2.ok) return false;
        c = c2;
        if (pass == 1) {
            if (c.rms > 0.02f * c.radius) return false;
            float tmin = std::numeric_limits<float>::max(), tmax = -tmin;
            for (const Vec3& p : pts) {
                float t = dot(p - c.center, axis);
                tmin = std::min(tmin, t);
                tmax = std::max(tmax, t);
            }
            f.kind = FeatureKind::Cylinder;
            f.axis = axis;
            f.radius = c.radius;
            f.fitError = c.rms;
            f.a = c.center + axis * tmin;
            f.b = c.center + axis * tmax;
            f.center = (f.a + f.b) * 0.5f;
            f.length = tmax - tmin;
            f.area = regionArea(m, r);
            Vec3 d = q.point - c.center;
            Vec3 out = d - axis * dot(d, axis);  // from the axis to the tap
            f.inner = dot(seedNormal, out) < 0;   // surface faces the axis: a hole
            regionOutline(m, adj, r, f.outline);
        }
    }
    return true;
}

bool sphereFeature(const Model& m, const TriangleAdjacency& adj, const FeatureQuery& q, const Region& patch,
                   const Vec3& seedNormal, Feature& f) {
    SphereFit s = fitSphere(regionVertices(m, patch));
    if (!s.ok || s.radius > 20.f * modelScale(m) || s.rms > 0.05f * s.radius) return false;
    const float tol = 0.04f * s.radius + 1e-4f * modelScale(m);
    Region r;
    grow(m, adj, q.triangle, kMaxRegion, r, [&](uint32_t, uint32_t g) {
        for (int k = 0; k < 3; ++k)
            if (std::fabs(length(triVertex(m, g, k) - s.center) - s.radius) > tol) return false;
        return true;
    });
    SphereFit s2 = fitSphere(regionVertices(m, r));
    if (!s2.ok || s2.rms > 0.02f * s2.radius) return false;
    f.kind = FeatureKind::Sphere;
    f.center = s2.center;
    f.radius = s2.radius;
    f.fitError = s2.rms;
    f.area = regionArea(m, r);
    f.axis = normalize(q.point - s2.center);
    f.inner = dot(seedNormal, q.point - s2.center) < 0;
    regionOutline(m, adj, r, f.outline);
    return true;
}

void surfaceFeature(const Model& m, const TriangleAdjacency* adj, const FeatureQuery& q, Feature& f) {
    const Vec3 n0 = triNormal(m, q.triangle);  // winding normal (outward for a good mesh)
    if (!adj || adj->neighbors.size() != 3 * m.triangleCount()) return;
    // A local smooth patch tells flat from curved.
    const float cosSmooth = std::cos(kSmoothDeg * kPi / 180.f);
    Region patch;
    grow(m, *adj, q.triangle, 600, patch, [&](uint32_t from, uint32_t g) {
        return dot(triNormal(m, from), triNormal(m, g)) > cosSmooth;
    });
    float minCos = 1.f;
    for (uint32_t t : patch.tris) minCos = std::min(minCos, dot(triNormal(m, t), n0));
    if (minCos > std::cos(2.f * kPi / 180.f)) {
        planeFeature(m, *adj, q, n0, f);
        return;
    }
    if (cylinderFeature(m, *adj, q, patch, n0, f)) return;
    if (sphereFeature(m, *adj, q, patch, n0, f)) return;
    // Not a simple curved shape. A flat face that meets a shallow chamfer still counts as a
    // face when it is big compared with the finger; on free-form surfaces it is just a point.
    Feature flat = f;
    if (planeFeature(m, *adj, q, n0, flat) && flat.area > 4.0 * (double)q.snapRadius * q.snapRadius) {
        f = std::move(flat);
        return;
    }
    f.kind = FeatureKind::Point;
}

// ---------------------------------------------------------------- edges

struct Chain {
    std::vector<Vec3> pts;
    std::vector<uint32_t> segs;
    bool closed = false;
    bool cornerFront = true, cornerBack = true;
};

Vec3 posOf(const Model& m, const EdgeGraph& g, uint32_t seg, uint32_t pos) {
    return m.vertex(g.segA[seg] == pos ? g.segVA[seg] : g.segVB[seg]);
}

uint32_t otherEnd(const EdgeGraph& g, uint32_t seg, uint32_t pos) { return g.segA[seg] == pos ? g.segB[seg] : g.segA[seg]; }

// Follows the chain from `seg` through position `pos` (exclusive). Returns the visited points
// and whether it stopped at a corner; `closedAt` is set when it comes back to `stopSeg`.
void walk(const Model& m, const EdgeGraph& g, uint32_t seg, uint32_t pos, uint32_t stopSeg, int part,
          std::vector<Vec3>& pts, std::vector<uint32_t>& segs, bool& corner, bool& closed,
          std::unordered_set<uint32_t>& visited) {
    const float cosTurn = std::cos(kChainTurnDeg * kPi / 180.f);
    uint32_t cur = seg, at = pos;
    corner = true;
    closed = false;
    for (int guard = 0; guard < 100000; ++guard) {
        // Distinct other segments at this joint (same part, different far end).
        uint32_t next = kNone, count = 0, farPrev = otherEnd(g, cur, at);
        std::unordered_set<uint32_t> ends;
        for (uint32_t k = g.offsets[at]; k < g.offsets[at + 1]; ++k) {
            uint32_t s = g.incident[k];
            if (s == cur || (int)m.partIds[g.segVA[s]] != part) continue;
            uint32_t far = otherEnd(g, s, at);
            if (far == farPrev || !ends.insert(far).second) continue;
            next = s;
            ++count;
        }
        if (count != 1) return;  // end of a line or a junction: a corner
        Vec3 pAt = posOf(m, g, cur, at);
        Vec3 d0 = normalize(pAt - posOf(m, g, cur, farPrev));
        uint32_t nextFar = otherEnd(g, next, at);
        Vec3 d1 = normalize(posOf(m, g, next, nextFar) - pAt);
        if (dot(d0, d1) < cosTurn) return;  // sharp bend: a corner
        corner = false;
        if (next == stopSeg) {
            closed = true;
            return;
        }
        if (!visited.insert(next).second) return;
        segs.push_back(next);
        pts.push_back(posOf(m, g, next, nextFar));
        cur = next;
        at = nextFar;
        corner = true;
    }
}

Chain traceChain(const Model& m, const EdgeGraph& g, uint32_t seed) {
    Chain c;
    const int part = m.partIds[g.segVA[seed]];
    std::unordered_set<uint32_t> visited{seed};
    std::vector<Vec3> fwd, back;
    std::vector<uint32_t> fwdSegs, backSegs;
    bool cornerF = true, cornerB = true, closed = false;
    walk(m, g, seed, g.segB[seed], seed, part, fwd, fwdSegs, cornerB, closed, visited);
    if (!closed) walk(m, g, seed, g.segA[seed], seed, part, back, backSegs, cornerF, closed, visited);
    c.closed = closed;
    c.cornerFront = !closed && cornerF;
    c.cornerBack = !closed && cornerB;
    for (auto it = back.rbegin(); it != back.rend(); ++it) c.pts.push_back(*it);
    c.pts.push_back(m.vertex(g.segVA[seed]));
    c.pts.push_back(m.vertex(g.segVB[seed]));
    for (const Vec3& p : fwd) c.pts.push_back(p);
    for (auto it = backSegs.rbegin(); it != backSegs.rend(); ++it) c.segs.push_back(*it);
    c.segs.push_back(seed);
    for (uint32_t s : fwdSegs) c.segs.push_back(s);
    return c;
}

bool edgeFeature(const Model& m, const EdgeGraph& g, const FeatureQuery& q, Feature& f) {
    float best = q.snapRadius;
    int bestSeg = -1;
    Vec3 bestPt;
    const size_t ns = g.segA.size();
    for (size_t s = 0; s < ns; ++s) {
        if ((int)m.partIds[g.segVA[s]] != q.part) continue;
        Vec3 a = m.vertex(g.segVA[s]), b = m.vertex(g.segVB[s]);
        Vec3 c = closestOnSegment(q.point, a, b);
        float d = length(c - q.point);
        if (d < best) {
            best = d;
            bestSeg = (int)s;
            bestPt = c;
        }
    }
    if (bestSeg < 0) return false;
    Chain ch = traceChain(m, g, (uint32_t)bestSeg);
    if (ch.pts.size() < 2) return false;

    // Corners win when the tap is close to one.
    const Vec3 ends[2] = {ch.pts.front(), ch.pts.back()};
    const bool isCorner[2] = {ch.cornerFront, ch.cornerBack};
    float bestCorner = q.snapRadius;
    int corner = -1;
    for (int i = 0; i < 2; ++i) {
        float d = length(ends[i] - q.point);
        if (isCorner[i] && d < bestCorner) {
            bestCorner = d;
            corner = i;
        }
    }
    if (corner >= 0) {
        f.kind = FeatureKind::Vertex;
        f.point = ends[corner];
        f.center = f.point;
        return true;
    }

    double len = 0;
    for (size_t i = 1; i < ch.pts.size(); ++i) len += length(ch.pts[i] - ch.pts[i - 1]);
    if (ch.closed) len += length(ch.pts.front() - ch.pts.back());
    f.point = bestPt;
    f.length = (float)len;
    f.a = ends[0];
    f.b = ends[1];
    f.closed = ch.closed;
    for (size_t i = 1; i < ch.pts.size() && f.outline.size() / 2 < kMaxOutline; ++i) {
        f.outline.push_back(ch.pts[i - 1]);
        f.outline.push_back(ch.pts[i]);
    }
    if (ch.closed) {
        f.outline.push_back(ch.pts.back());
        f.outline.push_back(ch.pts.front());
    }

    // Straight?
    if (!ch.closed) {
        Vec3 dir = ends[1] - ends[0];
        float l = length(dir);
        float dev = 0;
        if (l > 0) {
            dir = dir / l;
            for (const Vec3& p : ch.pts) {
                Vec3 d = p - ends[0];
                dev = std::max(dev, length(d - dir * dot(d, dir)));
            }
        }
        if (l > 0 && dev <= std::max(1e-3f * l, 1e-5f * modelScale(m))) {
            f.kind = FeatureKind::Edge;
            f.axis = dir;
            f.length = l;
            f.center = (ends[0] + ends[1]) * 0.5f;
            return true;
        }
    }

    // Round?
    if (ch.pts.size() >= 4) {
        Vec3 mean;
        for (const Vec3& p : ch.pts) mean += p;
        mean = mean / (float)ch.pts.size();
        double C[3][3] = {};
        for (const Vec3& p : ch.pts) {
            Vec3 d = p - mean;
            for (int i = 0; i < 3; ++i)
                for (int j = 0; j < 3; ++j) C[i][j] += (double)d[i] * d[j];
        }
        double w[3];
        Vec3 ev[3];
        eigenSym3(C, w, ev);
        Vec3 n = ev[0];
        float flat = 0;
        for (const Vec3& p : ch.pts) flat = std::max(flat, std::fabs(dot(p - mean, n)));
        CircleFit c = fitCircle(ch.pts, mean, n);
        if (c.ok && c.rms < 0.01f * c.radius && flat < 0.02f * c.radius + 1e-5f * modelScale(m)) {
            f.kind = FeatureKind::Circle;
            f.center = c.center;
            f.radius = c.radius;
            f.fitError = c.rms;
            f.axis = dot(n, q.normal) >= 0 ? n : -n;
            return true;
        }
    }
    f.kind = FeatureKind::Edge;
    f.axis = normalize(ends[1] - ends[0]);
    f.center = bestPt;
    return true;
}

}  // namespace

void Feature::translate(const Vec3& off) {
    point += off;
    center += off;
    a += off;
    b += off;
    for (Vec3& p : outline) p += off;
}

std::unique_ptr<TriangleAdjacency> buildAdjacency(const Model& m, Progress progress) {
    auto adj = std::make_unique<TriangleAdjacency>();
    const size_t nf = m.triangleCount();
    const uint32_t np = m.positionCount;
    adj->neighbors.assign(3 * nf, kNone);
    if (nf == 0 || np == 0) return adj;
    const uint32_t* I = m.indices.data();
    const uint32_t* PID = m.posIds.data();
    std::vector<uint32_t> offs(np + 1, 0);
    for (size_t f = 0; f < nf; ++f)
        for (int k = 0; k < 3; ++k) offs[PID[I[3 * f + k]] + 1]++;
    for (uint32_t i = 0; i < np; ++i) offs[i + 1] += offs[i];
    std::vector<uint32_t> faces(offs[np]);
    {
        std::vector<uint32_t> fill(offs.begin(), offs.end() - 1);
        for (size_t f = 0; f < nf; ++f)
            for (int k = 0; k < 3; ++k) faces[fill[PID[I[3 * f + k]]]++] = (uint32_t)f;
    }
    progress.report(0.3f);

    unsigned nThreads = std::max(1u, std::min(std::thread::hardware_concurrency(), 8u));
    if (nf < 20000) nThreads = 1;
    std::atomic<size_t> next{0}, done{0};
    std::atomic<bool> cancelled{false};
    const size_t chunk = 8192;
    auto worker = [&](unsigned tid) {
        for (;;) {
            if (cancelled) return;
            size_t start = next.fetch_add(chunk);
            if (start >= nf) return;
            size_t end = std::min(nf, start + chunk);
            for (size_t f = start; f < end; ++f) {
                for (int k = 0; k < 3; ++k) {
                    uint32_t pa = PID[I[3 * f + k]], pb = PID[I[3 * f + (k + 1) % 3]];
                    if (pa == pb) continue;
                    uint32_t found = kNone, count = 0;
                    for (uint32_t i = offs[pa]; i < offs[pa + 1]; ++i) {
                        uint32_t g = faces[i];
                        if (g == f) continue;
                        if (PID[I[3 * g]] == pb || PID[I[3 * g + 1]] == pb || PID[I[3 * g + 2]] == pb) {
                            if (g != found) {
                                found = g;
                                ++count;
                            }
                        }
                    }
                    if (count == 1) adj->neighbors[3 * f + k] = found;
                }
            }
            size_t d = done.fetch_add(end - start) + (end - start);
            if (tid == 0) {
                try {
                    progress.report(0.3f + 0.7f * (float)d / (float)nf);
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
    progress.report(1.f);
    return adj;
}

std::unique_ptr<EdgeGraph> buildEdgeGraph(const Model& m, const Topology& topo) {
    auto g = std::make_unique<EdgeGraph>();
    const uint32_t np = m.positionCount;
    const auto& E = topo.featureEdges;
    // CAD edges include seams and tangent joins where the surface is smooth; those are not
    // useful snap targets (a cylinder's seam would turn a tap on a shaft into an edge).
    std::vector<uint32_t> offs, faces;
    const bool filterSmooth = !m.cadEdges.empty();
    if (filterSmooth) {
        const size_t nf = m.triangleCount();
        offs.assign(np + 1, 0);
        for (size_t f = 0; f < nf; ++f)
            for (int k = 0; k < 3; ++k) offs[m.posIds[m.indices[3 * f + k]] + 1]++;
        for (uint32_t i = 0; i < np; ++i) offs[i + 1] += offs[i];
        faces.resize(offs[np]);
        std::vector<uint32_t> fill(offs.begin(), offs.end() - 1);
        for (size_t f = 0; f < nf; ++f)
            for (int k = 0; k < 3; ++k) faces[fill[m.posIds[m.indices[3 * f + k]]]++] = (uint32_t)f;
    }
    const float cosSmooth = std::cos(20.f * kPi / 180.f);
    auto smooth = [&](uint32_t pa, uint32_t pb) {
        uint32_t found[2], count = 0;
        for (uint32_t i = offs[pa]; i < offs[pa + 1] && count <= 2; ++i) {
            uint32_t f = faces[i];
            for (int k = 0; k < 3; ++k) {
                if (m.posIds[m.indices[3 * f + k]] != pb) continue;
                if (count < 2) found[count] = f;
                ++count;
                break;
            }
        }
        return count == 2 && dot(triNormal(m, found[0]), triNormal(m, found[1])) > cosSmooth;
    };
    for (size_t i = 0; i + 1 < E.size(); i += 2) {
        uint32_t va = E[i], vb = E[i + 1];
        if (va >= m.posIds.size() || vb >= m.posIds.size()) continue;
        uint32_t pa = m.posIds[va], pb = m.posIds[vb];
        if (pa == pb) continue;
        if (filterSmooth && smooth(pa, pb)) continue;
        g->segA.push_back(pa);
        g->segB.push_back(pb);
        g->segVA.push_back(va);
        g->segVB.push_back(vb);
    }
    g->offsets.assign(np + 1, 0);
    for (size_t s = 0; s < g->segA.size(); ++s) {
        g->offsets[g->segA[s] + 1]++;
        g->offsets[g->segB[s] + 1]++;
    }
    for (uint32_t i = 0; i < np; ++i) g->offsets[i + 1] += g->offsets[i];
    g->incident.resize(g->offsets[np]);
    std::vector<uint32_t> fill(g->offsets.begin(), g->offsets.end() - 1);
    for (size_t s = 0; s < g->segA.size(); ++s) {
        g->incident[fill[g->segA[s]]++] = (uint32_t)s;
        g->incident[fill[g->segB[s]]++] = (uint32_t)s;
    }
    return g;
}

Feature findFeature(const Model& m, const TriangleAdjacency* adj, const EdgeGraph* edges, const FeatureQuery& q) {
    Feature f;
    f.part = q.part;
    f.point = q.point;
    f.center = q.point;
    f.normal = q.normal;
    f.axis = q.normal;
    if (q.triangle >= m.triangleCount()) return f;
    f.kind = FeatureKind::Point;
    if (edges && !edges->segA.empty() && edgeFeature(m, *edges, q, f)) return f;
    surfaceFeature(m, adj, q, f);
    return f;
}

}  // namespace fsv
