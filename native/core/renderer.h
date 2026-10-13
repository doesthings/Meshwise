// OpenGL ES 3.0 renderer.
#pragma once

#include <string>
#include <unordered_map>
#include <vector>

#include "gl.h"
#include "model.h"

namespace fsv {

enum PartFlag : uint32_t {
    kPartVisible = 1,
    kPartSelected = 2,
    kPartVertexColors = 4,
    kPartHighlight = 8,
    kPartGhost = 16,
    kPartInverted = 32,  // negative volume: triangles wound inside-out
};

struct PartGpu {
    float color[4];
    float offsetFlags[4];
    float material[4];
    float cap[4];
};

enum class ShadingMode : int { Shaded = 0, ThicknessGradient = 1, ThicknessThreshold = 2, Overhang = 3, Normals = 4 };

struct RenderOptions {
    bool grid = true;
    bool shadows = true;
    bool edges = false;
    bool wireframe = false;
    bool axes = false;
    bool openEdges = false;
    int background = 0;
    int lighting = 0;
    float exposure = 1.f;
    ShadingMode mode = ShadingMode::Shaded;
    float scalarMin = 0, scalarMax = 1, threshold = 1;
    float overhangAngle = 45.f;
    bool section = false;
    int sectionAxis = 2;       // 0 x, 1 y, 2 z
    float sectionValue = 0;    // world coordinate along the axis
    bool sectionFlip = false;  // keep the + side instead of the - side
    bool hatch = true;
    bool sectionPlane = true;  // draw the plane outline
    float lineScale = 1.f;     // display density for line widths
    uint32_t selectColor = 0xFFFFB37Fu;
    bool transparentBackground = false;
};

struct FrameParams {
    Mat4 viewProj;
    Vec3 eye, forward, right, up;
    bool ortho = false;
    float worldPerPixel = 1.f;
    Box3 bounds;        // scene bounds including part offsets
    float groundZ = 0;  // floor height
    int width = 1, height = 1;
};

enum class LineSet : int { Feature = 0, Wire = 1, Open = 2, NonManifold = 3, Count = 4 };

class Renderer {
public:
    bool init();
    // Forget GL names after the context was lost (do not delete).
    void invalidate();
    void release();
    bool ready() const { return ready_; }

    void uploadModel(const Model& m);
    void clearModel();
    bool hasModel() const { return indexCount_ > 0; }
    void uploadLines(LineSet set, const std::vector<uint32_t>& lines);
    void uploadScalars(const std::vector<float>& s);
    void clearScalars();
    void updateParts(const std::vector<PartGpu>& parts);
    void markShadowDirty() { shadowDirty_ = true; }

    void render(const FrameParams& f, const RenderOptions& o, GLuint targetFbo);
    // Renders into an offscreen MSAA target and returns top-down RGBA8 pixels.
    bool renderToPixels(const FrameParams& f, const RenderOptions& o, int w, int h, std::vector<uint8_t>& rgba);

    static Vec4 sectionPlaneEquation(const RenderOptions& o);

private:
    struct Program {
        GLuint id = 0;
        std::unordered_map<std::string, GLint> locs;
        GLint loc(const char* name);
    };

    void renderShadowMap(const FrameParams& f);
    void drawMesh(Program& p, const FrameParams& f, const RenderOptions& o, int pass, int capPass = 0);
    void drawSectionDepth(const FrameParams& f, const RenderOptions& o);
    void drawLines(LineSet set, const FrameParams& f, const RenderOptions& o, const float color[4], float width, bool depthTest, float bias);
    void drawGrid(const FrameParams& f, const RenderOptions& o);
    void drawBackground(const RenderOptions& o);
    void drawSectionPlane(const FrameParams& f, const RenderOptions& o);
    void bindPartTex(Program& p, int unit);
    void setLights(Program& p, const FrameParams& f, const RenderOptions& o);

    bool ready_ = false;
    Program mesh_[2];  // [0] = no clip, [1] = clip
    Program depth_, line_, grid_, bg_, plane_;

    GLuint vao_ = 0, vbo_ = 0, ibo_ = 0, scalarVbo_ = 0;
    uint32_t indexCount_ = 0;
    uint32_t vertexCount_ = 0;
    bool hasScalars_ = false;
    GLuint lineVao_[(int)LineSet::Count] = {0, 0, 0, 0};
    GLuint lineIbo_[(int)LineSet::Count] = {0, 0, 0, 0};
    uint32_t lineCount_[(int)LineSet::Count] = {0, 0, 0, 0};

    GLuint partTex_ = 0;
    int partTexWidth_ = 1, partTexHeight_ = 1;
    bool anyGhost_ = false;

    GLuint quadVao_ = 0, quadVbo_ = 0;
    GLuint emptyVao_ = 0;
    GLuint planeVao_ = 0, planeVbo_ = 0;

    GLuint shadowFbo_ = 0, shadowTex_ = 0;
    int shadowSize_ = 2048;
    bool shadowDirty_ = true;
    Mat4 lightViewProj_ = Mat4::identity();
    Box3 shadowBounds_;

    float lineWidthMax_ = 1.f;
    bool stencilWarned_ = false;
};

}  // namespace fsv
