#include "mapped_file.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <stdexcept>

namespace fsv {

MappedFile::~MappedFile() {
    if (map_) munmap(map_, mapSize_);
}

std::unique_ptr<MappedFile> MappedFile::fromFd(int fd) {
    std::unique_ptr<MappedFile> f(new MappedFile());
    struct stat st{};
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
        void* p = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (p != MAP_FAILED) {
            madvise(p, (size_t)st.st_size, MADV_SEQUENTIAL);
            f->map_ = p;
            f->mapSize_ = (size_t)st.st_size;
            f->data_ = static_cast<const uint8_t*>(p);
            f->size_ = (size_t)st.st_size;
            return f;
        }
    }
    // Fallback: read everything (pipes, sockets, providers without a real file).
    std::vector<uint8_t> buf;
    if (st.st_size > 0) buf.reserve((size_t)st.st_size);
    uint8_t chunk[1 << 16];
    for (;;) {
        ssize_t n = read(fd, chunk, sizeof(chunk));
        if (n < 0) {
            if (errno == EINTR) continue;
            throw std::runtime_error("Could not read the file");
        }
        if (n == 0) break;
        buf.insert(buf.end(), chunk, chunk + n);
    }
    return fromBuffer(std::move(buf));
}

std::unique_ptr<MappedFile> MappedFile::fromPath(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("Could not open " + path);
    std::unique_ptr<MappedFile> f;
    try {
        f = fromFd(fd);
    } catch (...) {
        close(fd);
        throw;
    }
    close(fd);
    return f;
}

std::unique_ptr<MappedFile> MappedFile::fromBuffer(std::vector<uint8_t>&& data) {
    std::unique_ptr<MappedFile> f(new MappedFile());
    f->buffer_ = std::move(data);
    f->data_ = f->buffer_.data();
    f->size_ = f->buffer_.size();
    return f;
}

}  // namespace fsv
