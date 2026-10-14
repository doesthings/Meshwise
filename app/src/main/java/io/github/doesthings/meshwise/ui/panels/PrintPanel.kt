package io.github.doesthings.meshwise.ui.panels

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.Printers
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.engine.OrientationStats
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.RoundIconButton
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.ToggleChip
import io.github.doesthings.meshwise.ui.components.ToolPanel
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.PrintTab
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale

/** Print prep: orientation, size, printer fit and weight in one tool. */
@Composable
fun PrintPanel(vm: ViewerViewModel) {
    val st by vm.print.collectAsStateWithLifecycle()
    val tabLabels = mapOf(
        PrintTab.Orient to stringResource(R.string.print_orient),
        PrintTab.Size to stringResource(R.string.print_size),
        PrintTab.Printer to stringResource(R.string.print_printer),
        PrintTab.Weight to stringResource(R.string.weight),
    )
    ToolPanel(
        stringResource(R.string.tool_print), Icons.Rounded.Print, onClose = { vm.selectTool(Tool.None) },
        actions = {
            if (st.undo.isNotEmpty()) {
                RoundIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), enabled = !st.busy) { vm.undoTransform() }
                Spacer(Modifier.width(6.dp))
            }
            if (!st.transform.isIdentity) {
                RoundIconButton(Icons.Rounded.RestartAlt, stringResource(R.string.reset_model), enabled = !st.busy) { vm.resetTransform() }
                Spacer(Modifier.width(6.dp))
            }
        },
    ) {
        Segmented(PrintTab.entries, st.tab, label = { tabLabels.getValue(it) }, onSelect = vm::setPrintTab)
        if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (st.tab) {
                PrintTab.Orient -> OrientContent(vm)
                PrintTab.Size -> SizeContent(vm)
                PrintTab.Printer -> PrinterContent(vm)
                PrintTab.Weight -> WeightContent(vm)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, warn: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(scheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text("$label ", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            color = if (warn) scheme.tertiary else scheme.onSurface,
        )
    }
}

private fun percent(f: Double) = "%.1f%%".format(Locale.US, f * 100)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrientationChips(s: OrientationStats, u: Units) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatChip(stringResource(R.string.overhangs_short), percent(s.overhangFraction), warn = s.overhangFraction > 0.05)
        StatChip(stringResource(R.string.build_height), Fmt.length(s.height.toDouble(), u, 1))
        StatChip(stringResource(R.string.bed_contact), Fmt.area(s.contactArea, u), warn = s.contactArea <= 0.0)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrientContent(vm: ViewerViewModel) {
    val st by vm.print.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    Text(stringResource(R.string.orient_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    st.stats?.let { OrientationChips(it, s.units) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("X", "Y", "Z").forEachIndexed { axis, name ->
            PillButton("↻ $name", enabled = !st.busy, modifier = Modifier.weight(1f)) { vm.rotateModel(axis, 90f) }
        }
        PillButton(stringResource(R.string.mirror), enabled = !st.busy, modifier = Modifier.weight(1.3f)) { vm.mirrorModel(0) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ToggleChip(stringResource(R.string.show_overhangs), st.showOverhangs) { vm.setShowOverhangs(it) }
        PillButton(stringResource(R.string.suggest_orientations), icon = Icons.Rounded.AutoAwesome, selected = true, enabled = !st.busy && !st.suggesting) {
            vm.suggestOrientations()
        }
    }
    if (st.suggesting) {
        Text(stringResource(R.string.suggesting), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (st.suggestions.isNotEmpty()) {
        st.suggestions.forEachIndexed { i, sug ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.primaryContainer.copy(alpha = 0.35f))
                    .clickable(enabled = !st.busy) { vm.applySuggestion(sug) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = sug.down.z < -0.999f
                Text(
                    stringResource(if (current) R.string.suggestion_current else R.string.suggestion_title, i + 1),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                )
                OrientationChips(sug, s.units)
            }
        }
        Text(stringResource(R.string.suggestion_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeContent(vm: ViewerViewModel) {
    val st by vm.print.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val info = model ?: return
    val u = s.units
    val size = info.bounds.size
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.model_size), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            "${Fmt.number(size.x * u.factor, 1)} × ${Fmt.number(size.y * u.factor, 1)} × ${Fmt.number(size.z * u.factor, 1)} ${u.suffix}",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = LocalExtraColors.current.dimension,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.scale_percent), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val current = st.transform.scale
        NumberField(current * 100f, { v -> if (v > 0f) vm.scaleModel(v / 100f / current) })
    }
    Text(stringResource(R.string.set_axis_size), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("X", "Y", "Z").forEachIndexed { axis, name ->
            Text(name, style = MaterialTheme.typography.labelLarge)
            NumberField(
                size[axis] * u.factor.toFloat(),
                { v -> vm.scaleModelTo(axis, v / u.factor.toFloat()) },
                modifier = Modifier.weight(1f), width = Dp.Unspecified,
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(stringResource(R.string.file_in_inches), enabled = !st.busy) { vm.scaleModel(25.4f) }
        PillButton(stringResource(R.string.file_in_cm), enabled = !st.busy) { vm.scaleModel(10f) }
        PillButton(stringResource(R.string.file_in_m), enabled = !st.busy) { vm.scaleModel(1000f) }
        PillButton(stringResource(R.string.half_size), enabled = !st.busy) { vm.scaleModel(0.5f) }
        PillButton(stringResource(R.string.double_size), enabled = !st.busy) { vm.scaleModel(2f) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrinterContent(vm: ViewerViewModel) {
    val st by vm.print.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val info = model ?: return
    val printer = Printers.of(s)
    var menu by remember { mutableStateOf(false) }
    val currentName = when (s.printer) {
        "" -> stringResource(R.string.printer_none)
        Printers.CUSTOM -> stringResource(R.string.printer_custom)
        else -> s.printer
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.choose_printer), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            PillButton(currentName, icon = Icons.Rounded.ArrowDropDown, selected = printer != null) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.printer_none)) }, onClick = { vm.updateSettings { it.copy(printer = "") }; menu = false })
                Printers.presets.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.name}  ·  ${p.x.toInt()}×${p.y.toInt()}×${p.z.toInt()}") },
                        onClick = { vm.updateSettings { it.copy(printer = p.name) }; menu = false },
                    )
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.printer_custom)) }, onClick = { vm.updateSettings { it.copy(printer = Printers.CUSTOM) }; menu = false })
            }
        }
    }
    if (s.printer == Printers.CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(s.bedX, { v -> vm.updateSettings { it.copy(bedX = v.coerceIn(10f, 2000f)) } }, digits = 0, modifier = Modifier.weight(1f), width = Dp.Unspecified)
            Text("×")
            NumberField(s.bedY, { v -> vm.updateSettings { it.copy(bedY = v.coerceIn(10f, 2000f)) } }, digits = 0, modifier = Modifier.weight(1f), width = Dp.Unspecified)
            Text("×")
            NumberField(s.bedZ, { v -> vm.updateSettings { it.copy(bedZ = v.coerceIn(10f, 2000f)) } }, digits = 0, modifier = Modifier.weight(1f), width = Dp.Unspecified)
            Text("mm", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (printer != null) {
        val sz = info.bounds.size
        val straight = sz.z <= printer.z && sz.x <= printer.x && sz.y <= printer.y
        val fits = printer.fits(sz.x, sz.y, sz.z)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (fits) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null,
                tint = if (fits) LocalExtraColors.current.dimension else scheme.error, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (straight) R.string.fits else if (fits) R.string.fits_turned else R.string.too_big),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (fits && !straight) PillButton(stringResource(R.string.turn_90), icon = Icons.Rounded.Rotate90DegreesCw, enabled = !st.busy) { vm.rotateModel(2, 90f) }
            if (!fits) PillButton(stringResource(R.string.scale_to_fit), selected = true, enabled = !st.busy) { vm.scaleModel(printer.scaleToFit(sz.x, sz.y, sz.z)) }
        }
        Text(stringResource(R.string.printer_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}
