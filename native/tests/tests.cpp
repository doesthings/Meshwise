// Unit tests for loaders, processing and analysis. Run: build/fsv_tests
#include <cmath>
#include <cstdio>
#include <cstring>
#include <atomic>
#include <chrono>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "analysis.h"
#include "bvh.h"
#include "camera.h"
#include "feature_finder.h"
#include "engine.h"
#include "fsm_format.h"
#include "exporters.h"
#include "loaders.h"
#include "zip.h"
#include "../host/egl_context.h"

using namespace fsv;

static int gFailures = 0, gChecks = 0;
#define CHECK(c)                                                                  \
    do {                                                                          \
        ++gChecks;                                                                \
        if (!(c)) { ++gFailures; std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); } \
    } while (0)
#define CHECK_NEAR(a, b, eps)                                                                         \
    do {                                                                                              \
        ++gChecks;                                                                                    \
        double _a = (a), _b = (b);                                                                    \
        if (std::fabs(_a - _b) > (eps)) {                                                             \
            ++gFailures;                                                                              \
            std::printf("  FAIL %s:%d: %s = %g, expected %g\n", __FILE__, __LINE__, #a, _a, _b);       \
        }                                                                                             \
    } while (0)

namespace {

// Axis-aligned box as 12 outward-facing triangles.
std::vector<float> boxTris(Vec3 mn, Vec3 mx) {
    Vec3 c[8] = {{mn.x, mn.y, mn.z}, {mx.x, mn.y, mn.z}, {mx.x, mx.y, mn.z}, {mn.x, mx.y, mn.z},
                 {mn.x, mn.y, mx.z}, {mx.x, mn.y, mx.z}, {mx.x, mx.y, mx.z}, {mn.x, mx.y, mx.z}};
    int f[12][3] = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4},
                    {1, 2, 6}, {1, 6, 5}, {2, 3, 7}, {2, 7, 6}, {3, 0, 4}, {3, 4, 7}};
    std::vector<float> out;
    for (auto& t : f)
        for (int k = 0; k < 3; ++k) out.insert(out.end(), {c[t[k]].x, c[t[k]].y, c[t[k]].z});
    return out;
}

std::vector<uint8_t> binaryStl(const std::vector<float>& tris) {
    size_t n = tris.size() / 9;
    std::vector<uint8_t> b(84 + n * 50, 0);
    uint32_t c = (uint32_t)n;
    std::memcpy(b.data() + 80, &c, 4);
    for (size_t i = 0; i < n; ++i) std::memcpy(b.data() + 84 + i * 50 + 12, &tris[i * 9], 36);
    return b;
}

std::string asciiStl(const std::vector<std::vector<float>>& solids) {
    std::string s;
    int k = 0;
    for (auto& tris : solids) {
        s += "solid part" + std::to_string(k++) + "\n";
        for (size_t i = 0; i < tris.size(); i += 9) {
            s += "  facet normal 0 0 0\n    outer loop\n";
            for (int v = 0; v < 3; ++v) {
                char buf[128];
                std::snprintf(buf, sizeof buf, "      vertex %g %g %g\n", tris[i + v * 3], tris[i + v * 3 + 1], tris[i + v * 3 + 2]);
                s += buf;
            }
            s += "    endloop\n  endfacet\n";
        }
        s += "endsolid\n";
    }
    return s;
}

std::unique_ptr<Model> load(const std::vector<uint8_t>& bytes, const std::string& ext, float crease = 35.f) {
    auto f = MappedFile::fromBuffer(std::vector<uint8_t>(bytes));
    LoadRequest req;
    req.name = "test." + ext;
    req.ext = ext;
    req.process.creaseAngleDeg = crease;
    return loadModel(*f, req, nullptr);
}

std::vector<uint8_t> bytesOf(const std::string& s) { return std::vector<uint8_t>(s.begin(), s.end()); }

// A washer: outer cylinder, a through hole, flat top and bottom rings (outward winding).
std::vector<float> washerTris(float ro, float ri, float h, int seg) {
    std::vector<float> out;
    auto P = [&](float r, int i, float z) {
        float a = 2.f * kPi * (float)(i % seg) / (float)seg;
        return Vec3(r * std::cos(a), r * std::sin(a), z);
    };
    auto tri = [&](Vec3 a, Vec3 b, Vec3 c) { out.insert(out.end(), {a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z}); };
    for (int i = 0; i < seg; ++i) {
        tri(P(ro, i, 0), P(ro, i + 1, 0), P(ro, i + 1, h));  // outer wall, facing out
        tri(P(ro, i, 0), P(ro, i + 1, h), P(ro, i, h));
        tri(P(ri, i, 0), P(ri, i + 1, h), P(ri, i + 1, 0));  // hole wall, facing the axis
        tri(P(ri, i, 0), P(ri, i, h), P(ri, i + 1, h));
        tri(P(ri, i, h), P(ro, i, h), P(ro, i + 1, h));      // top ring, facing up
        tri(P(ri, i, h), P(ro, i + 1, h), P(ri, i + 1, h));
        tri(P(ri, i, 0), P(ro, i + 1, 0), P(ro, i, 0));      // bottom ring, facing down
        tri(P(ri, i, 0), P(ri, i + 1, 0), P(ro, i + 1, 0));
    }
    return out;
}

struct Measured {
    std::unique_ptr<Model> model;
    std::unique_ptr<Topology> topo;
    std::unique_ptr<TriangleAdjacency> adj;
    std::unique_ptr<EdgeGraph> edges;

    explicit Measured(std::unique_ptr<Model> m) : model(std::move(m)) {
        topo = computeTopology(*model, 30.f, Progress());
        adj = buildAdjacency(*model, Progress());
        edges = buildEdgeGraph(*model, *topo);
    }

    // Taps the triangle whose centroid is closest to `near`, at that centroid (or at `at`).
    Feature tap(const Vec3& near, float snap, const Vec3* at = nullptr) const {
        const Model& m = *model;
        uint32_t best = 0;
        float bd = std::numeric_limits<float>::max();
        for (uint32_t f = 0; f < m.triangleCount(); ++f) {
            Vec3 c = (m.vertex(m.indices[3 * f]) + m.vertex(m.indices[3 * f + 1]) + m.vertex(m.indices[3 * f + 2])) / 3.f;
            float d = length(c - near);
            if (d < bd) { bd = d; best = f; }
        }
        Vec3 a = m.vertex(m.indices[3 * best]), b = m.vertex(m.indices[3 * best + 1]), c = m.vertex(m.indices[3 * best + 2]);
        FeatureQuery q;
        q.part = 0;
        q.triangle = best;
        q.point = at ? *at : (a + b + c) / 3.f;
        q.normal = normalize(cross(b - a, c - a));
        q.snapRadius = snap;
        return findFeature(m, adj.get(), edges.get(), q);
    }
};

void test(const char* name, const std::function<void()>& fn) {
    int before = gFailures;
    try {
        fn();
    } catch (const std::exception& e) {
        ++gFailures;
        std::printf("  EXCEPTION: %s\n", e.what());
    }
    std::printf("%s %s\n", gFailures == before ? "[ ok ]" : "[FAIL]", name);
}

}  // namespace

int main() {
    test("binary STL cube: welding, stats, crease normals", [] {
        auto m = load(binaryStl(boxTris({0, 0, 0}, {10, 10, 10})), "stl");
        CHECK(m->parts.size() == 1);
        CHECK(m->triangleCount() == 12);
        CHECK(m->positionCount == 8);
        CHECK(m->vertexCount() == 24);  // each corner split into 3 by the 90° creases
        CHECK_NEAR(m->parts[0].volume, 1000.0, 1e-3);
        CHECK_NEAR(m->parts[0].area, 600.0, 1e-3);
        CHECK_NEAR(m->parts[0].centroid.x, 5.0, 1e-4);
        CHECK(m->parts[0].name == "test");
        auto smooth = load(binaryStl(boxTris({0, 0, 0}, {10, 10, 10})), "stl", 180.f);
        CHECK(smooth->vertexCount() == 8);
    });

    test("ASCII STL with two solids", [] {
        auto m = load(bytesOf(asciiStl({boxTris({0, 0, 0}, {1, 1, 1}), boxTris({2, 0, 0}, {3, 2, 1})})), "stl");
        CHECK(m->parts.size() == 2);
        CHECK(m->parts[0].name == "part0");
        CHECK(m->parts[1].name == "part1");
        CHECK_NEAR(m->parts[1].volume, 2.0, 1e-4);
        CHECK_NEAR(m->bounds.max.x, 3.0, 1e-6);
    });

    test("binary STL starting with 'solid' is still binary", [] {
        auto b = binaryStl(boxTris({0, 0, 0}, {1, 1, 1}));
        std::memcpy(b.data(), "solid fake header", 17);
        auto m = load(b, "stl");
        CHECK(m->triangleCount() == 12);
    });

    test("topology: closed cube is watertight", [] {
        auto m = load(binaryStl(boxTris({0, 0, 0}, {10, 10, 10})), "stl");
        auto t = computeTopology(*m, 30.f, Progress());
        CHECK(t->openEdges.empty());
        CHECK(t->nonManifoldEdges.empty());
        CHECK(t->shellCount == 1);
        CHECK(t->edgeCount == 18);
        CHECK(t->featureEdges.size() == 24);  // 12 cube edges
        CHECK(t->flippedEdges == 0);
    });

    test("topology: open box reports boundary edges", [] {
        auto tris = boxTris({0, 0, 0}, {10, 10, 10});
        tris.resize(tris.size() - 18);  // drop two triangles (one face)
        auto m = load(binaryStl(tris), "stl");
        auto t = computeTopology(*m, 30.f, Progress());
        CHECK(t->openEdges.size() == 8);  // 4 boundary edges
    });

    test("OBJ: objects, quads, negative indices, MTL-less", [] {
        std::string obj =
            "# test\n"
            "o first\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n"
            "o second\nv 0 0 1\nv 1 0 1\nv 1 1 1\nf -3/1/1 -2/2/2 -1/3/3\n";
        auto m = load(bytesOf(obj), "obj");
        CHECK(m->parts.size() == 2);
        CHECK(m->parts[0].name == "first");
        CHECK(m->parts[1].name == "second");
        CHECK(m->triangleCount() == 3);
        CHECK_NEAR(m->parts[0].area, 1.0, 1e-5);
    });

    test("PLY ascii with vertex colours", [] {
        std::string ply =
            "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\n"
            "property uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\n"
            "property list uchar int vertex_indices\nend_header\n"
            "0 0 0 255 0 0\n1 0 0 0 255 0\n0 1 0 0 0 255\n3 0 1 2\n";
        auto m = load(bytesOf(ply), "ply");
        CHECK(m->triangleCount() == 1);
        CHECK(m->parts[0].hasVertexColors);
        CHECK(m->colors.size() == 3);
        CHECK(m->colors[0] == 0xFF0000FFu);
    });

    test("PLY binary little endian", [] {
        std::string hdr =
            "ply\nformat binary_little_endian 1.0\nelement vertex 4\nproperty float x\nproperty float y\nproperty float z\n"
            "element face 1\nproperty list uchar uint vertex_indices\nend_header\n";
        std::vector<uint8_t> b(hdr.begin(), hdr.end());
        float v[12] = {0, 0, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0};
        b.insert(b.end(), (uint8_t*)v, (uint8_t*)v + sizeof v);
        b.push_back(4);
        uint32_t idx[4] = {0, 1, 2, 3};
        b.insert(b.end(), (uint8_t*)idx, (uint8_t*)idx + sizeof idx);
        auto m = load(b, "ply");
        CHECK(m->triangleCount() == 2);
        CHECK_NEAR(m->parts[0].area, 4.0, 1e-5);
    });

    test("OFF with face colours", [] {
        std::string off = "OFF\n# comment\n4 2 0\n0 0 0\n1 0 0\n1 1 0\n0 1 0\n3 0 1 2 255 0 0\n3 0 2 3 0 255 0\n";
        auto m = load(bytesOf(off), "off");
        CHECK(m->triangleCount() == 2);
        CHECK(m->parts[0].hasVertexColors);
    });

    test("3MF export/import round trip with names and colours", [] {
        auto a = load(bytesOf(asciiStl({boxTris({0, 0, 0}, {1, 1, 1}), boxTris({2, 0, 0}, {3, 2, 1})})), "stl");
        ExportOptions eo;
        eo.partColors = {0xFF0000FFu, 0xFF00FF00u};
        auto bytes = export3MF(*a, eo);
        CHECK(ZipReader::looksLikeZip(bytes.data(), bytes.size()));
        auto m = load(bytes, "3mf");
        CHECK(m->parts.size() == 2);
        CHECK(m->parts[0].name == "part0");
        CHECK(m->parts[0].color == 0xFF0000FFu);
        CHECK(m->parts[1].color == 0xFF00FF00u);
        CHECK_NEAR(m->parts[1].volume, 2.0, 1e-4);
    });

    test("3MF components, transforms and production paths", [] {
        const char* root =
            "<?xml version=\"1.0\"?><model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\" "
            "xmlns:p=\"http://schemas.microsoft.com/3dmanufacturing/production/2015/06\"><resources>"
            "<object id=\"5\" type=\"model\" name=\"Assembly\"><components>"
            "<component p:path=\"/3D/Objects/a.model\" objectid=\"1\" transform=\"1 0 0 0 1 0 0 0 1 10 0 0\"/>"
            "<component p:path=\"/3D/Objects/a.model\" objectid=\"1\" transform=\"1 0 0 0 1 0 0 0 1 20 0 0\"/>"
            "</components></object></resources><build><item objectid=\"5\" transform=\"1 0 0 0 1 0 0 0 1 0 0 5\"/></build></model>";
        const char* part =
            "<?xml version=\"1.0\"?><model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>"
            "<object id=\"1\" type=\"model\"><mesh><vertices>"
            "<vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"1\" y=\"0\" z=\"0\"/><vertex x=\"0\" y=\"1\" z=\"0\"/><vertex x=\"0\" y=\"0\" z=\"1\"/>"
            "</vertices><triangles><triangle v1=\"0\" v2=\"2\" v3=\"1\"/><triangle v1=\"0\" v2=\"1\" v3=\"3\"/>"
            "<triangle v1=\"1\" v2=\"2\" v3=\"3\"/><triangle v1=\"0\" v2=\"3\" v3=\"2\"/></triangles></mesh></object></resources></model>";
        ZipWriter zw;
        zw.add("3D/3dmodel.model", root, std::strlen(root));
        zw.add("3D/Objects/a.model", part, std::strlen(part));
        auto bytes = zw.finish();
        auto m = load(bytes, "3mf");
        CHECK(m->parts.size() == 2);
        CHECK_NEAR(m->bounds.min.x, 10.0, 1e-5);
        CHECK_NEAR(m->bounds.max.x, 21.0, 1e-5);
        CHECK_NEAR(m->bounds.min.z, 5.0, 1e-5);
    });

    test("FSM round trip keeps normals, names and edges", [] {
        auto a = load(binaryStl(boxTris({0, 0, 0}, {4, 4, 4})), "stl");
        auto bytes = exportFSM(*a, ExportOptions());
        auto m = load(bytes, "fsm");
        CHECK(m->vertexCount() == a->vertexCount());
        CHECK(m->triangleCount() == a->triangleCount());
        CHECK(m->positionCount == 8);
        CHECK(m->parts[0].name == a->parts[0].name);
        CHECK_NEAR(m->parts[0].volume, 64.0, 1e-3);
    });

    test("FSM loader drops edges that reference missing vertices", [] {
        const float pos[9] = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        const float nrm[9] = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        const uint32_t idx[3] = {0, 1, 2};
        const uint32_t edges[6] = {0, 1, 1, 7, 2, 0};  // 7 is out of range
        FsmWriter w(1);
        w.part("tri", 0, 0, pos, nrm, nullptr, 3, idx, 3, edges, 6);
        auto m = load(w.bytes(), "fsm");
        CHECK(m->triangleCount() == 1);
        CHECK(m->cadEdges.size() == 4);
        for (uint32_t e : m->cadEdges) CHECK(e < m->vertexCount());
    });

    test("engine ignores analysis results for a replaced model", [] {
        Engine engine;
        std::shared_ptr<const Model> a(load(binaryStl(boxTris({0, 0, 0}, {4, 4, 4})), "stl").release());
        std::shared_ptr<const Model> b(load(binaryStl(boxTris({0, 0, 0}, {8, 8, 8})), "stl").release());
        engine.setModel(a, true);
        auto bvhA = std::make_shared<ModelBVH>();
        bvhA->build(*a, Progress());
        CHECK(engine.setBVH(bvhA, a.get()));
        engine.setModel(b, true);
        CHECK(!engine.bvh());
        CHECK(!engine.setBVH(bvhA, a.get()));  // stale: computed for the old model
        CHECK(!engine.bvh());
        CHECK(!engine.setScalars(std::make_shared<std::vector<float>>(a->vertexCount(), 1.f), a.get()));
        CHECK(engine.setScalars(nullptr));
    });

    // Needs a headless GL context (Mesa); skipped where none is available.
    static const bool haveGl = fsvhost::createHeadlessContext(64, 64);
    if (!haveGl) std::printf("[skip] engine GL tests: no headless GL context\n");

    if (haveGl) test("model upload does not block the UI thread", [] {
        Engine engine;
        engine.onSurfaceCreated();
        engine.onSurfaceChanged(64, 64);
        std::shared_ptr<const Model> a(load(binaryStl(boxTris({0, 0, 0}, {4, 4, 4})), "stl").release());
        std::shared_ptr<const Model> b(load(binaryStl(boxTris({0, 0, 0}, {8, 8, 8})), "stl").release());
        engine.setModel(a, true);
        int uploads = 0;
        bool uiDone = false;
        std::atomic<bool> replaced{false};
        std::thread ui;
        engine.setUploadHookForTests([&] {
            if (++uploads != 1) return;
            // Mid-upload: the UI thread moves the camera and replaces the model.
            ui = std::thread([&] {
                engine.orbit(0.1f, 0.f);
                engine.setModel(b, false);
                replaced = true;
            });
            auto end = std::chrono::steady_clock::now() + std::chrono::seconds(5);
            while (!replaced && std::chrono::steady_clock::now() < end) std::this_thread::yield();
            uiDone = replaced;
        });
        bool again = engine.drawFrame();
        if (ui.joinable()) ui.join();
        CHECK(uploads == 1);
        CHECK(uiDone);
        CHECK(again);  // the scene changed during the upload: another frame is needed
        engine.drawFrame();
        CHECK(uploads == 2);  // ...and that frame uploads the new model
        engine.drawFrame();
        CHECK(uploads == 2);
        std::vector<uint8_t> px;
        CHECK(engine.thumbnail(32, px));
        CHECK(px.size() == 32 * 32 * 4);
    });

    test("STL/OBJ exporters", [] {
        auto a = load(binaryStl(boxTris({0, 0, 0}, {4, 4, 4})), "stl");
        auto stl = exportSTL(*a, ExportOptions());
        CHECK(stl.size() == 84 + 12 * 50);
        auto b = load(stl, "stl");
        CHECK_NEAR(b->parts[0].volume, 64.0, 1e-3);
        auto obj = exportOBJ(*a, ExportOptions());
        auto c = load(obj, "obj");
        CHECK(c->positionCount == 8);
        CHECK_NEAR(c->parts[0].volume, 64.0, 1e-3);
    });

    test("thickness of a 2 mm slab", [] {
        auto m = load(binaryStl(boxTris({0, 0, 0}, {40, 40, 2})), "stl");
        ModelBVH bvh;
        bvh.build(*m, Progress());
        auto r = computeThickness(*m, bvh, Progress());
        CHECK_NEAR(r.minValue, 2.0, 0.01);
        CHECK(!r.thinnest.empty());
    });

    test("BVH picking", [] {
        auto m = load(binaryStl(boxTris({0, 0, 0}, {10, 10, 10})), "stl");
        ModelBVH bvh;
        bvh.build(*m, Progress());
        Hit h;
        Ray r{Vec3(5, -20, 5), Vec3(0, 1, 0)};
        CHECK(bvh.part(0).intersect(*m, r, 0.f, 1e9f, h));
        CHECK_NEAR(h.t, 20.0, 1e-4);
        Ray miss{Vec3(50, -20, 5), Vec3(0, 1, 0)};
        Hit h2;
        CHECK(!bvh.part(0).intersect(*m, miss, 0.f, 1e9f, h2));
    });

    test("circle through three points", [] {
        Vec3 c, n;
        float r;
        CHECK(circleFrom3(Vec3(10, 0, 3), Vec3(0, 10, 3), Vec3(-10, 0, 3), c, n, r));
        CHECK_NEAR(r, 10.0, 1e-4);
        CHECK_NEAR(c.z, 3.0, 1e-4);
        CHECK_NEAR(std::fabs(n.z), 1.0, 1e-5);
        CHECK(!circleFrom3(Vec3(0, 0, 0), Vec3(1, 1, 1), Vec3(2, 2, 2), c, n, r));
    });

    test("measure: holes, shafts and faces of a washer", [] {
        Measured w(load(binaryStl(washerTris(10.f, 4.f, 3.f, 64)), "stl"));
        Feature outer = w.tap(Vec3(10, 0, 1.5f), 0.2f);
        CHECK(outer.kind == FeatureKind::Cylinder);
        CHECK_NEAR(outer.radius, 10.0, 1e-3);
        CHECK(!outer.inner);
        CHECK_NEAR(outer.length, 3.0, 1e-3);
        CHECK_NEAR(std::fabs(outer.axis.z), 1.0, 1e-4);
        CHECK(!outer.outline.empty());

        Feature hole = w.tap(Vec3(-4, 0, 1.5f), 0.2f);
        CHECK(hole.kind == FeatureKind::Cylinder);
        CHECK_NEAR(hole.radius, 4.0, 1e-3);
        CHECK(hole.inner);
        CHECK_NEAR(hole.center.x, 0.0, 1e-3);
        CHECK_NEAR(hole.center.y, 0.0, 1e-3);

        Feature top = w.tap(Vec3(7, 0, 3), 0.2f);
        CHECK(top.kind == FeatureKind::Plane);
        CHECK_NEAR(top.axis.z, 1.0, 1e-4);
        double ring = 0.5 * 64 * std::sin(2 * kPi / 64) * (100.0 - 16.0);  // polygon areas
        CHECK_NEAR(top.area, ring, 1e-2);

        // Near the top rim of the outer wall: the round edge wins.
        Vec3 nearRim(10.f * std::cos(0.05f), 10.f * std::sin(0.05f), 2.9f);
        Feature rim = w.tap(nearRim, 0.3f, &nearRim);
        CHECK(rim.kind == FeatureKind::Circle);
        CHECK_NEAR(rim.radius, 10.0, 1e-3);
        CHECK(rim.closed);
        CHECK_NEAR(rim.center.z, 3.0, 1e-3);
    });

    test("measure: corners, edges and faces of a box", [] {
        Measured b(load(binaryStl(boxTris({0, 0, 0}, {20, 10, 5})), "stl"));
        Vec3 nearCorner(19.8f, 0.1f, 5.f);
        Feature corner = b.tap(Vec3(15, 3, 5), 0.5f, &nearCorner);
        CHECK(corner.kind == FeatureKind::Vertex);
        CHECK_NEAR(length(corner.point - Vec3(20, 0, 5)), 0.0, 1e-4);

        Vec3 nearEdge(10.f, 0.1f, 5.f);
        Feature edge = b.tap(Vec3(10, 3, 5), 0.5f, &nearEdge);
        CHECK(edge.kind == FeatureKind::Edge);
        CHECK_NEAR(edge.length, 20.0, 1e-3);
        CHECK_NEAR(std::fabs(edge.axis.x), 1.0, 1e-4);

        Vec3 mid(10.f, 5.f, 5.f);
        Feature face = b.tap(mid, 0.5f, &mid);
        CHECK(face.kind == FeatureKind::Plane);
        CHECK_NEAR(face.area, 200.0, 1e-3);
        CHECK_NEAR(face.center.z, 5.0, 1e-4);
        CHECK(face.outline.size() == 8);  // the 4 rim edges of the face
    });

    test("measure: a sphere is recognised", [] {
        std::vector<float> tris;
        const int nu = 48, nv = 24;
        const float r = 6.f;
        auto S = [&](int i, int j) {
            float th = kPi * (float)j / (float)nv, ph = 2.f * kPi * (float)(i % nu) / (float)nu;
            return Vec3(r * std::sin(th) * std::cos(ph), r * std::sin(th) * std::sin(ph), r * std::cos(th));
        };
        for (int j = 0; j < nv; ++j)
            for (int i = 0; i < nu; ++i) {
                Vec3 a = S(i, j), b2 = S(i, j + 1), c = S(i + 1, j + 1), d = S(i + 1, j);
                if (j > 0) tris.insert(tris.end(), {a.x, a.y, a.z, b2.x, b2.y, b2.z, d.x, d.y, d.z});
                if (j < nv - 1) tris.insert(tris.end(), {d.x, d.y, d.z, b2.x, b2.y, b2.z, c.x, c.y, c.z});
            }
        Measured s(load(binaryStl(tris), "stl", 180.f));
        Feature f = s.tap(Vec3(0, 6, 0), 0.1f);
        CHECK(f.kind == FeatureKind::Sphere);
        CHECK_NEAR(f.radius, 6.0, 1e-3);
        CHECK(!f.inner);
    });

    test("camera: fit, project centre, zoom anchor", [] {
        Camera cam;
        cam.setViewport(800, 600);
        cam.setScene(Vec3(0, 0, 0), 10);
        cam.setView(StandardView::Iso, false);
        cam.fit(Vec3(0, 0, 0), 10, false);
        Vec4 c = cam.viewProj() * Vec4(0, 0, 0, 1);
        CHECK_NEAR(c.x / c.w, 0.0, 1e-4);
        CHECK_NEAR(c.y / c.w, 0.0, 1e-4);
        Vec3 o, d;
        cam.rayAt(400, 300, o, d);
        CHECK_NEAR(dot(d, cam.forward()), 1.0, 1e-4);
        // Zooming at a point keeps it on the same pixel.
        Vec3 anchor(3, 1, 2);
        Vec4 before = cam.viewProj() * Vec4(anchor, 1);
        cam.zoomAt(1.7f, anchor);
        Vec4 after = cam.viewProj() * Vec4(anchor, 1);
        CHECK_NEAR(before.x / before.w, after.x / after.w, 1e-3);
        CHECK_NEAR(before.y / before.w, after.y / after.w, 1e-3);
        // Top view looks straight down.
        cam.setView(StandardView::Top, false);
        CHECK_NEAR(cam.forward().z, -1.0, 1e-5);
        cam.setView(StandardView::Front, false);
        CHECK_NEAR(cam.forward().y, 1.0, 1e-5);
    });

    test("camera: a fit made before the view had its size is redone", [] {
        Camera cam;
        Box3 box;
        box.expand(Vec3(-32, -15, 0));
        box.expand(Vec3(32, 15, 4));
        cam.setScene(box.center(), box.radius());
        cam.setView(StandardView::Iso, false);
        cam.fitBox(box, false);  // viewport still 1x1
        cam.setViewport(1080, 1920);
        for (int i = 0; i < 8; ++i) {
            Vec3 p((i & 1) ? 32.f : -32.f, (i & 2) ? 15.f : -15.f, (i & 4) ? 4.f : 0.f);
            Vec4 c = cam.viewProj() * Vec4(p, 1);
            CHECK(std::fabs(c.x / c.w) < 1.f && std::fabs(c.y / c.w) < 1.f);
        }
    });

    test("camera: changing the field of view keeps the framing", [] {
        Camera cam;
        cam.setViewport(800, 600);
        cam.setScene(Vec3(0, 0, 0), 10);
        cam.setView(StandardView::Iso, false);
        cam.fit(Vec3(0, 0, 0), 10, false);
        float wpp = cam.worldPerPixel();
        float dist = cam.state().distance;
        cam.setFov(60.f * kPi / 180.f);
        CHECK_NEAR(cam.worldPerPixel(), wpp, 1e-4);
        CHECK(cam.state().distance < dist);  // wider lens moves closer
        cam.setFov(0.05f);                   // clamped to 10°
        CHECK_NEAR(cam.fovY(), 10.f * kPi / 180.f, 1e-5);
        CHECK_NEAR(cam.worldPerPixel(), wpp, 1e-4);
    });

    test("camera: insets shift the image into the visible band", [] {
        Camera cam;
        cam.setViewport(1000, 2000);
        Box3 box;
        box.expand(Vec3(-10, -10, 0));
        box.expand(Vec3(10, 10, 20));
        cam.setScene(box.center(), box.radius());
        cam.setView(StandardView::Iso, false);
        cam.setInsets(200, 800, false);
        cam.fitBox(box, false);
        auto toPx = [&](const Vec3& p) {
            Vec4 c = cam.viewProj() * Vec4(p, 1);
            return Vec3((c.x / c.w * 0.5f + 0.5f) * 1000.f, (1.f - (c.y / c.w * 0.5f + 0.5f)) * 2000.f, 0);
        };
        Vec3 c = toPx(box.center());
        CHECK_NEAR(c.y, 200 + (2000 - 200 - 800) / 2.0, 1.0);  // centre of the visible band
        // Every corner lands inside the band.
        for (int i = 0; i < 8; ++i) {
            Vec3 p((i & 1) ? 10.f : -10.f, (i & 2) ? 10.f : -10.f, (i & 4) ? 20.f : 0.f);
            Vec3 s = toPx(p);
            CHECK(s.y > 200 && s.y < 1200 && s.x > 0 && s.x < 1000);
        }
        // Picking rays agree with the shifted projection.
        Vec3 o, d;
        cam.rayAt(c.x, c.y, o, d);
        Vec3 toCenter = normalize(box.center() - o);
        CHECK_NEAR(dot(d, toCenter), 1.0, 1e-4);
    });

    test("merge + arrange of overlapping models", [] {
        std::vector<std::unique_ptr<Model>> ms;
        ms.push_back(load(binaryStl(boxTris({-5, -5, 0}, {5, 5, 10})), "stl"));
        ms.push_back(load(binaryStl(boxTris({-5, -5, 0}, {5, 5, 10})), "stl"));
        auto m = mergeModels(std::move(ms), true);
        CHECK(m->parts.size() == 2);
        const Box3 &a = m->parts[0].bounds, &b = m->parts[1].bounds;
        bool disjoint = a.max.x <= b.min.x || b.max.x <= a.min.x || a.max.y <= b.min.y || b.max.y <= a.min.y;
        CHECK(disjoint);
        CHECK_NEAR(m->bounds.min.z, 0.0, 1e-5);
    });

    test("zip64-free reader handles stored + deflated entries", [] {
        ZipWriter zw;
        std::string big(10000, 'a');
        zw.add("a.txt", big.data(), big.size(), true);
        zw.add("dir/b.bin", "xyz", 3, false);
        auto z = zw.finish();
        ZipReader zr;
        CHECK(zr.open(z.data(), z.size()));
        CHECK(zr.entries().size() == 2);
        std::vector<uint8_t> out;
        CHECK(zr.extract(*zr.find("A.TXT"), out));
        CHECK(out.size() == 10000);
        CHECK(zr.extract(*zr.find("/dir/b.bin"), out));
        CHECK(out.size() == 3 && out[0] == 'x');
    });

    test("garbage input is rejected cleanly", [] {
        bool threw = false;
        try { load(bytesOf("hello world"), "stl"); } catch (const std::exception&) { threw = true; }
        CHECK(threw);
        threw = false;
        try { load(bytesOf("PK\x03\x04garbage"), "3mf"); } catch (const std::exception&) { threw = true; }
        CHECK(threw);
    });

    fsvhost::destroyHeadlessContext();
    std::printf("\n%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
