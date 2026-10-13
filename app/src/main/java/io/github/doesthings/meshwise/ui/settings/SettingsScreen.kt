package io.github.doesthings.meshwise.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.BuildConfig
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.UPDATES_ON
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.Palette
import io.github.doesthings.meshwise.engine.StandardView
import io.github.doesthings.meshwise.ui.components.ColorSwatch
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.REPO_URL
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.SupportCard
import io.github.doesthings.meshwise.ui.components.SwitchRow
import io.github.doesthings.meshwise.ui.components.openUrl
import io.github.doesthings.meshwise.ui.panels.SliderRow
import io.github.doesthings.meshwise.ui.theme.Accents
import io.github.doesthings.meshwise.ui.viewer.icon
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import kotlin.math.abs

@Composable
fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().widthIn(max = 720.dp).align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.06f)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

/** A titled group of settings in a rounded card. */
@Composable
fun SettingsGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
        }
        Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun Labeled(title: String, summary: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }
    val themeLabels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    val qualityLabels = listOf(stringResource(R.string.quality_coarse), stringResource(R.string.quality_normal), stringResource(R.string.quality_fine))
    val edgeLabels = listOf(stringResource(R.string.edges_auto), stringResource(R.string.edges_on), stringResource(R.string.edges_off))
    val unitLabels = listOf(stringResource(R.string.unit_mm), stringResource(R.string.unit_cm), stringResource(R.string.unit_in))
    val sizeLabels = listOf(stringResource(R.string.size_compact), stringResource(R.string.size_normal), stringResource(R.string.size_large))
    val tapLabels = listOf(stringResource(R.string.double_tap_zoom), stringResource(R.string.double_tap_fit), stringResource(R.string.double_tap_none))
    val views = listOf(
        StandardView.Iso to R.string.view_iso, StandardView.Front to R.string.view_front, StandardView.Top to R.string.view_top,
        StandardView.Right to R.string.view_right, StandardView.Left to R.string.view_left, StandardView.Back to R.string.view_back,
    )
    PageScaffold(stringResource(R.string.settings), onBack = { vm.closeSettings() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsGroup(stringResource(R.string.appearance), Icons.Rounded.Palette) {
                Labeled(stringResource(R.string.theme)) {
                    Segmented(listOf(0, 1, 2), s.themeMode, label = { themeLabels[it] }, onSelect = { m -> vm.updateSettings { it.copy(themeMode = m) } })
                }
                Labeled(stringResource(R.string.accent_color)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Accents.forEachIndexed { i, a ->
                            ColorSwatch(a.swatch, selected = !s.dynamicColor && s.accent == i, size = 30.dp) {
                                vm.updateSettings { it.copy(accent = i, dynamicColor = false) }
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(stringResource(R.string.dynamic_color), s.dynamicColor, { v -> vm.updateSettings { it.copy(dynamicColor = v) } }, summary = stringResource(R.string.dynamic_color_summary))
                }
                SwitchRow(stringResource(R.string.pure_black), s.pureBlack, { v -> vm.updateSettings { it.copy(pureBlack = v) } }, summary = stringResource(R.string.pure_black_summary))
            }

            SettingsGroup(stringResource(R.string.interface_group), Icons.Rounded.Dashboard) {
                ToolbarSummary(s.dockItems.map { it.icon }, s.sideItems.map { it.icon }) { vm.navigate(Screen.Toolbar) }
                Divider()
                Labeled(stringResource(R.string.interface_size)) {
                    Segmented(listOf(0, 1, 2), s.uiScale, label = { sizeLabels[it] }, onSelect = { v -> vm.updateSettings { it.copy(uiScale = v) } })
                }
                SliderRow(stringResource(R.string.panel_opacity), "${(s.panelOpacity * 100).toInt()}%", s.panelOpacity, 0.55f..1f) { v ->
                    vm.updateSettings { it.copy(panelOpacity = v) }
                }
                SwitchRow(stringResource(R.string.show_labels), s.showLabels, { v -> vm.updateSettings { it.copy(showLabels = v) } }, summary = stringResource(R.string.show_labels_summary))
                SwitchRow(stringResource(R.string.left_handed), s.leftHanded, { v -> vm.updateSettings { it.copy(leftHanded = v) } }, summary = stringResource(R.string.left_handed_summary))
                SwitchRow(stringResource(R.string.show_view_cube), s.showViewCube, { v -> vm.updateSettings { it.copy(showViewCube = v) } })
                SwitchRow(stringResource(R.string.show_info_bar), s.showInfoBar, { v -> vm.updateSettings { it.copy(showInfoBar = v) } }, summary = stringResource(R.string.show_info_bar_summary))
                SwitchRow(stringResource(R.string.haptics), s.haptics, { v -> vm.updateSettings { it.copy(haptics = v) } })
            }

            SettingsGroup(stringResource(R.string.view_3d), Icons.Rounded.ViewInAr) {
                Labeled(stringResource(R.string.default_view)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        views.forEach { (v, label) ->
                            PillButton(stringResource(label), selected = s.defaultView == v.id) { vm.updateSettings { it.copy(defaultView = v.id) } }
                        }
                    }
                }
                SliderRow(stringResource(R.string.field_of_view), "${s.fov.toInt()}°", s.fov, 15f..75f) { v -> vm.updateSettings { it.copy(fov = v.toInt().toFloat()) } }
                Divider()
                Labeled(stringResource(R.string.model_color), stringResource(R.string.model_color_summary)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoSwatch(selected = s.modelColor == 0) { vm.updateSettings { it.copy(modelColor = 0) } }
                        MODEL_COLORS.forEach { c ->
                            ColorSwatch(Color(c), selected = s.modelColor == c) { vm.updateSettings { it.copy(modelColor = c) } }
                        }
                    }
                }
                Labeled(stringResource(R.string.model_material)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MaterialPreset.entries.filter { it.color == null }.forEach { m ->
                            PillButton(m.label, selected = s.defaultMaterial == m.ordinal) { vm.updateSettings { it.copy(defaultMaterial = m.ordinal) } }
                        }
                    }
                }
                Labeled(stringResource(R.string.selection_color)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SELECT_COLORS.forEach { c ->
                            ColorSwatch(Color(c), selected = s.selectColor == c) { vm.updateSettings { it.copy(selectColor = c) } }
                        }
                    }
                }
                Divider()
                SwitchRow(stringResource(R.string.smooth_shading), s.smoothShading, { v -> vm.updateSettings { it.copy(smoothShading = v) } })
                SliderRow(stringResource(R.string.crease_angle), "${s.creaseAngle.toInt()}°", s.creaseAngle, 5f..90f) { v -> vm.updateSettings { it.copy(creaseAngle = v) } }
                Text(stringResource(R.string.crease_angle_summary), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Labeled(stringResource(R.string.show_edges)) {
                    Segmented(listOf(0, 1, 2), s.edgesMode, label = { edgeLabels[it] }, onSelect = { m -> vm.updateSettings { it.copy(edgesMode = m) } })
                }
            }

            SettingsGroup(stringResource(R.string.controls), Icons.Rounded.TouchApp) {
                SliderRow(stringResource(R.string.rotation_speed), "${"%.1f".format(s.sensitivity)}×", s.sensitivity, 0.3f..2.5f) { v ->
                    vm.updateSettings { it.copy(sensitivity = v) }
                }
                SwitchRow(stringResource(R.string.invert_x), s.invertX, { v -> vm.updateSettings { it.copy(invertX = v) } })
                SwitchRow(stringResource(R.string.invert_y), s.invertY, { v -> vm.updateSettings { it.copy(invertY = v) } })
                Labeled(stringResource(R.string.double_tap)) {
                    Segmented(listOf(0, 1, 2), s.doubleTap, label = { tapLabels[it] }, onSelect = { v -> vm.updateSettings { it.copy(doubleTap = v) } })
                }
                Divider()
                val speed = abs(s.turntableSpeed).coerceIn(0.2f, 3f)
                val reverse = s.turntableSpeed < 0f
                SliderRow(stringResource(R.string.turntable_speed), "${"%.1f".format(speed)}×", speed, 0.2f..3f) { v ->
                    vm.updateSettings { it.copy(turntableSpeed = if (reverse) -v else v) }
                }
                SwitchRow(stringResource(R.string.turntable_reverse), reverse, { r -> vm.updateSettings { it.copy(turntableSpeed = if (r) -speed else speed) } })
                SwitchRow(stringResource(R.string.keep_screen_on), s.keepScreenOn, { v -> vm.updateSettings { it.copy(keepScreenOn = v) } })
            }

            SettingsGroup(stringResource(R.string.measurements), Icons.Rounded.Straighten) {
                Labeled(stringResource(R.string.units)) {
                    Segmented(Units.entries, s.units, label = { unitLabels[it.ordinal] }, onSelect = { u -> vm.updateSettings { it.copy(units = u) } })
                }
                Labeled(stringResource(R.string.decimals)) {
                    Segmented(listOf(0, 1, 2, 3, 4), s.decimals, label = { it.toString() }, onSelect = { d -> vm.updateSettings { it.copy(decimals = d) } })
                }
            }

            SettingsGroup(stringResource(R.string.startup), Icons.Rounded.FolderOpen) {
                SwitchRow(stringResource(R.string.reopen_last), s.reopenLast, { v -> vm.updateSettings { it.copy(reopenLast = v) } }, summary = stringResource(R.string.reopen_last_summary))
                Labeled(stringResource(R.string.cad_quality)) {
                    Segmented(listOf(0, 1, 2), s.cadQuality, label = { qualityLabels[it] }, onSelect = { q -> vm.updateSettings { it.copy(cadQuality = q) } })
                }
                Divider()
                LinkRow(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_cache)) { vm.clearCache() }
                LinkRow(Icons.Rounded.History, stringResource(R.string.clear_recents)) { vm.clearRecents() }
            }

            SettingsGroup(stringResource(R.string.updates), Icons.Rounded.SystemUpdate) {
                SwitchRow(stringResource(R.string.update_checks), s.updateChecks == UPDATES_ON, { v -> vm.setUpdateChecks(v) }, summary = stringResource(R.string.update_checks_summary))
                LinkRow(Icons.Rounded.Refresh, stringResource(R.string.update_check_now)) { vm.checkForUpdates(manual = true) }
            }

            SupportCard()

            SettingsGroup(stringResource(R.string.about), Icons.Rounded.Info) {
                LinkRow(Icons.Rounded.Info, stringResource(R.string.about) + " · " + stringResource(R.string.version, BuildConfig.VERSION_NAME)) { vm.navigate(Screen.About) }
                LinkRow(Icons.Rounded.RestartAlt, stringResource(R.string.reset_settings)) { confirmReset = true }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_settings)) },
            text = { Text(stringResource(R.string.reset_settings_message)) },
            confirmButton = { TextButton(onClick = { vm.resetSettings(); confirmReset = false }) { Text(stringResource(R.string.reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Model colours offered in Settings (ARGB). */
private val MODEL_COLORS: List<Int> = listOf(
    0xFFF2F2F2, 0xFF9AA0A6, 0xFF3A3A3C, 0xFFE53935, 0xFFFF7043, 0xFFFDD835, 0xFF7CB342, 0xFF26A69A, 0xFF1E88E5, 0xFF7E57C2, 0xFFEC407A,
).map { it.toInt() }

private val SELECT_COLORS: List<Int> = listOf(0xFF7FB3FF, 0xFF3EE6C4, 0xFFFFB37F, 0xFFFF6B9D, 0xFFFFE14D, 0xFFB98CFF).map { it.toInt() }

@Composable
private fun AutoSwatch(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (selected) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.color_auto).take(1), color = if (selected) scheme.onPrimaryContainer else scheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToolbarSummary(dock: List<ImageVector>, side: List<ImageVector>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.onSurface.copy(alpha = 0.05f)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.customize_toolbar), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.customize_toolbar_summary), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dock.take(8).forEach { Icon(it, null, tint = scheme.primary, modifier = Modifier.size(20.dp)) }
            }
            if (side.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                side.take(8).forEach { Icon(it, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
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
    val scheme = MaterialTheme.colorScheme
    PageScaffold(stringResource(R.string.about), onBack = { vm.navigate(Screen.Settings) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "A fast, free and open-source 3D model viewer for 3D printing and CAD files. No ads, no account and no " +
                            "tracking; your files never leave your phone. Built on a C++ core with OpenGL ES 3, and Open CASCADE " +
                            "for STEP and IGES.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinkRow(Icons.Rounded.Code, stringResource(R.string.source_code)) { openUrl(context, REPO_URL) }
                }
            }
            SupportCard()
            Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
            LICENSES.forEach { (name, text) ->
                Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Gavel, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The launcher icon (background + foreground layers), for in-app headers. */
@Composable
fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null,
            modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null,
            modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
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
