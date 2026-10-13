// Read-only view of a file: memory-mapped when possible, otherwise read into memory.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fsv {

class MappedFile {
public:
    ~MappedFile();
    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;

    // Maps the file behind `fd` (the descriptor is not closed). Falls back to reading for
    // non-seekable descriptors such as pipes.
    static std::unique_ptr<MappedFile> fromFd(int fd);
    static std::unique_ptr<MappedFile> fromPath(const std::string& path);
    static std::unique_ptr<MappedFile> fromBuffer(std::vector<uint8_t>&& data);

    const uint8_t* data() const { return data_; }
    size_t size() const { return size_; }

private:
    MappedFile() = default;
    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    void* map_ = nullptr;
    size_t mapSize_ = 0;
    std::vector<uint8_t> buffer_;
};

}  // namespace fsv
