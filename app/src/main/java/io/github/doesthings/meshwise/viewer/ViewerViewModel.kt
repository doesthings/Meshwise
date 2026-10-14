package io.github.doesthings.meshwise.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.doesthings.meshwise.BuildConfig
import io.github.doesthings.meshwise.MeshwiseApp
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.cad.CadCrashException
import io.github.doesthings.meshwise.cad.CadUnavailableException
import io.github.doesthings.meshwise.data.FormatKind
import io.github.doesthings.meshwise.data.LoadStage
import io.github.doesthings.meshwise.data.LoadedModel
import io.github.doesthings.meshwise.data.ModelFormat
import io.github.doesthings.meshwise.data.ModelSource
import io.github.doesthings.meshwise.data.PartState
import io.github.doesthings.meshwise.data.ProjectState
import io.github.doesthings.meshwise.data.RecentFile
import io.github.doesthings.meshwise.data.SettingsValues
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.data.UPDATES_OFF
import io.github.doesthings.meshwise.data.UPDATES_ON
import io.github.doesthings.meshwise.data.UpdateInfo
import io.github.doesthings.meshwise.data.Updates
import io.github.doesthings.meshwise.data.baseName
import io.github.doesthings.meshwise.data.sha1
import io.github.doesthings.meshwise.data.Printers
import io.github.doesthings.meshwise.engine.CameraFrame
import io.github.doesthings.meshwise.engine.DensityPreset
import io.github.doesthings.meshwise.engine.Filament
import io.github.doesthings.meshwise.engine.Engine
import io.github.doesthings.meshwise.engine.EstimateSettings
import io.github.doesthings.meshwise.engine.ExportFormat
import io.github.doesthings.meshwise.engine.MaterialPreset
import io.github.doesthings.meshwise.engine.MeasureFeature
import io.github.doesthings.meshwise.engine.Affine
import io.github.doesthings.meshwise.engine.FeatureKind
import io.github.doesthings.meshwise.engine.ModelInfo
import io.github.doesthings.meshwise.engine.NativeLib
import io.github.doesthings.meshwise.engine.Vec3
import io.github.doesthings.meshwise.engine.MeshCheck
import io.github.doesthings.meshwise.engine.NativeProgress
import io.github.doesthings.meshwise.engine.Palette
import io.github.doesthings.meshwise.engine.PickHit
import io.github.doesthings.meshwise.engine.RenderOptions
import io.github.doesthings.meshwise.engine.ShadingMode
import io.github.doesthings.meshwise.engine.StandardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** GL-thread bridge implemented by the surface view. */
interface GlHost {
    fun requestRender()
    fun queueOnGl(block: () -> Unit)
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeshwiseApp
    val engine = Engine()
    val settings: StateFlow<SettingsValues> = app.settings.values
    val recents = app.recents.items
    val library = app.library.items
    val thumbnailVersion = app.thumbnails.changes

    // Navigation.
    private val _screen = MutableStateFlow(Screen.Home)
    val screen = _screen.asStateFlow()

    // Loading.
    private val _loading = MutableStateFlow<LoadingState?>(null)
    val loading = _loading.asStateFlow()
    private var loadJob: Job? = null

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // Model.
    private val _model = MutableStateFlow<ModelInfo?>(null)
    val model = _model.asStateFlow()
    var modelFormat: ModelFormat? = null
        private set
    private var currentSource: ModelSource? = null
    private var currentRecentId: String? = null

    private val _parts = MutableStateFlow<List<PartState>>(emptyList())
    val parts = _parts.asStateFlow()
    private val _selected = MutableStateFlow(-1)
    val selected = _selected.asStateFlow()
    private val _explode = MutableStateFlow(0f)
    val explode = _explode.asStateFlow()
    private val _manualExplode = MutableStateFlow(false)
    val manualExplode = _manualExplode.asStateFlow()
    private val offsetUndo = ArrayDeque<OffsetEdit>()

    // Tools and options.
    private val _tool = MutableStateFlow(Tool.None)
    val tool = _tool.asStateFlow()
    private val _options = MutableStateFlow(RenderOptions())
    val options = _options.asStateFlow()
    private val _section = MutableStateFlow(SectionState())
    val section = _section.asStateFlow()
    private val _measure = MutableStateFlow(MeasureState())
    val measure = _measure.asStateFlow()
    private val _measureHover = MutableStateFlow<MeasureHover?>(null)
    private val _print = MutableStateFlow(PrintState())
    val print = _print.asStateFlow()
    val measureHover = _measureHover.asStateFlow()
    private val _showDimensions = MutableStateFlow(false)
    val showDimensions = _showDimensions.asStateFlow()
    private val _paintColor = MutableStateFlow(Palette.swatches[12])
    val paintColor = _paintColor.asStateFlow()
    private val _turntable = MutableStateFlow(false)
    val turntable = _turntable.asStateFlow()
    private val _analyzeTab = MutableStateFlow(AnalyzeTab.Thickness)
    val analyzeTab = _analyzeTab.asStateFlow()
    private val _thickness = MutableStateFlow(ThicknessUi())
    val thickness = _thickness.asStateFlow()
    private val _overhang = MutableStateFlow(OverhangUi())
    val overhang = _overhang.asStateFlow()
    private val _meshCheck = MutableStateFlow<MeshCheck?>(null)
    val meshCheck = _meshCheck.asStateFlow()
    private val _analysisReady = MutableStateFlow(false)
    val analysisReady = _analysisReady.asStateFlow()
    private var analysisJob: Job? = null
    private var thicknessJob: Job? = null

    // Camera frame for overlays (published from the GL thread).
    private val _camera = MutableStateFlow(CameraFrame.EMPTY)
    val camera = _camera.asStateFlow()
    private val _sceneBounds = MutableStateFlow(io.github.doesthings.meshwise.engine.Bounds.EMPTY)
    val sceneBounds = _sceneBounds.asStateFlow()

    private var glHost: GlHost? = null

    // GL work from coroutines (snapshots, thumbnails). It runs at the start of the next frame
    // rather than through GLSurfaceView.queueEvent, whose events also run while the view is
    // paused, when no EGL context is current.
    private val glTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private var thumbnailPending = false
    var density: Float = 2f

    init {
        viewModelScope.launch {
            settings.collect { applySettings(it) }
        }
    }

    // ------------------------------------------------------------------ updates

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    /** The newest release when it is newer than this app (it stays known after "Not now"). */
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()
    private val _updatePrompt = MutableStateFlow(false)
    /** Whether the update dialog is showing. */
    val updatePrompt: StateFlow<Boolean> = _updatePrompt.asStateFlow()
    private val _updateStep = MutableStateFlow<UpdateStep>(UpdateStep.Idle)
    val updateStep: StateFlow<UpdateStep> = _updateStep.asStateFlow()

    /** Fetches the newest release newer than the given version; replaceable in tests. */
    var updateSource: (currentVersion: String) -> UpdateInfo? = Updates::fetchLatest
    /** Downloads an update's APK; replaceable in tests. */
    var updateDownloader: (UpdateInfo, File, (Long, Long) -> Unit, () -> Boolean) -> Unit = Updates::download
    var currentVersion: String = BuildConfig.VERSION_NAME
    var now: () -> Long = System::currentTimeMillis
    private var updateJob: Job? = null
    private var downloadJob: Job? = null

    /** Called when the app starts: the daily check, unless the user turned it off. */
    fun onAppStart() {
        checkForUpdates(manual = false)
    }

    /** The "Check for updates" switch in Settings (on unless the user turns it off). */
    fun setUpdateChecks(on: Boolean) {
        updateSettings { it.copy(updateChecks = if (on) UPDATES_ON else UPDATES_OFF) }
        if (on) checkForUpdates(manual = false)
    }

    /**
     * Asks GitHub for new releases. Automatic checks run at most once a day, stay silent on
     * errors and skip a version the user chose to skip; a manual check always runs and reports
     * what it found.
     */
    fun checkForUpdates(manual: Boolean) {
        val s = settings.value
        if (!manual && (s.updateChecks == UPDATES_OFF || now() - s.lastUpdateCheck < Updates.CHECK_INTERVAL_MS)) return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { updateSource(currentVersion) } }
            updateSettings { it.copy(lastUpdateCheck = now()) }
            val info = result.getOrNull()
            when {
                result.isFailure -> if (manual) emit(app.getString(R.string.update_check_failed))
                info == null -> if (manual) emit(app.getString(R.string.update_none, currentVersion))
                else -> {
                    if (info.version != _update.value?.version) _updateStep.value = UpdateStep.Idle
                    _update.value = info
                    if (manual || info.version != settings.value.skippedUpdate) _updatePrompt.value = true
                }
            }
        }
    }

    /** Shows the dialog for a known update again (Settings → Update). */
    fun showUpdate() {
        if (_update.value != null) _updatePrompt.value = true
    }

    /** Closes the update dialog; [skip] stops automatic checks from offering this version again. */
    fun dismissUpdate(skip: Boolean) {
        val info = _update.value ?: return
        if (skip) updateSettings { it.copy(skippedUpdate = info.version) }
        downloadJob?.cancel()
        if (_updateStep.value !is UpdateStep.Ready) _updateStep.value = UpdateStep.Idle
        _updatePrompt.value = false
    }

    /** Downloads the update, checks it against the release checksums and starts installing it. */
    fun startUpdate() {
        val info = _update.value ?: return
        (_updateStep.value as? UpdateStep.Ready)?.takeIf { it.version == info.version && it.file.exists() }?.let { r ->
            _events.tryEmit(UiEvent.Install(r.file))
            return
        }
        if (downloadJob?.isActive == true) return
        val target = File(File(app.cacheDir, "updates"), "Meshwise-${info.version}.apk")
        _updateStep.value = UpdateStep.Downloading(0, info.apkSize)
        downloadJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    updateDownloader(info, target, { read, total -> _updateStep.value = UpdateStep.Downloading(read, total) }) { job?.isActive == false }
                }
            }
            if (job?.isActive == false) return@launch
            result.onSuccess {
                _updateStep.value = UpdateStep.Ready(info.version, target)
                _events.emit(UiEvent.Install(target))
            }.onFailure { e ->
                Log.w(TAG, "update download failed", e)
                _updateStep.value = UpdateStep.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------ GL host

    fun attachGl(host: GlHost) {
        glHost = host
        host.requestRender()
    }

    /** Called on the GL thread, with a current context, before drawing each frame. */
    fun runGlTasks() {
        while (true) (glTasks.poll() ?: return).invoke()
    }

    fun detachGl(host: GlHost) {
        if (glHost === host) glHost = null
    }

    fun requestRender() {
        glHost?.requestRender()
    }

    /** Called on the GL thread after every frame. */
    fun onFrameRendered() {
        val f = engine.cameraFrame()
        if (f.version != _camera.value.version) _camera.value = f
        if (thumbnailPending) {
            thumbnailPending = false
            val id = currentRecentId
            if (id != null) {
                engine.thumbnail(320)?.let { bmp ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            app.thumbnails.save(id, bmp)
                        } catch (e: Exception) {
                            Log.w(TAG, "thumbnail save failed", e)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> onGl(block: () -> T): T {
        val d = CompletableDeferred<T>()
        val task = {
            try {
                d.complete(block())
            } catch (e: Throwable) {
                d.completeExceptionally(e)
            }
            Unit
        }
        glTasks.add(task)
        glHost?.requestRender()
        return d.await()
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(s: Screen) {
        _screen.value = s
        if (s != Screen.Viewer) _turntable.value = false.also { engine.setTurntable(false) }
    }

    /** Back handling: returns true when consumed. */
    fun back(): Boolean {
        when {
            _screen.value == Screen.About || _screen.value == Screen.Toolbar -> _screen.value = Screen.Settings
            _screen.value == Screen.Settings -> closeSettings()
            _screen.value == Screen.Library -> _screen.value = Screen.Home
            _screen.value == Screen.Viewer && _manualExplode.value -> setManualExplode(false)
            _screen.value == Screen.Viewer && _tool.value != Tool.None -> selectTool(Tool.None)
            _screen.value == Screen.Viewer -> navigate(Screen.Home)
            else -> return false
        }
        return true
    }

    private var _screenBeforeSettings = Screen.Home
    fun openSettings() {
        _screenBeforeSettings = _screen.value
        _screen.value = Screen.Settings
    }

    fun closeSettings() {
        _screen.value = if (_screenBeforeSettings == Screen.Viewer && _model.value != null) Screen.Viewer else Screen.Home
    }

    // ------------------------------------------------------------------ loading

    fun open(source: ModelSource, recentId: String? = null) {
        loadJob?.cancel()
        val opener = app.opener
        _loading.value = LoadingState("", LoadStage.Reading, 0f)
        loadJob = viewModelScope.launch {
            var loaded: LoadedModel? = null
            var name = "model"
            try {
                // Content-provider queries can be slow (cloud documents): keep them off Main.
                withContext(Dispatchers.IO) { runCatching { opener.meta(source) }.getOrNull() }?.let { name = it.name }
                _loading.value = LoadingState(name, LoadStage.Reading, 0f)
                // Assigned inside the block so a cancellation racing the end of loading
                // (withContext then drops its result) still frees the model below.
                withContext(Dispatchers.Default) {
                    loaded = opener.open(source) { stage, p -> _loading.value = LoadingState(name, stage, p.coerceIn(0f, 1f)) }
                }
                installModel(loaded!!, source, recentId)
            } catch (e: CancellationException) {
                // user cancelled
            } catch (e: CadCrashException) {
                emit(app.getString(R.string.cad_crashed))
            } catch (e: CadUnavailableException) {
                emit(app.getString(R.string.cad_unavailable))
            } catch (e: Throwable) {
                Log.w(TAG, "open failed", e)
                emit(app.getString(R.string.open_failed, name) + ": " + (e.message ?: e.javaClass.simpleName))
            } finally {
                loaded?.dispose()
                _loading.value = null
            }
        }
    }

    fun openUri(uri: Uri) {
        // Keep access across restarts when the provider allows it.
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        open(ModelSource.FromUri(uri))
    }

    fun openRecent(r: RecentFile) {
        val local = r.localCopy?.let { File(it) }?.takeIf { it.exists() }
        val src = if (local != null) ModelSource.FromFile(local) else ModelSource.parse(r.source)
        if (src == null) {
            app.recents.remove(r.id)
            return
        }
        open(src, r.id)
    }

    fun openSample(assetPath: String) = open(ModelSource.FromAsset(assetPath))

    fun cancelLoading() {
        loadJob?.cancel()
        _loading.value = null
    }

    private fun installModel(loaded: LoadedModel, source: ModelSource, recentId: String?) {
        analysisJob?.cancel()
        thicknessJob?.cancel()
        val info = loaded.info
        val project = loaded.project
        engine.setModel(loaded.take(), project?.camera == null)
        modelFormat = loaded.format
        currentSource = source
        _model.value = info
        _selected.value = -1
        _explode.value = 0f
        _manualExplode.value = false
        offsetUndo.clear()
        _measure.value = MeasureState(mode = _measure.value.mode)
        _measureHover.value = null
        _thickness.value = ThicknessUi()
        _overhang.value = OverhangUi()
        _meshCheck.value = null
        _analysisReady.value = false
        _showDimensions.value = false
        val prefs = settings.value
        val material = MaterialPreset.entries.getOrElse(prefs.defaultMaterial) { MaterialPreset.Matte }
        _parts.value = info.parts.map { p ->
            val color = if (prefs.modelColor != 0 && info.parts.size == 1 && !p.hasFileColor && !p.hasVertexColors) prefs.modelColor else p.fileColor
            PartState(color = color, useVertexColors = p.hasVertexColors, material = material.takeIf { it.color == null } ?: MaterialPreset.Matte)
        }
        if (project == null) _parts.value.forEachIndexed { i, st -> if (st.color != info.parts[i].fileColor || st.material != MaterialPreset.Matte) pushPart(i, st) }
        if (project?.camera == null && prefs.defaultView != StandardView.Iso.id) {
            StandardView.entries.firstOrNull { it.id == prefs.defaultView }?.let { engine.setStandardView(it, animate = false) }
            engine.fitView(animate = false)
        }
        _section.value = SectionState(value = info.bounds.center.z)
        _options.update {
            it.copy(mode = ShadingMode.Shaded, section = false, openEdges = false, edges = edgesFor(settings.value), wireframe = false)
        }
        if (project != null) applyProject(project, info)
        pushOptions()
        _sceneBounds.value = engine.sceneBounds()
        if (_tool.value == Tool.Explode || _tool.value == Tool.Measure) _tool.value = Tool.None
        _screen.value = Screen.Viewer

        // Recents + thumbnail.
        val id = recentId ?: sha1(source.key)
        currentRecentId = id
        val existing = app.recents.find(id)
        val entry = RecentFile(
            id = id,
            source = existing?.source ?: source.key,
            name = existing?.name ?: loaded.displayName,
            format = if (loaded.format == ModelFormat.Project) "PROJECT" else loaded.format.label,
            size = if (info.fileSize > 0) info.fileSize else (existing?.size ?: 0),
            openedAt = System.currentTimeMillis(),
            triangles = info.triangles,
            parts = info.parts.size,
            localCopy = existing?.localCopy,
        )
        app.recents.add(entry)
        if (source is ModelSource.FromUri && source.uri.scheme == "content" && existing?.localCopy == null && !hasPersistedPermission(source.uri)) {
            viewModelScope.launch(Dispatchers.IO) {
                val target = File(app.recents.copyDir(), id + "." + ModelFormat.extensionOf(loaded.displayName).ifEmpty { "bin" })
                if (app.opener.keepCopy(source.uri, target)) app.recents.add(entry.copy(localCopy = target.absolutePath))
            }
        }
        thumbnailPending = true
        requestRender()

        _print.value = PrintState(tab = _print.value.tab)
        startAnalysis()
    }

    /** Background analysis (BVH for picking, topology for edges, checks and measuring). */
    private fun startAnalysis() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            val job = coroutineContext[Job]
            val ok = try {
                engine.buildAnalysis(NativeProgress { job?.isActive != false })
            } catch (e: Throwable) {
                false
            }
            if (ok && job?.isActive != false) {
                _meshCheck.value = engine.meshCheck()
                _analysisReady.value = true
                requestRender()
            }
        }
    }

    private fun hasPersistedPermission(uri: Uri) =
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun applyProject(p: ProjectState, info: ModelInfo) {
        val parts = info.parts.mapIndexed { i, pi -> p.parts.getOrNull(i) ?: PartState(pi.fileColor, useVertexColors = pi.hasVertexColors) }
        _parts.value = parts
        parts.forEachIndexed { i, s -> pushPart(i, s) }
        _explode.value = p.explode
        engine.setExplode(p.explode)
        p.camera?.let { engine.setCamera(it, false) }
        _section.value = SectionState(axis = p.sectionAxis, value = p.sectionValue, flip = p.sectionFlip)
        _options.update {
            it.copy(background = p.background, lighting = p.lighting, exposure = p.exposure, edges = p.edges, wireframe = p.wireframe,
                section = p.section, sectionAxis = p.sectionAxis, sectionValue = p.sectionValue, sectionFlip = p.sectionFlip,
                bgTop = p.bgTop, bgBottom = p.bgBottom)
        }
    }

    fun closeModel() {
        analysisJob?.cancel()
        thicknessJob?.cancel()
        engine.clearModel()
        _model.value = null
        _parts.value = emptyList()
        currentSource = null
        currentRecentId = null
        _tool.value = Tool.None
        _screen.value = Screen.Home
        requestRender()
    }

    // ------------------------------------------------------------------ settings → options

    private fun edgesFor(s: SettingsValues): Boolean = when (s.edgesMode) {
        1 -> true
        2 -> false
        else -> modelFormat?.kind == FormatKind.Cad
    }

    private fun applySettings(s: SettingsValues) {
        _options.update {
            it.copy(
                background = s.background, lighting = s.lighting, exposure = s.exposure, grid = s.grid, shadows = s.shadows,
                axes = s.axes, edges = edgesFor(s), lineScale = density / 2f, bgTop = s.bgTop, bgBottom = s.bgBottom,
                selectColor = s.selectColor,
            )
        }
        engine.setOrtho(s.orthographic)
        engine.setSensitivity(s.sensitivity)
        engine.setFov(s.fov)
        io.github.doesthings.meshwise.util.Fmt.decimals = s.decimals.coerceIn(0, 4)
        engine.setTurntableSpeed(TURNTABLE_RAD_PER_SEC * s.turntableSpeed)
        pushOptions()
    }

    fun updateSettings(transform: (SettingsValues) -> SettingsValues) = app.settings.update(transform)

    fun resetSettings() = updateSettings { it.resetKeepingState() }

    /** Places the toolbar buttons: [dock] at the bottom, [side] in the top cluster, the rest hidden. */
    fun setToolbar(dock: List<ToolbarItem>, side: List<ToolbarItem>) = updateSettings {
        it.copy(dock = ToolbarItem.encode(dock), side = ToolbarItem.encode(side.filter { t -> t !in dock }))
    }

    /** Runs a toolbar button. */
    fun onToolbarItem(item: ToolbarItem) {
        when (item) {
            ToolbarItem.Files -> navigate(Screen.Home)
            ToolbarItem.Save -> selectTool(Tool.Save)
            ToolbarItem.Color -> selectTool(Tool.Color)
            ToolbarItem.Print -> selectTool(Tool.Print)
            ToolbarItem.Measure -> selectTool(Tool.Measure)
            ToolbarItem.Analyze -> selectTool(Tool.Analyze)
            ToolbarItem.View -> selectTool(Tool.Lighting)
            ToolbarItem.Material -> selectTool(Tool.Material)
            ToolbarItem.Section -> selectTool(Tool.Section)
            ToolbarItem.Explode -> selectTool(Tool.Explode)
            ToolbarItem.Parts -> selectTool(Tool.Parts)
            ToolbarItem.Info -> selectTool(Tool.Info)
            ToolbarItem.Snapshot -> shareSnapshot()
            ToolbarItem.Turntable -> toggleTurntable()
        }
    }

    /** Opens the most recent file at launch when the user asked for that. */
    fun reopenLastIfWanted() {
        if (!settings.value.reopenLast || _model.value != null || _loading.value != null) return
        recents.value.firstOrNull()?.let { openRecent(it) }
    }

    fun updateOptions(transform: (RenderOptions) -> RenderOptions) {
        _options.update(transform)
        pushOptions()
    }

    private fun pushOptions() {
        val sec = _section.value
        val o = _options.value.copy(sectionAxis = sec.axis, sectionValue = sec.value, sectionFlip = sec.flip, hatch = sec.hatch,
            sectionPlane = sec.showPlane, lineScale = density / 2f)
        _options.value = o
        engine.setOptions(o)
        requestRender()
    }

    // ------------------------------------------------------------------ tools

    fun selectTool(t: Tool) {
        val prev = _tool.value
        val next = if (prev == t) Tool.None else t
        _tool.value = next
        if (prev == Tool.Section && next != Tool.Section) updateOptions { it.copy(section = false) }
        if (next == Tool.Section) enableSection()
        if (prev == Tool.Analyze && next != Tool.Analyze) {
            updateOptions { it.copy(mode = ShadingMode.Shaded, openEdges = false) }
        }
        if (next == Tool.Analyze) applyAnalyzeMode()
        if (prev == Tool.Explode && next != Tool.Explode) setManualExplode(false)
        if (prev == Tool.Print && next != Tool.Print && _print.value.showOverhangs) updateOptions { it.copy(mode = ShadingMode.Shaded) }
        if (next == Tool.Print) {
            if (_print.value.showOverhangs) updateOptions { it.copy(mode = ShadingMode.Overhang, overhangAngle = _overhang.value.angle) }
            refreshPrintStats()
        }
        if (next != Tool.Measure && prev == Tool.Measure) {
            _measure.update { it.copy(points = emptyList(), features = emptyList()) }
            _measureHover.value = null
        }
    }

    // ---- camera
    fun fitView() {
        engine.fitView(true)
        requestRender()
    }

    fun setStandardView(v: StandardView) {
        engine.setStandardView(v, true)
        requestRender()
    }

    fun setOrientation(yaw: Float, pitch: Float) {
        engine.setOrientation(yaw, pitch, true)
        requestRender()
    }

    fun toggleTurntable() {
        val on = !_turntable.value
        _turntable.value = on
        engine.setTurntable(on)
        requestRender()
    }

    fun toggleOrtho() = updateSettings { it.copy(orthographic = !it.orthographic) }

    private val _viewInsets = MutableStateFlow(0f to 0f)
    /** Pixels at the top/bottom of the 3D view covered by the UI. */
    val viewInsets = _viewInsets.asStateFlow()

    fun setViewInsets(top: Float, bottom: Float) {
        val cur = _viewInsets.value
        if (kotlin.math.abs(cur.first - top) < 1f && kotlin.math.abs(cur.second - bottom) < 1f) return
        _viewInsets.value = top to bottom
        engine.setViewInsets(top, bottom, animate = true)
        requestRender()
    }

    fun toggleDimensions() {
        _showDimensions.value = !_showDimensions.value
        _sceneBounds.value = engine.sceneBounds()
    }

    // ---- gestures from the surface (UI thread)
    val instantTaps: Boolean get() = _tool.value == Tool.Measure || _tool.value == Tool.Color || layFlatTaps
    private val layFlatTaps: Boolean get() = _tool.value == Tool.Print && _print.value.tab == PrintTab.Orient

    fun onTap(x: Float, y: Float) {
        if (layFlatTaps) {
            // Lay flat: the tapped face (or the surface under the finger) goes down on the bed.
            val f = engine.measurePick(x, y, snapPx) ?: return
            layFlat(if (f.kind == FeatureKind.Plane) f.axis else f.normal)
            return
        }
        if (_tool.value == Tool.Measure) {
            measureAt(x, y)
            requestRender()
            return
        }
        val hit = engine.pick(x, y)
        when (_tool.value) {
            Tool.Color -> if (hit != null) paintPart(hit.part)
            Tool.Explode -> if (_manualExplode.value) selectPart(hit?.part ?: -1)
            else -> selectPart(if (hit != null && hit.part != _selected.value) hit.part else -1)
        }
        requestRender()
    }

    fun onDoubleTap(x: Float, y: Float) {
        when (settings.value.doubleTap) {
            DOUBLE_TAP_FIT -> engine.fitView(true)
            DOUBLE_TAP_NONE -> return
            else -> {
                val hit = engine.pick(x, y)
                if (hit != null) engine.focusPoint(hit.point, 1.8f) else engine.fitView(true)
            }
        }
        requestRender()
    }

    /** Orbit from a one-finger drag, honouring the "invert" settings. */
    fun orbitDrag(dx: Float, dy: Float) {
        val s = settings.value
        engine.orbit(if (s.invertX) -dx else dx, if (s.invertY) -dy else dy)
    }

    fun onLongPress(x: Float, y: Float) {
        val hit = engine.pick(x, y) ?: return
        selectPart(hit.part)
    }

    // ---- parts
    private fun pushPart(i: Int, s: PartState) {
        if (s.useVertexColors) engine.setPartVertexColors(i, true) else engine.setPartColor(i, s.color)
        engine.setPartVisible(i, s.visible)
        engine.setPartMaterial(i, s.material)
        engine.setPartOffset(i, s.manualOffset)
    }

    fun selectPart(i: Int) {
        val prev = _selected.value
        if (prev >= 0) engine.setPartSelected(prev, false)
        _selected.value = i
        if (i >= 0) engine.setPartSelected(i, true)
        requestRender()
    }

    fun setPartVisible(i: Int, visible: Boolean) {
        _parts.update { list -> list.mapIndexed { k, s -> if (k == i) s.copy(visible = visible) else s } }
        engine.setPartVisible(i, visible)
        _sceneBounds.value = engine.sceneBounds()
        requestRender()
    }

    fun showAllParts() {
        _parts.update { list -> list.map { it.copy(visible = true) } }
        engine.setPartVisible(-1, true)
        engine.setPartGhost(-1, false)
        requestRender()
    }

    fun isolatePart(i: Int) {
        _parts.update { list -> list.mapIndexed { k, s -> s.copy(visible = k == i) } }
        _parts.value.forEachIndexed { k, s -> engine.setPartVisible(k, s.visible) }
        engine.focusPart(i, true)
        requestRender()
    }

    fun ghostOthers(i: Int, on: Boolean) {
        val n = _parts.value.size
        for (k in 0 until n) engine.setPartGhost(k, on && k != i)
        requestRender()
    }

    fun focusPart(i: Int) {
        engine.focusPart(i, true)
        requestRender()
    }

    fun setPaintColor(c: Int) {
        _paintColor.value = c
    }

    fun paintPart(i: Int, color: Int = _paintColor.value) {
        val single = _parts.value.size <= 1
        if (single) {
            applyColorAll(color)
            return
        }
        _parts.update { list -> list.mapIndexed { k, s -> if (k == i) s.copy(color = color, useVertexColors = false) else s } }
        engine.setPartColor(i, color)
        requestRender()
    }

    fun applyColorAll(color: Int = _paintColor.value) {
        _parts.update { list -> list.map { it.copy(color = color, useVertexColors = false) } }
        engine.setPartColor(-1, color)
        requestRender()
    }

    fun restoreOriginalColors() {
        val info = _model.value ?: return
        _parts.update { list -> list.mapIndexed { k, s -> s.copy(color = info.parts[k].fileColor, useVertexColors = info.parts[k].hasVertexColors) } }
        _parts.value.forEachIndexed { k, s -> if (s.useVertexColors) engine.setPartVertexColors(k, true) else engine.setPartColor(k, s.color) }
        requestRender()
    }

    fun colorByPart() {
        _parts.update { list -> list.mapIndexed { k, s -> s.copy(color = Palette.partColor(k), useVertexColors = false) } }
        _parts.value.forEachIndexed { k, s -> engine.setPartColor(k, s.color) }
        requestRender()
    }

    fun applyMaterial(m: MaterialPreset) {
        val sel = _selected.value
        val targets = if (sel >= 0) listOf(sel) else _parts.value.indices.toList()
        _parts.update { list ->
            list.mapIndexed { k, s ->
                if (k in targets) s.copy(material = m, color = m.color ?: s.color, useVertexColors = if (m.color != null) false else s.useVertexColors) else s
            }
        }
        targets.forEach { k ->
            val s = _parts.value[k]
            engine.setPartMaterial(k, m)
            if (m.color != null) engine.setPartColor(k, s.color)
        }
        requestRender()
    }

    // ---- explode
    fun setExplode(f: Float) {
        _explode.value = f
        engine.setExplode(f)
        _sceneBounds.value = engine.sceneBounds()
        requestRender()
    }

    fun setManualExplode(on: Boolean) {
        _manualExplode.value = on
        if (!on) selectPart(-1)
    }

    fun beginPartDrag(part: Int) {
        offsetUndo.addLast(OffsetEdit(part, _parts.value.getOrNull(part)?.manualOffset ?: Vec3.ZERO))
        while (offsetUndo.size > 100) offsetUndo.removeFirst()
    }

    fun movePart(part: Int, delta: Vec3) {
        val s = _parts.value.getOrNull(part) ?: return
        val off = s.manualOffset + delta
        _parts.update { list -> list.mapIndexed { k, p -> if (k == part) p.copy(manualOffset = off) else p } }
        engine.setPartOffset(part, off)
        requestRender()
    }

    fun undoMove() {
        val e = offsetUndo.removeLastOrNull() ?: return
        _parts.update { list -> list.mapIndexed { k, p -> if (k == e.part) p.copy(manualOffset = e.before) else p } }
        engine.setPartOffset(e.part, e.before)
        requestRender()
    }

    fun resetExplode() {
        offsetUndo.clear()
        _parts.update { list -> list.map { it.copy(manualOffset = Vec3.ZERO) } }
        engine.setPartOffset(-1, Vec3.ZERO)
        setExplode(0f)
    }

    fun partOffset(part: Int): Vec3 = engine.partOffset(part).first

    // ---- section
    private fun enableSection() {
        val b = engine.sceneBounds()
        val s = _section.value
        val axis = s.axis
        val value = if (s.value < b.min[axis] || s.value > b.max[axis]) b.center[axis] else s.value
        _section.value = s.copy(value = value, flip = facingFlip(axis))
        updateOptions { it.copy(section = true) }
    }

    /** Keeps the half whose cut face looks at the camera. */
    private fun facingFlip(axis: Int): Boolean = _camera.value.forward[axis] > 0f

    fun setSectionAxis(axis: Int) {
        val b = engine.sceneBounds()
        _section.update { it.copy(axis = axis, value = b.center[axis], flip = facingFlip(axis)) }
        pushOptions()
    }

    fun setSectionValue(v: Float) {
        _section.update { it.copy(value = v) }
        pushOptions()
    }

    fun flipSection() {
        _section.update { it.copy(flip = !it.flip) }
        pushOptions()
    }

    fun setSectionHatch(on: Boolean) {
        _section.update { it.copy(hatch = on) }
        pushOptions()
    }

    fun setSectionPlane(on: Boolean) {
        _section.update { it.copy(showPlane = on) }
        pushOptions()
    }

    /** Looks straight at the cut face. */
    fun viewSection() {
        val s = _section.value
        val view = when (s.axis) {
            0 -> if (s.flip) StandardView.Left else StandardView.Right
            1 -> if (s.flip) StandardView.Front else StandardView.Back
            else -> if (s.flip) StandardView.Bottom else StandardView.Top
        }
        setStandardView(view)
    }

    // ---- measure
    fun setMeasureMode(m: MeasureMode) {
        _measure.update { it.copy(mode = m, points = emptyList(), features = emptyList()) }
    }

    /** Edges and corners closer than this to a tap (in pixels) win over the surface. */
    private val snapPx: Float get() = 12f * density

    /** A measuring tap: picks a feature (Smart) or a snapped point (other modes). */
    fun measureAt(x: Float, y: Float) {
        val f = engine.measurePick(x, y, snapPx) ?: return
        if (_measure.value.mode == MeasureMode.Smart) addFeature(f) else addMeasurePoint(f.toPickHit())
    }

    private fun addFeature(f: MeasureFeature) {
        _measure.update { st ->
            val same = st.features.indexOfFirst { it.sameAs(f) }
            val next = when {
                same >= 0 -> st.features.filterIndexed { i, _ -> i != same }
                st.features.size >= 2 -> listOf(f)
                else -> st.features + f
            }
            st.copy(features = next)
        }
    }

    /** Precision cursor: shows what a tap at (x, y) would pick, without picking it. */
    fun measureHover(x: Float, y: Float) {
        // Finding a feature can take a few milliseconds on huge models: refresh it at most
        // ~20 times a second while the cursor itself follows every move.
        val now = System.nanoTime()
        val prev = _measureHover.value
        val feature = if (prev == null || now - lastHoverPick > 50_000_000L) {
            lastHoverPick = now
            engine.measurePick(x, y, snapPx)
        } else prev.feature
        _measureHover.value = MeasureHover(x, y, feature)
        requestRender()
    }

    private var lastHoverPick = 0L

    fun commitHover() {
        val h = _measureHover.value ?: return
        _measureHover.value = null
        val f = engine.measurePick(h.x, h.y, snapPx) ?: return
        if (_measure.value.mode == MeasureMode.Smart) addFeature(f) else addMeasurePoint(f.toPickHit())
    }

    fun cancelHover() {
        _measureHover.value = null
    }

    val measuring: Boolean get() = _tool.value == Tool.Measure

    /** Keeps the current Smart result on screen and starts a new measurement. */
    fun keepMeasurement() {
        _measure.update { st ->
            val r = st.smartResult ?: return@update st
            st.copy(kept = (st.kept + KeptMeasure(r, st.features)).takeLast(20), features = emptyList())
        }
    }

    fun removeKept(i: Int) {
        _measure.update { st -> st.copy(kept = st.kept.filterIndexed { k, _ -> k != i }) }
    }

    private fun addMeasurePoint(hit: PickHit?) {
        if (hit == null) return
        _measure.update { st ->
            if (st.complete) st.copy(points = listOf(hit), history = (st.history + (st.mode to st.points)).takeLast(10))
            else st.copy(points = st.points + hit)
        }
    }

    fun undoMeasurePoint() {
        _measure.update { if (it.mode == MeasureMode.Smart) it.copy(features = it.features.dropLast(1)) else it.copy(points = it.points.dropLast(1)) }
    }

    fun clearMeasure() {
        _measure.update { it.copy(points = emptyList(), features = emptyList(), kept = emptyList(), history = emptyList()) }
    }

    // ---- analysis
    fun setAnalyzeTab(t: AnalyzeTab) {
        _analyzeTab.value = t
        applyAnalyzeMode()
        if (t == AnalyzeTab.Overhang) computeOverhang()
    }

    private fun applyAnalyzeMode() {
        val th = _thickness.value
        val mode = when (_analyzeTab.value) {
            AnalyzeTab.Thickness -> if (th.result == null) ShadingMode.Shaded else if (th.threshold) ShadingMode.ThicknessThreshold else ShadingMode.ThicknessGradient
            AnalyzeTab.Overhang -> ShadingMode.Overhang
            AnalyzeTab.MeshCheck -> ShadingMode.Shaded
        }
        val res = th.result
        updateOptions {
            it.copy(
                mode = mode,
                openEdges = _analyzeTab.value == AnalyzeTab.MeshCheck && showProblems,
                scalarMin = res?.p05 ?: 0f, scalarMax = res?.p95 ?: 1f, threshold = th.thresholdValue,
                overhangAngle = _overhang.value.angle,
            )
        }
    }

    var showProblems = true
        private set

    fun setShowProblems(on: Boolean) {
        showProblems = on
        applyAnalyzeMode()
    }

    fun computeThickness() {
        if (_thickness.value.computing) return
        _thickness.update { it.copy(computing = true, progress = 0f) }
        thicknessJob = viewModelScope.launch(Dispatchers.Default) {
            val job = coroutineContext[Job]
            try {
                val r = engine.computeThickness(NativeProgress { f ->
                    _thickness.update { it.copy(progress = f) }
                    job?.isActive != false
                })
                if (r != null) {
                    // Publish the result and switch the shading together, so nobody sees the
                    // result with the old shading mode.
                    withContext(Dispatchers.Main) {
                        _thickness.update {
                            it.copy(computing = false, result = r, thresholdValue = if (it.result == null) defaultThreshold(r.min) else it.thresholdValue)
                        }
                        applyAnalyzeMode()
                    }
                } else {
                    _thickness.update { it.copy(computing = false) }
                }
            } catch (e: Throwable) {
                _thickness.update { it.copy(computing = false) }
                if (e !is CancellationException) emit(e.message ?: "Analysis failed")
            }
        }
    }

    private fun defaultThreshold(min: Float): Float = maxOf(0.4f, minOf(1.2f, min * 1.5f))

    fun setThicknessThresholdMode(on: Boolean) {
        _thickness.update { it.copy(threshold = on) }
        applyAnalyzeMode()
    }

    fun setThicknessThreshold(v: Float) {
        _thickness.update { it.copy(thresholdValue = v) }
        applyAnalyzeMode()
    }

    fun setOverhangAngle(a: Float) {
        _overhang.update { it.copy(angle = a) }
        applyAnalyzeMode()
        computeOverhang()
    }

    private var overhangJob: Job? = null

    private fun computeOverhang() {
        val a = _overhang.value.angle
        overhangJob?.cancel()
        overhangJob = viewModelScope.launch(Dispatchers.Default) {
            delay(40) // coalesce slider ticks
            val r = engine.overhang(a) ?: return@launch
            ensureActive() // a newer angle superseded this one
            _overhang.update { it.copy(overhangArea = r.first, totalArea = r.second) }
        }
    }

    // ------------------------------------------------------------------ print prep

    fun setPrintTab(t: PrintTab) {
        _print.update { it.copy(tab = t) }
        if (t == PrintTab.Orient) refreshPrintStats()
    }

    /** Turns the model so that the surface with outward normal [normal] lies on the bed. */
    fun layFlat(normal: Vec3) = transformBy(Affine.pointDown(normal))

    fun rotateModel(axis: Int, degrees: Float) = transformBy(Affine.rotation(axis, degrees))

    fun mirrorModel(axis: Int) = transformBy(Affine.mirror(axis))

    fun scaleModel(factor: Float) {
        if (factor > 0f && kotlin.math.abs(factor - 1f) > 1e-6f) transformBy(Affine.scale(factor))
    }

    /** Scales uniformly so the model is [size] mm along [axis]. */
    fun scaleModelTo(axis: Int, size: Float) {
        val current = _model.value?.bounds?.size?.get(axis) ?: return
        if (current > 0f && size > 0f) scaleModel(size / current)
    }

    fun resetTransform() {
        if (_print.value.transform.isIdentity) return
        applyTransform(Affine.IDENTITY, record = true, placeOnBed = false)
    }

    fun undoTransform() {
        val st = _print.value
        val prev = st.undo.lastOrNull() ?: return
        applyTransform(prev, record = false, placeOnBed = prev.isIdentity.not(), popUndo = true)
    }

    fun setShowOverhangs(on: Boolean) {
        _print.update { it.copy(showOverhangs = on) }
        updateOptions { it.copy(mode = if (on) ShadingMode.Overhang else ShadingMode.Shaded, overhangAngle = _overhang.value.angle) }
    }

    /** Ranks a few orientations for printing (fewest overhangs, a solid footprint, low height). */
    fun suggestOrientations() {
        if (_print.value.suggesting || _model.value == null) return
        _print.update { it.copy(suggesting = true, suggestions = emptyList()) }
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                runCatching { engine.suggestOrientations(_overhang.value.angle, 3, null) }.getOrDefault(emptyList())
            }
            _print.update { it.copy(suggesting = false, suggestions = list) }
        }
    }

    fun applySuggestion(s: io.github.doesthings.meshwise.engine.OrientationStats) = layFlat(s.down)

    // ------------------------------------------------------------------ print estimate

    private val _estimate = MutableStateFlow(EstimateUi())
    val estimate = _estimate.asStateFlow()
    private var estimateJob: Job? = null
    private var estimateFor: Triple<ModelInfo, EstimateSettings, List<Boolean>?>? = null  // what the shown/running estimate is for

    /**
     * Works out how long the shown model takes to print and how much filament it needs, in the
     * background; a newer request replaces a running one. [scope]: 0 all parts, 1 the visible
     * ones, 2 the selected one. Nothing to work out for materials that aren't printed from
     * filament.
     */
    fun requestEstimate(scope: Int) {
        val info = _model.value ?: return
        val s = settings.value
        val filament = Filament.of(DensityPreset.entries.getOrElse(s.density) { DensityPreset.PLA })
        if (filament == null) {
            estimateJob?.cancel()
            estimateFor = null
            _estimate.value = EstimateUi()
            return
        }
        val es = Printers.estimateSettings(s, filament)
        val parts = when (scope) {
            1 -> BooleanArray(info.parts.size) { _parts.value.getOrNull(it)?.visible != false }
            2 -> BooleanArray(info.parts.size) { it == _selected.value }
            else -> null
        }
        val key = Triple(info, es, parts?.toList())
        val current = estimateFor
        if (current != null && current.first === info && current.second == es && current.third == key.third &&
            (_estimate.value.running || _estimate.value.result != null)
        ) return  // already shown or on its way
        estimateFor = key
        _estimate.update { it.copy(running = true, progress = 0f, failed = false) }
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch(Dispatchers.Default) {
            delay(250)  // settle while sliders move
            val job = coroutineContext[Job]
            try {
                val r = engine.estimatePrint(es, parts, NativeProgress { f ->
                    _estimate.update { it.copy(progress = f) }
                    job?.isActive != false
                })
                ensureActive()
                _estimate.value = EstimateUi(result = r, density = filament.density, failed = r == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("Meshwise", "estimate failed", e)
                _estimate.update { it.copy(running = false, failed = true) }
            }
        }
    }

    private fun transformBy(a: Affine) = applyTransform(a * _print.value.transform, record = true, placeOnBed = true)

    private var printStatsJob: Job? = null

    private fun refreshPrintStats() {
        printStatsJob?.cancel()
        printStatsJob = viewModelScope.launch {
            val s = withContext(Dispatchers.Default) { engine.orientationStats(Vec3(0f, 0f, -1f), _overhang.value.angle) }
            _print.update { it.copy(stats = s) }
        }
    }

    /**
     * Shows the model as loaded transformed by [target]. The geometry is rebuilt from the
     * original (so repeated turns never drift), then measuring and analysis start over.
     */
    private fun applyTransform(target: Affine, record: Boolean, placeOnBed: Boolean, popUndo: Boolean = false) {
        val before = _print.value
        if (before.busy || _model.value == null) return
        _print.update { it.copy(busy = true) }
        val modelBefore = _model.value
        viewModelScope.launch {
            val built = withContext(Dispatchers.Default) {
                engine.transformedModel(target, placeOnBed)?.let { (handle, off) ->
                    Triple(handle, off, ModelInfo.fromHandle(handle, modelBefore?.name ?: "model", modelBefore?.loadMillis ?: 0))
                }
            }
            if (built == null || _model.value !== modelBefore) {
                built?.let { NativeLib.nativeFreeModel(it.first) }
                _print.update { it.copy(busy = false) }
                return@launch
            }
            val (handle, off, info) = built
            analysisJob?.cancel()
            thicknessJob?.cancel()
            selectPart(-1)
            engine.setTransformedModel(handle)
            _model.value = info.copy(warnings = modelBefore?.warnings ?: info.warnings)
            // The engine starts with fresh part states: put colours, materials and visibility back.
            _explode.value = 0f
            _manualExplode.value = false
            offsetUndo.clear()
            _parts.update { list -> list.map { it.copy(manualOffset = Vec3.ZERO) } }
            _parts.value.forEachIndexed { i, s -> pushPart(i, s) }
            _measure.update { it.copy(points = emptyList(), features = emptyList(), kept = emptyList()) }
            _thickness.value = ThicknessUi()
            _meshCheck.value = null
            _analysisReady.value = false
            _section.update { it.copy(value = info.bounds.center[it.axis]) }
            pushOptions()
            _sceneBounds.value = engine.sceneBounds()
            engine.fitView(true)
            startAnalysis()
            if (_analyzeTab.value == AnalyzeTab.Overhang) computeOverhang()
            val final = Affine.translation(off) * target
            _print.update {
                it.copy(
                    busy = false,
                    transform = final,
                    undo = when {
                        popUndo -> it.undo.dropLast(1)
                        record -> (it.undo + it.transform).takeLast(20)
                        else -> it.undo
                    },
                    suggestions = emptyList(),
                )
            }
            refreshPrintStats()
            requestRender()
        }
    }

    // ------------------------------------------------------------------ export

    fun visibleMask(): BooleanArray = _parts.value.map { it.visible }.toBooleanArray()

    fun export(uri: Uri, format: ExportFormat, visibleOnly: Boolean, applyOffsets: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                app.contentResolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                    engine.export(pfd.fd, format, if (visibleOnly) visibleMask() else null, applyOffsets)
                } ?: false
            } catch (e: Throwable) {
                Log.w(TAG, "export failed", e)
                false
            }
            emit(app.getString(if (ok) R.string.saved else R.string.export_failed))
        }
    }

    fun suggestedName(ext: String): String {
        val n = _model.value?.name ?: "model"
        return baseName(n) + "." + ext
    }

    private fun buildProjectState(): ProjectState {
        val o = _options.value
        val s = _section.value
        return ProjectState(
            name = baseName(_model.value?.name ?: "Project"),
            parts = _parts.value,
            explode = _explode.value,
            camera = engine.camera(),
            background = o.background,
            lighting = o.lighting,
            exposure = o.exposure,
            edges = o.edges,
            wireframe = o.wireframe,
            section = o.section,
            sectionAxis = s.axis,
            sectionValue = s.value,
            sectionFlip = s.flip,
            bgTop = o.bgTop,
            bgBottom = o.bgBottom,
        )
    }

    /** Writes a .meshwise project (zip with the mesh, viewer state and a thumbnail). */
    private suspend fun writeProject(out: java.io.OutputStream) {
        val state = buildProjectState()
        val meshFile = File(app.cacheDir, "project-export.fsm")
        val ok = withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.open(
                meshFile,
                android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { engine.export(it.fd, ExportFormat.FSM, null, false) }
        }
        if (!ok) throw java.io.IOException("Could not serialise the model")
        val thumb: Bitmap? = runCatching { onGl { engine.thumbnail(320) } }.getOrNull()
        withContext(Dispatchers.IO) {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(state.toJson().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("model.fsm"))
                meshFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (thumb != null) {
                    zip.putNextEntry(ZipEntry("thumbnail.png"))
                    thumb.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                }
            }
            meshFile.delete()
        }
    }

    fun saveProject(uri: Uri) {
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt") }?.use { writeProject(it) } != null
            } catch (e: Throwable) {
                Log.w(TAG, "project save failed", e)
                false
            }
            emit(app.getString(if (ok) R.string.saved else R.string.export_failed))
        }
    }

    fun saveToLibrary() {
        viewModelScope.launch {
            val name = baseName(_model.value?.name ?: "model") + ".meshwise"
            val target = app.library.newFile(name)
            val ok = try {
                withContext(Dispatchers.IO) { FileOutputStream(target) }.use { writeProject(it) }
                true
            } catch (e: Throwable) {
                target.delete()
                false
            }
            app.library.refresh()
            emit(app.getString(if (ok) R.string.saved else R.string.export_failed))
        }
    }

    suspend fun snapshot(transparent: Boolean = false, scale: Float = 1.5f): Bitmap? {
        val f = _camera.value
        // Uniform scale (keeps the aspect ratio), capped at 4096 px per side and ~8 MP so the
        // multisampled target fits in GPU memory on modest devices.
        val fw = f.width.coerceAtLeast(1f)
        val fh = f.height.coerceAtLeast(1f)
        val s = minOf(scale, 4096f / maxOf(fw, fh), kotlin.math.sqrt(8_000_000f / (fw * fh)))
        val w = (fw * s).toInt().coerceAtLeast(64)
        val h = (fh * s).toInt().coerceAtLeast(64)
        return onGl { engine.snapshot(w, h, transparent) }
    }

    fun savePng(uri: Uri, transparent: Boolean = false) {
        viewModelScope.launch {
            val bmp = snapshot(transparent)
            val ok = bmp != null && withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false }
                    .getOrDefault(false)
            }
            bmp?.recycle()
            emit(app.getString(if (ok) R.string.saved else R.string.export_failed))
        }
    }

    fun shareSnapshot() {
        viewModelScope.launch {
            val bmp = snapshot()
            val uri = bmp?.let {
                withContext(Dispatchers.IO) {
                    try {
                        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                        val f = File(dir, baseName(_model.value?.name ?: "model") + ".png")
                        f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        FileProvider.getUriForFile(app, app.packageName + ".files", f)
                    } catch (e: Exception) {
                        Log.w(TAG, "share snapshot failed", e)
                        null
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            if (uri != null) _events.emit(UiEvent.Share(uri, "image/png")) else emit(app.getString(R.string.export_failed))
        }
    }

    fun shareModel(format: ExportFormat) {
        viewModelScope.launch {
            val mask = visibleMask()
            val uri = withContext(Dispatchers.IO) {
                try {
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    val f = File(dir, baseName(_model.value?.name ?: "model") + "." + format.extension)
                    val ok = android.os.ParcelFileDescriptor.open(
                        f, android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                    ).use { engine.export(it.fd, format, mask, false) }
                    if (ok) FileProvider.getUriForFile(app, app.packageName + ".files", f) else null
                } catch (e: Exception) {
                    Log.w(TAG, "share model failed", e)
                    null
                }
            }
            if (uri != null) _events.emit(UiEvent.Share(uri, format.mime)) else emit(app.getString(R.string.export_failed))
        }
    }

    // ------------------------------------------------------------------ library / recents

    fun removeRecent(id: String) = app.recents.remove(id)
    fun clearRecents() = app.recents.clear()
    fun thumbnailFile(id: String): File = app.thumbnails.file(id)
    fun refreshLibrary() = app.library.refresh()
    fun deleteLibraryItem(item: io.github.doesthings.meshwise.data.LibraryItem) = app.library.delete(item)
    fun renameLibraryItem(item: io.github.doesthings.meshwise.data.LibraryItem, name: String) = app.library.rename(item, name)
    fun openLibraryItem(item: io.github.doesthings.meshwise.data.LibraryItem) = open(ModelSource.FromFile(item.file))

    fun importToLibrary(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val meta = app.opener.meta(ModelSource.FromUri(uri))
                app.opener.keepCopy(uri, app.library.newFile(meta.name))
            } catch (e: Exception) {
                Log.w(TAG, "library import failed", e)
                false
            }
            app.library.refresh()
            emit(app.getString(if (ok) R.string.saved else R.string.export_failed))
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            listOf("cad", "cad_in", "unzip", "project", "samples", "shared").forEach { File(app.cacheDir, it).deleteRecursively() }
            emit(app.getString(R.string.cache_cleared))
        }
    }

    private fun emit(msg: String) {
        _events.tryEmit(UiEvent.Message(msg))
    }

    override fun onCleared() {
        loadJob?.cancel()
        // Analysis runs native code on the engine from background threads: let it stop (it
        // checks for cancellation regularly) before the engine is freed.
        val running = listOfNotNull(analysisJob, thicknessJob, overhangJob).onEach { it.cancel() }
        val e = engine
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            running.joinAll()
            val host = glHost
            if (host != null) host.queueOnGl { e.release() } else e.release()
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "Meshwise"
        const val TURNTABLE_RAD_PER_SEC = 0.6f
        const val DOUBLE_TAP_FIT = 1
        const val DOUBLE_TAP_NONE = 2
        fun isNearZero(v: Float) = abs(v) < 1e-6f
    }
}
