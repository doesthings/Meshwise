package io.github.doesthings.meshwise.ui.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.engine.DensityPreset
import io.github.doesthings.meshwise.engine.ExportFormat
import io.github.doesthings.meshwise.engine.Palette
import io.github.doesthings.meshwise.ui.components.ColorSwatch
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.SectionLabel
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.SwitchRow
import io.github.doesthings.meshwise.ui.components.ToolPanel
import io.github.doesthings.meshwise.ui.components.ValueRow
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

@Composable
fun PartsPanel(vm: ViewerViewModel) {
    val model by vm.model.collectAsStateWithLifecycle()
    val parts by vm.parts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val info = model ?: return
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var xray by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val filtered = remember(query, info) {
        info.parts.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(selected) {
        val idx = filtered.indexOfFirst { it.index == selected }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    ToolPanel(stringResource(R.string.parts_title, info.parts.size), Icons.AutoMirrored.Rounded.FormatListBulleted, onClose = { vm.selectTool(Tool.None) }) {
        if (info.parts.size > 8) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_parts), color = scheme.onSurfaceVariant)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface), cursorBrush = SolidColor(scheme.primary), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.show_all), icon = Icons.Rounded.Visibility) { vm.showAllParts(); xray = false }
            PillButton(stringResource(R.string.isolate), icon = Icons.Rounded.Layers, enabled = selected >= 0) { if (selected >= 0) vm.isolatePart(selected) }
            PillButton(stringResource(R.string.ghost_others), selected = xray, enabled = selected >= 0) {
                xray = !xray
                vm.ghostOthers(selected, xray)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 320.dp)) {
            itemsIndexed(filtered, key = { _, p -> p.index }) { _, p ->
                val st = parts.getOrNull(p.index)
                val sel = p.index == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) scheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { vm.selectPart(if (sel) -1 else p.index) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Color(st?.color ?: p.fileColor)).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (st?.visible == false) scheme.onSurface.copy(alpha = 0.45f) else scheme.onSurface)
                        Text(stringResource(R.string.triangles_short, Fmt.count(p.triangles.toLong())), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { vm.focusPart(p.index); vm.selectPart(p.index) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.CenterFocusStrong, stringResource(R.string.focus), modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { vm.setPartVisible(p.index, st?.visible == false) }, contentAlignment = Alignment.Center) {
                        Icon(if (st?.visible == false) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SavePanel(vm: ViewerViewModel) {
    val scheme = MaterialTheme.colorScheme
    // Saveable: the document picker result can arrive after the activity was recreated.
    var visibleOnly by rememberSaveable { mutableStateOf(true) }
    var keepExploded by rememberSaveable { mutableStateOf(false) }
    var pendingFormat by rememberSaveable { mutableStateOf(ExportFormat.STL) }
    var transparentPng by rememberSaveable { mutableStateOf(false) }
    val projectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveProject(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.export(uri, pendingFormat, visibleOnly, keepExploded)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.savePng(uri, transparentPng)
    }
    ToolPanel(stringResource(R.string.tool_save), Icons.Rounded.Save, onClose = { vm.selectTool(Tool.None) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.project))
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { projectLauncher.launch(vm.suggestedName("meshwise")) },
                shape = RoundedCornerShape(16.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Archive, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.save_project), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(vm.suggestedName("meshwise"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ExportRow("LIB", stringResource(R.string.save_to_library), Icons.Rounded.Bookmarks) { vm.saveToLibrary() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionLabel(stringResource(R.string.export))
            ExportRow("STL", stringResource(R.string.export_stl)) { pendingFormat = ExportFormat.STL; exportLauncher.launch(vm.suggestedName("stl")) }
            ExportRow("OBJ", stringResource(R.string.export_obj)) { pendingFormat = ExportFormat.OBJ; exportLauncher.launch(vm.suggestedName("obj")) }
            ExportRow("3MF", stringResource(R.string.export_3mf)) { pendingFormat = ExportFormat.ThreeMF; exportLauncher.launch(vm.suggestedName("3mf")) }
            ExportRow("PNG", stringResource(R.string.export_png)) { pngLauncher.launch(vm.suggestedName("png")) }
            SwitchRow(stringResource(R.string.visible_parts_only), visibleOnly, { visibleOnly = it })
            SwitchRow(stringResource(R.string.apply_explode), keepExploded, { keepExploded = it })
            SwitchRow(stringResource(R.string.transparent_png), transparentPng, { transparentPng = it })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("${stringResource(R.string.share)} STL", icon = Icons.Rounded.Share) { vm.shareModel(ExportFormat.STL) }
                PillButton("${stringResource(R.string.share)} PNG", icon = Icons.Rounded.Share) { vm.shareSnapshot() }
            }
        }
    }
}

@Composable
private fun ExportRow(tag: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, modifier = Modifier.width(44.dp).size(22.dp), tint = scheme.primary)
        else Text(tag, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(56.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPanel(vm: ViewerViewModel) {
    val paint by vm.paintColor.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val single = (model?.parts?.size ?: 0) <= 1
    var hue by remember { mutableStateOf(210f) }
    var light by remember { mutableStateOf(0.55f) }
    ToolPanel(stringResource(R.string.color_title), Icons.Rounded.Palette, onClose = { vm.selectTool(Tool.None) }) {
        Text(
            stringResource(if (single) R.string.paint_hint_single else R.string.paint_hint_multi),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.swatches.forEach { c ->
                    ColorSwatch(Color(c), selected = c == paint) {
                        vm.setPaintColor(c)
                        if (single) vm.applyColorAll(c)
                    }
                }
            }
            SectionLabel(stringResource(R.string.custom_color))
            val custom = hslColor(hue, 0.65f, light)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(custom, selected = custom.toArgb() == paint, size = 40.dp) {
                    vm.setPaintColor(custom.toArgb())
                    if (single) vm.applyColorAll(custom.toArgb())
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient((0..6).map { hslColor(it * 60f, 0.65f, 0.55f) })))
                    Slider(hue, { hue = it; vm.setPaintColor(hslColor(it, 0.65f, light).toArgb()) }, valueRange = 0f..360f)
                    Slider(light, { light = it; vm.setPaintColor(hslColor(hue, 0.65f, it).toArgb()) }, valueRange = 0.08f..0.95f)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!single) PillButton(stringResource(R.string.apply_to_all)) { vm.applyColorAll() }
                if (!single) PillButton(stringResource(R.string.random_colors)) { vm.colorByPart() }
                PillButton(stringResource(R.string.original_colors)) { vm.restoreOriginalColors() }
            }
        }
    }
}

private fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when ((h / 60f).toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

