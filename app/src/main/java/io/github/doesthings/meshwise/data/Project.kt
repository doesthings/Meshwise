package io.github.doesthings.meshwise.data

import io.github.doesthings.meshwise.engine.CameraState
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.Vec3
import org.json.JSONArray
import org.json.JSONObject

/** Per-part viewer state that a project remembers. */
data class PartState(
    val color: Int,
    val visible: Boolean = true,
    val material: MaterialPreset = MaterialPreset.Matte,
    val useVertexColors: Boolean = false,
    val manualOffset: Vec3 = Vec3.ZERO,
)

/** Everything saved in a .meshwise project besides the mesh itself. */
data class ProjectState(
    val name: String,
    val parts: List<PartState>,
    val explode: Float,
    val camera: CameraState?,
    val background: Int,
    val lighting: Int,
    val exposure: Float,
    val edges: Boolean,
    val wireframe: Boolean,
    val section: Boolean,
    val sectionAxis: Int,
    val sectionValue: Float,
    val sectionFlip: Boolean,
    val bgTop: Int = 0xFF3C4C55.toInt(),
    val bgBottom: Int = 0xFF222B31.toInt(),
) {
    fun toJson(): String = JSONObject().apply {
        put("app", "Meshwise")
        put("version", 1)
        put("name", name)
        put("explode", explode.toDouble())
        put("background", background)
        if (background < 0) {
            put("backgroundTop", String.format("#%08X", bgTop))
            put("backgroundBottom", String.format("#%08X", bgBottom))
        }
        put("lighting", lighting)
        put("exposure", exposure.toDouble())
        put("edges", edges)
        put("wireframe", wireframe)
        put("section", JSONObject().apply {
            put("enabled", section); put("axis", sectionAxis); put("value", sectionValue.toDouble()); put("flip", sectionFlip)
        })
        camera?.let { c ->
            put("camera", JSONObject().apply {
                put("target", vec(c.target)); put("distance", c.distance.toDouble()); put("yaw", c.yaw.toDouble())
                put("pitch", c.pitch.toDouble()); put("ortho", c.ortho)
            })
        }
        put("parts", JSONArray().apply {
            parts.forEach { p ->
                put(JSONObject().apply {
                    put("color", String.format("#%08X", p.color))
                    put("visible", p.visible)
                    put("material", p.material.name)
                    put("vertexColors", p.useVertexColors)
                    put("offset", vec(p.manualOffset))
                })
            }
        })
    }.toString(2)

    companion object {
        private fun vec(v: Vec3) = JSONArray().apply { put(v.x.toDouble()); put(v.y.toDouble()); put(v.z.toDouble()) }
        private fun vec(a: JSONArray?) = if (a == null || a.length() < 3) Vec3.ZERO
            else Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

        fun fromJson(text: String): ProjectState {
            val o = JSONObject(text)
            val sec = o.optJSONObject("section")
            val cam = o.optJSONObject("camera")
            val partsArr = o.optJSONArray("parts") ?: JSONArray()
            return ProjectState(
                name = o.optString("name", "Project"),
                parts = (0 until partsArr.length()).map { i ->
                    val p = partsArr.getJSONObject(i)
                    PartState(
                        color = parseColor(p.optString("color", "#FFCCCCCC")),
                        visible = p.optBoolean("visible", true),
                        material = runCatching { MaterialPreset.valueOf(p.optString("material")) }.getOrDefault(MaterialPreset.Matte),
                        useVertexColors = p.optBoolean("vertexColors", false),
                        manualOffset = vec(p.optJSONArray("offset")),
                    )
                },
                explode = o.optDouble("explode", 0.0).toFloat(),
                camera = cam?.let {
                    CameraState(vec(it.optJSONArray("target")), it.optDouble("distance", 100.0).toFloat(),
                        it.optDouble("yaw", 0.0).toFloat(), it.optDouble("pitch", 0.0).toFloat(), it.optBoolean("ortho", false))
                },
                background = o.optInt("background", 0),
                lighting = o.optInt("lighting", 0),
                exposure = o.optDouble("exposure", 1.0).toFloat(),
                edges = o.optBoolean("edges", false),
                wireframe = o.optBoolean("wireframe", false),
                section = sec?.optBoolean("enabled", false) ?: false,
                sectionAxis = sec?.optInt("axis", 2) ?: 2,
                sectionValue = sec?.optDouble("value", 0.0)?.toFloat() ?: 0f,
                sectionFlip = sec?.optBoolean("flip", false) ?: false,
                bgTop = parseColor(o.optString("backgroundTop", "#FF3C4C55")),
                bgBottom = parseColor(o.optString("backgroundBottom", "#FF222B31")),
            )
        }

        private fun parseColor(s: String): Int = try {
            java.lang.Long.parseLong(s.removePrefix("#"), 16).toInt()
        } catch (e: NumberFormatException) {
            0xFFCCCCCC.toInt()
        }
    }
}
