// STL (binary and ASCII) loader. Binary files are welded straight from the mapped
// records so no intermediate triangle soup is allocated.
#include <cstring>

#include "loaders.h"
#include "parse_util.h"
#include "weld.h"

namespace fsv {

namespace {

bool isAsciiStl(const uint8_t* d, size_t n) {
    size_t i = 0;
    while (i < n && isSpace((char)d[i])) ++i;
    if (n - i < 6 || !startsWithNoCase(reinterpret_cast<const char*>(d + i), n - i, "solid")) return false;
    // Many binary files start with "solid" too: require "facet" or "endsolid" text early on.
    size_t probe = std::min<size_t>(n, 4096);
    for (size_t k = i + 5; k + 5 <= probe; ++k) {
        if (d[k] == 0) return false;
        if (startsWithNoCase(reinterpret_cast<const char*>(d + k), probe - k, "facet") ||
            startsWithNoCase(reinterpret_cast<const char*>(d + k), probe - k, "endsolid"))
            return true;
    }
    return n < 84;
}

RawModel loadBinary(const uint8_t* d, size_t n, Progress& prog) {
    RawModel rm;
    rm.format = "STL";
    if (n < 84) throw std::runtime_error("The STL file is truncated");
    uint32_t declared;
    std::memcpy(&declared, d + 80, 4);
    uint64_t available = (n - 84) / 50;
    uint64_t triCount = std::min<uint64_t>(declared, available);
    if (declared > available) rm.warnings.push_back("File is truncated; loaded the complete triangles only");
    if (triCount == 0) throw std::runtime_error("The STL file contains no triangles");

    // Colour conventions: Materialise Magics ("COLOR=" in header, bit 15 clear => face colour,
    // RGB in bits 0..14) and VisCAM/SolidView (bit 15 set => valid colour, BGR order).
    std::string header(reinterpret_cast<const char*>(d), 80);
    size_t colorPos = header.find("COLOR=");
    const bool magics = colorPos != std::string::npos && colorPos + 10 <= 80;
    uint32_t defaultColor = 0;
    if (magics) {
        const uint8_t* c = d + colorPos + 6;
        defaultColor = (uint32_t)c[0] | ((uint32_t)c[1] << 8) | ((uint32_t)c[2] << 16) | 0xFF000000u;
    }
    bool viscam = !magics;
    uint16_t firstAttr = 0;
    bool attrVaries = false;
    if (viscam) {
        // Only trust VisCAM colours when every face has the valid bit and colours vary.
        for (uint64_t t = 0; t < triCount; ++t) {
            uint16_t a;
            std::memcpy(&a, d + 84 + t * 50 + 48, 2);
            if (!(a & 0x8000)) { viscam = false; break; }
            if (t == 0) firstAttr = a; else if (a != firstAttr) attrVaries = true;
        }
        viscam = viscam && attrVaries;
    }
    const bool colored = magics || viscam;

    RawPart part;
    part.welded = true;
    part.color = magics ? defaultColor : 0;
    part.indices.resize(triCount * 3);
    if (colored) part.faceColors.resize(triCount);
    PositionWelder welder(part.positions, (size_t)std::min<uint64_t>(triCount, 1u << 26));
    uint64_t kept = 0;
    for (uint64_t t = 0; t < triCount; ++t) {
        const uint8_t* rec = d + 84 + t * 50;
        float v[9];
        std::memcpy(v, rec + 12, 36);
        bool ok = true;
        for (float f : v) ok &= std::isfinite(f);
        if (!ok) continue;
        uint32_t a = welder.insert(v), b = welder.insert(v + 3), c = welder.insert(v + 6);
        if (a == b || b == c || a == c) continue;
        part.indices[kept * 3] = a;
        part.indices[kept * 3 + 1] = b;
        part.indices[kept * 3 + 2] = c;
        if (colored) {
            uint16_t at;
            std::memcpy(&at, rec + 48, 2);
            uint32_t r5, g5, b5;
            if (magics) {
                if (at & 0x8000) { part.faceColors[kept] = defaultColor; ++kept; continue; }
                r5 = at & 31; g5 = (at >> 5) & 31; b5 = (at >> 10) & 31;
            } else {
                b5 = at & 31; g5 = (at >> 5) & 31; r5 = (at >> 10) & 31;
            }
            part.faceColors[kept] = ((r5 * 255 / 31)) | ((g5 * 255 / 31) << 8) | ((b5 * 255 / 31) << 16) | 0xFF000000u;
        }
        ++kept;
        if ((t & 0x3FFFF) == 0) prog.report((float)t / (float)triCount);
    }
    part.indices.resize(kept * 3);
    if (colored) {
        part.faceColors.resize(kept);
        // A single colour across the whole file is just the part colour.
        bool uniform = true;
        for (uint64_t i = 1; i < kept && uniform; ++i) uniform = part.faceColors[i] == part.faceColors[0];
        if (uniform && kept > 0) { part.color = part.faceColors[0]; part.faceColors.clear(); }
    }
    rm.parts.push_back(std::move(part));
    return rm;
}

RawModel loadAscii(const uint8_t* d, size_t n, Progress& prog) {
    RawModel rm;
    rm.format = "STL";
    TextCursor cur{reinterpret_cast<const char*>(d), reinterpret_cast<const char*>(d + n)};
    RawPart* part = nullptr;
    const char* tok;
    size_t len;
    size_t lines = 0;
    while (!cur.atEnd()) {
        cur.skipSpace();
        if (cur.atEnd()) break;
        if (!cur.token(tok, len)) { cur.skipLine(); continue; }
        if (tokenIs(tok, len, "vertex")) {
            float x, y, z;
            if (parseFloat(cur.p, cur.end, x) && parseFloat(cur.p, cur.end, y) && parseFloat(cur.p, cur.end, z)) {
                if (!part) { rm.parts.emplace_back(); part = &rm.parts.back(); }
                part->positions.insert(part->positions.end(), {x, y, z});
            }
        } else if (tokenIs(tok, len, "solid")) {
            cur.skipLineSpace();
            const char* s = cur.p;
            while (cur.p < cur.end && *cur.p != '\n' && *cur.p != '\r') ++cur.p;
            rm.parts.emplace_back();
            part = &rm.parts.back();
            part->name.assign(s, (size_t)(cur.p - s));
            while (!part->name.empty() && isSpace(part->name.back())) part->name.pop_back();
        } else if (tokenIs(tok, len, "endsolid")) {
            part = nullptr;
        }
        cur.skipLine();
        if ((++lines & 0xFFFF) == 0) prog.report((float)(cur.p - reinterpret_cast<const char*>(d)) / (float)n);
    }
    // Make each part a whole number of triangles.
    for (RawPart& p : rm.parts) p.positions.resize(p.positions.size() / 9 * 9);
    return rm;
}

}  // namespace

RawModel loadSTL(const uint8_t* data, size_t size, Progress progress) {
    if (size >= 84) {
        uint32_t declared;
        std::memcpy(&declared, data + 80, 4);
        if (84 + 50ull * declared == size) return loadBinary(data, size, progress);
    }
    if (isAsciiStl(data, size)) return loadAscii(data, size, progress);
    return loadBinary(data, size, progress);
}

}  // namespace fsv
