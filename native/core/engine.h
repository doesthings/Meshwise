// Engine: owns the scene (model + analysis data + per-part state), the camera and the
// renderer. Public methods are thread-safe; GL work happens in the draw/snapshot calls,
// which must run on the GL thread.
//
// Locking: mu_ guards the scene and camera and is only held briefly. glMu_ guards the
// renderer and is held for a whole GL call, including slow GPU uploads. Lock order is
// glMu_ before mu_; nothing takes glMu_ while holding mu_.
#pragma once

#include <chrono>
#include <functional>
#include <memory>
#include <mutex>
#include <vector>

#include "analysis.h"
#include "bvh.h"
#include "camera.h"
#include "model.h"
#include "renderer.h"

namespace fsv {

struct PickResult {
    bool hit = false;
    int part = -1;
    Vec3 point;   // world space (includes part offsets)
    Vec3 normal;  // world space surface normal (facing the camera)
    bool onCap = false;
    uint32_t triangle = UINT32_MAX;
};

struct PartState {
    uint32_t color = 0xFFCCCCCCu;  // RGBA
    bool visible = true;
    bool selected = false;
    bool highlight = false;
    bool ghost = false;
    bool useVertexColors = false;
    float roughness = 0.62f, metalness = 0.f, clearcoat = 0.f;
    Vec3 manualOffset;
};

class Engine {
public:
    Engine();

    // ---- GL thread ----
    void onSurfaceCreated();
    void onSurfaceChanged(int w, int h);
    // Renders a frame into the bound default framebuffer. Returns true if another frame
    // is needed (animation, inertia, pending uploads).
    bool drawFrame();
    // Offscreen render with the current camera (or a fitted iso camera for thumbnails).
    bool snapshot(int w, int h, bool transparent, std::vector<uint8_t>& rgba);
    bool thumbnail(int size, std::vector<uint8_t>& rgba);

    // ---- any thread ----
    void setModel(std::shared_ptr<const Model> model, bool resetView);
    std::shared_ptr<const Model> model() const;
    // Background analysis results. When `source` is given, the result is dropped (and false
    // returned) if the engine's model has been replaced since the analysis started.
    bool setTopology(std::shared_ptr<const Topology> topo, const Model* source = nullptr);
    bool setBVH(std::shared_ptr<const ModelBVH> bvh, const Model* source = nullptr);
    std::shared_ptr<const ModelBVH> bvh() const;
    bool setScalars(std::shared_ptr<const std::vector<float>> scalars, const Model* source = nullptr);

    // Camera.
    void orbit(float dx, float dy);
    void pan(float dx, float dy);
    void zoom(float factor, float px, float py);
    void fling(float vx, float vy);
    void stopMotion();
    void setStandardView(int view, bool animate);
    void setOrientation(float yaw, float pitch, bool animate);
    void fitView(bool animate);
    void focusPart(int part, bool animate);
    void focusPoint(const Vec3& p, float zoomFactor);
    void setOrtho(bool ortho);
    void setViewInsets(float top, float bottom, bool animate);
    void setTurntable(bool on);
    bool turntable() const;
    void setSensitivity(float s);
    void setTurntableSpeed(float radPerSec);
    void setFov(float radians);
    CameraState cameraState() const;
    void setCameraState(const CameraState& s, bool animate);
    // Fills viewProj (16 floats), returns camera version; also yaw/pitch for the view cube.
    uint64_t cameraMatrices(float* viewProj16, float* yawPitch2, float* viewport2) const;
    // Projects a world point to pixel coordinates (x, y, depth). Returns false if behind.
    bool project(const Vec3& p, float out[3]) const;

    // Picking.
    PickResult pick(float px, float py) const;

    // Part state.
    size_t partCount() const;
    void setPartColor(int part, uint32_t rgba);  // part < 0 => all
    void setPartVisible(int part, bool v);
    void setPartSelected(int part, bool s);     // part < 0 => clear
    void setPartHighlight(int part, bool h);
    void setPartGhost(int part, bool g);
    void setPartMaterial(int part, float rough, float metal, float clearcoat);
    void setPartVertexColors(int part, bool use);
    void setPartManualOffset(int part, const Vec3& off);
    Vec3 partManualOffset(int part) const;
    Vec3 partOffset(int part) const;  // total (explode + manual)
    void setExplode(float factor);
    float explode() const;
    void resetPartStates();
    std::vector<PartState> partStates() const;

    // Rendering options.
    RenderOptions options() const;
    void setOptions(const RenderOptions& o);

    // Scene bounds including offsets of visible parts.
    Box3 sceneBounds() const;
    float groundZ() const;

    uint64_t sceneVersion() const;

    // Test hook: runs on the GL thread right before a model upload, with only glMu_ held.
    void setUploadHookForTests(std::function<void()> hook);

private:
    void markPartsDirty() { partsDirty_ = true; ++sceneVersion_; }
    void syncGpu();  // GL thread, glMu_ held, mu_ not held
    Box3 sceneBoundsLocked(bool visibleOnly) const;
    Vec3 partOffsetLocked(int part) const;
    void buildPartGpu(std::vector<PartGpu>& out) const;
    FrameParams frameParamsLocked(const Camera& cam) const;
    void updateCameraSceneLocked();

    mutable std::mutex mu_;
    Camera camera_;

    // GL thread state, guarded by glMu_.
    std::mutex glMu_;
    Renderer renderer_;
    bool glReady_ = false;
    std::function<void()> uploadHook_;

    std::shared_ptr<const Model> model_;
    std::shared_ptr<const Topology> topo_;
    std::shared_ptr<const ModelBVH> bvh_;
    std::shared_ptr<const std::vector<float>> scalars_;
    std::vector<PartState> parts_;
    float explode_ = 0.f;
    RenderOptions options_;
    bool modelDirty_ = false, topoDirty_ = false, scalarsDirty_ = false, partsDirty_ = false;
    uint64_t sceneVersion_ = 1;
    std::chrono::steady_clock::time_point lastFrame_;
    bool hadFrame_ = false;
};

}  // namespace fsv
