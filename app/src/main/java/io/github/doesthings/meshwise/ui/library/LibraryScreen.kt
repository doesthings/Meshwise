package io.github.doesthings.meshwise.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.LibraryItem
import io.github.doesthings.meshwise.data.baseName
import io.github.doesthings.meshwise.ui.components.FormatBadge
import io.github.doesthings.meshwise.ui.settings.PageScaffold
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(vm: ViewerViewModel) {
    val items by vm.library.collectAsStateWithLifecycle()
    val thumbVersion by vm.thumbnailVersion.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importToLibrary(uri)
    }
    Box(Modifier.fillMaxSize()) {
        PageScaffold(stringResource(R.string.library), onBack = { vm.navigate(Screen.Home) }) {
            if (items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    items(items, key = { it.file.absolutePath }) { item ->
                        LibraryRow(item, vm, thumbVersion, onRename = { renaming = item }, onDelete = { deleting = item })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { importer.launch(arrayOf("*/*")) },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.import_to_library)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
    }

    renaming?.let { item ->
        var name by remember(item) { mutableStateOf(baseName(item.name)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameLibraryItem(item, name); renaming = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, item.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteLibraryItem(item); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(item: LibraryItem, vm: ViewerViewModel, thumbVersion: Int, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumbFile = vm.thumbnailFile(item.id)
    val thumb by produceState<ImageBitmap?>(null, thumbFile, thumbVersion) {
        value = withContext(Dispatchers.IO) {
            if (thumbFile.exists()) runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath)?.asImageBitmap() }.getOrNull() else null
        }
    }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { vm.openLibraryItem(item) }, onLongClick = { menu = true })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E3C44)), contentAlignment = Alignment.BottomCenter) {
                val t = thumb
                if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.ViewInAr, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center))
                FormatBadge(item.format?.label ?: "?", Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(baseName(item.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Fmt.bytes(item.size)} · ${Fmt.time(item.modified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
}
