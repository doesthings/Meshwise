// Print time and material estimate: slices the mesh into layers like a slicer, splits each
// layer into walls, top/bottom skins, internal bridges, sparse infill and (optionally) support,
// works out how long each kind of line is, then times the moves with acceleration, corner
// speed limits, the hotend's flow limit and the minimum layer time. No G-code is generated.
#pragma once

#include <vector>

#include "model.h"

namespace fsv {

// All lengths in mm, speeds in mm/s, accelerations in mm/s², densities as fractions.
struct PrintSettings {
    std::vector<uint8_t> parts;  // which of the model's parts to print (empty: all)
    float layerHeight = 0.2f, firstLayerHeight = 0.2f;
    float outerWidth = 0.42f, innerWidth = 0.45f, infillWidth = 0.45f, solidWidth = 0.42f, topWidth = 0.42f;
    float firstLayerWidth = 0.5f, supportWidth = 0.42f;
    int walls = 2, topLayers = 4, bottomLayers = 3;
    float topThickness = 0.6f, bottomThickness = 0.f;  // shells grow to at least this thickness
    bool topOneWall = true;                            // top surfaces get only the outer wall
    float infillDensity = 0.15f;
    enum InfillPattern { kLines = 0, kGrid = 1, kCubic = 2, kGyroid = 3 };  // cubic: also triangles
    int infillPattern = kGrid;
    float infillOverlap = 0.15f;  // infill reaches into the innermost wall by this share of a line
    float infillAnchor = 1.8f;    // anchor along the wall at each end of a sparse infill line
    float minSparseArea = 15;     // sparse regions smaller than this (mm²) are printed solid

    float outerSpeed = 200, innerSpeed = 300, infillSpeed = 270, solidSpeed = 250, topSpeed = 200;
    float firstLayerSpeed = 50, firstLayerInfillSpeed = 105, travelSpeed = 500, bridgeSpeed = 50, supportSpeed = 150;
    // Outer wall slowdown by how much of the line hangs over the layer below: 25–50 %,
    // 50–75 % and 75 % or more of the line width; 0 = no slowdown.
    float overhangSpeed[3] = {50, 30, 10};
    float smallPerimeterFactor = 0.5f, smallPerimeterRadius = 6.5f;

    float outerAccel = 5000, innerAccel = 10000, infillAccel = 10000, solidAccel = 10000, topAccel = 2000;
    float travelAccel = 10000, firstLayerAccel = 500;
    float jerk = 9;  // classic jerk: largest instant speed change per axis at a corner

    float maxVolumetricSpeed = 21;  // mm³/s the hotend can melt
    float minLayerTime = 5, minSpeed = 20;
    float retractLength = 0.8f, retractSpeed = 30, retractMinTravel = 1, zHop = 0.4f, zSpeed = 20;
    int skirtLoops = 1;
    float skirtDistance = 2;

    bool supports = false, treeSupports = false;
    float supportAngle = 30;  // overhangs flatter than this (from the horizontal) get support
    float supportSpacing = 2.5f, supportInterfaceSpacing = 0.5f;  // gaps between support lines
    float supportInterfaceSpeed = 80;
    int supportInterfaceLayers = 3;

    float filamentDiameter = 1.75f;
    float flowRatio = 0.98f;  // the filament profile's flow multiplier
};

struct PrintEstimate {
    double seconds = 0;         // printing time of the model (no heating, homing or calibration)
    double volumeMm3 = 0;       // extruded plastic, support and skirt included
    double filamentMm = 0;
    double supportMm3 = 0;
    double supportAreaMm2 = 0;  // overhang area (projected) that needs support
    int layers = 0;
    // Time (s) and line length (mm) per feature.
    double outerWall = 0, innerWall = 0, sparseInfill = 0, solidInfill = 0, topSurface = 0, bottomSurface = 0;
    double bridge = 0, support = 0, skirt = 0, travel = 0;
    double outerWallMm = 0, innerWallMm = 0, sparseInfillMm = 0, solidInfillMm = 0, topSurfaceMm = 0, bottomSurfaceMm = 0;
    double bridgeMm = 0, supportMm = 0, skirtMm = 0, travelMm = 0;
    int travels = 0, retracts = 0;
};

PrintEstimate estimatePrint(const Model& m, const PrintSettings& s, Progress progress);

// Flow cross-section of an extruded line (PrusaSlicer's rounded-rectangle model).
inline float extrusionArea(float height, float width) { return height * (width - height * (1.f - kPi / 4.f)); }

}  // namespace fsv
