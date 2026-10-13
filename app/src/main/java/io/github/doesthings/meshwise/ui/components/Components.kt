package io.github.doesthings.meshwise.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.doesthings.meshwise.ui.theme.LocalExtraColors
import io.github.doesthings.meshwise.ui.theme.LocalUiPrefs
import io.github.doesthings.meshwise.ui.theme.ValueStyle

/** Wraps a click with the light haptic tick the user can turn off in Settings. */
@Composable
fun rememberHaptic(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val on = LocalUiPrefs.current.haptics
    return {
        if (on) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onClick()
    }
}

/** Rounded square button floating over the 3D view. */
@Composable
fun OverlayButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color? = null,
    size: Dp = Dp.Unspecified,
    badge: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val prefs = LocalUiPrefs.current
    val scheme = MaterialTheme.colorScheme
    val s = if (size == Dp.Unspecified) prefs.buttonSize else size
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
    val corner = RoundedCornerShape(s * 0.3f)
    val click = rememberHaptic(onClick)
    Box(
        modifier
            .size(s)
            .shadow(if (active) 8.dp else 3.dp, corner, ambientColor = scheme.primary, spotColor = if (active) scheme.primary else Color.Black)
            .clip(corner)
            .background(bg)
            .combinedClickable(onClick = click, onLongClick = onLongClick, role = Role.Button, onClickLabel = contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(prefs.iconSize * (s / 52.dp).coerceIn(0.85f, 1.2f)))
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).clip(CircleShape).background(scheme.tertiary),
            )
        }
    }
}

/** A button inside the bottom dock: icon, optional label, and a pill behind it when active. */
@Composable
fun DockButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val prefs = LocalUiPrefs.current
    val bg by animateColorAsState(if (active) scheme.primaryContainer else Color.Transparent, label = "dockBg")
    val fg = if (active) scheme.onPrimaryContainer else extra.onOverlayButton
    val click = rememberHaptic(onClick)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = click)
            .padding(vertical = if (showLabel) 4.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .widthIn(max = prefs.dockButton + 8.dp)
                .fillMaxWidth()
                .height(if (showLabel) prefs.dockButton - 14.dp else prefs.dockButton)
                .clip(RoundedCornerShape(50))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (active) fg else (tint ?: fg), modifier = Modifier.size(prefs.iconSize))
        }
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) scheme.primary else extra.onOverlayButton.copy(alpha = 0.85f),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Container for tool panels shown above the bottom bar: one card with a header row. */
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = extra.panel,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
                RoundIconButton(Icons.Rounded.Close, "Close", onClick = onClose)
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 14.dp))
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val content = LocalContentColor.current.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(content.copy(alpha = 0.08f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = (tint ?: content).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}

/** Segmented selector: a track with a sliding pill behind the selected option. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(scheme.onSurface.copy(alpha = 0.07f))
            .padding(3.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val w = maxWidth / n
        val x by animateDpAsState(w * index, spring(dampingRatio = 0.8f, stiffness = 600f), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.primaryContainer),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { o ->
                val sel = o == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
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
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
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
    val bg by animateColorAsState(if (selected) scheme.primaryContainer else scheme.onSurface.copy(alpha = 0.08f), label = "pill")
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
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

/** Toggle chip: a pill with a check mark when on. */
@Composable
fun ToggleChip(text: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    PillButton(text, icon = if (checked) Icons.Rounded.Check else icon, selected = checked) { onChange(!checked) }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, summary: String? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
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
            .clip(RoundedCornerShape(8.dp))
            .background(if (isProject) scheme.primaryContainer else Color(0xCC1B2328))
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
