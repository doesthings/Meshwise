package io.github.doesthings.meshwise

import android.app.Application
import io.github.doesthings.meshwise.data.Library
import io.github.doesthings.meshwise.data.ModelOpener
import io.github.doesthings.meshwise.data.RecentFiles
import io.github.doesthings.meshwise.data.Settings
import io.github.doesthings.meshwise.data.ThumbnailStore

/** Lazily created singletons (the ":cad" process never touches them). */
class MeshwiseApp : Application() {
    val settings by lazy { Settings(this) }
    val thumbnails by lazy { ThumbnailStore(this) }
    val recents by lazy { RecentFiles(this, thumbnails) }
    val library by lazy { Library(this) }
    val opener by lazy { ModelOpener(this, settings) }
}
