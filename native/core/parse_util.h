// Fast, locale-independent text scanning helpers for the ASCII model formats.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace fsv {

inline bool isSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\v'; }
inline bool isLineSpace(char c) { return c == ' ' || c == '\t' || c == '\r'; }
inline bool isDigit(char c) { return c >= '0' && c <= '9'; }

struct TextCursor {
    const char* p;
    const char* end;

    bool atEnd() const { return p >= end; }
    void skipSpace() { while (p < end && isSpace(*p)) ++p; }
    void skipLineSpace() { while (p < end && isLineSpace(*p)) ++p; }
    void skipLine() {
        while (p < end && *p != '\n') ++p;
        if (p < end) ++p;
    }
    // Returns pointer/len of next whitespace-delimited token on the current line.
    bool token(const char*& s, size_t& n) {
        skipLineSpace();
        s = p;
        while (p < end && !isSpace(*p)) ++p;
        n = (size_t)(p - s);
        return n > 0;
    }
    bool atLineEnd() {
        skipLineSpace();
        return p >= end || *p == '\n';
    }
};

// Powers of ten for fast float conversion.
inline double pow10i(int e) {
    static const double kPos[] = {1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,  1e8,  1e9,  1e10,
                                  1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    if (e >= 0) {
        if (e <= 22) return kPos[e];
        double r = 1e22;
        e -= 22;
        while (e > 0) { r *= 10.0; --e; }
        return r;
    }
    e = -e;
    if (e <= 22) return 1.0 / kPos[e];
    double r = 1e-22;
    e -= 22;
    while (e > 0) { r *= 0.1; --e; }
    return r;
}

// Parses a decimal floating point number starting at p (leading spaces/tabs skipped).
// On success advances p and returns true.
inline bool parseFloat(const char*& p, const char* end, float& out) {
    while (p < end && (*p == ' ' || *p == '\t')) ++p;
    const char* s = p;
    bool neg = false;
    if (s < end && (*s == '-' || *s == '+')) { neg = (*s == '-'); ++s; }
    uint64_t mant = 0;
    int digits = 0, exp10 = 0;
    bool any = false;
    while (s < end && isDigit(*s)) {
        if (digits < 18) { mant = mant * 10 + (uint64_t)(*s - '0'); ++digits; }
        else ++exp10;
        ++s;
        any = true;
    }
    if (s < end && *s == '.') {
        ++s;
        while (s < end && isDigit(*s)) {
            if (digits < 18) { mant = mant * 10 + (uint64_t)(*s - '0'); ++digits; --exp10; }
            ++s;
            any = true;
        }
    }
    if (!any) {
        // nan / inf are treated as parse failures (caller drops the element).
        return false;
    }
    if (s < end && (*s == 'e' || *s == 'E')) {
        const char* e = s + 1;
        bool eneg = false;
        if (e < end && (*e == '-' || *e == '+')) { eneg = (*e == '-'); ++e; }
        if (e < end && isDigit(*e)) {
            int ev = 0;
            while (e < end && isDigit(*e)) { if (ev < 10000) ev = ev * 10 + (*e - '0'); ++e; }
            exp10 += eneg ? -ev : ev;
            s = e;
        }
    }
    double v = (double)mant;
    if (exp10 != 0) v *= pow10i(exp10);
    out = (float)(neg ? -v : v);
    p = s;
    return true;
}

inline bool parseInt(const char*& p, const char* end, long long& out) {
    while (p < end && (*p == ' ' || *p == '\t')) ++p;
    const char* s = p;
    bool neg = false;
    if (s < end && (*s == '-' || *s == '+')) { neg = (*s == '-'); ++s; }
    if (s >= end || !isDigit(*s)) return false;
    long long v = 0;
    while (s < end && isDigit(*s)) { v = v * 10 + (*s - '0'); ++s; }
    out = neg ? -v : v;
    p = s;
    return true;
}

inline bool startsWithNoCase(const char* s, size_t n, const char* prefix) {
    size_t k = std::strlen(prefix);
    if (n < k) return false;
    for (size_t i = 0; i < k; ++i) {
        char a = s[i], b = prefix[i];
        if (a >= 'A' && a <= 'Z') a = (char)(a - 'A' + 'a');
        if (b >= 'A' && b <= 'Z') b = (char)(b - 'A' + 'a');
        if (a != b) return false;
    }
    return true;
}

inline bool tokenIs(const char* s, size_t n, const char* word) {
    size_t k = std::strlen(word);
    return n == k && std::memcmp(s, word, k) == 0;
}

}  // namespace fsv
