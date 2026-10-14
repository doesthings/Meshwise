package io.github.doesthings.meshwise

import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.ModelSource
import io.github.doesthings.meshwise.data.RecentFile
import io.github.doesthings.meshwise.data.UPDATES_OFF
import io.github.doesthings.meshwise.data.sha1
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.ui.AppRoot
import io.github.doesthings.meshwise.ui.theme.MeshwiseTheme
import io.github.doesthings.meshwise.ui.theme.UiPrefs
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.ui.viewer.LocalViewerBackdrop
import io.github.doesthings.meshwise.viewer.AnalyzeTab
import io.github.doesthings.meshwise.viewer.MeasureMode
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the real Compose UI. The 3D view is replaced by frames rendered offline with the
 * same C++ renderer (scripts/make_screenshots.sh), so overlays line up with the geometry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: ViewerViewModel
    private val app get() = ApplicationProvider.getApplicationContext<MeshwiseApp>()
    private val outDir = File(System.getProperty("fsv.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val renderDir = File(System.getProperty("fsv.renderDir") ?: "build/renders")

    @Before
    fun setUp() {
        assumeTrue(TestSupport.nativeAvailable())
        app.recents.clear()
        app.settings.update { it.resetKeepingState().copy(updateChecks = UPDATES_OFF) }
        vm = ViewerViewModel(app)
        vm.density = 3f
        vm.engine.onSurfaceChanged(1080, 2400)
    }

    private fun backdrop(name: String): ImageBitmap? {
        val f = File(renderDir, "$name.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
    }

    private fun shoot(name: String, backdropName: String? = name) {
        vm.onFrameRendered()
        val bd = backdropName?.let { backdrop(it) }
        val st = app.settings.current
        compose.setContent {
            CompositionLocalProvider(LocalViewerBackdrop provides bd) {
                MeshwiseTheme(
                    themeMode = 2, accent = st.accent, pureBlack = st.pureBlack,
                    uiPrefs = UiPrefs(st.uiScale, st.showLabels, st.haptics, st.panelOpacity),
                ) { AppRoot(vm) }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        // The UI measured the areas it covers; apply them without animation (no GL frames
        // run here) and record them so the offline 3D frame uses the same framing.
        val (top, bottom) = vm.viewInsets.value
        vm.engine.setViewInsets(top, bottom, animate = false)
        vm.onFrameRendered()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        renderDir.mkdirs()
        File(renderDir, "$name.insets").writeText("${top.toInt()},${bottom.toInt()}")
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").absolutePath)
    }

    private fun open(source: ModelSource) {
        vm.open(source)
        awaitUntil { vm.model.value != null && vm.loading.value == null }
        awaitUntil { vm.analysisReady.value }
        vm.onFrameRendered()
    }

    private fun openAssembly() {
        // Seed the CAD cache with the tessellation of the STEP sample (the CAD kernel
        // runs in a separate Android process and is not available on the JVM).
        val src = ModelSource.FromAsset("samples/bearing_assembly.step")
        val meta = app.opener.meta(src)
        val input = app.opener.localInputFor(src, meta)
        val cache = app.opener.cadCacheFile(src, meta, input, app.settings.current.cadQuality)
        javaClass.classLoader!!.getResourceAsStream("bearing_assembly.fsm").use { i -> cache.outputStream().use { i.copyTo(it) } }
        open(src)
    }

    @Test
    fun home() {
        val samples = listOf(
            Triple("samples/bearing_assembly.step", "STEP", 19 to 7_680L),
            Triple("samples/keychain.3mf", "3MF", 4 to 9_424L),
            Triple("samples/trefoil_knot.stl", "STL", 1 to 69_120L),
            Triple("samples/twisted_vase.stl", "STL", 1 to 65_926L),
            Triple("samples/spur_gear.stl", "STL", 1 to 6_708L),
        )
        val now = System.currentTimeMillis()
        samples.reversed().forEachIndexed { i, (asset, fmt, pt) ->
            val id = sha1("asset:$asset")
            val thumb = File(renderDir, "thumb_" + asset.substringAfterLast('/').substringBefore('.') + ".png")
            if (thumb.exists()) thumb.copyTo(app.thumbnails.file(id), overwrite = true)
            val size = app.assets.open(asset).use { it.readBytes().size.toLong() }
            app.recents.add(
                RecentFile(id, "asset:$asset", asset.substringAfterLast('/'), fmt, size, now - (samples.size - i) * 7 * 60_000L, pt.second, pt.first),
            )
        }
        shoot("01_home", null)
    }

    @Test
    fun viewer() {
        openAssembly()
        shoot("02_viewer")
    }

    @Test
    fun section() {
        openAssembly()
        vm.selectTool(Tool.Section)
        vm.setSectionAxis(1)
        shoot("03_section")
    }

    @Test
    fun measure() {
        vm.updateSettings { it.copy(background = 2) }
        open(ModelSource.FromAsset("samples/spur_gear.stl"))
        vm.selectTool(Tool.Measure)
        vm.setMeasureMode(MeasureMode.Smart)
        // Smart measuring: the far walls of two lightening holes (Ø 6.88 mm, 60° apart).
        val f = vm.camera.value.forward
        val away = Vec3(f.x, f.y, 0f).normalized()
        val ring = 21.5f * 0.68f
        val centers = (0 until 6).map { k ->
            val a = Math.toRadians(60.0 * k)
            Vec3((ring * cos(a)).toFloat(), (ring * sin(a)).toFloat(), 5f)
        }.sortedBy { it.dot(away) }
        listOf(centers[0], centers[1]).forEach { c ->
            val s = vm.camera.value.project(c + away * 3.44f)!!
            vm.measureAt(s.x, s.y)
        }
        shoot("04_measure")
        vm.updateSettings { it.copy(background = 0) }
    }

    @Test
    fun thickness() {
        open(ModelSource.FromAsset("samples/twisted_vase.stl"))
        vm.selectTool(Tool.Analyze)
        vm.setAnalyzeTab(AnalyzeTab.Thickness)
        vm.computeThickness()
        awaitUntil { vm.thickness.value.result != null }
        shoot("05_thickness")
    }

    @Test
    fun explode() {
        openAssembly()
        vm.selectTool(Tool.Explode)
        vm.setExplode(0.9f)
        vm.engine.fitView(false)
        shoot("06_explode")
    }

    @Test
    fun parts() {
        openAssembly()
        vm.selectTool(Tool.Parts)
        val shaft = vm.model.value!!.parts.indexOfFirst { it.name.startsWith("Shaft") }
        vm.selectPart(shaft)
        shoot("07_parts")
    }

    @Test
    fun material() {
        open(ModelSource.FromAsset("samples/trefoil_knot.stl"))
        vm.selectTool(Tool.Material)
        vm.applyMaterial(MaterialPreset.Gold)
        shoot("08_material")
    }

    @Test
    fun dimensions() {
        open(ModelSource.FromAsset("samples/keychain.3mf"))
        vm.toggleDimensions()
        shoot("09_dimensions")
    }

    @Test
    fun color() {
        open(ModelSource.FromAsset("samples/keychain.3mf"))
        vm.selectTool(Tool.Color)
        shoot("10_color")
    }

    @Test
    fun weight() {
        open(ModelSource.FromAsset("samples/spur_gear.stl"))
        vm.selectTool(Tool.Print)
        vm.setPrintTab(io.github.doesthings.meshwise.viewer.PrintTab.Estimate)
        shoot("11_weight")
    }

    @Test
    fun viewPanel() {
        openAssembly()
        vm.selectTool(Tool.Lighting)
        shoot("13_view")
    }

    @Test
    fun customized() {
        vm.updateSettings {
            it.copy(accent = 1, leftHanded = true, showLabels = true, background = 6)
        }
        vm.setToolbar(
            listOf(ToolbarItem.Files, ToolbarItem.Section, ToolbarItem.Measure, ToolbarItem.Analyze, ToolbarItem.Turntable),
            listOf(ToolbarItem.View, ToolbarItem.Material, ToolbarItem.Parts, ToolbarItem.Snapshot, ToolbarItem.Save),
        )
        openAssembly()
        shoot("14_custom")
    }

    @Test
    fun toolbar() {
        open(ModelSource.FromAsset("samples/spur_gear.stl"))
        vm.openSettings()
        vm.navigate(Screen.Toolbar)
        shoot("15_toolbar", null)
    }

    @Test
    fun printPrep() {
        vm.updateSettings { it.copy(printer = "Prusa MK4 / MK4S") }
        openAssembly()
        vm.selectTool(Tool.Print)
        vm.suggestOrientations()
        awaitUntil { !vm.print.value.suggesting && vm.print.value.stats != null }
        shoot("16_print")
    }

    @Test
    fun settings() {
        open(ModelSource.FromAsset("samples/spur_gear.stl"))
        vm.openSettings()
        shoot("12_settings", null)
    }
}
