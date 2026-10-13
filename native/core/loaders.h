// Model file loaders.
#pragma once

#include <memory>
#include <string>

#include "mapped_file.h"
#include "model.h"

namespace fsv {

enum class Format { Unknown, STL, OBJ, PLY, OFF, ThreeMF, FSM };

Format formatFromExtension(const std::string& ext);
// Content sniffing for files with a missing/unknown extension.
Format sniffFormat(const uint8_t* data, size_t size);
const char* formatName(Format f);

RawModel loadSTL(const uint8_t* data, size_t size, Progress progress);
RawModel loadOBJ(const uint8_t* data, size_t size, const std::string& baseDir, Progress progress);
RawModel loadPLY(const uint8_t* data, size_t size, Progress progress);
RawModel loadOFF(const uint8_t* data, size_t size, Progress progress);
RawModel load3MF(const uint8_t* data, size_t size, Progress progress);
RawModel loadFSM(const uint8_t* data, size_t size, Progress progress);

struct LoadRequest {
    std::string name;      // display name (file name)
    std::string ext;       // lower-case extension hint without dot
    std::string baseDir;   // directory for side files (OBJ materials); may be empty
    ProcessOptions process;
};

// Parses and processes a model. Throws std::runtime_error with a user-readable message.
std::unique_ptr<Model> loadModel(const MappedFile& file, const LoadRequest& req, ProgressFn progress);

}  // namespace fsv
