package io.github.doesthings.meshwise.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.Printers
import io.github.doesthings.meshwise.engine.DensityPreset
import io.github.doesthings.meshwise.engine.Filament
import io.github.doesthings.meshwise.engine.InfillPattern
import io.github.doesthings.meshwise.engine.PrintEstimate
import io.github.doesthings.meshwise.engine.Supports
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.ValueRow
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.PrintTab
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale
import kotlin.math.abs

private val LayerHeights = listOf(0.12f, 0.16f, 0.2f, 0.24f, 0.28f)

/** The estimate's result, kept in view above the settings while they scroll. */
@Composable
fun EstimateSummary(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val est by vm.estimate.collectAsStateWithLifecycle()
    if (Filament.of(DensityPreset.entries.getOrElse(s.density) { DensityPreset.PLA }) == null) return
    ResultCard(est.result, est.running, est.progress, est.failed, est.density, s.pricePerKg, s.supports != Supports.Off.ordinal)
}

/** Print time, filament, weight and cost, worked out by slicing the model like a slicer would. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EstimateContent(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val parts by vm.parts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val check by vm.meshCheck.collectAsStateWithLifecycle()
    val est by vm.estimate.collectAsStateWithLifecycle()
    val info = model ?: return
    val scheme = MaterialTheme.colorScheme
    var scope by remember { mutableStateOf(if (selected >= 0) 2 else 0) }
    if (scope == 2 && selected < 0) scope = 0
    val preset = DensityPreset.entries.getOrElse(s.density) { DensityPreset.PLA }
    val filament = Filament.of(preset)
    val printer = Printers.of(s)
    val visibleKey = if (scope == 1) parts.map { it.visible } else null

    LaunchedEffect(
        info, scope, visibleKey, if (scope == 2) selected else -1, s.density, s.infill, s.walls, s.layerHeight,
        s.infillPattern, s.supports, s.filamentDiameter, s.printer,
    ) { vm.requestEstimate(scope) }

    if (info.parts.size > 1) {
        val scopeLabels = listOf(stringResource(R.string.scope_all), stringResource(R.string.scope_visible), stringResource(R.string.scope_selected))
        Segmented(if (selected >= 0) listOf(0, 1, 2) else listOf(0, 1), scope, label = { scopeLabels[it] }, onSelect = { scope = it })
    }
    MaterialRow(preset) { vm.updateSettings { s -> s.copy(density = it.ordinal) } }

    if (filament == null) {
        // Not printed from filament: what the solid part weighs.
        val volume = info.parts.filter { scope == 0 || (scope == 1 && parts.getOrNull(it.index)?.visible != false) || it.index == selected }
            .sumOf { abs(it.volume) }
        ValueRow(stringResource(R.string.volume), Fmt.volume(volume, s.units))
        ValueRow(stringResource(R.string.weight), Fmt.mass(volume * preset.density / 1000.0), highlight = true)
        Text(stringResource(R.string.estimate_solid_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        WatertightWarning(check?.watertight == false)
        return
    }

    est.result?.let { r ->
        if (s.supports == Supports.Off.ordinal && r.supportAreaMm2 > 25.0) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.tertiaryContainer.copy(alpha = 0.5f))
                    .clickable { vm.updateSettings { it.copy(supports = Supports.Normal.ordinal) } }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = scheme.tertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.estimate_needs_support), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.estimate_add_supports), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (printer != null && printer.name != Printers.CUSTOM) stringResource(R.string.estimate_using, printer.name)
            else stringResource(R.string.estimate_no_printer),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        PillButton(stringResource(R.string.print_printer), icon = Icons.Rounded.Print) { vm.setPrintTab(PrintTab.Printer) }
    }

    Text(stringResource(R.string.estimate_layer_height), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    Segmented(
        LayerHeights, LayerHeights.minByOrNull { abs(it - s.layerHeight) } ?: 0.2f,
        label = { "%.2f".format(Locale.US, it) },
        onSelect = { h -> vm.updateSettings { it.copy(layerHeight = h) } },
    )
    InfillRow(s.infill, InfillPattern.entries.getOrElse(s.infillPattern) { InfillPattern.Grid }, onInfill = { v ->
        vm.updateSettings { it.copy(infill = v) }
    }, onPattern = { p -> vm.updateSettings { it.copy(infillPattern = p.ordinal) } })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.weight_walls), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        (1..5).forEach { w -> PillButton("$w", selected = s.walls == w) { vm.updateSettings { it.copy(walls = w) } } }
    }
    Text(stringResource(R.string.estimate_supports), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    val supportLabels = mapOf(
        Supports.Off to stringResource(R.string.supports_off),
        Supports.Normal to stringResource(R.string.supports_normal),
        Supports.Tree to stringResource(R.string.supports_tree),
    )
    Segmented(
        Supports.entries, Supports.entries.getOrElse(s.supports) { Supports.Off }, label = { supportLabels.getValue(it) },
        onSelect = { v -> vm.updateSettings { it.copy(supports = v.ordinal) } },
    )

    Details(est.result, s.pricePerKg, s.filamentDiameter, onPrice = { v -> vm.updateSettings { it.copy(pricePerKg = v.coerceIn(0f, 100000f)) } },
        onDiameter = { d -> vm.updateSettings { it.copy(filamentDiameter = d) } })
    Text(stringResource(R.string.estimate_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    WatertightWarning(check?.watertight == false)
}

@Composable
private fun MaterialRow(preset: DensityPreset, onPick: (DensityPreset) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.weight_material), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            PillButton("${preset.label} · ${preset.density} g/cm³", icon = Icons.Rounded.ArrowDropDown, selected = true) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DensityPreset.entries.forEach { d ->
                    DropdownMenuItem(text = { Text("${d.label}  (${d.density})") }, onClick = { onPick(d); menu = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(r: PrintEstimate?, running: Boolean, progress: Float, failed: Boolean, density: Double, pricePerKg: Float, supports: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.primaryContainer.copy(alpha = 0.35f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    r != null -> Fmt.duration(r.seconds)
                    failed -> stringResource(R.string.estimate_failed)
                    else -> stringResource(R.string.estimate_working)
                },
                style = if (r != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).alpha(if (running && r != null) 0.5f else 1f),
            )
            if (r != null) Text(stringResource(R.string.estimate_layers, r.layers), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        if (running) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().height(3.dp))
        if (r != null) {
            val grams = r.grams(density)
            FlowRow(
                Modifier.alpha(if (running) 0.5f else 1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip(stringResource(R.string.estimate_filament), "%.2f m".format(Locale.US, r.filamentMm / 1000.0))
                Chip(stringResource(R.string.weight), Fmt.mass(grams))
                Chip(stringResource(R.string.cost), "%.2f".format(Locale.US, grams / 1000.0 * pricePerKg))
                if (supports && r.supportMm3 > 0) Chip(stringResource(R.string.estimate_supports), Fmt.mass(r.supportGrams(density)), extra.dimension)
            }
        }
    }
}

@Composable
private fun Chip(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(scheme.surface.copy(alpha = 0.6f)).padding(horizontal = 9.dp, vertical = 6.dp)) {
        Text("$label ", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = color ?: scheme.onSurface)
    }
}

@Composable
private fun InfillRow(infill: Float, pattern: InfillPattern, onInfill: (Float) -> Unit, onPattern: (InfillPattern) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val names = mapOf(
        InfillPattern.Lines to stringResource(R.string.pattern_lines),
        InfillPattern.Grid to stringResource(R.string.pattern_grid),
        InfillPattern.Cubic to stringResource(R.string.pattern_cubic),
        InfillPattern.Gyroid to stringResource(R.string.pattern_gyroid),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${stringResource(R.string.weight_infill)} ${(infill * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            PillButton(names.getValue(pattern), icon = Icons.Rounded.ArrowDropDown) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                InfillPattern.entries.forEach { p -> DropdownMenuItem(text = { Text(names.getValue(p)) }, onClick = { onPattern(p); menu = false }) }
            }
        }
    }
    Slider(infill, { v -> onInfill((v * 20).toInt() / 20f) }, valueRange = 0f..1f, modifier = Modifier.height(36.dp))
}

@Composable
private fun Details(r: PrintEstimate?, pricePerKg: Float, diameter: Float, onPrice: (Float) -> Unit, onDiameter: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.estimate_details), style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = scheme.primary)
    }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r != null) {
                val rows = listOf(
                    R.string.breakdown_walls to r.outerWall + r.innerWall + r.skirt,
                    R.string.breakdown_infill to r.sparseInfill,
                    R.string.breakdown_solid to r.solidInfill + r.topSurface + r.bottomSurface + r.bridge,
                    R.string.estimate_supports to r.support,
                    R.string.breakdown_travel to r.travel,
                )
                rows.filter { it.second > 0.5 }.forEach { (label, t) ->
                    ValueRow(stringResource(label), "${Fmt.duration(t)}  ·  ${(100 * t / r.seconds.coerceAtLeast(1.0)).toInt()}%")
                }
                HorizontalDivider()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.weight_price), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                NumberField(pricePerKg, onPrice, digits = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.filament_diameter), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                listOf(1.75f, 2.85f).forEach { d -> PillButton("%.2f".format(Locale.US, d), selected = abs(diameter - d) < 0.01f) { onDiameter(d) } }
            }
        }
    }
}

@Composable
private fun WatertightWarning(show: Boolean) {
    if (!show) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.not_watertight_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}
