package io.github.doesthings.meshwise.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.engine.Backgrounds
import io.github.doesthings.meshwise.engine.Lighting
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.RoundIconButton
import io.github.doesthings.meshwise.ui.components.SectionLabel
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.SwitchRow
import io.github.doesthings.meshwise.ui.components.ToolPanel
import io.github.doesthings.meshwise.ui.theme.ValueStyle
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale

@Composable
fun LightingPanel(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val o by vm.options.collectAsStateWithLifecycle()
    ToolPanel(stringResource(R.string.lighting_title), Icons.Rounded.WbSunny, onClose = { vm.selectTool(Tool.None) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.lighting))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Lighting.names.size) { i ->
                    PillButton(Lighting.names[i], selected = s.lighting == i) { vm.updateSettings { it.copy(lighting = i) } }
                }
            }
            SectionLabel(stringResource(R.string.background))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Backgrounds.previews.forEachIndexed { i, (top, bottom) ->
                    val sel = s.background == i
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color(top), Color(bottom))))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable { vm.updateSettings { it.copy(background = i) } },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.exposure), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
                Slider(value = s.exposure, onValueChange = { v -> vm.updateSettings { it.copy(exposure = v) } }, valueRange = 0.4f..2f, modifier = Modifier.weight(1f))
            }
            SwitchRow(stringResource(R.string.shadows), s.shadows, { v -> vm.updateSettings { it.copy(shadows = v) } })
            SwitchRow(stringResource(R.string.grid), s.grid, { v -> vm.updateSettings { it.copy(grid = v) } })
            SwitchRow(stringResource(R.string.axes), s.axes, { v -> vm.updateSettings { it.copy(axes = v) } })
            SwitchRow(stringResource(R.string.edges), o.edges, { v -> vm.updateOptions { it.copy(edges = v) } })
            SwitchRow(stringResource(R.string.wireframe), o.wireframe, { v -> vm.updateOptions { it.copy(wireframe = v) } })
            SwitchRow(stringResource(R.string.orthographic), s.orthographic, { v -> vm.updateSettings { it.copy(orthographic = v) } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaterialPanel(vm: ViewerViewModel) {
    val parts by vm.parts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val current = parts.getOrNull(if (selected >= 0) selected else 0)?.material
    val title = if (selected >= 0 && model != null) "${stringResource(R.string.material_title)} · ${model!!.parts[selected].name}" else stringResource(R.string.material_title)
    ToolPanel(title, Icons.Rounded.Diamond, onClose = { vm.selectTool(Tool.None) }) {
        Text(stringResource(R.string.material_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 5) {
                MaterialPreset.entries.forEach { m ->
                    MaterialTile(m, selected = m == current) { vm.applyMaterial(m) }
                }
            }
        }
    }
}

@Composable
private fun MaterialTile(m: MaterialPreset, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val base = Color(m.color ?: 0xFFB9BCC2.toInt())
    val highlight = if (m.isMetal) Color.White else Color.White.copy(alpha = 0.25f + (1f - m.roughness) * 0.6f)
    val dark = base.copy(red = base.red * 0.35f, green = base.green * 0.35f, blue = base.blue * 0.35f)
    Column(Modifier.width(58.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        0f to highlight, (0.18f + m.roughness * 0.3f) to base, 1f to dark,
                        center = androidx.compose.ui.geometry.Offset(36f, 30f), radius = 90f,
                    ),
                )
                .border(if (selected) 3.dp else 0.dp, if (selected) scheme.primary else Color.Transparent, CircleShape),
        )
        Text(m.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SectionPanel(vm: ViewerViewModel) {
    val sec by vm.section.collectAsStateWithLifecycle()
    val bounds by vm.sceneBounds.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val lo = bounds.min[sec.axis]
    val hi = bounds.max[sec.axis]
    val span = (hi - lo).coerceAtLeast(1e-6f)
    ToolPanel(stringResource(R.string.section_analysis), Icons.Rounded.ContentCut, onClose = { vm.selectTool(Tool.None) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf(0, 1, 2), sec.axis, label = { listOf("X", "Y", "Z")[it] }, onSelect = vm::setSectionAxis, modifier = Modifier.weight(3f))
            PillButton(stringResource(R.string.section_view), icon = Icons.Rounded.Visibility, modifier = Modifier.weight(1.4f)) { vm.viewSection() }
        }
        Slider(
            value = ((sec.value - lo) / span).coerceIn(0f, 1f),
            onValueChange = { f -> vm.setSectionValue(lo + f * span) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(R.string.section_side), icon = Icons.Rounded.SwapHoriz) { vm.flipSection() }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.section_cut), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            NumberField(
                value = (sec.value - lo) * s.units.factor.toFloat(),
                onCommit = { v -> vm.setSectionValue((lo + v / s.units.factor.toFloat()).coerceIn(lo, hi)) },
            )
            Spacer(Modifier.width(6.dp))
            Text("/ ${Fmt.number(span * s.units.factor, 1)} ${s.units.suffix}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.section_hatch), selected = sec.hatch) { vm.setSectionHatch(!sec.hatch) }
            PillButton(stringResource(R.string.section_plane), selected = sec.showPlane) { vm.setSectionPlane(!sec.showPlane) }
        }
    }
}

@Composable
fun NumberField(value: Float, onCommit: (Float) -> Unit, digits: Int = 1, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, focused) {
        if (!focused) text = "%.${digits}f".format(Locale.US, value)
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = ValueStyle.copy(color = scheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize, textAlign = TextAlign.End),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            text.replace(',', '.').toFloatOrNull()?.let(onCommit)
            focus.clearFocus()
        }),
        modifier = modifier
            .width(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, scheme.primary.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onFocusChanged {
                if (focused && !it.isFocused) text.replace(',', '.').toFloatOrNull()?.let(onCommit)
                focused = it.isFocused
            },
    )
}

@Composable
fun ExplodePanel(vm: ViewerViewModel) {
    val explode by vm.explode.collectAsStateWithLifecycle()
    val manual by vm.manualExplode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val single = (model?.parts?.size ?: 0) <= 1
    ToolPanel(
        if (manual) stringResource(R.string.manual_mode) else stringResource(R.string.explode_title),
        if (manual) Icons.Rounded.PanTool else Icons.Rounded.Widgets,
        onClose = { vm.selectTool(Tool.None) },
        actions = {
            if (manual) {
                RoundIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo)) { vm.undoMove() }
                Spacer(Modifier.width(6.dp))
                RoundIconButton(Icons.Rounded.VisibilityOff, stringResource(R.string.hide_part), enabled = selected >= 0) {
                    if (selected >= 0) { vm.setPartVisible(selected, false); vm.selectPart(-1) }
                }
                Spacer(Modifier.width(6.dp))
            }
        },
    ) {
        if (single) {
            Text(stringResource(R.string.explode_single_part), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(explode * 100).toInt()}%", style = ValueStyle, modifier = Modifier.width(72.dp))
                Slider(value = explode, onValueChange = vm::setExplode, valueRange = 0f..1.5f, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(stringResource(R.string.manual_mode), icon = Icons.Rounded.PanTool, selected = manual) { vm.setManualExplode(!manual) }
                PillButton(stringResource(R.string.reset), icon = Icons.Rounded.RestartAlt) { vm.resetExplode() }
            }
            if (manual) Text(stringResource(R.string.manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
