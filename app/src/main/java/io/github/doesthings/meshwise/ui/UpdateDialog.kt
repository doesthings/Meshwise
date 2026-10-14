package io.github.doesthings.meshwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.UpdateInfo
import io.github.doesthings.meshwise.util.Fmt
import io.github.doesthings.meshwise.viewer.UpdateStep
import io.github.doesthings.meshwise.viewer.ViewerViewModel

/** "Meshwise X is available": what changed, then Update or Not now. */
@Composable
fun UpdateDialog(vm: ViewerViewModel, info: UpdateInfo, onOpenPage: () -> Unit) {
    val step by vm.updateStep.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val busy = step is UpdateStep.Downloading
    Dialog(onDismissRequest = { if (!busy) vm.dismissUpdate(skip = false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.padding(20.dp).widthIn(max = 520.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = scheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.update_available_title, info.version), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.update_you_have, vm.currentVersion), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                if (info.notes.isNotEmpty()) {
                    Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerLowest.copy(alpha = 0.6f))
                            .verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        info.notes.forEachIndexed { i, n ->
                            if (info.notes.size > 1) {
                                Text(
                                    "Meshwise ${n.version}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    color = scheme.primary, modifier = Modifier.padding(top = if (i > 0) 10.dp else 0.dp),
                                )
                            }
                            MarkdownText(n.text)
                        }
                    }
                }
                when (val st = step) {
                    is UpdateStep.Downloading -> {
                        if (st.total > 0) LinearProgressIndicator(progress = { (st.read.toFloat() / st.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.update_downloading, Fmt.bytes(st.read), Fmt.bytes(st.total)),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    is UpdateStep.Failed -> Text(stringResource(R.string.update_failed, st.message), style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    is UpdateStep.Ready -> Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    UpdateStep.Idle -> if (info.canInstall) {
                        Text(stringResource(R.string.update_install_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.dismissUpdate(skip = true) }, enabled = !busy) { Text(stringResource(R.string.update_skip)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.dismissUpdate(skip = false) }) { Text(stringResource(R.string.update_later)) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { if (info.canInstall) vm.startUpdate() else onOpenPage() },
                        enabled = !busy,
                    ) {
                        Text(stringResource(if (step is UpdateStep.Ready) R.string.update_install else if (info.canInstall) R.string.update_now else R.string.update_download))
                    }
                }
            }
        }
    }
}

/** Just enough Markdown for release notes: headings, bullets, **bold**, `code` and [links]. */
@Composable
fun MarkdownText(md: String) {
    val scheme = MaterialTheme.colorScheme
    for (line in md.lines()) {
        val t = line.trim()
        when {
            t.isEmpty() -> Spacer(Modifier.size(2.dp))
            t.startsWith("#") -> Text(
                inline(t.trimStart('#').trim()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            t.startsWith("- ") || t.startsWith("* ") -> {
                val nested = line.length - line.trimStart().length >= 2
                Row(Modifier.padding(start = if (nested) 18.dp else 4.dp)) {
                    Text("•", color = scheme.primary, modifier = Modifier.width(14.dp))
                    Text(inline(t.substring(2)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            t.startsWith("|") -> {}  // tables don't fit a phone dialog
            else -> Text(inline(t), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(s.substring(i + 2, end))) }
                i = end + 2
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '[' -> {
                val close = s.indexOf("](", i)
                val end = if (close > 0) s.indexOf(')', close) else -1
                if (close < 0 || end < 0) { append(s[i]); i++ } else {
                    append(s.substring(i + 1, close))
                    i = end + 1
                }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
