package io.github.doesthings.meshwise.engine

import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-20f) this / l else Vec3(0f, 0f, 1f)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        fun axis(i: Int) = when (i) { 0 -> Vec3(1f, 0f, 0f); 1 -> Vec3(0f, 1f, 0f); else -> Vec3(0f, 0f, 1f) }
    }
}

data class Bounds(val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min
    val center: Vec3 get() = (min + max) * 0.5f
    val radius: Float get() = size.length() * 0.5f
    fun translated(t: Vec3) = Bounds(min + t, max + t)
    fun union(o: Bounds) = Bounds(
        Vec3(minOf(min.x, o.min.x), minOf(min.y, o.min.y), minOf(min.z, o.min.z)),
        Vec3(maxOf(max.x, o.max.x), maxOf(max.y, o.max.y), maxOf(max.z, o.max.z)),
    )

    companion object {
        val EMPTY = Bounds(Vec3.ZERO, Vec3.ZERO)
        fun of(a: FloatArray, offset: Int = 0) =
            Bounds(Vec3(a[offset], a[offset + 1], a[offset + 2]), Vec3(a[offset + 3], a[offset + 4], a[offset + 5]))
    }
}

data class PartInfo(
    val index: Int,
    val name: String,
    val fileColor: Int,          // ARGB as stored in the model (default palette if none)
    val hasFileColor: Boolean,
    val hasVertexColors: Boolean,
    val bounds: Bounds,
    val volume: Double,          // mm^3 (signed)
    val area: Double,            // mm^2
    val centroid: Vec3,
    val triangles: Int,
)

/** Immutable description of a loaded model. */
data class ModelInfo(
    val name: String,
    val format: String,
    val fileSize: Long,
    val triangles: Long,
    val vertices: Long,
    val bounds: Bounds,
    val volume: Double,
    val area: Double,
    val droppedFaces: Long,
    val parts: List<PartInfo>,
    val warnings: List<String>,
    val loadMillis: Long = 0,
) {
    val isAssembly: Boolean get() = parts.size > 1
    val anyVertexColors: Boolean get() = parts.any { it.hasVertexColors }
    val anyFileColors: Boolean get() = parts.any { it.hasFileColor || it.hasVertexColors }

    companion object {
        /** Reads the description of a native model handle (before it is handed to the engine). */
        fun fromHandle(handle: Long, displayName: String, loadMillis: Long): ModelInfo {
            val s = NativeLib.nativeModelStats(handle)
            val names = NativeLib.nativeModelPartNames(handle)
            val data = NativeLib.nativeModelPartData(handle)
            val colors = NativeLib.nativeModelPartColors(handle)
            val parts = names.indices.map { i ->
                val o = i * 12
                PartInfo(
                    index = i,
                    name = names[i],
                    fileColor = colors[2 * i],
                    hasFileColor = colors[2 * i + 1] and 1 != 0,
                    hasVertexColors = colors[2 * i + 1] and 2 != 0,
                    bounds = Bounds.of(data, o),
                    volume = data[o + 6].toDouble(),
                    area = data[o + 7].toDouble(),
                    centroid = Vec3(data[o + 8], data[o + 9], data[o + 10]),
                    triangles = data[o + 11].toInt(),
                )
            }
            return ModelInfo(
                name = displayName,
                format = NativeLib.nativeModelFormat(handle),
                fileSize = s[3].toLong(),
                triangles = s[0].toLong(),
                vertices = s[1].toLong(),
                bounds = Bounds(Vec3(s[4].toFloat(), s[5].toFloat(), s[6].toFloat()), Vec3(s[7].toFloat(), s[8].toFloat(), s[9].toFloat())),
                volume = s[10],
                area = s[11],
                droppedFaces = s[12].toLong(),
                parts = parts,
                warnings = NativeLib.nativeModelWarnings(handle).toList(),
                loadMillis = loadMillis,
            )
        }
    }
}

enum class ShadingMode(val id: Int) { Shaded(0), ThicknessGradient(1), ThicknessThreshold(2), Overhang(3), Normals(4) }

/** Mirrors fsv::RenderOptions. */
data class RenderOptions(
    val grid: Boolean = true,
    val shadows: Boolean = true,
    val edges: Boolean = false,
    val wireframe: Boolean = false,
    val axes: Boolean = false,
    val openEdges: Boolean = false,
    val background: Int = 0,
    val lighting: Int = 0,
    val mode: ShadingMode = ShadingMode.Shaded,
    val section: Boolean = false,
    val sectionAxis: Int = 2,
    val sectionFlip: Boolean = false,
    val hatch: Boolean = true,
    val sectionPlane: Boolean = true,
    val selectColor: Int = 0xFF7FB3FF.toInt(),
    val exposure: Float = 1f,
    val scalarMin: Float = 0f,
    val scalarMax: Float = 1f,
    val threshold: Float = 1f,
    val overhangAngle: Float = 45f,
    val sectionValue: Float = 0f,
    val lineScale: Float = 1f,
) {
    fun ints() = intArrayOf(
        grid.i, shadows.i, edges.i, wireframe.i, axes.i, openEdges.i, background, lighting, mode.id, section.i,
        sectionAxis, sectionFlip.i, hatch.i, sectionPlane.i, selectColor,
    )

    fun floats() = floatArrayOf(exposure, scalarMin, scalarMax, threshold, overhangAngle, sectionValue, lineScale)

    private val Boolean.i get() = if (this) 1 else 0
}

/** Result of a pick ray. */
data class PickHit(val part: Int, val point: Vec3, val normal: Vec3, val onCap: Boolean)

data class MeshCheck(
    val openEdges: Long,
    val nonManifoldEdges: Long,
    val shells: Long,
    val flippedEdges: Long,
    val degenerateFaces: Long,
    val edges: Long,
    val droppedFaces: Long,
) {
    val watertight: Boolean get() = openEdges == 0L && nonManifoldEdges == 0L
}

data class ThinSpot(val position: Vec3, val normal: Vec3, val value: Float, val part: Int)

data class ThicknessResult(val min: Float, val max: Float, val p05: Float, val p95: Float, val spots: List<ThinSpot>)
