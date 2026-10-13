// Desktop stubs for the NDK bitmap API (see host/stubs/android/bitmap.h).
#include <android/bitmap.h>

extern "C" int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*) { return -1; }
extern "C" int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**) { return -1; }
extern "C" int AndroidBitmap_unlockPixels(JNIEnv*, jobject) { return -1; }
