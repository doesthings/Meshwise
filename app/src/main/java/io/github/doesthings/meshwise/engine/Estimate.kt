package io.github.doesthings.meshwise.engine

/**
 * Slicer settings for [Engine.estimatePrint] (see native/core/print_estimate.h). Lengths in mm,
 * speeds in mm/s, accelerations in mm/s², densities as fractions. The defaults are Bambu Studio's
 * "0.20mm Standard" for a 0.4 mm nozzle; printers start from their own slicer defaults
 * ([io.github.doesthings.meshwise.data.Printer.profile]).
 */
data class EstimateSettings(
    val layerHeight: Float = 0.2f,
    val firstLayerHeight: Float = 0.2f,
    val outerWidth: Float = 0.42f,
    val innerWidth: Float = 0.45f,
    val infillWidth: Float = 0.45f,
    val solidWidth: Float = 0.42f,
    val topWidth: Float = 0.42f,
    val firstLayerWidth: Float = 0.5f,
    val supportWidth: Float = 0.42f,
    val walls: Int = 2,
    val topLayers: Int = 5,
    val bottomLayers: Int = 3,
    val topThickness: Float = 1.0f,
    val bottomThickness: Float = 0f,
    val topOneWall: Boolean = true,
    val infillDensity: Float = 0.15f,
    val infillPattern: InfillPattern = InfillPattern.Grid,
    val infillOverlap: Float = 0.15f,
    val infillAnchor: Float = 1.8f,
    val minSparseArea: Float = 15f,
    val outerSpeed: Float = 200f,
    val innerSpeed: Float = 300f,
    val infillSpeed: Float = 270f,
    val solidSpeed: Float = 250f,
    val topSpeed: Float = 200f,
    val firstLayerSpeed: Float = 50f,
    val firstLayerInfillSpeed: Float = 105f,
    val travelSpeed: Float = 500f,
    val bridgeSpeed: Float = 50f,
    val supportSpeed: Float = 150f,
    val supportInterfaceSpeed: Float = 80f,
    /** Outer wall speed when 25–50 %, 50–75 % and 75 % or more of the line overhangs; 0 = no slowdown. */
    val overhangSpeeds: List<Float> = listOf(50f, 30f, 10f),
    val smallPerimeterFactor: Float = 0.5f,
    val smallPerimeterRadius: Float = 0f,
    val outerAccel: Float = 5000f,
    val innerAccel: Float = 10000f,
    val infillAccel: Float = 10000f,
    val solidAccel: Float = 10000f,
    val topAccel: Float = 2000f,
    val travelAccel: Float = 9000f,
    val firstLayerAccel: Float = 500f,
    val jerk: Float = 9f,
    val maxFlow: Float = 21f,
    val minLayerTime: Float = 4f,
    val minSpeed: Float = 20f,
    val retractLength: Float = 0.8f,
    val retractSpeed: Float = 30f,
    val retractMinTravel: Float = 1f,
    val zHop: Float = 0.4f,
    val zSpeed: Float = 20f,
    val skirtLoops: Int = 0,
    val skirtDistance: Float = 2f,
    val supports: Supports = Supports.Off,
    val supportAngle: Float = 30f,
    val supportSpacing: Float = 2.5f,
    val supportInterfaceSpacing: Float = 0.5f,
    val supportInterfaceLayers: Int = 2,
    val filamentDiameter: Float = 1.75f,
    val flowRatio: Float = 0.98f,
) {
    /** The layout nativeEstimatePrint reads. */
    fun floats(): FloatArray = floatArrayOf(
        layerHeight, firstLayerHeight, outerWidth, innerWidth, infillWidth, solidWidth, topWidth, firstLayerWidth, supportWidth,
        topThickness, bottomThickness, infillDensity, infillOverlap, infillAnchor, minSparseArea,
        outerSpeed, innerSpeed, infillSpeed, solidSpeed, topSpeed, firstLayerSpeed, firstLayerInfillSpeed, travelSpeed, bridgeSpeed,
        supportSpeed, supportInterfaceSpeed, overhangSpeeds[0], overhangSpeeds[1], overhangSpeeds[2], smallPerimeterFactor,
        smallPerimeterRadius, outerAccel, innerAccel, infillAccel, solidAccel, topAccel, travelAccel, firstLayerAccel, jerk,
        maxFlow, minLayerTime, minSpeed, retractLength, retractSpeed, retractMinTravel, zHop, zSpeed, skirtDistance,
        supportAngle, supportSpacing, supportInterfaceSpacing, filamentDiameter, flowRatio,
    )

    fun ints(): IntArray = intArrayOf(
        walls, topLayers, bottomLayers, if (topOneWall) 1 else 0, infillPattern.native, skirtLoops,
        if (supports != Supports.Off) 1 else 0, if (supports == Supports.Tree) 1 else 0, supportInterfaceLayers,
    )
}

enum class InfillPattern(val native: Int) { Lines(0), Grid(1), Cubic(2), Gyroid(3) }

enum class Supports { Off, Normal, Tree }

/** A filament's properties for the estimate: density (g/cm³), the slicer's flow ratio and how fast it melts (mm³/s). */
data class Filament(val density: Double, val flowRatio: Float, val maxFlow: Float) {
    companion object {
        /** FDM materials, from the slicers' generic profiles; null for materials that are not printed this way. */
        fun of(preset: DensityPreset): Filament? = when (preset) {
            DensityPreset.PLA -> Filament(preset.density, 0.98f, Float.MAX_VALUE)
            DensityPreset.PETG -> Filament(preset.density, 0.95f, 12f)
            DensityPreset.ABS, DensityPreset.ASA -> Filament(preset.density, 0.95f, 16f)
            DensityPreset.TPU -> Filament(preset.density, 1.0f, 3.6f)
            DensityPreset.Nylon -> Filament(preset.density, 0.96f, 12f)
            DensityPreset.PC -> Filament(preset.density, 0.94f, 12f)
            else -> null
        }
    }
}

/** What [Engine.estimatePrint] works out. Times in seconds. */
data class PrintEstimate(
    val seconds: Double,
    val volumeMm3: Double,
    val filamentMm: Double,
    val supportMm3: Double,
    val supportAreaMm2: Double,
    val layers: Int,
    val outerWall: Double,
    val innerWall: Double,
    val sparseInfill: Double,
    val solidInfill: Double,
    val topSurface: Double,
    val bottomSurface: Double,
    val bridge: Double,
    val support: Double,
    val skirt: Double,
    val travel: Double,
) {
    fun grams(density: Double) = volumeMm3 * density / 1000.0
    fun supportGrams(density: Double) = supportMm3 * density / 1000.0

    companion object {
        fun of(v: DoubleArray) = PrintEstimate(
            v[0], v[1], v[2], v[3], v[4], v[5].toInt(), v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15],
        )
    }
}
