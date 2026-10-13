// In-memory model representation shared by loaders, analysis and renderer.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "vecmath.h"

namespace fsv {

// Progress callback: receives a fraction in [0,1]; returns false to cancel.
using ProgressFn = std::function<bool(float)>;

struct CancelledError : std::runtime_error {
    CancelledError() : std::runtime_error("cancelled") {}
};

// Maps a sub-range of work onto a parent progress callback and throttles calls.
class Progress {
public:
    explicit Progress(ProgressFn fn = nullptr, float begin = 0.f, float end = 1.f)
        : fn_(std::move(fn)), begin_(begin), end_(end) {}
    // Reports fraction f of this range. Throws CancelledError when cancelled.
    void report(float f) {
        if (!fn_) return;
        float v = begin_ + (end_ - begin_) * std::clamp(f, 0.f, 1.f);
        if (v - last_ < 0.005f && f < 1.f) return;
        last_ = v;
        if (!fn_(v)) throw CancelledError();
    }
    Progress sub(float b, float e) const {
        return Progress(fn_, begin_ + (end_ - begin_) * b, begin_ + (end_ - begin_) * e);
    }

private:
    ProgressFn fn_;
    float begin_, end_;
    float last_ = -1.f;
};

// Geometry of one part as produced by a loader, before processing.
struct RawPart {
    std::string name;
    uint32_t color = 0;                  // RGBA; 0 = not specified by the file
    std::vector<float> positions;        // xyz triplets
    std::vector<uint32_t> indices;       // triangle list; empty => positions is a triangle soup
    std::vector<uint32_t> faceColors;    // optional, one RGBA per triangle
    std::vector<uint32_t> vertexColors;  // optional, one RGBA per position
    std::vector<float> normals;          // optional, one normal per position (CAD tessellation)
    std::vector<uint32_t> edges;         // optional line list of position indices (CAD edges)
    bool welded = false;                 // positions already unique (skip welding)
};

struct RawModel {
    std::vector<RawPart> parts;
    std::string format;
    std::vector<std::string> warnings;
};

struct Part {
    std::string name;
    uint32_t color = 0;            // RGBA display colour
    bool hasFileColor = false;     // colour came from the file
    bool hasVertexColors = false;  // per-vertex/per-face colours present
    uint32_t firstIndex = 0, indexCount = 0;    // into Model::indices
    uint32_t firstVertex = 0, vertexCount = 0;  // into render vertex arrays
    Box3 bounds;
    double volume = 0;   // signed, mm^3
    double area = 0;     // mm^2
    Vec3 centroid;       // volume centroid (falls back to bounds centre)
};

struct ProcessOptions {
    float creaseAngleDeg = 35.f;  // >= 180 => fully smooth, 0 => flat
    bool smooth = true;
};

// Edge topology computed after loading (feature edges, open edges, shells).
struct Topology {
    std::vector<uint32_t> featureEdges;      // line list (render vertex indices)
    std::vector<uint32_t> openEdges;         // boundary edges (line list)
    std::vector<uint32_t> nonManifoldEdges;  // edges shared by >2 faces (line list)
    std::vector<uint32_t> allEdges;          // every edge once (wireframe); empty for huge meshes
    uint64_t edgeCount = 0;
    uint32_t shellCount = 0;
    uint64_t degenerateFaces = 0;
    uint64_t flippedEdges = 0;  // edges whose two faces disagree on orientation
};

struct Model {
    std::string name;
    std::string format;
    uint64_t fileSize = 0;
    std::vector<Part> parts;
    std::vector<std::string> warnings;

    // Render vertices (structure of arrays).
    std::vector<float> positions;    // 3 per vertex
    std::vector<float> normals;      // 3 per vertex
    std::vector<uint32_t> colors;    // RGBA per vertex; empty if no part has vertex colours
    std::vector<uint16_t> partIds;   // per vertex
    std::vector<uint32_t> indices;   // triangle list
    std::vector<uint32_t> posIds;    // render vertex -> welded position id (global)
    std::vector<uint32_t> cadEdges;  // optional CAD edge line list (render vertex indices)
    uint32_t positionCount = 0;      // number of distinct welded positions
    uint64_t droppedFaces = 0;       // degenerate/invalid faces removed while loading

    Box3 bounds;

    size_t vertexCount() const { return partIds.size(); }
    size_t triangleCount() const { return indices.size() / 3; }
    Vec3 vertex(uint32_t i) const { return {positions[3 * i], positions[3 * i + 1], positions[3 * i + 2]}; }
    Vec3 normal(uint32_t i) const { return {normals[3 * i], normals[3 * i + 1], normals[3 * i + 2]}; }
    double totalVolume() const;
    double totalArea() const;
};

// Converts raw loader output into a renderable model (welding, crease-aware normals,
// per-part statistics). Throws std::runtime_error on empty/invalid input.
std::unique_ptr<Model> buildModel(RawModel&& raw, const ProcessOptions& opt, Progress progress);

// Merges several models into one (each part keeps its identity). When `arrange` is true and
// the parts overlap heavily (typical for a ZIP of separately exported STLs) they are laid
// out side by side on the XY plane.
std::unique_ptr<Model> mergeModels(std::vector<std::unique_ptr<Model>>&& models, bool arrange);

// Computes edge topology. `featureAngleDeg` controls which edges are drawn as feature lines.
std::unique_ptr<Topology> computeTopology(const Model& m, float featureAngleDeg, Progress progress);

// Default palette used for parts without a colour.
uint32_t defaultPartColor(size_t index, size_t count);

}  // namespace fsv
