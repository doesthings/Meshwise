package io.github.doesthings.meshwise.data

import io.github.doesthings.meshwise.engine.EstimateSettings
import io.github.doesthings.meshwise.engine.Filament
import io.github.doesthings.meshwise.engine.InfillPattern
import io.github.doesthings.meshwise.engine.Supports

/**
 * A printer: its build volume in mm (X × Y × Z, from the maker's published specifications) and
 * the settings its usual slicer starts from for a 0.4 mm nozzle and 0.2 mm layers, which the
 * print estimate uses.
 */
data class Printer(val name: String, val x: Float, val y: Float, val z: Float, val profile: EstimateSettings = Printers.generic) {
    /** Whether a part of this size fits, as is or turned 90° on the bed. */
    fun fits(sx: Float, sy: Float, sz: Float): Boolean = sz <= z && ((sx <= x && sy <= y) || (sx <= y && sy <= x))

    /** Uniform scale that makes a part of this size fit (with a small margin). */
    fun scaleToFit(sx: Float, sy: Float, sz: Float): Float {
        val straight = minOf(x / sx, y / sy)
        val turned = minOf(y / sx, x / sy)
        return minOf(maxOf(straight, turned), z / sz) * 0.98f
    }
}

object Printers {
    const val CUSTOM = "custom"

    // Slicer defaults: Bambu Studio's resolved profiles (0.20mm Standard, Bambu PLA Basic or the
    // vendor's generic PLA) and PrusaSlicer's 0.20mm SPEED for the MK4S-class printers.
    private val bambuX1 = EstimateSettings()  // P1S / P1P / X1C
    private val bambuA1 = bambuX1.copy(
        innerAccel = 6000f, infillAccel = 6000f, solidAccel = 6000f, travelSpeed = 700f, zSpeed = 30f, minLayerTime = 6f,
    )

    /** For a printer we have no profile for: a typical current bed slinger. */
    val generic = EstimateSettings(
        topLayers = 4, topThickness = 0.8f, outerSpeed = 120f, innerSpeed = 180f, infillSpeed = 200f, solidSpeed = 180f,
        topSpeed = 100f, firstLayerSpeed = 40f, firstLayerInfillSpeed = 60f, travelSpeed = 300f, bridgeSpeed = 30f,
        supportSpeed = 100f, supportInterfaceSpeed = 60f, outerAccel = 2000f, innerAccel = 3000f, infillAccel = 3000f,
        solidAccel = 3000f, topAccel = 1500f, travelAccel = 4000f, jerk = 8f, maxFlow = 15f, minLayerTime = 6f,
        minSpeed = 15f, retractSpeed = 40f, zHop = 0.2f, zSpeed = 12f, skirtLoops = 1,
    )

    private val prusaSpeed = EstimateSettings(
        outerWidth = 0.45f, solidWidth = 0.45f, topWidth = 0.42f, supportWidth = 0.4f, topThickness = 0.7f, bottomThickness = 0.5f,
        topOneWall = false, infillAnchor = 2f, minSparseArea = 0f,
        outerSpeed = 170f, innerSpeed = 170f, infillSpeed = 200f, solidSpeed = 200f, topSpeed = 100f, firstLayerSpeed = 40f,
        firstLayerInfillSpeed = 40f, travelSpeed = 300f, bridgeSpeed = 30f, supportSpeed = 120f, supportInterfaceSpeed = 80f,
        overhangSpeeds = listOf(25f, 20f, 15f), smallPerimeterFactor = 1f, outerAccel = 4000f, innerAccel = 4000f,
        infillAccel = 4000f, solidAccel = 4000f, topAccel = 1500f, travelAccel = 4000f, jerk = 8f, maxFlow = 15f,
        minLayerTime = 6f, retractLength = 0.7f, retractSpeed = 35f, zHop = 0.2f, zSpeed = 12f, supportSpacing = 2f,
        supportInterfaceSpacing = 0.2f, supportInterfaceLayers = 3, supportAngle = 40f,
    )

    private val prusaMini = EstimateSettings(
        outerWidth = 0.4f, solidWidth = 0.4f, topWidth = 0.4f, supportWidth = 0.4f, walls = 3, topLayers = 4, topThickness = 0.8f,
        infillOverlap = 0.25f, outerSpeed = 40f, innerSpeed = 50f, infillSpeed = 140f, solidSpeed = 140f, topSpeed = 40f,
        firstLayerSpeed = 20f, firstLayerInfillSpeed = 25f, travelSpeed = 150f, bridgeSpeed = 25f, overhangSpeeds = listOf(30f, 30f, 10f),
        smallPerimeterFactor = 1f, outerAccel = 700f, innerAccel = 1000f, infillAccel = 1000f, solidAccel = 1000f, topAccel = 1000f,
        travelAccel = 1000f, firstLayerAccel = 700f, jerk = 8f, maxFlow = 12f, minLayerTime = 8f, minSpeed = 10f,
        retractLength = 3.2f, retractSpeed = 70f, retractMinTravel = 1.5f, zSpeed = 12f,
    )

    private val ender3 = EstimateSettings(
        outerWidth = 0.4f, solidWidth = 0.4f, topWidth = 0.4f, supportWidth = 0.4f, walls = 3, topLayers = 4, topThickness = 0.8f,
        infillOverlap = 0.23f, outerSpeed = 25f, innerSpeed = 40f, infillSpeed = 50f, solidSpeed = 40f, topSpeed = 30f,
        firstLayerSpeed = 15f, firstLayerInfillSpeed = 20f, travelSpeed = 150f, bridgeSpeed = 25f, overhangSpeeds = listOf(20f, 15f, 10f),
        smallPerimeterFactor = 1f, outerAccel = 500f, innerAccel = 500f, infillAccel = 500f, solidAccel = 500f, topAccel = 500f,
        travelAccel = 500f, firstLayerAccel = 500f, jerk = 8f, maxFlow = 12f, minLayerTime = 8f, minSpeed = 10f,
        retractLength = 5f, retractSpeed = 60f, retractMinTravel = 2f, zHop = 0f, zSpeed = 10f, skirtLoops = 1,
    )

    private val k1 = EstimateSettings(
        walls = 3, topLayers = 3, topThickness = 0.6f, outerSpeed = 200f, innerSpeed = 300f, infillSpeed = 500f, solidSpeed = 300f,
        smallPerimeterFactor = 1f, outerAccel = 5000f, innerAccel = 5000f, infillAccel = 12000f, solidAccel = 12000f,
        topAccel = 5000f, travelAccel = 9000f, firstLayerAccel = 2000f, jerk = 5f, maxFlow = 18f, minLayerTime = 8f, minSpeed = 10f,
        retractLength = 0.5f, retractSpeed = 60f, retractMinTravel = 2f, zHop = 0f, zSpeed = 30f,
    )

    private val neptune4 = EstimateSettings(
        outerWidth = 0.4f, solidWidth = 0.45f, topWidth = 0.38f, firstLayerWidth = 0.42f, supportWidth = 0.38f, walls = 3,
        topLayers = 4, topThickness = 0.8f, infillOverlap = 0.25f, minSparseArea = 25f, outerSpeed = 120f, innerSpeed = 200f,
        infillSpeed = 200f, solidSpeed = 200f, topSpeed = 120f, firstLayerInfillSpeed = 80f, travelSpeed = 350f, bridgeSpeed = 25f,
        supportSpeed = 60f, supportInterfaceSpeed = 60f, smallPerimeterFactor = 1f, outerAccel = 3000f, innerAccel = 3000f,
        infillAccel = 5000f, solidAccel = 5000f, topAccel = 2000f, travelAccel = 5000f, firstLayerAccel = 1000f, maxFlow = 15f,
        minLayerTime = 8f, minSpeed = 10f, retractSpeed = 60f, zSpeed = 12f, skirtLoops = 1, skirtDistance = 1f,
        supportSpacing = 2f, supportInterfaceSpacing = 0.2f,
    )

    val presets = listOf(
        Printer("Bambu Lab A1 mini", 180f, 180f, 180f, bambuA1),
        Printer("Bambu Lab A1", 256f, 256f, 256f, bambuA1),
        Printer("Bambu Lab P1S / P1P / X1C", 256f, 256f, 256f, bambuX1),
        Printer("Prusa MINI+", 180f, 180f, 180f, prusaMini),
        Printer("Prusa MK4 / MK4S", 250f, 210f, 220f, prusaSpeed),
        Printer("Prusa CORE One", 250f, 220f, 270f, prusaSpeed),
        Printer("Prusa XL", 360f, 360f, 360f, prusaSpeed),
        Printer("Creality Ender-3 V2", 220f, 220f, 250f, ender3),
        Printer("Creality K1 / K1C", 220f, 220f, 250f, k1),
        Printer("Creality K1 Max", 300f, 300f, 300f, k1.copy(infillSpeed = 270f, solidSpeed = 250f, infillAccel = 10000f, solidAccel = 10000f)),
        Printer("Elegoo Neptune 4 Pro", 225f, 225f, 265f, neptune4),
    )

    /** The estimate's settings: the printer's slicer defaults with the user's choices and the filament's. */
    fun estimateSettings(s: SettingsValues, filament: Filament): EstimateSettings {
        val base = of(s)?.profile ?: generic
        return base.copy(
            layerHeight = s.layerHeight,
            walls = s.walls.coerceIn(1, 10),
            infillDensity = s.infill.coerceIn(0f, 1f),
            infillPattern = InfillPattern.entries.getOrElse(s.infillPattern) { InfillPattern.Grid },
            supports = Supports.entries.getOrElse(s.supports) { Supports.Off },
            filamentDiameter = s.filamentDiameter,
            flowRatio = filament.flowRatio,
            maxFlow = minOf(base.maxFlow, filament.maxFlow),
        )
    }

    /** The chosen printer, or null when none is picked. */
    fun of(s: SettingsValues): Printer? = when (s.printer) {
        "" -> null
        CUSTOM -> Printer(CUSTOM, s.bedX, s.bedY, s.bedZ)
        else -> presets.firstOrNull { it.name == s.printer }
    }
}
