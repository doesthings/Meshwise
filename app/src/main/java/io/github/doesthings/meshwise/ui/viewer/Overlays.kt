package io.github.doesthings.meshwise.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.LoadStage
import io.github.doesthings.meshwise.data.Units
import io.github.doesthings.meshwise.engine.Bounds
import io.github.doesthings.meshwise.engine.CameraFrame
import io.github.doesthings.meshwise.engine.Engine
import io.github.doesthings.meshwise.engine.PickHit
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.AnalyzeTab
import io.github.doesthings.meshwise.viewer.LoadingState
import io.github.doesthings.meshwise.viewer.MeasureMode
import io.github.doesthings.meshwise.viewer.MeasureState
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

val MeasureColors = listOf(Color(0xFF3EE6C4), Color(0xFFFF4D8D), Color(0xFFFFE14D))

@Composable
fun OverlayLayer(vm: ViewerViewModel) {
    val camera by vm.camera.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val measure by vm.measure.collectAsStateWithLifecycle()
    val dims by vm.showDimensions.collectAsStateWithLifecycle()
    val bounds by vm.sceneBounds.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val thickness by vm.thickness.collectAsStateWithLifecycle()
    val analyzeTab by vm.analyzeTab.collectAsStateWithLifecycle()
    val manual by vm.manualExplode.collectAsStateWithLifecycle()
    val parts by vm.parts.collectAsStateWithLifecycle()
    val explode by vm.explode.collectAsStateWithLifecycle()
    val extra = LocalExtraColors.current
    val measurer = rememberTextMeasurer()
    val units = settings.units
    val insets by vm.viewInsets.collectAsStateWithLifecycle()

    // Dimension box: the selected part when there is one, else the visible scene.
    val dimBounds = if (selected >= 0 && model != null && selected < model!!.parts.size) {
        model!!.parts[selected].bounds.translated(vm.partOffset(selected).also { parts; explode })
    } else bounds

    Canvas(Modifier.fillMaxSize()) {
        if (camera.width < 2f) return@Canvas
        band = insets.first to (size.height - insets.second).coerceAtLeast(insets.first + 1f)
        if (dims && dimBounds.size.length() > 0f) drawDimensions(camera, dimBounds, units, extra.dimension, measurer)
        if (tool == Tool.Measure || measure.points.isNotEmpty()) drawMeasure(camera, measure, units, measurer)
        val res = thickness.result
        if (tool == Tool.Analyze && analyzeTab == AnalyzeTab.Thickness && res != null) {
            drawCallouts(camera, res.spots.map { it.position to Fmt.length(it.value.toDouble(), units, 3) }, bounds.center, extra.dimension, measurer)
        }
        if (manual && selected >= 0 && model != null && selected < model!!.parts.size) {
            val c = model!!.parts[selected].bounds.center + vm.partOffset(selected)
            drawGizmo(camera, c, extra.axisX, extra.axisY, extra.axisZ)
        }
    }

    // Drag handles for the explode gizmo.
    if (manual && selected >= 0 && model != null && selected < model!!.parts.size) {
        val c = model!!.parts[selected].bounds.center + vm.partOffset(selected)
        GizmoHandles(camera, c, onStart = { vm.beginPartDrag(selected) }) { axis, delta -> vm.movePart(selected, Vec3.axis(axis) * delta) }
    }
}

private fun pixelsPerUnit(cam: CameraFrame, p: Vec3, dir: Vec3): Float {
    val a = cam.project(p) ?: return 0f
    val b = cam.project(p + dir) ?: return 0f
    return (b - a).getDistance()
}

private val gizmoLengthPx = 70f

private fun DrawScope.drawGizmo(cam: CameraFrame, c: Vec3, cx: Color, cy: Color, cz: Color) {
    val o = cam.project(c) ?: return
    listOf(Vec3(1f, 0f, 0f) to cx, Vec3(0f, 1f, 0f) to cy, Vec3(0f, 0f, 1f) to cz).forEach { (axis, color) ->
        val ppu = pixelsPerUnit(cam, c, axis).coerceAtLeast(1e-6f)
        val len = (gizmoLengthPx * density / 2.6f) / ppu
        for (sgn in listOf(1f, -1f)) {
            val tip = cam.project(c + axis * (len * sgn)) ?: continue
            drawLine(color, o, tip, strokeWidth = 4f * density / 2.6f, cap = StrokeCap.Round)
            val dir = (tip - o).let { it / it.getDistance().coerceAtLeast(1e-3f) }
            val n = Offset(-dir.y, dir.x)
            val head = Path().apply {
                val s = 12f * density / 2.6f
                moveTo(tip.x + dir.x * s, tip.y + dir.y * s)
                lineTo(tip.x + n.x * s * 0.6f, tip.y + n.y * s * 0.6f)
                lineTo(tip.x - n.x * s * 0.6f, tip.y - n.y * s * 0.6f)
                close()
            }
            drawPath(head, color)
        }
    }
    drawCircle(Color.White, 5f * density / 2.6f, o)
}

@Composable
private fun GizmoHandles(cam: CameraFrame, c: Vec3, onStart: () -> Unit, onMove: (axis: Int, delta: Float) -> Unit) {
    val density = LocalDensity.current
    val handle = 44.dp
    val handlePx = with(density) { handle.toPx() }
    val o = cam.project(c) ?: return
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    for (axis in 0..2) {
        val dir = Vec3.axis(axis)
        val ppu = pixelsPerUnit(cam, c, dir).coerceAtLeast(1e-6f)
        val len = (gizmoLengthPx * density.density / 2.6f) / ppu
        for (sgn in listOf(1f, -1f)) key(axis, sgn) {
            val tip = cam.project(c + dir * (len * sgn))
            val axisEnd = cam.project(c + dir)
            if (tip != null && axisEnd != null) {
                // Every move changes the camera frame; the gesture must survive that, so it is
                // keyed on the handle only and reads the latest projection through state.
                val screenAxis by rememberUpdatedState(axisEnd - o)
                Box(
                    Modifier
                        .offset { IntOffset((tip.x - handlePx / 2).roundToInt(), (tip.y - handlePx / 2).roundToInt()) }
                        .size(handle)
                        .clip(CircleShape)
                        .pointerInput(axis, sgn) {
                            detectDragGestures(onDragStart = { start() }) { change, drag ->
                                change.consume()
                                val a = screenAxis
                                val l2 = a.x * a.x + a.y * a.y
                                if (l2 > 1e-8f) move(axis, (drag.x * a.x + drag.y * a.y) / l2)
                            }
                        },
                )
            }
        }
    }
}

/** Vertical band of the view not covered by UI (labels are kept inside it). */
private var band: Pair<Float, Float> = 0f to Float.MAX_VALUE

private val labelStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)

private fun DrawScope.labelSize(measurer: TextMeasurer, text: String): Size {
    val layout = measurer.measure(text, labelStyle)
    val pad = 6.dp.toPx()
    return Size(layout.size.width + pad * 2, layout.size.height + pad)
}

/** Top-left of a label box centred at [at], clamped to the visible band. */
private fun DrawScope.labelRect(measurer: TextMeasurer, text: String, at: Offset): androidx.compose.ui.geometry.Rect {
    val sz = labelSize(measurer, text)
    val margin = 4.dp.toPx()
    val top = band.first + margin
    val bottom = minOf(size.height, band.second) - margin
    val x = (at.x - sz.width / 2).coerceIn(margin, (size.width - sz.width - margin).coerceAtLeast(margin))
    val y = (at.y - sz.height / 2).coerceIn(top, (bottom - sz.height).coerceAtLeast(top))
    return androidx.compose.ui.geometry.Rect(Offset(x, y), sz)
}

private fun DrawScope.labelBox(measurer: TextMeasurer, text: String, at: Offset, border: Color, center: Boolean = true) {
    val layout = measurer.measure(text, labelStyle)
    val pad = 6.dp.toPx()
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad
    val tl = if (center) labelRect(measurer, text, at).topLeft else at
    drawRoundRect(Color(0xE6121A1F), tl, Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
    drawRoundRect(border, tl, Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad / 2))
}

private fun DrawScope.drawDimensions(cam: CameraFrame, b: Bounds, units: Units, color: Color, measurer: TextMeasurer) {
    val f = cam.forward
    val xNear = if (f.x > 0f) b.min.x else b.max.x
    val yNear = if (f.y > 0f) b.min.y else b.max.y
    val yFar = if (f.y > 0f) b.max.y else b.min.y
    val z0 = b.min.z
    fun corner(i: Int) = Vec3(if (i and 1 == 0) b.min.x else b.max.x, if (i and 2 == 0) b.min.y else b.max.y, if (i and 4 == 0) b.min.z else b.max.z)
    // Faint box.
    val edges = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
    edges.forEach { (a, c) ->
        val pa = cam.project(corner(a)); val pc = cam.project(corner(c))
        if (pa != null && pc != null) drawLine(color.copy(alpha = 0.35f), pa, pc, strokeWidth = 1.5f)
    }
    fun dim(p0: Vec3, p1: Vec3, value: Float) {
        val a = cam.project(p0) ?: return
        val c = cam.project(p1) ?: return
        drawLine(color, a, c, strokeWidth = 2.5f)
        drawCircle(color, 4f, a)
        drawCircle(color, 4f, c)
        labelBox(measurer, Fmt.lengthShort(value.toDouble(), units), (a + c) / 2f, color)
    }
    dim(Vec3(b.min.x, yNear, z0), Vec3(b.max.x, yNear, z0), b.size.x)
    dim(Vec3(xNear, b.min.y, z0), Vec3(xNear, b.max.y, z0), b.size.y)
    dim(Vec3(xNear, yFar, b.min.z), Vec3(xNear, yFar, b.max.z), b.size.z)
}

private fun DrawScope.drawDisc(cam: CameraFrame, p: Vec3, n: Vec3, color: Color) {
    val ppu = pixelsPerUnit(cam, p, cam.right).coerceAtLeast(1e-6f)
    val r = 15.dp.toPx() / ppu
    val u = (if (kotlin.math.abs(n.z) < 0.9f) n.cross(Vec3(0f, 0f, 1f)) else n.cross(Vec3(1f, 0f, 0f))).normalized()
    val v = n.cross(u)
    val path = Path()
    var started = false
    for (i in 0..32) {
        val a = i / 32f * 2f * Math.PI.toFloat()
        val q = cam.project(p + u * (r * cos(a)) + v * (r * sin(a))) ?: continue
        if (!started) { path.moveTo(q.x, q.y); started = true } else path.lineTo(q.x, q.y)
    }
    path.close()
    drawPath(path, color.copy(alpha = 0.55f))
    cam.project(p)?.let {
        drawCircle(Color.White.copy(alpha = 0.9f), 5.dp.toPx() / 2f + 1f, it)
        drawCircle(color, 4.dp.toPx() / 2f, it)
    }
}

private fun DrawScope.drawMeasure(cam: CameraFrame, st: MeasureState, units: Units, measurer: TextMeasurer) {
    val pts = st.points
    val white = Color.White
    when (st.mode) {
        MeasureMode.Distance -> if (pts.size == 2) {
            val a = cam.project(pts[0].point); val b = cam.project(pts[1].point)
            if (a != null && b != null) {
                drawLine(white, a, b, strokeWidth = 3f, cap = StrokeCap.Round)
                labelBox(measurer, Fmt.length((pts[1].point - pts[0].point).length().toDouble(), units), (a + b) / 2f, MeasureColors[0])
            }
        }
        MeasureMode.Circle -> if (pts.size == 3) {
            val circ = Engine.circleThrough(pts[0].point, pts[1].point, pts[2].point)
            if (circ != null) {
                val (c, n, r) = circ
                val u = (pts[0].point - c).normalized()
                val v = n.cross(u)
                val path = Path()
                var started = false
                for (i in 0..96) {
                    val a = i / 96f * 2f * Math.PI.toFloat()
                    val q = cam.project(c + u * (r * cos(a)) + v * (r * sin(a))) ?: continue
                    if (!started) { path.moveTo(q.x, q.y); started = true } else path.lineTo(q.x, q.y)
                }
                drawPath(path, white, style = Stroke(3f))
                val pc = cam.project(c); val p0 = cam.project(pts[0].point)
                if (pc != null && p0 != null) {
                    drawLine(white, pc, p0, strokeWidth = 3f)
                    drawCircle(white, 7.dp.toPx() / 2f, pc)
                    labelBox(measurer, "R " + Fmt.length(r.toDouble(), units), (pc + p0) / 2f, MeasureColors[0])
                }
            }
        }
        MeasureMode.Angle -> if (pts.size >= 2) {
            val a = cam.project(pts[0].point); val b = cam.project(pts[1].point)
            if (a != null && b != null) drawLine(white, a, b, strokeWidth = 3f)
            if (pts.size == 3) {
                val c = cam.project(pts[2].point)
                if (b != null && c != null) drawLine(white, b, c, strokeWidth = 3f)
                val v1 = (pts[0].point - pts[1].point).normalized()
                val v2 = (pts[2].point - pts[1].point).normalized()
                val ang = acos(v1.dot(v2).coerceIn(-1f, 1f))
                if (b != null) labelBox(measurer, Fmt.degrees(ang.toDouble()), b + Offset(0f, -28.dp.toPx()), MeasureColors[1])
            }
        }
        MeasureMode.Point -> pts.firstOrNull()?.let { p ->
            cam.project(p.point)?.let { o ->
                labelBox(measurer, "%.2f, %.2f, %.2f".format(java.util.Locale.US, p.point.x, p.point.y, p.point.z), o + Offset(0f, -30.dp.toPx()), MeasureColors[0])
            }
        }
    }
    pts.forEachIndexed { i, p -> drawDisc(cam, p.point, p.normal, MeasureColors[i % MeasureColors.size]) }
}

/** Leader-line callouts pushed outwards from the model centre without overlapping. */
private fun DrawScope.drawCallouts(cam: CameraFrame, items: List<Pair<Vec3, String>>, center: Vec3, color: Color, measurer: TextMeasurer) {
    val c = cam.project(center) ?: return
    val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
    val gap = 6.dp.toPx()
    items.forEach { (p, text) ->
        val o = cam.project(p) ?: return@forEach
        var dir = o - c
        val len = dir.getDistance()
        dir = if (len > 1f) dir / len else Offset(-0.7f, -0.7f)
        // Try increasing distances and alternate sides until the box is free.
        var rect = labelRect(measurer, text, o + dir * 56.dp.toPx())
        val candidates = (0 until 24).map { k ->
            val dist = (56 + (k / 4) * 22).dp.toPx()
            val side = when (k % 4) { 0 -> dir; 1 -> Offset(dir.y, -dir.x); 2 -> Offset(-dir.y, dir.x); else -> -dir }
            o + side * dist
        }
        for (cand in candidates) {
            val r = labelRect(measurer, text, cand)
            if (placed.none { it.inflate(gap).overlaps(r) }) { rect = r; break }
        }
        placed += rect
        drawLine(color, o, rect.center, strokeWidth = 2f)
        drawCircle(color, 3.dp.toPx(), o, style = Stroke(1.dp.toPx()))
        labelBox(measurer, text, rect.center, color)
    }
}

@Composable
fun LoadingOverlay(state: LoadingState, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerHigh, shadowElevation = 12.dp, modifier = Modifier.padding(32.dp).widthIn(max = 420.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        when (state.stage) {
                            LoadStage.Reading -> R.string.loading_reading
                            LoadStage.Processing -> R.string.loading_processing
                            LoadStage.Cad -> R.string.loading_cad
                            LoadStage.Unpacking -> R.string.loading_unpacking
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                if (state.progress > 0.001f) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
