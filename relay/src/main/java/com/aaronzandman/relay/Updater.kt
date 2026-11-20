package com.aaronzandman.relay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps Next Stop up to date from the Companion GitHub releases: checks for a newer build, downloads it
 * quietly in the background, verifies it's signed with the same key, and offers a one-tap install.
 * Silent when already up to date. All apps share one release; the trailing build number is the version
 * that matters (the release tag ends with the same CI run number as this app's versionName).
 */
object Updater {
    private const val API = "https://api.github.com/repos/AaronZandman/Companion/releases/latest"
    private const val APK = "https://github.com/AaronZandman/Companion/releases/latest/download/NextStop.apk"

    private fun prefs(c: Context) = c.getSharedPreferences("nextstop", Context.MODE_PRIVATE)
    private fun file(c: Context) = File(File(c.cacheDir, "update").apply { mkdirs() }, "NextStop.apk")

    private fun lastNum(s: String): Int = s.removePrefix("v").split(".").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    /** Newer if the release's trailing build number is higher than ours. */
    fun newer(tag: String): Boolean = lastNum(tag) > lastNum(BuildConfig.VERSION_NAME)

    /** The version downloaded and waiting to install, if it's newer than this one. */
    fun ready(c: Context): String? {
        val tag = prefs(c).getString("updReady", "") ?: ""; val f = file(c)
        return if (tag.isNotBlank() && f.exists() && newer(tag)) tag else null
    }

    /** Checks GitHub (at most once per [minGapMs]; 0 = now) and downloads a newer build. Run off the main thread. */
    @Synchronized
    fun check(c: Context, minGapMs: Long = 0L, onProgress: ((Int) -> Unit)? = null): JSONObject {
        val res = JSONObject().put("current", BuildConfig.VERSION_NAME)
        val have = ready(c)
        val last = prefs(c).getString("updChecked", "0")?.toLongOrNull() ?: 0L
        if (minGapMs > 0 && System.currentTimeMillis() - last < minGapMs) {
            return if (have != null) res.put("tag", have).put("ready", true) else res.put("skipped", true)
        }
        prefs(c).edit().putString("updChecked", System.currentTimeMillis().toString()).apply()
        return try {
            val con = URL(API).openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json"); con.connectTimeout = 15_000; con.readTimeout = 15_000
            if (con.responseCode == 403 || con.responseCode == 429) return res.put("error", "busy")
            val tag = JSONObject(con.inputStream.bufferedReader().readText()).optString("tag_name")
            prefs(c).edit().putString("updLatest", tag).apply()
            res.put("tag", tag)
            if (!newer(tag)) return res.put("ready", false).put("upToDate", true)
            if (have == tag) return res.put("ready", true)
            val err = download(c, tag, onProgress)
            if (err == null) res.put("ready", true) else res.put("ready", false).put("error", "download").put("why", err)
        } catch (e: Exception) { res.put("error", e.javaClass.simpleName) }
    }

    private fun download(c: Context, tag: String, onProgress: ((Int) -> Unit)?): String? {
        val tmp = File(file(c).parentFile, "download.tmp")
        try {
            val con = URL(APK).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true; con.connectTimeout = 20_000; con.readTimeout = 60_000
            if (con.responseCode !in 200..299) return "GitHub answered ${con.responseCode}"
            val total = con.contentLengthLong; var got = 0L; var lastPct = -1
            con.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); got += n
                    if (total > 0) { val pct = (got * 100 / total).toInt(); if (pct >= lastPct + 5) { lastPct = pct; onProgress?.invoke(pct) } } }
            } }
            if (total > 0 && got != total) { tmp.delete(); return "The download stopped early" }
            if (!sameSigner(c, tmp)) { tmp.delete(); return "signature check failed" }
            tmp.renameTo(file(c).also { it.delete() })
            prefs(c).edit().putString("updReady", tag).apply()
            return null
        } catch (e: Exception) { tmp.delete(); return "download failed (${e.javaClass.simpleName})" }
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(c: Context, f: File): Boolean = try {
        val pm = c.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val a = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: throw Exception()
            val mine = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            a.packageName == c.packageName &&
                (a.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: emptySet<String>()) ==
                (mine.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: emptySet<String>())
        } else {
            val a = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES) ?: throw Exception()
            val mine = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES)
            a.packageName == c.packageName && a.signatures?.map { it.toCharsString() }?.toSet() == mine.signatures?.map { it.toCharsString() }?.toSet()
        }
    } catch (_: Exception) { false }

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun installIntent(c: Context): Intent {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file(c))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
