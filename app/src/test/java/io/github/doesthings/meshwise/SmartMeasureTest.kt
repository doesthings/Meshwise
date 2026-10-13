package io.github.doesthings.meshwise

import io.github.doesthings.meshwise.engine.FeatureKind
import io.github.doesthings.meshwise.engine.MeasureFeature
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.viewer.SmartMeasure
import io.github.doesthings.meshwise.viewer.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "what to measure between these two features" rules, on hand-made features. */
class SmartMeasureTest {
    private fun feature(
        kind: FeatureKind,
        point: Vec3 = Vec3.ZERO,
        center: Vec3 = point,
        axis: Vec3 = Vec3(0f, 0f, 1f),
        radius: Float = 0f,
        inner: Boolean = false,
    ) = MeasureFeature(
        kind, 0, point, axis, center, axis, center, center, radius, 0f, 0.0, 0f, inner, true, FloatArray(0),
    )

    private fun plane(p: Vec3, n: Vec3) = feature(FeatureKind.Plane, point = p, axis = n)
    private fun hole(c: Vec3, r: Float, axis: Vec3 = Vec3(0f, 0f, 1f)) = feature(FeatureKind.Cylinder, center = c, axis = axis, radius = r, inner = true)

    @Test
    fun parallelFacesGiveTheirDistance() {
        val r = SmartMeasure.pair(plane(Vec3(1f, 2f, 0f), Vec3(0f, 0f, -1f)), plane(Vec3(5f, -3f, 4.5f), Vec3(0f, 0f, 1f)))
        assertEquals(R.string.pair_faces_parallel, r.title)
        assertEquals(4.5, r.primary!!.value, 1e-5)
        assertEquals(ValueKind.Length, r.primary!!.kind)
    }

    @Test
    fun facesAtAnAngleGiveTheAngle() {
        val r = SmartMeasure.pair(plane(Vec3.ZERO, Vec3(0f, 0f, 1f)), plane(Vec3.ZERO, Vec3(1f, 0f, 0f)))
        assertEquals(R.string.pair_faces_angle, r.title)
        assertEquals(90.0, r.primary!!.value, 1e-4)
    }

    @Test
    fun holesGiveCenterDistanceAndTheWallBetween() {
        val r = SmartMeasure.pair(hole(Vec3(0f, 0f, 0f), 2f), hole(Vec3(10f, 0f, 3f), 3f))
        assertEquals(R.string.pair_rounds, r.title)
        assertEquals(10.0, r.primary!!.value, 1e-5)  // measured across the axes, not along them
        val wall = r.values.first { it.label == R.string.wall_between }
        assertEquals(5.0, wall.value, 1e-5)
    }

    @Test
    fun concentricRoundsAreRecognised() {
        val shaft = feature(FeatureKind.Cylinder, center = Vec3(0f, 0f, 5f), radius = 4f)
        val r = SmartMeasure.pair(hole(Vec3(0f, 0f, 0f), 5f), shaft)
        assertEquals(R.string.concentric, r.primary!!.label)
        assertEquals(1.0, r.values.first { it.label == R.string.wall_thickness }.value, 1e-5)
    }

    @Test
    fun holeAlongAFaceShowsTheMaterialLeft() {
        val face = plane(Vec3(0f, 0f, 10f), Vec3(0f, 0f, 1f))
        val sideHole = hole(Vec3(0f, 0f, 6f), 2f, axis = Vec3(1f, 0f, 0f))
        val r = SmartMeasure.pair(face, sideHole)
        assertEquals(4.0, r.primary!!.value, 1e-5)
        assertEquals(2.0, r.values.first { it.label == R.string.wall_to_hole }.value, 1e-5)
        assertTrue(r.from != null && r.to != null)
    }

    @Test
    fun pointsGiveDistanceAndDeltas() {
        val r = SmartMeasure.pair(feature(FeatureKind.Vertex, Vec3(0f, 0f, 0f)), feature(FeatureKind.Vertex, Vec3(3f, 4f, 0f)))
        assertEquals(5.0, r.primary!!.value, 1e-5)
        assertEquals(4.0, r.values.first { it.label == R.string.delta_y }.value, 1e-5)
    }
}
