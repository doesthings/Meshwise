// Minimal ZIP reader/writer on top of zlib (enough for 3MF packages and project files).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace fsv {

class ZipReader {
public:
    struct Entry {
        std::string name;
        uint64_t compressedSize = 0;
        uint64_t size = 0;
        uint64_t localHeaderOffset = 0;
        uint16_t method = 0;  // 0 = stored, 8 = deflate
    };

    // `data` must outlive the reader.
    bool open(const uint8_t* data, size_t size);
    const std::vector<Entry>& entries() const { return entries_; }
    // Case-insensitive lookup; leading '/' and backslashes are ignored.
    const Entry* find(const std::string& name) const;
    bool extract(const Entry& e, std::vector<uint8_t>& out) const;

    static bool looksLikeZip(const uint8_t* data, size_t size) {
        return size >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

private:
    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    std::vector<Entry> entries_;
};

class ZipWriter {
public:
    void add(const std::string& name, const void* data, size_t size, bool compress = true);
    // Finishes the archive and returns its bytes.
    std::vector<uint8_t> finish();

private:
    struct Rec {
        std::string name;
        uint32_t crc;
        uint64_t compSize, size, offset;
        uint16_t method;
    };
    std::vector<uint8_t> out_;
    std::vector<Rec> recs_;
};

std::string normalizeZipPath(const std::string& s);

}  // namespace fsv
