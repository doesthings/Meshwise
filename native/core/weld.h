// Exact-position vertex welding with an open-addressing hash table.
#pragma once

#include <cstdint>
#include <cstring>
#include <vector>

namespace fsv {

class PositionWelder {
public:
    explicit PositionWelder(std::vector<float>& out, size_t expected = 1024) : out_(out) {
        size_t cap = 1024;
        while (cap < expected * 2) cap <<= 1;
        table_.assign(cap, 0u);
        mask_ = (uint32_t)(cap - 1);
        out_.reserve(expected * 3);
    }

    // Returns the unique index of position p (x,y,z), inserting it if new.
    uint32_t insert(const float* p) {
        uint32_t b[3];
        std::memcpy(b, p, 12);
        // Treat -0.0 and +0.0 as the same coordinate.
        for (uint32_t& v : b)
            if (v == 0x80000000u) v = 0;
        uint32_t h = hash(b) & mask_;
        const uint32_t* outBits;
        for (;;) {
            uint32_t e = table_[h];
            if (e == 0) break;
            outBits = reinterpret_cast<const uint32_t*>(out_.data()) + (size_t)(e - 1) * 3;
            if (outBits[0] == b[0] && outBits[1] == b[1] && outBits[2] == b[2]) return e - 1;
            h = (h + 1) & mask_;
        }
        uint32_t id = (uint32_t)(out_.size() / 3);
        float f[3];
        std::memcpy(f, b, 12);
        out_.push_back(f[0]);
        out_.push_back(f[1]);
        out_.push_back(f[2]);
        table_[h] = id + 1;
        if ((size_t)(id + 1) * 2 > table_.size()) grow();
        return id;
    }

    size_t size() const { return out_.size() / 3; }

private:
    static uint32_t hash(const uint32_t* b) {
        uint64_t h = (uint64_t)b[0] * 0x9E3779B97F4A7C15ull;
        h ^= (uint64_t)b[1] * 0xC2B2AE3D27D4EB4Full + (h >> 29);
        h ^= (uint64_t)b[2] * 0x165667B19E3779F9ull + (h >> 32);
        h ^= h >> 31;
        return (uint32_t)h;
    }

    void grow() {
        size_t cap = table_.size() * 2;
        table_.assign(cap, 0u);
        mask_ = (uint32_t)(cap - 1);
        const uint32_t* bits = reinterpret_cast<const uint32_t*>(out_.data());
        size_t n = out_.size() / 3;
        for (size_t i = 0; i < n; ++i) {
            uint32_t h = hash(bits + i * 3) & mask_;
            while (table_[h] != 0) h = (h + 1) & mask_;
            table_[h] = (uint32_t)i + 1;
        }
    }

    std::vector<float>& out_;
    std::vector<uint32_t> table_;
    uint32_t mask_;
};

}  // namespace fsv
