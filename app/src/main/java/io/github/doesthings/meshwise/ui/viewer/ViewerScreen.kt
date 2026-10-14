package io.github.doesthings.meshwise.ui.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded._360
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.ui.components.rememberHaptic
import io.github.doesthings.meshwise.ui.components.DockButton
import io.github.doesthings.meshwise.ui.theme.LocalUiPrefs
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
import io.github.doesthings.meshwise.ui.panels.PrintPanel
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
    val info = model ?: return
    val prefs = LocalUiPrefs.current

    val density = LocalDensity.current
    var rootHeight by remember { mutableStateOf(0f) }
    var clusterEdge by remember { mutableStateOf(0f) }
    var viewEdge by remember { mutableStateOf(0f) }
    var bottomEdge by remember { mutableStateOf(0f) }
    var clusterHeight by remember { mutableStateOf(0) }
    var viewHeight by remember { mutableStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    // In landscape the top controls sit in the corners, so the model may use the full height
    // above the bottom bars; in portrait it goes between the top controls and the bars.
    val topEdge = if (landscape) 0f else maxOf(clusterEdge, viewEdge)
    LaunchedEffect(rootHeight, topEdge, bottomEdge) {
        if (rootHeight > 0f && bottomEdge > 0f) {
            val gap = with(density) { 10.dp.toPx() }
            vm.setViewInsets(if (landscape) 0f else topEdge + gap, (rootHeight - bottomEdge) + gap)
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { rootHeight = it.size.height.toFloat() + it.positionInRoot().y }
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
    ) {
        val compact = maxWidth < 380.dp
        val wide = maxWidth > maxHeight
        landscape = wide
        val left = settings.leftHanded
        val dock = settings.dockItems
        val side = settings.sideItems

        // Tool cluster: settings plus the user's "top cluster" tools.
        SideCluster(
            vm = vm,
            items = side,
            tool = tool,
            turntable = turntable,
            columns = if (compact) 3 else 3,
            badgeParts = info.parts.size > 1,
            modifier = Modifier.align(if (left) Alignment.TopEnd else Alignment.TopStart)
                .onGloballyPositioned { clusterEdge = it.positionInRoot().y + it.size.height; clusterHeight = it.size.height },
        )

        // View column: cube, fit view and projection.
        Column(
            Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .onGloballyPositioned { viewEdge = it.positionInRoot().y + it.size.height; viewHeight = it.size.height },
            horizontalAlignment = if (left) Alignment.Start else Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (settings.showViewCube) {
                ViewCube(
                    camera = camera,
                    modifier = Modifier.size(if (compact) 84.dp else 96.dp),
                    onOrient = { yaw, pitch -> vm.setOrientation(yaw, pitch) },
                    onDrag = { dx, dy -> vm.engine.orbit(dx * 2.2f, dy * 2.2f); vm.requestRender() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val small = prefs.buttonSize - 8.dp
                OverlayButton(Icons.Rounded.CenterFocusStrong, stringResource(R.string.home_view), size = small, onLongClick = { vm.navigate(Screen.Home) }) { vm.fitView() }
                OverlayButton(
                    if (settings.orthographic) Icons.Rounded.CropSquare else Icons.Rounded.ViewInAr,
                    stringResource(R.string.projection), size = small, active = settings.orthographic,
                ) { vm.toggleOrtho() }
            }
        }

        // Selected part chip, just below whatever is tallest at the top.
        if (selected >= 0 && selected < info.parts.size && tool != Tool.Parts && tool != Tool.Explode && tool != Tool.Color) {
            val chipTop = with(density) { maxOf(clusterHeight, viewHeight).toDp() } + 10.dp
            SelectionChip(
                name = info.parts[selected].name,
                visible = parts.getOrNull(selected)?.visible ?: true,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = chipTop),
                onFocus = { vm.focusPart(selected) },
                onToggleVisible = { vm.setPartVisible(selected, !(parts.getOrNull(selected)?.visible ?: true)) },
                onIsolate = { vm.isolatePart(selected) },
                onClose = { vm.selectPart(-1) },
            )
        }

        // Bottom: panel or info bar, then the dock.
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .onGloballyPositioned { bottomEdge = it.positionInRoot().y },
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
                        Tool.None -> if (settings.showInfoBar) InfoBar(vm, turntable)
                        Tool.Lighting -> LightingPanel(vm)
                        Tool.Material -> MaterialPanel(vm)
                        Tool.Section -> SectionPanel(vm)
                        Tool.Explode -> ExplodePanel(vm)
                        Tool.Parts -> PartsPanel(vm)
                        Tool.Save -> SavePanel(vm)
                        Tool.Color -> ColorPanel(vm)
                        Tool.Print -> PrintPanel(vm)
                        Tool.Measure -> MeasurePanel(vm)
                        Tool.Analyze -> AnalyzePanel(vm)
                        Tool.Info -> InfoPanel(vm)
                    }
                }
            }
            if (dock.isNotEmpty()) Dock(vm, dock, tool, turntable, settings.showLabels)
        }
    }
}

private fun ToolbarItem.isActive(tool: Tool, turntable: Boolean) =
    if (this == ToolbarItem.Turntable) turntable else this.tool?.let { it == tool } ?: false

@Composable
private fun SideCluster(
    vm: ViewerViewModel,
    items: List<ToolbarItem>,
    tool: Tool,
    turntable: Boolean,
    columns: Int,
    badgeParts: Boolean,
    modifier: Modifier,
) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val prefs = LocalUiPrefs.current
    val cells: List<ToolbarItem?> = listOf<ToolbarItem?>(null) + items  // null = settings
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(prefs.buttonSize * 0.42f),
        color = extra.panel,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { item ->
                        if (item == null) {
                            ClusterButton(Icons.Rounded.Settings, stringResource(R.string.settings), active = false) { vm.openSettings() }
                        } else {
                            ClusterButton(
                                item.icon, stringResource(item.longLabel),
                                active = item.isActive(tool, turntable),
                                badge = item == ToolbarItem.Parts && badgeParts && tool != Tool.Parts,
                            ) { vm.onToolbarItem(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterButton(icon: ImageVector, label: String, active: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val prefs = LocalUiPrefs.current
    val s = prefs.buttonSize - 4.dp
    val bg by animateColorAsState(if (active) scheme.primaryContainer else Color.Transparent, label = "cluster")
    val click = rememberHaptic(onClick)
    Box(
        Modifier.size(s).clip(RoundedCornerShape(s * 0.32f)).background(bg)
            .clickable(role = Role.Button, onClickLabel = label, onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) scheme.onPrimaryContainer else extra.onOverlayButton, modifier = Modifier.size(prefs.iconSize))
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp).clip(CircleShape).background(scheme.tertiary))
    }
}

@Composable
private fun Dock(vm: ViewerViewModel, items: List<ToolbarItem>, tool: Tool, turntable: Boolean, showLabels: Boolean) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val prefs = LocalUiPrefs.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = extra.panel,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 8.dp,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            val minItem = prefs.dockButton + 2.dp
            val fits = maxWidth / items.size >= minItem
            val row = Modifier.fillMaxWidth().then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            Row(row, horizontalArrangement = if (fits) Arrangement.SpaceEvenly else Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEach { item ->
                    DockButton(
                        icon = item.icon,
                        label = stringResource(item.label),
                        active = item.isActive(tool, turntable),
                        showLabel = showLabels,
                        tint = if (item == ToolbarItem.Color) extra.accent else null,
                        modifier = if (fits) Modifier.weight(1f) else Modifier.width(minItem),
                    ) { vm.onToolbarItem(item) }
                }
            }
        }
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
    val d = settings.decimals.coerceIn(0, 1)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = extra.panel,
        contentColor = extra.onOverlayButton,
        border = BorderStroke(1.dp, if (dims) extra.dimension.copy(alpha = 0.7f) else scheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoIcon(Icons.Rounded.Info, stringResource(R.string.model_info)) { vm.selectTool(Tool.Info) }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable { vm.toggleDimensions() }.padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${info.name}  ·  ${Fmt.bytes(info.fileSize)}  ·  ${Fmt.count(info.triangles)} △",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = extra.onOverlayButton.copy(alpha = 0.75f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Straighten, null, tint = if (dims) extra.dimension else extra.onOverlayButton.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${Fmt.number(b.size.x * u.factor, d)} × ${Fmt.number(b.size.y * u.factor, d)} × ${Fmt.number(b.size.z * u.factor, d)} ${u.suffix}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center,
                        color = if (dims) extra.dimension else extra.onOverlayButton,
                    )
                }
                Text(
                    stringResource(if (dims) R.string.hide_dimensions else R.string.tap_for_dimensions),
                    style = MaterialTheme.typography.labelSmall, color = extra.onOverlayButton.copy(alpha = 0.55f),
                )
            }
            InfoIcon(if (turntable) Icons.Rounded.Pause else Icons.Rounded._360, stringResource(R.string.turntable), active = turntable) { vm.toggleTurntable() }
        }
    }
}

@Composable
private fun InfoIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val click = rememberHaptic(onClick)
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) scheme.primaryContainer else extra.onOverlayButton.copy(alpha = 0.07f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) scheme.onPrimaryContainer else extra.onOverlayButton)
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier, shape = RoundedCornerShape(22.dp), color = extra.panel, contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)), shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
            Spacer(Modifier.width(8.dp))
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
private fun ChipIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, modifier = Modifier.size(20.dp))
    }
}
