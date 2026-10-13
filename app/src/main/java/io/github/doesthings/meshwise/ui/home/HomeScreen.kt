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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import io.github.doesthings.meshwise.ui.components.openUrl
import io.github.doesthings.meshwise.ui.components.KofiColor
import io.github.doesthings.meshwise.ui.components.KOFI_URL
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.Coffee
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.RecentFile
import io.github.doesthings.meshwise.data.baseName
import io.github.doesthings.meshwise.ui.components.FormatBadge
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

@Composable
fun HomeScreen(vm: ViewerViewModel) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val thumbVersion by vm.thumbnailVersion.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, scheme.surfaceContainerLowest))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.primaryContainer)
                        .clickable { vm.openSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = scheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("STL · STEP · 3MF viewer", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                val context = LocalContext.current
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(KofiColor.copy(alpha = 0.14f))
                        .clickable { openUrl(context, KOFI_URL) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Coffee, stringResource(R.string.support_cd), tint = KofiColor, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            if (model != null) {
                ContinueCard(model!!.name, onClick = { vm.navigate(Screen.Viewer) })
                Spacer(Modifier.height(12.dp))
            }

            // Recent files header.
            Surface(shape = RoundedCornerShape(22.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.recent_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (recents.isNotEmpty()) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(scheme.onPrimaryContainer.copy(alpha = 0.1f))
                                .clickable { confirmClear = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Delete, stringResource(R.string.clear_recents), modifier = Modifier.size(20.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = scheme.surfaceContainerHigh.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            ) {
                if (recents.isEmpty()) {
                    EmptyRecents(onSample = { vm.openSample(it.asset) })
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(recents, key = { it.id }) { r ->
                            RecentRow(r, vm.thumbnailFile(r.id), thumbVersion, onOpen = { vm.openRecent(r) }, onRemove = { vm.removeRecent(r.id) })
                        }
                        item {
                            Text(
                                stringResource(R.string.samples),
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
                            )
                            SampleRow { vm.openSample(it.asset) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Library entry.
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { vm.refreshLibrary(); vm.navigate(Screen.Library) },
                shape = RoundedCornerShape(22.dp),
                color = scheme.surfaceContainerHigh.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = scheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.library), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))

            // Big open button.
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(28.dp),
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.open_model), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.open_model_formats), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
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
private fun ContinueCard(name: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ViewInAr, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.continue_viewing), style = MaterialTheme.typography.labelLarge)
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.PlayArrow, null)
        }
    }
}

@Composable
private fun EmptyRecents(onSample: (Sample) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(vertical = 20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.ViewInAr, null, modifier = Modifier.size(56.dp), tint = scheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.no_recents_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.no_recents_body),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        SampleRow(onSample)
    }
}

@Composable
private fun SampleRow(onSample: (Sample) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SAMPLES) { s ->
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSample(s) },
                shape = RoundedCornerShape(16.dp),
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(s.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(s.format, style = MaterialTheme.typography.labelSmall, color = scheme.onSecondaryContainer.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(r: RecentFile, thumbFile: File, thumbVersion: Int, onOpen: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, thumbFile, thumbVersion) {
        value = withContext(Dispatchers.IO) {
            if (thumbFile.exists()) runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath)?.asImageBitmap() }.getOrNull() else null
        }
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E3C44)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val t = thumb
                if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.ViewInAr, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center))
                FormatBadge(r.format, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(baseName(r.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Fmt.bytes(r.size)} · ${Fmt.count(r.triangles)} △" + if (r.parts > 1) " · ${r.parts} parts" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(Fmt.time(r.openedAt), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onRemove() })
        }
    }
}
