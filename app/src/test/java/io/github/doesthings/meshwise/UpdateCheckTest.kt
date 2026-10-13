package io.github.doesthings.meshwise

import androidx.test.core.app.ApplicationProvider
import io.github.doesthings.meshwise.TestSupport.awaitUntil
import io.github.doesthings.meshwise.data.UPDATES_NOT_ASKED
import io.github.doesthings.meshwise.data.UPDATES_OFF
import io.github.doesthings.meshwise.data.UPDATES_ON
import io.github.doesthings.meshwise.data.UpdateInfo
import io.github.doesthings.meshwise.data.Updates
import io.github.doesthings.meshwise.viewer.ViewerViewModel
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
    private val release = """
        {"tag_name": "v1.2.0", "html_url": "https://github.com/doesthings/Meshwise/releases/tag/v1.2.0",
         "draft": false, "prerelease": false, "assets": [
           {"name": "Meshwise-1.2.0-arm64-v8a.apk", "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v1.2.0/Meshwise-1.2.0-arm64-v8a.apk"},
           {"name": "Meshwise-1.2.0-universal.apk", "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v1.2.0/Meshwise-1.2.0-universal.apk"},
           {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/doesthings/Meshwise/releases/download/v1.2.0/SHA256SUMS.txt"}
         ]}
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
    fun picksTheApkForThisDevice() {
        val arm = Updates.parseLatest(release, "1.0.1", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("1.2.0", arm.version)
        assertTrue(arm.downloadUrl.endsWith("Meshwise-1.2.0-arm64-v8a.apk"))
        val x86 = Updates.parseLatest(release, "1.0.1", listOf("x86_64"))!!
        assertTrue(x86.downloadUrl.endsWith("Meshwise-1.2.0-universal.apk"))
        val noAssets = Updates.parseLatest("""{"tag_name": "v1.2.0", "html_url": "https://github.com/doesthings/Meshwise/releases/tag/v1.2.0"}""", "1.0.1")!!
        assertEquals(noAssets.pageUrl, noAssets.downloadUrl)
    }

    @Test
    fun ignoresOlderDraftAndPrereleases() {
        assertNull(Updates.parseLatest(release, "1.2.0"))
        assertNull(Updates.parseLatest(release.replace("\"draft\": false", "\"draft\": true"), "1.0.1"))
        assertNull(Updates.parseLatest(release.replace("\"prerelease\": false", "\"prerelease\": true"), "1.0.1"))
    }

    @Test
    fun viewModelFlow() {
        assumeTrue("JVM build of libmeshwise.so missing", TestSupport.nativeAvailable())
        val vm = ViewerViewModel(ApplicationProvider.getApplicationContext())
        val calls = AtomicInteger()
        var clock = 1_000_000_000L
        var answer: () -> UpdateInfo? = { UpdateInfo("1.2.0", "https://example.invalid/a.apk", "https://example.invalid/") }
        vm.updateSource = { calls.incrementAndGet(); answer() }
        vm.currentVersion = "1.0.1"
        vm.now = { clock }

        // Nothing happens until the user says yes.
        assertEquals(UPDATES_NOT_ASKED, vm.settings.value.updateChecks)
        vm.checkForUpdates(manual = false)
        assertEquals(0, calls.get())

        vm.setUpdateChecks(true)
        assertEquals(UPDATES_ON, vm.settings.value.updateChecks)
        awaitUntil { vm.update.value != null }
        assertEquals("1.2.0", vm.update.value!!.version)
        assertEquals(1, calls.get())

        // Skipping hides it from automatic checks, which also wait a day.
        vm.dismissUpdate(skip = true)
        assertNull(vm.update.value)
        vm.checkForUpdates(manual = false)
        assertEquals(1, calls.get())
        clock += Updates.CHECK_INTERVAL_MS + 1
        vm.checkForUpdates(manual = false)
        awaitUntil { calls.get() == 2 && vm.settings.value.lastUpdateCheck == clock }
        assertNull(vm.update.value)

        // A manual check still offers it, and reports errors.
        vm.checkForUpdates(manual = true)
        awaitUntil { vm.update.value != null }
        vm.dismissUpdate(skip = false)
        answer = { throw IOException("offline") }
        vm.checkForUpdates(manual = true)
        awaitUntil { calls.get() == 4 }
        assertNull(vm.update.value)

        vm.setUpdateChecks(false)
        assertEquals(UPDATES_OFF, vm.settings.value.updateChecks)
        clock += Updates.CHECK_INTERVAL_MS + 1
        vm.checkForUpdates(manual = false)
        assertEquals(4, calls.get())
    }
}
