package io.github.doesthings.meshwise.data

import java.util.Locale

enum class FormatKind { Mesh, Cad, Archive, Project }

enum class ModelFormat(val label: String, val kind: FormatKind, vararg val extensions: String) {
    STL("STL", FormatKind.Mesh, "stl"),
    OBJ("OBJ", FormatKind.Mesh, "obj"),
    PLY("PLY", FormatKind.Mesh, "ply"),
    OFF("OFF", FormatKind.Mesh, "off"),
    ThreeMF("3MF", FormatKind.Mesh, "3mf"),
    FSM("FSM", FormatKind.Mesh, "fsm"),
    STEP("STEP", FormatKind.Cad, "step", "stp", "p21"),
    IGES("IGES", FormatKind.Cad, "iges", "igs"),
    BREP("BREP", FormatKind.Cad, "brep", "brp"),
    ZIP("ZIP", FormatKind.Archive, "zip"),
    Project("PROJECT", FormatKind.Project, "meshwise");

    companion object {
        fun fromFileName(name: String): ModelFormat? = fromExtension(extensionOf(name))

        fun fromExtension(ext: String): ModelFormat? {
            val e = ext.lowercase(Locale.ROOT)
            return entries.firstOrNull { e in it.extensions }
        }

        fun extensionOf(name: String): String {
            val n = name.substringAfterLast('/')
            val dot = n.lastIndexOf('.')
            return if (dot > 0) n.substring(dot + 1).lowercase(Locale.ROOT) else ""
        }

        /** Best guess from a MIME type when the name has no usable extension. */
        fun fromMime(mime: String?): ModelFormat? = when (mime?.lowercase(Locale.ROOT)) {
            "model/stl", "model/x.stl-binary", "model/x.stl-ascii", "application/sla", "application/vnd.ms-pki.stl" -> STL
            "model/obj" -> OBJ
            "model/3mf", "application/vnd.ms-3mfdocument", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" -> ThreeMF
            "model/step", "application/step", "model/step+xml" -> STEP
            "model/iges", "application/iges" -> IGES
            "model/x-ply" -> PLY
            "application/zip" -> ZIP
            else -> null
        }

        val modelExtensions: Set<String> = entries.filter { it.kind == FormatKind.Mesh || it.kind == FormatKind.Cad }
            .flatMap { it.extensions.toList() }.toSet()
    }
}

fun baseName(name: String): String {
    val n = name.substringAfterLast('/')
    val dot = n.lastIndexOf('.')
    return if (dot > 0) n.substring(0, dot) else n
}
