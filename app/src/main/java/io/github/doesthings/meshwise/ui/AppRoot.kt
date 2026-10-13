package io.github.doesthings.meshwise.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.UPDATES_NOT_ASKED
import io.github.doesthings.meshwise.ui.components.openUrl
import io.github.doesthings.meshwise.ui.home.HomeScreen
import io.github.doesthings.meshwise.ui.library.LibraryScreen
import io.github.doesthings.meshwise.ui.settings.AboutScreen
import io.github.doesthings.meshwise.ui.settings.SettingsScreen
import io.github.doesthings.meshwise.ui.viewer.LoadingOverlay
import io.github.doesthings.meshwise.ui.viewer.ViewerScreen
import io.github.doesthings.meshwise.viewer.Screen
import io.github.doesthings.meshwise.viewer.UiEvent
import io.github.doesthings.meshwise.viewer.ViewerViewModel

@Composable
fun AppRoot(vm: ViewerViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = e.mime
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
        }
    }

    BackHandler(enabled = screen != Screen.Home || loading != null) {
        if (loading != null) vm.cancelLoading() else vm.back()
    }

    val view = LocalView.current
    view.keepScreenOn = screen == Screen.Viewer && settings.keepScreenOn

    Box(Modifier.fillMaxSize()) {
        // The 3D view stays composed while a model is loaded so the GL context survives
        // trips to the home screen.
        if (model != null) ViewerScreen(vm, active = screen == Screen.Viewer)

        AnimatedVisibility(visible = screen == Screen.Home, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().blockTouches()) { HomeScreen(vm) }
        }
        AnimatedVisibility(visible = screen == Screen.Library, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().blockTouches()) { LibraryScreen(vm) }
        }
        AnimatedVisibility(visible = screen == Screen.Settings, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().blockTouches()) { SettingsScreen(vm) }
        }
        AnimatedVisibility(visible = screen == Screen.About, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().blockTouches()) { AboutScreen(vm) }
        }

        loading?.let { LoadingOverlay(it, onCancel = vm::cancelLoading) }

        UpdateDialogs(vm, askNow = screen == Screen.Home && loading == null && settings.updateChecks == UPDATES_NOT_ASKED)
        update?.let { info ->
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate(skip = false) },
                title = { Text(stringResource(R.string.update_available_title, info.version)) },
                text = { Text(stringResource(R.string.update_available_body, vm.currentVersion)) },
                confirmButton = {
                    Button(onClick = { openUrl(context, info.downloadUrl); vm.dismissUpdate(skip = false) }) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.dismissUpdate(skip = true) }) { Text(stringResource(R.string.update_skip)) }
                        TextButton(onClick = { vm.dismissUpdate(skip = false) }) { Text(stringResource(R.string.update_later)) }
                    }
                },
            )
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
        )
    }
}

/** Keeps touches from reaching the 3D view underneath full-screen pages. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/** Asks once whether Meshwise may check GitHub for updates. Closing the dialog asks again next launch. */
@Composable
private fun UpdateDialogs(vm: ViewerViewModel, askNow: Boolean) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!askNow || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.update_ask_title)) },
        text = { Text(stringResource(R.string.update_ask_body)) },
        confirmButton = { Button(onClick = { vm.setUpdateChecks(true) }) { Text(stringResource(R.string.update_ask_yes)) } },
        dismissButton = { TextButton(onClick = { vm.setUpdateChecks(false) }) { Text(stringResource(R.string.update_ask_no)) } },
    )
}
