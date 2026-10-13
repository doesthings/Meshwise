#include "loaders.h"

#include <cstring>

#include "parse_util.h"
#include "zip.h"

namespace fsv {

Format formatFromExtension(const std::string& extIn) {
    std::string ext;
    for (char c : extIn) ext += (char)((c >= 'A' && c <= 'Z') ? c - 'A' + 'a' : c);
    if (ext == "stl") return Format::STL;
    if (ext == "obj") return Format::OBJ;
    if (ext == "ply") return Format::PLY;
    if (ext == "off") return Format::OFF;
    if (ext == "3mf") return Format::ThreeMF;
    if (ext == "fsm") return Format::FSM;
    return Format::Unknown;
}

const char* formatName(Format f) {
    switch (f) {
        case Format::STL: return "STL";
        case Format::OBJ: return "OBJ";
        case Format::PLY: return "PLY";
        case Format::OFF: return "OFF";
        case Format::ThreeMF: return "3MF";
        case Format::FSM: return "FSM";
        default: return "?";
    }
}

Format sniffFormat(const uint8_t* d, size_t n) {
    if (n >= 4 && std::memcmp(d, "FSM1", 4) == 0) return Format::FSM;
    if (ZipReader::looksLikeZip(d, n)) return Format::ThreeMF;
    if (n >= 3 && std::memcmp(d, "ply", 3) == 0) return Format::PLY;
    if (n >= 84) {
        uint32_t tc;
        std::memcpy(&tc, d + 80, 4);
        if (84 + 50ull * tc == n) return Format::STL;
    }
    size_t i = 0;
    while (i < n && isSpace((char)d[i])) ++i;
    const char* s = reinterpret_cast<const char*>(d + i);
    size_t left = n - i;
    if (startsWithNoCase(s, left, "solid")) return Format::STL;
    if (startsWithNoCase(s, left, "off") || startsWithNoCase(s, left, "coff")) return Format::OFF;
    // OBJ: look for "v " / "f " lines near the start.
    size_t probe = std::min<size_t>(n, 8192);
    for (size_t k = 0; k + 2 < probe; ++k) {
        if ((k == 0 || d[k - 1] == '\n') && (d[k] == 'v' || d[k] == 'f') && d[k + 1] == ' ') return Format::OBJ;
    }
    return n >= 84 ? Format::STL : Format::Unknown;
}

std::unique_ptr<Model> loadModel(const MappedFile& file, const LoadRequest& req, ProgressFn progressFn) {
    Progress progress(std::move(progressFn));
    const uint8_t* d = file.data();
    const size_t n = file.size();
    if (n == 0) throw std::runtime_error("The file is empty");

    Format fmt = formatFromExtension(req.ext);
    Format sniffed = sniffFormat(d, n);
    if (fmt == Format::Unknown) fmt = sniffed;
    // Trust content over a wrong extension for unambiguous signatures.
    if (sniffed == Format::FSM || (sniffed == Format::PLY && fmt != Format::PLY)) fmt = sniffed;
    if (fmt == Format::ThreeMF && !ZipReader::looksLikeZip(d, n)) fmt = sniffed;
    if (fmt == Format::Unknown) throw std::runtime_error("Unrecognised file format");

    Progress parseProg = progress.sub(0.f, 0.6f);
    RawModel raw;
    switch (fmt) {
        case Format::STL: raw = loadSTL(d, n, parseProg); break;
        case Format::OBJ: raw = loadOBJ(d, n, req.baseDir, parseProg); break;
        case Format::PLY: raw = loadPLY(d, n, parseProg); break;
        case Format::OFF: raw = loadOFF(d, n, parseProg); break;
        case Format::ThreeMF: raw = load3MF(d, n, parseProg); break;
        case Format::FSM: raw = loadFSM(d, n, parseProg); break;
        default: throw std::runtime_error("Unsupported format");
    }
    auto model = buildModel(std::move(raw), req.process, progress.sub(0.6f, 1.f));
    model->name = req.name;
    model->fileSize = n;
    // A single unnamed body takes the file name.
    if (model->parts.size() == 1 && (model->parts[0].name == "Body" || model->parts[0].name.empty())) {
        std::string base = req.name;
        size_t dot = base.find_last_of('.');
        if (dot != std::string::npos && dot > 0) base = base.substr(0, dot);
        if (!base.empty()) model->parts[0].name = base;
    }
    return model;
}

}  // namespace fsv
