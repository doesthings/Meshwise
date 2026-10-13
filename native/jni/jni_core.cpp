// JNI bridge for libmeshwise.so (io.github.doesthings.meshwise.engine.NativeLib).
#include <android/bitmap.h>
#include <jni.h>
#include <unistd.h>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

#include "analysis.h"
#include "engine.h"
#include "exporters.h"
#include "loaders.h"
#include "jni_strings.h"

using namespace fsv;

#define FSV_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_doesthings_meshwise_engine_NativeLib_##name

namespace {

struct EngineHandle {
    Engine engine;

    // Written by analysis on a background thread, reset from the main thread.
    std::shared_ptr<const Topology> topology() const {
        std::lock_guard<std::mutex> lk(mu);
        return topo;
    }
    void setTopology(std::shared_ptr<const Topology> t) {
        std::lock_guard<std::mutex> lk(mu);
        topo = std::move(t);
    }
    void setThickness(std::shared_ptr<const ThicknessResult> t) {
        std::lock_guard<std::mutex> lk(mu);
        thickness = std::move(t);
    }

private:
    mutable std::mutex mu;
    std::shared_ptr<const Topology> topo;
    std::shared_ptr<const ThicknessResult> thickness;
};

struct ModelHandle {
    std::unique_ptr<Model> model;
};

EngineHandle* E(jlong h) { return reinterpret_cast<EngineHandle*>(h); }
ModelHandle* M(jlong h) { return reinterpret_cast<ModelHandle*>(h); }

std::string str(JNIEnv* env, jstring s) { return fsvjni::toUtf8(env, s); }
jstring newString(JNIEnv* env, const std::string& s) { return fsvjni::newString(env, s); }

void throwJava(JNIEnv* env, const char* cls, const std::string& msg) { fsvjni::throwJava(env, cls, msg); }

// Wraps a Kotlin NativeProgress (fun onProgress(Float): Boolean).
ProgressFn makeProgress(JNIEnv* env, jobject listener) {
    if (!listener) return nullptr;
    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(F)Z");
    if (!mid) { env->ExceptionClear(); return nullptr; }
    return [env, listener, mid](float f) -> bool {
        jboolean keep = env->CallBooleanMethod(listener, mid, (jfloat)f);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep == JNI_TRUE;
    };
}

template <typename F>
auto guarded(JNIEnv* env, F&& fn, decltype(fn()) fallback) -> decltype(fn()) {
    try {
        return fn();
    } catch (const CancelledError&) {
        throwJava(env, "java/util/concurrent/CancellationException", "Cancelled");
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/io/IOException", "Not enough memory to open this model");
    } catch (const std::exception& e) {
        throwJava(env, "java/io/IOException", e.what());
    } catch (...) {
        throwJava(env, "java/io/IOException", "Unexpected error");
    }
    return fallback;
}

jlong loadWith(JNIEnv* env, const MappedFile& file, jstring name, jstring ext, jstring baseDir, jfloat crease,
               jboolean smooth, jobject progress) {
    LoadRequest req;
    req.name = str(env, name);
    req.ext = str(env, ext);
    req.baseDir = str(env, baseDir);
    req.process.creaseAngleDeg = crease;
    req.process.smooth = smooth == JNI_TRUE;
    auto h = std::make_unique<ModelHandle>();
    h->model = loadModel(file, req, makeProgress(env, progress));
    return reinterpret_cast<jlong>(h.release());
}

}  // namespace

// ---------------------------------------------------------------- engine lifecycle

FSV_JNI(jlong, nativeCreate)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new EngineHandle()); }

FSV_JNI(void, nativeDestroy)(JNIEnv*, jclass, jlong h) { delete E(h); }

// GL callbacks can race with teardown; a released engine handle is 0.
FSV_JNI(void, nativeSurfaceCreated)(JNIEnv*, jclass, jlong h) { if (h) E(h)->engine.onSurfaceCreated(); }

FSV_JNI(void, nativeSurfaceChanged)(JNIEnv*, jclass, jlong h, jint w, jint hh) { if (h) E(h)->engine.onSurfaceChanged(w, hh); }

// GL entry points must not let C++ exceptions (e.g. bad_alloc while uploading a huge
// model) unwind into the JVM, which would abort the process.
FSV_JNI(jboolean, nativeDrawFrame)(JNIEnv*, jclass, jlong h) {
    if (!h) return JNI_FALSE;
    try {
        return E(h)->engine.drawFrame() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

static jboolean copyToBitmap(JNIEnv* env, jobject bitmap, const std::vector<uint8_t>& rgba, int w, int h) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if ((int)info.width != w || (int)info.height != h || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    for (int y = 0; y < h; ++y)
        std::memcpy(static_cast<uint8_t*>(pixels) + (size_t)y * info.stride, rgba.data() + (size_t)y * w * 4, (size_t)w * 4);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

FSV_JNI(jboolean, nativeSnapshot)(JNIEnv* env, jclass, jlong h, jint w, jint hh, jboolean transparent, jobject bitmap) {
    if (!h) return JNI_FALSE;
    try {
        std::vector<uint8_t> rgba;
        if (!E(h)->engine.snapshot(w, hh, transparent == JNI_TRUE, rgba)) return JNI_FALSE;
        return copyToBitmap(env, bitmap, rgba, w, hh);
    } catch (...) {
        return JNI_FALSE;
    }
}

FSV_JNI(jboolean, nativeThumbnail)(JNIEnv* env, jclass, jlong h, jint size, jobject bitmap) {
    if (!h) return JNI_FALSE;
    try {
        std::vector<uint8_t> rgba;
        if (!E(h)->engine.thumbnail(size, rgba)) return JNI_FALSE;
        return copyToBitmap(env, bitmap, rgba, size, size);
    } catch (...) {
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------- model loading

FSV_JNI(jlong, nativeLoadFd)(JNIEnv* env, jclass, jint fd, jstring name, jstring ext, jstring baseDir, jfloat crease,
                             jboolean smooth, jobject progress) {
    return guarded(env, [&]() -> jlong {
        auto file = MappedFile::fromFd(fd);
        return loadWith(env, *file, name, ext, baseDir, crease, smooth, progress);
    }, (jlong)0);
}

FSV_JNI(jlong, nativeLoadPath)(JNIEnv* env, jclass, jstring path, jstring name, jstring ext, jstring baseDir,
                               jfloat crease, jboolean smooth, jobject progress) {
    return guarded(env, [&]() -> jlong {
        auto file = MappedFile::fromPath(str(env, path));
        return loadWith(env, *file, name, ext, baseDir, crease, smooth, progress);
    }, (jlong)0);
}

FSV_JNI(jlong, nativeMerge)(JNIEnv* env, jclass, jlongArray handles, jboolean arrange, jstring name) {
    return guarded(env, [&]() -> jlong {
        jsize n = env->GetArrayLength(handles);
        std::vector<jlong> hs((size_t)n);
        env->GetLongArrayRegion(handles, 0, n, hs.data());
        std::vector<std::unique_ptr<Model>> models;
        for (jlong m : hs) {
            if (!m) continue;
            models.push_back(std::move(M(m)->model));
            delete M(m);
        }
        auto out = std::make_unique<ModelHandle>();
        out->model = mergeModels(std::move(models), arrange == JNI_TRUE);
        out->model->name = str(env, name);
        return reinterpret_cast<jlong>(out.release());
    }, (jlong)0);
}

FSV_JNI(void, nativeFreeModel)(JNIEnv*, jclass, jlong m) { delete M(m); }

// [triangles, vertices, parts, fileSize, minx, miny, minz, maxx, maxy, maxz, volume, area, dropped, positions]
FSV_JNI(jdoubleArray, nativeModelStats)(JNIEnv* env, jclass, jlong m) {
    const Model& md = *M(m)->model;
    double v[14] = {(double)md.triangleCount(), (double)md.vertexCount(), (double)md.parts.size(), (double)md.fileSize,
                    md.bounds.min.x, md.bounds.min.y, md.bounds.min.z, md.bounds.max.x, md.bounds.max.y, md.bounds.max.z,
                    md.totalVolume(), md.totalArea(), (double)md.droppedFaces, (double)md.positionCount};
    jdoubleArray arr = env->NewDoubleArray(14);
    env->SetDoubleArrayRegion(arr, 0, 14, v);
    return arr;
}

FSV_JNI(jstring, nativeModelFormat)(JNIEnv* env, jclass, jlong m) { return newString(env, M(m)->model->format); }

FSV_JNI(jobjectArray, nativeModelPartNames)(JNIEnv* env, jclass, jlong m) {
    const Model& md = *M(m)->model;
    jobjectArray arr = env->NewObjectArray((jsize)md.parts.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < md.parts.size(); ++i) {
        jstring s = newString(env, md.parts[i].name);
        env->SetObjectArrayElement(arr, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

FSV_JNI(jobjectArray, nativeModelWarnings)(JNIEnv* env, jclass, jlong m) {
    const Model& md = *M(m)->model;
    jobjectArray arr = env->NewObjectArray((jsize)md.warnings.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < md.warnings.size(); ++i) {
        jstring s = newString(env, md.warnings[i]);
        env->SetObjectArrayElement(arr, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

// Per part: [minx, miny, minz, maxx, maxy, maxz, volume, area, cx, cy, cz, triangles]
FSV_JNI(jfloatArray, nativeModelPartData)(JNIEnv* env, jclass, jlong m) {
    const Model& md = *M(m)->model;
    std::vector<float> v;
    v.reserve(md.parts.size() * 12);
    for (const Part& p : md.parts) {
        v.insert(v.end(), {p.bounds.min.x, p.bounds.min.y, p.bounds.min.z, p.bounds.max.x, p.bounds.max.y, p.bounds.max.z,
                           (float)p.volume, (float)p.area, p.centroid.x, p.centroid.y, p.centroid.z, (float)(p.indexCount / 3)});
    }
    jfloatArray arr = env->NewFloatArray((jsize)v.size());
    env->SetFloatArrayRegion(arr, 0, (jsize)v.size(), v.data());
    return arr;
}

// Per part ARGB colour; flags bit0 = colour from file, bit1 = vertex colours.
FSV_JNI(jintArray, nativeModelPartColors)(JNIEnv* env, jclass, jlong m) {
    const Model& md = *M(m)->model;
    std::vector<jint> v(md.parts.size() * 2);
    for (size_t i = 0; i < md.parts.size(); ++i) {
        v[2 * i] = (jint)rgbaToArgb(md.parts[i].color);
        v[2 * i + 1] = (md.parts[i].hasFileColor ? 1 : 0) | (md.parts[i].hasVertexColors ? 2 : 0);
    }
    jintArray arr = env->NewIntArray((jsize)v.size());
    env->SetIntArrayRegion(arr, 0, (jsize)v.size(), v.data());
    return arr;
}

FSV_JNI(void, nativeSetModel)(JNIEnv*, jclass, jlong h, jlong m, jboolean resetView) {
    if (!h) {
        delete M(m);  // the engine is gone; don't leak the model
        return;
    }
    EngineHandle* eh = E(h);
    eh->setTopology(nullptr);
    eh->setThickness(nullptr);
    if (!m) {
        eh->engine.setModel(nullptr, false);
        return;
    }
    // The engine shares ownership; the Kotlin handle becomes empty.
    std::shared_ptr<const Model> sp(M(m)->model.release());
    delete M(m);
    eh->engine.setModel(sp, resetView == JNI_TRUE);
}

// ---------------------------------------------------------------- analysis

FSV_JNI(jboolean, nativeBuildAnalysis)(JNIEnv* env, jclass, jlong h, jobject progress) {
    if (!h) return JNI_FALSE;
    return guarded(env, [&]() -> jboolean {
        EngineHandle* eh = E(h);
        auto model = eh->engine.model();
        if (!model) return JNI_FALSE;
        Progress p(makeProgress(env, progress));
        auto bvh = std::make_shared<ModelBVH>();
        bvh->build(*model, p.sub(0.f, 0.5f));
        if (!eh->engine.setBVH(bvh, model.get())) return JNI_FALSE;  // model changed meanwhile
        std::shared_ptr<const Topology> topo(computeTopology(*model, 30.f, p.sub(0.5f, 0.8f)).release());
        if (!eh->engine.setTopology(topo, model.get())) return JNI_FALSE;
        eh->setTopology(topo);
        std::shared_ptr<const TriangleAdjacency> adj(buildAdjacency(*model, p.sub(0.8f, 1.f)).release());
        std::shared_ptr<const EdgeGraph> edges(buildEdgeGraph(*model, *topo).release());
        if (!eh->engine.setFeatureData(adj, edges, model.get())) return JNI_FALSE;
        return JNI_TRUE;
    }, (jboolean)JNI_FALSE);
}

// [openEdges, nonManifoldEdges, shells, flippedEdges, degenerateFaces, edges, droppedFaces]
FSV_JNI(jlongArray, nativeMeshCheck)(JNIEnv* env, jclass, jlong h) {
    if (!h) return nullptr;
    EngineHandle* eh = E(h);
    auto model = eh->engine.model();
    auto topo = eh->topology();
    if (!topo || !model) return nullptr;
    const Topology& t = *topo;
    jlong v[7] = {(jlong)(t.openEdges.size() / 2), (jlong)(t.nonManifoldEdges.size() / 2), (jlong)t.shellCount,
                  (jlong)t.flippedEdges, (jlong)t.degenerateFaces, (jlong)t.edgeCount, (jlong)model->droppedFaces};
    jlongArray arr = env->NewLongArray(7);
    env->SetLongArrayRegion(arr, 0, 7, v);
    return arr;
}

// [min, max, p05, p95, spotCount, (x, y, z, nx, ny, nz, value, part) * spotCount]
FSV_JNI(jfloatArray, nativeComputeThickness)(JNIEnv* env, jclass, jlong h, jobject progress) {
    if (!h) return nullptr;
    return guarded(env, [&]() -> jfloatArray {
        EngineHandle* eh = E(h);
        auto model = eh->engine.model();
        auto bvh = eh->engine.bvh();
        if (!model) return nullptr;
        Progress p(makeProgress(env, progress));
        if (!bvh) {
            auto b = std::make_shared<ModelBVH>();
            b->build(*model, p.sub(0.f, 0.3f));
            bvh = b;
            if (!eh->engine.setBVH(bvh, model.get())) return nullptr;
            p = p.sub(0.3f, 1.f);
        }
        auto res = std::make_shared<ThicknessResult>(computeThickness(*model, *bvh, p));
        if (!eh->engine.setScalars(std::shared_ptr<const std::vector<float>>(res, &res->perVertex), model.get())) return nullptr;
        eh->setThickness(res);
        std::vector<float> out = {res->minValue, res->maxValue, res->p05, res->p95, (float)res->thinnest.size()};
        for (const auto& s : res->thinnest)
            out.insert(out.end(), {s.position.x, s.position.y, s.position.z, s.normal.x, s.normal.y, s.normal.z, s.value, (float)s.part});
        jfloatArray arr = env->NewFloatArray((jsize)out.size());
        env->SetFloatArrayRegion(arr, 0, (jsize)out.size(), out.data());
        return arr;
    }, (jfloatArray) nullptr);
}

FSV_JNI(void, nativeClearScalars)(JNIEnv*, jclass, jlong h) {
    if (!h) return;
    E(h)->setThickness(nullptr);
    E(h)->engine.setScalars(nullptr);
}

FSV_JNI(jdoubleArray, nativeOverhang)(JNIEnv* env, jclass, jlong h, jfloat angle) {
    if (!h) return nullptr;
    auto model = E(h)->engine.model();
    if (!model) return nullptr;
    std::vector<uint8_t> vis;
    for (const PartState& s : E(h)->engine.partStates()) vis.push_back(s.visible ? 1 : 0);
    OverhangStats st = computeOverhang(*model, angle, vis);
    double v[2] = {st.overhangArea, st.totalArea};
    jdoubleArray arr = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(arr, 0, 2, v);
    return arr;
}

FSV_JNI(jfloatArray, nativeCircle3)(JNIEnv* env, jclass, jfloatArray pts) {
    float p[9];
    env->GetFloatArrayRegion(pts, 0, 9, p);
    Vec3 c, n;
    float r;
    if (!circleFrom3(Vec3(p[0], p[1], p[2]), Vec3(p[3], p[4], p[5]), Vec3(p[6], p[7], p[8]), c, n, r)) return nullptr;
    float out[7] = {c.x, c.y, c.z, n.x, n.y, n.z, r};
    jfloatArray arr = env->NewFloatArray(7);
    env->SetFloatArrayRegion(arr, 0, 7, out);
    return arr;
}

// ---------------------------------------------------------------- camera

FSV_JNI(void, nativeOrbit)(JNIEnv*, jclass, jlong h, jfloat dx, jfloat dy) { if (h) E(h)->engine.orbit(dx, dy); }
FSV_JNI(void, nativePan)(JNIEnv*, jclass, jlong h, jfloat dx, jfloat dy) { if (h) E(h)->engine.pan(dx, dy); }
FSV_JNI(void, nativeZoom)(JNIEnv*, jclass, jlong h, jfloat f, jfloat x, jfloat y) { if (h) E(h)->engine.zoom(f, x, y); }
FSV_JNI(void, nativeFling)(JNIEnv*, jclass, jlong h, jfloat vx, jfloat vy) { if (h) E(h)->engine.fling(vx, vy); }
FSV_JNI(void, nativeStopMotion)(JNIEnv*, jclass, jlong h) { if (h) E(h)->engine.stopMotion(); }
FSV_JNI(void, nativeSetStandardView)(JNIEnv*, jclass, jlong h, jint v, jboolean a) { if (h) E(h)->engine.setStandardView(v, a == JNI_TRUE); }
FSV_JNI(void, nativeSetOrientation)(JNIEnv*, jclass, jlong h, jfloat yaw, jfloat pitch, jboolean a) {
    if (!h) return;
    E(h)->engine.setOrientation(yaw, pitch, a == JNI_TRUE);
}
FSV_JNI(void, nativeFitView)(JNIEnv*, jclass, jlong h, jboolean a) { if (h) E(h)->engine.fitView(a == JNI_TRUE); }
FSV_JNI(void, nativeFocusPart)(JNIEnv*, jclass, jlong h, jint p, jboolean a) { if (h) E(h)->engine.focusPart(p, a == JNI_TRUE); }
FSV_JNI(void, nativeFocusPoint)(JNIEnv*, jclass, jlong h, jfloat x, jfloat y, jfloat z, jfloat zoom) {
    if (!h) return;
    E(h)->engine.focusPoint(Vec3(x, y, z), zoom);
}
FSV_JNI(void, nativeSetOrtho)(JNIEnv*, jclass, jlong h, jboolean o) { if (h) E(h)->engine.setOrtho(o == JNI_TRUE); }
FSV_JNI(void, nativeSetViewInsets)(JNIEnv*, jclass, jlong h, jfloat top, jfloat bottom, jboolean animate) {
    if (!h) return;
    E(h)->engine.setViewInsets(top, bottom, animate == JNI_TRUE);
}
FSV_JNI(void, nativeSetTurntable)(JNIEnv*, jclass, jlong h, jboolean o) { if (h) E(h)->engine.setTurntable(o == JNI_TRUE); }
FSV_JNI(void, nativeSetSensitivity)(JNIEnv*, jclass, jlong h, jfloat s) { if (h) E(h)->engine.setSensitivity(s); }
FSV_JNI(void, nativeSetTurntableSpeed)(JNIEnv*, jclass, jlong h, jfloat s) { if (h) E(h)->engine.setTurntableSpeed(s); }
FSV_JNI(void, nativeSetFov)(JNIEnv*, jclass, jlong h, jfloat deg) { if (h) E(h)->engine.setFov(deg * kPi / 180.f); }

// out[0..15] view-projection, [16..17] yaw/pitch, [18..19] viewport. Returns a change counter.
FSV_JNI(jlong, nativeCameraMatrices)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    if (!h) return 0;
    float buf[20];
    uint64_t v = E(h)->engine.cameraMatrices(buf, buf + 16, buf + 18);
    env->SetFloatArrayRegion(out, 0, 20, buf);
    return (jlong)v;
}

// [tx, ty, tz, distance, yaw, pitch, ortho]
FSV_JNI(jfloatArray, nativeGetCamera)(JNIEnv* env, jclass, jlong h) {
    CameraState s = h ? E(h)->engine.cameraState() : CameraState{};
    float v[7] = {s.target.x, s.target.y, s.target.z, s.distance, s.yaw, s.pitch, s.ortho ? 1.f : 0.f};
    jfloatArray arr = env->NewFloatArray(7);
    env->SetFloatArrayRegion(arr, 0, 7, v);
    return arr;
}

FSV_JNI(void, nativeSetCamera)(JNIEnv* env, jclass, jlong h, jfloatArray a, jboolean animate) {
    if (!h) return;
    float v[7];
    env->GetFloatArrayRegion(a, 0, 7, v);
    CameraState s;
    s.target = Vec3(v[0], v[1], v[2]);
    s.distance = v[3];
    s.yaw = v[4];
    s.pitch = v[5];
    s.ortho = v[6] > 0.5f;
    E(h)->engine.setCameraState(s, animate == JNI_TRUE);
}

// ---------------------------------------------------------------- picking

// out: [x, y, z, nx, ny, nz, onCap]
FSV_JNI(jint, nativePick)(JNIEnv* env, jclass, jlong h, jfloat x, jfloat y, jfloatArray out) {
    if (!h) return -1;
    PickResult r = E(h)->engine.pick(x, y);
    if (!r.hit) return -1;
    float v[7] = {r.point.x, r.point.y, r.point.z, r.normal.x, r.normal.y, r.normal.z, r.onCap ? 1.f : 0.f};
    env->SetFloatArrayRegion(out, 0, 7, v);
    return r.part;
}

// [kind, part, point(3), normal(3), center(3), axis(3), a(3), b(3), radius, length, area, fitError,
//  inner, closed, segmentCount, segments (6 floats each)]; null when nothing was hit.
FSV_JNI(jfloatArray, nativeMeasurePick)(JNIEnv* env, jclass, jlong h, jfloat x, jfloat y, jfloat snapPx) {
    if (!h) return nullptr;
    return guarded(env, [&]() -> jfloatArray {
        Feature f = E(h)->engine.measurePick(x, y, snapPx);
        if (f.kind == FeatureKind::None) return nullptr;
        const size_t nSeg = f.outline.size() / 2;
        std::vector<float> v;
        v.reserve(27 + nSeg * 6);
        auto put = [&](const Vec3& p) { v.push_back(p.x); v.push_back(p.y); v.push_back(p.z); };
        v.push_back((float)f.kind);
        v.push_back((float)f.part);
        put(f.point); put(f.normal); put(f.center); put(f.axis); put(f.a); put(f.b);
        v.push_back(f.radius);
        v.push_back(f.length);
        v.push_back((float)f.area);
        v.push_back(f.fitError);
        v.push_back(f.inner ? 1.f : 0.f);
        v.push_back(f.closed ? 1.f : 0.f);
        v.push_back((float)nSeg);
        for (const Vec3& p : f.outline) put(p);
        jfloatArray arr = env->NewFloatArray((jsize)v.size());
        env->SetFloatArrayRegion(arr, 0, (jsize)v.size(), v.data());
        return arr;
    }, (jfloatArray) nullptr);
}

// ---------------------------------------------------------------- part state

FSV_JNI(void, nativeSetPartColor)(JNIEnv*, jclass, jlong h, jint p, jint argb) { if (h) E(h)->engine.setPartColor(p, argbToRgba((uint32_t)argb)); }
FSV_JNI(void, nativeSetPartVisible)(JNIEnv*, jclass, jlong h, jint p, jboolean v) { if (h) E(h)->engine.setPartVisible(p, v == JNI_TRUE); }
FSV_JNI(void, nativeSetPartSelected)(JNIEnv*, jclass, jlong h, jint p, jboolean v) { if (h) E(h)->engine.setPartSelected(p, v == JNI_TRUE); }
FSV_JNI(void, nativeSetPartHighlight)(JNIEnv*, jclass, jlong h, jint p, jboolean v) { if (h) E(h)->engine.setPartHighlight(p, v == JNI_TRUE); }
FSV_JNI(void, nativeSetPartGhost)(JNIEnv*, jclass, jlong h, jint p, jboolean v) { if (h) E(h)->engine.setPartGhost(p, v == JNI_TRUE); }
FSV_JNI(void, nativeSetPartMaterial)(JNIEnv*, jclass, jlong h, jint p, jfloat r, jfloat m, jfloat c) {
    if (!h) return;
    E(h)->engine.setPartMaterial(p, r, m, c);
}
FSV_JNI(void, nativeSetPartVertexColors)(JNIEnv*, jclass, jlong h, jint p, jboolean v) { if (h) E(h)->engine.setPartVertexColors(p, v == JNI_TRUE); }
FSV_JNI(void, nativeSetPartOffset)(JNIEnv*, jclass, jlong h, jint p, jfloat x, jfloat y, jfloat z) {
    if (!h) return;
    E(h)->engine.setPartManualOffset(p, Vec3(x, y, z));
}
// Total offset (explode + manual) of a part.
FSV_JNI(void, nativeGetPartOffset)(JNIEnv* env, jclass, jlong h, jint p, jfloatArray out) {
    if (!h) return;
    Vec3 total = E(h)->engine.partOffset(p);
    Vec3 manual = E(h)->engine.partManualOffset(p);
    float v[6] = {total.x, total.y, total.z, manual.x, manual.y, manual.z};
    env->SetFloatArrayRegion(out, 0, 6, v);
}
FSV_JNI(void, nativeSetExplode)(JNIEnv*, jclass, jlong h, jfloat f) { if (h) E(h)->engine.setExplode(f); }
FSV_JNI(void, nativeResetParts)(JNIEnv*, jclass, jlong h) { if (h) E(h)->engine.resetPartStates(); }

// ---------------------------------------------------------------- options

// ints:   [grid, shadows, edges, wireframe, axes, openEdges, background, lighting, mode, section,
//          sectionAxis, sectionFlip, hatch, sectionPlane, selectColorArgb]
// floats: [exposure, scalarMin, scalarMax, threshold, overhangAngle, sectionValue, lineScale]
FSV_JNI(void, nativeSetOptions)(JNIEnv* env, jclass, jlong h, jintArray ints, jfloatArray floats) {
    if (!h) return;
    jint iv[17];
    jfloat fv[7];
    env->GetIntArrayRegion(ints, 0, 17, iv);
    env->GetFloatArrayRegion(floats, 0, 7, fv);
    RenderOptions o;
    o.grid = iv[0] != 0;
    o.shadows = iv[1] != 0;
    o.edges = iv[2] != 0;
    o.wireframe = iv[3] != 0;
    o.axes = iv[4] != 0;
    o.openEdges = iv[5] != 0;
    o.background = iv[6];
    o.lighting = iv[7];
    o.mode = (ShadingMode)iv[8];
    o.section = iv[9] != 0;
    o.sectionAxis = iv[10];
    o.sectionFlip = iv[11] != 0;
    o.hatch = iv[12] != 0;
    o.sectionPlane = iv[13] != 0;
    o.selectColor = argbToRgba((uint32_t)iv[14]);
    o.bgTop = (uint32_t)iv[15];
    o.bgBottom = (uint32_t)iv[16];
    o.exposure = fv[0];
    o.scalarMin = fv[1];
    o.scalarMax = fv[2];
    o.threshold = fv[3];
    o.overhangAngle = fv[4];
    o.sectionValue = fv[5];
    o.lineScale = fv[6];
    E(h)->engine.setOptions(o);
}

FSV_JNI(void, nativeSceneBounds)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    if (!h) return;
    Box3 b = E(h)->engine.sceneBounds();
    float v[6] = {b.min.x, b.min.y, b.min.z, b.max.x, b.max.y, b.max.z};
    if (!b.valid()) for (float& f : v) f = 0;
    env->SetFloatArrayRegion(out, 0, 6, v);
}

// ---------------------------------------------------------------- export

// format: 0 STL, 1 OBJ, 2 3MF, 3 FSM. partMask may be null (all parts).
FSV_JNI(jboolean, nativeExport)(JNIEnv* env, jclass, jlong h, jint fd, jint format, jbooleanArray partMask,
                                jboolean applyOffsets) {
    if (!h) return JNI_FALSE;
    return guarded(env, [&]() -> jboolean {
        EngineHandle* eh = E(h);
        auto model = eh->engine.model();
        if (!model) return JNI_FALSE;
        ExportOptions eo;
        auto states = eh->engine.partStates();
        if (partMask) {
            jsize n = env->GetArrayLength(partMask);
            std::vector<jboolean> mask((size_t)n);
            env->GetBooleanArrayRegion(partMask, 0, n, mask.data());
            for (jboolean b : mask) eo.partMask.push_back(b ? 1 : 0);
        }
        for (size_t i = 0; i < states.size(); ++i) {
            eo.partColors.push_back(states[i].color);
            if (applyOffsets == JNI_TRUE) eo.partOffsets.push_back(eh->engine.partOffset((int)i));
        }
        std::vector<uint8_t> bytes;
        switch (format) {
            case 0: bytes = exportSTL(*model, eo); break;
            case 1: bytes = exportOBJ(*model, eo); break;
            case 2: bytes = export3MF(*model, eo); break;
            default: bytes = exportFSM(*model, eo); break;
        }
        if (!writeAll(fd, bytes)) throw std::runtime_error("Could not write the file");
        return JNI_TRUE;
    }, (jboolean)JNI_FALSE);
}
