package io.github.doesthings.meshwise.data

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A release on GitHub that is newer than the installed app. */
data class UpdateInfo(
    val version: String,
    /** The APK for this device when the release has one, otherwise the release page. */
    val downloadUrl: String,
    val pageUrl: String,
)

/**
 * The optional update check. It is the only network request Meshwise makes, it only runs when
 * the user turned it on (or taps "Check now"), and it sends nothing but a plain GET for the
 * latest release of the public repository.
 */
object Updates {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/doesthings/Meshwise/releases/latest"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** "v1.2.3", "1.2.3" or "1.2.3-debug" → [1, 2, 3]; null when there is no version number. */
    fun parseVersion(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        val parts = core.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True when [candidate] is a higher version than [current]; unparsable versions never are. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Reads GitHub's "latest release" JSON. Returns the release if it is newer than
     * [currentVersion], or null. Drafts and pre-releases never count.
     */
    fun parseLatest(json: String, currentVersion: String, abis: List<String> = Build.SUPPORTED_ABIS.toList()): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        if (!isNewer(tag, currentVersion)) return null
        val version = tag.removePrefix("v").removePrefix("V")
        val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/doesthings/Meshwise/releases/latest"
        val apks = buildMap {
            val assets = o.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk") && url.startsWith("https://github.com/")) put(name, url)
            }
        }
        // Same naming as the release workflow: Meshwise-<version>-<abi>.apk
        val download = (abis + "universal").firstNotNullOfOrNull { abi -> apks["Meshwise-$version-$abi.apk"] } ?: page
        return UpdateInfo(version, download, page)
    }

    /** Asks GitHub for the latest release. Blocking; throws IOException when GitHub can't be reached. */
    fun fetchLatest(currentVersion: String): UpdateInfo? {
        val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Meshwise")
            return when (val code = conn.responseCode) {
                200 -> parseLatest(conn.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                404 -> null // no published release yet
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
