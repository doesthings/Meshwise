package io.github.doesthings.meshwise.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What changed in one release, as cleaned-up Markdown. */
data class ReleaseNotes(val version: String, val text: String)

/** A release on GitHub that is newer than the installed app. */
data class UpdateInfo(
    val version: String,
    /** The APK for this device when the release has one, otherwise the release page. */
    val downloadUrl: String,
    val pageUrl: String,
    val apkName: String? = null,
    val apkSize: Long = 0,
    val checksumsUrl: String? = null,
    /** Notes of every release newer than the installed one, newest first. */
    val notes: List<ReleaseNotes> = emptyList(),
) {
    val canInstall: Boolean get() = apkName != null
}

/**
 * The update check: one plain GET for the repository's recent releases (nothing is sent but
 * the request itself), and, when the user taps Update, the APK download.
 */
object Updates {
    const val RELEASES_API = "https://api.github.com/repos/doesthings/Meshwise/releases?per_page=20"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val RELEASES_PAGE = "https://github.com/doesthings/Meshwise/releases/latest"

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

    private fun compare(a: String, b: String): Int = when {
        isNewer(a, b) -> 1
        isNewer(b, a) -> -1
        else -> 0
    }

    /**
     * Reads GitHub's release list. Returns the newest published release if it is newer than
     * [currentVersion] (with the notes of every newer release), or null. Drafts and
     * pre-releases never count.
     */
    fun parseReleases(json: String, currentVersion: String, abis: List<String> = Build.SUPPORTED_ABIS.toList()): UpdateInfo? {
        val arr = JSONArray(json)
        val newer = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && isNewer(it.optString("tag_name"), currentVersion) }
            .sortedWith { x, y -> compare(y.optString("tag_name"), x.optString("tag_name")) }
        val latest = newer.firstOrNull() ?: return null
        return release(latest, abis).copy(
            notes = newer.map { ReleaseNotes(versionOf(it), cleanNotes(it.optString("body"))) }.filter { it.text.isNotBlank() },
        )
    }

    /** One release object (as in the list or GitHub's "latest release"); null if not newer. */
    fun parseLatest(json: String, currentVersion: String, abis: List<String> = Build.SUPPORTED_ABIS.toList()): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease") || !isNewer(o.optString("tag_name"), currentVersion)) return null
        return release(o, abis).copy(notes = listOf(ReleaseNotes(versionOf(o), cleanNotes(o.optString("body")))).filter { it.text.isNotBlank() })
    }

    private fun versionOf(o: JSONObject) = o.optString("tag_name").removePrefix("v").removePrefix("V")

    private fun release(o: JSONObject, abis: List<String>): UpdateInfo {
        val version = versionOf(o)
        val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: RELEASES_PAGE
        data class Asset(val url: String, val size: Long)
        val assets = buildMap {
            val list = o.optJSONArray("assets")
            for (i in 0 until (list?.length() ?: 0)) {
                val a = list!!.getJSONObject(i)
                val url = a.optString("browser_download_url")
                if (url.startsWith("https://github.com/")) put(a.optString("name"), Asset(url, a.optLong("size")))
            }
        }
        // Same naming as the release workflow: Meshwise-<version>-<abi>.apk
        val apk = (abis + "universal").map { "Meshwise-$version-$it.apk" }.firstOrNull { it in assets }
        return UpdateInfo(
            version = version,
            downloadUrl = apk?.let { assets.getValue(it).url } ?: page,
            pageUrl = page,
            apkName = apk,
            apkSize = apk?.let { assets.getValue(it).size } ?: 0,
            checksumsUrl = assets["SHA256SUMS.txt"]?.url,
        )
    }

    /**
     * Keeps what changed: drops the banner image, the download table and checksum help that
     * every release carries, the support line and the quoted note.
     */
    fun cleanNotes(body: String): String {
        val out = mutableListOf<String>()
        for (raw in body.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.startsWith("### Which APK") || t.startsWith("### Verify the download")) break
            if (t.startsWith("![") || t.startsWith(">")) continue
            if (t.contains("ko-fi", ignoreCase = true) || t.contains("/donate", ignoreCase = true)) continue
            val heading = Regex("^#{1,3}\\s*Meshwise\\s+v?[0-9.]+\\s*:?\\s*(.*)$").find(t)
            if (heading != null) {
                val rest = heading.groupValues[1].trim()
                if (rest.isNotEmpty()) out += "### $rest"
                continue
            }
            if (t.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Meshwise")
        }

    /** Asks GitHub for recent releases. Blocking; throws IOException when GitHub can't be reached. */
    fun fetchLatest(currentVersion: String): UpdateInfo? {
        val conn = open(RELEASES_API, 10_000)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            return when (val code = conn.responseCode) {
                200 -> parseReleases(conn.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                404 -> null
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads the update's APK to [target], reporting (bytes, total). When the release lists
     * checksums, the file must match them. Blocking; throws on failure.
     */
    fun download(info: UpdateInfo, target: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val name = info.apkName ?: throw IOException("This release has no APK for this device")
        val expected = info.checksumsUrl?.let { url ->
            val conn = open(url, 15_000)
            try {
                if (conn.responseCode != 200) throw IOException("Checksums unavailable (${conn.responseCode})")
                conn.inputStream.bufferedReader().use { it.readText() }.lines()
                    .map { it.trim().split(Regex("\\s+")) }
                    .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == name }
                    ?.first()?.lowercase()
            } finally {
                conn.disconnect()
            }
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(info.downloadUrl, 20_000)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        progress(read, total)
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            part.delete()
            throw IOException("The download is damaged (checksum mismatch)")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save the update")
    }
}
