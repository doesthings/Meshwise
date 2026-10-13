#include "zip.h"

#include <zlib.h>

#include <algorithm>
#include <cstring>
#include <stdexcept>

namespace fsv {

namespace {

uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
uint64_t rd64(const uint8_t* p) { return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32); }

void wr16(std::vector<uint8_t>& o, uint16_t v) { o.push_back((uint8_t)v); o.push_back((uint8_t)(v >> 8)); }
void wr32(std::vector<uint8_t>& o, uint32_t v) { for (int i = 0; i < 4; ++i) o.push_back((uint8_t)(v >> (8 * i))); }
void wr64(std::vector<uint8_t>& o, uint64_t v) { for (int i = 0; i < 8; ++i) o.push_back((uint8_t)(v >> (8 * i))); }

std::string lower(std::string s) {
    for (char& c : s)
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    return s;
}

}  // namespace

std::string normalizeZipPath(const std::string& s) {
    std::string r = s;
    std::replace(r.begin(), r.end(), '\\', '/');
    while (!r.empty() && r[0] == '/') r.erase(r.begin());
    return r;
}

bool ZipReader::open(const uint8_t* data, size_t size) {
    data_ = data;
    size_ = size;
    entries_.clear();
    if (size < 22) return false;
    // Locate the end-of-central-directory record.
    size_t maxBack = std::min<size_t>(size, 65535 + 22);
    size_t eocd = SIZE_MAX;
    for (size_t i = 22; i <= maxBack; ++i) {
        const uint8_t* p = data + size - i;
        if (rd32(p) == 0x06054b50) { eocd = size - i; break; }
    }
    if (eocd == SIZE_MAX) return false;
    uint64_t cdCount = rd16(data + eocd + 10);
    uint64_t cdSize = rd32(data + eocd + 12);
    uint64_t cdOffset = rd32(data + eocd + 16);
    // Zip64 end of central directory locator.
    if (eocd >= 20 && rd32(data + eocd - 20) == 0x07064b50) {
        uint64_t z64 = rd64(data + eocd - 20 + 8);
        if (z64 + 56 <= size && rd32(data + z64) == 0x06064b50) {
            cdCount = rd64(data + z64 + 32);
            cdSize = rd64(data + z64 + 40);
            cdOffset = rd64(data + z64 + 48);
        }
    }
    if (cdOffset + cdSize > size) return false;
    const uint8_t* p = data + cdOffset;
    const uint8_t* end = p + cdSize;
    for (uint64_t i = 0; i < cdCount && p + 46 <= end; ++i) {
        if (rd32(p) != 0x02014b50) break;
        Entry e;
        e.method = rd16(p + 10);
        e.compressedSize = rd32(p + 20);
        e.size = rd32(p + 24);
        uint16_t nameLen = rd16(p + 28), extraLen = rd16(p + 30), commentLen = rd16(p + 32);
        e.localHeaderOffset = rd32(p + 42);
        if (p + 46 + nameLen + extraLen + commentLen > end) break;
        e.name.assign(reinterpret_cast<const char*>(p + 46), nameLen);
        // Zip64 extended information.
        const uint8_t* x = p + 46 + nameLen;
        const uint8_t* xe = x + extraLen;
        while (x + 4 <= xe) {
            uint16_t id = rd16(x), len = rd16(x + 2);
            if (id == 0x0001) {
                const uint8_t* q = x + 4;
                if (e.size == 0xFFFFFFFFu && q + 8 <= x + 4 + len) { e.size = rd64(q); q += 8; }
                if (e.compressedSize == 0xFFFFFFFFu && q + 8 <= x + 4 + len) { e.compressedSize = rd64(q); q += 8; }
                if (e.localHeaderOffset == 0xFFFFFFFFu && q + 8 <= x + 4 + len) { e.localHeaderOffset = rd64(q); }
            }
            x += 4 + len;
        }
        if (!e.name.empty() && e.name.back() != '/') entries_.push_back(std::move(e));
        p += 46 + nameLen + extraLen + commentLen;
    }
    return !entries_.empty();
}

const ZipReader::Entry* ZipReader::find(const std::string& name) const {
    std::string key = lower(normalizeZipPath(name));
    for (const Entry& e : entries_)
        if (lower(normalizeZipPath(e.name)) == key) return &e;
    return nullptr;
}

bool ZipReader::extract(const Entry& e, std::vector<uint8_t>& out) const {
    if (e.localHeaderOffset + 30 > size_) return false;
    const uint8_t* lh = data_ + e.localHeaderOffset;
    if (rd32(lh) != 0x04034b50) return false;
    uint64_t start = e.localHeaderOffset + 30 + rd16(lh + 26) + rd16(lh + 28);
    if (start + e.compressedSize > size_) return false;
    const uint8_t* src = data_ + start;
    if (e.method == 0) {
        out.assign(src, src + e.compressedSize);
        return true;
    }
    if (e.method != 8) return false;
    out.resize((size_t)e.size);
    z_stream zs;
    std::memset(&zs, 0, sizeof(zs));
    if (inflateInit2(&zs, -MAX_WBITS) != Z_OK) return false;
    // zlib's avail_* are 32-bit; feed in chunks for very large entries.
    uint64_t inLeft = e.compressedSize, outDone = 0;
    zs.next_in = const_cast<Bytef*>(src);
    int ret = Z_OK;
    while (ret != Z_STREAM_END) {
        if (zs.avail_in == 0 && inLeft > 0) {
            uInt chunk = (uInt)std::min<uint64_t>(inLeft, 1u << 30);
            zs.avail_in = chunk;
            inLeft -= chunk;
        }
        if (outDone >= out.size()) out.resize(out.size() + (1 << 20));  // size field may lie
        zs.next_out = out.data() + outDone;
        zs.avail_out = (uInt)std::min<uint64_t>(out.size() - outDone, 1u << 30);
        uInt before = zs.avail_out;
        ret = inflate(&zs, Z_NO_FLUSH);
        outDone += before - zs.avail_out;
        if (ret != Z_OK && ret != Z_STREAM_END) {
            if (ret == Z_BUF_ERROR && zs.avail_in == 0 && inLeft == 0) break;
            if (ret != Z_BUF_ERROR) { inflateEnd(&zs); return false; }
        }
    }
    inflateEnd(&zs);
    out.resize((size_t)outDone);
    return true;
}

void ZipWriter::add(const std::string& name, const void* data, size_t size, bool compress) {
    Rec r;
    r.name = name;
    r.size = size;
    r.crc = (uint32_t)crc32(0L, Z_NULL, 0);
    // crc32 takes uInt lengths; process in chunks.
    const Bytef* p = static_cast<const Bytef*>(data);
    for (size_t off = 0; off < size;) {
        uInt n = (uInt)std::min<size_t>(size - off, 1u << 30);
        r.crc = (uint32_t)crc32(r.crc, p + off, n);
        off += n;
    }
    std::vector<uint8_t> comp;
    r.method = 0;
    if (compress && size > 64) {
        z_stream zs;
        std::memset(&zs, 0, sizeof(zs));
        if (deflateInit2(&zs, 6, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) == Z_OK) {
            comp.resize(deflateBound(&zs, (uLong)std::min<size_t>(size, 0xFFFFFFFFu)) + 1024);
            size_t inOff = 0, outOff = 0;
            int ret = Z_OK;
            while (ret != Z_STREAM_END) {
                if (zs.avail_in == 0 && inOff < size) {
                    uInt n = (uInt)std::min<size_t>(size - inOff, 1u << 28);
                    zs.next_in = const_cast<Bytef*>(p + inOff);
                    zs.avail_in = n;
                    inOff += n;
                }
                if (comp.size() - outOff < (1 << 16)) comp.resize(comp.size() + (1 << 20));
                zs.next_out = comp.data() + outOff;
                zs.avail_out = (uInt)std::min<size_t>(comp.size() - outOff, 1u << 30);
                uInt before = zs.avail_out;
                ret = deflate(&zs, inOff >= size && zs.avail_in == 0 ? Z_FINISH : Z_NO_FLUSH);
                outOff += before - zs.avail_out;
                if (ret == Z_STREAM_ERROR) break;
            }
            deflateEnd(&zs);
            if (ret == Z_STREAM_END && outOff < size) {
                comp.resize(outOff);
                r.method = 8;
            }
        }
    }
    const bool z64 = size >= 0xFFFFFFFFu || out_.size() >= 0xFFFFFFFFu;
    r.offset = out_.size();
    r.compSize = r.method == 8 ? comp.size() : size;
    wr32(out_, 0x04034b50);
    wr16(out_, z64 ? 45 : 20);
    wr16(out_, 0x0800);  // UTF-8 names
    wr16(out_, r.method);
    wr16(out_, 0);
    wr16(out_, 0x21);  // 1980-01-01
    wr32(out_, r.crc);
    wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)r.compSize);
    wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)r.size);
    wr16(out_, (uint16_t)name.size());
    wr16(out_, z64 ? 20 : 0);
    out_.insert(out_.end(), name.begin(), name.end());
    if (z64) {
        wr16(out_, 0x0001);
        wr16(out_, 16);
        wr64(out_, r.size);
        wr64(out_, r.compSize);
    }
    if (r.method == 8) out_.insert(out_.end(), comp.begin(), comp.end());
    else out_.insert(out_.end(), p, p + size);
    recs_.push_back(std::move(r));
}

std::vector<uint8_t> ZipWriter::finish() {
    uint64_t cdStart = out_.size();
    for (const Rec& r : recs_) {
        const bool z64 = r.size >= 0xFFFFFFFFu || r.compSize >= 0xFFFFFFFFu || r.offset >= 0xFFFFFFFFu;
        wr32(out_, 0x02014b50);
        wr16(out_, z64 ? 45 : 20);
        wr16(out_, z64 ? 45 : 20);
        wr16(out_, 0x0800);
        wr16(out_, r.method);
        wr16(out_, 0);
        wr16(out_, 0x21);
        wr32(out_, r.crc);
        wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)r.compSize);
        wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)r.size);
        wr16(out_, (uint16_t)r.name.size());
        wr16(out_, z64 ? 28 : 0);
        wr16(out_, 0);
        wr16(out_, 0);
        wr16(out_, 0);
        wr32(out_, 0);
        wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)r.offset);
        out_.insert(out_.end(), r.name.begin(), r.name.end());
        if (z64) {
            wr16(out_, 0x0001);
            wr16(out_, 24);
            wr64(out_, r.size);
            wr64(out_, r.compSize);
            wr64(out_, r.offset);
        }
    }
    uint64_t cdSize = out_.size() - cdStart;
    const bool z64 = cdStart >= 0xFFFFFFFFu || recs_.size() >= 0xFFFF;
    if (z64) {
        uint64_t z64Off = out_.size();
        wr32(out_, 0x06064b50);
        wr64(out_, 44);
        wr16(out_, 45);
        wr16(out_, 45);
        wr32(out_, 0);
        wr32(out_, 0);
        wr64(out_, recs_.size());
        wr64(out_, recs_.size());
        wr64(out_, cdSize);
        wr64(out_, cdStart);
        wr32(out_, 0x07064b50);
        wr32(out_, 0);
        wr64(out_, z64Off);
        wr32(out_, 1);
    }
    wr32(out_, 0x06054b50);
    wr16(out_, 0);
    wr16(out_, 0);
    wr16(out_, z64 ? 0xFFFF : (uint16_t)recs_.size());
    wr16(out_, z64 ? 0xFFFF : (uint16_t)recs_.size());
    wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)cdSize);
    wr32(out_, z64 ? 0xFFFFFFFFu : (uint32_t)cdStart);
    wr16(out_, 0);
    return std::move(out_);
}

}  // namespace fsv
