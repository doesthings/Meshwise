package io.github.doesthings.meshwise.engine

import android.graphics.Bitmap

/** Progress callback invoked from native code. Return false to cancel. */
fun interface NativeProgress {
    fun onProgress(fraction: Float): Boolean
}

/** JNI surface of libmeshwise.so. See native/jni/jni_core.cpp for the contracts. */
object NativeLib {
    init {
        System.loadLibrary("meshwise")
    }

    // Engine lifecycle and GL thread.
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(h: Long)
    @JvmStatic external fun nativeSurfaceCreated(h: Long)
    @JvmStatic external fun nativeSurfaceChanged(h: Long, w: Int, height: Int)
    @JvmStatic external fun nativeDrawFrame(h: Long): Boolean
    @JvmStatic external fun nativeSnapshot(h: Long, w: Int, height: Int, transparent: Boolean, bitmap: Bitmap): Boolean
    @JvmStatic external fun nativeThumbnail(h: Long, size: Int, bitmap: Bitmap): Boolean

    // Loading.
    @JvmStatic external fun nativeLoadFd(
        fd: Int, name: String, ext: String, baseDir: String?, crease: Float, smooth: Boolean, progress: NativeProgress?,
    ): Long
    @JvmStatic external fun nativeLoadPath(
        path: String, name: String, ext: String, baseDir: String?, crease: Float, smooth: Boolean, progress: NativeProgress?,
    ): Long
    @JvmStatic external fun nativeMerge(handles: LongArray, arrange: Boolean, name: String): Long
    @JvmStatic external fun nativeFreeModel(m: Long)
    @JvmStatic external fun nativeModelStats(m: Long): DoubleArray
    @JvmStatic external fun nativeModelFormat(m: Long): String
    @JvmStatic external fun nativeModelPartNames(m: Long): Array<String>
    @JvmStatic external fun nativeModelWarnings(m: Long): Array<String>
    @JvmStatic external fun nativeModelPartData(m: Long): FloatArray
    @JvmStatic external fun nativeModelPartColors(m: Long): IntArray
    @JvmStatic external fun nativeSetModel(h: Long, m: Long, resetView: Boolean)

    // Analysis.
    @JvmStatic external fun nativeBuildAnalysis(h: Long, progress: NativeProgress?): Boolean
    @JvmStatic external fun nativeMeshCheck(h: Long): LongArray?
    @JvmStatic external fun nativeComputeThickness(h: Long, progress: NativeProgress?): FloatArray?
    @JvmStatic external fun nativeClearScalars(h: Long)
    @JvmStatic external fun nativeOverhang(h: Long, angle: Float): DoubleArray?
    @JvmStatic external fun nativeCircle3(points: FloatArray): FloatArray?

    // Camera.
    @JvmStatic external fun nativeOrbit(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativePan(h: Long, dx: Float, dy: Float)
    @JvmStatic external fun nativeZoom(h: Long, factor: Float, x: Float, y: Float)
    @JvmStatic external fun nativeFling(h: Long, vx: Float, vy: Float)
    @JvmStatic external fun nativeStopMotion(h: Long)
    @JvmStatic external fun nativeSetStandardView(h: Long, view: Int, animate: Boolean)
    @JvmStatic external fun nativeSetOrientation(h: Long, yaw: Float, pitch: Float, animate: Boolean)
    @JvmStatic external fun nativeFitView(h: Long, animate: Boolean)
    @JvmStatic external fun nativeFocusPart(h: Long, part: Int, animate: Boolean)
    @JvmStatic external fun nativeFocusPoint(h: Long, x: Float, y: Float, z: Float, zoom: Float)
    @JvmStatic external fun nativeSetOrtho(h: Long, ortho: Boolean)
    @JvmStatic external fun nativeSetViewInsets(h: Long, top: Float, bottom: Float, animate: Boolean)
    @JvmStatic external fun nativeSetTurntable(h: Long, on: Boolean)
    @JvmStatic external fun nativeSetSensitivity(h: Long, s: Float)
    @JvmStatic external fun nativeMeasurePick(h: Long, x: Float, y: Float, snapPx: Float): FloatArray?
    @JvmStatic external fun nativeSetTurntableSpeed(h: Long, radPerSec: Float)
    @JvmStatic external fun nativeSetFov(h: Long, degrees: Float)
    @JvmStatic external fun nativeCameraMatrices(h: Long, out: FloatArray): Long
    @JvmStatic external fun nativeGetCamera(h: Long): FloatArray
    @JvmStatic external fun nativeSetCamera(h: Long, state: FloatArray, animate: Boolean)

    // Picking.
    @JvmStatic external fun nativePick(h: Long, x: Float, y: Float, out: FloatArray): Int

    // Parts.
    @JvmStatic external fun nativeSetPartColor(h: Long, part: Int, argb: Int)
    @JvmStatic external fun nativeSetPartVisible(h: Long, part: Int, visible: Boolean)
    @JvmStatic external fun nativeSetPartSelected(h: Long, part: Int, selected: Boolean)
    @JvmStatic external fun nativeSetPartHighlight(h: Long, part: Int, on: Boolean)
    @JvmStatic external fun nativeSetPartGhost(h: Long, part: Int, ghost: Boolean)
    @JvmStatic external fun nativeSetPartMaterial(h: Long, part: Int, rough: Float, metal: Float, clearcoat: Float)
    @JvmStatic external fun nativeSetPartVertexColors(h: Long, part: Int, use: Boolean)
    @JvmStatic external fun nativeSetPartOffset(h: Long, part: Int, x: Float, y: Float, z: Float)
    @JvmStatic external fun nativeGetPartOffset(h: Long, part: Int, out: FloatArray)
    @JvmStatic external fun nativeSetExplode(h: Long, factor: Float)
    @JvmStatic external fun nativeResetParts(h: Long)

    // Options and export.
    @JvmStatic external fun nativeSetOptions(h: Long, ints: IntArray, floats: FloatArray)
    @JvmStatic external fun nativeSceneBounds(h: Long, out: FloatArray)
    @JvmStatic external fun nativeExport(h: Long, fd: Int, format: Int, partMask: BooleanArray?, applyOffsets: Boolean): Boolean
}
