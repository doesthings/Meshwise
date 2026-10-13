// STEP / IGES / BREP import through Open CASCADE. Produces an FSM mesh file.
#pragma once

#include <functional>
#include <string>
#include <vector>

namespace fsvcad {

struct CadOptions {
    // Linear deflection as a fraction of the model's bounding-box diagonal.
    double relativeDeflection = 0.0008;
    // Angular deflection in radians.
    double angularDeflection = 0.35;
};

struct CadResult {
    int parts = 0;
    long long triangles = 0;
    std::vector<std::string> warnings;
};

// Reads `inputPath` (format from `ext`: step/stp/iges/igs/brep/brp) and writes an FSM file
// to `outputPath`. `progress` receives values in [0,1] and returns false to cancel.
// Throws std::runtime_error with a user-readable message on failure.
CadResult importCad(const std::string& inputPath, const std::string& ext, const std::string& outputPath,
                    const CadOptions& options, const std::function<bool(float)>& progress);

}  // namespace fsvcad
