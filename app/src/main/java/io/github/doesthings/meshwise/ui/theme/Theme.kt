package io.github.doesthings.meshwise.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFFD0BCFF),
    onPrimaryContainer = Color(0xFF2E1A61),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF522300),
    tertiaryContainer = Color(0xFFFF7A1A),
    onTertiaryContainer = Color(0xFF1F0B00),
    background = Color(0xFF1E272C),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E272C),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF141A1E),
    surfaceContainerLow = Color(0xFF232C31),
    surfaceContainer = Color(0xFF2A3237),
    surfaceContainerHigh = Color(0xFF353A40),
    surfaceContainerHighest = Color(0xFF45434C),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF9A4600),
    tertiaryContainer = Color(0xFFFF7A1A),
    onTertiaryContainer = Color(0xFF1F0B00),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F6),
    surfaceContainer = Color(0xFFECECF1),
    surfaceContainerHigh = Color(0xFFE6E5EC),
    surfaceContainerHighest = Color(0xFFDFDEE6),
)

/** An accent the user can pick in Settings. Index 0 keeps the original Meshwise colours. */
@Immutable
data class Accent(val name: String, val hue: Float, val saturation: Float, val swatch: Color)

val Accents = listOf(
    Accent("Meshwise", 262f, 0.60f, Color(0xFF8B6FD6)),
    Accent("Ocean", 212f, 0.70f, Color(0xFF3D8BEA)),
    Accent("Teal", 176f, 0.60f, Color(0xFF1FA89A)),
    Accent("Forest", 140f, 0.45f, Color(0xFF4CAF6E)),
    Accent("Amber", 40f, 0.85f, Color(0xFFE0A022)),
    Accent("Ember", 18f, 0.80f, Color(0xFFE8673A)),
    Accent("Rose", 340f, 0.65f, Color(0xFFD9507E)),
    Accent("Graphite", 215f, 0.10f, Color(0xFF8A9099)),
)

private fun hsl(h: Float, s: Float, l: Float) = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** A full Material scheme generated from an accent hue (surfaces get a faint tint of it). */
private fun accentScheme(a: Accent, dark: Boolean): ColorScheme {
    val h = a.hue
    val s = a.saturation
    val tint = (s * 0.18f).coerceAtMost(0.12f)
    return if (dark) darkColorScheme(
        primary = hsl(h, s, 0.78f),
        onPrimary = hsl(h, s, 0.18f),
        primaryContainer = hsl(h, s * 0.9f, 0.76f),
        onPrimaryContainer = hsl(h, s, 0.15f),
        secondary = hsl(h, s * 0.35f, 0.78f),
        onSecondary = hsl(h, s * 0.35f, 0.20f),
        secondaryContainer = hsl(h, s * 0.30f, 0.30f),
        onSecondaryContainer = hsl(h, s * 0.35f, 0.90f),
        tertiary = Color(0xFFFFB68A),
        onTertiary = Color(0xFF522300),
        tertiaryContainer = Color(0xFFFF7A1A),
        onTertiaryContainer = Color(0xFF1F0B00),
        background = hsl(h, tint, 0.105f),
        onBackground = hsl(h, 0.08f, 0.90f),
        surface = hsl(h, tint, 0.105f),
        onSurface = hsl(h, 0.08f, 0.90f),
        surfaceVariant = hsl(h, tint, 0.28f),
        onSurfaceVariant = hsl(h, 0.10f, 0.79f),
        surfaceContainerLowest = hsl(h, tint, 0.07f),
        surfaceContainerLow = hsl(h, tint, 0.125f),
        surfaceContainer = hsl(h, tint, 0.15f),
        surfaceContainerHigh = hsl(h, tint, 0.19f),
        surfaceContainerHighest = hsl(h, tint, 0.24f),
        outline = hsl(h, 0.06f, 0.57f),
        outlineVariant = hsl(h, tint, 0.30f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    ) else lightColorScheme(
        primary = hsl(h, s, 0.40f),
        onPrimary = Color.White,
        primaryContainer = hsl(h, s, 0.90f),
        onPrimaryContainer = hsl(h, s, 0.14f),
        secondary = hsl(h, s * 0.30f, 0.40f),
        secondaryContainer = hsl(h, s * 0.45f, 0.90f),
        onSecondaryContainer = hsl(h, s * 0.40f, 0.14f),
        tertiary = Color(0xFF9A4600),
        tertiaryContainer = Color(0xFFFF7A1A),
        onTertiaryContainer = Color(0xFF1F0B00),
        background = hsl(h, tint * 1.6f, 0.975f),
        surface = hsl(h, tint * 1.6f, 0.975f),
        surfaceVariant = hsl(h, tint * 1.6f, 0.90f),
        onSurfaceVariant = hsl(h, 0.08f, 0.32f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = hsl(h, tint * 1.6f, 0.955f),
        surfaceContainer = hsl(h, tint * 1.6f, 0.935f),
        surfaceContainerHigh = hsl(h, tint * 1.6f, 0.915f),
        surfaceContainerHighest = hsl(h, tint * 1.6f, 0.89f),
        outline = hsl(h, 0.06f, 0.50f),
        outlineVariant = hsl(h, tint, 0.80f),
    )
}

/** Pure black backgrounds for OLED screens. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF19191C),
    surfaceContainerHighest = Color(0xFF222226),
)

/** Extra colours not covered by the Material scheme. */
@Immutable
data class ExtraColors(
    val accent: Color,          // signature orange (colour tool)
    val onAccent: Color,
    val measure: Color,         // measurement highlight
    val overlayButton: Color,   // floating buttons over the 3D view
    val onOverlayButton: Color,
    val panel: Color,           // tool panels over the 3D view
    val axisX: Color,
    val axisY: Color,
    val axisZ: Color,
    val dimension: Color,
)

private val DarkExtra = ExtraColors(
    accent = Color(0xFFFF6D00),
    onAccent = Color(0xFF1A0A00),
    measure = Color(0xFFFF4D8D),
    overlayButton = Color(0xEB45434C),
    onOverlayButton = Color(0xFFE6E1E5),
    panel = Color(0xF2403D47),
    axisX = Color(0xFFFF6B6B),
    axisY = Color(0xFF7BD88F),
    axisZ = Color(0xFF6CB4FF),
    dimension = Color(0xFF3EE6C4),
)

private val LightExtra = DarkExtra.copy(
    overlayButton = Color(0xEBFFFFFF),
    onOverlayButton = Color(0xFF1C1B1F),
    panel = Color(0xF5FFFFFF),
    dimension = Color(0xFF00897B),
)

val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

/** Interface preferences that shape the 3D view's controls. */
@Immutable
data class UiPrefs(
    val scale: Int = 1,              // 0 compact, 1 normal, 2 large
    val showLabels: Boolean = false,
    val haptics: Boolean = true,
    val panelOpacity: Float = 0.94f,
) {
    val buttonSize: Dp get() = when (scale) { 0 -> 46.dp; 2 -> 60.dp; else -> 52.dp }
    val dockButton: Dp get() = when (scale) { 0 -> 44.dp; 2 -> 58.dp; else -> 50.dp }
    val iconSize: Dp get() = when (scale) { 0 -> 22.dp; 2 -> 28.dp; else -> 24.dp }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

val ValueStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 20.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MeshwiseTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = false,
    accent: Int = 0,
    pureBlack: Boolean = false,
    uiPrefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accent in 1 until Accents.size -> accentScheme(Accents[accent], dark)
        dark -> DarkColors
        else -> LightColors
    }
    if (dark && pureBlack) scheme = scheme.pureBlack()
    val alpha = uiPrefs.panelOpacity.coerceIn(0.5f, 1f)
    val extra = if (dark) {
        DarkExtra.copy(
            overlayButton = scheme.surfaceContainerHighest.copy(alpha = alpha),
            onOverlayButton = scheme.onSurface,
            panel = scheme.surfaceContainerHigh.copy(alpha = alpha),
        )
    } else {
        LightExtra.copy(
            overlayButton = scheme.surfaceContainerLowest.copy(alpha = alpha),
            onOverlayButton = scheme.onSurface,
            panel = scheme.surfaceContainerLowest.copy(alpha = alpha),
        )
    }
    CompositionLocalProvider(LocalExtraColors provides extra, LocalUiPrefs provides uiPrefs) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), shapes = AppShapes, content = content)
    }
}
