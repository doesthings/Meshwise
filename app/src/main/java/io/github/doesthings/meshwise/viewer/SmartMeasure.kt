package io.github.doesthings.meshwise.viewer

import androidx.annotation.StringRes
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.engine.FeatureKind
import io.github.doesthings.meshwise.engine.MeasureFeature
import io.github.doesthings.meshwise.engine.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max

enum class ValueKind { Length, Angle, Area, Coordinate, Position, Text }

/** One line of a measurement. Lengths are mm, angles degrees, areas mm². */
data class MeasureValue(
    @StringRes val label: Int,
    val value: Double,
    val kind: ValueKind,
    val primary: Boolean = false,
    @StringRes val text: Int = 0,
    val position: Vec3? = null,
)

/**
 * What to show for the picked features: a title, values, and optionally a dimension line
 * from [from] to [to] (labelled with the primary value) or a label at [anchor].
 */
data class MeasureResult(
    @StringRes val title: Int,
    val values: List<MeasureValue>,
    val anchor: Vec3,
    val from: Vec3? = null,
    val to: Vec3? = null,
) {
    val primary: MeasureValue? get() = values.firstOrNull { it.primary } ?: values.firstOrNull()
}

/** Turns one or two picked features into the measurement that makes sense for them. */
object SmartMeasure {
    private val PARALLEL = cos(Math.toRadians(0.5)).toFloat()
    private val AXIS_PARALLEL = cos(Math.toRadians(1.0)).toFloat()

    fun of(features: List<MeasureFeature>): MeasureResult? = when (features.size) {
        0 -> null
        1 -> single(features[0])
        else -> pair(features[0], features[1])
    }

    @StringRes
    fun title(f: MeasureFeature): Int = when (f.kind) {
        FeatureKind.Vertex -> R.string.feature_corner
        FeatureKind.Edge -> R.string.feature_edge
        FeatureKind.Circle -> if (f.closed) R.string.feature_round_edge else R.string.feature_arc
        FeatureKind.Plane -> R.string.feature_face
        FeatureKind.Cylinder -> if (f.inner) R.string.feature_hole else R.string.feature_shaft
        FeatureKind.Sphere -> R.string.feature_sphere
        else -> R.string.feature_point
    }

    fun single(f: MeasureFeature): MeasureResult {
        val v = mutableListOf<MeasureValue>()
        when (f.kind) {
            FeatureKind.Edge -> v += MeasureValue(R.string.length, f.length.toDouble(), ValueKind.Length, primary = true)
            FeatureKind.Circle -> {
                v += MeasureValue(R.string.diameter_short, f.diameter.toDouble(), ValueKind.Length, primary = true)
                v += MeasureValue(R.string.radius_short, f.radius.toDouble(), ValueKind.Length)
                if (!f.closed) v += MeasureValue(R.string.arc_length, f.length.toDouble(), ValueKind.Length)
                v += MeasureValue(R.string.center_xyz, 0.0, ValueKind.Position, position = f.center)
            }
            FeatureKind.Cylinder -> {
                v += MeasureValue(R.string.diameter_short, f.diameter.toDouble(), ValueKind.Length, primary = true)
                v += MeasureValue(R.string.radius_short, f.radius.toDouble(), ValueKind.Length)
                v += MeasureValue(if (f.inner) R.string.depth else R.string.length, f.length.toDouble(), ValueKind.Length)
                v += MeasureValue(R.string.axis, 0.0, ValueKind.Text, text = axisName(f.axis, signed = false))
            }
            FeatureKind.Sphere -> {
                v += MeasureValue(R.string.diameter_short, f.diameter.toDouble(), ValueKind.Length, primary = true)
                v += MeasureValue(R.string.radius_short, f.radius.toDouble(), ValueKind.Length)
            }
            FeatureKind.Plane -> {
                v += MeasureValue(R.string.area, f.area, ValueKind.Area, primary = true)
                v += MeasureValue(R.string.facing, 0.0, ValueKind.Text, text = axisName(f.axis, signed = true))
            }
            else -> v += MeasureValue(R.string.position, 0.0, ValueKind.Position, primary = true, position = f.point)
        }
        val anchor = if (f.isRound) f.center else f.point
        return MeasureResult(title(f), v, anchor)
    }

    fun pair(a: MeasureFeature, b: MeasureFeature): MeasureResult {
        val planeA = a.kind == FeatureKind.Plane
        val planeB = b.kind == FeatureKind.Plane
        return when {
            planeA && planeB -> planes(a, b)
            planeA -> toPlane(a, b)
            planeB -> toPlane(b, a)
            a.isRound && b.isRound -> rounds(a, b)
            a.kind == FeatureKind.Edge && b.kind == FeatureKind.Edge -> edges(a, b)
            a.kind == FeatureKind.Edge -> toEdge(a, b)
            b.kind == FeatureKind.Edge -> toEdge(b, a)
            else -> points(rep(a), rep(b))
        }
    }

    /** The point that stands for a feature in distances. */
    fun rep(f: MeasureFeature): Vec3 = if (f.isRound) f.center else f.point

    private fun planes(a: MeasureFeature, b: MeasureFeature): MeasureResult {
        val c = a.axis.dot(b.axis)
        if (abs(c) > PARALLEL) {
            val d = (b.point - a.point).dot(a.axis)
            val foot = b.point - a.axis * d
            return MeasureResult(
                R.string.pair_faces_parallel,
                listOf(MeasureValue(R.string.distance, abs(d).toDouble(), ValueKind.Length, primary = true)),
                anchor = (foot + b.point) * 0.5f, from = foot, to = b.point,
            )
        }
        val angle = Math.toDegrees(acos(c.coerceIn(-1f, 1f).toDouble()))
        return MeasureResult(
            R.string.pair_faces_angle,
            listOf(
                MeasureValue(R.string.angle, angle, ValueKind.Angle, primary = true),
                MeasureValue(R.string.angle_inside, 180.0 - angle, ValueKind.Angle),
            ),
            anchor = (a.point + b.point) * 0.5f,
        )
    }

    private fun toPlane(p: MeasureFeature, x: MeasureFeature): MeasureResult {
        val q = rep(x)
        val d = (q - p.point).dot(p.axis)
        val foot = q - p.axis * d
        val v = mutableListOf(MeasureValue(R.string.distance_to_face, abs(d).toDouble(), ValueKind.Length, primary = true))
        if (x.hasAxis && abs(x.axis.dot(p.axis)) < 0.02f) {
            // A hole or shaft running along the face: how much material is left to its surface.
            val gap = abs(d) - x.radius
            if (gap > 0) v += MeasureValue(if (x.inner) R.string.wall_to_hole else R.string.gap_to_surface, gap.toDouble(), ValueKind.Length)
        }
        if (x.kind == FeatureKind.Edge && abs(x.axis.dot(p.axis)) > 0.02f) {
            val angle = 90.0 - Math.toDegrees(acos(abs(x.axis.dot(p.axis)).coerceIn(0f, 1f).toDouble()))
            v += MeasureValue(R.string.angle, angle, ValueKind.Angle)
        }
        return MeasureResult(R.string.pair_to_face, v, anchor = (foot + q) * 0.5f, from = foot, to = q)
    }

    private fun rounds(a: MeasureFeature, b: MeasureFeature): MeasureResult {
        val v = mutableListOf<MeasureValue>()
        val delta = b.center - a.center
        if (a.hasAxis && b.hasAxis && abs(a.axis.dot(b.axis)) > AXIS_PARALLEL) {
            val perp = delta - a.axis * delta.dot(a.axis)
            val d = perp.length()
            if (d < 1e-3f * max(1f, max(a.radius, b.radius))) {
                v += MeasureValue(R.string.concentric, 0.0, ValueKind.Text, primary = true, text = R.string.yes)
                v += MeasureValue(R.string.diameter_a, a.diameter.toDouble(), ValueKind.Length)
                v += MeasureValue(R.string.diameter_b, b.diameter.toDouble(), ValueKind.Length)
                if (a.inner != b.inner) {
                    val wall = abs(a.radius - b.radius)
                    v += MeasureValue(R.string.wall_thickness, wall.toDouble(), ValueKind.Length)
                }
                return MeasureResult(R.string.pair_rounds, v, anchor = a.center)
            }
            v += MeasureValue(R.string.center_distance, d.toDouble(), ValueKind.Length, primary = true)
            val gap = d - a.radius - b.radius
            if (gap > 0) v += MeasureValue(if (a.inner && b.inner) R.string.wall_between else R.string.edge_distance, gap.toDouble(), ValueKind.Length)
            val from = a.center + a.axis * (delta.dot(a.axis) * 0.5f)
            return MeasureResult(R.string.pair_rounds, v, anchor = from + perp * 0.5f, from = from, to = from + perp)
        }
        v += MeasureValue(R.string.center_distance, delta.length().toDouble(), ValueKind.Length, primary = true)
        if (a.hasAxis && b.hasAxis) {
            val angle = Math.toDegrees(acos(abs(a.axis.dot(b.axis)).coerceIn(0f, 1f).toDouble()))
            v += MeasureValue(R.string.axis_angle, angle, ValueKind.Angle)
        }
        v += deltas(delta)
        return MeasureResult(R.string.pair_rounds, v, anchor = (a.center + b.center) * 0.5f, from = a.center, to = b.center)
    }

    private fun edges(a: MeasureFeature, b: MeasureFeature): MeasureResult {
        val c = abs(a.axis.dot(b.axis))
        if (c > PARALLEL) {
            val delta = b.point - a.point
            val perp = delta - a.axis * delta.dot(a.axis)
            val from = b.point - perp
            return MeasureResult(
                R.string.pair_edges_parallel,
                listOf(MeasureValue(R.string.distance, perp.length().toDouble(), ValueKind.Length, primary = true)),
                anchor = from + perp * 0.5f, from = from, to = b.point,
            )
        }
        val angle = Math.toDegrees(acos(c.coerceIn(0f, 1f).toDouble()))
        return MeasureResult(
            R.string.pair_edges_angle,
            listOf(
                MeasureValue(R.string.angle, angle, ValueKind.Angle, primary = true),
                MeasureValue(R.string.angle_inside, 180.0 - angle, ValueKind.Angle),
            ),
            anchor = (a.point + b.point) * 0.5f,
        )
    }

    private fun toEdge(e: MeasureFeature, x: MeasureFeature): MeasureResult {
        val q = rep(x)
        val t = (q - e.a).dot(e.axis)
        val foot = e.a + e.axis * t
        val d = (q - foot).length()
        return MeasureResult(
            R.string.pair_to_edge,
            listOf(MeasureValue(R.string.distance_to_edge, d.toDouble(), ValueKind.Length, primary = true)),
            anchor = (foot + q) * 0.5f, from = foot, to = q,
        )
    }

    private fun points(p: Vec3, q: Vec3): MeasureResult {
        val delta = q - p
        return MeasureResult(
            R.string.pair_points,
            listOf(MeasureValue(R.string.distance, delta.length().toDouble(), ValueKind.Length, primary = true)) + deltas(delta),
            anchor = (p + q) * 0.5f, from = p, to = q,
        )
    }

    private fun deltas(d: Vec3) = listOf(
        MeasureValue(R.string.delta_x, abs(d.x).toDouble(), ValueKind.Length),
        MeasureValue(R.string.delta_y, abs(d.y).toDouble(), ValueKind.Length),
        MeasureValue(R.string.delta_z, abs(d.z).toDouble(), ValueKind.Length),
    )

    /** "Z", "+X", or "tilted" for directions that are not along an axis. */
    @StringRes
    fun axisName(d: Vec3, signed: Boolean): Int {
        val axes = listOf(
            Triple(d.x, R.string.dir_x_pos, R.string.dir_x_neg),
            Triple(d.y, R.string.dir_y_pos, R.string.dir_y_neg),
            Triple(d.z, R.string.dir_z_pos, R.string.dir_z_neg),
        )
        val unsigned = listOf(R.string.dir_x, R.string.dir_y, R.string.dir_z)
        axes.forEachIndexed { i, (c, pos, neg) ->
            if (abs(c) > 0.9995f) return if (!signed) unsigned[i] else if (c > 0) pos else neg
        }
        return R.string.dir_tilted
    }
}
