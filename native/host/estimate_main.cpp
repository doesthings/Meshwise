// fsv_estimate: print time and material estimate for a model (used to calibrate against slicers).
//   fsv_estimate model.stl [key=value ...]   e.g. layerHeight=0.2 walls=3 supports=1
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>

#include "loaders.h"
#include "print_estimate.h"

using namespace fsv;

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: fsv_estimate model [key=value ...]\n");
        return 2;
    }
    PrintSettings s;
    std::map<std::string, float*> f = {
        {"layerHeight", &s.layerHeight}, {"firstLayerHeight", &s.firstLayerHeight}, {"outerWidth", &s.outerWidth},
        {"innerWidth", &s.innerWidth}, {"infillWidth", &s.infillWidth}, {"solidWidth", &s.solidWidth}, {"topWidth", &s.topWidth},
        {"firstLayerWidth", &s.firstLayerWidth}, {"supportWidth", &s.supportWidth}, {"topThickness", &s.topThickness},
        {"bottomThickness", &s.bottomThickness}, {"infillDensity", &s.infillDensity}, {"outerSpeed", &s.outerSpeed},
        {"innerSpeed", &s.innerSpeed}, {"infillSpeed", &s.infillSpeed}, {"solidSpeed", &s.solidSpeed}, {"topSpeed", &s.topSpeed},
        {"firstLayerSpeed", &s.firstLayerSpeed}, {"firstLayerInfillSpeed", &s.firstLayerInfillSpeed}, {"travelSpeed", &s.travelSpeed},
        {"bridgeSpeed", &s.bridgeSpeed}, {"supportSpeed", &s.supportSpeed}, {"overhang2", &s.overhangSpeed[0]},
        {"overhang3", &s.overhangSpeed[1]}, {"overhang4", &s.overhangSpeed[2]}, {"smallPerimeterFactor", &s.smallPerimeterFactor},
        {"outerAccel", &s.outerAccel}, {"innerAccel", &s.innerAccel}, {"infillAccel", &s.infillAccel}, {"solidAccel", &s.solidAccel},
        {"topAccel", &s.topAccel}, {"travelAccel", &s.travelAccel}, {"firstLayerAccel", &s.firstLayerAccel}, {"jerk", &s.jerk},
        {"maxVolumetricSpeed", &s.maxVolumetricSpeed}, {"minLayerTime", &s.minLayerTime}, {"minSpeed", &s.minSpeed},
        {"retractLength", &s.retractLength}, {"retractSpeed", &s.retractSpeed}, {"zHop", &s.zHop}, {"zSpeed", &s.zSpeed},
        {"supportAngle", &s.supportAngle}, {"supportSpacing", &s.supportSpacing}, {"supportInterfaceSpacing", &s.supportInterfaceSpacing},
        {"filamentDiameter", &s.filamentDiameter}, {"flowRatio", &s.flowRatio}, {"infillOverlap", &s.infillOverlap},
        {"infillAnchor", &s.infillAnchor}, {"minSparseArea", &s.minSparseArea}, {"retractMinTravel", &s.retractMinTravel},
        {"skirtDistance", &s.skirtDistance}, {"supportInterfaceSpeed", &s.supportInterfaceSpeed}, {"smallPerimeterRadius", &s.smallPerimeterRadius},
    };
    for (int i = 2; i < argc; ++i) {
        const char* eq = std::strchr(argv[i], '=');
        if (!eq) continue;
        std::string k(argv[i], eq - argv[i]);
        float v = std::strtof(eq + 1, nullptr);
        if (f.count(k)) *f[k] = v;
        else if (k == "walls") s.walls = (int)v;
        else if (k == "topLayers") s.topLayers = (int)v;
        else if (k == "bottomLayers") s.bottomLayers = (int)v;
        else if (k == "supportInterfaceLayers") s.supportInterfaceLayers = (int)v;
        else if (k == "supports") s.supports = v != 0;
        else if (k == "treeSupports") s.treeSupports = v != 0;
        else if (k == "topOneWall") s.topOneWall = v != 0;
        else if (k == "infillPattern") s.infillPattern = (int)v;
        else if (k == "skirtLoops") s.skirtLoops = (int)v;
        else std::fprintf(stderr, "unknown setting %s\n", k.c_str());
    }
    std::string path = argv[1];
    auto file = MappedFile::fromPath(path);
    LoadRequest req;
    req.name = path;
    size_t dot = path.find_last_of('.');
    req.ext = dot == std::string::npos ? "" : path.substr(dot + 1);
    auto m = loadModel(*file, req, nullptr);
    auto t0 = std::chrono::steady_clock::now();
    PrintEstimate e = estimatePrint(*m, s, Progress());
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::printf("{\"seconds\": %.1f, \"filament_mm\": %.1f, \"volume_mm3\": %.1f, \"support_mm3\": %.1f, \"support_area_mm2\": %.1f, "
                "\"layers\": %d, \"compute_ms\": %.0f, \"travels\": %d, \"retracts\": %d,\n",
                e.seconds, e.filamentMm, e.volumeMm3, e.supportMm3, e.supportAreaMm2, e.layers, ms, e.travels, e.retracts);
    std::printf(" \"features\": {\"Outer wall\": %.1f, \"Inner wall\": %.1f, \"Sparse infill\": %.1f, \"Internal solid infill\": %.1f, "
                "\"Top surface\": %.1f, \"Bottom surface\": %.1f, \"Bridge\": %.1f, \"Support\": %.1f, \"Skirt\": %.1f, \"Travel\": %.1f},\n",
                e.outerWall, e.innerWall, e.sparseInfill, e.solidInfill, e.topSurface, e.bottomSurface, e.bridge, e.support, e.skirt, e.travel);
    std::printf(" \"lengths\": {\"Outer wall\": %.1f, \"Inner wall\": %.1f, \"Sparse infill\": %.1f, \"Internal solid infill\": %.1f, "
                "\"Top surface\": %.1f, \"Bottom surface\": %.1f, \"Bridge\": %.1f, \"Support\": %.1f, \"Skirt\": %.1f, \"Travel\": %.1f}}\n",
                e.outerWallMm, e.innerWallMm, e.sparseInfillMm, e.solidInfillMm, e.topSurfaceMm, e.bottomSurfaceMm, e.bridgeMm, e.supportMm,
                e.skirtMm, e.travelMm);
    return 0;
}
