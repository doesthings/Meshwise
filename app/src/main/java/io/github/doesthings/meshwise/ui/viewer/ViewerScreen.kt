package io.github.doesthings.meshwise.ui.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.ui.components.OverlayButton
import io.github.doesthings.meshwise.ui.panels.AnalyzePanel
import io.github.doesthings.meshwise.ui.panels.ColorPanel
import io.github.doesthings.meshwise.ui.panels.ExplodePanel
import io.github.doesthings.meshwise.ui.panels.InfoPanel
import io.github.doesthings.meshwise.ui.panels.LightingPanel
import io.github.doesthings.meshwise.ui.panels.MaterialPanel
import io.github.doesthings.meshwise.ui.panels.MeasurePanel
import io.github.doesthings.meshwise.ui.panels.PartsPanel
import io.github.doesthings.meshwise.ui.panels.SavePanel
import io.github.doesthings.meshwise.ui.panels.SectionPanel
import io.github.doesthings.meshwise.ui.panels.WeightPanel
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.Tool
import io.github.doesthings.meshwise.viewer.ViewerSurfaceView
import io.github.doesthings.meshwise.viewer.ViewerViewModel

/** Screenshot tests provide a pre-rendered frame instead of the live GL view. */
val LocalViewerBackdrop = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.graphics.ImageBitmap?> { null }

@Composable
fun ViewerScreen(vm: ViewerViewModel, active: Boolean) {
    var glView by remember { mutableStateOf<ViewerSurfaceView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, glView) {
        val v = glView
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize()) {
        val backdrop = LocalViewerBackdrop.current
        if (backdrop != null) {
            androidx.compose.foundation.Image(backdrop, null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        } else {
            AndroidView(
                factory = { ctx -> ViewerSurfaceView(ctx, vm).also { glView = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (active) {
            OverlayLayer(vm)
            ViewerChrome(vm)
        }
    }
}

@Composable
private fun ViewerChrome(vm: ViewerViewModel) {
    val tool by vm.tool.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val turntable by vm.turntable.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val parts by vm.parts.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val extra = LocalExtraColors.current
    val info = model ?: return

    val density = LocalDensity.current
    var rootHeight by remember { mutableStateOf(0f) }
    var topEdge by remember { mutableStateOf(0f) }
    var bottomEdge by remember { mutableStateOf(0f) }
    LaunchedEffect(rootHeight, topEdge, bottomEdge) {
        if (rootHeight > 0f && bottomEdge > 0f) {
            val gap = with(density) { 10.dp.toPx() }
            vm.setViewInsets(topEdge + gap, (rootHeight - bottomEdge) + gap)
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { rootHeight = it.size.height.toFloat() + it.positionInRoot().y }
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
    ) {
        val compact = maxWidth < 380.dp
        val wide = maxWidth > 700.dp

        // Top-left tool grid.
        Column(
            Modifier.align(Alignment.TopStart).onGloballyPositioned { topEdge = it.positionInRoot().y + it.size.height },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverlayButton(Icons.Rounded.Settings, stringResource(R.string.settings)) { vm.openSettings() }
                OverlayButton(Icons.Rounded.WbSunny, stringResource(R.string.tool_lighting), active = tool == Tool.Lighting) { vm.selectTool(Tool.Lighting) }
                OverlayButton(Icons.Rounded.Diamond, stringResource(R.string.tool_material), active = tool == Tool.Material) { vm.selectTool(Tool.Material) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverlayButton(Icons.Rounded.ContentCut, stringResource(R.string.tool_section), active = tool == Tool.Section) { vm.selectTool(Tool.Section) }
                OverlayButton(Icons.Rounded.Widgets, stringResource(R.string.tool_explode), active = tool == Tool.Explode) { vm.selectTool(Tool.Explode) }
                OverlayButton(
                    Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.tool_parts),
                    active = tool == Tool.Parts, badge = info.parts.size > 1 && tool != Tool.Parts,
                ) { vm.selectTool(Tool.Parts) }
            }
        }

        // Top centre: fit view + projection toggle.
        Column(
            Modifier.align(Alignment.TopCenter).padding(start = if (compact) 120.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayButton(Icons.Rounded.Home, stringResource(R.string.home_view), onLongClick = { vm.navigate(Screen.Home) }) { vm.fitView() }
            OverlayButton(
                if (settings.orthographic) Icons.Rounded.CropSquare else Icons.Rounded.ViewInAr,
                stringResource(R.string.projection), size = 40.dp, active = settings.orthographic,
            ) { vm.toggleOrtho() }
        }

        // View cube.
        ViewCube(
            camera = camera,
            modifier = Modifier.align(Alignment.TopEnd).size(if (compact) 84.dp else 100.dp),
            onOrient = { yaw, pitch -> vm.setOrientation(yaw, pitch) },
            onDrag = { dx, dy -> vm.engine.orbit(dx * 2.2f, dy * 2.2f); vm.requestRender() },
        )

        // Selected part chip.
        if (selected >= 0 && selected < info.parts.size && tool != Tool.Parts && tool != Tool.Explode && tool != Tool.Color) {
            SelectionChip(
                name = info.parts[selected].name,
                visible = parts.getOrNull(selected)?.visible ?: true,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                onFocus = { vm.focusPart(selected) },
                onToggleVisible = { vm.setPartVisible(selected, !(parts.getOrNull(selected)?.visible ?: true)) },
                onIsolate = { vm.isolatePart(selected) },
                onClose = { vm.selectPart(-1) },
            )
        }

        // Bottom: panel or info bar, then the toolbar.
        Column(
            Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .onGloballyPositioned { bottomEdge = if (wide) rootHeight else it.positionInRoot().y },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val maxPanel = (this@BoxWithConstraints.maxHeight * if (wide) 0.6f else 0.45f)
            AnimatedContent(
                targetState = tool,
                transitionSpec = { (fadeIn() + slideInVertically { it / 6 }) togetherWith (fadeOut() + slideOutVertically { it / 6 }) },
                label = "panel",
            ) { t ->
                Box(Modifier.heightIn(max = maxPanel)) {
                    when (t) {
                        Tool.None -> InfoBar(vm, turntable)
                        Tool.Lighting -> LightingPanel(vm)
                        Tool.Material -> MaterialPanel(vm)
                        Tool.Section -> SectionPanel(vm)
                        Tool.Explode -> ExplodePanel(vm)
                        Tool.Parts -> PartsPanel(vm)
                        Tool.Save -> SavePanel(vm)
                        Tool.Color -> ColorPanel(vm)
                        Tool.Weight -> WeightPanel(vm)
                        Tool.Measure -> MeasurePanel(vm)
                        Tool.Analyze -> AnalyzePanel(vm)
                        Tool.Info -> InfoPanel(vm)
                    }
                }
            }
            BottomBar(vm, tool, compact)
        }
    }
}

@Composable
private fun BottomBar(vm: ViewerViewModel, tool: Tool, compact: Boolean) {
    val extra = LocalExtraColors.current
    val size = if (compact) 48.dp else 54.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OverlayButton(Icons.Rounded.FolderOpen, stringResource(R.string.tool_files), size = size) { vm.navigate(Screen.Home) }
        OverlayButton(Icons.Rounded.Save, stringResource(R.string.tool_save), size = size, active = tool == Tool.Save) { vm.selectTool(Tool.Save) }
        OverlayButton(
            Icons.Rounded.Palette, stringResource(R.string.tool_color), size = size,
            accent = if (tool == Tool.Color) null else extra.accent, active = tool == Tool.Color,
        ) { vm.selectTool(Tool.Color) }
        OverlayButton(Icons.Rounded.Scale, stringResource(R.string.tool_weight), size = size, active = tool == Tool.Weight) { vm.selectTool(Tool.Weight) }
        OverlayButton(Icons.Rounded.Straighten, stringResource(R.string.tool_measure), size = size, active = tool == Tool.Measure) { vm.selectTool(Tool.Measure) }
        OverlayButton(Icons.Rounded.Insights, stringResource(R.string.tool_analyze), size = size, active = tool == Tool.Analyze) { vm.selectTool(Tool.Analyze) }
    }
}

@Composable
private fun InfoBar(vm: ViewerViewModel, turntable: Boolean) {
    val model by vm.model.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dims by vm.showDimensions.collectAsStateWithLifecycle()
    val bounds by vm.sceneBounds.collectAsStateWithLifecycle()
    val info = model ?: return
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val b = if (bounds.size.length() > 0f) bounds else info.bounds
    val u = settings.units
    Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OverlayButton(Icons.Rounded.ViewInAr, stringResource(R.string.model_info), size = 64.dp) { vm.selectTool(Tool.Info) }
        Surface(
            modifier = Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp)).clickable { vm.toggleDimensions() },
            shape = RoundedCornerShape(18.dp),
            color = extra.overlayButton,
            contentColor = extra.onOverlayButton,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    "${info.name}  ·  ${Fmt.bytes(info.fileSize)} | ${Fmt.count(info.triangles)}△",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = extra.onOverlayButton.copy(alpha = 0.8f),
                )
                Text(
                    "${Fmt.number(b.size.x * u.factor, 1)} × ${Fmt.number(b.size.y * u.factor, 1)} × ${Fmt.number(b.size.z * u.factor, 1)} ${u.suffix}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(if (dims) R.string.hide_dimensions else R.string.tap_for_dimensions),
                    style = MaterialTheme.typography.labelSmall, color = if (dims) extra.dimension else extra.onOverlayButton.copy(alpha = 0.6f),
                )
            }
        }
        OverlayButton(if (turntable) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(R.string.turntable), size = 64.dp, active = turntable) {
            vm.toggleTurntable()
        }
    }
}

@Composable
private fun SelectionChip(
    name: String,
    visible: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onToggleVisible: () -> Unit,
    onIsolate: () -> Unit,
    onClose: () -> Unit,
) {
    val extra = LocalExtraColors.current
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = extra.panel, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Spacer(Modifier.width(4.dp))
            ChipIcon(Icons.Rounded.CenterFocusStrong, stringResource(R.string.focus), onFocus)
            ChipIcon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.hide_part), onToggleVisible)
            ChipIcon(Icons.Rounded.Layers, stringResource(R.string.isolate), onIsolate)
            ChipIcon(Icons.Rounded.Close, stringResource(R.string.close), onClose)
        }
    }
}

@Composable
private fun ChipIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, modifier = Modifier.size(20.dp))
    }
}
