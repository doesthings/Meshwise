// OFF / COFF loader.
#include "loaders.h"
#include "parse_util.h"

namespace fsv {

RawModel loadOFF(const uint8_t* data, size_t size, Progress progress) {
    RawModel rm;
    rm.format = "OFF";
    TextCursor cur{reinterpret_cast<const char*>(data), reinterpret_cast<const char*>(data + size)};
    auto skipComments = [&]() {
        for (;;) {
            cur.skipSpace();
            if (cur.p < cur.end && *cur.p == '#') cur.skipLine();
            else break;
        }
    };
    skipComments();
    const char* tok;
    size_t len;
    if (!cur.token(tok, len) || len < 3 || std::string(tok + len - 3, 3) != "OFF")
        throw std::runtime_error("Not an OFF file");
    bool hasVColor = std::string(tok, len).find('C') != std::string::npos;
    long long nv = 0, nf = 0, ne = 0;
    skipComments();
    if (!parseInt(cur.p, cur.end, nv) || !parseInt(cur.p, cur.end, nf)) throw std::runtime_error("Invalid OFF header");
    parseInt(cur.p, cur.end, ne);
    if (nv <= 0 || nf <= 0) throw std::runtime_error("The OFF file is empty");

    RawPart part;
    part.positions.resize((size_t)nv * 3);
    if (hasVColor) part.vertexColors.resize((size_t)nv);
    for (long long i = 0; i < nv; ++i) {
        skipComments();
        float x = 0, y = 0, z = 0;
        parseFloat(cur.p, cur.end, x);
        parseFloat(cur.p, cur.end, y);
        parseFloat(cur.p, cur.end, z);
        part.positions[(size_t)i * 3] = x;
        part.positions[(size_t)i * 3 + 1] = y;
        part.positions[(size_t)i * 3 + 2] = z;
        if (hasVColor) {
            float c[4] = {1, 1, 1, 1};
            int n = 0;
            while (n < 4 && !cur.atLineEnd() && parseFloat(cur.p, cur.end, c[n])) ++n;
            bool bytes = c[0] > 1 || c[1] > 1 || c[2] > 1;
            float s = bytes ? 1.f / 255.f : 1.f;
            part.vertexColors[(size_t)i] = packRGBA(c[0] * s, c[1] * s, c[2] * s, n >= 4 ? c[3] * s : 1.f);
        }
        cur.skipLine();
        if ((i & 0xFFFF) == 0) progress.report(0.5f * (float)i / (float)nv);
    }
    std::vector<long long> poly;
    bool anyFaceColor = false;
    std::vector<uint32_t> faceColors;
    for (long long f = 0; f < nf; ++f) {
        skipComments();
        long long n = 0;
        if (!parseInt(cur.p, cur.end, n)) break;
        poly.resize((size_t)std::max(0LL, n));
        for (long long k = 0; k < n; ++k) parseInt(cur.p, cur.end, poly[(size_t)k]);
        float c[4] = {1, 1, 1, 1};
        int nc = 0;
        while (nc < 4 && !cur.atLineEnd() && parseFloat(cur.p, cur.end, c[nc])) ++nc;
        uint32_t color = 0xFFFFFFFFu;
        if (nc >= 3) {
            bool bytes = c[0] > 1 || c[1] > 1 || c[2] > 1;
            float s = bytes ? 1.f / 255.f : 1.f;
            color = packRGBA(c[0] * s, c[1] * s, c[2] * s);
            anyFaceColor = true;
        }
        for (long long k = 2; k < n; ++k) {
            long long a = poly[0], b = poly[(size_t)k - 1], c2 = poly[(size_t)k];
            if (a < 0 || b < 0 || c2 < 0 || a >= nv || b >= nv || c2 >= nv) continue;
            part.indices.insert(part.indices.end(), {(uint32_t)a, (uint32_t)b, (uint32_t)c2});
            faceColors.push_back(color);
        }
        cur.skipLine();
        if ((f & 0xFFFF) == 0) progress.report(0.5f + 0.5f * (float)f / (float)nf);
    }
    if (anyFaceColor) {
        part.faceColors = std::move(faceColors);
        part.vertexColors.clear();
    }
    rm.parts.push_back(std::move(part));
    return rm;
}

}  // namespace fsv
