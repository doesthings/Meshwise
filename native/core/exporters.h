// Model exporters.
#pragma once

#include <string>
#include <vector>

#include "model.h"

namespace fsv {

struct ExportOptions {
    std::vector<uint8_t> partMask;       // empty = all parts
    std::vector<uint32_t> partColors;    // RGBA override per part (3MF); empty = model colours
    std::vector<Vec3> partOffsets;       // optional per-part translation (e.g. exploded layout)
    std::string modelName;
};

std::vector<uint8_t> exportSTL(const Model& m, const ExportOptions& opt);
std::vector<uint8_t> exportOBJ(const Model& m, const ExportOptions& opt);
std::vector<uint8_t> export3MF(const Model& m, const ExportOptions& opt);
std::vector<uint8_t> exportFSM(const Model& m, const ExportOptions& opt);

// Writes all bytes to a file descriptor. Returns false on I/O error.
bool writeAll(int fd, const std::vector<uint8_t>& bytes);

}  // namespace fsv
