// fsv_render: offscreen renderer used for regression renders and README images.
//
//   fsv_render --in model.stl [--in more.obj ...] --out out.png [options]
//
// Options:
//   --size WxH            image size (default 1080x1080)
//   --view NAME           iso|front|back|left|right|top|bottom (default iso)
//   --yaw DEG --pitch DEG explicit orientation
//   --zoom F              zoom factor after fitting (default 1)
//   --bg N --light N      background / lighting preset index
//   --grid 0|1 --shadows 0|1 --edges --wire --axes --ortho --transparent
//   --section AXIS:FRAC[:flip]   e.g. y:0.5
//   --thickness [threshold:MM]   wall-thickness colour map
//   --overhang DEG
//   --explode F
//   --color PART:#RRGGBB  (PART = index or 'all')
//   --material PART:NAME  plastic|glossy|resin|metal|gold|silver|copper|chrome|rubber
//   --select PART  --ghost-others
//   --open-edges
//   --linescale F         line width multiplier (display density / 2 on devices)
//   --insets TOP,BOTTOM   pixels covered by UI; the model is framed in the band between
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "analysis.h"
#include "egl_context.h"
#include "engine.h"
#include "loaders.h"
#include "png.h"

using namespace fsv;

namespace {

struct MaterialPreset { const char* name; float rough, metal, coat; uint32_t color; };
const MaterialPreset kMaterials[] = {
    {"plastic", 0.55f, 0.f, 0.f, 0},
    {"glossy", 0.22f, 0.f, 0.6f, 0},
    {"resin", 0.15f, 0.f, 0.9f, 0},
    {"rubber", 0.9f, 0.f, 0.f, 0},
    {"metal", 0.32f, 1.f, 0.f, 0xFFA7A3A0u},
    {"gold", 0.22f, 1.f, 0.f, 0xFF57C7FFu},
    {"silver", 0.18f, 1.f, 0.f, 0xFFE8E9EAu},
    {"copper", 0.25f, 1.f, 0.f, 0xFF8AA5F5u},
    {"chrome", 0.05f, 1.f, 0.f, 0xFFE0DFDEu},
};

uint32_t parseHex(const char* s) {
    if (*s == '#') ++s;
    unsigned long v = std::strtoul(s, nullptr, 16);
    uint32_t r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
    return r | (g << 8) | (b << 16) | 0xFF000000u;
}

std::string extOf(const std::string& path) {
    size_t d = path.find_last_of('.');
    return d == std::string::npos ? "" : path.substr(d + 1);
}

std::string dirOf(const std::string& path) {
    size_t s = path.find_last_of('/');
    return s == std::string::npos ? "." : path.substr(0, s);
}

std::string baseName(const std::string& path) {
    size_t s = path.find_last_of('/');
    return s == std::string::npos ? path : path.substr(s + 1);
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> inputs;
    std::string out = "out.png";
    int w = 1080, h = 1080;
    std::string view = "iso";
    bool haveYawPitch = false;
    float yaw = 0, pitch = 0, zoomF = 1;
    RenderOptions opt;
    opt.lineScale = 2.f;
    bool thickness = false, threshold = false;
    float thresholdMm = 1.f;
    float explode = 0;
    bool ortho = false, transparent = false, ghostOthers = false;
    int select = -1;
    std::vector<std::pair<std::string, uint32_t>> colors;
    std::vector<std::pair<std::string, std::string>> materials;
    std::string sectionSpec;
    float crease = 35.f;
    float insetTop = 0, insetBottom = 0;

    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", a.c_str()); std::exit(2); }
            return argv[++i];
        };
        if (a == "--in") inputs.push_back(next());
        else if (a == "--out") out = next();
        else if (a == "--size") { std::string s = next(); std::sscanf(s.c_str(), "%dx%d", &w, &h); }
        else if (a == "--view") view = next();
        else if (a == "--yaw") { yaw = std::stof(next()) * kPi / 180.f; haveYawPitch = true; }
        else if (a == "--pitch") { pitch = std::stof(next()) * kPi / 180.f; haveYawPitch = true; }
        else if (a == "--zoom") zoomF = std::stof(next());
        else if (a == "--bg") opt.background = std::stoi(next());
        else if (a == "--light") opt.lighting = std::stoi(next());
        else if (a == "--grid") opt.grid = std::stoi(next()) != 0;
        else if (a == "--shadows") opt.shadows = std::stoi(next()) != 0;
        else if (a == "--edges") opt.edges = true;
        else if (a == "--wire") opt.wireframe = true;
        else if (a == "--axes") opt.axes = true;
        else if (a == "--ortho") ortho = true;
        else if (a == "--transparent") transparent = true;
        else if (a == "--section") sectionSpec = next();
        else if (a == "--thickness") {
            thickness = true;
            if (i + 1 < argc && std::strncmp(argv[i + 1], "threshold:", 10) == 0) {
                threshold = true;
                thresholdMm = std::stof(std::string(argv[++i]).substr(10));
            }
        } else if (a == "--overhang") { opt.mode = ShadingMode::Overhang; opt.overhangAngle = std::stof(next()); }
        else if (a == "--explode") explode = std::stof(next());
        else if (a == "--color") {
            std::string s = next();
            size_t c = s.find(':');
            colors.push_back({s.substr(0, c), parseHex(s.substr(c + 1).c_str())});
        } else if (a == "--material") {
            std::string s = next();
            size_t c = s.find(':');
            materials.push_back({s.substr(0, c), s.substr(c + 1)});
        } else if (a == "--select") select = std::stoi(next());
        else if (a == "--ghost-others") ghostOthers = true;
        else if (a == "--open-edges") opt.openEdges = true;
        else if (a == "--crease") crease = std::stof(next());
        else if (a == "--linescale") opt.lineScale = std::stof(next());
        else if (a == "--insets") { std::string v = next(); std::sscanf(v.c_str(), "%f,%f", &insetTop, &insetBottom); }
        else { std::fprintf(stderr, "unknown option %s\n", a.c_str()); return 2; }
    }
    if (inputs.empty()) { std::fprintf(stderr, "no --in given\n"); return 2; }

    std::vector<std::unique_ptr<Model>> models;
    for (const std::string& path : inputs) {
        auto file = MappedFile::fromPath(path);
        LoadRequest req;
        req.name = baseName(path);
        req.ext = extOf(path);
        req.baseDir = dirOf(path);
        req.process.creaseAngleDeg = crease;
        try {
            models.push_back(loadModel(*file, req, nullptr));
        } catch (const std::exception& e) {
            std::fprintf(stderr, "failed to load %s: %s\n", path.c_str(), e.what());
            return 1;
        }
    }
    std::shared_ptr<Model> model(mergeModels(std::move(models), true).release());

    if (!fsvhost::createHeadlessContext(16, 16)) return 1;
    Engine engine;
    engine.onSurfaceCreated();
    engine.onSurfaceChanged(w, h);
    engine.setViewInsets(insetTop, insetBottom, false);
    engine.setModel(model, true);

    auto topo = computeTopology(*model, 30.f, Progress());
    engine.setTopology(std::shared_ptr<const Topology>(topo.release()));
    auto bvh = std::make_shared<ModelBVH>();
    bvh->build(*model, Progress());
    engine.setBVH(bvh);

    auto partsMatching = [&](const std::string& key) {
        std::vector<int> r;
        if (key == "all") r.push_back(-1);
        else r.push_back(std::stoi(key));
        return r;
    };
    for (auto& c : colors)
        for (int p : partsMatching(c.first)) engine.setPartColor(p, c.second);
    for (auto& m : materials) {
        for (const MaterialPreset& mp : kMaterials) {
            if (m.second != mp.name) continue;
            for (int p : partsMatching(m.first)) {
                engine.setPartMaterial(p, mp.rough, mp.metal, mp.coat);
                if (mp.color) engine.setPartColor(p, mp.color);
            }
        }
    }
    if (select >= 0) {
        engine.setPartSelected(select, true);
        if (ghostOthers)
            for (int p = 0; p < (int)model->parts.size(); ++p)
                if (p != select) engine.setPartGhost(p, true);
    }
    if (explode > 0) engine.setExplode(explode);

    if (thickness) {
        auto res = computeThickness(*model, *bvh, Progress());
        std::printf("thickness: min %.3f  p05 %.3f  p95 %.3f  max %.3f\n", res.minValue, res.p05, res.p95, res.maxValue);
        for (auto& s : res.thinnest) std::printf("  thin spot %.3f mm at (%.2f, %.2f, %.2f)\n", s.value, s.position.x, s.position.y, s.position.z);
        engine.setScalars(std::make_shared<std::vector<float>>(res.perVertex));
        opt.mode = threshold ? ShadingMode::ThicknessThreshold : ShadingMode::ThicknessGradient;
        opt.scalarMin = res.p05;
        opt.scalarMax = res.p95;
        opt.threshold = thresholdMm;
    }
    if (!sectionSpec.empty()) {
        int axis = sectionSpec[0] == 'x' ? 0 : sectionSpec[0] == 'y' ? 1 : 2;
        float frac = 0.5f;
        size_t c = sectionSpec.find(':');
        if (c != std::string::npos) frac = std::stof(sectionSpec.substr(c + 1));
        Box3 b = engine.sceneBounds();
        opt.section = true;
        opt.sectionAxis = axis;
        opt.sectionValue = b.min[axis] + (b.max[axis] - b.min[axis]) * frac;
        opt.sectionFlip = sectionSpec.find("flip") != std::string::npos;
    }
    engine.setOptions(opt);
    engine.setOrtho(ortho);

    static const char* names[] = {"front", "back", "left", "right", "top", "bottom", "iso"};
    int viewIdx = 6;
    for (int i = 0; i < 7; ++i)
        if (view == names[i]) viewIdx = i;
    engine.setStandardView(viewIdx, false);
    if (haveYawPitch) engine.setOrientation(yaw, pitch, false);
    engine.fitView(false);
    if (zoomF != 1.f) {
        CameraState s = engine.cameraState();
        s.distance /= zoomF;
        engine.setCameraState(s, false);
    }

    std::vector<uint8_t> rgba;
    if (!engine.snapshot(w, h, transparent, rgba)) {
        std::fprintf(stderr, "render failed\n");
        return 1;
    }
    if (!fsvhost::writePng(out, w, h, rgba)) {
        std::fprintf(stderr, "could not write %s\n", out.c_str());
        return 1;
    }
    std::printf("%s: %zu parts, %zu triangles, %zu vertices -> %s\n", inputs[0].c_str(), model->parts.size(),
                model->triangleCount(), model->vertexCount(), out.c_str());
    fsvhost::destroyHeadlessContext();
    return 0;
}
