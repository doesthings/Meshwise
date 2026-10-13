package io.github.doesthings.meshwise.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import io.github.doesthings.meshwise.cad.CadImportClient
import io.github.doesthings.meshwise.engine.ModelInfo
import io.github.doesthings.meshwise.engine.NativeLib
import io.github.doesthings.meshwise.engine.NativeProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

enum class LoadStage { Reading, Processing, Cad, Unpacking }

/** A model handle that has been loaded but not yet handed to the engine. */
class LoadedModel(
    handle: Long,
    val info: ModelInfo,
    val format: ModelFormat,
    val source: ModelSource,
    val displayName: String,
    val project: ProjectState?,
) {
    private val owned = java.util.concurrent.atomic.AtomicLong(handle)

    /** Transfers ownership of the native model (e.g. to the engine); dispose() then does nothing. */
    fun take(): Long = owned.getAndSet(0).also { check(it != 0L) { "model already taken" } }

    /** Frees the native model unless it was taken. Safe to call more than once. */
    fun dispose() {
        val h = owned.getAndSet(0)
        if (h != 0L) NativeLib.nativeFreeModel(h)
    }
}

data class SourceMeta(val name: String, val size: Long, val mime: String?, val lastModified: Long)

/** Turns a [ModelSource] into a native model. All work happens on the calling (background) thread. */
class ModelOpener(private val context: Context, private val settings: Settings) {
    private val cad = CadImportClient(context)
    private val cacheDir get() = context.cacheDir

    fun meta(source: ModelSource): SourceMeta = when (source) {
        is ModelSource.FromFile -> SourceMeta(source.file.name, source.file.length(), null, source.file.lastModified())
        is ModelSource.FromAsset -> SourceMeta(source.path.substringAfterLast('/'), -1, null, 0)
        is ModelSource.FromUri -> uriMeta(source.uri)
    }

    private fun uriMeta(uri: Uri): SourceMeta {
        var name: String? = null
        var size = -1L
        var modified = 0L
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return SourceMeta(f.name, f.length(), null, f.lastModified())
        }
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val mi = c.getColumnIndex("last_modified")
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi)
                }
            }
        } catch (_: Exception) {
        }
        val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        return SourceMeta(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model", size, mime, modified)
    }

    fun formatOf(meta: SourceMeta): ModelFormat? = ModelFormat.fromFileName(meta.name) ?: ModelFormat.fromMime(meta.mime)

    suspend fun open(source: ModelSource, onProgress: (LoadStage, Float) -> Unit): LoadedModel {
        val start = SystemClock.elapsedRealtime()
        val meta = meta(source)
        var format = formatOf(meta)
        if (format == null) format = sniff(source) ?: ModelFormat.STL
        val job = currentCoroutineContext()[Job]
        val values = settings.current

        val (handle, project) = when (format.kind) {
            FormatKind.Mesh -> loadMesh(source, meta.name, format, null, values, job, onProgress) to null
            FormatKind.Cad -> loadCad(source, meta, format, values, job, onProgress) to null
            FormatKind.Archive -> loadZip(source, meta, values, job, onProgress) to null
            FormatKind.Project -> loadProject(source, meta, values, job, onProgress)
        }
        val info = try {
            currentCoroutineContext().ensureActive()
            ModelInfo.fromHandle(handle, meta.name, SystemClock.elapsedRealtime() - start)
        } catch (e: Throwable) {
            NativeLib.nativeFreeModel(handle)
            throw e
        }
        return LoadedModel(handle, info, format, source, meta.name, project)
    }

    private fun progressFor(job: Job?, stage: LoadStage, onProgress: (LoadStage, Float) -> Unit) =
        NativeProgress { f ->
            onProgress(stage, f)
            job?.isActive != false
        }

    private fun loadMesh(
        source: ModelSource, name: String, format: ModelFormat, baseDir: String?, v: SettingsValues, job: Job?,
        onProgress: (LoadStage, Float) -> Unit,
    ): Long {
        val ext = format.extensions.first()
        val progress = progressFor(job, LoadStage.Reading, onProgress)
        return when (source) {
            is ModelSource.FromFile ->
                NativeLib.nativeLoadPath(source.file.absolutePath, name, ext, baseDir ?: source.file.parent, v.creaseAngle, v.smoothShading, progress)
            is ModelSource.FromAsset -> {
                val f = assetToCache(source.path)
                NativeLib.nativeLoadPath(f.absolutePath, name, ext, f.parent, v.creaseAngle, v.smoothShading, progress)
            }
            is ModelSource.FromUri -> {
                if (source.uri.scheme == "file") {
                    val f = File(source.uri.path ?: throw IOException("Invalid path"))
                    NativeLib.nativeLoadPath(f.absolutePath, name, ext, f.parent, v.creaseAngle, v.smoothShading, progress)
                } else {
                    val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
                        ?: throw IOException("Could not open the file")
                    pfd.use { NativeLib.nativeLoadFd(it.fd, name, ext, null, v.creaseAngle, v.smoothShading, progress) }
                }
            }
        }
    }

    /** Local copy of a CAD source (content URIs and assets are copied into the cache). */
    fun localInputFor(source: ModelSource, meta: SourceMeta): File = localFile(source, meta, "cad_in")

    /** Where the tessellated result of a CAD file is cached; reopening it skips the CAD kernel. */
    fun cadCacheFile(source: ModelSource, meta: SourceMeta, input: File, quality: Int): File {
        val key = sha1("${source.key}|${input.length()}|${meta.lastModified}|${input.lastModified()}|q$quality")
        return File(File(cacheDir, "cad").apply { mkdirs() }, "$key.fsm")
    }

    /** Returns a local file for any source (copying content URIs and assets). */
    private fun localFile(source: ModelSource, meta: SourceMeta, subdir: String): File = when (source) {
        is ModelSource.FromFile -> source.file
        is ModelSource.FromAsset -> assetToCache(source.path)
        is ModelSource.FromUri -> {
            if (source.uri.scheme == "file") File(source.uri.path!!)
            else {
                val dir = File(cacheDir, subdir).apply { mkdirs() }
                val out = File(dir, sha1(source.key) + "." + ModelFormat.extensionOf(meta.name).ifEmpty { "bin" })
                if (!(out.exists() && meta.size > 0 && out.length() == meta.size)) {
                    context.contentResolver.openInputStream(source.uri)?.use { input -> copyTo(input, out) }
                        ?: throw IOException("Could not open the file")
                }
                out
            }
        }
    }

    private suspend fun loadCad(
        source: ModelSource, meta: SourceMeta, format: ModelFormat, v: SettingsValues, job: Job?,
        onProgress: (LoadStage, Float) -> Unit,
    ): Long {
        onProgress(LoadStage.Cad, 0f)
        val input = localInputFor(source, meta)
        val cacheFile = cadCacheFile(source, meta, input, v.cadQuality)
        if (!cacheFile.exists() || cacheFile.length() < 16) {
            val tmp = File(cacheFile.parentFile, cacheFile.nameWithoutExtension + ".tmp")
            cad.import(input, format.extensions.first(), tmp, v.cadQuality) { f -> onProgress(LoadStage.Cad, f * 0.9f) }
            if (!tmp.renameTo(cacheFile)) throw IOException("Could not store the converted model")
        }
        val progress = progressFor(job, LoadStage.Processing, onProgress)
        return NativeLib.nativeLoadPath(cacheFile.absolutePath, meta.name, "fsm", null, v.creaseAngle, v.smoothShading, progress)
    }

    private suspend fun loadZip(
        source: ModelSource, meta: SourceMeta, v: SettingsValues, job: Job?, onProgress: (LoadStage, Float) -> Unit,
    ): Long {
        onProgress(LoadStage.Unpacking, 0f)
        val dir = File(cacheDir, "unzip/" + sha1(source.key)).apply { deleteRecursively(); mkdirs() }
        val extracted = mutableListOf<File>()
        openStream(source).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    val ext = ModelFormat.extensionOf(name)
                    val wanted = ext in ModelFormat.modelExtensions || ext == "mtl"
                    if (!entry.isDirectory && wanted && !name.startsWith("__MACOSX/") && !name.contains("../")) {
                        val out = File(dir, name)
                        out.parentFile?.mkdirs()
                        copyTo(zip, out)
                        if (ext != "mtl") extracted += out
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (extracted.isEmpty()) throw IOException("The ZIP archive contains no 3D models")
        if (extracted.size > 256) throw IOException("The archive contains too many models (${extracted.size})")
        extracted.sortBy { it.name.lowercase() }
        val handles = mutableListOf<Long>()
        try {
            extracted.forEachIndexed { i, f ->
                currentCoroutineContext().ensureActive()
                val fmt = ModelFormat.fromFileName(f.name) ?: return@forEachIndexed
                val sub: (LoadStage, Float) -> Unit = { stage, p -> onProgress(stage, (i + p) / extracted.size) }
                val h = if (fmt.kind == FormatKind.Cad) {
                    loadCad(ModelSource.FromFile(f), SourceMeta(f.name, f.length(), null, f.lastModified()), fmt, v, job, sub)
                } else {
                    loadMesh(ModelSource.FromFile(f), f.name, fmt, f.parent, v, job, sub)
                }
                handles += h
            }
        } catch (e: Throwable) {
            handles.forEach { NativeLib.nativeFreeModel(it) }
            throw e
        }
        if (handles.size == 1) return handles[0]
        return NativeLib.nativeMerge(handles.toLongArray(), true, meta.name)
    }

    private suspend fun loadProject(
        source: ModelSource, meta: SourceMeta, v: SettingsValues, job: Job?, onProgress: (LoadStage, Float) -> Unit,
    ): Pair<Long, ProjectState?> {
        onProgress(LoadStage.Unpacking, 0f)
        val dir = File(cacheDir, "project/" + sha1(source.key)).apply { deleteRecursively(); mkdirs() }
        var state: ProjectState? = null
        var mesh: File? = null
        openStream(source).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "project.json" -> state = ProjectState.fromJson(zip.readBytes().decodeToString())
                        "model.fsm" -> mesh = File(dir, "model.fsm").also { copyTo(zip, it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val m = mesh ?: throw IOException("This project file is damaged (no model)")
        val progress = progressFor(job, LoadStage.Processing, onProgress)
        val h = NativeLib.nativeLoadPath(m.absolutePath, state?.name ?: meta.name, "fsm", null, v.creaseAngle, v.smoothShading, progress)
        return h to state
    }

    private fun openStream(source: ModelSource): InputStream = when (source) {
        is ModelSource.FromFile -> source.file.inputStream()
        is ModelSource.FromAsset -> context.assets.open(source.path)
        is ModelSource.FromUri -> context.contentResolver.openInputStream(source.uri) ?: throw IOException("Could not open the file")
    }

    private fun sniff(source: ModelSource): ModelFormat? = try {
        openStream(source).use { s ->
            val head = ByteArray(512)
            val n = s.read(head)
            val text = String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1)
            when {
                n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ModelFormat.ZIP
                text.startsWith("ISO-10303-21") -> ModelFormat.STEP
                text.startsWith("DBRep_DrawableShape") || text.startsWith("CASCADE Topology") -> ModelFormat.BREP
                text.length >= 80 && text[72] == 'S' && text.substring(73, 80).trim().all { it.isDigit() } -> ModelFormat.IGES
                text.startsWith("ply") -> ModelFormat.PLY
                text.trimStart().startsWith("OFF") -> ModelFormat.OFF
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun assetToCache(path: String): File {
        val out = File(File(cacheDir, "samples").apply { mkdirs() }, path.substringAfterLast('/'))
        if (!out.exists()) context.assets.open(path).use { copyTo(it, out) }
        return out
    }

    /** Copies a content URI into the private recents folder so it can be reopened later. */
    fun keepCopy(uri: Uri, target: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { copyTo(it, target) } != null
    } catch (_: Exception) {
        false
    }

    companion object {
        fun copyTo(input: InputStream, out: File) {
            val tmp = File(out.parentFile, out.name + ".part")
            FileOutputStream(tmp).use { o -> input.copyTo(o, 1 shl 16) }
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("Could not write ${out.name}")
            }
        }
    }
}
