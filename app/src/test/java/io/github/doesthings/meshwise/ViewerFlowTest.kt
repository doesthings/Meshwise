package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.ShadingMode
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.viewer.AnalyzeTab
import io.github.doesthings.meshwise.viewer.MeasureMode
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real view model and native engine (desktop build) through the main tools. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerFlowTest {
    private lateinit var vm: ViewerViewModel

    @Before
    fun setUp() {
        assumeTrue("JVM build of libmeshwise.so missing", TestSupport.nativeAvailable())
        vm = ViewerViewModel(ApplicationProvider.getApplicationContext())
        vm.engine.onSurfaceChanged(1080, 2400)
    }

    private fun load(asset: String) {
        vm.openSample(asset)
        awaitUntil { vm.model.value != null && vm.loading.value == null }
        awaitUntil { vm.analysisReady.value }
        vm.onFrameRendered()
    }

    /** Finds a screen position that hits the model. */
    private fun findHit(): Pair<Float, Float> {
        for (y in 600..1800 step 60) for (x in 200..880 step 60) {
            if (vm.engine.pick(x.toFloat(), y.toFloat()) != null) return x.toFloat() to y.toFloat()
        }
        throw AssertionError("no surface found on screen")
    }

    @Test
    fun loadsMeasuresAndAnalyses() {
        load("samples/twisted_vase.stl")
        val m = vm.model.value!!
        assertEquals(Screen.Viewer, vm.screen.value)
        assertTrue(m.triangles > 10_000)
        assertEquals(1, m.parts.size)
        assertTrue(vm.recents.value.any { it.name == "twisted_vase.stl" })

        // Distance measurement through real picking.
        vm.selectTool(Tool.Measure)
        vm.setMeasureMode(MeasureMode.Distance)
        val (x, y) = findHit()
        vm.onTap(x, y)
        vm.onTap(x + 40f, y + 80f)
        val st = vm.measure.value
        assertTrue("both taps should hit", st.points.size == 2)
        assertTrue(st.complete)
        assertTrue((st.points[1].point - st.points[0].point).length() > 0f)
        // A further tap starts a new measurement.
        vm.onTap(x, y)
        assertEquals(1, vm.measure.value.points.size)

        // Mesh check comes from the background analysis.
        val check = vm.meshCheck.value
        assertNotNull(check)
        assertTrue(check!!.watertight)

        // Thickness analysis.
        vm.selectTool(Tool.Analyze)
        vm.setAnalyzeTab(AnalyzeTab.Thickness)
        vm.computeThickness()
        awaitUntil { vm.thickness.value.result != null }
        val th = vm.thickness.value.result!!
        assertTrue(th.min > 0.1f && th.min < 3f)
        assertEquals(ShadingMode.ThicknessGradient, vm.options.value.mode)
        vm.setThicknessThresholdMode(true)
        assertEquals(ShadingMode.ThicknessThreshold, vm.options.value.mode)
        vm.selectTool(Tool.None)
        assertEquals(ShadingMode.Shaded, vm.options.value.mode)
    }

    @Test
    fun sectionExplodeAndColour() {
        load("samples/keychain.3mf")
        val m = vm.model.value!!
        assertEquals(4, m.parts.size)
        assertEquals("Base", m.parts[0].name)

        vm.selectTool(Tool.Section)
        assertTrue(vm.options.value.section)
        vm.setSectionAxis(0)
        assertEquals(0, vm.options.value.sectionAxis)
        vm.flipSection()
        vm.selectTool(Tool.None)
        assertTrue(!vm.options.value.section)

        val before = vm.engine.sceneBounds().size
        vm.setExplode(1f)
        val after = vm.engine.sceneBounds().size
        assertTrue(after.length() > before.length())
        vm.resetExplode()

        vm.selectTool(Tool.Color)
        vm.setPaintColor(0xFFE53935.toInt())
        vm.paintPart(2)
        assertEquals(0xFFE53935.toInt(), vm.parts.value[2].color)
        vm.restoreOriginalColors()
        assertEquals(m.parts[2].fileColor, vm.parts.value[2].color)

        vm.applyMaterial(MaterialPreset.Gold)
        assertTrue(vm.parts.value.all { it.material == MaterialPreset.Gold })

        vm.isolatePart(1)
        assertTrue(vm.parts.value.count { it.visible } == 1)
        vm.showAllParts()
        assertTrue(vm.parts.value.all { it.visible })

        // Manual move + undo.
        vm.setManualExplode(true)
        vm.selectPart(1)
        vm.beginPartDrag(1)
        vm.movePart(1, Vec3(5f, 0f, 0f))
        assertEquals(5f, vm.engine.partOffset(1).second.x, 1e-4f)
        vm.undoMove()
        assertEquals(0f, vm.engine.partOffset(1).second.x, 1e-4f)
    }

    @Test
    fun cameraAndViews() {
        load("samples/spur_gear.stl")
        val f0 = vm.camera.value
        vm.engine.setStandardView(io.github.doesthings.meshwise.engine.StandardView.Top, animate = false)
        vm.onFrameRendered()
        val f1 = vm.camera.value
        assertTrue(f1.version != f0.version)
        assertEquals(-1f, f1.forward.z, 1e-4f)
        // Orbit/pan/zoom do not throw and change the camera.
        vm.engine.orbit(50f, 20f)
        vm.engine.pan(10f, 10f)
        vm.engine.zoom(1.5f, 540f, 1200f)
        vm.onFrameRendered()
        assertTrue(vm.camera.value.version != f1.version)
    }
}
