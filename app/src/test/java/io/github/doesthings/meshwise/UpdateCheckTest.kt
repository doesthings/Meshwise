package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.UPDATES_DEFAULT
import io.github.doesthings.meshwise.data.UPDATES_OFF
import io.github.doesthings.meshwise.data.UpdateInfo
import io.github.doesthings.meshwise.data.Updates
import io.github.doesthings.meshwise.viewer.UiEvent
import io.github.doesthings.meshwise.viewer.UpdateStep
import io.github.doesthings.meshwise.viewer.ViewerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckTest {
    private fun release(version: String, body: String = "", draft: Boolean = false, pre: Boolean = false) = """
        {"tag_name": "v$version", "html_url": "https://github.com/doesthings/Meshwise/releases/tag/v$version",
         "draft": $draft, "prerelease": $pre, "body": ${org.json.JSONObject.quote(body)}, "assets": [
           {"name": "Meshwise-$version-arm64-v8a.apk", "size": 1000, "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v$version/Meshwise-$version-arm64-v8a.apk"},
           {"name": "Meshwise-$version-universal.apk", "size": 3000, "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v$version/Meshwise-$version-universal.apk"},
           {"name": "SHA256SUMS.txt", "size": 300, "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v$version/SHA256SUMS.txt"}
         ]}
    """.trimIndent()

    private val notes120 = """
        ![Meshwise](https://example.invalid/hero.png)

        ## Meshwise 1.2.0: Smart measuring

        Tap a **feature** and Meshwise works out what it is.

        - **Hole or shaft:** diameter, radius and depth
        - Point to point with ΔX / ΔY / ΔZ

        > **Honest note:** it's vibe coded.

        If Meshwise is useful to you, you can [buy me a coffee on Ko-fi](https://doesthings.github.io/donate/) ☕

        ### Which APK do I need?
        | File | Devices |
        |---|---|
    """.trimIndent()

    @Test
    fun comparesVersions() {
        assertEquals(listOf(1, 0, 1), Updates.parseVersion("v1.0.1"))
        assertEquals(listOf(1, 0, 1), Updates.parseVersion("1.0.1-debug"))
        assertNull(Updates.parseVersion("latest"))
        assertTrue(Updates.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updates.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Updates.isNewer("v2.0", "1.9.9"))
        assertFalse(Updates.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updates.isNewer("v1.0.0", "1.0.1-debug"))
        assertFalse(Updates.isNewer("nightly", "1.0.0"))
    }

    @Test
    fun picksTheApkForThisDeviceAndCollectsNotes() {
        val list = "[" + listOf(
            release("1.1.0", "## Meshwise 1.1.0\n\n- New look"),
            release("1.3.0", "## Meshwise 1.3.0: Print prep\n\n- Lay flat", pre = true),
            release("1.2.0", notes120),
            release("1.0.0", "- First"),
        ).joinToString(",") + "]"
        val arm = Updates.parseReleases(list, "1.0.0", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("1.2.0", arm.version)  // the pre-release doesn't count
        assertEquals("Meshwise-1.2.0-arm64-v8a.apk", arm.apkName)
        assertTrue(arm.downloadUrl.endsWith("Meshwise-1.2.0-arm64-v8a.apk"))
        assertTrue(arm.checksumsUrl!!.endsWith("SHA256SUMS.txt"))
        assertEquals(1000L, arm.apkSize)
        assertEquals(listOf("1.2.0", "1.1.0"), arm.notes.map { it.version })  // newest first, only newer ones
        val x86 = Updates.parseReleases(list, "1.0.0", listOf("x86_64"))!!
        assertEquals("Meshwise-1.2.0-universal.apk", x86.apkName)
        assertNull(Updates.parseReleases(list, "1.2.0"))
        assertNull(Updates.parseReleases("[" + release("1.4.0", draft = true) + "]", "1.0.0"))
    }

    @Test
    fun cleansReleaseNotes() {
        val text = Updates.cleanNotes(notes120)
        assertTrue(text.startsWith("### Smart measuring"))
        assertTrue(text.contains("- **Hole or shaft:** diameter, radius and depth"))
        assertFalse(text.contains("!["))
        assertFalse(text.contains("Honest note"))
        assertFalse(text.contains("Ko-fi"))
        assertFalse(text.contains("Which APK"))
        assertEquals("", Updates.cleanNotes("## Meshwise 1.0.0\n\n![x](y)"))
    }

    @Test
    fun viewModelFlow() {
        assumeTrue("JVM build of libmeshwise.so missing", TestSupport.nativeAvailable())
        val vm = ViewerViewModel(ApplicationProvider.getApplicationContext())
        val calls = AtomicInteger()
        var clock = 1_000_000_000L
        var answer: () -> UpdateInfo? = {
            UpdateInfo("1.2.0", "https://example.invalid/a.apk", "https://example.invalid/", apkName = "Meshwise-1.2.0-universal.apk", apkSize = 10)
        }
        vm.updateSource = { calls.incrementAndGet(); answer() }
        vm.currentVersion = "1.0.1"
        vm.now = { clock }

        // Nothing is fetched while the app is merely created; checks start with the app, on by default.
        assertEquals(UPDATES_DEFAULT, vm.settings.value.updateChecks)
        assertEquals(0, calls.get())
        vm.onAppStart()
        awaitUntil { vm.updatePrompt.value }
        assertEquals("1.2.0", vm.update.value!!.version)
        assertEquals(1, calls.get())

        // "Skip this version" hides it from automatic checks, which also wait a day; the
        // update stays known (Settings offers it).
        vm.dismissUpdate(skip = true)
        assertFalse(vm.updatePrompt.value)
        assertEquals("1.2.0", vm.update.value!!.version)
        vm.checkForUpdates(manual = false)
        assertEquals(1, calls.get())
        clock += Updates.CHECK_INTERVAL_MS + 1
        vm.checkForUpdates(manual = false)
        awaitUntil { calls.get() == 2 && vm.settings.value.lastUpdateCheck == clock }
        assertFalse(vm.updatePrompt.value)

        // A manual check still offers it. Update downloads (here: a fake download) and asks to install.
        vm.checkForUpdates(manual = true)
        awaitUntil { vm.updatePrompt.value }
        vm.updateDownloader = { _, file, progress, _ ->
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(10))
            progress(10, 10)
        }
        var installed: java.io.File? = null
        val collector = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { if (it is UiEvent.Install) installed = it.file } }
        vm.startUpdate()
        awaitUntil { vm.updateStep.value is UpdateStep.Ready && installed != null }
        assertTrue(installed!!.name.endsWith("1.2.0.apk"))
        collector.cancel()

        // A failed download says so.
        vm.dismissUpdate(skip = false)
        vm.updateDownloader = { _, _, _, _ -> throw IOException("checksum mismatch") }
        answer = { UpdateInfo("1.3.0", "https://example.invalid/b.apk", "https://example.invalid/", apkName = "Meshwise-1.3.0-universal.apk") }
        vm.checkForUpdates(manual = true)
        awaitUntil { vm.update.value?.version == "1.3.0" }
        vm.startUpdate()
        awaitUntil { vm.updateStep.value is UpdateStep.Failed }

        // Offline: a manual check reports it. Turned off: automatic checks stop.
        answer = { throw IOException("offline") }
        vm.checkForUpdates(manual = true)
        awaitUntil { calls.get() == 5 }
        vm.setUpdateChecks(false)
        assertEquals(UPDATES_OFF, vm.settings.value.updateChecks)
        clock += Updates.CHECK_INTERVAL_MS + 1
        vm.checkForUpdates(manual = false)
        assertEquals(5, calls.get())
    }
}
