package io.github.doesthings.meshwise.engine

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kotlin facade over the native engine. Methods may be called from any thread; GL work
 * (surface callbacks, drawFrame, snapshot, thumbnail) must run on the GL thread.
 */
class Engine {
    @Volatile
    var handle: Long = NativeLib.nativeCreate()
        private set

    fun release() {
        val h = handle
        handle = 0
        if (h != 0L) NativeLib.nativeDestroy(h)
    }

    // ---- GL thread
    fun onSurfaceCreated() = NativeLib.nativeSurfaceCreated(handle)
    fun onSurfaceChanged(w: Int, h: Int) = NativeLib.nativeSurfaceChanged(handle, w, h)
    fun drawFrame(): Boolean = NativeLib.nativeDrawFrame(handle)

    fun snapshot(w: Int, h: Int, transparent: Boolean): Bitmap? {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return if (NativeLib.nativeSnapshot(handle, w, h, transparent, bmp)) bmp else { bmp.recycle(); null }
    }

    fun thumbnail(size: Int): Bitmap? {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return if (NativeLib.nativeThumbnail(handle, size, bmp)) bmp else { bmp.recycle(); null }
    }

    // ---- model
    /** Hands a loaded model to the engine (the handle is consumed). */
    fun setModel(modelHandle: Long, resetView: Boolean) = NativeLib.nativeSetModel(handle, modelHandle, resetView)
    fun clearModel() = NativeLib.nativeSetModel(handle, 0, false)

    fun buildAnalysis(progress: NativeProgress?): Boolean = NativeLib.nativeBuildAnalysis(handle, progress)

    fun meshCheck(): MeshCheck? = NativeLib.nativeMeshCheck(handle)?.let {
        MeshCheck(it[0], it[1], it[2], it[3], it[4], it[5], it[6])
    }

    fun computeThickness(progress: NativeProgress?): ThicknessResult? {
        val r = NativeLib.nativeComputeThickness(handle, progress) ?: return null
        val n = r[4].toInt()
        val spots = (0 until n).map { i ->
            val o = 5 + i * 8
            ThinSpot(Vec3(r[o], r[o + 1], r[o + 2]), Vec3(r[o + 3], r[o + 4], r[o + 5]), r[o + 6], r[o + 7].toInt())
        }
        return ThicknessResult(r[0], r[1], r[2], r[3], spots)
    }

    fun clearScalars() = NativeLib.nativeClearScalars(handle)

    fun overhang(angle: Float): Pair<Double, Double>? = NativeLib.nativeOverhang(handle, angle)?.let { it[0] to it[1] }

    // ---- camera
    fun orbit(dx: Float, dy: Float) = NativeLib.nativeOrbit(handle, dx, dy)
    fun pan(dx: Float, dy: Float) = NativeLib.nativePan(handle, dx, dy)
    fun zoom(factor: Float, x: Float, y: Float) = NativeLib.nativeZoom(handle, factor, x, y)
    fun fling(vx: Float, vy: Float) = NativeLib.nativeFling(handle, vx, vy)
    fun stopMotion() = NativeLib.nativeStopMotion(handle)
    fun setStandardView(view: StandardView, animate: Boolean = true) = NativeLib.nativeSetStandardView(handle, view.id, animate)
    fun setOrientation(yaw: Float, pitch: Float, animate: Boolean = true) = NativeLib.nativeSetOrientation(handle, yaw, pitch, animate)
    fun fitView(animate: Boolean = true) = NativeLib.nativeFitView(handle, animate)
    fun focusPart(part: Int, animate: Boolean = true) = NativeLib.nativeFocusPart(handle, part, animate)
    fun focusPoint(p: Vec3, zoom: Float) = NativeLib.nativeFocusPoint(handle, p.x, p.y, p.z, zoom)
    fun setOrtho(ortho: Boolean) = NativeLib.nativeSetOrtho(handle, ortho)
    fun setViewInsets(top: Float, bottom: Float, animate: Boolean = true) = NativeLib.nativeSetViewInsets(handle, top, bottom, animate)
    fun setTurntable(on: Boolean) = NativeLib.nativeSetTurntable(handle, on)
    fun setSensitivity(s: Float) = NativeLib.nativeSetSensitivity(handle, s)
    fun setTurntableSpeed(radPerSec: Float) = NativeLib.nativeSetTurntableSpeed(handle, radPerSec)
    fun setFov(degrees: Float) = NativeLib.nativeSetFov(handle, degrees)

    fun camera(): CameraState = NativeLib.nativeGetCamera(handle).let {
        CameraState(Vec3(it[0], it[1], it[2]), it[3], it[4], it[5], it[6] > 0.5f)
    }

    fun setCamera(s: CameraState, animate: Boolean) = NativeLib.nativeSetCamera(
        handle, floatArrayOf(s.target.x, s.target.y, s.target.z, s.distance, s.yaw, s.pitch, if (s.ortho) 1f else 0f), animate,
    )

    private val matBuf = FloatArray(20)

    /** Returns the current camera matrices for overlays. */
    @Synchronized
    fun cameraFrame(): CameraFrame {
        val v = NativeLib.nativeCameraMatrices(handle, matBuf)
        return CameraFrame(matBuf.copyOfRange(0, 16), matBuf[16], matBuf[17], matBuf[18], matBuf[19], v)
    }

    private val pickBuf = FloatArray(7)

    @Synchronized
    fun pick(x: Float, y: Float): PickHit? {
        val part = NativeLib.nativePick(handle, x, y, pickBuf)
        if (part < 0) return null
        return PickHit(part, Vec3(pickBuf[0], pickBuf[1], pickBuf[2]), Vec3(pickBuf[3], pickBuf[4], pickBuf[5]), pickBuf[6] > 0.5f)
    }

    /** What a measuring tap at (x, y) picks: a corner, edge, hole, face… or null for a miss. */
    fun measurePick(x: Float, y: Float, snapPx: Float): MeasureFeature? =
        NativeLib.nativeMeasurePick(handle, x, y, snapPx)?.let(MeasureFeature::parse)

    // ---- parts
    fun setPartColor(part: Int, argb: Int) = NativeLib.nativeSetPartColor(handle, part, argb)
    fun setPartVisible(part: Int, v: Boolean) = NativeLib.nativeSetPartVisible(handle, part, v)
    fun setPartSelected(part: Int, s: Boolean) = NativeLib.nativeSetPartSelected(handle, part, s)
    fun setPartHighlight(part: Int, on: Boolean) = NativeLib.nativeSetPartHighlight(handle, part, on)
    fun setPartGhost(part: Int, g: Boolean) = NativeLib.nativeSetPartGhost(handle, part, g)
    fun setPartMaterial(part: Int, m: MaterialPreset) = NativeLib.nativeSetPartMaterial(handle, part, m.roughness, m.metalness, m.clearcoat)
    fun setPartVertexColors(part: Int, use: Boolean) = NativeLib.nativeSetPartVertexColors(handle, part, use)
    fun setPartOffset(part: Int, o: Vec3) = NativeLib.nativeSetPartOffset(handle, part, o.x, o.y, o.z)

    private val offBuf = FloatArray(6)

    /** Returns (total offset, manual offset) of a part. */
    @Synchronized
    fun partOffset(part: Int): Pair<Vec3, Vec3> {
        NativeLib.nativeGetPartOffset(handle, part, offBuf)
        return Vec3(offBuf[0], offBuf[1], offBuf[2]) to Vec3(offBuf[3], offBuf[4], offBuf[5])
    }

    fun setExplode(f: Float) = NativeLib.nativeSetExplode(handle, f)
    fun resetParts() = NativeLib.nativeResetParts(handle)

    fun setOptions(o: RenderOptions) = NativeLib.nativeSetOptions(handle, o.ints(), o.floats())

    fun sceneBounds(): Bounds {
        val b = FloatArray(6)
        NativeLib.nativeSceneBounds(handle, b)
        return Bounds.of(b)
    }

    /** Writes the current model to [fd]. Format: 0 STL, 1 OBJ, 2 3MF, 3 FSM. */
    fun export(fd: Int, format: ExportFormat, partMask: BooleanArray?, applyOffsets: Boolean): Boolean =
        NativeLib.nativeExport(handle, fd, format.id, partMask, applyOffsets)

    companion object {
        fun circleThrough(a: Vec3, b: Vec3, c: Vec3): Triple<Vec3, Vec3, Float>? {
            val r = NativeLib.nativeCircle3(floatArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z)) ?: return null
            return Triple(Vec3(r[0], r[1], r[2]), Vec3(r[3], r[4], r[5]), r[6])
        }
    }
}

enum class StandardView(val id: Int) { Front(0), Back(1), Left(2), Right(3), Top(4), Bottom(5), Iso(6) }

enum class ExportFormat(val id: Int, val extension: String, val mime: String) {
    STL(0, "stl", "model/stl"),
    OBJ(1, "obj", "model/obj"),
    ThreeMF(2, "3mf", "model/3mf"),
    FSM(3, "fsm", "application/octet-stream"),
}

data class CameraState(val target: Vec3, val distance: Float, val yaw: Float, val pitch: Float, val ortho: Boolean)

/** Camera snapshot used by Compose overlays to project world points to pixels. */
class CameraFrame(
    val viewProj: FloatArray,
    val yaw: Float,
    val pitch: Float,
    val width: Float,
    val height: Float,
    val version: Long,
) {
    /** Projects a world point to view pixels; null when behind the camera. */
    fun project(p: Vec3): Offset? {
        val m = viewProj
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        if (w <= 1e-6f) return null
        return Offset((x / w * 0.5f + 0.5f) * width, (1f - (y / w * 0.5f + 0.5f)) * height)
    }

    /** Camera axes derived from yaw/pitch (Z up turntable). */
    val forward: Vec3 get() = Vec3(-cos(pitch) * sin(yaw), cos(pitch) * cos(yaw), -sin(pitch))
    val right: Vec3 get() = Vec3(cos(yaw), sin(yaw), 0f)
    val up: Vec3 get() = right.cross(forward)

    companion object {
        val EMPTY = CameraFrame(FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }, 0f, 0f, 1f, 1f, 0)
    }
}
