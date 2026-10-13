// Tiny forward-only XML tag scanner, sufficient for 3MF model files and slicer configs.
#pragma once

#include <cstring>
#include <string>
#include <string_view>

namespace fsv {

struct XmlTag {
    std::string_view name;   // local name (namespace prefix stripped)
    std::string_view attrs;  // raw attribute text
    bool closing = false;    // </name>
    bool selfClosing = false;
};

class XmlScanner {
public:
    XmlScanner(const char* begin, const char* end) : p_(begin), end_(end) {}

    const char* position() const { return p_; }

    bool next(XmlTag& t) {
        for (;;) {
            const char* lt = static_cast<const char*>(std::memchr(p_, '<', (size_t)(end_ - p_)));
            if (!lt) { p_ = end_; return false; }
            p_ = lt + 1;
            if (p_ >= end_) return false;
            if (*p_ == '!') {
                if (end_ - p_ >= 3 && p_[1] == '-' && p_[2] == '-') {
                    const char* e = find("-->");
                    p_ = e ? e + 3 : end_;
                } else if (end_ - p_ >= 8 && std::memcmp(p_, "![CDATA[", 8) == 0) {
                    const char* e = find("]]>");
                    p_ = e ? e + 3 : end_;
                } else {
                    skipTo('>');
                }
                continue;
            }
            if (*p_ == '?') {
                const char* e = find("?>");
                p_ = e ? e + 2 : end_;
                continue;
            }
            t.closing = false;
            if (*p_ == '/') { t.closing = true; ++p_; }
            const char* ns = p_;
            while (p_ < end_ && !isNameEnd(*p_)) ++p_;
            std::string_view full(ns, (size_t)(p_ - ns));
            size_t colon = full.find(':');
            t.name = colon == std::string_view::npos ? full : full.substr(colon + 1);
            const char* as = p_;
            char quote = 0;
            while (p_ < end_) {
                char c = *p_;
                if (quote) { if (c == quote) quote = 0; }
                else if (c == '"' || c == '\'') quote = c;
                else if (c == '>') break;
                ++p_;
            }
            const char* ae = p_;
            t.selfClosing = ae > as && ae[-1] == '/';
            if (t.selfClosing) --ae;
            t.attrs = std::string_view(as, (size_t)(ae - as));
            if (p_ < end_) ++p_;
            return true;
        }
    }

    // Text content up to the next '<'.
    std::string_view text() {
        const char* s = p_;
        const char* lt = static_cast<const char*>(std::memchr(p_, '<', (size_t)(end_ - p_)));
        return std::string_view(s, (size_t)((lt ? lt : end_) - s));
    }

private:
    static bool isNameEnd(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/' || c == '>'; }
    const char* find(const char* needle) {
        size_t n = std::strlen(needle);
        for (const char* q = p_; q + n <= end_; ++q)
            if (std::memcmp(q, needle, n) == 0) return q;
        return nullptr;
    }
    void skipTo(char c) {
        while (p_ < end_ && *p_ != c) ++p_;
        if (p_ < end_) ++p_;
    }
    const char* p_;
    const char* end_;
};

// Finds attribute `name` (local name match, namespace prefix ignored). Returns false if absent.
inline bool xmlAttr(std::string_view attrs, std::string_view name, std::string_view& value) {
    size_t i = 0, n = attrs.size();
    while (i < n) {
        while (i < n && (attrs[i] == ' ' || attrs[i] == '\t' || attrs[i] == '\r' || attrs[i] == '\n')) ++i;
        size_t ks = i;
        while (i < n && attrs[i] != '=' && attrs[i] != ' ' && attrs[i] != '\t' && attrs[i] != '\r' && attrs[i] != '\n') ++i;
        std::string_view key = attrs.substr(ks, i - ks);
        while (i < n && attrs[i] != '=') ++i;
        if (i >= n) return false;
        ++i;
        while (i < n && attrs[i] != '"' && attrs[i] != '\'') ++i;
        if (i >= n) return false;
        char q = attrs[i++];
        size_t vs = i;
        while (i < n && attrs[i] != q) ++i;
        std::string_view val = attrs.substr(vs, i - vs);
        ++i;
        size_t colon = key.find(':');
        std::string_view local = colon == std::string_view::npos ? key : key.substr(colon + 1);
        if (local == name) { value = val; return true; }
    }
    return false;
}

// Exact (prefix-sensitive) attribute lookup, e.g. "slic3rpe:mmu_segmentation".
inline bool xmlAttrExact(std::string_view attrs, std::string_view name, std::string_view& value) {
    size_t pos = 0;
    while ((pos = attrs.find(name, pos)) != std::string_view::npos) {
        bool startOk = pos == 0 || attrs[pos - 1] == ' ' || attrs[pos - 1] == '\t' || attrs[pos - 1] == '\n' || attrs[pos - 1] == '\r';
        size_t e = pos + name.size();
        if (startOk && e < attrs.size() && attrs[e] == '=') {
            size_t q = e + 1;
            if (q < attrs.size() && (attrs[q] == '"' || attrs[q] == '\'')) {
                char qc = attrs[q];
                size_t ve = attrs.find(qc, q + 1);
                if (ve == std::string_view::npos) return false;
                value = attrs.substr(q + 1, ve - q - 1);
                return true;
            }
        }
        pos = e;
    }
    return false;
}

inline std::string xmlUnescape(std::string_view s) {
    std::string r;
    r.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '&') {
            size_t semi = s.find(';', i);
            if (semi != std::string_view::npos) {
                std::string_view ent = s.substr(i + 1, semi - i - 1);
                if (ent == "amp") { r += '&'; i = semi; continue; }
                if (ent == "lt") { r += '<'; i = semi; continue; }
                if (ent == "gt") { r += '>'; i = semi; continue; }
                if (ent == "quot") { r += '"'; i = semi; continue; }
                if (ent == "apos") { r += '\''; i = semi; continue; }
                if (!ent.empty() && ent[0] == '#') {
                    unsigned long cp = ent.size() > 1 && (ent[1] == 'x' || ent[1] == 'X')
                                           ? std::strtoul(std::string(ent.substr(2)).c_str(), nullptr, 16)
                                           : std::strtoul(std::string(ent.substr(1)).c_str(), nullptr, 10);
                    // UTF-8 encode.
                    if (cp < 0x80) r += (char)cp;
                    else if (cp < 0x800) { r += (char)(0xC0 | (cp >> 6)); r += (char)(0x80 | (cp & 0x3F)); }
                    else if (cp < 0x10000) { r += (char)(0xE0 | (cp >> 12)); r += (char)(0x80 | ((cp >> 6) & 0x3F)); r += (char)(0x80 | (cp & 0x3F)); }
                    else { r += (char)(0xF0 | (cp >> 18)); r += (char)(0x80 | ((cp >> 12) & 0x3F)); r += (char)(0x80 | ((cp >> 6) & 0x3F)); r += (char)(0x80 | (cp & 0x3F)); }
                    i = semi;
                    continue;
                }
            }
        }
        r += s[i];
    }
    return r;
}

inline std::string xmlEscape(const std::string& s) {
    std::string r;
    r.reserve(s.size());
    for (char c : s) {
        switch (c) {
            case '&': r += "&amp;"; break;
            case '<': r += "&lt;"; break;
            case '>': r += "&gt;"; break;
            case '"': r += "&quot;"; break;
            case '\'': r += "&apos;"; break;
            default: r += c;
        }
    }
    return r;
}

}  // namespace fsv
