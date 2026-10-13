package io.github.doesthings.meshwise.ui.home

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.RecentFile
import io.github.doesthings.meshwise.data.baseName
import io.github.doesthings.meshwise.ui.components.FormatBadge
import io.github.doesthings.meshwise.ui.components.KOFI_URL
import io.github.doesthings.meshwise.ui.components.KofiColor
import io.github.doesthings.meshwise.ui.components.openUrl
import io.github.doesthings.meshwise.ui.settings.AppIcon
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Sample(val title: String, val format: String, val asset: String)

val SAMPLES = listOf(
    Sample("Bearing assembly", "STEP", "samples/bearing_assembly.step"),
    Sample("Keychain", "3MF", "samples/keychain.3mf"),
    Sample("Spur gear", "STL", "samples/spur_gear.stl"),
    Sample("Twisted vase", "STL", "samples/twisted_vase.stl"),
    Sample("Trefoil knot", "STL", "samples/trefoil_knot.stl"),
)

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun HomeScreen(vm: ViewerViewModel) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val thumbVersion by vm.thumbnailVersion.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    val grid = settings.recentsGrid
    val gridState = rememberLazyGridState()
    val showFab by remember { derivedStateOf { gridState.firstVisibleItemIndex > 1 } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }

    val shown = remember(recents, query, settings.recentsSort) {
        val filtered = recents.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        when (settings.recentsSort) {
            1 -> filtered.sortedBy { it.name.lowercase() }
            2 -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedByDescending { it.openedAt }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.surfaceContainerLow, scheme.background, scheme.background)))) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().widthIn(max = 840.dp).align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            full("top") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    TopIcon(Icons.Rounded.Coffee, stringResource(R.string.support_cd), tint = KofiColor, bg = KofiColor.copy(alpha = 0.14f)) { openUrl(context, KOFI_URL) }
                    Spacer(Modifier.width(8.dp))
                    TopIcon(Icons.Rounded.Settings, stringResource(R.string.settings)) { vm.openSettings() }
                }
            }

            full("open") { OpenCard { picker.launch(arrayOf("*/*")) } }

            model?.let { m ->
                full("continue") { ContinueCard(m.name, onClick = { vm.navigate(Screen.Viewer) }) }
            }

            full("recentHeader") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.recent_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (recents.isNotEmpty()) Text(pluralStringResource(R.plurals.recent_count, recents.size, recents.size), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                        if (recents.isNotEmpty()) {
                            SmallAction(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, stringResource(R.string.search), searching) {
                                searching = !searching
                                if (!searching) query = ""
                            }
                            Box {
                                SmallAction(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) { sortMenu = true }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf(R.string.sort_recent, R.string.sort_name, R.string.sort_size).forEachIndexed { i, label ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(label), fontWeight = if (settings.recentsSort == i) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = { vm.updateSettings { it.copy(recentsSort = i) }; sortMenu = false },
                                        )
                                    }
                                }
                            }
                            SmallAction(
                                if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                stringResource(if (grid) R.string.layout_list else R.string.layout_grid),
                            ) { vm.updateSettings { it.copy(recentsGrid = !grid) } }
                            SmallAction(Icons.Rounded.Delete, stringResource(R.string.clear_recents)) { confirmClear = true }
                        }
                    }
                    if (searching) SearchField(query) { query = it }
                }
            }

            if (recents.isEmpty()) {
                full("empty") { EmptyRecents() }
            } else if (shown.isEmpty()) {
                full("nomatch") {
                    Text(stringResource(R.string.no_matches), color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
            } else if (grid) {
                items(shown, key = { "g" + it.id }) { r ->
                    RecentCard(r, vm.thumbnailFile(r.id), thumbVersion, onOpen = { vm.openRecent(r) }, onRemove = { vm.removeRecent(r.id) })
                }
            } else {
                full("list") {
                    Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            shown.forEach { r ->
                                RecentRow(r, vm.thumbnailFile(r.id), thumbVersion, onOpen = { vm.openRecent(r) }, onRemove = { vm.removeRecent(r.id) })
                            }
                        }
                    }
                }
            }

            full("samples") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.try_sample), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SAMPLES) { s -> SampleChip(s) { vm.openSample(s.asset) } }
                    }
                }
            }

            full("library") { LibraryCard { vm.refreshLibrary(); vm.navigate(Screen.Library) } }
        }

        // Floating "open" button once the big one has scrolled away.
        AnimatedVisibility(
            showFab,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
        Surface(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.navigationBarsPadding().padding(20.dp),
            shape = RoundedCornerShape(22.dp),
            color = scheme.primary,
            contentColor = scheme.onPrimary,
            shadowElevation = 10.dp,
        ) {
            Row(Modifier.height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FileOpen, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.open_model), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_recents)) },
            text = { Text(stringResource(R.string.clear_recents_message)) },
            confirmButton = { TextButton(onClick = { vm.clearRecents(); confirmClear = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, tint: Color? = null, bg: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg ?: scheme.surfaceContainerHighest).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint ?: scheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (active) scheme.primaryContainer else Color.Transparent).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.search_recents), color = scheme.onSurfaceVariant)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OpenCard(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer)))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(scheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ViewInAr, null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.open_model), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
                    Text(stringResource(R.string.open_model_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.open_model_formats), style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(name: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ViewInAr, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.continue_viewing), style = MaterialTheme.typography.labelLarge)
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.onTertiaryContainer.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null)
            }
        }
    }
}

@Composable
private fun EmptyRecents() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ViewInAr, null, modifier = Modifier.size(38.dp), tint = scheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.no_recents_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.no_recents_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SampleChip(s: Sample, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
        Row(Modifier.padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(scheme.onSecondaryContainer.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Text(s.format, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(s.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LibraryCard(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Inventory2, null, tint = scheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberThumb(file: File, version: Int): ImageBitmap? {
    val thumb by produceState<ImageBitmap?>(null, file, version) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() else null
        }
    }
    return thumb
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(r: RecentFile, thumbFile: File, thumbVersion: Int, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val thumb = rememberThumb(thumbFile, thumbVersion)
    Box {
        Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF26323A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.ViewInAr, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                    FormatBadge(r.format, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                Text(
                    baseName(r.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                )
                Text(
                    "${Fmt.bytes(r.size)} · ${Fmt.time(r.openedAt)}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(r: RecentFile, thumbFile: File, thumbVersion: Int, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val thumb = rememberThumb(thumbFile, thumbVersion)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF26323A)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.ViewInAr, null, tint = Color.White.copy(alpha = 0.5f))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(r.format)
                    Spacer(Modifier.width(6.dp))
                    Text(baseName(r.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${Fmt.bytes(r.size)} · ${Fmt.count(r.triangles)} △" + if (r.parts > 1) " · ${r.parts} parts" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(Fmt.time(r.openedAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onRemove() })
        }
    }
}
