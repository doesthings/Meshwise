// "FSM" — Meshwise's own compact mesh container. Used for CAD tessellation results,
// the CAD tessellation cache and project files. Little-endian, header-only writer so the
// CAD library can produce it without linking the core.
//
//   char[4]  "FSM1"
//   u32      version (1)
//   u32      part count
//   per part:
//     u32 nameLength, utf8 name
//     u32 colour (RGBA, 0 = none)
//     u32 flags (bit0: colour came from file, bit1: per-vertex colours present)
//     u32 vertexCount, u32 indexCount, u32 edgeIndexCount
//     f32[3*vertexCount] positions
//     f32[3*vertexCount] normals
//     u32[vertexCount]   colours        (only when flags bit1)
//     u32[indexCount]    triangle indices (part local)
//     u32[edgeIndexCount] edge line list (part local)
#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace fsv {

constexpr uint32_t kFsmVersion = 1;
constexpr uint32_t kFsmFlagFileColor = 1u << 0;
constexpr uint32_t kFsmFlagVertexColors = 1u << 1;

class FsmWriter {
public:
    explicit FsmWriter(uint32_t partCount) {
        bytes_.insert(bytes_.end(), {'F', 'S', 'M', '1'});
        u32(kFsmVersion);
        u32(partCount);
    }

    void part(const std::string& name, uint32_t color, uint32_t flags, const float* positions, const float* normals,
              const uint32_t* colors, uint32_t vertexCount, const uint32_t* indices, uint32_t indexCount,
              const uint32_t* edges, uint32_t edgeCount) {
        u32((uint32_t)name.size());
        bytes_.insert(bytes_.end(), name.begin(), name.end());
        u32(color);
        if (!colors) flags &= ~kFsmFlagVertexColors;
        u32(flags);
        u32(vertexCount);
        u32(indexCount);
        u32(edgeCount);
        raw(positions, sizeof(float) * 3 * vertexCount);
        raw(normals, sizeof(float) * 3 * vertexCount);
        if (flags & kFsmFlagVertexColors) raw(colors, sizeof(uint32_t) * vertexCount);
        raw(indices, sizeof(uint32_t) * indexCount);
        if (edgeCount) raw(edges, sizeof(uint32_t) * edgeCount);
    }

    std::vector<uint8_t>& bytes() { return bytes_; }

private:
    void u32(uint32_t v) {
        uint8_t b[4];
        std::memcpy(b, &v, 4);
        bytes_.insert(bytes_.end(), b, b + 4);
    }
    void raw(const void* p, size_t n) {
        const uint8_t* b = static_cast<const uint8_t*>(p);
        bytes_.insert(bytes_.end(), b, b + n);
    }
    std::vector<uint8_t> bytes_;
};

}  // namespace fsv
