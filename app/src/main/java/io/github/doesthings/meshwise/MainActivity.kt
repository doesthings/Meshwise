package io.github.doesthings.meshwise

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.doesthings.meshwise.ui.AppRoot
import io.github.doesthings.meshwise.ui.theme.MeshwiseTheme
import io.github.doesthings.meshwise.viewer.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.density = resources.displayMetrics.density
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            MeshwiseTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                AppRoot(vm)
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { vm.openUri(it) }
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { vm.openUri(it) }
            }
        }
    }
}
