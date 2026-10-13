package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.BACKGROUND_CUSTOM
import io.github.doesthings.meshwise.data.Settings
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.StandardView
import io.github.doesthings.meshwise.viewer.Screen
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

/** The customization settings: toolbar layout, persistence and what they change when a model opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomizationTest {
    private val app get() = ApplicationProvider.getApplicationContext<MeshwiseApp>()
    private lateinit var vm: ViewerViewModel

    @Before
    fun setUp() {
        assumeTrue("JVM build of libmeshwise.so missing", TestSupport.nativeAvailable())
        app.settings.update { it.resetKeepingState() }
        vm = ViewerViewModel(app)
        vm.engine.onSurfaceChanged(1080, 2400)
    }

    private fun load(asset: String) {
        vm.openSample(asset)
        awaitUntil { vm.model.value != null && vm.loading.value == null }
        vm.onFrameRendered()
    }

    @Test
    fun toolbarLayoutRoundTrips() {
        val d = app.settings.current
        assertEquals(ToolbarItem.DEFAULT_DOCK, d.dockItems)
        assertEquals(ToolbarItem.DEFAULT_SIDE, d.sideItems)

        vm.setToolbar(listOf(ToolbarItem.Measure, ToolbarItem.Turntable), listOf(ToolbarItem.Measure, ToolbarItem.Parts))
        val s = app.settings.current
        assertEquals(listOf(ToolbarItem.Measure, ToolbarItem.Turntable), s.dockItems)
        assertEquals(listOf(ToolbarItem.Parts), s.sideItems)  // never in both places

        // Unknown or repeated names in stored values are dropped.
        assertEquals(listOf(ToolbarItem.Files, ToolbarItem.Save), ToolbarItem.decode("Files,Nope,Save,Files", emptyList()))

        // A fresh Settings instance reads the same values back.
        val again = Settings(app).current
        assertEquals(s.dockItems, again.dockItems)
        assertEquals(s.sideItems, again.sideItems)

        vm.onToolbarItem(ToolbarItem.View)
        assertEquals(Tool.Lighting, vm.tool.value)
        vm.onToolbarItem(ToolbarItem.Turntable)
        assertTrue(vm.turntable.value)
        vm.onToolbarItem(ToolbarItem.Turntable)
        assertFalse(vm.turntable.value)
    }

    @Test
    fun openingPreferencesApplyToNewModels() {
        val red = 0xFFE53935.toInt()
        vm.updateSettings { it.copy(defaultView = StandardView.Top.id, modelColor = red, defaultMaterial = MaterialPreset.Glossy.ordinal) }
        load("samples/spur_gear.stl")
        assertEquals(-1f, vm.camera.value.forward.z, 1e-3f)
        assertEquals(red, vm.parts.value[0].color)
        assertEquals(MaterialPreset.Glossy, vm.parts.value[0].material)
        // "Original" still means the file's own colour.
        vm.restoreOriginalColors()
        assertEquals(vm.model.value!!.parts[0].fileColor, vm.parts.value[0].color)

        // Multi-part files keep their own colours.
        load("samples/keychain.3mf")
        assertEquals(vm.model.value!!.parts.map { it.fileColor }, vm.parts.value.map { it.color })
    }

    @Test
    fun viewSettingsReachTheRenderer() {
        vm.updateSettings { it.copy(background = BACKGROUND_CUSTOM, bgTop = 0xFF112233.toInt(), bgBottom = 0xFF000000.toInt(), selectColor = 0xFF3EE6C4.toInt()) }
        val o = vm.options.value
        assertEquals(BACKGROUND_CUSTOM, o.background)
        assertEquals(0xFF112233.toInt(), o.bgTop)
        assertEquals(0xFF3EE6C4.toInt(), o.selectColor)

        load("samples/spur_gear.stl")
        val before = vm.engine.camera().distance
        vm.updateSettings { it.copy(fov = 70f) }
        assertTrue("a wider lens moves the camera closer", vm.engine.camera().distance < before)

        vm.updateSettings { it.copy(reopenLast = true) }
        vm.reopenLastIfWanted()  // a model is already open: nothing happens
        assertEquals(Screen.Viewer, vm.screen.value)

        vm.resetSettings()
        assertEquals(0, app.settings.current.background)
        assertFalse(app.settings.current.reopenLast)
    }
}
