#pragma once

#include <string>

#include "gl.h"

namespace fsv {

namespace shaders {
extern const char* kMeshVS;
extern const char* kMeshFS;
extern const char* kDepthVS;
extern const char* kDepthFS;
extern const char* kLineVS;
extern const char* kLineFS;
extern const char* kGridVS;
extern const char* kGridFS;
extern const char* kBackgroundVS;
extern const char* kBackgroundFS;
extern const char* kPlaneVS;
extern const char* kPlaneFS;
}  // namespace shaders

// Compiles and links a program; `defines` is inserted after the #version line.
GLuint buildProgram(const char* vs, const char* fs, const std::string& defines, const char* debugName);

}  // namespace fsv
