package io.github.doesthings.meshwise

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.io.File

object TestSupport {
    /** The JVM build of libmeshwise.so must exist (cmake target meshwise_jvm). */
    fun nativeAvailable(): Boolean {
        val dir = System.getProperty("fsv.hostLibDir") ?: return false
        return File(dir, "libmeshwise.so").exists()
    }

    /** Spins the main looper until [cond] holds (background work runs on real threads). */
    fun awaitUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
