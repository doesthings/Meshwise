// Turntable orbit camera (Z up) with smooth transitions, inertia and zoom-to-point.
#pragma once

#include "vecmath.h"

namespace fsv {

// Standard views addressed by the view cube. Values are shared with the Kotlin side.
enum class StandardView : int {
    Front = 0, Back, Left, Right, Top, Bottom, Iso,
};

struct CameraState {
    Vec3 target;
    float distance = 100.f;
    float yaw = 0.f;    // radians, rotation about +Z (0 = looking from -Y, "front")
    float pitch = 0.f;  // radians, elevation (+90° = top view)
    bool ortho = false;
};

class Camera {
public:
    static constexpr float kDefaultFovY = 35.f * kPi / 180.f;

    // An untouched, auto-fitted view is fitted again for the new size.
    void setViewport(int w, int h);
    int width() const { return width_; }
    int height() const { return height_; }
    float aspect() const { return (float)width_ / (float)height_; }

    // Scene bounds drive clipping planes and zoom limits.
    void setScene(const Vec3& center, float radius) { sceneCenter_ = center; sceneRadius_ = radius > 0 ? radius : 1.f; }
    float sceneRadius() const { return sceneRadius_; }

    const CameraState& state() const { return cur_; }
    void setState(const CameraState& s, bool animate);

    // Fits a sphere into view, keeping the current orientation unless a view is given.
    void fit(const Vec3& center, float radius, bool animate);
    // Fits a box tightly using its projected extents for the (target) orientation.
    void fitBox(const Box3& box, bool animate);

    // Screen areas covered by UI (pixels). The projection centre moves to the middle of the
    // visible band and fitting uses only that band. Animated unless `animate` is false.
    void setInsets(float top, float bottom, bool animate);
    float insetShiftNdc() const { return (insetBottom_ - insetTop_) / (float)height_; }
    bool autoFit() const { return autoFit_; }
    void setView(StandardView v, bool animate);
    void setOrientation(float yaw, float pitch, bool animate);

    // Interaction (pixel units).
    void orbit(float dxPx, float dyPx);
    void pan(float dxPx, float dyPx);
    // Zoom by `factor` (>1 = closer) keeping world point `anchor` fixed on screen.
    void zoomAt(float factor, const Vec3& anchor);
    void fling(float vxPxPerSec, float vyPxPerSec);
    void stopMotion();
    void setTurntable(bool on) { turntable_ = on; }
    bool turntable() const { return turntable_; }
    void setOrtho(bool ortho);
    void setSensitivity(float s) { sensitivity_ = s; }
    // Turntable spin in radians per second (negative spins the other way).
    void setTurntableSpeed(float radPerSec) { turntableSpeed_ = radPerSec; }
    // Vertical field of view (radians). The distance is rescaled so the framing stays the same.
    void setFov(float radians);
    float fovY() const { return fovY_; }

    // Advances animations; returns true while anything is still moving.
    bool update(float dt);

    // Derived matrices for the current frame.
    Vec3 eye() const;
    Vec3 forward() const;
    Vec3 right() const;
    Vec3 up() const;
    Mat4 view() const;
    Mat4 projection() const;
    Mat4 viewProj() const { return projection() * view(); }
    float nearPlane() const;
    float farPlane() const;

    // World units per pixel at the target depth.
    float worldPerPixel() const;
    // Ray through a pixel (origin at the near plane for ortho).
    void rayAt(float px, float py, Vec3& origin, Vec3& dir) const;
    // Point on the plane through the target facing the camera, under a pixel.
    Vec3 pointOnTargetPlane(float px, float py) const;

    uint64_t version() const { return version_; }

private:
    void touch() { ++version_; }
    static float wrapAngle(float a);

    int width_ = 1, height_ = 1;
    Vec3 sceneCenter_;
    float sceneRadius_ = 1.f;
    CameraState cur_;

    // Transition animation.
    bool animating_ = false;
    float animT_ = 0.f, animDuration_ = 0.35f;
    CameraState from_, to_;

    // UI insets (current values animate towards targets).
    float insetTop_ = 0.f, insetBottom_ = 0.f, targetTop_ = 0.f, targetBottom_ = 0.f;
    bool autoFit_ = false;  // last placement came from a fit and the user has not moved since
    Box3 lastFitBox_;

    // Inertia.
    float yawVel_ = 0.f, pitchVel_ = 0.f;
    bool turntable_ = false;
    float sensitivity_ = 1.f;
    float turntableSpeed_ = 0.6f;
    float fovY_ = kDefaultFovY;
    uint64_t version_ = 1;
};

}  // namespace fsv
