#pragma once

#include <GLES3/gl3.h>

#ifdef FSV_ANDROID
#include <android/log.h>
#define FSV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Meshwise", __VA_ARGS__)
#define FSV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Meshwise", __VA_ARGS__)
#else
#include <cstdio>
#define FSV_LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#define FSV_LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif
