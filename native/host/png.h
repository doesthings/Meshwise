#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace fsvhost {

// Writes top-down RGBA8 pixels as a PNG file.
bool writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgba);

}  // namespace fsvhost
