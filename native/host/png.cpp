#include "png.h"

#include <zlib.h>

#include <cstdio>

namespace fsvhost {

namespace {
void be32(std::vector<uint8_t>& o, uint32_t v) {
    o.push_back((uint8_t)(v >> 24)); o.push_back((uint8_t)(v >> 16)); o.push_back((uint8_t)(v >> 8)); o.push_back((uint8_t)v);
}
void chunk(std::vector<uint8_t>& out, const char* type, const std::vector<uint8_t>& data) {
    be32(out, (uint32_t)data.size());
    size_t start = out.size();
    out.insert(out.end(), type, type + 4);
    out.insert(out.end(), data.begin(), data.end());
    uint32_t crc = (uint32_t)crc32(0, out.data() + start, (uInt)(out.size() - start));
    be32(out, crc);
}
}  // namespace

bool writePng(const std::string& path, int w, int h, const std::vector<uint8_t>& rgba) {
    std::vector<uint8_t> raw;
    raw.reserve((size_t)(w * 4 + 1) * h);
    for (int y = 0; y < h; ++y) {
        raw.push_back(0);
        raw.insert(raw.end(), rgba.begin() + (size_t)y * w * 4, rgba.begin() + (size_t)(y + 1) * w * 4);
    }
    uLongf bound = compressBound((uLong)raw.size());
    std::vector<uint8_t> z(bound);
    if (compress2(z.data(), &bound, raw.data(), (uLong)raw.size(), 9) != Z_OK) return false;
    z.resize(bound);
    std::vector<uint8_t> out = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    std::vector<uint8_t> ihdr;
    be32(ihdr, (uint32_t)w);
    be32(ihdr, (uint32_t)h);
    ihdr.insert(ihdr.end(), {8, 6, 0, 0, 0});
    chunk(out, "IHDR", ihdr);
    chunk(out, "IDAT", z);
    chunk(out, "IEND", {});
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    return ok;
}

}  // namespace fsvhost
