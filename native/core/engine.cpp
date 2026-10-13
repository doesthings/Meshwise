#include "engine.h"

namespace fsv {

namespace {

uint32_t capColorFor(uint32_t rgba) {
    float r = colR(rgba), g = colG(rgba), b = colB(rgba);
    return packRGBA(r + (1.f - r) * 0.3f, g + (1.f - g) * 0.3f, b + (1.f - b) * 0.3f);
}

}  // namespace

Engine::Engine() {
    camera_.setViewport(1, 1);
}

void Engine::onSurfaceCreated() {
    std::lock_guard<std::mutex> lk(mu_);
    // A new context: previous GL names are gone.
    renderer_.invalidate();
    glReady_ = renderer_.init();
    modelDirty_ = true;
    hadFrame_ = false;
    if (!glReady_) FSV_LOGE("renderer init failed");
}

void Engine::onSurfaceChanged(int w, int h) {
    std::lock_guard<std::mutex> lk(mu_);
    camera_.setViewport(w, h);
}

void Engine::buildPartGpu(std::vector<PartGpu>& out) const {
    out.resize(parts_.size());
    for (size_t i = 0; i < parts_.size(); ++i) {
        const PartState& s = parts_[i];
        PartGpu& g = out[i];
        g.color[0] = colR(s.color); g.color[1] = colG(s.color); g.color[2] = colB(s.color); g.color[3] = colA(s.color);
        Vec3 off = partOffsetLocked((int)i);
        uint32_t flags = (s.visible ? (uint32_t)kPartVisible : 0u) | (s.selected ? (uint32_t)kPartSelected : 0u) |
                         (s.useVertexColors ? (uint32_t)kPartVertexColors : 0u) | (s.highlight ? (uint32_t)kPartHighlight : 0u) |
                         (s.ghost ? (uint32_t)kPartGhost : 0u) |
                         (model_ && model_->parts[i].volume < 0 ? (uint32_t)kPartInverted : 0u);
        g.offsetFlags[0] = off.x; g.offsetFlags[1] = off.y; g.offsetFlags[2] = off.z; g.offsetFlags[3] = (float)flags;
        g.material[0] = s.roughness; g.material[1] = s.metalness; g.material[2] = s.clearcoat; g.material[3] = 0;
        uint32_t cap = capColorFor(s.color);
        g.cap[0] = colR(cap); g.cap[1] = colG(cap); g.cap[2] = colB(cap); g.cap[3] = 1;
    }
}

Vec3 Engine::partOffsetLocked(int part) const {
    if (!model_ || part < 0 || (size_t)part >= parts_.size()) return Vec3(0, 0, 0);
    Vec3 off = parts_[(size_t)part].manualOffset;
    if (explode_ != 0.f && model_->parts.size() > 1) {
        Vec3 c = model_->bounds.center();
        Vec3 pc = model_->parts[(size_t)part].bounds.center();
        off += (pc - c) * explode_;
    }
    return off;
}

Box3 Engine::sceneBoundsLocked(bool visibleOnly) const {
    Box3 b;
    if (!model_) return b;
    for (size_t i = 0; i < model_->parts.size(); ++i) {
        if (visibleOnly && i < parts_.size() && !parts_[i].visible) continue;
        b.expand(model_->parts[i].bounds.translated(partOffsetLocked((int)i)));
    }
    if (!b.valid()) b = model_->bounds;
    return b;
}

void Engine::updateCameraSceneLocked() {
    Box3 b = sceneBoundsLocked(false);
    if (b.valid()) camera_.setScene(b.center(), std::max(1e-3f, b.radius()));
}

FrameParams Engine::frameParamsLocked(const Camera& cam) const {
    FrameParams f;
    f.viewProj = cam.viewProj();
    f.eye = cam.eye();
    f.forward = cam.forward();
    f.right = cam.right();
    f.up = cam.up();
    f.ortho = cam.state().ortho;
    f.worldPerPixel = cam.worldPerPixel();
    f.width = cam.width();
    f.height = cam.height();
    f.bounds = sceneBoundsLocked(true);
    if (!f.bounds.valid()) {
        f.bounds.expand(Vec3(-50, -50, 0));
        f.bounds.expand(Vec3(50, 50, 50));
    }
    f.groundZ = f.bounds.min.z;
    return f;
}

void Engine::syncGpuLocked() {
    if (modelDirty_) {
        if (model_) renderer_.uploadModel(*model_);
        else renderer_.clearModel();
        modelDirty_ = false;
        topoDirty_ = scalarsDirty_ = partsDirty_ = true;
    }
    if (topoDirty_) {
        static const std::vector<uint32_t> empty;
        if (model_) {
            const std::vector<uint32_t>& feature = topo_ ? topo_->featureEdges : (model_->cadEdges.empty() ? empty : model_->cadEdges);
            renderer_.uploadLines(LineSet::Feature, feature);
            renderer_.uploadLines(LineSet::Wire, topo_ ? topo_->allEdges : empty);
            renderer_.uploadLines(LineSet::Open, topo_ ? topo_->openEdges : empty);
            renderer_.uploadLines(LineSet::NonManifold, topo_ ? topo_->nonManifoldEdges : empty);
        }
        topoDirty_ = false;
    }
    if (scalarsDirty_) {
        if (scalars_ && model_ && scalars_->size() == model_->vertexCount()) renderer_.uploadScalars(*scalars_);
        else renderer_.clearScalars();
        scalarsDirty_ = false;
    }
    if (partsDirty_) {
        std::vector<PartGpu> gpu;
        buildPartGpu(gpu);
        renderer_.updateParts(gpu);
        partsDirty_ = false;
    }
}

bool Engine::drawFrame() {
    std::lock_guard<std::mutex> lk(mu_);
    auto now = std::chrono::steady_clock::now();
    float dt = hadFrame_ ? std::chrono::duration<float>(now - lastFrame_).count() : 0.f;
    lastFrame_ = now;
    hadFrame_ = true;
    bool moving = camera_.update(dt);
    if (!glReady_) return false;
    syncGpuLocked();
    renderer_.render(frameParamsLocked(camera_), options_, 0);
    return moving;
}

bool Engine::snapshot(int w, int h, bool transparent, std::vector<uint8_t>& rgba) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!glReady_) return false;
    syncGpuLocked();
    Camera cam = camera_;
    cam.setViewport(w, h);
    RenderOptions o = options_;
    o.transparentBackground = transparent;
    o.lineScale = options_.lineScale * (float)h / (float)std::max(1, camera_.height());
    return renderer_.renderToPixels(frameParamsLocked(cam), o, w, h, rgba);
}

bool Engine::thumbnail(int size, std::vector<uint8_t>& rgba) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!glReady_ || !model_) return false;
    syncGpuLocked();
    Camera cam;
    cam.setViewport(size, size);
    Box3 b = sceneBoundsLocked(true);
    cam.setScene(b.center(), b.radius());
    cam.setView(StandardView::Iso, false);
    cam.fitBox(b, false);
    RenderOptions o = options_;
    o.section = false;
    o.mode = ShadingMode::Shaded;
    o.openEdges = false;
    o.grid = true;
    o.lineScale = 1.f;
    return renderer_.renderToPixels(frameParamsLocked(cam), o, size, size, rgba);
}

void Engine::setModel(std::shared_ptr<const Model> model, bool resetView) {
    std::lock_guard<std::mutex> lk(mu_);
    model_ = std::move(model);
    topo_.reset();
    bvh_.reset();
    scalars_.reset();
    parts_.clear();
    explode_ = 0.f;
    if (model_) {
        parts_.resize(model_->parts.size());
        for (size_t i = 0; i < parts_.size(); ++i) {
            parts_[i].color = model_->parts[i].color | 0xFF000000u;
            parts_[i].useVertexColors = model_->parts[i].hasVertexColors;
            parts_[i].roughness = 0.62f;
        }
    }
    modelDirty_ = true;
    ++sceneVersion_;
    updateCameraSceneLocked();
    if (model_ && resetView) {
        Box3 b = sceneBoundsLocked(true);
        camera_.setView(StandardView::Iso, false);
        camera_.fitBox(b, false);
    }
}

std::shared_ptr<const Model> Engine::model() const {
    std::lock_guard<std::mutex> lk(mu_);
    return model_;
}

bool Engine::setTopology(std::shared_ptr<const Topology> topo, const Model* source) {
    std::lock_guard<std::mutex> lk(mu_);
    if (source && source != model_.get()) return false;
    topo_ = std::move(topo);
    topoDirty_ = true;
    return true;
}

bool Engine::setBVH(std::shared_ptr<const ModelBVH> bvh, const Model* source) {
    std::lock_guard<std::mutex> lk(mu_);
    if (source && source != model_.get()) return false;
    bvh_ = std::move(bvh);
    return true;
}

std::shared_ptr<const ModelBVH> Engine::bvh() const {
    std::lock_guard<std::mutex> lk(mu_);
    return bvh_;
}

bool Engine::setScalars(std::shared_ptr<const std::vector<float>> scalars, const Model* source) {
    std::lock_guard<std::mutex> lk(mu_);
    if (source && source != model_.get()) return false;
    scalars_ = std::move(scalars);
    scalarsDirty_ = true;
    return true;
}

void Engine::orbit(float dx, float dy) { std::lock_guard<std::mutex> lk(mu_); camera_.orbit(dx, dy); }
void Engine::pan(float dx, float dy) { std::lock_guard<std::mutex> lk(mu_); camera_.pan(dx, dy); }

void Engine::zoom(float factor, float px, float py) {
    // Anchor on the surface under the fingers when there is one.
    PickResult hit = pick(px, py);
    std::lock_guard<std::mutex> lk(mu_);
    Vec3 anchor = hit.hit ? hit.point : camera_.pointOnTargetPlane(px, py);
    camera_.zoomAt(factor, anchor);
}

void Engine::fling(float vx, float vy) { std::lock_guard<std::mutex> lk(mu_); camera_.fling(vx, vy); }
void Engine::stopMotion() { std::lock_guard<std::mutex> lk(mu_); camera_.stopMotion(); }

void Engine::setStandardView(int view, bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    camera_.setView((StandardView)std::clamp(view, 0, 6), animate);
}

void Engine::setOrientation(float yaw, float pitch, bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    camera_.setOrientation(yaw, pitch, animate);
}

void Engine::fitView(bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    Box3 b = sceneBoundsLocked(true);
    if (!b.valid()) return;
    updateCameraSceneLocked();
    camera_.fitBox(b, animate);
}

void Engine::focusPart(int part, bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!model_ || part < 0 || (size_t)part >= model_->parts.size()) return;
    Box3 b = model_->parts[(size_t)part].bounds.translated(partOffsetLocked(part));
    camera_.fitBox(b, animate);
}

void Engine::focusPoint(const Vec3& p, float zoomFactor) {
    std::lock_guard<std::mutex> lk(mu_);
    CameraState s = camera_.state();
    s.target = p;
    s.distance = std::max(camera_.sceneRadius() * 0.002f, s.distance / std::max(zoomFactor, 0.01f));
    camera_.setState(s, true);
}

void Engine::setOrtho(bool ortho) { std::lock_guard<std::mutex> lk(mu_); camera_.setOrtho(ortho); }
void Engine::setViewInsets(float top, float bottom, bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    camera_.setInsets(top, bottom, animate);
}
void Engine::setTurntable(bool on) { std::lock_guard<std::mutex> lk(mu_); camera_.setTurntable(on); }
bool Engine::turntable() const { std::lock_guard<std::mutex> lk(mu_); return camera_.turntable(); }
void Engine::setSensitivity(float s) { std::lock_guard<std::mutex> lk(mu_); camera_.setSensitivity(s); }

CameraState Engine::cameraState() const {
    std::lock_guard<std::mutex> lk(mu_);
    return camera_.state();
}

void Engine::setCameraState(const CameraState& s, bool animate) {
    std::lock_guard<std::mutex> lk(mu_);
    camera_.setState(s, animate);
}

uint64_t Engine::cameraMatrices(float* vp, float* yawPitch, float* viewport) const {
    std::lock_guard<std::mutex> lk(mu_);
    Mat4 m = camera_.viewProj();
    std::memcpy(vp, m.m, sizeof(m.m));
    if (yawPitch) { yawPitch[0] = camera_.state().yaw; yawPitch[1] = camera_.state().pitch; }
    if (viewport) { viewport[0] = (float)camera_.width(); viewport[1] = (float)camera_.height(); }
    return camera_.version() + sceneVersion_ * 1000003ull;
}

bool Engine::project(const Vec3& p, float out[3]) const {
    std::lock_guard<std::mutex> lk(mu_);
    Vec4 c = camera_.viewProj() * Vec4(p, 1.f);
    if (c.w <= 1e-8f) return false;
    out[0] = (c.x / c.w * 0.5f + 0.5f) * (float)camera_.width();
    out[1] = (1.f - (c.y / c.w * 0.5f + 0.5f)) * (float)camera_.height();
    out[2] = c.z / c.w;
    return true;
}

PickResult Engine::pick(float px, float py) const {
    std::lock_guard<std::mutex> lk(mu_);
    PickResult r;
    if (!model_ || !bvh_ || bvh_->partCount() != model_->parts.size()) return r;
    Vec3 o, d;
    camera_.rayAt(px, py, o, d);
    const Model& m = *model_;
    const bool clip = options_.section;
    const Vec4 plane = Renderer::sectionPlaneEquation(options_);
    const Vec3 pn = plane.xyz();
    float bestT = std::numeric_limits<float>::max();
    for (int pass = 0; pass < 2 && !r.hit; ++pass) {
        for (size_t pi = 0; pi < m.parts.size(); ++pi) {
            const PartState& s = parts_[pi];
            if (!s.visible || (pass == 0 && s.ghost) || (pass == 1 && !s.ghost)) continue;
            Vec3 off = partOffsetLocked((int)pi);
            Box3 b = m.parts[pi].bounds.translated(off);
            // Quick slab test against the part box.
            float t0 = 0, t1 = bestT;
            bool miss = false;
            for (int a = 0; a < 3 && !miss; ++a) {
                float inv = 1.f / d[a];
                float ta = (b.min[a] - o[a]) * inv, tb = (b.max[a] - o[a]) * inv;
                if (ta > tb) std::swap(ta, tb);
                t0 = std::max(t0, ta);
                t1 = std::min(t1, tb);
                if (t0 > t1) miss = true;
            }
            if (miss) continue;
            Ray ray{o - off, d};
            Hit hit;
            auto accept = [&](uint32_t, float t) {
                if (!clip) return true;
                Vec3 p = o + d * t;
                return dot(p, pn) + plane.w <= 0.f;
            };
            if (bvh_->part(pi).intersect(m, ray, 0.f, bestT, hit, accept)) {
                bestT = hit.t;
                r.hit = true;
                r.part = (int)pi;
                r.triangle = hit.triangle;
                r.point = o + d * hit.t;
                Vec3 a = m.vertex(m.indices[3 * hit.triangle]), bb = m.vertex(m.indices[3 * hit.triangle + 1]),
                     c = m.vertex(m.indices[3 * hit.triangle + 2]);
                Vec3 n = normalize(cross(bb - a, c - a));
                r.onCap = false;
                if (dot(n, d) > 0) {
                    n = -n;
                    if (clip) {
                        // Looking at an interior face through the cut: the visible surface is the cap.
                        float denom = dot(d, pn);
                        if (std::fabs(denom) > 1e-8f) {
                            float tp = -(dot(o, pn) + plane.w) / denom;
                            if (tp > 0 && tp < hit.t) {
                                r.point = o + d * tp;
                                n = -pn;
                                r.onCap = true;
                            }
                        }
                    }
                }
                r.normal = n;
            }
        }
    }
    return r;
}

size_t Engine::partCount() const {
    std::lock_guard<std::mutex> lk(mu_);
    return parts_.size();
}

#define FSV_FOR_PARTS(part, body)                                         \
    std::lock_guard<std::mutex> lk(mu_);                                  \
    if (part < 0) {                                                       \
        for (auto& ps : parts_) { body; }                                 \
    } else if ((size_t)part < parts_.size()) {                            \
        auto& ps = parts_[(size_t)part];                                  \
        body;                                                             \
    }                                                                     \
    markPartsDirty();

void Engine::setPartColor(int part, uint32_t rgba) { FSV_FOR_PARTS(part, ps.color = rgba | 0xFF000000u; ps.useVertexColors = false) }
void Engine::setPartVisible(int part, bool v) {
    FSV_FOR_PARTS(part, ps.visible = v)
}
void Engine::setPartSelected(int part, bool s) {
    std::lock_guard<std::mutex> lk(mu_);
    if (part < 0) {
        for (auto& ps : parts_) ps.selected = false;
    } else if ((size_t)part < parts_.size()) {
        parts_[(size_t)part].selected = s;
    }
    markPartsDirty();
}
void Engine::setPartHighlight(int part, bool h) { FSV_FOR_PARTS(part, ps.highlight = h) }
void Engine::setPartGhost(int part, bool g) { FSV_FOR_PARTS(part, ps.ghost = g) }
void Engine::setPartMaterial(int part, float rough, float metal, float clearcoat) {
    FSV_FOR_PARTS(part, ps.roughness = rough; ps.metalness = metal; ps.clearcoat = clearcoat)
}
void Engine::setPartVertexColors(int part, bool use) {
    std::lock_guard<std::mutex> lk(mu_);
    auto apply = [&](size_t i) {
        parts_[i].useVertexColors = use && model_ && model_->parts[i].hasVertexColors;
    };
    if (part < 0) for (size_t i = 0; i < parts_.size(); ++i) apply(i);
    else if ((size_t)part < parts_.size()) apply((size_t)part);
    markPartsDirty();
}
void Engine::setPartManualOffset(int part, const Vec3& off) {
    FSV_FOR_PARTS(part, ps.manualOffset = off)
    updateCameraSceneLocked();
}
#undef FSV_FOR_PARTS

Vec3 Engine::partManualOffset(int part) const {
    std::lock_guard<std::mutex> lk(mu_);
    if (part < 0 || (size_t)part >= parts_.size()) return Vec3(0, 0, 0);
    return parts_[(size_t)part].manualOffset;
}

Vec3 Engine::partOffset(int part) const {
    std::lock_guard<std::mutex> lk(mu_);
    return partOffsetLocked(part);
}

void Engine::setExplode(float factor) {
    std::lock_guard<std::mutex> lk(mu_);
    explode_ = std::max(0.f, factor);
    markPartsDirty();
    updateCameraSceneLocked();
}

float Engine::explode() const {
    std::lock_guard<std::mutex> lk(mu_);
    return explode_;
}

void Engine::resetPartStates() {
    std::lock_guard<std::mutex> lk(mu_);
    if (!model_) return;
    for (size_t i = 0; i < parts_.size(); ++i) {
        PartState s;
        s.color = model_->parts[i].color | 0xFF000000u;
        s.useVertexColors = model_->parts[i].hasVertexColors;
        parts_[i] = s;
    }
    explode_ = 0.f;
    markPartsDirty();
    updateCameraSceneLocked();
}

std::vector<PartState> Engine::partStates() const {
    std::lock_guard<std::mutex> lk(mu_);
    return parts_;
}

RenderOptions Engine::options() const {
    std::lock_guard<std::mutex> lk(mu_);
    return options_;
}

void Engine::setOptions(const RenderOptions& o) {
    std::lock_guard<std::mutex> lk(mu_);
    options_ = o;
    ++sceneVersion_;
}

Box3 Engine::sceneBounds() const {
    std::lock_guard<std::mutex> lk(mu_);
    return sceneBoundsLocked(true);
}

float Engine::groundZ() const {
    std::lock_guard<std::mutex> lk(mu_);
    Box3 b = sceneBoundsLocked(true);
    return b.valid() ? b.min.z : 0.f;
}

uint64_t Engine::sceneVersion() const {
    std::lock_guard<std::mutex> lk(mu_);
    return sceneVersion_;
}

}  // namespace fsv
