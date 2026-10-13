#include "fsm_format.h"
#include "loaders.h"

namespace fsv {

RawModel loadFSM(const uint8_t* data, size_t size, Progress progress) {
    RawModel rm;
    rm.format = "FSM";
    size_t off = 0;
    auto need = [&](size_t n) {
        if (off + n > size) throw std::runtime_error("The mesh file is damaged");
    };
    auto u32 = [&]() {
        need(4);
        uint32_t v;
        std::memcpy(&v, data + off, 4);
        off += 4;
        return v;
    };
    need(4);
    if (std::memcmp(data, "FSM1", 4) != 0) throw std::runtime_error("Not a Meshwise mesh file");
    off = 4;
    uint32_t version = u32();
    if (version != kFsmVersion) throw std::runtime_error("Unsupported mesh file version");
    uint32_t parts = u32();
    for (uint32_t pi = 0; pi < parts; ++pi) {
        RawPart p;
        uint32_t nameLen = u32();
        need(nameLen);
        p.name.assign(reinterpret_cast<const char*>(data + off), nameLen);
        off += nameLen;
        p.color = u32();
        uint32_t flags = u32();
        if (!(flags & kFsmFlagFileColor)) p.color = 0;
        uint32_t vc = u32(), ic = u32(), ec = u32();
        auto take = [&](auto& vec, size_t count) {
            using T = typename std::decay_t<decltype(vec)>::value_type;
            need(count * sizeof(T));
            vec.resize(count);
            if (count) std::memcpy(vec.data(), data + off, count * sizeof(T));
            off += count * sizeof(T);
        };
        take(p.positions, (size_t)vc * 3);
        take(p.normals, (size_t)vc * 3);
        if (flags & kFsmFlagVertexColors) take(p.vertexColors, vc);
        take(p.indices, ic);
        take(p.edges, ec);
        rm.parts.push_back(std::move(p));
        progress.report((float)(pi + 1) / (float)std::max(1u, parts));
    }
    return rm;
}

}  // namespace fsv
