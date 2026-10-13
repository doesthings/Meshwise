// JNI entry points of libmeshwise_cad.so (runs inside the isolated ":cad" process).
#include <jni.h>

#include <stdexcept>
#include <string>

#include "cad_import.h"
#include "jni_strings.h"

namespace {

std::string toString(JNIEnv* env, jstring s) { return fsvjni::toUtf8(env, s); }

void throwJava(JNIEnv* env, const char* cls, const std::string& msg) { fsvjni::throwJava(env, cls, msg); }

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_doesthings_meshwise_cad_CadNative_importCad(JNIEnv* env, jclass, jstring jin, jstring jext,
                                                                  jstring jout, jdouble relDefl, jdouble angDefl,
                                                                  jobject callback) {
    std::string in = toString(env, jin), ext = toString(env, jext), out = toString(env, jout);
    jmethodID onProgress = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        onProgress = env->GetMethodID(cls, "onProgress", "(F)Z");
    }
    std::function<bool(float)> progress = [&](float f) -> bool {
        if (!callback || !onProgress) return true;
        jboolean keep = env->CallBooleanMethod(callback, onProgress, (jfloat)f);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };
    try {
        fsvcad::CadOptions opt;
        opt.relativeDeflection = relDefl > 0 ? relDefl : opt.relativeDeflection;
        opt.angularDeflection = angDefl > 0 ? angDefl : opt.angularDeflection;
        fsvcad::CadResult r = fsvcad::importCad(in, ext, out, opt, progress);
        jlongArray arr = env->NewLongArray(2);
        jlong vals[2] = {(jlong)r.parts, (jlong)r.triangles};
        env->SetLongArrayRegion(arr, 0, 2, vals);
        return arr;
    } catch (const std::exception& e) {
        std::string msg = e.what();
        if (msg == "cancelled") throwJava(env, "java/util/concurrent/CancellationException", "Import cancelled");
        else throwJava(env, "java/io/IOException", msg);
    } catch (...) {
        throwJava(env, "java/io/IOException", "The CAD kernel failed to import this file");
    }
    return nullptr;
}
