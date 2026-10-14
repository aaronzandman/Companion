package com.aaronzandman.companion

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics recorder (Settings → Diagnostics): while it's on, Companion writes down every Bluetooth event it hears,
 * every decision it makes about your day and why, and what the timer was doing, so a problem can be traced afterwards.
 * Stays on this phone; you copy it yourself.
 */
object DiagLog {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    fun on(c: Context) = Store.get(c, "diagRec", "0") == "1"
    private fun file(c: Context) = File(c.applicationContext.filesDir, "diag.log")

    @Synchronized fun w(c: Context, tag: String, msg: String) {
        if (!on(c)) return
        try {
            val f = file(c)
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            if (f.length() > 2_000_000) f.writeText(f.readLines().takeLast(8000).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }

    /** Where the call came from (to see who ended the day). */
    fun caller(): String = Throwable().stackTrace.drop(2).filter { it.className.contains("companion") }.take(4)
        .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    fun start(c: Context) {
        Store.put(c, "diagRec", "1"); Store.put(c, "diagSince", System.currentTimeMillis().toString())
        w(c, "REC", "===== recording started · Companion ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} =====")
        snapshot(c)
    }
    fun stop(c: Context) { snapshot(c); w(c, "REC", "===== recording stopped ====="); Store.put(c, "diagRec", "0") }
    @Synchronized fun clear(c: Context) { try { file(c).delete() } catch (_: Exception) {}; if (on(c)) w(c, "REC", "===== log cleared, still recording =====") }
    fun text(c: Context): String = try { file(c).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    fun lines(c: Context): Int = try { file(c).takeIf { it.exists() }?.useLines { it.count() } ?: 0 } catch (_: Exception) { 0 }

    private fun yn(b: Boolean) = if (b) "yes" else "NO"
    private fun ok(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /** The settings and permissions that decide what happens. */
    fun snapshot(c: Context) {
        fun g(k: String, d: String = "") = Store.get(c, k, d)
        w(c, "SET", "auto start on Bluetooth ${g("autoStartBt", "0")} · device ${g("autoStartName").ifBlank { "-" }} ${g("autoStartAddr")} · driving=connected ${g("autoBtDrive", "0")} · end at home ${g("autoEndHome", "0")} · home saved ${yn(Tracker.home(c) != null)}")
        w(c, "SET", "vLinker watch ${g("vanWatch", "0")} · adapter ${g("obdName").ifBlank { "-" }} ${g("obdAddr")} (${g("obdType", "auto")}) · LE ${VanWatch.leAddr(c) ?: "-"} · van Bluetooth ${g("vanAclName").ifBlank { "-" }} ${g("vanAclAddr")}")
        w(c, "SET", "standby ${g("standby", "1")} · resume after restart ${g("autoBoot", "1")} · toast ${g("dayToast", "1")}")
        val pw = c.getSystemService(PowerManager::class.java)
        val bt = Build.VERSION.SDK_INT < 31 || (ok(c, Manifest.permission.BLUETOOTH_CONNECT) && ok(c, Manifest.permission.BLUETOOTH_SCAN))
        val bg = Build.VERSION.SDK_INT < 29 || ok(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < 33 || ok(c, Manifest.permission.POST_NOTIFICATIONS)
        val alarms = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        w(c, "SET", "permissions: location ${yn(ok(c, Manifest.permission.ACCESS_FINE_LOCATION))} · all the time ${yn(bg)} · Bluetooth ${yn(bt)} · notifications ${yn(notif)} · alarms ${yn(alarms)} · unrestricted battery ${yn(pw.isIgnoringBatteryOptimizations(c.packageName))}")
        w(c, "SET", "timer: ${dayState(c)}")
    }

    fun dayState(c: Context): String {
        val s = Tracker.state(c)
        if (!s.optBoolean("running")) return "no day running"
        val cur = s.optJSONObject("cur")
        val since = (System.currentTimeMillis() - s.optLong("dayStart")) / 1000
        return "day running ${since / 60}m${since % 60}s · now ${cur?.optString("type") ?: "?"}${cur?.optString("store")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""} · left home ${if (s.optLong("dep") > 0) "yes" else "no"} · reached a store ${yn(s.optBoolean("reached"))}"
    }
}
