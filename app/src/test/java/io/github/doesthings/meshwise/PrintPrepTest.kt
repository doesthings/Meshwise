package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.Printer
import io.github.doesthings.meshwise.engine.Affine
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Re-orienting, scaling and printer fit through the real engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrintPrepTest {
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

    private fun settle() = awaitUntil { !vm.print.value.busy && vm.analysisReady.value }

    @Test
    fun orientScaleUndoAndReset() {
        load("samples/keychain.3mf")
        val original = vm.model.value!!
        val size0 = original.bounds.size
        val volume0 = original.volume
        vm.selectTool(Tool.Print)

        vm.rotateModel(0, 90f)  // X: the 30 mm side now stands up
        settle()
        val turned = vm.model.value!!
        assertEquals(size0.y, turned.bounds.size.z, 1e-3f)
        assertEquals(0f, turned.bounds.min.z, 1e-3f)  // dropped onto the bed
        assertEquals(volume0, turned.volume, 1e-3 * kotlin.math.abs(volume0))
        awaitUntil { vm.print.value.stats != null }
        assertEquals(size0.y, vm.print.value.stats!!.height, 1e-2f)

        // Lay the original top face flat: the model ends up upside down, same height again.
        vm.resetTransform()
        settle()
        vm.layFlat(Vec3(0f, 0f, 1f))
        settle()
        assertEquals(size0.z, vm.model.value!!.bounds.size.z, 1e-3f)

        vm.scaleModel(2f)
        settle()
        assertEquals(size0.x * 2f, vm.model.value!!.bounds.size.x, 1e-2f)
        assertEquals(volume0 * 8, vm.model.value!!.volume, 1e-2 * kotlin.math.abs(volume0))
        assertEquals(2f, vm.print.value.transform.scale, 1e-4f)

        // Undo and reset bring the file back.
        vm.undoTransform()
        settle()
        assertEquals(size0.x, vm.model.value!!.bounds.size.x, 1e-2f)
        vm.resetTransform()
        settle()
        assertTrue(vm.print.value.transform.isIdentity)
        assertEquals(original.bounds.min.z, vm.model.value!!.bounds.min.z, 1e-3f)
        assertEquals(volume0, vm.model.value!!.volume, 1e-3 * kotlin.math.abs(volume0))
    }

    @Test
    fun suggestionsAndPrinterFit() {
        load("samples/spur_gear.stl")
        vm.selectTool(Tool.Print)
        vm.suggestOrientations()
        awaitUntil { !vm.print.value.suggesting }
        val list = vm.print.value.suggestions
        assertTrue(list.isNotEmpty() && list.size <= 3)
        assertTrue(list.zipWithNext().all { (a, b) -> a.score <= b.score })
        // The gear already lies on its flat side: the best option is the current one.
        assertEquals(-1f, list.first().down.z, 1e-3f)
        vm.applySuggestion(list[1])
        settle()
        assertFalse(vm.print.value.transform.isIdentity)

        val mini = Printer("mini", 180f, 180f, 180f)
        assertTrue(mini.fits(170f, 100f, 50f))
        assertTrue(Printer("mk4", 250f, 210f, 220f).fits(205f, 245f, 10f))  // only when turned 90°
        assertFalse(mini.fits(200f, 100f, 50f))
        val s = mini.scaleToFit(360f, 90f, 10f)
        assertTrue(mini.fits(360f * s, 90f * s, 10f * s))
    }

    @Test
    fun affineHelpers() {
        val down = Affine.pointDown(Vec3(1f, 0f, 0f))
        val r = down.apply(Vec3(1f, 0f, 0f))
        assertEquals(-1f, r.z, 1e-5f)
        assertEquals(-1f, Affine.pointDown(Vec3(0f, 0f, 1f)).apply(Vec3(0f, 0f, 1f)).z, 1e-5f)
        val composed = Affine.translation(Vec3(0f, 0f, 5f)) * Affine.scale(2f)
        assertEquals(7f, composed.apply(Vec3(0f, 0f, 1f)).z, 1e-5f)
    }
}
