package io.github.doesthings.meshwise.engine

/** Surface material presets. Metals also define a colour. */
enum class MaterialPreset(
    val label: String,
    val roughness: Float,
    val metalness: Float,
    val clearcoat: Float,
    val color: Int? = null,
) {
    Matte("Matte plastic", 0.62f, 0f, 0f),
    Satin("Satin plastic", 0.40f, 0f, 0.15f),
    Glossy("Glossy plastic", 0.22f, 0f, 0.6f),
    Resin("Resin", 0.14f, 0f, 0.9f),
    Rubber("Rubber", 0.92f, 0f, 0f),
    Ceramic("Ceramic", 0.30f, 0f, 1.0f),
    Steel("Steel", 0.32f, 1f, 0f, 0xFFA0A3A7.toInt()),
    Aluminum("Aluminum", 0.26f, 1f, 0f, 0xFFD9DBDE.toInt()),
    Chrome("Chrome", 0.06f, 1f, 0f, 0xFFDEDFE0.toInt()),
    Gold("Gold", 0.22f, 1f, 0f, 0xFFFFC757.toInt()),
    Silver("Silver", 0.18f, 1f, 0f, 0xFFEAE9E8.toInt()),
    Copper("Copper", 0.25f, 1f, 0f, 0xFFF5A58A.toInt()),
    Brass("Brass", 0.28f, 1f, 0f, 0xFFE3C26E.toInt()),
    Titanium("Titanium", 0.35f, 1f, 0f, 0xFFB5ACA2.toInt());

    val isMetal: Boolean get() = metalness > 0.5f
}

/** Density presets for the weight calculator (g/cm³). */
enum class DensityPreset(val label: String, val density: Double, val fdm: Boolean) {
    PLA("PLA", 1.24, true),
    PETG("PETG", 1.27, true),
    ABS("ABS", 1.04, true),
    ASA("ASA", 1.07, true),
    TPU("TPU", 1.21, true),
    Nylon("Nylon (PA12)", 1.01, true),
    PC("Polycarbonate", 1.20, true),
    Resin("Standard resin", 1.15, false),
    Steel("Steel", 7.85, false),
    Stainless("Stainless steel", 8.00, false),
    Aluminum("Aluminum", 2.70, false),
    Titanium("Titanium", 4.43, false),
    Brass("Brass", 8.50, false),
    Copper("Copper", 8.96, false),
    Silver("Silver", 10.49, false),
    Gold("Gold", 19.32, false),
    Water("Water", 1.00, false),
}

object Palette {
    /** Swatches offered by the colour tool (ARGB). */
    val swatches: List<Int> = listOf(
        0xFFCCCCCA, 0xFFF2F2F2, 0xFF3A3A3C, 0xFF1C1C1E,
        0xFFE53935, 0xFFFF7043, 0xFFFFA000, 0xFFFDD835,
        0xFFF2E35A, 0xFF7CB342, 0xFF2E7D32, 0xFF26A69A,
        0xFF29B6F6, 0xFF1E88E5, 0xFF3949AB, 0xFF7E57C2,
        0xFFAB47BC, 0xFFEC407A, 0xFF8D6E63, 0xFFA8352C,
    ).map { it.toInt() }

    /** Distinct colours used by "colour by part". */
    fun partColor(i: Int): Int {
        val golden = 0.618034f
        val h = (i * golden) % 1f
        return android.graphics.Color.HSVToColor(floatArrayOf(h * 360f, 0.48f, 0.92f))
    }
}

object Backgrounds {
    val names = listOf("Slate", "Graphite", "Light", "Ocean", "Black", "White")
    /** Approximate preview colours (top, bottom) for the picker. */
    val previews: List<Pair<Int, Int>> = listOf(
        0xFF3C4C55.toInt() to 0xFF222B31.toInt(),
        0xFF2A2A2E.toInt() to 0xFF121214.toInt(),
        0xFFF6F7F9.toInt() to 0xFFD1D6DC.toInt(),
        0xFF455C8F.toInt() to 0xFF17213A.toInt(),
        0xFF000000.toInt() to 0xFF000000.toInt(),
        0xFFFFFFFF.toInt() to 0xFFFFFFFF.toInt(),
    )
    fun isDark(i: Int) = i != 2 && i != 5
}

object Lighting {
    val names = listOf("Studio", "Soft", "Dramatic", "Flat", "Headlight")
}
