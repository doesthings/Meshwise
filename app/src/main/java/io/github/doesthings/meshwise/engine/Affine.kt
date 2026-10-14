package io.github.doesthings.meshwise.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Row-major 3x4 affine transform (linear part plus translation), as the native code expects. */
class Affine(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)) {
    init {
        require(m.size == 12)
    }

    /** This transform applied after [o]: (this * o)(p) = this(o(p)). */
    operator fun times(o: Affine): Affine {
        val a = m
        val b = o.m
        val r = FloatArray(12)
        for (i in 0 until 3) {
            for (j in 0 until 4) {
                var s = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j]
                if (j == 3) s += a[i * 4 + 3]
                r[i * 4 + j] = s
            }
        }
        return Affine(r)
    }

    fun apply(p: Vec3) = Vec3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    /** Uniform scale factor (cube root of the determinant's magnitude). */
    val scale: Float
        get() {
            val det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8])
            return Math.cbrt(abs(det).toDouble()).toFloat()
        }

    val isIdentity: Boolean get() = m.contentEquals(IDENTITY.m)

    companion object {
        val IDENTITY = Affine()

        fun translation(t: Vec3) = Affine(floatArrayOf(1f, 0f, 0f, t.x, 0f, 1f, 0f, t.y, 0f, 0f, 1f, t.z))

        fun scale(s: Float) = Affine(floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f))

        fun mirror(axis: Int) = Affine(FloatArray(12).also { r ->
            for (i in 0 until 3) r[i * 4 + i] = if (i == axis) -1f else 1f
        })

        /** Rotation by [degrees] about the X (0), Y (1) or Z (2) axis. */
        fun rotation(axis: Int, degrees: Float): Affine = rotation(Vec3.axis(axis), Math.toRadians(degrees.toDouble()).toFloat())

        /** Rotation by [angle] radians about the unit vector [k] (Rodrigues). */
        fun rotation(k: Vec3, angle: Float): Affine {
            val c = cos(angle)
            val s = sin(angle)
            val t = 1f - c
            return Affine(floatArrayOf(
                t * k.x * k.x + c, t * k.x * k.y - s * k.z, t * k.x * k.z + s * k.y, 0f,
                t * k.x * k.y + s * k.z, t * k.y * k.y + c, t * k.y * k.z - s * k.x, 0f,
                t * k.x * k.z - s * k.y, t * k.y * k.z + s * k.x, t * k.z * k.z + c, 0f,
            ))
        }

        /** The smallest rotation that turns direction [d] to point straight down (−Z). */
        fun pointDown(d: Vec3): Affine {
            val from = d.normalized()
            val to = Vec3(0f, 0f, -1f)
            val c = from.dot(to).coerceIn(-1f, 1f)
            if (c > 0.999999f) return IDENTITY
            if (c < -0.999999f) return rotation(Vec3(1f, 0f, 0f), Math.PI.toFloat())  // pointing up: flip over
            val axis = from.cross(to).normalized()
            return rotation(axis, kotlin.math.acos(c))
        }
    }
}

/** How the model prints with [down] pointing at the bed (see native/core/print_prep.h). */
data class OrientationStats(
    val down: Vec3,
    val overhangArea: Double,
    val contactArea: Double,
    val totalArea: Double,
    val height: Float,
    val score: Float,
) {
    val overhangFraction: Double get() = if (totalArea > 0) overhangArea / totalArea else 0.0
}
