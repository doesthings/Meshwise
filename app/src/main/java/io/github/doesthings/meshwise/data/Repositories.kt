package io.github.doesthings.meshwise.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/** Where a model comes from. Serialised into recents as "uri:", "file:" or "asset:". */
sealed interface ModelSource {
    val key: String

    data class FromUri(val uri: Uri) : ModelSource {
        override val key get() = "uri:$uri"
    }

    data class FromFile(val file: File) : ModelSource {
        override val key get() = "file:${file.absolutePath}"
    }

    data class FromAsset(val path: String) : ModelSource {
        override val key get() = "asset:$path"
    }

    companion object {
        fun parse(key: String): ModelSource? = when {
            key.startsWith("uri:") -> FromUri(Uri.parse(key.removePrefix("uri:")))
            key.startsWith("file:") -> FromFile(File(key.removePrefix("file:")))
            key.startsWith("asset:") -> FromAsset(key.removePrefix("asset:"))
            else -> null
        }
    }
}

fun sha1(s: String): String {
    val md = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
    return md.joinToString("") { "%02x".format(it) }
}

data class RecentFile(
    val id: String,
    val source: String,
    val name: String,
    val format: String,
    val size: Long,
    val openedAt: Long,
    val triangles: Long,
    val parts: Int,
    /** Private copy used when the original URI can't be reopened later. */
    val localCopy: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("source", source); put("name", name); put("format", format); put("size", size)
        put("openedAt", openedAt); put("triangles", triangles); put("parts", parts)
        if (localCopy != null) put("localCopy", localCopy)
    }

    companion object {
        fun fromJson(o: JSONObject) = RecentFile(
            id = o.getString("id"), source = o.getString("source"), name = o.getString("name"),
            format = o.optString("format"), size = o.optLong("size"), openedAt = o.optLong("openedAt"),
            triangles = o.optLong("triangles"), parts = o.optInt("parts", 1),
            localCopy = if (o.has("localCopy")) o.getString("localCopy") else null,
        )
    }
}

/** Thumbnails rendered by the engine, stored as WebP in filesDir/thumbs. */
class ThumbnailStore(context: Context) {
    private val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val version = MutableStateFlow(0)
    val changes: StateFlow<Int> = version.asStateFlow()

    fun file(id: String) = File(dir, "$id.webp")

    fun save(id: String, bitmap: Bitmap) {
        val f = file(id)
        f.outputStream().use {
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bitmap.compress(fmt, 88, it)
        }
        version.value++
    }

    fun delete(id: String) {
        file(id).delete()
        version.value++
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        version.value++
    }
}

class RecentFiles(private val context: Context, private val thumbs: ThumbnailStore) {
    private val file = File(context.filesDir, "recents.json")
    private val copiesDir = File(context.filesDir, "recent").apply { mkdirs() }
    private val state = MutableStateFlow(load())
    val items: StateFlow<List<RecentFile>> = state.asStateFlow()

    fun copyDir(): File = copiesDir

    @Synchronized
    fun add(item: RecentFile) {
        val list = state.value.filterNot { it.id == item.id }.toMutableList()
        list.add(0, item)
        while (list.size > MAX) {
            val removed = list.removeAt(list.size - 1)
            cleanup(removed)
        }
        state.value = list
        save()
    }

    @Synchronized
    fun remove(id: String) {
        val item = state.value.firstOrNull { it.id == id } ?: return
        cleanup(item)
        state.value = state.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun clear() {
        state.value.forEach { cleanup(it) }
        state.value = emptyList()
        save()
    }

    fun find(id: String) = state.value.firstOrNull { it.id == id }

    private fun cleanup(item: RecentFile) {
        thumbs.delete(item.id)
        item.localCopy?.let { File(it).delete() }
    }

    private fun load(): List<RecentFile> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { RecentFile.fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        state.value.forEach { arr.put(it.toJson()) }
        // Best effort: a full disk must not turn a successful open into an error.
        try {
            val tmp = File(file.parentFile, "recents.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (e: java.io.IOException) {
            android.util.Log.w("Meshwise", "could not save recent files", e)
        }
    }

    companion object {
        const val MAX = 40
    }
}

data class LibraryItem(val file: File, val name: String, val format: ModelFormat?, val size: Long, val modified: Long) {
    val id: String get() = sha1("file:${file.absolutePath}")
}

/** Models saved inside the app (filesDir/library). */
class Library(context: Context) {
    val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val state = MutableStateFlow(scan())
    val items: StateFlow<List<LibraryItem>> = state.asStateFlow()

    fun refresh() {
        state.value = scan()
    }

    /** Returns a non-clashing file in the library for [name]. */
    fun newFile(name: String): File {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "model" }
        var f = File(dir, clean)
        var i = 2
        while (f.exists()) {
            f = File(dir, "${baseName(clean)} ($i).${ModelFormat.extensionOf(clean)}")
            i++
        }
        return f
    }

    fun delete(item: LibraryItem) {
        item.file.delete()
        refresh()
    }

    fun rename(item: LibraryItem, newName: String): Boolean {
        val ext = ModelFormat.extensionOf(item.name)
        val target = newFile(if (newName.endsWith(".$ext", ignoreCase = true)) newName else "$newName.$ext")
        val ok = item.file.renameTo(target)
        refresh()
        return ok
    }

    private fun scan(): List<LibraryItem> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .map { LibraryItem(it, it.name, ModelFormat.fromFileName(it.name), it.length(), it.lastModified()) }
        .sortedByDescending { it.modified }
}
