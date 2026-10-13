// PLY loader (ASCII, binary little/big endian) with vertex and face colours.
#include <cstring>

#include "loaders.h"
#include "parse_util.h"

namespace fsv {

namespace {

enum class PType { Int8, UInt8, Int16, UInt16, Int32, UInt32, Float32, Float64, Invalid };

PType parseType(const std::string& s) {
    if (s == "char" || s == "int8") return PType::Int8;
    if (s == "uchar" || s == "uint8") return PType::UInt8;
    if (s == "short" || s == "int16") return PType::Int16;
    if (s == "ushort" || s == "uint16") return PType::UInt16;
    if (s == "int" || s == "int32") return PType::Int32;
    if (s == "uint" || s == "uint32") return PType::UInt32;
    if (s == "float" || s == "float32") return PType::Float32;
    if (s == "double" || s == "float64") return PType::Float64;
    return PType::Invalid;
}

int typeSize(PType t) {
    switch (t) {
        case PType::Int8: case PType::UInt8: return 1;
        case PType::Int16: case PType::UInt16: return 2;
        case PType::Int32: case PType::UInt32: case PType::Float32: return 4;
        case PType::Float64: return 8;
        default: return 0;
    }
}

struct Property {
    std::string name;
    PType type = PType::Invalid;
    bool isList = false;
    PType countType = PType::Invalid;
};

struct Element {
    std::string name;
    uint64_t count = 0;
    std::vector<Property> props;
};

class BinReader {
public:
    BinReader(const uint8_t* p, const uint8_t* end, bool bigEndian) : p_(p), end_(end), be_(bigEndian) {}
    bool ok() const { return p_ <= end_; }
    const uint8_t* pos() const { return p_; }

    double read(PType t) {
        int n = typeSize(t);
        if (p_ + n > end_) { p_ = end_ + 1; return 0; }
        uint8_t b[8];
        std::memcpy(b, p_, (size_t)n);
        p_ += n;
        if (be_) std::reverse(b, b + n);
        switch (t) {
            case PType::Int8: return (double)(int8_t)b[0];
            case PType::UInt8: return (double)b[0];
            case PType::Int16: { int16_t v; std::memcpy(&v, b, 2); return v; }
            case PType::UInt16: { uint16_t v; std::memcpy(&v, b, 2); return v; }
            case PType::Int32: { int32_t v; std::memcpy(&v, b, 4); return v; }
            case PType::UInt32: { uint32_t v; std::memcpy(&v, b, 4); return v; }
            case PType::Float32: { float v; std::memcpy(&v, b, 4); return v; }
            case PType::Float64: { double v; std::memcpy(&v, b, 8); return v; }
            default: return 0;
        }
    }

private:
    const uint8_t* p_;
    const uint8_t* end_;
    bool be_;
};

uint8_t colorByte(double v, PType t) {
    if (t == PType::Float32 || t == PType::Float64) return (uint8_t)std::clamp(v * 255.0 + 0.5, 0.0, 255.0);
    if (t == PType::UInt16 || t == PType::Int16) return (uint8_t)std::clamp(v / 257.0, 0.0, 255.0);
    return (uint8_t)std::clamp(v, 0.0, 255.0);
}

int colorChannel(const std::string& n) {
    if (n == "red" || n == "r" || n == "diffuse_red") return 0;
    if (n == "green" || n == "g" || n == "diffuse_green") return 1;
    if (n == "blue" || n == "b" || n == "diffuse_blue") return 2;
    if (n == "alpha" || n == "a" || n == "diffuse_alpha") return 3;
    return -1;
}

}  // namespace

RawModel loadPLY(const uint8_t* data, size_t size, Progress progress) {
    RawModel rm;
    rm.format = "PLY";
    const char* text = reinterpret_cast<const char*>(data);
    // Header.
    if (size < 10 || std::memcmp(text, "ply", 3) != 0) throw std::runtime_error("Not a PLY file");
    size_t headerEnd = 0;
    {
        const char* e = static_cast<const char*>(memmem(text, std::min<size_t>(size, 1 << 20), "end_header", 10));
        if (!e) throw std::runtime_error("PLY header is incomplete");
        const char* nl = static_cast<const char*>(std::memchr(e, '\n', size - (size_t)(e - text)));
        headerEnd = nl ? (size_t)(nl - text) + 1 : size;
    }
    std::string header(text, headerEnd);
    std::vector<Element> elements;
    int format = 0;  // 0 ascii, 1 LE, 2 BE
    {
        size_t pos = 0;
        while (pos < header.size()) {
            size_t nl = header.find('\n', pos);
            if (nl == std::string::npos) nl = header.size();
            std::string line = header.substr(pos, nl - pos);
            pos = nl + 1;
            if (!line.empty() && line.back() == '\r') line.pop_back();
            std::vector<std::string> w;
            size_t i = 0;
            while (i < line.size()) {
                while (i < line.size() && isSpace(line[i])) ++i;
                size_t s = i;
                while (i < line.size() && !isSpace(line[i])) ++i;
                if (i > s) w.push_back(line.substr(s, i - s));
            }
            if (w.empty()) continue;
            if (w[0] == "format" && w.size() >= 2) {
                if (w[1] == "ascii") format = 0;
                else if (w[1] == "binary_little_endian") format = 1;
                else if (w[1] == "binary_big_endian") format = 2;
                else throw std::runtime_error("Unsupported PLY format");
            } else if (w[0] == "element" && w.size() >= 3) {
                Element e;
                e.name = w[1];
                e.count = std::strtoull(w[2].c_str(), nullptr, 10);
                elements.push_back(e);
            } else if (w[0] == "property" && !elements.empty()) {
                Property p;
                if (w.size() >= 5 && w[1] == "list") {
                    p.isList = true;
                    p.countType = parseType(w[2]);
                    p.type = parseType(w[3]);
                    p.name = w[4];
                } else if (w.size() >= 3) {
                    p.type = parseType(w[1]);
                    p.name = w[2];
                }
                if (p.type == PType::Invalid || (p.isList && p.countType == PType::Invalid))
                    throw std::runtime_error("Unsupported PLY property type");
                elements.back().props.push_back(p);
            }
        }
    }

    RawPart part;
    std::vector<double> vals;
    const uint8_t* body = data + headerEnd;
    const uint8_t* end = data + size;
    TextCursor cur{reinterpret_cast<const char*>(body), reinterpret_cast<const char*>(end)};
    BinReader br(body, end, format == 2);
    uint64_t vertexCount = 0;
    bool hasVColor = false, hasFColor = false;
    uint64_t totalItems = 0, doneItems = 0;
    for (const Element& e : elements) totalItems += e.count;

    auto readScalar = [&](PType t) -> double {
        if (format == 0) {
            float f;
            if (!parseFloat(cur.p, cur.end, f)) {
                cur.skipSpace();
                if (!parseFloat(cur.p, cur.end, f)) return 0;
            }
            return f;
        }
        return br.read(t);
    };
    auto nextLine = [&]() { if (format == 0) cur.skipLine(); };

    for (const Element& e : elements) {
        const bool isVertex = e.name == "vertex";
        const bool isFace = e.name == "face";
        int ix = -1, iy = -1, iz = -1, ilist = -1;
        int ch[4] = {-1, -1, -1, -1};
        bool simpleBinary = format != 0;
        size_t stride = 0;
        for (size_t i = 0; i < e.props.size(); ++i) {
            const Property& p = e.props[i];
            if (p.isList) simpleBinary = false;
            stride += (size_t)typeSize(p.type);
            if (p.name == "x") ix = (int)i;
            else if (p.name == "y") iy = (int)i;
            else if (p.name == "z") iz = (int)i;
            else if (p.isList && (p.name == "vertex_indices" || p.name == "vertex_index")) ilist = (int)i;
            int c = colorChannel(p.name);
            if (c >= 0 && !p.isList) ch[c] = (int)i;
        }
        if (isVertex) {
            if (ix < 0 || iy < 0 || iz < 0) throw std::runtime_error("PLY vertices have no x/y/z");
            vertexCount = e.count;
            part.positions.resize(e.count * 3);
            hasVColor = ch[0] >= 0 && ch[1] >= 0 && ch[2] >= 0;
            if (hasVColor) part.vertexColors.resize(e.count);
        }
        if (isFace) hasFColor = ch[0] >= 0 && ch[1] >= 0 && ch[2] >= 0;

        if (simpleBinary && !isFace) {
            // Fast path: fixed stride records.
            std::vector<size_t> offs(e.props.size());
            size_t o = 0;
            for (size_t i = 0; i < e.props.size(); ++i) { offs[i] = o; o += (size_t)typeSize(e.props[i].type); }
            const uint8_t* p = br.pos();
            if (p + stride * e.count > end) throw std::runtime_error("PLY file is truncated");
            if (isVertex) {
                for (uint64_t v = 0; v < e.count; ++v) {
                    const uint8_t* rec = p + v * stride;
                    BinReader r(rec, rec + stride, format == 2);
                    for (int a = 0; a < 3; ++a) {
                        int pi = a == 0 ? ix : (a == 1 ? iy : iz);
                        BinReader rr(rec + offs[(size_t)pi], rec + stride, format == 2);
                        part.positions[v * 3 + (size_t)a] = (float)rr.read(e.props[(size_t)pi].type);
                    }
                    if (hasVColor) {
                        uint8_t c[4] = {255, 255, 255, 255};
                        for (int k = 0; k < 4; ++k) {
                            if (ch[k] < 0) continue;
                            BinReader rr(rec + offs[(size_t)ch[k]], rec + stride, format == 2);
                            c[k] = colorByte(rr.read(e.props[(size_t)ch[k]].type), e.props[(size_t)ch[k]].type);
                        }
                        part.vertexColors[v] = (uint32_t)c[0] | ((uint32_t)c[1] << 8) | ((uint32_t)c[2] << 16) | ((uint32_t)c[3] << 24);
                    }
                    if ((v & 0xFFFF) == 0) progress.report((float)(doneItems + v) / (float)totalItems);
                    (void)r;
                }
            }
            br = BinReader(p + stride * e.count, end, format == 2);
            doneItems += e.count;
            continue;
        }

        for (uint64_t it = 0; it < e.count; ++it) {
            uint8_t fc[4] = {255, 255, 255, 255};
            size_t faceStart = part.indices.size();
            for (size_t i = 0; i < e.props.size(); ++i) {
                const Property& p = e.props[i];
                if (p.isList) {
                    double cnt = readScalar(p.countType);
                    uint64_t n = cnt > 0 ? (uint64_t)cnt : 0;
                    if (n > 1000000) throw std::runtime_error("Invalid PLY face");
                    if (isFace && (int)i == ilist) {
                        vals.resize(n);
                        for (uint64_t k = 0; k < n; ++k) vals[k] = readScalar(p.type);
                        for (uint64_t k = 2; k < n; ++k) {
                            double a = vals[0], b = vals[k - 1], c = vals[k];
                            if (a < 0 || b < 0 || c < 0 || a >= vertexCount || b >= vertexCount || c >= vertexCount) continue;
                            part.indices.insert(part.indices.end(), {(uint32_t)a, (uint32_t)b, (uint32_t)c});
                        }
                    } else {
                        for (uint64_t k = 0; k < n; ++k) readScalar(p.type);
                    }
                } else {
                    double v = readScalar(p.type);
                    if (isVertex) {
                        if ((int)i == ix) part.positions[it * 3] = (float)v;
                        else if ((int)i == iy) part.positions[it * 3 + 1] = (float)v;
                        else if ((int)i == iz) part.positions[it * 3 + 2] = (float)v;
                        else if (hasVColor) {
                            int c = colorChannel(p.name);
                            if (c >= 0) fc[c] = colorByte(v, p.type);
                        }
                    } else if (isFace && hasFColor) {
                        int c = colorChannel(p.name);
                        if (c >= 0) fc[c] = colorByte(v, p.type);
                    }
                }
            }
            uint32_t color = (uint32_t)fc[0] | ((uint32_t)fc[1] << 8) | ((uint32_t)fc[2] << 16) | ((uint32_t)fc[3] << 24);
            if (isVertex && hasVColor) part.vertexColors[it] = color;
            if (isFace && hasFColor)
                for (size_t k = faceStart; k < part.indices.size(); k += 3) part.faceColors.push_back(color);
            nextLine();
            if (format != 0 && !br.ok()) throw std::runtime_error("PLY file is truncated");
            if ((it & 0xFFFF) == 0) progress.report((float)(doneItems + it) / (float)totalItems);
        }
        doneItems += e.count;
    }
    if (part.indices.empty()) {
        // Point clouds are not supported; a vertex-only PLY may still be a triangle soup.
        if (vertexCount >= 3 && vertexCount % 3 == 0 && part.positions.size() >= 9) {
            rm.warnings.push_back("PLY has no faces; vertices interpreted as triangles");
        } else {
            throw std::runtime_error("The PLY file has no faces (point clouds are not supported)");
        }
    }
    if (!part.faceColors.empty()) part.vertexColors.clear();
    rm.parts.push_back(std::move(part));
    return rm;
}

}  // namespace fsv
