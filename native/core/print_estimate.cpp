#include "print_estimate.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <deque>
#include <thread>
#include <unordered_map>
#include <vector>

#include "parallel.h"

namespace fsv {

namespace {

constexpr double kSqrt2 = 1.4142135623730951;

// ---------------------------------------------------------------- motion

// Time for a straight move of length L that starts at v0 and ends at v1, cruising at no more
// than v with acceleration a (trapezoid; a triangle when the move is too short to cruise).
double moveTime(double L, double v, double a, double v0, double v1) {
    if (L <= 0 || v <= 0) return 0;
    v0 = std::min(v0, v);
    v1 = std::min(v1, v);
    if (a <= 0) return L / v;
    const double peak2 = a * L + 0.5 * (v0 * v0 + v1 * v1);  // accelerate, then brake, no cruise
    if (peak2 >= v * v) {
        double da = (v * v - v0 * v0) / (2 * a), dd = (v * v - v1 * v1) / (2 * a);
        return (v - v0) / a + (v - v1) / a + (L - da - dd) / v;
    }
    double vp = std::sqrt(std::max(peak2, 0.0));
    if (vp < std::max(v0, v1)) return L / std::max(0.5 * (v0 + v1), 1e-3);  // the planner caps v0/v1 first
    return (vp - v0) / a + (vp - v1) / a;
}

struct V2 {
    double x = 0, y = 0;
};

// Highest speed a move in direction u (unit) can start from, or end at, from a standstill.
double safeSpeed(V2 u, double v, double jerk) {
    const double m = std::max(std::fabs(u.x), std::fabs(u.y));
    return m * v > jerk ? jerk / m : v;
}

// Corner speed between a move along u at nominal speed v0 and one along w at v1, by Marlin's
// classic jerk rule as PrusaSlicer's and Bambu Studio's time estimators apply it: each axis may
// change its speed by at most `jerk`, and an axis that reverses only counts its larger side.
double junctionSpeed(V2 u, double v0, V2 w, double v1, double jerk) {
    const bool prevLarger = v0 > v1;
    const double smaller = prevLarger ? v1 / v0 : v0 / v1;
    double vmax = prevLarger ? v1 : v0;
    double factor = 1;
    bool limited = false;
    const double ue[2] = {u.x * v0, u.y * v0}, we[2] = {w.x * v1, w.y * v1};
    for (int a = 0; a < 2; ++a) {
        double vExit = ue[a], vEntry = we[a];
        if (prevLarger) vExit *= smaller;
        if (limited) {
            vExit *= factor;
            vEntry *= factor;
        }
        const double j = vExit > vEntry ? ((vEntry > 0 || vExit < 0) ? vExit - vEntry : std::max(vExit, -vEntry))
                                        : ((vEntry < 0 || vExit > 0) ? vEntry - vExit : std::max(-vExit, vEntry));
        if (j > jerk) {
            factor *= jerk / j;
            limited = true;
        }
    }
    if (limited) vmax *= factor;
    const double s0 = safeSpeed(u, v0, jerk), s1 = safeSpeed(w, v1, jerk);
    if (s0 > vmax * 0.99 && s1 > vmax * 0.99) vmax = s1;
    return vmax;
}

// Plans a closed polyline with a speed limit per segment, the way a firmware look-ahead
// planner would: corner limits from jerk, then backward/forward acceleration passes.
double planLoop(const std::vector<V2>& pts, const std::vector<double>& limit, double a, double jerk) {
    const size_t n = pts.size();
    if (n < 2) return 0;
    std::vector<double> len(n), vj(n + 1);
    std::vector<V2> dir(n);
    for (size_t i = 0; i < n; ++i) {
        const V2& p = pts[i];
        const V2& q = pts[(i + 1) % n];
        double dx = q.x - p.x, dy = q.y - p.y, l = std::sqrt(dx * dx + dy * dy);
        len[i] = l;
        dir[i] = l > 0 ? V2{dx / l, dy / l} : V2{1, 0};
    }
    // The loop starts and ends at a standstill; corners follow the jerk rule.
    vj[0] = safeSpeed(dir[0], limit[0], jerk);
    vj[n] = safeSpeed(dir[n - 1], limit[n - 1], jerk);
    for (size_t i = 1; i < n; ++i) vj[i] = std::min({junctionSpeed(dir[i - 1], limit[i - 1], dir[i], limit[i], jerk), limit[i - 1], limit[i]});
    for (size_t i = n; i-- > 0;) vj[i] = std::min(vj[i], std::sqrt(vj[i + 1] * vj[i + 1] + 2 * a * len[i]));
    for (size_t i = 1; i <= n; ++i) vj[i] = std::min(vj[i], std::sqrt(vj[i - 1] * vj[i - 1] + 2 * a * len[i - 1]));
    double t = 0;
    for (size_t i = 0; i < n; ++i) t += moveTime(len[i], limit[i], a, vj[i], vj[i + 1]);
    return t;
}

// Seconds per mm along gyroid infill: its waves are written as ~0.5 mm segments that turn a
// little at every vertex (turns measured from slicer output, scaled with the wave's curvature).
double gyroidSecondsPerMm(double v, double a, double jerk, double curvatureScale) {
    static const double kTurnsDeg[5] = {8, 13, 18, 28, 13};
    const double k = std::sqrt(std::max(0.05, curvatureScale));
    const double seg = 0.5 / k;
    std::vector<V2> pts;
    double ang = kPi / 4;
    V2 p{0, 0};
    pts.push_back(p);
    for (int rep = 0; rep < 8; ++rep)
        for (int i = 0; i < 5; ++i) {
            ang += (rep % 2 ? -1 : 1) * kTurnsDeg[i] * kPi / 180 * k;
            p = {p.x + seg * std::cos(ang), p.y + seg * std::sin(ang)};
            pts.push_back(p);
        }
    const size_t n = pts.size() - 1;
    std::vector<double> len(n, seg), vj(n + 1);
    std::vector<V2> dir(n);
    for (size_t i = 0; i < n; ++i) dir[i] = {(pts[i + 1].x - pts[i].x) / seg, (pts[i + 1].y - pts[i].y) / seg};
    vj[0] = safeSpeed(dir[0], v, jerk);
    vj[n] = safeSpeed(dir[n - 1], v, jerk);
    for (size_t i = 1; i < n; ++i) vj[i] = std::min(junctionSpeed(dir[i - 1], v, dir[i], v, jerk), v);
    for (size_t i = n; i-- > 0;) vj[i] = std::min(vj[i], std::sqrt(vj[i + 1] * vj[i + 1] + 2 * a * len[i]));
    for (size_t i = 1; i <= n; ++i) vj[i] = std::min(vj[i], std::sqrt(vj[i - 1] * vj[i - 1] + 2 * a * len[i - 1]));
    double t = 0;
    for (size_t i = 0; i < n; ++i) t += moveTime(seg, v, a, vj[i], vj[i + 1]);
    return t / (seg * n);
}

// ---------------------------------------------------------------- slicing

struct Seg {
    float ax, ay, bx, by;
    float slope;      // how far the surface leans out over the layer below, per mm of height
    uint64_t ka, kb;  // mesh edges the end points lie on (for joining segments into loops)
};

struct Loop {
    std::vector<V2> pts;
    std::vector<float> slope;     // per segment (pts[i] -> pts[i+1])
    double length = 0, area = 0;  // signed area: > 0 outer contour, < 0 hole
};

// Grid covering the model (plus a margin) with square cells of size `c`.
struct Grid {
    int nx = 0, ny = 0;
    double x0 = 0, y0 = 0, c = 1;
    size_t size() const { return (size_t)nx * ny; }
    int cellX(double x) const { return std::clamp((int)std::floor((x - x0) / c), 0, nx - 1); }
    int cellY(double y) const { return std::clamp((int)std::floor((y - y0) / c), 0, ny - 1); }
};

struct Box {
    int x0 = 0, y0 = 0, x1 = 0, y1 = 0;  // cells [x0, x1) × [y0, y1)
    bool empty() const { return x0 >= x1 || y0 >= y1; }
};

struct Layer {
    double z = 0, h = 0;
    std::vector<uint8_t> inside;
    std::vector<Seg> segs;
    std::vector<Loop> loops;
    Box box;
};

// Cell classes within a layer.
enum : uint8_t { kEmpty = 0, kWall, kSparse, kSolid, kTop, kBottom, kBridge, kGap, kClassCount };

// dst = whether a set cell of src lies within r cells (a disk), computed only for cells where
// want(i) holds (0 elsewhere), inside the box [x0, x1) × [y0, y1). Returns whether src had any set
// cell. `row` is scratch: the distance along the row to the nearest set cell, capped at r + 1.
template <typename Want>
bool dilate(const std::vector<uint8_t>& src, int nx, int x0, int y0, int x1, int y1, int r, std::vector<uint8_t>& dst,
            std::vector<int>& row, Want want) {
    bool any = false;
    for (int y = y0; y < y1; ++y) {
        const size_t base = (size_t)y * nx;
        int d = r + 1;
        for (int x = x0; x < x1; ++x) {
            const bool on = src[base + x];
            any |= on;
            d = on ? 0 : std::min(d + 1, r + 1);
            row[base + x] = d;
        }
        d = r + 1;
        for (int x = x1 - 1; x >= x0; --x) {
            d = src[base + x] ? 0 : std::min(d + 1, r + 1);
            row[base + x] = std::min(row[base + x], d);
        }
    }
    int reachAt[64];
    r = std::min(r, 63);
    for (int dy = 0; dy <= r; ++dy) reachAt[dy] = (int)std::floor(std::sqrt((double)(r * r - dy * dy)) + 1e-9);
    for (int y = y0; y < y1; ++y)
        for (int x = x0; x < x1; ++x) {
            const size_t i = (size_t)y * nx + x;
            uint8_t hit = 0;
            if (any && want(i)) {
                const int lo = std::max(y0, y - r), hi = std::min(y1 - 1, y + r);
                for (int yy = lo; yy <= hi && !hit; ++yy) hit = row[(size_t)yy * nx + x] <= reachAt[yy < y ? y - yy : yy - y];
            }
            dst[i] = hit;
        }
    return any;
}

// Douglas-Peucker simplification of a closed loop, as slicers do before writing G-code (their
// "resolution"): fewer, sharper corners than the mesh's fine tessellation. Slopes of merged
// segments are averaged by length.
void simplifyLoop(Loop& lp, double tol) {
    const size_t n = lp.pts.size();
    if (n < 8 || tol <= 0) return;
    size_t far = 0;
    double best = -1;
    for (size_t i = 1; i < n; ++i) {
        const double d = (lp.pts[i].x - lp.pts[0].x) * (lp.pts[i].x - lp.pts[0].x) + (lp.pts[i].y - lp.pts[0].y) * (lp.pts[i].y - lp.pts[0].y);
        if (d > best) {
            best = d;
            far = i;
        }
    }
    std::vector<uint8_t> keep(n + 1, 0);
    keep[0] = keep[far] = keep[n] = 1;
    auto at = [&](size_t i) -> const V2& { return lp.pts[i % n]; };
    std::vector<std::pair<size_t, size_t>> todo = {{0, far}, {far, n}};
    const double tol2 = tol * tol;
    while (!todo.empty()) {
        auto [a, b] = todo.back();
        todo.pop_back();
        if (b <= a + 1) continue;
        const V2 &A = at(a), &Bp = at(b);
        const double dx = Bp.x - A.x, dy = Bp.y - A.y, l2 = dx * dx + dy * dy;
        size_t worst = 0;
        double wd = tol2;
        for (size_t i = a + 1; i < b; ++i) {
            const V2& P = at(i);
            double d2;
            if (l2 <= 0) d2 = (P.x - A.x) * (P.x - A.x) + (P.y - A.y) * (P.y - A.y);
            else {
                const double cr = (P.x - A.x) * dy - (P.y - A.y) * dx;
                d2 = cr * cr / l2;
            }
            if (d2 > wd) {
                wd = d2;
                worst = i;
            }
        }
        if (worst) {
            keep[worst] = 1;
            todo.push_back({a, worst});
            todo.push_back({worst, b});
        }
    }
    Loop out;
    out.length = lp.length;
    out.area = lp.area;
    double slopeSum = 0, lenSum = 0;
    for (size_t i = 0; i < n; ++i) {
        const V2& a = lp.pts[i];
        const V2& b = lp.pts[(i + 1) % n];
        const double l = std::hypot(b.x - a.x, b.y - a.y);
        if (keep[i]) out.pts.push_back(a);
        slopeSum += lp.slope[i] * l;
        lenSum += l;
        if (keep[i + 1]) {
            out.slope.push_back((float)(lenSum > 0 ? slopeSum / lenSum : 0));
            slopeSum = lenSum = 0;
        }
    }
    if (out.pts.size() >= 3 && out.pts.size() == out.slope.size()) lp = std::move(out);
}

// Line directions on the grid: along X, along Y and the two diagonals.
enum Dir { kAlongX = 0, kAlongY = 1, kDiagUp = 2, kDiagDown = 3 };

}  // namespace

PrintEstimate estimatePrint(const Model& m, const PrintSettings& s, Progress progress) {
    PrintEstimate est;
    const size_t nt = m.triangleCount();
    auto included = [&](size_t part) { return s.parts.empty() || (part < s.parts.size() && s.parts[part]); };
    Box3 bb;
    for (size_t i = 0; i < m.parts.size(); ++i)
        if (included(i) && m.parts[i].bounds.valid()) bb.expand(m.parts[i].bounds);
    if (nt == 0 || !bb.valid()) return est;
    const double H = bb.max.z - bb.min.z;
    const double h = std::max(0.02f, s.layerHeight), h1 = std::max(0.02f, s.firstLayerHeight);
    const int nLayers = H <= h1 ? 1 : 1 + (int)std::ceil((H - h1) / h - 1e-6);
    est.layers = nLayers;

    // Grid: fine enough for line widths, coarser for big models so the work stays bounded on a
    // phone (cells per layer and cells over all layers are both capped).
    const double margin = 2.0;
    const double ex = bb.max.x - bb.min.x + 2 * margin, ey = bb.max.y - bb.min.y + 2 * margin;
    double c = std::max({0.13, std::sqrt(ex * ey / 250000.0), std::sqrt(ex * ey * nLayers / 1.0e8)});
    c = std::max(c, std::max(ex, ey) / 1500.0);
    Grid g;
    g.c = c;
    g.x0 = bb.min.x - margin;
    g.y0 = bb.min.y - margin;
    g.nx = std::max(1, (int)std::ceil(ex / c));
    g.ny = std::max(1, (int)std::ceil(ey / c));
    const double cellArea = c * c;

    // Triangles sorted by their lowest point, for a sweep through the layers.
    std::vector<uint32_t> order;
    std::vector<float> tzmin(nt), tzmax(nt);
    std::vector<uint8_t> flip(nt, 0);
    order.reserve(nt);
    for (size_t pi = 0; pi < m.parts.size(); ++pi) {
        if (!included(pi)) continue;
        const Part& p = m.parts[pi];
        for (uint32_t t = p.firstIndex / 3; t < (p.firstIndex + p.indexCount) / 3; ++t) {
            flip[t] = p.volume < 0;
            float a = m.positions[3 * m.indices[3 * t] + 2], b = m.positions[3 * m.indices[3 * t + 1] + 2], cz = m.positions[3 * m.indices[3 * t + 2] + 2];
            tzmin[t] = std::min({a, b, cz});
            tzmax[t] = std::max({a, b, cz});
            order.push_back(t);
        }
    }
    const size_t nOrder = order.size();
    std::sort(order.begin(), order.end(), [&](uint32_t a, uint32_t b) { return tzmin[a] < tzmin[b]; });
    // Shell counts: at least the layers, at least the thickness.
    const int topN = std::max(1, std::max(s.topLayers, (int)std::ceil(s.topThickness / h - 1e-6)));
    const int botN = std::max(1, std::max(s.bottomLayers, (int)std::ceil(s.bottomThickness / h - 1e-6)));

    const double jerk = std::max(0.5f, s.jerk);
    const double flowCap = std::max(0.5f, s.maxVolumetricSpeed);
    const double supportDist = h / std::tan((std::clamp(s.supportAngle, 1.f, 89.f) + 1.f) * kPi / 180.f);
    const int supportR = std::max(1, (int)std::ceil(supportDist / c - 0.5));
    const int walls = std::max(1, s.walls);
    const double zAccel = 500;
    const double retractNominal = 2 * s.retractLength / std::max(1.f, s.retractSpeed) + 2 * s.zHop / std::max(1.f, s.zSpeed);
    const double retractTime = 2 * s.retractLength / std::max(1.f, s.retractSpeed) +
                               (s.zHop > 0 ? moveTime(s.zHop, s.zSpeed, zAccel, 0, 0) : 0);

    // Support grid: cells at the support line pitch; per layer, how much of each cell the model
    // fills and whether support was already counted there.
    struct SupportGrid {
        int nx = 1, ny = 1;
        double c = 1;
        size_t size() const { return (size_t)nx * ny; }
        uint32_t at(const Grid& g, int x, int y) const {
            const int cx = std::min(nx - 1, (int)((x + 0.5) * g.c / c)), cy = std::min(ny - 1, (int)((y + 0.5) * g.c / c));
            return (uint32_t)(cy * nx + cx);
        }
    } sg;
    std::vector<uint16_t> supOcc;
    std::vector<std::vector<uint32_t>> supRoof;
    if (s.supports) {
        sg.c = std::max(0.5, (double)s.supportSpacing + s.supportWidth - h * (1 - kPi / 4));
        sg.nx = std::max(1, (int)std::ceil(g.nx * c / sg.c));
        sg.ny = std::max(1, (int)std::ceil(g.ny * c / sg.c));
        supOcc.assign(sg.size() * nLayers, 0);
        supRoof.resize(nLayers);
    }
    // Layers are worked on in chunks, in parallel. A chunk slices a few extra layers around it (for
    // the shells) and works through the layer before it first (the layer below matters).
    const int chunk = std::max(8, std::min(64, nLayers / (int)(4 * workerCount()) + 1));
    const int nChunks = (nLayers + chunk - 1) / chunk;
    std::atomic<int> nextChunk{0}, layersDone{0};
    std::atomic<bool> cancelled{false};
    std::atomic<unsigned> helpersDone{0};

    // Progress goes through the calling thread only (it may call back into Java).
    auto report = [&](int n) {
        try {
            progress.report(0.98f * (float)n / (float)nLayers);
        } catch (const CancelledError&) {
            cancelled = true;
        }
    };
    auto worker = [&](PrintEstimate& est, bool reporter) {
        size_t next = 0;
        std::vector<uint32_t> active;
        std::vector<std::vector<std::pair<float, int>>> rows(g.ny);
        auto slice = [&](double z, Layer& L) {
            while (next < nOrder && tzmin[order[next]] <= z) active.push_back(order[next++]);
            active.erase(std::remove_if(active.begin(), active.end(), [&](uint32_t t) { return tzmax[t] < z; }), active.end());
            std::vector<Seg> segs;
            segs.reserve(active.size() / 2 + 16);
            for (uint32_t t : active) {
                uint32_t vi[3] = {m.indices[3 * t], m.indices[3 * t + 1], m.indices[3 * t + 2]};
                Vec3 p[3] = {m.vertex(vi[0]), m.vertex(vi[1]), m.vertex(vi[2])};
                bool up[3] = {p[0].z >= z, p[1].z >= z, p[2].z >= z};
                if (up[0] == up[1] && up[1] == up[2]) continue;
                Vec3 n = cross(p[1] - p[0], p[2] - p[0]);
                if (flip[t]) n = -n;
                float px[2], py[2];
                uint64_t key[2];
                int k = 0;
                for (int e = 0; e < 3 && k < 2; ++e) {
                    int a = e, b = (e + 1) % 3;
                    if (up[a] == up[b]) continue;
                    double tt = (z - p[a].z) / (double)(p[b].z - p[a].z);
                    px[k] = (float)(p[a].x + (p[b].x - p[a].x) * tt);
                    py[k] = (float)(p[a].y + (p[b].y - p[a].y) * tt);
                    uint64_t ia = m.posIds[vi[a]], ib = m.posIds[vi[b]];
                    key[k] = std::min(ia, ib) << 32 | std::max(ia, ib);
                    ++k;
                }
                if (k != 2) continue;
                // Solid on the left: the segment runs along cross(z, n).
                double dx = px[1] - px[0], dy = py[1] - py[0];
                if (dx * dx + dy * dy < 1e-14) continue;
                if (dx * -n.y + dy * n.x < 0) {
                    std::swap(px[0], px[1]);
                    std::swap(py[0], py[1]);
                    std::swap(key[0], key[1]);
                }
                const double nxy = std::sqrt((double)n.x * n.x + (double)n.y * n.y);
                const float slope = n.z < 0 ? (float)std::min(1e3, -n.z / std::max(nxy, 1e-12)) : 0.f;
                segs.push_back({px[0], py[0], px[1], py[1], slope, key[0], key[1]});
            }
            // Fill with the non-zero winding rule (overlapping parts merge, like a slicer's union).
            L.inside.assign(g.size(), 0);
            for (auto& r : rows) r.clear();
            for (const Seg& sg : segs) {
                double ylo = std::min(sg.ay, sg.by), yhi = std::max(sg.ay, sg.by);
                int j0 = (int)std::ceil((ylo - g.y0) / c - 0.5), j1 = (int)std::ceil((yhi - g.y0) / c - 0.5) - 1;
                j0 = std::max(j0, 0);
                j1 = std::min(j1, g.ny - 1);
                int w = sg.by > sg.ay ? 1 : -1;
                for (int j = j0; j <= j1; ++j) {
                    double y = g.y0 + (j + 0.5) * c;
                    double x = sg.ax + (sg.bx - sg.ax) * (y - sg.ay) / (sg.by - sg.ay);
                    rows[j].push_back({(float)x, w});
                }
            }
            Box& bx = L.box;
            bx = {g.nx, g.ny, 0, 0};
            for (int j = 0; j < g.ny; ++j) {
                auto& r = rows[j];
                if (r.empty()) continue;
                std::sort(r.begin(), r.end());
                int wind = 0;
                for (size_t i = 0; i + 1 < r.size(); ++i) {
                    wind += r[i].second;
                    if (wind == 0) continue;
                    int i0 = std::max(0, (int)std::ceil((r[i].first - g.x0) / c - 0.5));
                    int i1 = std::min(g.nx - 1, (int)std::ceil((r[i + 1].first - g.x0) / c - 0.5) - 1);
                    if (i0 > i1) continue;
                    std::fill(L.inside.begin() + (size_t)j * g.nx + i0, L.inside.begin() + (size_t)j * g.nx + i1 + 1, 1);
                    bx.x0 = std::min(bx.x0, i0);
                    bx.x1 = std::max(bx.x1, i1 + 1);
                    bx.y0 = std::min(bx.y0, j);
                    bx.y1 = std::max(bx.y1, j + 1);
                }
            }
            // Join segments into loops through the mesh edges they share: open chains first, then
            // closed cycles; chain ends that nearly meet (cracks and seams in the mesh) are stitched.
            const size_t ns = segs.size();
            std::unordered_map<uint64_t, uint32_t> start;
            start.reserve(ns * 2);
            for (uint32_t i = 0; i < ns; ++i) start.emplace(segs[i].ka, i);
            std::vector<int32_t> nextSeg(ns, -1);
            std::vector<uint8_t> hasPrev(ns, 0), used(ns, 0);
            for (uint32_t i = 0; i < ns; ++i) {
                auto it = start.find(segs[i].kb);
                if (it == start.end() || it->second == i) continue;
                nextSeg[i] = (int32_t)it->second;
                hasPrev[it->second] = 1;
            }
            std::vector<std::vector<uint32_t>> chains;
            std::vector<uint8_t> open;
            auto walk = [&](uint32_t i) {
                std::vector<uint32_t> ch;
                int32_t cur = (int32_t)i;
                while (cur >= 0 && !used[cur]) {
                    used[cur] = 1;
                    ch.push_back((uint32_t)cur);
                    cur = nextSeg[cur];
                }
                const bool isOpen = cur != (int32_t)i;
                chains.push_back(std::move(ch));
                open.push_back(isOpen);
            };
            for (uint32_t i = 0; i < ns; ++i)
                if (!hasPrev[i] && !used[i]) walk(i);
            for (uint32_t i = 0; i < ns; ++i)
                if (!used[i]) walk(i);
            const double tol2 = 0.05 * 0.05;
            size_t nOpen = 0;
            for (uint8_t o : open) nOpen += o;
            if (nOpen > 0 && nOpen < 4000) {
                for (size_t a = 0; a < chains.size(); ++a) {
                    while (open[a] && !chains[a].empty()) {
                        const Seg& e = segs[chains[a].back()];
                        const Seg& s0 = segs[chains[a].front()];
                        if ((e.bx - s0.ax) * (e.bx - s0.ax) + (e.by - s0.ay) * (e.by - s0.ay) < tol2) {
                            open[a] = 0;  // closes on itself
                            break;
                        }
                        size_t best = SIZE_MAX;
                        double bestD = tol2;
                        for (size_t b = 0; b < chains.size(); ++b) {
                            if (b == a || !open[b] || chains[b].empty()) continue;
                            const Seg& f = segs[chains[b].front()];
                            const double d2 = (e.bx - f.ax) * (e.bx - f.ax) + (e.by - f.ay) * (e.by - f.ay);
                            if (d2 < bestD) {
                                bestD = d2;
                                best = b;
                            }
                        }
                        if (best == SIZE_MAX) break;
                        chains[a].insert(chains[a].end(), chains[best].begin(), chains[best].end());
                        chains[best].clear();
                        open[best] = 0;
                    }
                }
            }
            for (size_t ci = 0; ci < chains.size(); ++ci) {
                const auto& ch = chains[ci];
                if (ch.size() < 2) continue;
                Loop lp;
                for (uint32_t i : ch) {
                    lp.pts.push_back({segs[i].ax, segs[i].ay});
                    lp.slope.push_back(segs[i].slope);
                }
                if (open[ci]) {
                    lp.pts.push_back({segs[ch.back()].bx, segs[ch.back()].by});
                    lp.slope.push_back(segs[ch.back()].slope);
                }
                if (lp.pts.size() < 3) continue;
                for (size_t k = 0; k < lp.pts.size(); ++k) {
                    const V2& a = lp.pts[k];
                    const V2& b = lp.pts[(k + 1) % lp.pts.size()];
                    lp.length += std::hypot(b.x - a.x, b.y - a.y);
                    lp.area += 0.5 * (a.x * b.y - b.x * a.y);
                }
                if (lp.length > 0.3) {
                    simplifyLoop(lp, 0.02);
                    L.loops.push_back(std::move(lp));
                }
            }
            L.segs = std::move(segs);
        };

        std::vector<float> depth(g.size(), 0.f);
        std::vector<uint8_t> prevInside(g.size(), 0), prevCls(g.size(), kEmpty), cls(g.size(), kEmpty), roof(g.size(), 0);
        std::vector<int> label(g.size(), -1), rowScratch(g.size(), 0);
        std::vector<uint8_t> mask(g.size(), 0), mask2(g.size(), 0), mask3(g.size(), 0);
        std::vector<uint32_t> stack;
        Box prevBox;

        // Run lengths (mm) of each fill class along each grid direction, for the current layer.
        std::vector<float> runs[kClassCount][4];
        // Works out layer k. The warm-up layer before a chunk (keep = false) only sets up "the layer
        // below"; its results are thrown away.
        auto layer = [&](int k, Layer& L, auto& layerAt, PrintEstimate& est, bool keep) {
            const Box B = L.box;
            const bool firstLayer = k == 0;
            const double lh = L.h;
            auto spacing = [&](double w) { return std::max(0.05, w - lh * (1 - kPi / 4)); };
            auto area = [&](double w) { return (double)extrusionArea((float)lh, (float)w); };
            auto flowLimited = [&](double v, double w) { return std::min(v, flowCap / area(w)); };

            double tTravelFixed = 0, tTravelNominal = 0, travelMm = 0;
            int travels = 0, retracts = 0;
            // Layer change (its travel and retraction are the first contour's).
            tTravelFixed += moveTime(lh, s.zSpeed, zAccel, 0, 0);

            if (B.empty()) {
                est.travel += tTravelFixed;
                std::fill(prevInside.begin(), prevInside.end(), 0);
                std::fill(prevCls.begin(), prevCls.end(), kEmpty);
                prevBox = B;
                return;
            }

            // Line widths and where each wall sits (depth of its centre line from the contour).
            const double wOut = firstLayer ? s.firstLayerWidth : s.outerWidth;
            const double wIn = firstLayer ? s.firstLayerWidth : s.innerWidth;
            const double sOut = spacing(wOut), sIn = spacing(wIn);
            const double d0 = wOut / 2;
            const double outerEnd = d0 + sOut / 2;
            const double wallEnd = walls > 1 ? outerEnd + (walls - 1) * sIn : outerEnd;
            const double lastSpacing = walls > 1 ? sIn : sOut;
            const double infillEdge = wallEnd - lastSpacing / 2 + lastSpacing * (1 - s.infillOverlap);
            const double topEdge = outerEnd - sOut / 2 + sOut * (1 - s.infillOverlap);

            // Distance from each cell to the layer's contour, exact within `reach` of it (deeper cells
            // only need to know they are deeper). Segments inside the part (where overlapping parts
            // meet) are not contour and are skipped.
            const int X0 = std::max(0, B.x0 - 1), Y0 = std::max(0, B.y0 - 1), X1 = std::min(g.nx, B.x1 + 1), Y1 = std::min(g.ny, B.y1 + 1);
            const double reach = std::max(infillEdge, topEdge) + c;
            for (int y = Y0; y < Y1; ++y) std::fill(depth.begin() + (size_t)y * g.nx + X0, depth.begin() + (size_t)y * g.nx + X1, 1e9f);
            for (const Seg& sg : L.segs) {
                const double ax = sg.ax, ay = sg.ay, dx = sg.bx - sg.ax, dy = sg.by - sg.ay;
                const double len2 = dx * dx + dy * dy, len = std::sqrt(len2);
                {
                    const double ox = ax + 0.5 * dx + dy / len * c, oy = ay + 0.5 * dy - dx / len * c;  // just outside
                    if (L.inside[(size_t)g.cellY(oy) * g.nx + g.cellX(ox)]) continue;
                }
                const int pieces = std::max(1, (int)std::ceil(len / (2 * reach)));
                for (int pc = 0; pc < pieces; ++pc) {
                    const double t0 = (double)pc / pieces, t1 = (double)(pc + 1) / pieces;
                    const double px0 = ax + dx * t0, py0 = ay + dy * t0, px1 = ax + dx * t1, py1 = ay + dy * t1;
                    const int cx0 = std::max(X0, g.cellX(std::min(px0, px1) - reach)), cx1 = std::min(X1 - 1, g.cellX(std::max(px0, px1) + reach));
                    const int cy0 = std::max(Y0, g.cellY(std::min(py0, py1) - reach)), cy1 = std::min(Y1 - 1, g.cellY(std::max(py0, py1) + reach));
                    for (int y = cy0; y <= cy1; ++y) {
                        const double qy = g.y0 + (y + 0.5) * c;
                        const size_t row = (size_t)y * g.nx;
                        for (int x = cx0; x <= cx1; ++x) {
                            const double qx = g.x0 + (x + 0.5) * c;
                            double t = len2 > 0 ? ((qx - ax) * dx + (qy - ay) * dy) / len2 : 0;
                            t = std::clamp(t, t0, t1);
                            const double ex2 = qx - (ax + dx * t), ey2 = qy - (ay + dy * t);
                            const float d = (float)std::sqrt(ex2 * ex2 + ey2 * ey2);
                            if (d < depth[row + x]) depth[row + x] = d;
                        }
                    }
                }
            }

            // Classify the cells. Wall and fill areas are measured with each cell spanning a range of
            // depths (± half a cell), so walls lying along the grid don't snap to whole cells.
            Layer* above = layerAt(k + 1);
            double outerArea = 0, innerArea = 0, fillExact = 0, fillCells = 0;
            const double half = 0.5 * c;
            auto share = [&](double dep, double lo, double hi) {
                return std::clamp((std::min(hi, dep + half) - std::max(lo, dep - half)) / c, 0.0, 1.0);
            };
            for (int y = Y0; y < Y1; ++y) {
                const size_t row = (size_t)y * g.nx;
                for (int x = X0; x < X1; ++x) {
                    const size_t i = row + x;
                    cls[i] = kEmpty;
                    if (!L.inside[i]) {
                        // Just outside the contour, a cell still spans the start of the outer wall.
                        if (depth[i] < half) outerArea += cellArea * share(-depth[i], d0 - sOut / 2, outerEnd);
                        continue;
                    }
                    const double dep = depth[i];
                    const bool topSurface = !above || !above->inside[i];
                    const bool oneWall = topSurface && s.topOneWall;
                    const double edge = oneWall ? topEdge : infillEdge;
                    if (dep < outerEnd + half) outerArea += cellArea * share(dep, d0 - sOut / 2, outerEnd);
                    if (!oneWall && dep < wallEnd + half) innerArea += cellArea * share(dep, outerEnd, wallEnd);
                    if (dep > edge - half) fillExact += share(dep, edge, 1e30);
                    if (dep < edge) {
                        cls[i] = kWall;
                        continue;
                    }
                    fillCells += 1;
                    if (oneWall) {
                        cls[i] = kTop;
                        continue;
                    }
                    // Shells: solid where the column ends within the shell count above or below.
                    bool top = topSurface, bottom = false;
                    for (int j = 2; j <= topN && !top; ++j) {
                        Layer* a = layerAt(k + j);
                        top = !a || !a->inside[i];
                    }
                    for (int j = 1; j <= botN && !bottom; ++j) {
                        Layer* b = layerAt(k - j);
                        bottom = !b || !b->inside[i];
                    }
                    uint8_t cl;
                    if (topSurface) cl = kTop;
                    else if (top) cl = kSolid;
                    else if (bottom) cl = (k == 0 || !prevInside[i]) ? kBottom : kSolid;
                    else cl = kSparse;
                    cls[i] = cl;
                }
            }
            const double fillScale = fillCells > 0 ? fillExact / fillCells : 1;

            // Vertical shells: solid infill also reaches a little sideways from the shells (sloped
            // surfaces get a thicker shell), and sparse infill too thin to print becomes solid.
            {
                const int e = std::max(1, (int)std::lround(sIn / c));
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        mask[i] = cls[i] == kSolid || cls[i] == kTop || cls[i] == kBottom;
                    }
                auto sparseCell = [&](size_t i) { return cls[i] == kSparse; };
                dilate(mask, g.nx, X0, Y0, X1, Y1, e, mask2, rowScratch, sparseCell);
                const int r = std::max(1, (int)std::lround(0.5 * sIn / c));
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        if (cls[i] == kSparse && mask2[i]) cls[i] = kSolid;
                        mask[i] = cls[i] != kSparse;
                    }
                dilate(mask, g.nx, X0, Y0, X1, Y1, r, mask2, rowScratch, sparseCell);  // mask2: sparse eroded away
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        mask[i] = !mask2[i] && cls[i] == kSparse;
                    }
                dilate(mask, g.nx, X0, Y0, X1, Y1, r, mask2, rowScratch, sparseCell);  // opened sparse
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        if (cls[i] == kSparse && !mask2[i]) cls[i] = kSolid;
                    }
            }
            // Internal bridges: solid infill over sparse infill, where that area is wide enough to need
            // bridging (thin strips on sloped shells rest on the walls).
            if (k > 0) {
                const int rb = std::max(1, (int)std::lround(1.5 * sIn / c));
                bool any = false;
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        mask[i] = (cls[i] == kSolid || cls[i] == kTop) && prevCls[i] == kSparse;
                        any = any || mask[i];
                    }
                if (any) {
                    std::vector<uint8_t>& notOver = mask3;
                    for (int y = Y0; y < Y1; ++y)
                        for (int x = X0; x < X1; ++x) notOver[(size_t)y * g.nx + x] = !mask[(size_t)y * g.nx + x];
                    auto candidate = [&](size_t i) { return mask[i] != 0; };
                    dilate(notOver, g.nx, X0, Y0, X1, Y1, rb, mask2, rowScratch, candidate);
                    for (int y = Y0; y < Y1; ++y)
                        for (int x = X0; x < X1; ++x) {
                            const size_t i = (size_t)y * g.nx + x;
                            notOver[i] = mask[i] && !mask2[i];  // eroded
                        }
                    dilate(notOver, g.nx, X0, Y0, X1, Y1, rb, mask2, rowScratch, candidate);
                    for (int y = Y0; y < Y1; ++y)
                        for (int x = X0; x < X1; ++x) {
                            const size_t i = (size_t)y * g.nx + x;
                            if (mask[i] && mask2[i]) cls[i] = kBridge;
                        }
                }
            }

            // Overhangs: cells further out over the layer below than the support angle allows. Bottom
            // surfaces there are bridged (over air, or over the support's sparse interface).
            for (int y = Y0; y < Y1; ++y) std::fill(roof.begin() + (size_t)y * g.nx + X0, roof.begin() + (size_t)y * g.nx + X1, 0);
            double roofArea = 0;
            if (k > 0) {
                const int RX0 = std::max(0, X0 - supportR), RY0 = std::max(0, Y0 - supportR);
                const int RX1 = std::min(g.nx, X1 + supportR), RY1 = std::min(g.ny, Y1 + supportR);
                auto fresh = [&](size_t i) { return L.inside[i] && !prevInside[i]; };
                dilate(prevInside, g.nx, RX0, RY0, RX1, RY1, supportR, mask2, rowScratch, fresh);  // mask2: near the layer below
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        roof[i] = fresh(i) && !mask2[i];
                    }
                // Like slicers, grow what needs support back to the whole overhang around it (the strip
                // within the angle's reach of the layer below rests on the same support).
                dilate(roof, g.nx, X0, Y0, X1, Y1, supportR + 1, mask2, rowScratch, fresh);
                for (int y = B.y0; y < B.y1; ++y)
                    for (int x = B.x0; x < B.x1; ++x) {
                        const size_t i = (size_t)y * g.nx + x;
                        roof[i] = mask2[i] && L.inside[i] && !prevInside[i];
                        if (!roof[i]) continue;
                        roofArea += cellArea;
                        if (cls[i] == kBottom) cls[i] = kBridge;
                    }
            }
            est.supportAreaMm2 += roofArea;

            // Connected regions of each fill class: small sparse regions become solid, regions too thin
            // for more than one line are printed as a single line (gap fill), and every region costs a
            // travel move.
            int regions = 0;
            std::vector<double> gapRuns;
            {
                const int rc = std::max(1, (int)std::ceil(0.5 * sIn / c));
                for (int y = Y0; y < Y1; ++y)
                    for (int x = X0; x < X1; ++x) mask[(size_t)y * g.nx + x] = cls[(size_t)y * g.nx + x] < kSparse;
                dilate(mask, g.nx, X0, Y0, X1, Y1, rc, mask3, rowScratch, [&](size_t i) { return cls[i] >= kSparse; });  // mask3: not core
                for (int y = Y0; y < Y1; ++y)
                    std::fill(label.begin() + (size_t)y * g.nx + X0, label.begin() + (size_t)y * g.nx + X1, -1);
                for (int y = Y0; y < Y1; ++y) {
                    for (int x = X0; x < X1; ++x) {
                        const size_t i0 = (size_t)y * g.nx + x;
                        if (cls[i0] < kSparse || label[i0] >= 0) continue;
                        const uint8_t cl = cls[i0];
                        stack.clear();
                        stack.push_back((uint32_t)i0);
                        label[i0] = regions;
                        size_t members = 0, head = 0;
                        bool core = false;
                        while (head < stack.size()) {
                            const uint32_t i = stack[head++];
                            ++members;
                            core = core || !mask3[i];
                            const int cx = (int)(i % g.nx), cy = (int)(i / g.nx);
                            const int nb[4][2] = {{cx - 1, cy}, {cx + 1, cy}, {cx, cy - 1}, {cx, cy + 1}};
                            for (auto& q : nb) {
                                if (q[0] < X0 || q[0] >= X1 || q[1] < Y0 || q[1] >= Y1) continue;
                                const size_t j = (size_t)q[1] * g.nx + q[0];
                                if (cls[j] != cl || label[j] >= 0) continue;
                                label[j] = regions;
                                stack.push_back((uint32_t)j);
                            }
                        }
                        if (!core) {
                            // Too thin for infill lines: one line along it, unless it is only a speck.
                            const double len = members * cellArea / sIn;
                            for (uint32_t i : stack) cls[i] = len >= 1.0 ? kGap : kWall;
                            if (len >= 1.0) gapRuns.push_back(len);
                        } else if (cl == kSparse && members * cellArea < s.minSparseArea) {
                            for (uint32_t i : stack) cls[i] = kSolid;
                        }
                        // Specks (corners trimmed off a region) are printed with their neighbours.
                        if (members * cellArea >= std::max(0.5, 4 * sIn * sIn)) ++regions;
                    }
                }
            }

            // Runs of each fill class along the grid directions the fills use.
            const int solidDir = (k % 2 == 0) ? kDiagUp : kDiagDown;
            bool needDir[4] = {false, false, false, false};
            needDir[solidDir] = true;
            const bool gyroid = s.infillPattern == PrintSettings::kGyroid;
            const int nd = s.infillPattern == PrintSettings::kGrid ? 2 : s.infillPattern == PrintSettings::kCubic ? 3 : 1;
            int sparseDirs[3], nSparseDirs = 0;
            if (nd == 1) sparseDirs[nSparseDirs++] = solidDir;
            else {
                sparseDirs[nSparseDirs++] = kDiagUp;
                sparseDirs[nSparseDirs++] = kDiagDown;
                if (nd == 3) sparseDirs[nSparseDirs++] = (k % 2 == 0) ? kAlongX : kAlongY;
            }
            for (int i = 0; i < nSparseDirs; ++i) needDir[sparseDirs[i]] = true;
            for (auto& perClass : runs)
                for (auto& r : perClass) r.clear();
            for (int d = 0; d < 4; ++d) {
                if (!needDir[d]) continue;
                const double step = d >= kDiagUp ? c * kSqrt2 : c;
                // A line keeps going across a cell or two of something else (raster noise where the
                // region's edge runs along the line).
                auto walk = [&](int x, int y, int sx, int sy) {
                    uint8_t cur = kEmpty;
                    int n = 0, gap = 0;
                    for (; x >= X0 && x < X1 && y >= Y0 && y < Y1; x += sx, y += sy) {
                        const uint8_t cl = cls[(size_t)y * g.nx + x];
                        if (cl == cur && cur >= kSparse) {
                            ++n;
                            gap = 0;
                            continue;
                        }
                        if (cl < kSparse && cur >= kSparse && gap < 2) {
                            ++gap;
                            continue;
                        }
                        if (cur >= kSparse && n > 0) runs[cur][d].push_back((float)(n * step));
                        cur = cl;
                        n = cl >= kSparse ? 1 : 0;
                        gap = 0;
                    }
                    if (cur >= kSparse && n > 0) runs[cur][d].push_back((float)(n * step));
                };
                if (d == kAlongX)
                    for (int y = Y0; y < Y1; ++y) walk(X0, y, 1, 0);
                else if (d == kAlongY)
                    for (int x = X0; x < X1; ++x) walk(x, Y0, 0, 1);
                else if (d == kDiagUp) {
                    for (int y = Y1 - 1; y >= Y0; --y) walk(X0, y, 1, 1);
                    for (int x = X0 + 1; x < X1; ++x) walk(x, Y0, 1, 1);
                } else {
                    for (int y = Y0; y < Y1; ++y) walk(X0, y, 1, -1);
                    for (int x = X0 + 1; x < X1; ++x) walk(x, Y1 - 1, 1, -1);
                }
            }

            // ---- the layer's work: walls, fills, then travel; timed with an optional speed cap from
            // the minimum layer time.
            struct FillSpec {
                uint8_t cls;
                double width, lineSpacing, speed, accel, crossSection;
                bool sparse;
            };
            const double fl = firstLayer ? s.firstLayerInfillSpeed : 0;
            const double wInfill = firstLayer ? s.firstLayerWidth : s.infillWidth;
            const double wSolid = firstLayer ? s.firstLayerWidth : s.solidWidth;
            const double wTop = firstLayer ? s.firstLayerWidth : s.topWidth;
            const double aFirst = s.firstLayerAccel;
            const double density = std::clamp(s.infillDensity, 0.f, 1.f);
            const double bridgeArea = kPi * wSolid * wSolid / 4;
            FillSpec specs[] = {
                {kSparse, wInfill, density > 0 ? spacing(wInfill) / density : 1e9, flowLimited(fl ? fl : s.infillSpeed, wInfill),
                 firstLayer ? aFirst : s.infillAccel, area(wInfill), true},
                {kSolid, wSolid, spacing(wSolid), flowLimited(fl ? fl : s.solidSpeed, wSolid), firstLayer ? aFirst : s.solidAccel, area(wSolid), false},
                {kTop, wTop, spacing(wTop), flowLimited(fl ? fl : s.topSpeed, wTop), firstLayer ? aFirst : s.topAccel, area(wTop), false},
                {kBottom, wSolid, spacing(wSolid), flowLimited(fl ? fl : s.solidSpeed, wSolid), firstLayer ? aFirst : s.solidAccel, area(wSolid), false},
                {kBridge, wSolid, wSolid + 0.05, std::min((double)s.bridgeSpeed, flowCap / bridgeArea), firstLayer ? aFirst : s.solidAccel,
                 bridgeArea, false},
            };
            double fillLen[kClassCount] = {0}, sparsePieces = 0;
            for (const FillSpec& f : specs) {
                if (f.sparse && density <= 0) continue;
                double len = 0;
                const int* dirs = f.sparse ? sparseDirs : &solidDir;
                const int ndirs = f.sparse ? nSparseDirs : 1;
                for (int di = 0; di < ndirs; ++di) {
                    const int d = dirs[di];
                    const double perp = d >= kDiagUp ? c / kSqrt2 : c;
                    const double lines = perp / (f.lineSpacing * ndirs);
                    // An anchor runs along the wall at most halfway to the next line's end.
                    const double anchorGap = 0.5 * f.lineSpacing * kSqrt2;
                    double sum = 0;
                    for (float r : runs[f.cls][d]) sum += r + (f.sparse ? 2 * std::min({(double)s.infillAnchor, 0.5 * r, anchorGap}) : f.lineSpacing * kSqrt2);
                    len += lines * sum;
                    if (f.sparse) sparsePieces += lines * runs[f.cls][d].size();
                }
                fillLen[f.cls] = len;
            }

            double gapLen = 0;
            for (double& r : gapRuns) gapLen += (r *= fillScale);
            for (double& l : fillLen) l *= fillScale;
            sparsePieces *= fillScale;

            // Walls: the outer wall follows the contour; inner walls are timed like it.
            const double outerLen = outerArea / sOut, innerLen = innerArea / sIn;
            double contourLen = 0;
            for (const Loop& lp : L.loops) contourLen += lp.length;
            const double vOuter = flowLimited(firstLayer ? s.firstLayerSpeed : s.outerSpeed, wOut);
            const double vInner = flowLimited(firstLayer ? s.firstLayerSpeed : s.innerSpeed, wIn);
            const double aOuter = firstLayer ? aFirst : s.outerAccel;
            const double aInner = firstLayer ? aFirst : s.innerAccel;
            std::vector<std::vector<double>> outerLim(L.loops.size());
            for (size_t li = 0; li < L.loops.size(); ++li) {
                const Loop& lp = L.loops[li];
                const size_t n = lp.pts.size();
                const bool small = lp.length < 2 * kPi * s.smallPerimeterRadius;
                auto& lim = outerLim[li];
                lim.assign(n, small ? vOuter * s.smallPerimeterFactor : vOuter);
                if (firstLayer) continue;
                for (size_t q = 0; q < n; ++q) {
                    // How much of the line hangs over the layer below: from the surface's slope, or all
                    // of it when there is nothing below.
                    double over = lp.slope[q] * lh / wOut;
                    const V2& a = lp.pts[q];
                    const V2& b = lp.pts[(q + 1) % n];
                    double dx = b.x - a.x, dy = b.y - a.y, l = std::hypot(dx, dy);
                    if (l > 0) {
                        const double px = 0.5 * (a.x + b.x) - dy / l * wOut * 0.75, py = 0.5 * (a.y + b.y) + dx / l * wOut * 0.75;
                        const int cx = g.cellX(px), cy = g.cellY(py);
                        bool below = false;
                        for (int yy = std::max(0, cy - 1); yy <= std::min(g.ny - 1, cy + 1) && !below; ++yy)
                            for (int xx = std::max(0, cx - 1); xx <= std::min(g.nx - 1, cx + 1) && !below; ++xx)
                                below = prevInside[(size_t)yy * g.nx + xx];
                        if (!below) over = 1;
                    }
                    const int bucket = over >= 0.75 ? 2 : over >= 0.5 ? 1 : over >= 0.25 ? 0 : -1;
                    if (bucket >= 0 && s.overhangSpeed[bucket] > 0) lim[q] = std::min(lim[q], (double)s.overhangSpeed[bucket]);
                }
            }

            // Skirt around the first layer.
            double skirtLen = 0;
            if (firstLayer && s.skirtLoops > 0) {
                std::vector<V2> pts;
                for (const Loop& lp : L.loops)
                    if (lp.area > 0) pts.insert(pts.end(), lp.pts.begin(), lp.pts.end());
                if (pts.size() >= 3) {
                    std::sort(pts.begin(), pts.end(), [](const V2& a, const V2& b) { return a.x < b.x || (a.x == b.x && a.y < b.y); });
                    std::vector<V2> hull(2 * pts.size());
                    size_t kk = 0;
                    auto crossz = [](const V2& o, const V2& a, const V2& b) { return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x); };
                    for (size_t i = 0; i < pts.size(); ++i) {
                        while (kk >= 2 && crossz(hull[kk - 2], hull[kk - 1], pts[i]) <= 0) --kk;
                        hull[kk++] = pts[i];
                    }
                    for (size_t i = pts.size() - 1, t = kk + 1; i-- > 0;) {
                        while (kk >= t && crossz(hull[kk - 2], hull[kk - 1], pts[i]) <= 0) --kk;
                        hull[kk++] = pts[i];
                    }
                    double per = 0;
                    for (size_t i = 0; i + 1 < kk; ++i) per += std::hypot(hull[i + 1].x - hull[i].x, hull[i + 1].y - hull[i].y);
                    for (int i = 0; i < s.skirtLoops; ++i) skirtLen += per + 2 * kPi * (s.skirtDistance + i * sOut);
                }
            }

            // Travel: across the layer to every contour's walls (with a retraction), between its walls,
            // into every fill region, and between groups of sparse infill lines.
            const double layerArea = (double)(B.x1 - B.x0) * (B.y1 - B.y0) * cellArea;
            auto hop = [&](double n, double dist, double retractShare) {
                if (n <= 0) return;
                dist = std::max(0.3, dist);
                const double share = dist >= s.retractMinTravel ? retractShare : 0;
                tTravelFixed += n * (moveTime(dist, s.travelSpeed, s.travelAccel, jerk, jerk) + share * retractTime);
                tTravelNominal += n * (dist / std::max(1.f, s.travelSpeed) + share * retractNominal);
                travels += (int)std::lround(n);
                retracts += (int)std::lround(n * share);
                travelMm += n * dist;
            };
            const int nLoops = (int)L.loops.size();
            hop(nLoops, 0.9 * std::sqrt(layerArea / std::max(1, nLoops)), 1);
            hop(nLoops * (walls - 1), 1.5 * sIn, 0);
            hop(regions, 0.5 * std::sqrt(layerArea / std::max(1, regions)), 0.8);
            if (skirtLen > 0) hop(1, 0.5 * std::sqrt(layerArea) + s.skirtDistance, 1);
            if (fillLen[kSparse] > 0) {
                const double perDir = specs[0].lineSpacing * nSparseDirs;
                hop(0.17 * sparsePieces, std::min(perDir * kSqrt2, std::sqrt(layerArea)), 0.1);
            }

            // Times with a speed cap (cooling slowdown); `nominal` is length / speed, as slicers use
            // to decide on the slowdown.
            double tOuter = 0, tInner = 0, tFill[kClassCount] = {0}, tSkirt = 0;
            auto timeLayer = [&](double cap, bool nominal) {
                tOuter = tInner = tSkirt = 0;
                std::fill(std::begin(tFill), std::end(tFill), 0.0);
                const double jc = std::min(jerk, cap);
                if (contourLen > 0) {
                    for (size_t li = 0; li < L.loops.size(); ++li) {
                        const Loop& lp = L.loops[li];
                        std::vector<double> lim = outerLim[li];
                        for (double& v : lim) v = std::min(v, cap);
                        if (nominal) {
                            double t = 0;
                            for (size_t q = 0; q < lp.pts.size(); ++q) {
                                const V2& a = lp.pts[q];
                                const V2& b = lp.pts[(q + 1) % lp.pts.size()];
                                t += std::hypot(b.x - a.x, b.y - a.y) / lim[q];
                            }
                            tOuter += t;
                        } else {
                            tOuter += planLoop(lp.pts, lim, aOuter, jc);
                        }
                        const bool small = lp.length < 2 * kPi * s.smallPerimeterRadius;
                        const double vi = std::min(cap, small ? vInner * s.smallPerimeterFactor : vInner);
                        if (nominal) tInner += lp.length / vi;
                        else tInner += planLoop(lp.pts, std::vector<double>(lp.pts.size(), vi), aInner, jc);
                    }
                    tOuter *= outerLen / contourLen;
                    tInner *= innerLen / contourLen;
                }
                for (const FillSpec& f : specs) {
                    if (fillLen[f.cls] <= 0) continue;
                    const double v = std::min(f.speed, cap);
                    if (nominal) {
                        tFill[f.cls] = fillLen[f.cls] / v;
                        continue;
                    }
                    const int* dirs = f.sparse ? sparseDirs : &solidDir;
                    const int ndirs = f.sparse ? nSparseDirs : 1;
                    double t = 0;
                    for (int di = 0; di < ndirs; ++di) {
                        const int d = dirs[di];
                        const double perp = d >= kDiagUp ? c / kSqrt2 : c;
                        const double lines = perp / (f.lineSpacing * ndirs);
                        const double anchorGap = 0.5 * f.lineSpacing * kSqrt2;
                        // Corner speed where a line turns into the link to the next one.
                        const V2 u = d == kAlongX ? V2{1, 0} : d == kAlongY ? V2{0, 1} : d == kDiagUp ? V2{0.5 * kSqrt2, 0.5 * kSqrt2} : V2{0.5 * kSqrt2, -0.5 * kSqrt2};
                        const double vc = std::min(v, junctionSpeed(u, v, V2{-u.y, u.x}, v, jc));
                        const double tLink = moveTime(f.lineSpacing * kSqrt2, v, f.accel, vc, vc);
                        double sum = 0;
                        if (f.sparse && gyroid) {
                            // Gyroid lines are waves: the turns, not the straights, set the pace.
                            const double perMm = gyroidSecondsPerMm(v, f.accel, jc, (density / 0.15) * (0.357 / spacing(f.width)));
                            for (float r : runs[f.cls][d]) sum += (r + 2 * std::min({(double)s.infillAnchor, 0.5 * r, anchorGap})) * perMm;
                        } else {
                            for (float r : runs[f.cls][d])
                                sum += moveTime(r, v, f.accel, vc, vc) +
                                       (f.sparse ? 2 * moveTime(std::min({(double)s.infillAnchor, 0.5 * r, anchorGap}), v, f.accel, vc, vc) : tLink);
                        }
                        t += lines * sum;
                    }
                    tFill[f.cls] = t * fillScale;
                }
                if (gapLen > 0) {
                    const double v = std::min(specs[0].speed, cap);
                    double t = 0;
                    for (double r : gapRuns) t += nominal ? r / v : moveTime(r, v, specs[0].accel, jc, jc);
                    tFill[kGap] = t;
                }
                if (skirtLen > 0) tSkirt = nominal ? skirtLen / std::min(vOuter, cap) : moveTime(skirtLen, std::min(vOuter, cap), aOuter, jc, jc);
                double total = tOuter + tInner + tSkirt;
                for (double t : tFill) total += t;
                return total;
            };
            double cap = 1e9;
            if (s.minLayerTime > 0) {
                // Slicers decide on the slowdown from length / feed rate, travel included.
                const double target = s.minLayerTime - tTravelNominal;
                if (timeLayer(cap, true) < target) {
                    double lo = std::max(1.f, s.minSpeed), hi = 1000;
                    if (timeLayer(lo, true) <= target) cap = lo;
                    else {
                        for (int it = 0; it < 40; ++it) {
                            double mid = 0.5 * (lo + hi);
                            if (timeLayer(mid, true) < target) hi = mid;
                            else lo = mid;
                        }
                        cap = lo;
                    }
                }
            }
            timeLayer(cap, false);

            // Support: what the model fills of each support grid cell, and the cells overhangs touch
            // (the columns under them are worked out once all layers are done).
            if (s.supports && keep) {
                const size_t base0 = (size_t)k * sg.size();
                for (int y = B.y0; y < B.y1; ++y)
                    for (int x = B.x0; x < B.x1; ++x)
                        if (L.inside[(size_t)y * g.nx + x]) ++supOcc[base0 + sg.at(g, x, y)];
                auto& touched = supRoof[k];
                for (int y = B.y0; y < B.y1; ++y)
                    for (int x = B.x0; x < B.x1; ++x)
                        if (roof[(size_t)y * g.nx + x]) touched.push_back(sg.at(g, x, y));
                std::sort(touched.begin(), touched.end());
                touched.erase(std::unique(touched.begin(), touched.end()), touched.end());
            }

            // ---- totals
            est.outerWall += tOuter;
            est.innerWall += tInner;
            est.sparseInfill += tFill[kSparse] + tFill[kGap];
            est.solidInfill += tFill[kSolid];
            est.topSurface += tFill[kTop];
            est.bottomSurface += tFill[kBottom];
            est.bridge += tFill[kBridge];
            est.skirt += tSkirt;
            est.travel += tTravelFixed;
            est.outerWallMm += outerLen;
            est.innerWallMm += innerLen;
            est.sparseInfillMm += fillLen[kSparse] + gapLen;
            est.solidInfillMm += fillLen[kSolid];
            est.topSurfaceMm += fillLen[kTop];
            est.bottomSurfaceMm += fillLen[kBottom];
            est.bridgeMm += fillLen[kBridge];
            est.skirtMm += skirtLen;
            est.travelMm += travelMm;
            est.travels += travels;
            est.retracts += retracts;
            double vol = outerLen * area(wOut) + innerLen * area(wIn) + skirtLen * area(wOut) + gapLen * area(wIn);
            for (const FillSpec& f : specs) vol += fillLen[f.cls] * f.crossSection;
            est.volumeMm3 += vol * s.flowRatio;

            // Keep this layer as "the layer below" for the next one.
            if (!prevBox.empty())
                for (int y = prevBox.y0; y < prevBox.y1; ++y) {
                    std::fill(prevInside.begin() + (size_t)y * g.nx + prevBox.x0, prevInside.begin() + (size_t)y * g.nx + prevBox.x1, 0);
                    std::fill(prevCls.begin() + (size_t)y * g.nx + prevBox.x0, prevCls.begin() + (size_t)y * g.nx + prevBox.x1, kEmpty);
                }
            for (int y = Y0; y < Y1; ++y) {
                const size_t row = (size_t)y * g.nx;
                std::copy(L.inside.begin() + row + X0, L.inside.begin() + row + X1, prevInside.begin() + row + X0);
                std::copy(cls.begin() + row + X0, cls.begin() + row + X1, prevCls.begin() + row + X0);
            }
            prevBox = {X0, Y0, X1, Y1};
        };
        PrintEstimate warmUp;

        for (int ci = nextChunk++; ci < nChunks && !cancelled; ci = nextChunk++) {
            const int k0 = ci * chunk, k1 = std::min(nLayers, k0 + chunk), kStart = std::max(0, k0 - 1);
            // Sliding window of sliced layers: classifying layer k needs the layers below (bottom
            // shells) and above (top shells).
            std::deque<Layer> win;  // win.front() is layer `first`
            int first = std::max(0, kStart - botN - 1), sliced = first;
            auto zOf = [&](int k) { return k == 0 ? bb.min.z + h1 * 0.5 : bb.min.z + h1 + (k - 0.5) * h; };
            {
                const double z0 = zOf(first);
                next = (size_t)(std::upper_bound(order.begin(), order.end(), (float)z0, [&](float z, uint32_t t) { return z < tzmin[t]; }) - order.begin());
                active.clear();
                for (size_t i = 0; i < next; ++i)
                    if (tzmax[order[i]] >= z0) active.push_back(order[i]);
            }
            auto layerAt = [&](int k) -> Layer* { return (k < first || k >= first + (int)win.size()) ? nullptr : &win[k - first]; };
            auto sliceNext = [&]() {
                Layer L;
                const int k = sliced++;
                L.h = k == 0 ? h1 : h;
                L.z = zOf(k);
                slice(L.z, L);
                win.push_back(std::move(L));
            };
            std::fill(prevInside.begin(), prevInside.end(), 0);
            std::fill(prevCls.begin(), prevCls.end(), kEmpty);
            prevBox = Box();

            for (int k = kStart; k < k1 && !cancelled; ++k) {
                while (sliced < std::min(nLayers, k + topN + 1)) sliceNext();
                while (first < k - botN - 1 && !win.empty()) {
                    win.pop_front();
                    ++first;
                }
                PrintEstimate& acc = k >= k0 ? est : warmUp;
                layer(k, *layerAt(k), layerAt, acc, k >= k0);
                if (k < k0) continue;
                const int n = ++layersDone;
                if (reporter) report(n);
            }
        }
    };

    const unsigned threads = std::max(1u, std::min(workerCount(), (unsigned)nChunks));
    std::vector<PrintEstimate> parts(threads);
    {
        std::vector<std::thread> pool;
        for (unsigned t = 1; t < threads; ++t)
            pool.emplace_back([&, t] {
                worker(parts[t], false);
                ++helpersDone;
            });
        worker(parts[0], true);
        while (helpersDone < threads - 1) {
            report(layersDone);
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
        }
        for (auto& th : pool) th.join();
    }
    if (cancelled) throw CancelledError();
    for (const PrintEstimate& p : parts) {
        est.outerWall += p.outerWall;
        est.innerWall += p.innerWall;
        est.sparseInfill += p.sparseInfill;
        est.solidInfill += p.solidInfill;
        est.topSurface += p.topSurface;
        est.bottomSurface += p.bottomSurface;
        est.bridge += p.bridge;
        est.skirt += p.skirt;
        est.travel += p.travel;
        est.outerWallMm += p.outerWallMm;
        est.innerWallMm += p.innerWallMm;
        est.sparseInfillMm += p.sparseInfillMm;
        est.solidInfillMm += p.solidInfillMm;
        est.topSurfaceMm += p.topSurfaceMm;
        est.bottomSurfaceMm += p.bottomSurfaceMm;
        est.bridgeMm += p.bridgeMm;
        est.skirtMm += p.skirtMm;
        est.travelMm += p.travelMm;
        est.travels += p.travels;
        est.retracts += p.retracts;
        est.volumeMm3 += p.volumeMm3;
        est.supportAreaMm2 += p.supportAreaMm2;
    }

    // Support columns: each grid cell an overhang touches gets support down to the bed or to the
    // model, one layer short of both; its top layers are the denser interface.
    if (s.supports) {
        std::vector<uint8_t> supDone(supOcc.size(), 0);
        const double sw = s.supportWidth, ss = std::max(0.05, sw - h * (1 - kPi / 4));
        const double pitch = sg.c, cellA = pitch * pitch;
        const double perCell = std::max(1.0, cellA / cellArea);
        double baseArea = 0, ifaceArea = 0;
        for (int k = 1; k < nLayers; ++k)
            for (uint32_t C : supRoof[k]) {
                int layersDone = 0;
                for (int j = k - 2; j >= 0; --j) {
                    const double occ = supOcc[(size_t)j * sg.size() + C] / perCell;
                    if (occ > 0.5) break;  // resting on the model
                    uint8_t& done = supDone[(size_t)j * sg.size() + C];
                    if (!done) {
                        done = 1;
                        (layersDone < s.supportInterfaceLayers ? ifaceArea : baseArea) += cellA * (1 - occ);
                    }
                    ++layersDone;
                }
            }
        const double baseGap = std::max(0.1f, s.supportSpacing), ifaceGap = std::max(0.f, s.supportInterfaceSpacing);
        const double baseLen = baseArea / (baseGap + ss), ifaceLen = ifaceArea / (ifaceGap + ss);
        const double flowArea = extrusionArea((float)h, (float)sw);
        auto perMm = [&](double v, double run, double link) {
            v = std::min(v, flowCap / flowArea);
            const double vc = std::min(v, jerk * kSqrt2);
            return (moveTime(run, v, s.infillAccel, vc, vc) + moveTime(link, v, s.infillAccel, vc, vc)) / (run + link);
        };
        double len = baseLen + ifaceLen;
        double t = baseLen * perMm(s.supportSpeed, 8, baseGap + ss) + ifaceLen * perMm(s.supportInterfaceSpeed, 8, ifaceGap + ss);
        double vol = len * flowArea * s.flowRatio;
        if (s.treeSupports) {
            // Tree supports branch out from fewer trunks: less plastic, more moves.
            vol *= 0.6;
            len *= 0.6;
            t *= 1.25;
        }
        est.support += t;
        est.supportMm += len;
        est.supportMm3 += vol;
    }
    est.volumeMm3 += est.supportMm3;
    est.seconds = est.outerWall + est.innerWall + est.sparseInfill + est.solidInfill + est.topSurface + est.bottomSurface +
                  est.bridge + est.support + est.skirt + est.travel;
    const double r = s.filamentDiameter * 0.5;
    est.filamentMm = est.volumeMm3 / (kPi * r * r);
    progress.report(1.f);
    return est;
}

}  // namespace fsv
