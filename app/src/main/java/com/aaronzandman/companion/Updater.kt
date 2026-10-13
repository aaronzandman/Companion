package com.aaronzandman.companion

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
 * Keeps Companion up to date from its GitHub releases: checks for a newer version, downloads it in the background,
 * checks that it's signed with Companion's own key, and then offers it for one-tap install
 * (Android always asks you to confirm installing an app that isn't from the Play Store).
 */
object Updater {
    private const val API = "https://api.github.com/repos/AaronZandman/Companion/releases/latest"
    private const val APK = "https://github.com/AaronZandman/Companion/releases/latest/download/Companion.apk"

    private fun file(c: Context) = File(File(c.cacheDir, "update").apply { mkdirs() }, "Companion.apk")

    fun newer(a: String, b: String): Boolean {
        val x = a.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }; val y = b.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    /** The version downloaded and ready to install, if it's newer than this one. */
    fun ready(c: Context): String? {
        val tag = Store.get(c, "updReady", ""); val f = file(c)
        return if (tag.isNotBlank() && f.exists() && newer(tag, BuildConfig.VERSION_NAME)) tag else null
    }

    /** Checks GitHub (at most every [minGapMs]) and downloads a newer version. Returns a JSON result. Run off the main thread. */
    fun check(c: Context, minGapMs: Long = 0L): JSONObject {
        val res = JSONObject().put("current", BuildConfig.VERSION_NAME)
        ready(c)?.let { return res.put("tag", it).put("ready", true) }
        val last = Store.get(c, "updChecked", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < minGapMs) return res.put("skipped", true)
        Store.put(c, "updChecked", System.currentTimeMillis().toString())
        return try {
            val con = URL(API).openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json"); con.connectTimeout = 15_000; con.readTimeout = 15_000
            val tag = JSONObject(con.inputStream.bufferedReader().readText()).optString("tag_name")
            res.put("tag", tag)
            if (!newer(tag, BuildConfig.VERSION_NAME)) return res.put("ready", false).put("upToDate", true)
            if (download(c, tag)) res.put("ready", true) else res.put("ready", false).put("error", "download")
        } catch (e: Exception) { res.put("error", e.javaClass.simpleName) }
    }

    private fun download(c: Context, tag: String): Boolean {
        val tmp = File(file(c).parentFile, "download.tmp")
        try {
            val con = URL(APK).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = true; con.connectTimeout = 20_000; con.readTimeout = 60_000
            con.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (!sameSigner(c, tmp)) { tmp.delete(); return false }
            tmp.renameTo(file(c).also { it.delete() })
            Store.put(c, "updReady", tag)
            return true
        } catch (_: Exception) { tmp.delete(); return false }
    }

    /** True only if the downloaded file is Companion, signed with the same key as the installed app. */
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

    /** True if Android already lets Companion install its own updates. */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Opens Android's install screen for the downloaded update. */
    fun installIntent(c: Context): Intent {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file(c))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
