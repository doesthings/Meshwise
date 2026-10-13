// Wavefront OBJ loader with object/group/material based part splitting and MTL colours.
#include <fstream>
#include <sstream>
#include <unordered_map>

#include "loaders.h"
#include "parse_util.h"

namespace fsv {

namespace {

struct Material {
    uint32_t color = 0;
};

std::unordered_map<std::string, Material> loadMtl(const std::string& path) {
    std::unordered_map<std::string, Material> mats;
    std::ifstream in(path, std::ios::binary);
    if (!in) return mats;
    std::stringstream ss;
    ss << in.rdbuf();
    std::string text = ss.str();
    TextCursor cur{text.data(), text.data() + text.size()};
    Material* current = nullptr;
    float alpha = 1.f;
    const char* tok;
    size_t len;
    while (!cur.atEnd()) {
        cur.skipSpace();
        if (!cur.token(tok, len)) { cur.skipLine(); continue; }
        if (tokenIs(tok, len, "newmtl")) {
            cur.skipLineSpace();
            const char* s = cur.p;
            while (cur.p < cur.end && *cur.p != '\n' && *cur.p != '\r') ++cur.p;
            std::string name(s, (size_t)(cur.p - s));
            while (!name.empty() && isSpace(name.back())) name.pop_back();
            current = &mats[name];
            alpha = 1.f;
        } else if (current && tokenIs(tok, len, "Kd")) {
            float r, g, b;
            if (parseFloat(cur.p, cur.end, r) && parseFloat(cur.p, cur.end, g) && parseFloat(cur.p, cur.end, b))
                current->color = packRGBA(r, g, b, alpha);
        } else if (current && tokenIs(tok, len, "d")) {
            float a;
            if (parseFloat(cur.p, cur.end, a)) alpha = a;
        }
        cur.skipLine();
    }
    return mats;
}

std::string restOfLine(TextCursor& cur) {
    cur.skipLineSpace();
    const char* s = cur.p;
    while (cur.p < cur.end && *cur.p != '\n' && *cur.p != '\r' && *cur.p != '#') ++cur.p;
    std::string r(s, (size_t)(cur.p - s));
    while (!r.empty() && isSpace(r.back())) r.pop_back();
    return r;
}

uint32_t internName(std::unordered_map<std::string, uint32_t>& map, std::vector<std::string>& names,
                    const std::string& n) {
    auto it = map.find(n);
    if (it != map.end()) return it->second;
    uint32_t id = (uint32_t)names.size();
    names.push_back(n);
    map.emplace(n, id);
    return id;
}

}  // namespace

RawModel loadOBJ(const uint8_t* data, size_t size, const std::string& baseDir, Progress progress) {
    RawModel rm;
    rm.format = "OBJ";
    TextCursor cur{reinterpret_cast<const char*>(data), reinterpret_cast<const char*>(data + size)};

    std::vector<float> V;
    std::vector<uint32_t> VC;  // vertex colours (optional extension "v x y z r g b")
    bool anyVC = false;
    std::vector<uint32_t> F;   // triangles (global vertex indices)
    struct FaceTag { uint32_t o, g, m; };
    std::vector<FaceTag> tags;

    std::unordered_map<std::string, uint32_t> oMap, gMap, mMap;
    std::vector<std::string> oNames, gNames, mNames;
    uint32_t curO = internName(oMap, oNames, ""), curG = internName(gMap, gNames, ""), curM = internName(mMap, mNames, "");
    std::vector<std::string> mtlFiles;

    std::vector<long long> poly;
    const char* tok;
    size_t len;
    size_t lines = 0;
    while (!cur.atEnd()) {
        cur.skipSpace();
        if (cur.atEnd()) break;
        char c0 = *cur.p;
        if (c0 == '#') { cur.skipLine(); continue; }
        if (!cur.token(tok, len)) { cur.skipLine(); continue; }
        if (len == 1 && tok[0] == 'v') {
            float x, y, z;
            if (parseFloat(cur.p, cur.end, x) && parseFloat(cur.p, cur.end, y) && parseFloat(cur.p, cur.end, z)) {
                V.insert(V.end(), {x, y, z});
                float r, g, b;
                const char* save = cur.p;
                if (parseFloat(cur.p, cur.end, r) && parseFloat(cur.p, cur.end, g) && parseFloat(cur.p, cur.end, b)) {
                    // Some writers emit 0..255; normalise.
                    if (r > 1.f || g > 1.f || b > 1.f) { r /= 255.f; g /= 255.f; b /= 255.f; }
                    if (VC.size() < V.size() / 3 - 1) VC.resize(V.size() / 3 - 1, 0xFFFFFFFFu);
                    VC.push_back(packRGBA(r, g, b));
                    anyVC = true;
                } else {
                    cur.p = save;
                    if (anyVC) VC.push_back(0xFFFFFFFFu);
                }
            } else {
                V.insert(V.end(), {0.f, 0.f, 0.f});  // keep numbering stable
                if (anyVC) VC.push_back(0xFFFFFFFFu);
            }
        } else if (len == 1 && tok[0] == 'f') {
            poly.clear();
            const long long nv = (long long)(V.size() / 3);
            for (;;) {
                cur.skipLineSpace();
                if (cur.p >= cur.end || *cur.p == '\n' || *cur.p == '#') break;
                long long idx;
                const char* before = cur.p;
                if (!parseInt(cur.p, cur.end, idx)) {
                    // Skip unknown token.
                    while (cur.p < cur.end && !isSpace(*cur.p)) ++cur.p;
                    if (cur.p == before) break;
                    continue;
                }
                // Skip /vt/vn parts.
                while (cur.p < cur.end && !isSpace(*cur.p)) ++cur.p;
                long long r = idx > 0 ? idx - 1 : nv + idx;
                poly.push_back(r);
            }
            for (size_t i = 2; i < poly.size(); ++i) {
                long long a = poly[0], b = poly[i - 1], c = poly[i];
                if (a < 0 || b < 0 || c < 0 || a >= nv || b >= nv || c >= nv) continue;
                F.insert(F.end(), {(uint32_t)a, (uint32_t)b, (uint32_t)c});
                tags.push_back({curO, curG, curM});
            }
        } else if (len == 1 && tok[0] == 'o') {
            curO = internName(oMap, oNames, restOfLine(cur));
        } else if (len == 1 && tok[0] == 'g') {
            curG = internName(gMap, gNames, restOfLine(cur));
        } else if (tokenIs(tok, len, "usemtl")) {
            curM = internName(mMap, mNames, restOfLine(cur));
        } else if (tokenIs(tok, len, "mtllib")) {
            mtlFiles.push_back(restOfLine(cur));
        }
        cur.skipLine();
        if ((++lines & 0xFFFF) == 0) progress.report(0.8f * (float)(cur.p - reinterpret_cast<const char*>(data)) / (float)size);
    }
    if (F.empty()) throw std::runtime_error("The OBJ file contains no faces");

    std::unordered_map<std::string, Material> mats;
    if (!baseDir.empty()) {
        for (const std::string& f : mtlFiles) {
            auto m = loadMtl(baseDir + "/" + f);
            mats.insert(m.begin(), m.end());
        }
    }
    std::vector<uint32_t> matColor(mNames.size(), 0);
    for (size_t i = 0; i < mNames.size(); ++i) {
        auto it = mats.find(mNames[i]);
        if (it != mats.end()) matColor[i] = it->second.color;
    }

    // Choose the grouping key: objects, then groups, then materials.
    auto distinctUsed = [&](auto key) {
        std::vector<uint8_t> seen;
        size_t count = 0;
        for (const FaceTag& t : tags) {
            uint32_t k = key(t);
            if (k >= seen.size()) seen.resize(k + 1, 0);
            if (!seen[k]) { seen[k] = 1; ++count; }
        }
        return count;
    };
    int mode = 0;  // 0 single, 1 object, 2 group, 3 material
    if (distinctUsed([](const FaceTag& t) { return t.o; }) > 1) mode = 1;
    else if (distinctUsed([](const FaceTag& t) { return t.g; }) > 1) mode = 2;
    else if (distinctUsed([](const FaceTag& t) { return t.m; }) > 1) mode = 3;
    auto keyOf = [&](const FaceTag& t) -> uint32_t {
        switch (mode) {
            case 1: return t.o;
            case 2: return t.g;
            case 3: return t.m;
            default: return 0;
        }
    };
    auto nameOf = [&](uint32_t k) -> std::string {
        switch (mode) {
            case 1: return oNames[k];
            case 2: return gNames[k];
            case 3: return mNames[k];
            default: return oNames.size() > 1 ? oNames[1] : (gNames.size() > 1 ? gNames[1] : std::string());
        }
    };

    std::unordered_map<uint32_t, size_t> partOf;
    std::vector<uint32_t> localIndex(V.size() / 3, UINT32_MAX);
    std::vector<uint32_t> stamp(V.size() / 3, UINT32_MAX);
    // Collect faces per part.
    std::vector<std::vector<uint32_t>> facesOfPart;
    std::vector<uint32_t> partKeys;
    for (size_t f = 0; f < tags.size(); ++f) {
        uint32_t k = keyOf(tags[f]);
        auto it = partOf.find(k);
        size_t pi;
        if (it == partOf.end()) {
            pi = facesOfPart.size();
            partOf.emplace(k, pi);
            facesOfPart.emplace_back();
            partKeys.push_back(k);
        } else {
            pi = it->second;
        }
        facesOfPart[pi].push_back((uint32_t)f);
    }

    for (size_t pi = 0; pi < facesOfPart.size(); ++pi) {
        RawPart part;
        part.name = nameOf(partKeys[pi]);
        const auto& faces = facesOfPart[pi];
        // Part colour: material colour when the whole part uses one material.
        bool singleMat = true;
        for (uint32_t f : faces) if (tags[f].m != tags[faces[0]].m) { singleMat = false; break; }
        if (singleMat) part.color = matColor[tags[faces[0]].m];
        else {
            part.faceColors.reserve(faces.size());
            for (uint32_t f : faces) {
                uint32_t c = matColor[tags[f].m];
                part.faceColors.push_back(c ? c : 0xFFCCCCCCu);
            }
        }
        part.indices.reserve(faces.size() * 3);
        for (uint32_t f : faces) {
            for (int c = 0; c < 3; ++c) {
                uint32_t g = F[3 * f + c];
                if (stamp[g] != pi) {
                    stamp[g] = (uint32_t)pi;
                    localIndex[g] = (uint32_t)(part.positions.size() / 3);
                    part.positions.insert(part.positions.end(), {V[3 * g], V[3 * g + 1], V[3 * g + 2]});
                    if (anyVC) part.vertexColors.push_back(g < VC.size() ? VC[g] : 0xFFFFFFFFu);
                }
                part.indices.push_back(localIndex[g]);
            }
        }
        rm.parts.push_back(std::move(part));
        progress.report(0.8f + 0.2f * (float)(pi + 1) / (float)facesOfPart.size());
    }
    return rm;
}

}  // namespace fsv
