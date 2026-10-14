package io.github.doesthings.meshwise.data

/** A printer's build volume in mm (X × Y × Z), from the maker's published specifications. */
data class Printer(val name: String, val x: Float, val y: Float, val z: Float) {
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

    val presets = listOf(
        Printer("Bambu Lab A1 mini", 180f, 180f, 180f),
        Printer("Bambu Lab A1", 256f, 256f, 256f),
        Printer("Bambu Lab P1S / P1P / X1C", 256f, 256f, 256f),
        Printer("Prusa MINI+", 180f, 180f, 180f),
        Printer("Prusa MK4 / MK4S", 250f, 210f, 220f),
        Printer("Prusa CORE One", 250f, 220f, 270f),
        Printer("Prusa XL", 360f, 360f, 360f),
        Printer("Creality Ender-3 V2", 220f, 220f, 250f),
        Printer("Creality K1 / K1C", 220f, 220f, 250f),
        Printer("Creality K1 Max", 300f, 300f, 300f),
        Printer("Elegoo Neptune 4 Pro", 225f, 225f, 265f),
    )

    /** The chosen printer, or null when none is picked. */
    fun of(s: SettingsValues): Printer? = when (s.printer) {
        "" -> null
        CUSTOM -> Printer(CUSTOM, s.bedX, s.bedY, s.bedZ)
        else -> presets.firstOrNull { it.name == s.printer }
    }
}
