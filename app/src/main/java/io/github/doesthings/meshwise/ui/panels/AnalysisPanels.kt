package io.github.doesthings.meshwise.ui.panels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.engine.MeasureFeature
import io.github.doesthings.meshwise.engine.PickHit
import io.github.doesthings.meshwise.ui.viewer.FeatureColors
import io.github.doesthings.meshwise.ui.viewer.formatMeasure
import io.github.doesthings.meshwise.ui.viewer.measureSummary
import io.github.doesthings.meshwise.viewer.MeasureResult
import io.github.doesthings.meshwise.viewer.SmartMeasure
import io.github.doesthings.meshwise.viewer.ValueKind
import io.github.doesthings.meshwise.engine.Engine
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.components.RoundIconButton
import io.github.doesthings.meshwise.ui.components.Segmented
import io.github.doesthings.meshwise.ui.components.SwitchRow
import io.github.doesthings.meshwise.ui.components.ToolPanel
import io.github.doesthings.meshwise.ui.components.ValueRow
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.ui.theme.ValueStyle
import io.github.doesthings.meshwise.ui.viewer.MeasureColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.AnalyzeTab
import io.github.doesthings.meshwise.viewer.MeasureMode
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import java.util.Locale
import kotlin.math.acos

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("measurement", text))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

@Composable
fun MeasurePanel(vm: ViewerViewModel) {
    val st by vm.measure.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val ready by vm.analysisReady.collectAsStateWithLifecycle()
    val hover by vm.measureHover.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = context.resources
    val u = s.units
    var showDiameter by remember { mutableStateOf(false) }
    val modes = MeasureMode.entries
    val modeLabels = mapOf(
        MeasureMode.Smart to stringResource(R.string.measure_smart),
        MeasureMode.Distance to stringResource(R.string.measure_distance),
        MeasureMode.Angle to stringResource(R.string.measure_angle),
        MeasureMode.Circle to stringResource(R.string.measure_radius),
    )
    val smart = st.smartResult
    val header = when {
        hover != null -> stringResource(R.string.measure_hint_hold)
        st.mode == MeasureMode.Smart -> when (st.features.size) {
            0 -> stringResource(R.string.measure_smart)
            1 -> stringResource(SmartMeasure.title(st.features[0]))
            else -> smart?.let { stringResource(it.title) } ?: ""
        }
        st.complete -> stringResource(R.string.measure_done)
        st.points.isEmpty() -> when (st.mode) {
            MeasureMode.Distance -> stringResource(R.string.measure_hint_distance)
            MeasureMode.Circle -> stringResource(R.string.measure_hint_radius)
            MeasureMode.Angle -> stringResource(R.string.measure_hint_angle)
            MeasureMode.Smart -> ""
        }
        else -> stringResource(R.string.measure_points_left, st.mode.points - st.points.size)
    }
    ToolPanel(
        header, if (st.complete) Icons.Rounded.Check else Icons.Rounded.Straighten, onClose = { vm.selectTool(Tool.None) },
        actions = {
            if (smart != null && st.features.size == 2) {
                RoundIconButton(Icons.Rounded.PushPin, stringResource(R.string.keep_measurement)) { vm.keepMeasurement() }
                Spacer(Modifier.width(6.dp))
            }
            RoundIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.undo), enabled = !st.empty) { vm.undoMeasurePoint() }
            Spacer(Modifier.width(6.dp))
        },
    ) {
        Segmented(modes, st.mode, label = { modeLabels.getValue(it) }, onSelect = vm::setMeasureMode)
        if (!ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.measure_waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.mode == MeasureMode.Smart) {
                SmartContent(vm, st.features, smart, u)
            } else {
                PointContent(st.mode, st.points, u, showDiameter, { showDiameter = it }) { copy(context, it) }
            }
            if (st.kept.isNotEmpty()) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.kept_measurements), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                    PillButton(stringResource(R.string.copy_all), icon = Icons.Rounded.ContentCopy) {
                        copy(context, st.kept.joinToString("\n") { measureSummary(it.result, u, res) })
                    }
                }
                st.kept.forEachIndexed { i, k ->
                    val primary = k.result.primary?.let { formatMeasure(it, u, res) } ?: ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(k.result.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(primary, style = ValueStyle.copy(fontSize = 16.sp), color = LocalExtraColors.current.dimension)
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable { vm.removeKept(i) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.delete), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartContent(vm: ViewerViewModel, features: List<MeasureFeature>, result: MeasureResult?, u: Units) {
    val context = LocalContext.current
    val res = context.resources
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    if (features.isEmpty()) {
        Text(stringResource(R.string.measure_hint_smart), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        return
    }
    // What is picked: A and B in their own colours.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        features.forEachIndexed { i, f ->
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(scheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(FeatureColors[i % FeatureColors.size]))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(SmartMeasure.title(f)), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        if (features.size == 1) {
            Text(
                stringResource(R.string.measure_hint_smart_second), style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
            )
        }
    }
    val r = result ?: return
    val primary = r.primary
    if (primary != null) {
        val text = formatMeasure(primary, u, res)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { copy(context, text) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(primary.label), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(text, style = ValueStyle.copy(fontSize = 24.sp), color = extra.dimension)
        }
    }
    val others = r.values.filter { it !== primary }
    if (others.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { v ->
                val text = formatMeasure(v, u, res)
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(scheme.onSurface.copy(alpha = 0.06f))
                        .clickable { copy(context, text) }.padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(stringResource(v.label) + " ", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The point-based modes: distance, angle and circle through three points. */
@Composable
private fun PointContent(
    mode: MeasureMode,
    p: List<PickHit>,
    u: Units,
    showDiameter: Boolean,
    setShowDiameter: (Boolean) -> Unit,
    copy: (String) -> Unit,
) {
    when (mode) {
        MeasureMode.Distance -> if (p.size == 2) {
            val d = p[1].point - p[0].point
            val text = Fmt.length(d.length().toDouble(), u)
            ValueRow(stringResource(R.string.distance), text, highlight = true, onClick = { copy(text) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AxisValue("ΔX", Fmt.lengthShort(kotlin.math.abs(d.x).toDouble(), u), LocalExtraColors.current.axisX)
                AxisValue("ΔY", Fmt.lengthShort(kotlin.math.abs(d.y).toDouble(), u), LocalExtraColors.current.axisY)
                AxisValue("ΔZ", Fmt.lengthShort(kotlin.math.abs(d.z).toDouble(), u), LocalExtraColors.current.axisZ)
            }
        }
        MeasureMode.Circle -> if (p.size == 3) {
            val circ = Engine.circleThrough(p[0].point, p[1].point, p[2].point)
            if (circ == null) {
                Text(stringResource(R.string.collinear_points), color = MaterialTheme.colorScheme.error)
            } else {
                val (c, _, r) = circ
                ValueRow(stringResource(R.string.radius), Fmt.length(r.toDouble(), u), highlight = !showDiameter,
                    icon = if (!showDiameter) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked) { setShowDiameter(false) }
                HorizontalDivider()
                ValueRow(stringResource(R.string.diameter), Fmt.length(2.0 * r, u), highlight = showDiameter,
                    icon = if (showDiameter) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked) { setShowDiameter(true) }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ControlCamera, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.center_xyz), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AxisValue("X:", Fmt.number(c.x * u.factor), null)
                    AxisValue("Y:", Fmt.number(c.y * u.factor), null)
                    AxisValue("Z:", Fmt.number(c.z * u.factor), null)
                }
                val txt = if (showDiameter) Fmt.length(2.0 * r, u) else Fmt.length(r.toDouble(), u)
                PillButton(stringResource(R.string.copy), icon = Icons.Rounded.ContentCopy) { copy(txt) }
            }
        }
        MeasureMode.Angle -> if (p.size == 3) {
            val v1 = (p[0].point - p[1].point).normalized()
            val v2 = (p[2].point - p[1].point).normalized()
            val ang = Fmt.degrees(acos(v1.dot(v2).coerceIn(-1f, 1f)).toDouble())
            ValueRow(stringResource(R.string.angle), ang, highlight = true) { copy(ang) }
        }
        MeasureMode.Smart -> {}
    }
    if (p.isNotEmpty() && p.size < mode.points) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.indices.forEach { i -> Box(Modifier.size(12.dp).clip(CircleShape).background(MeasureColors[i % 3])) }
        }
    }
}

@Composable
private fun AxisValue(label: String, value: String, color: Color?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(value, style = ValueStyle.copy(fontSize = 16.sp))
    }
}

@Composable
fun AnalyzePanel(vm: ViewerViewModel) {
    val tab by vm.analyzeTab.collectAsStateWithLifecycle()
    val th by vm.thickness.collectAsStateWithLifecycle()
    val oh by vm.overhang.collectAsStateWithLifecycle()
    val check by vm.meshCheck.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val u = s.units
    val tabLabels = listOf(stringResource(R.string.thickness), stringResource(R.string.overhang), stringResource(R.string.mesh_check))
    val gradientLabel = stringResource(R.string.gradient)
    val thresholdLabel = stringResource(R.string.threshold)
    ToolPanel(
        stringResource(R.string.analyze_title), Icons.Rounded.Insights, onClose = { vm.selectTool(Tool.None) },
        actions = {
            RoundIconButton(Icons.Rounded.CameraAlt, stringResource(R.string.snapshot)) { vm.shareSnapshot() }
            Spacer(Modifier.width(6.dp))
        },
    ) {
        Segmented(AnalyzeTab.entries, tab, label = { tabLabels[it.ordinal] }, onSelect = vm::setAnalyzeTab)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                AnalyzeTab.Thickness -> {
                    val r = th.result
                    when {
                        th.computing -> {
                            Text(stringResource(R.string.analyzing), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(progress = { th.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        r == null -> {
                            Text(
                                "Casts a ray inward from every triangle to find how thick each wall is. Great for checking 3D prints.",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            )
                            PillButton(stringResource(R.string.tool_analyze), icon = Icons.Rounded.Insights, selected = true) { vm.computeThickness() }
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.thinnest_wall), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(Fmt.length(r.min.toDouble(), u, 3), style = ValueStyle.copy(fontSize = 18.sp), color = extra.dimension)
                            }
                            Box(
                                Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(
                                    Brush.horizontalGradient(listOf(Color(0xFFDB1A14), Color(0xFFF57310), Color(0xFFFADB1F), Color(0xFF59CC4D), Color(0xFF33B3DB), Color(0xFF294DDB))),
                                ),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(Fmt.length(r.p05.toDouble(), u, 3), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text(Fmt.length(r.p95.toDouble(), u, 2), style = MaterialTheme.typography.labelMedium)
                            }
                            Segmented(listOf(false, true), th.threshold, label = { if (it) thresholdLabel else gradientLabel }, onSelect = vm::setThicknessThresholdMode)
                            if (th.threshold) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.min_wall), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(Fmt.length(th.thresholdValue.toDouble(), u, 2), style = ValueStyle.copy(fontSize = 16.sp))
                                }
                                Slider(th.thresholdValue, vm::setThicknessThreshold, valueRange = 0.1f..maxOf(3f, r.p95))
                            }
                        }
                    }
                }
                AnalyzeTab.Overhang -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.overhang_angle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${oh.angle.toInt()}°", style = ValueStyle.copy(fontSize = 18.sp))
                    }
                    Slider(oh.angle, { vm.setOverhangAngle(it) }, valueRange = 20f..80f, steps = 11)
                    val pct = if (oh.totalArea > 0) oh.overhangArea / oh.totalArea * 100 else 0.0
                    ValueRow(stringResource(R.string.overhang_area), "%s (%.1f%%)".format(Locale.US, Fmt.area(oh.overhangArea, u), pct), highlight = true)
                    Text(
                        "Red faces point down more steeply than the angle and will need supports when printed in this orientation.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                AnalyzeTab.MeshCheck -> {
                    val c = check
                    if (c == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.analyzing))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (c.watertight) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                                tint = if (c.watertight) Color(0xFF59CC4D) else scheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (c.watertight) R.string.watertight else R.string.not_watertight), style = MaterialTheme.typography.titleMedium)
                        }
                        ValueRow(stringResource(R.string.open_edges), c.openEdges.toString(), highlight = c.openEdges > 0)
                        ValueRow(stringResource(R.string.non_manifold_edges), c.nonManifoldEdges.toString(), highlight = c.nonManifoldEdges > 0)
                        ValueRow(stringResource(R.string.flipped_edges), c.flippedEdges.toString(), highlight = c.flippedEdges > 0)
                        ValueRow(stringResource(R.string.degenerate_faces), (c.degenerateFaces + c.droppedFaces).toString())
                        ValueRow(stringResource(R.string.shells), c.shells.toString())
                        SwitchRow(stringResource(R.string.highlight_problems), vm.showProblems, vm::setShowProblems)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoPanel(vm: ViewerViewModel) {
    val model by vm.model.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val check by vm.meshCheck.collectAsStateWithLifecycle()
    val info = model ?: return
    val u = s.units
    val b = info.bounds
    ToolPanel(info.name, Icons.Rounded.Info, onClose = { vm.selectTool(Tool.None) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ValueRow(stringResource(R.string.format), vm.modelFormat?.label ?: info.format)
            ValueRow(stringResource(R.string.file_size), Fmt.bytes(info.fileSize))
            ValueRow(stringResource(R.string.triangles), "%,d".format(info.triangles))
            ValueRow(stringResource(R.string.vertices), "%,d".format(info.vertices))
            ValueRow(stringResource(R.string.parts), info.parts.size.toString())
            ValueRow(stringResource(R.string.dimensions), "${Fmt.number(b.size.x * u.factor, 1)} × ${Fmt.number(b.size.y * u.factor, 1)} × ${Fmt.number(b.size.z * u.factor, 1)} ${u.suffix}")
            ValueRow(stringResource(R.string.volume), Fmt.volume(kotlin.math.abs(info.volume), u))
            ValueRow(stringResource(R.string.surface_area), Fmt.area(info.area, u))
            ValueRow(stringResource(R.string.bounds_min), "%.1f, %.1f, %.1f".format(Locale.US, b.min.x, b.min.y, b.min.z))
            ValueRow(stringResource(R.string.bounds_max), "%.1f, %.1f, %.1f".format(Locale.US, b.max.x, b.max.y, b.max.z))
            ValueRow(stringResource(R.string.load_time), "${info.loadMillis} ms", highlight = true)
            check?.let { ValueRow(stringResource(R.string.watertight), if (it.watertight) "✓" else "✗ (${it.openEdges})") }
            if (info.warnings.isNotEmpty()) {
                Text(stringResource(R.string.warnings), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                info.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
