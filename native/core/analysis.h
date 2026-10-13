// Geometry analysis: wall thickness, overhangs, picking helpers.
#pragma once

#include <vector>

#include "bvh.h"
#include "model.h"

namespace fsv {

struct ThicknessResult {
    std::vector<float> perVertex;  // thickness per render vertex (mm); maxDistance when no hit
    float minValue = 0, maxValue = 0;   // over measured vertices
    float p05 = 0, p95 = 0;             // robust range for colour mapping
    struct Spot { Vec3 position; Vec3 normal; float value; int part; };
    std::vector<Spot> thinnest;         // spatially separated thinnest locations
    float maxDistance = 0;
};

// Ray-based wall thickness: from every surface point a ray is cast against the inward
// normal; the distance to the opposite wall is the local thickness. Multi-threaded.
ThicknessResult computeThickness(const Model& m, const ModelBVH& bvh, Progress progress);

struct OverhangStats {
    double overhangArea = 0;  // mm^2 of downward faces steeper than the threshold (excluding the bed)
    double totalArea = 0;
};
OverhangStats computeOverhang(const Model& m, float angleDeg, const std::vector<uint8_t>& partVisible);

// Circle through three points. Returns false for (nearly) collinear points.
bool circleFrom3(const Vec3& a, const Vec3& b, const Vec3& c, Vec3& center, Vec3& normal, float& radius);

}  // namespace fsv
