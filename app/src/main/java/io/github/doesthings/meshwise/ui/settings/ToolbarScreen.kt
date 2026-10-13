package io.github.doesthings.meshwise.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.ui.components.PillButton
import io.github.doesthings.meshwise.ui.viewer.icon
import io.github.doesthings.meshwise.ui.viewer.longLabel
import io.github.doesthings.meshwise.viewer.ViewerViewModel

private enum class Place { Dock, Side, Hidden }

@Composable
fun ToolbarScreen(vm: ViewerViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val dock = s.dockItems
    val side = s.sideItems
    val hidden = ToolbarItem.entries.filter { it !in dock && it !in side }

    fun place(item: ToolbarItem, to: Place) {
        val d = dock.filter { it != item }.toMutableList()
        val sd = side.filter { it != item }.toMutableList()
        when (to) {
            Place.Dock -> d += item
            Place.Side -> sd += item
            Place.Hidden -> {}
        }
        vm.setToolbar(d, sd)
    }

    fun move(item: ToolbarItem, delta: Int) {
        fun List<ToolbarItem>.moved(): List<ToolbarItem> {
            val i = indexOf(item)
            val j = (i + delta).coerceIn(0, size - 1)
            if (i < 0 || i == j) return this
            return toMutableList().also { it.removeAt(i); it.add(j, item) }
        }
        vm.setToolbar(dock.moved(), side.moved())
    }

    PageScaffold(stringResource(R.string.customize_toolbar), onBack = { vm.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Preview(dock.map { it.icon }, side.map { it.icon }, s.leftHanded)
            Text(stringResource(R.string.toolbar_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SettingsGroup(stringResource(R.string.toolbar_dock), Icons.Rounded.VerticalAlignBottom) {
                dock.forEachIndexed { i, item -> ToolRow(item, Place.Dock, i, dock.size, ::move, ::place) }
            }
            SettingsGroup(stringResource(R.string.toolbar_side), Icons.Rounded.VerticalAlignTop) {
                ToolRowFixed(Icons.Rounded.Settings, stringResource(R.string.settings))
                side.forEachIndexed { i, item -> ToolRow(item, Place.Side, i, side.size, ::move, ::place) }
            }
            if (hidden.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.toolbar_hidden), Icons.Rounded.VisibilityOff) {
                    hidden.forEach { item -> ToolRow(item, Place.Hidden, 0, 0, ::move, ::place) }
                }
            }
            PillButton(stringResource(R.string.reset_layout), icon = Icons.Rounded.RestartAlt) {
                vm.setToolbar(ToolbarItem.DEFAULT_DOCK, ToolbarItem.DEFAULT_SIDE)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Preview(dock: List<ImageVector>, side: List<ImageVector>, leftHanded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(190.dp),
        color = scheme.surfaceContainer,
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerLowest))).padding(12.dp)) {
            // Top cluster.
            Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.align(if (leftHanded) Alignment.TopEnd else Alignment.TopStart)) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (listOf(Icons.Rounded.Settings) + side).chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(it, null, modifier = Modifier.size(18.dp)) } }
                        }
                    }
                }
            }
            Box(Modifier.align(if (leftHanded) Alignment.TopStart else Alignment.TopEnd).size(44.dp).clip(RoundedCornerShape(10.dp)).background(scheme.onSurface.copy(alpha = 0.12f)))
            Text(stringResource(R.string.toolbar_preview), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            // Bottom bar.
            Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Row(Modifier.padding(vertical = 8.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    dock.forEach { Icon(it, null, tint = scheme.primary, modifier = Modifier.size(22.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ToolRowFixed(icon: ImageVector, label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(scheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolRow(
    item: ToolbarItem,
    place: Place,
    index: Int,
    count: Int,
    onMove: (ToolbarItem, Int) -> Unit,
    onPlace: (ToolbarItem, Place) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(item.icon, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(item.longLabel), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (place != Place.Hidden) {
                SmallIcon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.move_up), enabled = index > 0) { onMove(item, -1) }
                SmallIcon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.move_down), enabled = index < count - 1) { onMove(item, 1) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaceChip(stringResource(R.string.toolbar_dock), place == Place.Dock) { onPlace(item, Place.Dock) }
            PlaceChip(stringResource(R.string.toolbar_side), place == Place.Side) { onPlace(item, Place.Side) }
            PlaceChip(stringResource(R.string.toolbar_hidden), place == Place.Hidden) { onPlace(item, Place.Hidden) }
        }
    }
}

@Composable
private fun RowScope.PlaceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.07f))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = if (selected) scheme.onPrimary else scheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = scheme.onSurface.copy(alpha = if (enabled) 1f else 0.25f))
    }
}
