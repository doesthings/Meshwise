package io.github.doesthings.meshwise.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

val ValueStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 20.sp)

@Composable
fun MeshwiseTheme(themeMode: Int = 0, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
