#include "renderer.h"

#include <cstring>

#include "shaders.h"

namespace fsv {

namespace {

struct Light { Vec3 dir; Vec3 color; };
struct LightingPreset {
    Light lights[3];
    Vec3 sky, ground;
    float env;
};

// Directions are in view space (x right, y up, z towards the viewer).
const LightingPreset kLighting[] = {
    // Studio
    {{{Vec3(-0.50f, 0.70f, 0.55f), Vec3(1.55f, 1.50f, 1.42f)},
      {Vec3(0.75f, 0.15f, 0.55f), Vec3(0.40f, 0.44f, 0.52f)},
      {Vec3(0.20f, 0.50f, -0.85f), Vec3(1.00f, 1.00f, 1.05f)}},
     Vec3(0.30f, 0.32f, 0.36f), Vec3(0.11f, 0.10f, 0.10f), 0.75f},
    // Soft
    {{{Vec3(-0.30f, 0.60f, 0.75f), Vec3(1.45f, 1.42f, 1.38f)},
      {Vec3(0.60f, 0.20f, 0.70f), Vec3(0.85f, 0.88f, 0.95f)},
      {Vec3(0.00f, 0.60f, -0.80f), Vec3(0.55f, 0.55f, 0.58f)}},
     Vec3(0.62f, 0.64f, 0.68f), Vec3(0.32f, 0.30f, 0.28f), 0.85f},
    // Dramatic
    {{{Vec3(-0.85f, 0.45f, 0.30f), Vec3(3.40f, 3.20f, 2.90f)},
      {Vec3(0.80f, -0.10f, 0.40f), Vec3(0.18f, 0.22f, 0.30f)},
      {Vec3(0.35f, 0.60f, -0.75f), Vec3(2.30f, 2.35f, 2.50f)}},
     Vec3(0.13f, 0.14f, 0.17f), Vec3(0.04f, 0.04f, 0.04f), 0.75f},
    // Flat / technical
    {{{Vec3(0.00f, 0.20f, 1.00f), Vec3(0.95f, 0.95f, 0.95f)},
      {Vec3(0.00f, 1.00f, 0.00f), Vec3(0.10f, 0.10f, 0.10f)},
      {Vec3(1.00f, 0.00f, 0.00f), Vec3(0.00f, 0.00f, 0.00f)}},
     Vec3(0.78f, 0.78f, 0.80f), Vec3(0.55f, 0.55f, 0.56f), 0.25f},
    // Headlight
    {{{Vec3(0.05f, 0.12f, 1.00f), Vec3(2.10f, 2.05f, 2.00f)},
      {Vec3(0.00f, 1.00f, 0.25f), Vec3(0.40f, 0.42f, 0.46f)},
      {Vec3(0.00f, 0.30f, -1.00f), Vec3(0.60f, 0.60f, 0.62f)}},
     Vec3(0.30f, 0.31f, 0.34f), Vec3(0.12f, 0.12f, 0.12f), 0.85f},
};
constexpr int kLightingCount = sizeof(kLighting) / sizeof(kLighting[0]);

struct BackgroundPreset { Vec3 top, bottom; bool dark; float vignette; };
// Colours in display (sRGB) space.
const BackgroundPreset kBackgrounds[] = {
    {Vec3(0.235f, 0.298f, 0.333f), Vec3(0.133f, 0.169f, 0.192f), true, 0.35f},   // Slate
    {Vec3(0.165f, 0.165f, 0.180f), Vec3(0.071f, 0.071f, 0.078f), true, 0.30f},   // Graphite
    {Vec3(0.965f, 0.969f, 0.976f), Vec3(0.820f, 0.839f, 0.863f), false, 0.15f},  // Light
    {Vec3(0.270f, 0.360f, 0.560f), Vec3(0.090f, 0.130f, 0.230f), true, 0.35f},   // Ocean
    {Vec3(0.000f, 0.000f, 0.000f), Vec3(0.000f, 0.000f, 0.000f), true, 0.0f},    // Black
    {Vec3(1.000f, 1.000f, 1.000f), Vec3(1.000f, 1.000f, 1.000f), false, 0.0f},   // White
};
constexpr int kBackgroundCount = sizeof(kBackgrounds) / sizeof(kBackgrounds[0]);

uint32_t packNormal(float x, float y, float z) {
    auto q = [](float v) {
        int i = (int)std::lround(std::clamp(v, -1.f, 1.f) * 511.f);
        return (uint32_t)(i & 1023);
    };
    return q(x) | (q(y) << 10) | (q(z) << 20);
}

Vec3 viewToWorld(const FrameParams& f, const Vec3& d) {
    return normalize(f.right * d.x + f.up * d.y - f.forward * d.z);
}

}  // namespace

GLint Renderer::Program::loc(const char* name) {
    auto it = locs.find(name);
    if (it != locs.end()) return it->second;
    GLint l = glGetUniformLocation(id, name);
    locs.emplace(name, l);
    return l;
}

bool Renderer::init() {
    ready_ = false;
    mesh_[0] = Program{buildProgram(shaders::kMeshVS, shaders::kMeshFS, "", "mesh"), {}};
    mesh_[1] = Program{buildProgram(shaders::kMeshVS, shaders::kMeshFS, "#define CLIP 1", "mesh-clip"), {}};
    depth_ = Program{buildProgram(shaders::kDepthVS, shaders::kDepthFS, "", "depth"), {}};
    line_ = Program{buildProgram(shaders::kLineVS, shaders::kLineFS, "", "line"), {}};
    grid_ = Program{buildProgram(shaders::kGridVS, shaders::kGridFS, "", "grid"), {}};
    bg_ = Program{buildProgram(shaders::kBackgroundVS, shaders::kBackgroundFS, "", "background"), {}};
    plane_ = Program{buildProgram(shaders::kPlaneVS, shaders::kPlaneFS, "", "plane"), {}};
    if (!mesh_[0].id || !mesh_[1].id || !depth_.id || !line_.id || !grid_.id || !bg_.id || !plane_.id) return false;

    glGenVertexArrays(1, &emptyVao_);

    // Unit quad for the grid.
    const float quad[] = {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1};
    glGenVertexArrays(1, &quadVao_);
    glGenBuffers(1, &quadVbo_);
    glBindVertexArray(quadVao_);
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 8, nullptr);

    glGenVertexArrays(1, &planeVao_);
    glGenBuffers(1, &planeVbo_);
    glBindVertexArray(planeVao_);
    glBindBuffer(GL_ARRAY_BUFFER, planeVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 3 * 8, nullptr, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glBindVertexArray(0);

    // Shadow map.
    GLint maxTex = 2048;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    shadowSize_ = std::min(2048, (int)maxTex);
    glGenTextures(1, &shadowTex_);
    glBindTexture(GL_TEXTURE_2D, shadowTex_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, shadowSize_, shadowSize_, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
    glGenFramebuffers(1, &shadowFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex_, 0);
    GLenum none = GL_NONE;
    glDrawBuffers(1, &none);
    glReadBuffer(GL_NONE);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        FSV_LOGE("shadow framebuffer incomplete");
        glDeleteFramebuffers(1, &shadowFbo_);
        shadowFbo_ = 0;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    glGenTextures(1, &partTex_);
    glBindTexture(GL_TEXTURE_2D, partTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    float zero[4] = {0, 0, 0, 0};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, zero);
    partTexWidth_ = partTexHeight_ = 1;

    GLfloat range[2] = {1, 1};
    glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE, range);
    lineWidthMax_ = range[1];
    shadowDirty_ = true;
    ready_ = true;
    return true;
}

void Renderer::invalidate() {
    *this = Renderer();
}

void Renderer::release() {
    clearModel();
    for (Program* p : {&mesh_[0], &mesh_[1], &depth_, &line_, &grid_, &bg_, &plane_})
        if (p->id) glDeleteProgram(p->id);
    GLuint vaos[] = {emptyVao_, quadVao_, planeVao_};
    glDeleteVertexArrays(3, vaos);
    GLuint bufs[] = {quadVbo_, planeVbo_};
    glDeleteBuffers(2, bufs);
    if (shadowFbo_) glDeleteFramebuffers(1, &shadowFbo_);
    if (shadowTex_) glDeleteTextures(1, &shadowTex_);
    if (partTex_) glDeleteTextures(1, &partTex_);
    *this = Renderer();
}

void Renderer::clearModel() {
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (ibo_) glDeleteBuffers(1, &ibo_);
    if (scalarVbo_) glDeleteBuffers(1, &scalarVbo_);
    for (int i = 0; i < (int)LineSet::Count; ++i) {
        if (lineVao_[i]) glDeleteVertexArrays(1, &lineVao_[i]);
        if (lineIbo_[i]) glDeleteBuffers(1, &lineIbo_[i]);
        lineVao_[i] = lineIbo_[i] = 0;
        lineCount_[i] = 0;
    }
    vao_ = vbo_ = ibo_ = scalarVbo_ = 0;
    indexCount_ = vertexCount_ = 0;
    hasScalars_ = false;
    shadowDirty_ = true;
}

void Renderer::uploadModel(const Model& m) {
    clearModel();
    const size_t nv = m.vertexCount();
    vertexCount_ = (uint32_t)nv;
    struct V { float p[3]; uint32_t n; uint32_t c; uint16_t part; uint16_t pad; };
    static_assert(sizeof(V) == 24, "vertex layout");
    std::vector<V> verts(nv);
    const bool colors = !m.colors.empty();
    for (size_t i = 0; i < nv; ++i) {
        V& v = verts[i];
        v.p[0] = m.positions[3 * i];
        v.p[1] = m.positions[3 * i + 1];
        v.p[2] = m.positions[3 * i + 2];
        v.n = packNormal(m.normals[3 * i], m.normals[3 * i + 1], m.normals[3 * i + 2]);
        v.c = colors ? m.colors[i] : 0xFFFFFFFFu;
        v.part = m.partIds[i];
        v.pad = 0;
    }
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glGenBuffers(1, &ibo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(verts.size() * sizeof(V)), verts.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(V), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_INT_2_10_10_10_REV, GL_TRUE, sizeof(V), (void*)12);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(V), (void*)16);
    glEnableVertexAttribArray(3);
    glVertexAttribIPointer(3, 1, GL_UNSIGNED_SHORT, sizeof(V), (void*)20);
    glDisableVertexAttribArray(4);
    glVertexAttrib1f(4, 0.f);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, (GLsizeiptr)(m.indices.size() * 4), m.indices.data(), GL_STATIC_DRAW);
    glBindVertexArray(0);
    indexCount_ = (uint32_t)m.indices.size();

    // Line VAOs share the vertex buffer.
    for (int i = 0; i < (int)LineSet::Count; ++i) {
        glGenVertexArrays(1, &lineVao_[i]);
        glGenBuffers(1, &lineIbo_[i]);
        glBindVertexArray(lineVao_[i]);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(V), (void*)0);
        glEnableVertexAttribArray(3);
        glVertexAttribIPointer(3, 1, GL_UNSIGNED_SHORT, sizeof(V), (void*)20);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, lineIbo_[i]);
    }
    glBindVertexArray(0);
    shadowDirty_ = true;
}

void Renderer::uploadLines(LineSet set, const std::vector<uint32_t>& lines) {
    int i = (int)set;
    if (!lineVao_[i]) return;
    glBindVertexArray(lineVao_[i]);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, lineIbo_[i]);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, (GLsizeiptr)(lines.size() * 4), lines.empty() ? nullptr : lines.data(), GL_STATIC_DRAW);
    glBindVertexArray(0);
    lineCount_[i] = (uint32_t)lines.size();
}

void Renderer::uploadScalars(const std::vector<float>& s) {
    if (!vao_ || s.size() != vertexCount_) return;
    if (!scalarVbo_) glGenBuffers(1, &scalarVbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, scalarVbo_);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(s.size() * 4), s.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, 4, nullptr);
    glBindVertexArray(0);
    hasScalars_ = true;
}

void Renderer::clearScalars() {
    if (!vao_) return;
    glBindVertexArray(vao_);
    glDisableVertexAttribArray(4);
    glVertexAttrib1f(4, 0.f);
    glBindVertexArray(0);
    if (scalarVbo_) glDeleteBuffers(1, &scalarVbo_);
    scalarVbo_ = 0;
    hasScalars_ = false;
}

void Renderer::updateParts(const std::vector<PartGpu>& parts) {
    const int texels = std::max(1, (int)parts.size() * 4);
    int w = std::min(texels, 1024);
    int h = (texels + w - 1) / w;
    std::vector<float> data((size_t)w * h * 4, 0.f);
    if (!parts.empty()) std::memcpy(data.data(), parts.data(), parts.size() * sizeof(PartGpu));
    glBindTexture(GL_TEXTURE_2D, partTex_);
    if (w != partTexWidth_ || h != partTexHeight_) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, w, h, 0, GL_RGBA, GL_FLOAT, data.data());
        partTexWidth_ = w;
        partTexHeight_ = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_FLOAT, data.data());
    }
    anyGhost_ = false;
    for (const PartGpu& p : parts)
        if (((uint32_t)(p.offsetFlags[3] + 0.5f) & kPartGhost) && ((uint32_t)(p.offsetFlags[3] + 0.5f) & kPartVisible)) {
            anyGhost_ = true;
            break;
        }
    shadowDirty_ = true;
}

void Renderer::bindPartTex(Program& p, int unit) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, partTex_);
    glUniform1i(p.loc("uPartTex"), unit);
    glUniform1i(p.loc("uPartTexWidth"), partTexWidth_);
}

Vec4 Renderer::sectionPlaneEquation(const RenderOptions& o) {
    Vec3 n(0, 0, 0);
    n[o.sectionAxis] = 1.f;
    float w = -o.sectionValue;
    if (o.sectionFlip) { n = -n; w = -w; }
    return Vec4(n, w);
}

void Renderer::renderShadowMap(const FrameParams& f) {
    if (!shadowFbo_ || !indexCount_) return;
    Vec3 c = f.bounds.center();
    float r = std::max(1e-3f, f.bounds.radius());
    Vec3 ls = normalize(Vec3(0.35f, 0.25f, 1.0f));
    Mat4 view = Mat4::lookAt(c + ls * (r * 2.f), c, Vec3(0, 1, 0));
    // Cover the floor area under the model as well.
    float ext = r * 1.6f;
    Mat4 proj = Mat4::ortho(-ext, ext, -ext, ext, 0.01f * r, r * 5.f);
    lightViewProj_ = proj * view;

    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, shadowSize_, shadowSize_);
    glDepthMask(GL_TRUE);
    glClear(GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(2.f, 4.f);
    glUseProgram(depth_.id);
    glUniformMatrix4fv(depth_.loc("uViewProj"), 1, GL_FALSE, lightViewProj_.m);
    bindPartTex(depth_, 0);
    glBindVertexArray(vao_);
    glDrawElements(GL_TRIANGLES, (GLsizei)indexCount_, GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
    glDisable(GL_POLYGON_OFFSET_FILL);
    shadowDirty_ = false;
}

void Renderer::setLights(Program& p, const FrameParams& f, const RenderOptions& o) {
    const LightingPreset& lp = kLighting[std::clamp(o.lighting, 0, kLightingCount - 1)];
    float dirs[9], cols[9];
    for (int i = 0; i < 3; ++i) {
        Vec3 d = viewToWorld(f, normalize(lp.lights[i].dir));
        dirs[3 * i] = d.x; dirs[3 * i + 1] = d.y; dirs[3 * i + 2] = d.z;
        cols[3 * i] = lp.lights[i].color.x; cols[3 * i + 1] = lp.lights[i].color.y; cols[3 * i + 2] = lp.lights[i].color.z;
    }
    glUniform3fv(p.loc("uLightDir"), 3, dirs);
    glUniform3fv(p.loc("uLightColor"), 3, cols);
    glUniform3f(p.loc("uSkyColor"), lp.sky.x, lp.sky.y, lp.sky.z);
    glUniform3f(p.loc("uGroundColor"), lp.ground.x, lp.ground.y, lp.ground.z);
    glUniform1f(p.loc("uEnvStrength"), lp.env);
}

void Renderer::drawMesh(Program& p, const FrameParams& f, const RenderOptions& o, int pass, int capPass) {
    glUseProgram(p.id);
    glUniformMatrix4fv(p.loc("uViewProj"), 1, GL_FALSE, f.viewProj.m);
    glUniform3f(p.loc("uCamPos"), f.eye.x, f.eye.y, f.eye.z);
    glUniform3f(p.loc("uCamForward"), f.forward.x, f.forward.y, f.forward.z);
    glUniform3f(p.loc("uCamRight"), f.right.x, f.right.y, f.right.z);
    glUniform3f(p.loc("uCamUp"), f.up.x, f.up.y, f.up.z);
    glUniform1i(p.loc("uOrtho"), f.ortho ? 1 : 0);
    setLights(p, f, o);
    glUniform1f(p.loc("uExposure"), o.exposure);
    int mode = (int)o.mode;
    if ((mode == 1 || mode == 2) && !hasScalars_) mode = 0;
    glUniform1i(p.loc("uMode"), mode);
    glUniform4f(p.loc("uScalarRange"), o.scalarMin, o.scalarMax, o.threshold, 0.f);
    glUniform1f(p.loc("uOverhangSin"), std::sin(o.overhangAngle * kPi / 180.f));
    glUniform1f(p.loc("uBedZ"), f.groundZ + std::max(1e-3f, f.bounds.radius() * 2e-4f));
    glUniform3f(p.loc("uSelectColor"), colR(o.selectColor), colG(o.selectColor), colB(o.selectColor));
    glUniform1i(p.loc("uPass"), pass);
    Vec4 plane = sectionPlaneEquation(o);
    glUniform4f(p.loc("uClipPlane"), plane.x, plane.y, plane.z, plane.w);
    glUniform1f(p.loc("uHatchSpacing"), std::max({1e-4f, f.bounds.radius() * 0.02f, f.worldPerPixel * 9.f}));
    glUniform1i(p.loc("uHatch"), o.hatch ? 1 : 0);
    glUniform1i(p.loc("uCapPass"), capPass);
    bindPartTex(p, 0);
    glBindVertexArray(vao_);
    glDrawElements(GL_TRIANGLES, (GLsizei)indexCount_, GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
}

void Renderer::drawLines(LineSet set, const FrameParams& f, const RenderOptions& o, const float color[4], float width,
                         bool depthTest, float bias) {
    int i = (int)set;
    if (!lineCount_[i]) return;
    glUseProgram(line_.id);
    glUniformMatrix4fv(line_.loc("uViewProj"), 1, GL_FALSE, f.viewProj.m);
    glUniform4fv(line_.loc("uColor"), 1, color);
    glUniform1f(line_.loc("uDepthBias"), bias);
    Vec4 plane = sectionPlaneEquation(o);
    glUniform4f(line_.loc("uClipPlane"), plane.x, plane.y, plane.z, plane.w);
    glUniform1i(line_.loc("uClip"), o.section ? 1 : 0);
    bindPartTex(line_, 0);
    glLineWidth(std::clamp(width, 1.f, lineWidthMax_));
    if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glBindVertexArray(lineVao_[i]);
    glDrawElements(GL_LINES, (GLsizei)lineCount_[i], GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);
    glLineWidth(1.f);
}

void Renderer::drawGrid(const FrameParams& f, const RenderOptions& o) {
    const BackgroundPreset& bg = kBackgrounds[std::clamp(o.background, 0, kBackgroundCount - 1)];
    Vec3 c = f.bounds.center();
    float r = std::max(1e-3f, f.bounds.radius());
    float extent = std::max(r * 6.f, length(f.eye - c) * 1.5f);
    float wpp = std::max(f.worldPerPixel, 1e-6f);
    // Minor step: smallest power of ten that is at least ~6 px at the target depth.
    float step = std::pow(10.f, std::ceil(std::log10(wpp * 6.f)));
    // ...but never coarser than the model: keep at least a few cells across it.
    while (step > r * 0.5f && step > 1e-4f) step *= 0.1f;

    glUseProgram(grid_.id);
    glUniformMatrix4fv(grid_.loc("uViewProj"), 1, GL_FALSE, f.viewProj.m);
    // Snap the centre to the major grid so lines stay put when panning.
    float major = step * 10.f;
    Vec3 gc(std::round(c.x / major) * major, std::round(c.y / major) * major, f.groundZ - r * 0.0008f);
    glUniform3f(grid_.loc("uCenter"), gc.x, gc.y, gc.z);
    glUniform1f(grid_.loc("uExtent"), extent);
    glUniform1f(grid_.loc("uStep"), step);
    if (bg.dark) {
        glUniform3f(grid_.loc("uLineColor"), 0.82f, 0.88f, 0.92f);
        glUniform1f(grid_.loc("uLineAlpha"), 0.30f);
        glUniform3f(grid_.loc("uFloorColor"), 1.f, 1.f, 1.f);
        glUniform1f(grid_.loc("uFloorAlpha"), 0.035f);
        glUniform1f(grid_.loc("uShadowStrength"), 0.55f);
    } else {
        glUniform3f(grid_.loc("uLineColor"), 0.10f, 0.12f, 0.15f);
        glUniform1f(grid_.loc("uLineAlpha"), 0.22f);
        glUniform3f(grid_.loc("uFloorColor"), 0.f, 0.f, 0.f);
        glUniform1f(grid_.loc("uFloorAlpha"), 0.025f);
        glUniform1f(grid_.loc("uShadowStrength"), 0.38f);
    }
    glUniform1i(grid_.loc("uShadows"), (o.shadows && shadowFbo_) ? 1 : 0);
    glUniformMatrix4fv(grid_.loc("uLightViewProj"), 1, GL_FALSE, lightViewProj_.m);
    glUniform1f(grid_.loc("uShadowTexel"), 1.f / (float)shadowSize_);
    glUniform3f(grid_.loc("uCamPos"), f.eye.x, f.eye.y, f.eye.z);
    glUniform1i(grid_.loc("uAxes"), o.axes ? 1 : 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, shadowTex_);
    glUniform1i(grid_.loc("uShadowMap"), 1);
    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBindVertexArray(0);
    glActiveTexture(GL_TEXTURE0);
}

void Renderer::drawBackground(const RenderOptions& o) {
    const BackgroundPreset& bg = kBackgrounds[std::clamp(o.background, 0, kBackgroundCount - 1)];
    glUseProgram(bg_.id);
    glUniform3f(bg_.loc("uTop"), bg.top.x, bg.top.y, bg.top.z);
    glUniform3f(bg_.loc("uBottom"), bg.bottom.x, bg.bottom.y, bg.bottom.z);
    glUniform1f(bg_.loc("uVignette"), bg.vignette);
    glBindVertexArray(emptyVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
}

void Renderer::drawSectionDepth(const FrameParams& f, const RenderOptions& o) {
    GLint stencilBits = 0;
    glGetIntegerv(GL_STENCIL_BITS, &stencilBits);
    if (stencilBits <= 0) {
        if (!stencilWarned_) { FSV_LOGI("no stencil buffer: section caps keep interior depth"); stencilWarned_ = true; }
        return;
    }
    const Box3& b = f.bounds;
    if (!b.valid()) return;
    int a = std::clamp(o.sectionAxis, 0, 2);
    int u = (a + 1) % 3, v = (a + 2) % 3;
    Vec3 pad = b.size() * 0.25f + Vec3(1e-3f, 1e-3f, 1e-3f);
    float uv[4][2] = {{b.min[u] - pad[u], b.min[v] - pad[v]}, {b.max[u] + pad[u], b.min[v] - pad[v]},
                      {b.max[u] + pad[u], b.max[v] + pad[v]}, {b.min[u] - pad[u], b.max[v] + pad[v]}};
    const int tri[6] = {0, 1, 2, 0, 2, 3};
    float data[18];
    for (int i = 0; i < 6; ++i) {
        Vec3 c;
        c[a] = o.sectionValue;
        c[u] = uv[tri[i]][0];
        c[v] = uv[tri[i]][1];
        std::memcpy(&data[3 * i], &c, 12);
    }
    glBindBuffer(GL_ARRAY_BUFFER, planeVbo_);
    glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(data), data);
    glUseProgram(plane_.id);
    glUniformMatrix4fv(plane_.loc("uViewProj"), 1, GL_FALSE, f.viewProj.m);
    glUniform4f(plane_.loc("uColor"), 0, 0, 0, 0);
    glStencilFunc(GL_EQUAL, 1, 0xFF);
    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
    glDepthFunc(GL_ALWAYS);
    glDepthMask(GL_TRUE);
    glBindVertexArray(planeVao_);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBindVertexArray(0);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDepthFunc(GL_LESS);
}

void Renderer::drawSectionPlane(const FrameParams& f, const RenderOptions& o) {
    const Box3& b = f.bounds;
    if (!b.valid()) return;
    int a = std::clamp(o.sectionAxis, 0, 2);
    int u = (a + 1) % 3, v = (a + 2) % 3;
    Vec3 pad = b.size() * 0.06f;
    float umin = b.min[u] - pad[u], umax = b.max[u] + pad[u];
    float vmin = b.min[v] - pad[v], vmax = b.max[v] + pad[v];
    Vec3 corners[4];
    float uv[4][2] = {{umin, vmin}, {umax, vmin}, {umax, vmax}, {umin, vmax}};
    for (int i = 0; i < 4; ++i) {
        corners[i][a] = o.sectionValue;
        corners[i][u] = uv[i][0];
        corners[i][v] = uv[i][1];
    }
    float data[24];
    // Two triangles (fill) followed by the outline loop.
    const int tri[6] = {0, 1, 2, 0, 2, 3};
    for (int i = 0; i < 6; ++i) std::memcpy(&data[3 * i], &corners[tri[i]], 12);
    glBindBuffer(GL_ARRAY_BUFFER, planeVbo_);
    glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(float) * 18, data);
    float loop[12];
    for (int i = 0; i < 4; ++i) std::memcpy(&loop[3 * i], &corners[i], 12);
    glUseProgram(plane_.id);
    glUniformMatrix4fv(plane_.loc("uViewProj"), 1, GL_FALSE, f.viewProj.m);
    glBindVertexArray(planeVao_);
    glUniform4f(plane_.loc("uColor"), colR(o.selectColor), colG(o.selectColor), colB(o.selectColor), 0.035f);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(loop), loop);
    glUniform4f(plane_.loc("uColor"), colR(o.selectColor), colG(o.selectColor), colB(o.selectColor), 0.75f);
    glLineWidth(std::clamp(1.5f * o.lineScale, 1.f, lineWidthMax_));
    glDrawArrays(GL_LINE_LOOP, 0, 4);
    glLineWidth(1.f);
    glBindVertexArray(0);
}

void Renderer::render(const FrameParams& f, const RenderOptions& o, GLuint targetFbo) {
    if (!ready_) return;
    if (o.shadows && shadowDirty_ && indexCount_) renderShadowMap(f);
    else if (shadowDirty_ && !o.shadows) { /* render lazily when re-enabled */ }

    glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
    glViewport(0, 0, f.width, f.height);
    glDepthMask(GL_TRUE);
    glStencilMask(0xFF);
    glClearColor(0, 0, 0, o.transparentBackground ? 0.f : 1.f);
    glClearDepthf(1.f);
    glClearStencil(0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);

    if (!o.transparentBackground) {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(GL_FALSE);
        drawBackground(o);
    }

    const bool lines = indexCount_ && (o.edges || o.wireframe);
    if (indexCount_) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(GL_TRUE);
        if (lines) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1.f, 1.f);
        }
        if (!o.section) {
            drawMesh(mesh_[0], f, o, 0);
        } else {
            // 1) outside faces, 2) interior faces seen through the cut drawn as caps and marked
            // in the stencil buffer, 3) plane depth written on the cap pixels.
            drawMesh(mesh_[1], f, o, 0, 0);
            glEnable(GL_STENCIL_TEST);
            glStencilMask(0xFF);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            drawMesh(mesh_[1], f, o, 0, 1);
            drawSectionDepth(f, o);
            glDisable(GL_STENCIL_TEST);
        }
        glDisable(GL_POLYGON_OFFSET_FILL);
    }

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    const BackgroundPreset& bg = kBackgrounds[std::clamp(o.background, 0, kBackgroundCount - 1)];
    if (lines) {
        if (o.wireframe) {
            float c[4] = {bg.dark ? 0.08f : 0.05f, bg.dark ? 0.09f : 0.06f, bg.dark ? 0.10f : 0.07f, 0.45f};
            drawLines(LineSet::Wire, f, o, c, 1.f * o.lineScale, true, 0.f);
        }
        if (o.edges) {
            float c[4] = {0.03f, 0.035f, 0.04f, 0.85f};
            drawLines(LineSet::Feature, f, o, c, 1.25f * o.lineScale, true, 0.f);
        }
    }
    if (o.grid) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        drawGrid(f, o);
    }
    if (indexCount_ && anyGhost_) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        drawMesh(mesh_[o.section ? 1 : 0], f, o, 1);
    }
    if (indexCount_ && o.openEdges) {
        float red[4] = {1.f, 0.18f, 0.15f, 0.95f};
        float orange[4] = {1.f, 0.62f, 0.1f, 0.95f};
        drawLines(LineSet::Open, f, o, red, 2.5f * o.lineScale, false, 0.f);
        drawLines(LineSet::NonManifold, f, o, orange, 2.5f * o.lineScale, false, 0.f);
    }
    if (o.section && o.sectionPlane) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        drawSectionPlane(f, o);
    }
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
}

bool Renderer::renderToPixels(const FrameParams& f, const RenderOptions& o, int w, int h, std::vector<uint8_t>& rgba) {
    if (!ready_ || w <= 0 || h <= 0) return false;
    GLint maxSamples = 0;
    glGetIntegerv(GL_MAX_SAMPLES, &maxSamples);
    GLint maxRb = 4096;
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxRb);
    if (w > maxRb || h > maxRb) return false;

    // Large multisampled targets can exceed GPU memory on low-end devices: on failure,
    // try again without MSAA.
    for (GLsizei samples = std::min(4, (int)maxSamples);; samples = 0) {
        while (glGetError() != GL_NO_ERROR) {
        }
        GLuint msFbo = 0, msColor = 0, msDepth = 0, resolveFbo = 0, resolveColor = 0;
        glGenFramebuffers(1, &msFbo);
        glGenRenderbuffers(1, &msColor);
        glGenRenderbuffers(1, &msDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, msColor);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, msDepth);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msColor);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, msDepth);
        bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

        glGenFramebuffers(1, &resolveFbo);
        glGenRenderbuffers(1, &resolveColor);
        glBindRenderbuffer(GL_RENDERBUFFER, resolveColor);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, resolveColor);
        ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;

        if (ok) {
            FrameParams ff = f;
            ff.width = w;
            ff.height = h;
            render(ff, o, msFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
            std::vector<uint8_t> tmp((size_t)w * h * 4);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, tmp.data());
            ok = glGetError() == GL_NO_ERROR;
            if (ok) {
                rgba.resize(tmp.size());
                const size_t row = (size_t)w * 4;
                for (int y = 0; y < h; ++y) std::memcpy(&rgba[(size_t)y * row], &tmp[(size_t)(h - 1 - y) * row], row);
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &msFbo);
        glDeleteFramebuffers(1, &resolveFbo);
        GLuint rbs[] = {msColor, msDepth, resolveColor};
        glDeleteRenderbuffers(3, rbs);
        if (ok || samples == 0) return ok;
    }
}

}  // namespace fsv
