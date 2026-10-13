// Minimal stand-in for the NDK's <android/bitmap.h> so the JNI bridge can be built for the
// desktop JVM (Robolectric unit tests). Bitmap access is not available there.
#pragma once
#include <jni.h>
#include <stdint.h>

#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1

typedef struct {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    int32_t format;
    uint32_t flags;
} AndroidBitmapInfo;

#ifdef __cplusplus
extern "C" {
#endif
int AndroidBitmap_getInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info);
int AndroidBitmap_lockPixels(JNIEnv* env, jobject bitmap, void** addrPtr);
int AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap);
#ifdef __cplusplus
}
#endif
