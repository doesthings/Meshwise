// 3MF loader: core spec (meshes, components, build items, base materials, colour groups),
// production extension (external model parts) and slicer metadata from PrusaSlicer /
// Bambu Studio (part names, extruder colours and multi-material painting).
#include <array>
#include <cctype>
#include <cstdlib>
#include <functional>
#include <map>
#include <unordered_map>

#include "loaders.h"
#include "parse_util.h"
#include "xml_scan.h"
#include "zip.h"

namespace fsv {

namespace {

struct Xform {
    // Row-vector convention used by 3MF: p' = [x y z 1] * M (4x3).
    float m[12] = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
    Vec3 apply(const Vec3& p) const {
        return {p.x * m[0] + p.y * m[3] + p.z * m[6] + m[9], p.x * m[1] + p.y * m[4] + p.z * m[7] + m[10],
                p.x * m[2] + p.y * m[5] + p.z * m[8] + m[11]};
    }
    // Returns "this then other".
    Xform then(const Xform& o) const {
        Xform r;
        for (int row = 0; row < 4; ++row)
            for (int col = 0; col < 3; ++col) {
                float s = row == 3 ? o.m[9 + col] : 0.f;
                for (int k = 0; k < 3; ++k) s += m[row * 3 + k] * o.m[k * 3 + col];
                r.m[row * 3 + col] = s;
            }
        return r;
    }
    bool identity() const {
        static const float id[12] = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        for (int i = 0; i < 12; ++i)
            if (m[i] != id[i]) return false;
        return true;
    }
};

Xform parseXform(std::string_view s) {
    Xform x;
    const char* p = s.data();
    const char* e = p + s.size();
    float v[12];
    int n = 0;
    while (n < 12 && parseFloat(p, e, v[n])) {
        ++n;
        while (p < e && isSpace(*p)) ++p;
    }
    if (n == 12) std::memcpy(x.m, v, sizeof(v));
    return x;
}

uint32_t parseColor(std::string_view s) {
    // #RRGGBB or #RRGGBBAA
    if (s.size() < 7 || s[0] != '#') return 0;
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
    };
    auto byte = [&](size_t i) { return (uint32_t)(hex(s[i]) * 16 + hex(s[i + 1])); };
    uint32_t r = byte(1), g = byte(3), b = byte(5), a = s.size() >= 9 ? byte(7) : 255;
    return r | (g << 8) | (b << 16) | (a << 24);
}

uint32_t svToU32(std::string_view s, uint32_t def = 0) {
    const char* p = s.data();
    long long v;
    return parseInt(p, s.data() + s.size(), v) && v >= 0 ? (uint32_t)v : def;
}

float svToF(std::string_view s, float def = 0) {
    const char* p = s.data();
    float v;
    return parseFloat(p, s.data() + s.size(), v) ? v : def;
}

// Decodes a TriangleSelector string (PrusaSlicer "mmu_segmentation" / Bambu "paint_color")
// and returns the state covering the largest area (0 = not painted).
int decodePaint(std::string_view s) {
    std::vector<uint8_t> bits;
    bits.reserve(s.size() * 4);
    for (size_t i = s.size(); i-- > 0;) {
        char ch = s[i];
        int d = (ch >= '0' && ch <= '9') ? ch - '0' : (ch >= 'A' && ch <= 'F') ? ch - 'A' + 10 : (ch >= 'a' && ch <= 'f') ? ch - 'a' + 10 : 0;
        for (int b = 0; b < 4; ++b) bits.push_back((uint8_t)((d >> b) & 1));
    }
    size_t off = 0;
    auto nibble = [&]() -> int {
        if (off + 4 > bits.size()) { off = bits.size() + 1; return 0; }
        int n = 0;
        for (int i = 0; i < 4; ++i) n |= bits[off + (size_t)i] << i;
        off += 4;
        return n;
    };
    std::array<double, 32> weight{};
    std::function<void(double, int)> parse = [&](double w, int depth) {
        if (off > bits.size() || depth > 24) return;
        int code = nibble();
        int split = code & 3;
        if (split == 0) {
            int state = code >> 2;
            if (state == 3) state = nibble() + 3;
            if (state < (int)weight.size()) weight[(size_t)state] += w;
        } else {
            int children = split + 1;
            for (int c = 0; c < children; ++c) parse(w / children, depth + 1);
        }
    };
    parse(1.0, 0);
    int best = 0;
    double bw = weight[0];
    for (size_t i = 1; i < weight.size(); ++i)
        if (weight[i] > bw) { bw = weight[i]; best = (int)i; }
    return best;
}

struct Object3mf {
    std::string name;
    std::string type;
    std::vector<float> verts;
    std::vector<uint32_t> tris;
    std::vector<uint32_t> triPid, triP1;  // per triangle property refs (UINT32_MAX = none)
    std::vector<uint8_t> triPaint;        // per triangle painted extruder (0 = none)
    bool anyTriProps = false, anyPaint = false;
    uint32_t pid = UINT32_MAX, pindex = 0;
    struct Comp { std::string path; uint32_t objectId; Xform xf; };
    std::vector<Comp> comps;
};

struct ModelFile {
    std::unordered_map<uint32_t, Object3mf> objects;
    std::unordered_map<uint32_t, std::vector<uint32_t>> groups;  // basematerials / colorgroup colours
    std::unordered_map<uint32_t, std::vector<std::string>> groupNames;
    struct Item { uint32_t objectId; std::string path; Xform xf; };
    std::vector<Item> items;
    float unitScale = 1.f;
};

void parseModelXml(const char* text, size_t size, ModelFile& mf, Progress prog) {
    XmlScanner sc(text, text + size);
    XmlTag t;
    std::string_view v;
    Object3mf* obj = nullptr;
    std::vector<uint32_t>* group = nullptr;
    std::vector<std::string>* groupNames = nullptr;
    bool inVertices = false, inTriangles = false;
    size_t counter = 0;
    while (sc.next(t)) {
        const std::string_view& n = t.name;
        if (t.closing) {
            if (n == "object") obj = nullptr;
            else if (n == "vertices") inVertices = false;
            else if (n == "triangles") inTriangles = false;
            else if (n == "basematerials" || n == "colorgroup") { group = nullptr; groupNames = nullptr; }
            continue;
        }
        if (inVertices && n == "vertex" && obj) {
            float x = 0, y = 0, z = 0;
            if (xmlAttr(t.attrs, "x", v)) x = svToF(v);
            if (xmlAttr(t.attrs, "y", v)) y = svToF(v);
            if (xmlAttr(t.attrs, "z", v)) z = svToF(v);
            obj->verts.insert(obj->verts.end(), {x, y, z});
        } else if (inTriangles && n == "triangle" && obj) {
            uint32_t a = 0, b = 0, c = 0;
            if (xmlAttr(t.attrs, "v1", v)) a = svToU32(v);
            if (xmlAttr(t.attrs, "v2", v)) b = svToU32(v);
            if (xmlAttr(t.attrs, "v3", v)) c = svToU32(v);
            obj->tris.insert(obj->tris.end(), {a, b, c});
            uint32_t pid = UINT32_MAX, p1 = UINT32_MAX;
            if (xmlAttr(t.attrs, "pid", v)) pid = svToU32(v, UINT32_MAX);
            if (xmlAttr(t.attrs, "p1", v)) p1 = svToU32(v, UINT32_MAX);
            if (pid != UINT32_MAX || p1 != UINT32_MAX) {
                if (!obj->anyTriProps) {
                    obj->triPid.assign(obj->tris.size() / 3 - 1, UINT32_MAX);
                    obj->triP1.assign(obj->tris.size() / 3 - 1, UINT32_MAX);
                    obj->anyTriProps = true;
                }
            }
            if (obj->anyTriProps) { obj->triPid.push_back(pid); obj->triP1.push_back(p1); }
            std::string_view paint;
            if (xmlAttrExact(t.attrs, "paint_color", paint) || xmlAttrExact(t.attrs, "slic3rpe:mmu_segmentation", paint)) {
                if (!obj->anyPaint) {
                    obj->triPaint.assign(obj->tris.size() / 3 - 1, 0);
                    obj->anyPaint = true;
                }
                obj->triPaint.push_back((uint8_t)std::min(255, decodePaint(paint)));
            } else if (obj->anyPaint) {
                obj->triPaint.push_back(0);
            }
            if ((++counter & 0x3FFF) == 0) prog.report((float)(sc.position() - text) / (float)size);
        } else if (n == "vertices") {
            inVertices = !t.selfClosing;
        } else if (n == "triangles") {
            inTriangles = !t.selfClosing;
        } else if (n == "object") {
            uint32_t id = xmlAttr(t.attrs, "id", v) ? svToU32(v) : 0;
            obj = &mf.objects[id];
            if (xmlAttr(t.attrs, "name", v)) obj->name = xmlUnescape(v);
            if (xmlAttr(t.attrs, "type", v)) obj->type = std::string(v);
            if (xmlAttr(t.attrs, "pid", v)) obj->pid = svToU32(v, UINT32_MAX);
            if (xmlAttr(t.attrs, "pindex", v)) obj->pindex = svToU32(v);
            if (t.selfClosing) obj = nullptr;
        } else if (n == "component" && obj) {
            Object3mf::Comp c;
            c.objectId = xmlAttr(t.attrs, "objectid", v) ? svToU32(v) : 0;
            if (xmlAttr(t.attrs, "path", v)) c.path = normalizeZipPath(std::string(v));
            if (xmlAttr(t.attrs, "transform", v)) c.xf = parseXform(v);
            obj->comps.push_back(std::move(c));
        } else if (n == "basematerials" || n == "colorgroup") {
            uint32_t id = xmlAttr(t.attrs, "id", v) ? svToU32(v) : 0;
            group = &mf.groups[id];
            groupNames = &mf.groupNames[id];
            if (t.selfClosing) { group = nullptr; groupNames = nullptr; }
        } else if (n == "base" && group) {
            group->push_back(xmlAttr(t.attrs, "displaycolor", v) ? parseColor(v) : 0xFFFFFFFFu);
            groupNames->push_back(xmlAttr(t.attrs, "name", v) ? xmlUnescape(v) : std::string());
        } else if (n == "color" && group) {
            group->push_back(xmlAttr(t.attrs, "color", v) ? parseColor(v) : 0xFFFFFFFFu);
            groupNames->push_back(std::string());
        } else if (n == "item") {
            ModelFile::Item it;
            it.objectId = xmlAttr(t.attrs, "objectid", v) ? svToU32(v) : 0;
            if (xmlAttr(t.attrs, "path", v)) it.path = normalizeZipPath(std::string(v));
            if (xmlAttr(t.attrs, "transform", v)) it.xf = parseXform(v);
            mf.items.push_back(std::move(it));
        } else if (n == "model") {
            if (xmlAttr(t.attrs, "unit", v)) {
                if (v == "inch") mf.unitScale = 25.4f;
                else if (v == "micron") mf.unitScale = 0.001f;
                else if (v == "centimeter") mf.unitScale = 10.f;
                else if (v == "foot") mf.unitScale = 304.8f;
                else if (v == "meter") mf.unitScale = 1000.f;
            }
        }
    }
}

// Slicer metadata: object/part names and extruder assignments.
struct SlicerMeta {
    struct PartMeta { std::string name; int extruder = 0; };
    struct VolumeMeta { uint32_t first = 0, last = 0; std::string name; int extruder = 0; };
    struct ObjMeta {
        std::string name;
        int extruder = 0;
        std::map<uint32_t, PartMeta> parts;  // Bambu: component object id -> meta
        std::vector<VolumeMeta> volumes;     // Prusa: triangle ranges
    };
    std::unordered_map<uint32_t, ObjMeta> objects;
    std::vector<uint32_t> extruderColors;
};

std::string readEntry(const ZipReader& zip, const std::string& name) {
    const ZipReader::Entry* e = zip.find(name);
    std::vector<uint8_t> buf;
    if (!e || !zip.extract(*e, buf)) return std::string();
    return std::string(buf.begin(), buf.end());
}

std::vector<uint32_t> parseColorList(const std::string& s) {
    // Accepts "#RRGGBB;#RRGGBB" or a JSON array ["#..","#.."].
    std::vector<uint32_t> out;
    size_t i = 0;
    while ((i = s.find('#', i)) != std::string::npos) {
        size_t j = i + 1;
        while (j < s.size() && std::isxdigit((unsigned char)s[j])) ++j;
        if (j - i == 7 || j - i == 9) out.push_back(parseColor(std::string_view(s).substr(i, j - i)));
        i = j;
    }
    return out;
}

SlicerMeta parseSlicerMeta(const ZipReader& zip) {
    SlicerMeta meta;
    // Bambu Studio / Orca.
    std::string bambu = readEntry(zip, "Metadata/model_settings.config");
    // PrusaSlicer.
    std::string prusa = readEntry(zip, "Metadata/Slic3r_PE_model.config");
    for (int pass = 0; pass < 2; ++pass) {
        const std::string& text = pass == 0 ? bambu : prusa;
        if (text.empty()) continue;
        XmlScanner sc(text.data(), text.data() + text.size());
        XmlTag t;
        std::string_view v;
        SlicerMeta::ObjMeta* obj = nullptr;
        SlicerMeta::PartMeta* part = nullptr;
        SlicerMeta::VolumeMeta* vol = nullptr;
        bool inPlate = false;
        while (sc.next(t)) {
            if (t.closing) {
                if (t.name == "object") obj = nullptr;
                else if (t.name == "part") part = nullptr;
                else if (t.name == "volume") vol = nullptr;
                else if (t.name == "plate") inPlate = false;
                continue;
            }
            if (t.name == "plate") { inPlate = !t.selfClosing; continue; }
            if (inPlate) continue;
            if (t.name == "object") {
                uint32_t id = xmlAttr(t.attrs, "id", v) ? svToU32(v) : 0;
                obj = &meta.objects[id];
                if (t.selfClosing) obj = nullptr;
            } else if (t.name == "part" && obj) {
                uint32_t id = xmlAttr(t.attrs, "id", v) ? svToU32(v) : 0;
                part = &obj->parts[id];
                if (t.selfClosing) part = nullptr;
            } else if (t.name == "volume" && obj) {
                obj->volumes.emplace_back();
                vol = &obj->volumes.back();
                if (xmlAttr(t.attrs, "firstid", v)) vol->first = svToU32(v);
                if (xmlAttr(t.attrs, "lastid", v)) vol->last = svToU32(v);
                if (t.selfClosing) vol = nullptr;
            } else if (t.name == "metadata") {
                std::string_view key, value;
                if (!xmlAttr(t.attrs, "key", key) || !xmlAttr(t.attrs, "value", value)) continue;
                if (key == "name") {
                    std::string s = xmlUnescape(value);
                    if (vol) vol->name = s; else if (part) part->name = s; else if (obj) obj->name = s;
                } else if (key == "extruder") {
                    int e = (int)svToU32(value);
                    if (vol) vol->extruder = e; else if (part) part->extruder = e; else if (obj) obj->extruder = e;
                }
            }
        }
    }
    // Extruder / filament colours.
    std::string proj = readEntry(zip, "Metadata/project_settings.config");
    if (!proj.empty()) {
        size_t k = proj.find("\"filament_colour\"");
        if (k != std::string::npos) {
            size_t e = proj.find(']', k);
            meta.extruderColors = parseColorList(proj.substr(k, e == std::string::npos ? std::string::npos : e - k));
        }
    }
    if (meta.extruderColors.empty()) {
        std::string cfg = readEntry(zip, "Metadata/Slic3r_PE.config");
        for (const char* key : {"; extruder_colour = ", "; filament_colour = "}) {
            size_t k = cfg.find(key);
            if (k == std::string::npos) continue;
            size_t e = cfg.find('\n', k);
            auto cols = parseColorList(cfg.substr(k, e == std::string::npos ? std::string::npos : e - k));
            if (!cols.empty()) { meta.extruderColors = cols; break; }
        }
    }
    return meta;
}

uint32_t extruderColor(const SlicerMeta& meta, int extruder) {
    static const uint32_t fallback[] = {packRGBA(0.85f, 0.85f, 0.85f), packRGBA(0.95f, 0.55f, 0.15f),
                                        packRGBA(0.2f, 0.45f, 0.85f),  packRGBA(0.9f, 0.2f, 0.25f),
                                        packRGBA(0.2f, 0.7f, 0.35f),   packRGBA(0.15f, 0.15f, 0.15f),
                                        packRGBA(0.95f, 0.85f, 0.2f),  packRGBA(0.6f, 0.35f, 0.75f)};
    if (extruder <= 0) extruder = 1;
    if ((size_t)extruder <= meta.extruderColors.size()) return meta.extruderColors[(size_t)extruder - 1] | 0xFF000000u;
    return fallback[(size_t)(extruder - 1) % 8];
}

}  // namespace

RawModel load3MF(const uint8_t* data, size_t size, Progress progress) {
    RawModel rm;
    rm.format = "3MF";
    ZipReader zip;
    if (!zip.open(data, size)) throw std::runtime_error("The 3MF package is damaged");

    // Root model part from the package relationships.
    std::string rootPath = "3D/3dmodel.model";
    {
        std::string rels = readEntry(zip, "_rels/.rels");
        XmlScanner sc(rels.data(), rels.data() + rels.size());
        XmlTag t;
        while (sc.next(t)) {
            if (t.closing || t.name != "Relationship") continue;
            std::string_view type, target;
            if (xmlAttr(t.attrs, "Type", type) && type.find("3dmodel") != std::string_view::npos &&
                xmlAttr(t.attrs, "Target", target)) {
                rootPath = normalizeZipPath(std::string(target));
                break;
            }
        }
        if (!zip.find(rootPath)) {
            for (const auto& e : zip.entries())
                if (e.name.size() > 6 && e.name.compare(e.name.size() - 6, 6, ".model") == 0) { rootPath = e.name; break; }
        }
    }

    std::map<std::string, ModelFile> files;
    std::function<ModelFile*(const std::string&)> getFile = [&](const std::string& path) -> ModelFile* {
        std::string key = normalizeZipPath(path);
        auto it = files.find(key);
        if (it != files.end()) return &it->second;
        const ZipReader::Entry* e = zip.find(key);
        if (!e) return nullptr;
        std::vector<uint8_t> buf;
        if (!zip.extract(*e, buf)) throw std::runtime_error("Could not decompress " + key);
        ModelFile& mf = files[key];
        parseModelXml(reinterpret_cast<const char*>(buf.data()), buf.size(), mf,
                      progress.sub(files.size() == 1 ? 0.f : 0.5f, 0.9f));
        return &mf;
    };
    ModelFile* root = getFile(rootPath);
    if (!root) throw std::runtime_error("The 3MF package has no 3D model");
    const float unit = root->unitScale;
    SlicerMeta meta = parseSlicerMeta(zip);
    const bool hasSlicerMeta = !meta.objects.empty();

    auto resolveProp = [&](const ModelFile& mf, uint32_t pid, uint32_t idx) -> uint32_t {
        auto g = mf.groups.find(pid);
        if (g == mf.groups.end() || idx >= g->second.size()) return 0;
        return g->second[idx];
    };

    // Emits a mesh object (with transform) as one or more parts.
    auto emitMesh = [&](const ModelFile& mf, const Object3mf& o, const Xform& xf, const std::string& name,
                        int extruder, const SlicerMeta::ObjMeta* objMeta) {
        if (o.tris.empty() || o.verts.empty()) return;
        const size_t nv = o.verts.size() / 3, nt = o.tris.size() / 3;
        uint32_t objColor = o.pid != UINT32_MAX ? resolveProp(mf, o.pid, o.pindex) : 0;
        // Prusa volumes split one mesh into several named parts.
        struct Range { uint32_t first, last; std::string name; int extruder; };
        std::vector<Range> ranges;
        if (objMeta && !objMeta->volumes.empty()) {
            for (const auto& vm : objMeta->volumes)
                ranges.push_back({vm.first, std::min<uint32_t>(vm.last, (uint32_t)nt - 1), vm.name,
                                  vm.extruder ? vm.extruder : extruder});
        } else {
            ranges.push_back({0, (uint32_t)nt - 1, name, extruder});
        }
        for (const Range& r : ranges) {
            if (r.first > r.last || r.first >= nt) continue;
            RawPart part;
            part.name = r.name.empty() ? name : r.name;
            const bool slicerColor = hasSlicerMeta && (r.extruder > 0 || !meta.extruderColors.empty());
            part.color = objColor ? objColor : (slicerColor ? extruderColor(meta, r.extruder) : 0);
            std::vector<uint32_t> map(nv, UINT32_MAX);
            bool anyFace = false;
            std::vector<uint32_t> faceColors;
            for (uint32_t t = r.first; t <= r.last; ++t) {
                uint32_t idx[3];
                bool ok = true;
                for (int c = 0; c < 3; ++c) {
                    uint32_t vi = o.tris[3 * t + c];
                    if (vi >= nv) { ok = false; break; }
                    if (map[vi] == UINT32_MAX) {
                        map[vi] = (uint32_t)(part.positions.size() / 3);
                        Vec3 p = xf.apply(Vec3(o.verts[3 * vi], o.verts[3 * vi + 1], o.verts[3 * vi + 2])) * unit;
                        part.positions.insert(part.positions.end(), {p.x, p.y, p.z});
                    }
                    idx[c] = map[vi];
                }
                if (!ok) continue;
                part.indices.insert(part.indices.end(), {idx[0], idx[1], idx[2]});
                uint32_t fc = part.color;
                if (o.anyTriProps && t < o.triPid.size()) {
                    uint32_t pid = o.triPid[t] != UINT32_MAX ? o.triPid[t] : o.pid;
                    uint32_t p1 = o.triP1[t] != UINT32_MAX ? o.triP1[t] : o.pindex;
                    if (pid != UINT32_MAX) {
                        uint32_t c = resolveProp(mf, pid, p1);
                        if (c) { fc = c; anyFace = true; }
                    }
                }
                if (o.anyPaint && t < o.triPaint.size() && o.triPaint[t] > 0) {
                    fc = extruderColor(meta, o.triPaint[t]);
                    anyFace = true;
                }
                faceColors.push_back(fc ? fc : 0xFFCCCCCCu);
            }
            if (anyFace) {
                // Collapse to a part colour if every face agrees.
                bool uniform = true;
                for (size_t i = 1; i < faceColors.size() && uniform; ++i) uniform = faceColors[i] == faceColors[0];
                if (uniform && !faceColors.empty()) part.color = faceColors[0];
                else part.faceColors = std::move(faceColors);
            }
            part.welded = false;
            rm.parts.push_back(std::move(part));
        }
    };

    // Recursively walks components.
    std::function<void(const std::string&, uint32_t, const Xform&, const std::string&, int, int, uint32_t)> walk =
        [&](const std::string& path, uint32_t objectId, const Xform& xf, const std::string& inheritedName,
            int extruder, int depth, uint32_t rootObjectId) {
            if (depth > 16) return;
            ModelFile* mf = path.empty() ? root : getFile(path);
            if (!mf) return;
            auto it = mf->objects.find(objectId);
            if (it == mf->objects.end()) return;
            const Object3mf& o = it->second;
            if (o.type == "support" || o.type == "surface" || o.type == "solidsupport") return;
            const SlicerMeta::ObjMeta* om = nullptr;
            if (depth == 0) {
                auto mi = meta.objects.find(objectId);
                if (mi != meta.objects.end()) om = &mi->second;
            }
            std::string name = !o.name.empty() ? o.name : inheritedName;
            int ext = extruder;
            if (om) {
                if (!om->name.empty()) name = om->name;
                if (om->extruder) ext = om->extruder;
            }
            if (!o.verts.empty()) emitMesh(*mf, o, xf, name, ext, om);
            const SlicerMeta::ObjMeta* rootMeta = nullptr;
            {
                auto mi = meta.objects.find(rootObjectId);
                if (mi != meta.objects.end()) rootMeta = &mi->second;
            }
            int ci = 0;
            for (const auto& c : o.comps) {
                ++ci;
                std::string cname = name;
                int cext = ext;
                if (rootMeta) {
                    auto pi = rootMeta->parts.find(c.objectId);
                    if (pi != rootMeta->parts.end()) {
                        if (!pi->second.name.empty()) cname = pi->second.name;
                        if (pi->second.extruder) cext = pi->second.extruder;
                    } else if (o.comps.size() > 1) {
                        cname = name + " " + std::to_string(ci);
                    }
                }
                walk(c.path.empty() ? path : c.path, c.objectId, c.xf.then(xf), cname, cext, depth + 1, rootObjectId);
            }
        };

    if (root->items.empty()) {
        for (auto& kv : root->objects)
            if (kv.second.type.empty() || kv.second.type == "model") walk("", kv.first, Xform(), kv.second.name, 0, 0, kv.first);
    } else {
        for (const auto& item : root->items) walk(item.path, item.objectId, item.xf, std::string(), 0, 0, item.objectId);
    }
    if (rm.parts.empty()) throw std::runtime_error("The 3MF file contains no printable meshes");
    progress.report(1.f);
    return rm;
}

}  // namespace fsv
