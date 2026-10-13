package io.github.doesthings.meshwise.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units(val label: String, val factor: Double, val suffix: String) {
    MM("mm", 1.0, "mm"), CM("cm", 0.1, "cm"), IN("in", 1.0 / 25.4, "in");
}

/** Buttons that can be placed in the bottom dock or the side cluster of the 3D view. */
enum class ToolbarItem {
    Files, Save, Color, Weight, Measure, Analyze, View, Material, Section, Explode, Parts, Info, Snapshot, Turntable;

    companion object {
        val DEFAULT_DOCK = listOf(Files, Save, Color, Weight, Measure, Analyze)
        val DEFAULT_SIDE = listOf(View, Material, Section, Explode, Parts)

        fun decode(s: String, fallback: List<ToolbarItem>): List<ToolbarItem> =
            if (s.isBlank()) fallback else s.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n } }.distinct()

        fun encode(items: List<ToolbarItem>): String = items.joinToString(",") { it.name }
    }
}

data class SettingsValues(
    // Appearance
    val themeMode: Int = 0,          // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = false,
    val accent: Int = 0,             // index into ui.theme.Accents
    val pureBlack: Boolean = false,
    val uiScale: Int = 1,            // 0 compact, 1 normal, 2 large
    val panelOpacity: Float = 0.94f,
    val showLabels: Boolean = false,
    val leftHanded: Boolean = false,
    val showViewCube: Boolean = true,
    val showInfoBar: Boolean = true,
    val haptics: Boolean = true,
    val dock: String = "",           // ToolbarItem names; blank = default
    val side: String = "",
    // 3D view
    val background: Int = 0,         // preset index, or BACKGROUND_CUSTOM
    val bgTop: Int = 0xFF3C4C55.toInt(),
    val bgBottom: Int = 0xFF222B31.toInt(),
    val lighting: Int = 0,
    val exposure: Float = 1f,
    val smoothShading: Boolean = true,
    val creaseAngle: Float = 35f,
    val grid: Boolean = true,
    val shadows: Boolean = true,
    val axes: Boolean = false,
    val edgesMode: Int = 0,          // 0 auto (CAD), 1 always, 2 never
    val orthographic: Boolean = false,
    val fov: Float = 35f,            // degrees
    val defaultView: Int = 6,        // StandardView id (6 = isometric)
    val modelColor: Int = 0,         // ARGB for models without colours; 0 = built-in grey
    val defaultMaterial: Int = 0,    // MaterialPreset ordinal
    val selectColor: Int = 0xFF7FB3FF.toInt(),
    // Controls
    val sensitivity: Float = 1f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val doubleTap: Int = 0,          // 0 zoom to point, 1 fit view, 2 nothing
    val turntableSpeed: Float = 1f,  // multiplier; negative spins the other way
    val keepScreenOn: Boolean = true,
    // Measurements
    val units: Units = Units.MM,
    val decimals: Int = 2,
    // Files
    val recentsGrid: Boolean = true,
    val recentsSort: Int = 0,        // 0 last opened, 1 name, 2 size
    val reopenLast: Boolean = false,
    val cadQuality: Int = 1,
    // Weight
    val density: Int = 0,            // DensityPreset ordinal
    val infill: Float = 0.2f,
    val walls: Int = 2,
    val pricePerKg: Float = 20f,
    // Updates
    val updateChecks: Int = UPDATES_NOT_ASKED,
    val lastUpdateCheck: Long = 0L,
    val skippedUpdate: String = "",
) {
    val dockItems: List<ToolbarItem> get() = ToolbarItem.decode(dock, ToolbarItem.DEFAULT_DOCK)
    val sideItems: List<ToolbarItem> get() = ToolbarItem.decode(side, ToolbarItem.DEFAULT_SIDE).filter { it !in dockItems }

    /** Everything a "reset settings" should restore, keeping answers the user gave elsewhere. */
    fun resetKeepingState(): SettingsValues = SettingsValues(
        density = density, infill = infill, walls = walls, pricePerKg = pricePerKg,
        updateChecks = updateChecks, lastUpdateCheck = lastUpdateCheck, skippedUpdate = skippedUpdate,
    )
}

/** [SettingsValues.updateChecks]: the user hasn't been asked yet, said yes, or said no. */
const val UPDATES_NOT_ASKED = 0
const val UPDATES_ON = 1
const val UPDATES_OFF = 2

/** [SettingsValues.background] value for the user's own gradient (matches the native renderer). */
const val BACKGROUND_CUSTOM = -1

/** App settings persisted in SharedPreferences and exposed as a StateFlow. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val values: StateFlow<SettingsValues> = state.asStateFlow()
    val current: SettingsValues get() = state.value

    fun update(transform: (SettingsValues) -> SettingsValues) {
        val v = transform(state.value)
        state.value = v
        write(v)
    }

    private fun read(): SettingsValues {
        val d = SettingsValues()
        val p = prefs
        return SettingsValues(
            themeMode = p.int("themeMode", d.themeMode),
            dynamicColor = p.bool("dynamicColor", d.dynamicColor),
            accent = p.int("accent", d.accent),
            pureBlack = p.bool("pureBlack", d.pureBlack),
            uiScale = p.int("uiScale", d.uiScale),
            panelOpacity = p.float("panelOpacity", d.panelOpacity),
            showLabels = p.bool("showLabels", d.showLabels),
            leftHanded = p.bool("leftHanded", d.leftHanded),
            showViewCube = p.bool("showViewCube", d.showViewCube),
            showInfoBar = p.bool("showInfoBar", d.showInfoBar),
            haptics = p.bool("haptics", d.haptics),
            dock = p.string("dock", d.dock),
            side = p.string("side", d.side),
            background = p.int("background", d.background),
            bgTop = p.int("bgTop", d.bgTop),
            bgBottom = p.int("bgBottom", d.bgBottom),
            lighting = p.int("lighting", d.lighting),
            exposure = p.float("exposure", d.exposure),
            smoothShading = p.bool("smoothShading", d.smoothShading),
            creaseAngle = p.float("creaseAngle", d.creaseAngle),
            grid = p.bool("grid", d.grid),
            shadows = p.bool("shadows", d.shadows),
            axes = p.bool("axes", d.axes),
            edgesMode = p.int("edgesMode", d.edgesMode),
            orthographic = p.bool("orthographic", d.orthographic),
            fov = p.float("fov", d.fov),
            defaultView = p.int("defaultView", d.defaultView),
            modelColor = p.int("modelColor", d.modelColor),
            defaultMaterial = p.int("defaultMaterial", d.defaultMaterial),
            selectColor = p.int("selectColor", d.selectColor),
            sensitivity = p.float("sensitivity", d.sensitivity),
            invertX = p.bool("invertX", d.invertX),
            invertY = p.bool("invertY", d.invertY),
            doubleTap = p.int("doubleTap", d.doubleTap),
            turntableSpeed = p.float("turntableSpeed", d.turntableSpeed),
            keepScreenOn = p.bool("keepScreenOn", d.keepScreenOn),
            units = Units.entries.getOrElse(p.int("units", d.units.ordinal)) { Units.MM },
            decimals = p.int("decimals", d.decimals),
            recentsGrid = p.bool("recentsGrid", d.recentsGrid),
            recentsSort = p.int("recentsSort", d.recentsSort),
            reopenLast = p.bool("reopenLast", d.reopenLast),
            cadQuality = p.int("cadQuality", d.cadQuality),
            density = p.int("density", d.density),
            infill = p.float("infill", d.infill),
            walls = p.int("walls", d.walls),
            pricePerKg = p.float("pricePerKg", d.pricePerKg),
            updateChecks = p.int("updateChecks", d.updateChecks),
            lastUpdateCheck = p.getLong("lastUpdateCheck", d.lastUpdateCheck),
            skippedUpdate = p.string("skippedUpdate", d.skippedUpdate),
        )
    }

    private fun write(v: SettingsValues) {
        prefs.edit()
            .putInt("themeMode", v.themeMode)
            .putBoolean("dynamicColor", v.dynamicColor)
            .putInt("accent", v.accent)
            .putBoolean("pureBlack", v.pureBlack)
            .putInt("uiScale", v.uiScale)
            .putFloat("panelOpacity", v.panelOpacity)
            .putBoolean("showLabels", v.showLabels)
            .putBoolean("leftHanded", v.leftHanded)
            .putBoolean("showViewCube", v.showViewCube)
            .putBoolean("showInfoBar", v.showInfoBar)
            .putBoolean("haptics", v.haptics)
            .putString("dock", v.dock)
            .putString("side", v.side)
            .putInt("background", v.background)
            .putInt("bgTop", v.bgTop)
            .putInt("bgBottom", v.bgBottom)
            .putInt("lighting", v.lighting)
            .putFloat("exposure", v.exposure)
            .putBoolean("smoothShading", v.smoothShading)
            .putFloat("creaseAngle", v.creaseAngle)
            .putBoolean("grid", v.grid)
            .putBoolean("shadows", v.shadows)
            .putBoolean("axes", v.axes)
            .putInt("edgesMode", v.edgesMode)
            .putBoolean("orthographic", v.orthographic)
            .putFloat("fov", v.fov)
            .putInt("defaultView", v.defaultView)
            .putInt("modelColor", v.modelColor)
            .putInt("defaultMaterial", v.defaultMaterial)
            .putInt("selectColor", v.selectColor)
            .putFloat("sensitivity", v.sensitivity)
            .putBoolean("invertX", v.invertX)
            .putBoolean("invertY", v.invertY)
            .putInt("doubleTap", v.doubleTap)
            .putFloat("turntableSpeed", v.turntableSpeed)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putInt("units", v.units.ordinal)
            .putInt("decimals", v.decimals)
            .putBoolean("recentsGrid", v.recentsGrid)
            .putInt("recentsSort", v.recentsSort)
            .putBoolean("reopenLast", v.reopenLast)
            .putInt("cadQuality", v.cadQuality)
            .putInt("density", v.density)
            .putFloat("infill", v.infill)
            .putInt("walls", v.walls)
            .putFloat("pricePerKg", v.pricePerKg)
            .putInt("updateChecks", v.updateChecks)
            .putLong("lastUpdateCheck", v.lastUpdateCheck)
            .putString("skippedUpdate", v.skippedUpdate)
            .apply()
    }

    private fun SharedPreferences.int(k: String, d: Int) = getInt(k, d)
    private fun SharedPreferences.bool(k: String, d: Boolean) = getBoolean(k, d)
    private fun SharedPreferences.float(k: String, d: Float) = getFloat(k, d)
    private fun SharedPreferences.string(k: String, d: String) = getString(k, d) ?: d
}
