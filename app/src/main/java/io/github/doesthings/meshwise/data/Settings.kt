package io.github.doesthings.meshwise.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units(val label: String, val factor: Double, val suffix: String) {
    MM("mm", 1.0, "mm"), CM("cm", 0.1, "cm"), IN("in", 1.0 / 25.4, "in");
}

data class SettingsValues(
    val themeMode: Int = 0,          // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = false,
    val background: Int = 0,
    val lighting: Int = 0,
    val exposure: Float = 1f,
    val smoothShading: Boolean = true,
    val creaseAngle: Float = 35f,
    val grid: Boolean = true,
    val shadows: Boolean = true,
    val axes: Boolean = false,
    val edgesMode: Int = 0,          // 0 auto (CAD), 1 always, 2 never
    val orthographic: Boolean = false,
    val sensitivity: Float = 1f,
    val units: Units = Units.MM,
    val cadQuality: Int = 1,
    val keepScreenOn: Boolean = true,
    val density: Int = 0,            // DensityPreset ordinal
    val infill: Float = 0.2f,
    val walls: Int = 2,
    val pricePerKg: Float = 20f,
)

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
        return SettingsValues(
            themeMode = prefs.getInt("themeMode", d.themeMode),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            background = prefs.getInt("background", d.background),
            lighting = prefs.getInt("lighting", d.lighting),
            exposure = prefs.getFloat("exposure", d.exposure),
            smoothShading = prefs.getBoolean("smoothShading", d.smoothShading),
            creaseAngle = prefs.getFloat("creaseAngle", d.creaseAngle),
            grid = prefs.getBoolean("grid", d.grid),
            shadows = prefs.getBoolean("shadows", d.shadows),
            axes = prefs.getBoolean("axes", d.axes),
            edgesMode = prefs.getInt("edgesMode", d.edgesMode),
            orthographic = prefs.getBoolean("orthographic", d.orthographic),
            sensitivity = prefs.getFloat("sensitivity", d.sensitivity),
            units = Units.entries.getOrElse(prefs.getInt("units", d.units.ordinal)) { Units.MM },
            cadQuality = prefs.getInt("cadQuality", d.cadQuality),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            density = prefs.getInt("density", d.density),
            infill = prefs.getFloat("infill", d.infill),
            walls = prefs.getInt("walls", d.walls),
            pricePerKg = prefs.getFloat("pricePerKg", d.pricePerKg),
        )
    }

    private fun write(v: SettingsValues) {
        prefs.edit()
            .putInt("themeMode", v.themeMode)
            .putBoolean("dynamicColor", v.dynamicColor)
            .putInt("background", v.background)
            .putInt("lighting", v.lighting)
            .putFloat("exposure", v.exposure)
            .putBoolean("smoothShading", v.smoothShading)
            .putFloat("creaseAngle", v.creaseAngle)
            .putBoolean("grid", v.grid)
            .putBoolean("shadows", v.shadows)
            .putBoolean("axes", v.axes)
            .putInt("edgesMode", v.edgesMode)
            .putBoolean("orthographic", v.orthographic)
            .putFloat("sensitivity", v.sensitivity)
            .putInt("units", v.units.ordinal)
            .putInt("cadQuality", v.cadQuality)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putInt("density", v.density)
            .putFloat("infill", v.infill)
            .putInt("walls", v.walls)
            .putFloat("pricePerKg", v.pricePerKg)
            .apply()
    }
}
