// Print preparation: re-orienting and scaling a model, and ranking orientations for printing.
#pragma once

#include <memory>
#include <vector>

#include "model.h"

namespace fsv {

// Copy of `m` with every vertex transformed by the affine matrix `affine` (row-major 3x4:
// rotation/scale/mirror in the left 3x3, translation in the last column). Normals follow,
// triangle winding is flipped for mirroring, and bounds, volume, area and centroids are
// recomputed. With `placeOnBed` the result is also moved to sit on z = 0, centred on the
// origin in X and Y; `applied` (optional) receives that extra translation.
std::unique_ptr<Model> transformModel(const Model& m, const float affine[12], bool placeOnBed, Vec3* applied);

struct OrientationStats {
    Vec3 down;                 // model direction that points down onto the bed
    double overhangArea = 0;   // mm² steeper than the overhang angle, not resting on the bed
    double contactArea = 0;    // mm² lying flat on the bed
    double totalArea = 0;
    float height = 0;          // build height in this orientation
    float score = 0;           // lower is better
};

// How the model prints when `down` points at the bed.
OrientationStats orientationStats(const Model& m, const Vec3& down, float overhangDeg);

// Tries the six axis directions and the model's largest flat faces as the bottom and returns
// the `count` best distinct orientations, best first.
std::vector<OrientationStats> suggestOrientations(const Model& m, float overhangDeg, int count, Progress progress);

}  // namespace fsv
