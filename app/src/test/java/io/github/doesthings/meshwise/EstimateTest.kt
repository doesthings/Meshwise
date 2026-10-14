package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.Printers
import io.github.doesthings.meshwise.engine.DensityPreset
import io.github.doesthings.meshwise.engine.Filament
import io.github.doesthings.meshwise.engine.InfillPattern
import io.github.doesthings.meshwise.engine.PrintEstimate
import io.github.doesthings.meshwise.engine.Supports
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.PrintTab
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The print estimate through the view model and the real engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EstimateTest {
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
        vm.selectTool(Tool.Print)
        vm.setPrintTab(PrintTab.Estimate)
    }

    private fun estimate(scope: Int = 0): PrintEstimate {
        vm.requestEstimate(scope)
        awaitUntil { !vm.estimate.value.running && (vm.estimate.value.result != null || vm.estimate.value.failed) }
        return vm.estimate.value.result!!
    }

    @Test
    fun printersMaterialsAndSupports() {
        load("samples/spur_gear.stl")
        vm.updateSettings { it.copy(printer = "Bambu Lab P1S / P1P / X1C", density = DensityPreset.PLA.ordinal, infill = 0.15f, walls = 2) }
        val fast = estimate()
        assertTrue("${fast.seconds}", fast.seconds in 15 * 60.0..45 * 60.0)
        assertTrue(fast.filamentMm > 2000)
        assertEquals(fast.volumeMm3 / (Math.PI * 0.875 * 0.875), fast.filamentMm, 1.0)
        assertEquals(fast.volumeMm3 * 1.24 / 1000.0, fast.grams(1.24), 1e-9)
        assertTrue(fast.volumeMm3 < vm.model.value!!.volume)  // walls and 15 % infill, not solid
        assertEquals(0.0, fast.supportMm3, 1e-9)  // the gear lies flat

        // The same gear on a slow bed slinger takes much longer, with the same plastic.
        vm.updateSettings { it.copy(printer = "Creality Ender-3 V2") }
        val slow = estimate()
        assertTrue(slow.seconds > 2 * fast.seconds)

        // More infill: more plastic and time. Thinner layers: more layers.
        vm.updateSettings { it.copy(infill = 0.5f) }
        val dense = estimate()
        assertTrue(dense.volumeMm3 > slow.volumeMm3 && dense.seconds > slow.seconds)
        vm.updateSettings { it.copy(layerHeight = 0.12f, infill = 0.15f) }
        assertTrue(estimate().layers > slow.layers)

        // Metals and resin aren't printed from filament: no estimate.
        vm.updateSettings { it.copy(density = DensityPreset.Steel.ordinal) }
        vm.requestEstimate(0)
        assertNull(vm.estimate.value.result)
    }

    @Test
    fun supportsAndParts() {
        load("samples/trefoil_knot.stl")
        vm.updateSettings { it.copy(printer = "Bambu Lab A1", supports = Supports.Off.ordinal, infillPattern = InfillPattern.Gyroid.ordinal) }
        val bare = estimate()
        assertTrue(bare.supportAreaMm2 > 25)  // the knot hangs over itself
        vm.updateSettings { it.copy(supports = Supports.Normal.ordinal) }
        val normal = estimate()
        assertTrue(normal.supportMm3 > 0 && normal.seconds > bare.seconds)
        vm.updateSettings { it.copy(supports = Supports.Tree.ordinal) }
        assertTrue(estimate().supportMm3 < normal.supportMm3)

        // A multi-part file: one part on its own is less than the lot.
        load("samples/keychain.3mf")
        vm.updateSettings { it.copy(supports = Supports.Off.ordinal) }
        val all = estimate()
        vm.selectPart(0)
        val one = estimate(scope = 2)
        assertTrue(one.volumeMm3 < all.volumeMm3 && one.seconds <= all.seconds)
    }

    @Test
    fun settingsAndFormatting() {
        val s = vm.settings.value.copy(printer = "Prusa MK4 / MK4S", density = DensityPreset.PETG.ordinal, layerHeight = 0.28f)
        val e = Printers.estimateSettings(s, Filament.of(DensityPreset.PETG)!!)
        assertEquals(0.28f, e.layerHeight)
        assertEquals(12f, e.maxFlow)  // PETG melts slower than the profile's PLA
        assertEquals(0.95f, e.flowRatio)
        assertEquals(53, e.floats().size)
        assertEquals(9, e.ints().size)
        assertEquals(Printers.generic.outerSpeed, Printers.estimateSettings(s.copy(printer = ""), Filament.of(DensityPreset.PLA)!!).outerSpeed)
        assertEquals("2 h 14 min", Fmt.duration(2 * 3600.0 + 14 * 60 + 10))
        assertEquals("45 min", Fmt.duration(45 * 60.0))
        assertEquals("3 min 20 s", Fmt.duration(200.0))
        assertEquals("1 d 3 h", Fmt.duration(27 * 3600.0))
    }
}
