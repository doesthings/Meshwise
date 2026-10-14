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
import androidx.compose.ui.unit.Dp
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
import io.github.doesthings.meshwise.ui.components.ToggleChip
import io.github.doesthings.meshwise.data.BACKGROUND_CUSTOM
import io.github.doesthings.meshwise.engine.StandardView
import androidx.compose.material.icons.rounded.Tune
import io.github.doesthings.meshwise.ui.components.ToolPanel
import io.github.doesthings.meshwise.ui.theme.ValueStyle
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightingPanel(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val o by vm.options.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var editCustom by remember { mutableStateOf(false) }
    ToolPanel(stringResource(R.string.lighting_title), Icons.Rounded.WbSunny, onClose = { vm.selectTool(Tool.None) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.standard_views))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(STANDARD_VIEWS) { (view, label) ->
                    PillButton(stringResource(label)) { vm.setStandardView(view) }
                }
            }
            SectionLabel(stringResource(R.string.lighting))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Lighting.names.size) { i ->
                    PillButton(Lighting.names[i], selected = s.lighting == i) { vm.updateSettings { it.copy(lighting = i) } }
                }
            }
            SliderRow(stringResource(R.string.lighting_exposure), "${(s.exposure * 100).toInt()}%", s.exposure, 0.4f..2f) { v -> vm.updateSettings { it.copy(exposure = v) } }
            SectionLabel(stringResource(R.string.background))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Backgrounds.previews.size) { i ->
                    val (top, bottom) = Backgrounds.previews[i]
                    GradientDot(Color(top), Color(bottom), s.background == i, Backgrounds.names[i]) {
                        editCustom = false
                        vm.updateSettings { it.copy(background = i) }
                    }
                }
                item {
                    GradientDot(Color(s.bgTop), Color(s.bgBottom), s.background == BACKGROUND_CUSTOM, stringResource(R.string.custom_background), icon = Icons.Rounded.Tune) {
                        editCustom = s.background == BACKGROUND_CUSTOM && !editCustom || s.background != BACKGROUND_CUSTOM
                        vm.updateSettings { it.copy(background = BACKGROUND_CUSTOM) }
                    }
                }
            }
            if (editCustom && s.background == BACKGROUND_CUSTOM) {
                Text(stringResource(R.string.gradient_top), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                ColorPickerRow(s.bgTop) { c -> vm.updateSettings { it.copy(bgTop = c) } }
                Text(stringResource(R.string.gradient_bottom), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                ColorPickerRow(s.bgBottom) { c -> vm.updateSettings { it.copy(bgBottom = c) } }
            }
            if (!s.orthographic) {
                SliderRow(stringResource(R.string.field_of_view), "${s.fov.toInt()}°", s.fov, 15f..75f) { v -> vm.updateSettings { it.copy(fov = v.toInt().toFloat()) } }
            }
            SectionLabel(stringResource(R.string.display))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip(stringResource(R.string.shadows), s.shadows) { v -> vm.updateSettings { it.copy(shadows = v) } }
                ToggleChip(stringResource(R.string.grid), s.grid) { v -> vm.updateSettings { it.copy(grid = v) } }
                ToggleChip(stringResource(R.string.axes), s.axes) { v -> vm.updateSettings { it.copy(axes = v) } }
                ToggleChip(stringResource(R.string.edges), o.edges) { v -> vm.updateOptions { it.copy(edges = v) } }
                ToggleChip(stringResource(R.string.wireframe), o.wireframe) { v -> vm.updateOptions { it.copy(wireframe = v) } }
                ToggleChip(stringResource(R.string.orthographic), s.orthographic) { v -> vm.updateSettings { it.copy(orthographic = v) } }
            }
        }
    }
}

private val STANDARD_VIEWS = listOf(
    StandardView.Iso to R.string.view_iso,
    StandardView.Front to R.string.view_front,
    StandardView.Top to R.string.view_top,
    StandardView.Right to R.string.view_right,
    StandardView.Back to R.string.view_back,
    StandardView.Left to R.string.view_left,
    StandardView.Bottom to R.string.view_bottom,
)

/** Label, value and a slider on one row. */
@Composable
fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = ValueStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = MaterialTheme.colorScheme.primary)
        }
        Slider(current, onChange, valueRange = range, steps = steps, modifier = Modifier.height(36.dp))
    }
}

@Composable
private fun GradientDot(top: Color, bottom: Color, selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Hue and lightness sliders with a preview swatch; reports ARGB colours. */
@Composable
fun ColorPickerRow(color: Int, onChange: (Int) -> Unit) {
    val hsl = remember(color) { FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(color, it) } }
    var hue by remember { mutableStateOf(hsl[0]) }
    var light by remember { mutableStateOf(hsl[2]) }
    var sat by remember { mutableStateOf(hsl[1].coerceAtLeast(0f)) }
    fun emit() = onChange(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsl(it * 60f % 360f, 0.7f, 0.55f) })))
            Slider(hue, { hue = it; if (sat < 0.05f) sat = 0.45f; emit() }, valueRange = 0f..359f, modifier = Modifier.height(30.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Color.Black, Color.hsl(hue, sat, 0.5f), Color.White))))
            Slider(light, { light = it; emit() }, valueRange = 0f..1f, modifier = Modifier.height(30.dp))
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
fun NumberField(value: Float, onCommit: (Float) -> Unit, digits: Int = 1, modifier: Modifier = Modifier, width: Dp = 96.dp) {
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
            .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.onSurface.copy(alpha = 0.07f))
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
