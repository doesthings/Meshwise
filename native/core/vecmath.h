// Small, dependency-free vector/matrix helpers used by the loaders, the
// analysis code and the renderer. Matrices are column-major (OpenGL layout).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

namespace fsv {

constexpr float kPi = 3.14159265358979323846f;

struct Vec2 {
    float x = 0, y = 0;
};

struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3() = default;
    constexpr Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}
    float& operator[](int i) { return (&x)[i]; }
    float operator[](int i) const { return (&x)[i]; }
    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator-() const { return {-x, -y, -z}; }
    Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
    Vec3 operator/(float s) const { return {x / s, y / s, z / s}; }
    Vec3& operator+=(const Vec3& o) { x += o.x; y += o.y; z += o.z; return *this; }
    Vec3& operator-=(const Vec3& o) { x -= o.x; y -= o.y; z -= o.z; return *this; }
    Vec3& operator*=(float s) { x *= s; y *= s; z *= s; return *this; }
};

inline Vec3 operator*(float s, const Vec3& v) { return v * s; }
inline float dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 cross(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline float length(const Vec3& v) { return std::sqrt(dot(v, v)); }
inline float length2(const Vec3& v) { return dot(v, v); }
inline Vec3 normalize(const Vec3& v) {
    float l = length(v);
    return l > 1e-30f ? v / l : Vec3(0, 0, 1);
}
inline Vec3 vmin(const Vec3& a, const Vec3& b) { return {std::min(a.x, b.x), std::min(a.y, b.y), std::min(a.z, b.z)}; }
inline Vec3 vmax(const Vec3& a, const Vec3& b) { return {std::max(a.x, b.x), std::max(a.y, b.y), std::max(a.z, b.z)}; }
inline Vec3 lerp(const Vec3& a, const Vec3& b, float t) { return a + (b - a) * t; }

struct Vec4 {
    float x = 0, y = 0, z = 0, w = 0;
    Vec4() = default;
    constexpr Vec4(float x_, float y_, float z_, float w_) : x(x_), y(y_), z(z_), w(w_) {}
    Vec4(const Vec3& v, float w_) : x(v.x), y(v.y), z(v.z), w(w_) {}
    Vec3 xyz() const { return {x, y, z}; }
};

struct Box3 {
    Vec3 min{std::numeric_limits<float>::max(), std::numeric_limits<float>::max(), std::numeric_limits<float>::max()};
    Vec3 max{-std::numeric_limits<float>::max(), -std::numeric_limits<float>::max(), -std::numeric_limits<float>::max()};

    bool valid() const { return min.x <= max.x && min.y <= max.y && min.z <= max.z; }
    void expand(const Vec3& p) { min = vmin(min, p); max = vmax(max, p); }
    void expand(const Box3& b) {
        if (!b.valid()) return;
        min = vmin(min, b.min);
        max = vmax(max, b.max);
    }
    Vec3 center() const { return (min + max) * 0.5f; }
    Vec3 size() const { return valid() ? max - min : Vec3(0, 0, 0); }
    float radius() const { return valid() ? length(max - min) * 0.5f : 0.f; }
    Box3 translated(const Vec3& t) const {
        Box3 b;
        if (valid()) { b.min = min + t; b.max = max + t; }
        return b;
    }
};

// Column-major 4x4 matrix.
struct Mat4 {
    float m[16];

    static Mat4 identity() {
        Mat4 r;
        std::memset(r.m, 0, sizeof(r.m));
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.f;
        return r;
    }
    float& at(int row, int col) { return m[col * 4 + row]; }
    float at(int row, int col) const { return m[col * 4 + row]; }

    Mat4 operator*(const Mat4& b) const {
        Mat4 r;
        for (int c = 0; c < 4; ++c)
            for (int rr = 0; rr < 4; ++rr) {
                float s = 0;
                for (int k = 0; k < 4; ++k) s += at(rr, k) * b.at(k, c);
                r.at(rr, c) = s;
            }
        return r;
    }
    Vec4 operator*(const Vec4& v) const {
        return {m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12] * v.w,
                m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13] * v.w,
                m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14] * v.w,
                m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15] * v.w};
    }
    Vec3 transformPoint(const Vec3& p) const {
        Vec4 r = (*this) * Vec4(p, 1.f);
        return {r.x, r.y, r.z};
    }
    Vec3 transformDir(const Vec3& d) const {
        return {m[0] * d.x + m[4] * d.y + m[8] * d.z, m[1] * d.x + m[5] * d.y + m[9] * d.z,
                m[2] * d.x + m[6] * d.y + m[10] * d.z};
    }

    static Mat4 translation(const Vec3& t) {
        Mat4 r = identity();
        r.m[12] = t.x; r.m[13] = t.y; r.m[14] = t.z;
        return r;
    }
    static Mat4 scale(float s) {
        Mat4 r = identity();
        r.m[0] = r.m[5] = r.m[10] = s;
        return r;
    }

    static Mat4 perspective(float fovyRad, float aspect, float zn, float zf) {
        Mat4 r;
        std::memset(r.m, 0, sizeof(r.m));
        float f = 1.f / std::tan(fovyRad * 0.5f);
        r.m[0] = f / aspect;
        r.m[5] = f;
        r.m[10] = (zf + zn) / (zn - zf);
        r.m[11] = -1.f;
        r.m[14] = 2.f * zf * zn / (zn - zf);
        return r;
    }

    static Mat4 ortho(float l, float r_, float b, float t, float zn, float zf) {
        Mat4 r = identity();
        r.m[0] = 2.f / (r_ - l);
        r.m[5] = 2.f / (t - b);
        r.m[10] = -2.f / (zf - zn);
        r.m[12] = -(r_ + l) / (r_ - l);
        r.m[13] = -(t + b) / (t - b);
        r.m[14] = -(zf + zn) / (zf - zn);
        return r;
    }

    // View matrix from an orthonormal camera basis.
    static Mat4 view(const Vec3& eye, const Vec3& right, const Vec3& up, const Vec3& forward) {
        Mat4 r = identity();
        r.m[0] = right.x;  r.m[4] = right.y;  r.m[8] = right.z;
        r.m[1] = up.x;     r.m[5] = up.y;     r.m[9] = up.z;
        r.m[2] = -forward.x; r.m[6] = -forward.y; r.m[10] = -forward.z;
        r.m[12] = -dot(right, eye);
        r.m[13] = -dot(up, eye);
        r.m[14] = dot(forward, eye);
        return r;
    }

    static Mat4 lookAt(const Vec3& eye, const Vec3& target, const Vec3& upHint) {
        Vec3 f = normalize(target - eye);
        Vec3 r = cross(f, upHint);
        if (length2(r) < 1e-12f) r = cross(f, std::fabs(f.z) < 0.9f ? Vec3(0, 0, 1) : Vec3(0, 1, 0));
        r = normalize(r);
        Vec3 u = cross(r, f);
        return view(eye, r, u, f);
    }

    Mat4 inverse() const {
        const float* a = m;
        float inv[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        Mat4 r;
        if (std::fabs(det) < 1e-30f) return identity();
        det = 1.f / det;
        for (int i = 0; i < 16; ++i) r.m[i] = inv[i] * det;
        return r;
    }
};

inline uint32_t packRGBA(float r, float g, float b, float a = 1.f) {
    auto c = [](float v) { return (uint32_t)std::lround(std::clamp(v, 0.f, 1.f) * 255.f); };
    return c(r) | (c(g) << 8) | (c(b) << 16) | (c(a) << 24);
}
// Colours are stored as 0xAABBGGRR (little-endian RGBA bytes), matching GL_UNSIGNED_BYTE RGBA.
inline float colR(uint32_t c) { return (c & 0xFF) / 255.f; }
inline float colG(uint32_t c) { return ((c >> 8) & 0xFF) / 255.f; }
inline float colB(uint32_t c) { return ((c >> 16) & 0xFF) / 255.f; }
inline float colA(uint32_t c) { return ((c >> 24) & 0xFF) / 255.f; }
// Android uses 0xAARRGGBB ints; convert both ways.
inline uint32_t argbToRgba(uint32_t argb) {
    return (argb & 0xFF00FF00u) | ((argb >> 16) & 0xFFu) | ((argb & 0xFFu) << 16);
}
inline uint32_t rgbaToArgb(uint32_t rgba) { return argbToRgba(rgba); }

}  // namespace fsv
