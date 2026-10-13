// Measurement features: what a tap on the model "means" (a corner, an edge, a hole rim, a flat
// face, a cylinder, a sphere or just a point), recovered from the triangle mesh alone.
#pragma once

#include <memory>
#include <vector>

#include "model.h"

namespace fsv {

// Neighbouring triangle across each edge (k, k+1) of every triangle; kNoNeighbor for open and
// non-manifold edges.
struct TriangleAdjacency {
    static constexpr uint32_t kNoNeighbor = 0xFFFFFFFFu;
    std::vector<uint32_t> neighbors;  // 3 per triangle
};
std::unique_ptr<TriangleAdjacency> buildAdjacency(const Model& m, Progress progress);

// Feature edges (sharp edges, or the CAD edges of STEP/IGES models) indexed by welded position
// so connected edges can be followed into chains.
struct EdgeGraph {
    std::vector<uint32_t> segA, segB;    // welded position ids of each segment
    std::vector<uint32_t> segVA, segVB;  // render vertex ids (coordinates, part)
    std::vector<uint32_t> offsets;       // CSR: position -> incident segments
    std::vector<uint32_t> incident;
};
std::unique_ptr<EdgeGraph> buildEdgeGraph(const Model& m, const Topology& topo);

enum class FeatureKind : int {
    None = 0,
    Point = 1,     // somewhere on a free-form surface
    Vertex = 2,    // a corner where edges meet
    Edge = 3,      // a straight or free-form edge
    Circle = 4,    // a round edge: hole rim, fillet boundary, arc
    Plane = 5,     // a flat face
    Cylinder = 6,  // a hole, a shaft, a boss
    Sphere = 7,
};

struct Feature {
    FeatureKind kind = FeatureKind::None;
    int part = -1;
    Vec3 point;   // where the tap snapped to
    Vec3 normal;  // surface normal there (towards the viewer)
    Vec3 center;  // circle/cylinder/sphere centre; plane: area centroid
    Vec3 axis;    // edge direction, circle normal, cylinder axis or plane normal
    Vec3 a, b;    // edge end points; cylinder axis extent
    float radius = 0, length = 0;
    double area = 0;
    float fitError = 0;  // RMS distance of the mesh from the fitted shape
    bool inner = false;  // cylinder/sphere: concave (a hole), surface faces the centre
    bool closed = false; // circle: a full loop rather than an arc
    std::vector<Vec3> outline;  // line list (pairs) showing what was picked

    void translate(const Vec3& off);
};

struct FeatureQuery {
    int part = -1;
    uint32_t triangle = 0;  // global triangle index
    Vec3 point;             // hit point, part-local
    Vec3 normal;            // hit normal (towards the viewer)
    float snapRadius = 1;   // world units: edges and corners this close win over the surface
};

// Works without adjacency or edges too (it then falls back to a plane or a point).
Feature findFeature(const Model& m, const TriangleAdjacency* adj, const EdgeGraph* edges, const FeatureQuery& q);

}  // namespace fsv
