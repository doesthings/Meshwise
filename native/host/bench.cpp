// fsv_bench: load + process timing for large models.  fsv_bench model.stl [...]
#include <chrono>
#include <cstdio>
#include <string>

#include "analysis.h"
#include "bvh.h"
#include "loaders.h"

using namespace fsv;
using Clock = std::chrono::steady_clock;

static double ms(Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); }

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        std::string path = argv[i];
        auto t0 = Clock::now();
        auto f = MappedFile::fromPath(path);
        LoadRequest req;
        req.name = path;
        size_t dot = path.find_last_of('.');
        req.ext = dot == std::string::npos ? "" : path.substr(dot + 1);
        auto m = loadModel(*f, req, nullptr);
        auto t1 = Clock::now();
        auto topo = computeTopology(*m, 30.f, Progress());
        auto t2 = Clock::now();
        ModelBVH bvh;
        bvh.build(*m, Progress());
        auto t3 = Clock::now();
        auto th = computeThickness(*m, bvh, Progress());
        auto t4 = Clock::now();
        std::printf("%s: %.1f MB, %zu tris, %zu verts, %zu parts\n", path.c_str(), f->size() / 1048576.0, m->triangleCount(),
                    m->vertexCount(), m->parts.size());
        std::printf("  load+process %.0f ms | topology %.0f ms | bvh %.0f ms | thickness %.0f ms (min %.3f)\n", ms(t0, t1),
                    ms(t1, t2), ms(t2, t3), ms(t3, t4), th.minValue);
        std::printf("  open edges %zu, shells %u\n", topo->openEdges.size() / 2, topo->shellCount);
    }
    return 0;
}
