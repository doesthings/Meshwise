#include "camera.h"

namespace fsv {

namespace {
constexpr float kOrbitRadPerPx = 0.0085f;
float easeInOut(float t) { return t < 0.5f ? 4.f * t * t * t : 1.f - std::pow(-2.f * t + 2.f, 3.f) / 2.f; }
}  // namespace

float Camera::wrapAngle(float a) {
    while (a > kPi) a -= 2.f * kPi;
    while (a < -kPi) a += 2.f * kPi;
    return a;
}

void Camera::setViewport(int w, int h) {
    w = w > 0 ? w : 1;
    h = h > 0 ? h : 1;
    if (w == width_ && h == height_) return;
    width_ = w;
    height_ = h;
    // The first model is often fitted before the view has its real size.
    if (autoFit_ && lastFitBox_.valid()) fitBox(lastFitBox_, false);
    touch();
}

void Camera::setState(const CameraState& s, bool animate) {
    autoFit_ = false;
    if (animate) {
        from_ = cur_;
        to_ = s;
        to_.yaw = from_.yaw + wrapAngle(s.yaw - from_.yaw);
        animT_ = 0.f;
        animating_ = true;
        cur_.ortho = s.ortho;
    } else {
        cur_ = s;
        animating_ = false;
    }
    yawVel_ = pitchVel_ = 0.f;
    touch();
}

void Camera::fit(const Vec3& center, float radius, bool animate) {
    CameraState s = animating_ ? to_ : cur_;
    s.target = center;
    float visH = std::max(1.f, (float)height_ - targetTop_ - targetBottom_);
    float tanV = std::tan(fovY_ * 0.5f) * visH / (float)height_;
    float tanH = std::tan(fovY_ * 0.5f) * aspect();
    float fov = 2.f * std::atan(std::min(tanV, tanH));
    s.distance = std::max(1e-4f, radius) / std::sin(fov * 0.5f) * 1.04f;
    setState(s, animate);
    autoFit_ = false;
}

void Camera::fitBox(const Box3& box, bool animate) {
    if (!box.valid()) return;
    CameraState s = animating_ ? to_ : cur_;
    const Vec3 c = box.center();
    // Basis of the destination orientation.
    float cp = std::cos(s.pitch), sp = std::sin(s.pitch), cy = std::cos(s.yaw), sy = std::sin(s.yaw);
    Vec3 f(-cp * sy, cp * cy, -sp);
    Vec3 r(cy, sy, 0.f);
    Vec3 u = cross(r, f);
    float visH = std::max(1.f, (float)height_ - targetTop_ - targetBottom_);
    float tanV = std::tan(fovY_ * 0.5f) * visH / (float)height_;
    float tanH = std::tan(fovY_ * 0.5f) * aspect();
    const float margin = 1.10f;
    float dist = 0.f;
    float minExtent = 1e-4f;
    for (int i = 0; i < 8; ++i) {
        Vec3 p((i & 1) ? box.max.x : box.min.x, (i & 2) ? box.max.y : box.min.y, (i & 4) ? box.max.z : box.min.z);
        Vec3 d = p - c;
        float x = std::fabs(dot(d, r)) * margin, y = std::fabs(dot(d, u)) * margin, z = -dot(d, f);
        minExtent = std::max(minExtent, std::max(x, y));
        float need = s.ortho ? std::max(x / tanH, y / tanV) : z + std::max(x / tanH, y / tanV);
        dist = std::max(dist, need);
    }
    s.target = c;
    s.distance = std::max(dist, minExtent * 0.5f);
    setState(s, animate);
    autoFit_ = true;
    lastFitBox_ = box;
}

void Camera::setInsets(float top, float bottom, bool animate) {
    top = std::max(0.f, top);
    bottom = std::max(0.f, bottom);
    if (std::fabs(top - targetTop_) < 0.5f && std::fabs(bottom - targetBottom_) < 0.5f && (animate || (top == insetTop_ && bottom == insetBottom_))) return;
    targetTop_ = top;
    targetBottom_ = bottom;
    if (!animate) {
        insetTop_ = top;
        insetBottom_ = bottom;
    }
    // Keep an untouched, auto-fitted view filling the new visible band.
    if (autoFit_ && lastFitBox_.valid()) fitBox(lastFitBox_, animate);
    touch();
}

void Camera::setOrientation(float yaw, float pitch, bool animate) {
    CameraState s = animating_ ? to_ : cur_;
    s.yaw = yaw;
    s.pitch = std::clamp(pitch, -kPi / 2, kPi / 2);
    setState(s, animate);
}

void Camera::setFov(float radians) {
    float f = std::clamp(radians, 10.f * kPi / 180.f, 90.f * kPi / 180.f);
    if (std::fabs(f - fovY_) < 1e-5f) return;
    const float k = std::tan(fovY_ * 0.5f) / std::tan(f * 0.5f);
    fovY_ = f;
    cur_.distance *= k;
    from_.distance *= k;
    to_.distance *= k;
    touch();
}

void Camera::setView(StandardView v, bool animate) {
    const float d90 = kPi / 2;
    switch (v) {
        case StandardView::Front: setOrientation(0, 0, animate); break;
        case StandardView::Back: setOrientation(kPi, 0, animate); break;
        case StandardView::Left: setOrientation(-d90, 0, animate); break;
        case StandardView::Right: setOrientation(d90, 0, animate); break;
        case StandardView::Top: setOrientation(0, d90, animate); break;
        case StandardView::Bottom: setOrientation(0, -d90, animate); break;
        case StandardView::Iso: setOrientation(kPi / 4, std::atan(1.f / std::sqrt(2.f)), animate); break;
    }
}

void Camera::orbit(float dxPx, float dyPx) {
    animating_ = false;
    autoFit_ = false;
    float k = kOrbitRadPerPx * sensitivity_;
    cur_.yaw = wrapAngle(cur_.yaw - dxPx * k);
    cur_.pitch = std::clamp(cur_.pitch + dyPx * k, -kPi / 2, kPi / 2);
    touch();
}

void Camera::pan(float dxPx, float dyPx) {
    animating_ = false;
    autoFit_ = false;
    float wpp = worldPerPixel();
    cur_.target -= right() * (dxPx * wpp);
    cur_.target += up() * (dyPx * wpp);
    // Keep the target within a reasonable distance of the scene.
    Vec3 d = cur_.target - sceneCenter_;
    float maxD = sceneRadius_ * 20.f;
    if (length(d) > maxD) cur_.target = sceneCenter_ + normalize(d) * maxD;
    touch();
}

void Camera::zoomAt(float factor, const Vec3& anchor) {
    animating_ = false;
    autoFit_ = false;
    if (!(factor > 0)) return;
    float newDist = std::clamp(cur_.distance / factor, sceneRadius_ * 0.002f, sceneRadius_ * 60.f);
    float s = newDist / cur_.distance;  // actual scale applied
    cur_.target = anchor + (cur_.target - anchor) * s;
    cur_.distance = newDist;
    touch();
}

void Camera::fling(float vx, float vy) {
    float k = kOrbitRadPerPx * sensitivity_;
    yawVel_ = -vx * k;
    pitchVel_ = vy * k;
    const float maxV = 12.f;
    yawVel_ = std::clamp(yawVel_, -maxV, maxV);
    pitchVel_ = std::clamp(pitchVel_, -maxV, maxV);
}

void Camera::stopMotion() {
    yawVel_ = pitchVel_ = 0.f;
}

void Camera::setOrtho(bool ortho) {
    cur_.ortho = ortho;
    if (animating_) to_.ortho = ortho;
    touch();
}

bool Camera::update(float dt) {
    bool moving = false;
    dt = std::clamp(dt, 0.f, 0.1f);
    if (animating_) {
        animT_ += dt / animDuration_;
        float t = easeInOut(std::min(animT_, 1.f));
        cur_.target = lerp(from_.target, to_.target, t);
        cur_.distance = from_.distance * std::pow(to_.distance / from_.distance, t);
        cur_.yaw = wrapAngle(from_.yaw + (to_.yaw - from_.yaw) * t);
        cur_.pitch = from_.pitch + (to_.pitch - from_.pitch) * t;
        if (animT_ >= 1.f) {
            animating_ = false;
            cur_ = to_;
            cur_.yaw = wrapAngle(cur_.yaw);
        }
        moving = true;
        touch();
    }
    if (std::fabs(yawVel_) > 1e-3f || std::fabs(pitchVel_) > 1e-3f) {
        cur_.yaw = wrapAngle(cur_.yaw + yawVel_ * dt);
        cur_.pitch = std::clamp(cur_.pitch + pitchVel_ * dt, -kPi / 2, kPi / 2);
        float decay = std::exp(-dt * 4.5f);
        yawVel_ *= decay;
        pitchVel_ *= decay;
        if (std::fabs(yawVel_) < 0.01f && std::fabs(pitchVel_) < 0.01f) yawVel_ = pitchVel_ = 0;
        moving = true;
        touch();
    }
    if (insetTop_ != targetTop_ || insetBottom_ != targetBottom_) {
        float k = 1.f - std::exp(-dt * 10.f);
        insetTop_ += (targetTop_ - insetTop_) * k;
        insetBottom_ += (targetBottom_ - insetBottom_) * k;
        if (std::fabs(insetTop_ - targetTop_) < 0.5f && std::fabs(insetBottom_ - targetBottom_) < 0.5f) {
            insetTop_ = targetTop_;
            insetBottom_ = targetBottom_;
        }
        moving = true;
        touch();
    }
    if (turntable_ && !animating_) {
        cur_.yaw = wrapAngle(cur_.yaw + dt * turntableSpeed_);
        moving = true;
        touch();
    }
    return moving;
}

Vec3 Camera::forward() const {
    float cp = std::cos(cur_.pitch), sp = std::sin(cur_.pitch);
    float cy = std::cos(cur_.yaw), sy = std::sin(cur_.yaw);
    Vec3 d(cp * sy, -cp * cy, sp);  // eye offset direction
    return -d;
}

Vec3 Camera::right() const { return Vec3(std::cos(cur_.yaw), std::sin(cur_.yaw), 0.f); }
Vec3 Camera::up() const { return cross(right(), forward()); }
Vec3 Camera::eye() const { return cur_.target - forward() * cur_.distance; }

Mat4 Camera::view() const { return Mat4::view(eye(), right(), up(), forward()); }

float Camera::nearPlane() const {
    if (cur_.ortho) return -sceneRadius_ * 40.f;
    float dEye = length(eye() - sceneCenter_);
    // The floor grid can come close to the camera (especially with a lens shift), so the
    // near plane is not pushed out to the model's bounding sphere.
    float n = std::min(dEye - sceneRadius_ * 1.6f, dEye * 0.08f);
    return std::max({n, sceneRadius_ * 0.0005f, cur_.distance * 0.01f});
}

float Camera::farPlane() const {
    float dEye = length(eye() - sceneCenter_);
    if (cur_.ortho) return cur_.distance + dEye + sceneRadius_ * 40.f;
    return dEye + sceneRadius_ * 30.f;
}

Mat4 Camera::projection() const {
    float n = nearPlane(), f = farPlane();
    Mat4 p;
    if (cur_.ortho) {
        float h = cur_.distance * std::tan(fovY_ * 0.5f);
        float w = h * aspect();
        p = Mat4::ortho(-w, w, -h, h, n, f);
    } else {
        p = Mat4::perspective(fovY_, aspect(), n, f);
    }
    // Lens shift: centre the image in the band not covered by UI.
    const float shift = insetShiftNdc();
    if (shift != 0.f)
        for (int c = 0; c < 4; ++c) p.m[c * 4 + 1] += shift * p.m[c * 4 + 3];
    return p;
}

float Camera::worldPerPixel() const {
    return 2.f * cur_.distance * std::tan(fovY_ * 0.5f) / (float)height_;
}

void Camera::rayAt(float px, float py, Vec3& origin, Vec3& dir) const {
    float ndcX = (2.f * px / (float)width_) - 1.f;
    float ndcY = 1.f - (2.f * py / (float)height_) - insetShiftNdc();
    float h = std::tan(fovY_ * 0.5f);
    if (cur_.ortho) {
        float hh = cur_.distance * h, ww = hh * aspect();
        origin = eye() + right() * (ndcX * ww) + up() * (ndcY * hh) - forward() * (sceneRadius_ * 30.f);
        dir = forward();
    } else {
        origin = eye();
        dir = normalize(forward() + right() * (ndcX * h * aspect()) + up() * (ndcY * h));
    }
}

Vec3 Camera::pointOnTargetPlane(float px, float py) const {
    Vec3 o, d;
    rayAt(px, py, o, d);
    Vec3 n = forward();
    float denom = dot(d, n);
    if (std::fabs(denom) < 1e-8f) return cur_.target;
    float t = dot(cur_.target - o, n) / denom;
    return o + d * t;
}

}  // namespace fsv
