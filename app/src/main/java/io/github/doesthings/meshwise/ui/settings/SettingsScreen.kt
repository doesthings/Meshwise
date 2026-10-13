package io.github.doesthings.meshwise.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.BuildConfig
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.ui.components.REPO_URL
import io.github.doesthings.meshwise.ui.components.SectionLabel
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.SupportCard
import io.github.doesthings.meshwise.ui.components.SwitchRow
import io.github.doesthings.meshwise.ui.components.openUrl
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.ViewerViewModel

@Composable
fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().widthIn(max = 720.dp).align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun SettingsScreen(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val themeLabels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    val qualityLabels = listOf(stringResource(R.string.quality_coarse), stringResource(R.string.quality_normal), stringResource(R.string.quality_fine))
    val edgeLabels = listOf(stringResource(R.string.edges_auto), stringResource(R.string.edges_on), stringResource(R.string.edges_off))
    val unitLabels = listOf(stringResource(R.string.unit_mm), stringResource(R.string.unit_cm), stringResource(R.string.unit_in))
    PageScaffold(stringResource(R.string.settings), onBack = { vm.closeSettings() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.appearance))
            Card {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                Segmented(listOf(0, 1, 2), s.themeMode, label = { themeLabels[it] }, onSelect = { m -> vm.updateSettings { it.copy(themeMode = m) } })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(stringResource(R.string.dynamic_color), s.dynamicColor, { v -> vm.updateSettings { it.copy(dynamicColor = v) } })
                }
            }
            SectionLabel(stringResource(R.string.viewer))
            Card {
                SwitchRow(stringResource(R.string.smooth_shading), s.smoothShading, { v -> vm.updateSettings { it.copy(smoothShading = v) } })
                Text("${stringResource(R.string.crease_angle)}: ${s.creaseAngle.toInt()}°", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.crease_angle_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(s.creaseAngle, { v -> vm.updateSettings { it.copy(creaseAngle = v) } }, valueRange = 5f..90f)
                Text(stringResource(R.string.show_edges), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf(0, 1, 2), s.edgesMode, label = { edgeLabels[it] }, onSelect = { m -> vm.updateSettings { it.copy(edgesMode = m) } })
                Text("${stringResource(R.string.rotation_speed)}: ${"%.1f".format(s.sensitivity)}×", style = MaterialTheme.typography.bodyLarge)
                Slider(s.sensitivity, { v -> vm.updateSettings { it.copy(sensitivity = v) } }, valueRange = 0.3f..2.5f)
                SwitchRow(stringResource(R.string.keep_screen_on), s.keepScreenOn, { v -> vm.updateSettings { it.copy(keepScreenOn = v) } })
            }
            SectionLabel(stringResource(R.string.units))
            Card {
                Segmented(Units.entries, s.units, label = { unitLabels[it.ordinal] }, onSelect = { u -> vm.updateSettings { it.copy(units = u) } })
            }
            SectionLabel(stringResource(R.string.cad_import))
            Card {
                Text(stringResource(R.string.cad_quality), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf(0, 1, 2), s.cadQuality, label = { qualityLabels[it] }, onSelect = { q -> vm.updateSettings { it.copy(cadQuality = q) } })
            }
            SectionLabel(stringResource(R.string.storage))
            Card {
                LinkRow(Icons.Rounded.Info, stringResource(R.string.clear_cache)) { vm.clearCache() }
                LinkRow(Icons.Rounded.Info, stringResource(R.string.clear_recents)) { vm.clearRecents() }
            }
            SectionLabel(stringResource(R.string.support))
            SupportCard()
            SectionLabel(stringResource(R.string.about))
            Card {
                LinkRow(Icons.Rounded.Info, stringResource(R.string.about) + " · " + stringResource(R.string.version, BuildConfig.VERSION_NAME)) { vm.navigate(Screen.About) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AboutScreen(vm: ViewerViewModel) {
    val context = LocalContext.current
    PageScaffold(stringResource(R.string.about), onBack = { vm.navigate(Screen.Settings) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A fast, native 3D model viewer for Android: OpenGL ES 3 rendering, a multi-threaded C++ mesh pipeline and an " +
                        "embedded Open CASCADE kernel for STEP and IGES.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinkRow(Icons.Rounded.Code, stringResource(R.string.source_code)) { openUrl(context, REPO_URL) }
            }
            SupportCard()
            Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
            LICENSES.forEach { (name, text) ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Gavel, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val LICENSES = listOf(
    "Meshwise" to "MIT License. Copyright (c) 2026 doesthings.",
    "Open CASCADE Technology 7.8.1" to
        "GNU Lesser General Public License v2.1 with the Open CASCADE exception. Used unmodified for STEP, IGES and BREP " +
        "import. Source: https://github.com/Open-Cascade-SAS/OCCT — you may relink this app against a modified OCCT using " +
        "the build scripts in the Meshwise repository.",
    "AndroidX, Jetpack Compose, Material Components" to "Apache License 2.0. Copyright The Android Open Source Project.",
    "Kotlin & kotlinx.coroutines" to "Apache License 2.0. Copyright JetBrains s.r.o. and contributors.",
    "zlib" to "zlib License. Copyright Jean-loup Gailly and Mark Adler (provided by the Android platform).",
    "Sample models" to "Generated procedurally for Meshwise, released under CC0 1.0.",
)
