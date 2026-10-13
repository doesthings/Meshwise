package io.github.doesthings.meshwise.ui.viewer

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.doesthings.meshwise.engine.CameraFrame
import io.github.doesthings.meshwise.engine.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sign

private data class CubeFace(val label: String, val normal: Vec3, val up: Vec3) {
    val right: Vec3 get() = up.cross(normal)
}

private val FACES = listOf(
    CubeFace("FRONT", Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f)),
    CubeFace("BACK", Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)),
    CubeFace("RIGHT", Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
    CubeFace("LEFT", Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f)),
    CubeFace("TOP", Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
    CubeFace("BOTTOM", Vec3(0f, 0f, -1f), Vec3(0f, -1f, 0f)),
)

/**
 * Navigation cube drawn from the camera orientation. Tap a face, edge or corner to look from
 * that direction; drag to orbit.
 */
@Composable
fun ViewCube(
    camera: CameraFrame,
    modifier: Modifier = Modifier,
    onOrient: (yaw: Float, pitch: Float) -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminanceApprox() < 0.5f
    val faceLight = if (dark) Color(0xFF6B7078) else Color(0xFFF5F5F5)
    val faceDark = if (dark) Color(0xFF3B3F45) else Color(0xFFC9CDD2)
    val bevel = if (dark) Color(0xFF26292D) else Color(0xFF9AA0A6)
    val textColor = if (dark) Color.White else Color(0xFF26292D)
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val right = camera.right
    val up = camera.up
    val fwd = camera.forward

    fun toCam(v: Vec3) = Vec3(v.dot(right), v.dot(up), -v.dot(fwd))

    Canvas(
        modifier
            .semantics { contentDescription = "View cube" }
            .pointerInput(camera.yaw, camera.pitch) {
                detectTapGestures { tap ->
                    val s = min(size.width, size.height) * 0.30f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val visible = FACES.map { it to toCam(it.normal) }.filter { it.second.z > 0.05f }.sortedByDescending { it.second.z }
                    for ((face, _) in visible) {
                        val center = project(toCam(face.normal), c, s)
                        val r2 = project(toCam(face.right), Offset.Zero, s)
                        val u2 = project(toCam(face.up), Offset.Zero, s)
                        val d = tap - center
                        val det = r2.x * u2.y - r2.y * u2.x
                        if (abs(det) < 1e-3f) continue
                        val u = (d.x * u2.y - d.y * u2.x) / det
                        val v = (r2.x * d.y - r2.y * d.x) / det
                        if (abs(u) > 1.05f || abs(v) > 1.05f) continue
                        var dir = face.normal
                        if (abs(u) > 0.55f) dir += face.right * sign(u)
                        if (abs(v) > 0.55f) dir += face.up * sign(v)
                        val n = dir.normalized()
                        val pitch = asin(n.z.coerceIn(-1f, 1f))
                        val yaw = if (abs(n.z) > 0.999f) 0f else atan2(n.x, -n.y)
                        onOrient(yaw, pitch)
                        break
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
    ) {
        val s = min(size.width, size.height) * 0.30f
        val c = Offset(size.width / 2f, size.height / 2f)
        val faces = FACES.map { it to toCam(it.normal) }.filter { it.second.z > 0f }.sortedBy { it.second.z }
        // Silhouette in the bevel colour, then inset faces on top: reads as chamfered edges.
        for ((face, nCam) in faces) {
            drawFace(face, nCam, c, s, 1.0f, bevel, ::toCam)
        }
        for ((face, nCam) in faces) {
            val shade = nCam.z.coerceIn(0f, 1f)
            val col = lerp(faceDark, faceLight, 0.35f + 0.65f * shade)
            drawFace(face, nCam, c, s, 0.86f, col, ::toCam)
            if (nCam.z > 0.25f) drawLabel(face, c, s, ::toCam, paint, textColor.copy(alpha = ((nCam.z - 0.25f) / 0.4f).coerceIn(0f, 1f)))
        }
    }
}

private fun project(v: Vec3, c: Offset, s: Float) = Offset(c.x + v.x * s, c.y - v.y * s)

private fun DrawScope.drawFace(face: CubeFace, nCam: Vec3, c: Offset, s: Float, inset: Float, color: Color, toCam: (Vec3) -> Vec3) {
    val r = face.right
    val u = face.up
    val n = face.normal
    val corners = listOf(n - r * inset - u * inset, n + r * inset - u * inset, n + r * inset + u * inset, n - r * inset + u * inset)
    val path = Path()
    corners.forEachIndexed { i, p ->
        val o = project(toCam(p), c, s)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    path.close()
    drawPath(path, color)
    if (inset < 1f) drawPath(path, Color.White.copy(alpha = 0.10f), style = Stroke(width = 1.2f))
}

private fun DrawScope.drawLabel(face: CubeFace, c: Offset, s: Float, toCam: (Vec3) -> Vec3, paint: Paint, color: Color) {
    // Map a text box onto the projected face with an affine transform.
    val box = 100f
    val k = 0.78f
    val p0 = project(toCam(face.normal - face.right * k + face.up * 0.35f), c, s)
    val p1 = project(toCam(face.normal + face.right * k + face.up * 0.35f), c, s)
    val p2 = project(toCam(face.normal - face.right * k - face.up * 0.35f), c, s)
    val src = floatArrayOf(0f, 0f, box, 0f, 0f, box * 0.45f)
    val dst = floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y)
    val m = Matrix()
    if (!m.setPolyToPoly(src, 0, dst, 0, 3)) return
    paint.color = color.toArgb()
    paint.textSize = if (face.label.length > 5) 24f else 30f
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        nc.save()
        nc.concat(m)
        val fm = paint.fontMetrics
        nc.drawText(face.label, box / 2f, box * 0.225f - (fm.ascent + fm.descent) / 2f, paint)
        nc.restore()
    }
}


private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
