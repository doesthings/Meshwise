package io.github.doesthings.meshwise.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.ui.theme.ValueStyle

/** Rounded square button floating over the 3D view (matches the tool grid look). */
@Composable
fun OverlayButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color? = null,
    size: Dp = 52.dp,
    badge: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            accent != null -> accent
            active -> scheme.primaryContainer
            else -> extra.overlayButton
        },
        label = "bg",
    )
    val fg = when {
        accent != null -> extra.onAccent
        active -> scheme.onPrimaryContainer
        else -> extra.onOverlayButton
    }
    Box(
        modifier
            .size(size)
            .shadow(if (active) 8.dp else 3.dp, RoundedCornerShape(16.dp), ambientColor = scheme.primary, spotColor = if (active) scheme.primary else Color.Black)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, onClickLabel = contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(24.dp))
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(scheme.tertiary),
            )
        }
    }
}

/** Container for tool panels shown above the bottom bar. */
@Composable
fun ToolPanel(
    title: String,
    icon: ImageVector,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
                RoundIconButton(Icons.Rounded.Close, "Close", onClick = onClose)
            }
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = extra.panel,
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.55f)),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(scheme.onPrimaryContainer.copy(alpha = 0.10f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = (tint ?: LocalContentColorOr(scheme.onPrimaryContainer)).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LocalContentColorOr(c: Color): Color = androidx.compose.material3.LocalContentColor.current.takeIf { it != Color.Unspecified } ?: c

/** Compact segmented selector. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.08f))
                    .clickable(role = Role.Tab) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    color = if (sel) scheme.onPrimaryContainer else scheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Pill button used inside panels. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = (if (selected) scheme.onPrimaryContainer else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.4f)
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "Label ........ value" row with a monospaced value. */
@Composable
fun ValueRow(label: String, value: String, highlight: Boolean = false, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = ValueStyle.copy(fontSize = 18.sp), color = if (highlight) extra.dimension else scheme.onSurface)
    }
}

@Composable
fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val isProject = text == "PROJECT"
    Box(
        modifier
            .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
            .background(if (isProject) scheme.primaryContainer else Color(0xCC1B2328))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (isProject) scheme.onPrimaryContainer else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, size: Dp = 34.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
