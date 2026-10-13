#include "exporters.h"

#include <unistd.h>

#include <cerrno>
#include <charconv>
#include <cstring>

#include "fsm_format.h"
#include "xml_scan.h"
#include "zip.h"

namespace fsv {

namespace {

bool included(const ExportOptions& o, size_t part) { return o.partMask.empty() || (part < o.partMask.size() && o.partMask[part]); }
Vec3 offsetOf(const ExportOptions& o, size_t part) { return part < o.partOffsets.size() ? o.partOffsets[part] : Vec3(0, 0, 0); }

class TextOut {
public:
    void str(const char* s) { buf.insert(buf.end(), s, s + std::strlen(s)); }
    void str(const std::string& s) { buf.insert(buf.end(), s.begin(), s.end()); }
    void ch(char c) { buf.push_back((uint8_t)c); }
    void num(float v) {
        char tmp[32];
        auto r = std::to_chars(tmp, tmp + sizeof(tmp), v);
        buf.insert(buf.end(), tmp, r.ptr);
    }
    void num(uint64_t v) {
        char tmp[24];
        auto r = std::to_chars(tmp, tmp + sizeof(tmp), v);
        buf.insert(buf.end(), tmp, r.ptr);
    }
    std::vector<uint8_t> buf;
};

// Per-part welded vertex list (by posId) for formats that share vertices.
struct WeldedPart {
    std::vector<uint32_t> verts;    // render vertex representing each local vertex
    std::vector<uint32_t> indices;  // local triangle indices
};

WeldedPart weldPart(const Model& m, const Part& p, std::vector<uint32_t>& scratch) {
    WeldedPart w;
    if (scratch.size() < m.positionCount) scratch.assign(m.positionCount, UINT32_MAX);
    w.indices.reserve(p.indexCount);
    for (uint32_t i = p.firstIndex; i < p.firstIndex + p.indexCount; ++i) {
        uint32_t v = m.indices[i];
        uint32_t pid = m.posIds[v];
        if (scratch[pid] == UINT32_MAX) {
            scratch[pid] = (uint32_t)w.verts.size();
            w.verts.push_back(v);
        }
        w.indices.push_back(scratch[pid]);
    }
    for (uint32_t v : w.verts) scratch[m.posIds[v]] = UINT32_MAX;
    return w;
}

std::string hexColor(uint32_t rgba) {
    static const char* d = "0123456789ABCDEF";
    std::string s = "#";
    for (int i = 0; i < 4; ++i) {
        uint8_t b = (uint8_t)(rgba >> (8 * i));
        s += d[b >> 4];
        s += d[b & 15];
    }
    return s;
}

}  // namespace

std::vector<uint8_t> exportSTL(const Model& m, const ExportOptions& opt) {
    uint64_t tris = 0;
    for (size_t pi = 0; pi < m.parts.size(); ++pi)
        if (included(opt, pi)) tris += m.parts[pi].indexCount / 3;
    if (tris > 0xFFFFFFFFull) throw std::runtime_error("Too many triangles for STL");
    std::vector<uint8_t> out(84 + tris * 50, 0);
    const char* header = "Binary STL exported by Meshwise";
    std::memcpy(out.data(), header, std::strlen(header));
    uint32_t tc = (uint32_t)tris;
    std::memcpy(out.data() + 80, &tc, 4);
    uint8_t* p = out.data() + 84;
    for (size_t pi = 0; pi < m.parts.size(); ++pi) {
        if (!included(opt, pi)) continue;
        const Part& part = m.parts[pi];
        Vec3 off = offsetOf(opt, pi);
        for (uint32_t i = part.firstIndex; i < part.firstIndex + part.indexCount; i += 3) {
            Vec3 a = m.vertex(m.indices[i]) + off, b = m.vertex(m.indices[i + 1]) + off, c = m.vertex(m.indices[i + 2]) + off;
            Vec3 n = normalize(cross(b - a, c - a));
            float rec[12] = {n.x, n.y, n.z, a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z};
            std::memcpy(p, rec, 48);
            p += 50;
        }
    }
    return out;
}

std::vector<uint8_t> exportOBJ(const Model& m, const ExportOptions& opt) {
    TextOut o;
    o.str("# Exported by Meshwise\n");
    std::vector<uint32_t> scratch;
    uint64_t base = 1;
    for (size_t pi = 0; pi < m.parts.size(); ++pi) {
        if (!included(opt, pi)) continue;
        const Part& part = m.parts[pi];
        Vec3 off = offsetOf(opt, pi);
        WeldedPart w = weldPart(m, part, scratch);
        o.str("o ");
        std::string name = part.name;
        for (char& c : name) if (c == '\n' || c == '\r') c = ' ';
        o.str(name.empty() ? "part" : name);
        o.ch('\n');
        for (uint32_t v : w.verts) {
            Vec3 p = m.vertex(v) + off;
            o.str("v ");
            o.num(p.x); o.ch(' '); o.num(p.y); o.ch(' '); o.num(p.z);
            o.ch('\n');
        }
        for (size_t i = 0; i + 2 < w.indices.size(); i += 3) {
            o.str("f ");
            o.num(base + w.indices[i]); o.ch(' ');
            o.num(base + w.indices[i + 1]); o.ch(' ');
            o.num(base + w.indices[i + 2]);
            o.ch('\n');
        }
        base += w.verts.size();
    }
    return std::move(o.buf);
}

std::vector<uint8_t> export3MF(const Model& m, const ExportOptions& opt) {
    TextOut o;
    o.str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          "<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n"
          " <metadata name=\"Application\">Meshwise</metadata>\n"
          " <resources>\n  <basematerials id=\"1\">\n");
    std::vector<size_t> exported;
    for (size_t pi = 0; pi < m.parts.size(); ++pi)
        if (included(opt, pi)) exported.push_back(pi);
    for (size_t k = 0; k < exported.size(); ++k) {
        size_t pi = exported[k];
        uint32_t col = pi < opt.partColors.size() ? opt.partColors[pi] : m.parts[pi].color;
        o.str("   <base name=\"");
        o.str(xmlEscape(m.parts[pi].name));
        o.str("\" displaycolor=\"");
        o.str(hexColor(col | 0xFF000000u));
        o.str("\"/>\n");
    }
    o.str("  </basematerials>\n");
    std::vector<uint32_t> scratch;
    for (size_t k = 0; k < exported.size(); ++k) {
        size_t pi = exported[k];
        const Part& part = m.parts[pi];
        Vec3 off = offsetOf(opt, pi);
        WeldedPart w = weldPart(m, part, scratch);
        o.str("  <object id=\"");
        o.num((uint64_t)(k + 2));
        o.str("\" type=\"model\" name=\"");
        o.str(xmlEscape(part.name));
        o.str("\" pid=\"1\" pindex=\"");
        o.num((uint64_t)k);
        o.str("\">\n   <mesh>\n    <vertices>\n");
        for (uint32_t v : w.verts) {
            Vec3 p = m.vertex(v) + off;
            o.str("     <vertex x=\""); o.num(p.x);
            o.str("\" y=\""); o.num(p.y);
            o.str("\" z=\""); o.num(p.z);
            o.str("\"/>\n");
        }
        o.str("    </vertices>\n    <triangles>\n");
        for (size_t i = 0; i + 2 < w.indices.size(); i += 3) {
            o.str("     <triangle v1=\""); o.num((uint64_t)w.indices[i]);
            o.str("\" v2=\""); o.num((uint64_t)w.indices[i + 1]);
            o.str("\" v3=\""); o.num((uint64_t)w.indices[i + 2]);
            o.str("\"/>\n");
        }
        o.str("    </triangles>\n   </mesh>\n  </object>\n");
    }
    o.str(" </resources>\n <build>\n");
    for (size_t k = 0; k < exported.size(); ++k) {
        o.str("  <item objectid=\"");
        o.num((uint64_t)(k + 2));
        o.str("\"/>\n");
    }
    o.str(" </build>\n</model>\n");

    static const char* contentTypes =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
        " <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
        " <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n"
        "</Types>\n";
    static const char* rels =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
        " <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" "
        "Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
        "</Relationships>\n";
    ZipWriter zip;
    zip.add("[Content_Types].xml", contentTypes, std::strlen(contentTypes));
    zip.add("_rels/.rels", rels, std::strlen(rels));
    zip.add("3D/3dmodel.model", o.buf.data(), o.buf.size());
    return zip.finish();
}

std::vector<uint8_t> exportFSM(const Model& m, const ExportOptions& opt) {
    uint32_t count = 0;
    for (size_t pi = 0; pi < m.parts.size(); ++pi)
        if (included(opt, pi)) ++count;
    FsmWriter w(count);
    std::vector<uint32_t> idx, edges, cols;
    std::vector<float> pos;
    // Partition CAD edges by part once.
    std::vector<std::vector<uint32_t>> partEdges(m.parts.size());
    for (size_t i = 0; i + 1 < m.cadEdges.size(); i += 2) {
        uint32_t a = m.cadEdges[i];
        if (a < m.partIds.size()) {
            auto& pe = partEdges[m.partIds[a]];
            pe.push_back(m.cadEdges[i]);
            pe.push_back(m.cadEdges[i + 1]);
        }
    }
    for (size_t pi = 0; pi < m.parts.size(); ++pi) {
        if (!included(opt, pi)) continue;
        const Part& p = m.parts[pi];
        Vec3 off = offsetOf(opt, pi);
        idx.resize(p.indexCount);
        for (uint32_t i = 0; i < p.indexCount; ++i) idx[i] = m.indices[p.firstIndex + i] - p.firstVertex;
        edges.clear();
        for (uint32_t e : partEdges[pi]) edges.push_back(e - p.firstVertex);
        pos.assign(m.positions.begin() + (long)p.firstVertex * 3, m.positions.begin() + (long)(p.firstVertex + p.vertexCount) * 3);
        for (size_t i = 0; i < p.vertexCount; ++i) { pos[3 * i] += off.x; pos[3 * i + 1] += off.y; pos[3 * i + 2] += off.z; }
        const uint32_t* colors = nullptr;
        if (p.hasVertexColors && !m.colors.empty()) colors = m.colors.data() + p.firstVertex;
        uint32_t flags = (p.hasFileColor ? kFsmFlagFileColor : 0) | (colors ? kFsmFlagVertexColors : 0);
        w.part(p.name, p.color, flags, pos.data(), m.normals.data() + (size_t)p.firstVertex * 3, colors, p.vertexCount,
               idx.data(), p.indexCount, edges.data(), (uint32_t)edges.size());
    }
    return std::move(w.bytes());
}

bool writeAll(int fd, const std::vector<uint8_t>& bytes) {
    size_t off = 0;
    while (off < bytes.size()) {
        ssize_t n = ::write(fd, bytes.data() + off, std::min<size_t>(bytes.size() - off, 1 << 20));
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        off += (size_t)n;
    }
    return true;
}

}  // namespace fsv
