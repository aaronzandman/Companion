package com.aaronzandman.btdiag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The log: kept in memory for the screen and appended to a file for export. */
object L {
    private var ctx: Context? = null
    private val buf = ArrayDeque<String>()
    @Volatile var version = 0
    private var loaded = false
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) { if (ctx == null) ctx = c.applicationContext }
    fun file(c: Context) = File(c.filesDir, "diag.log")

    @Synchronized private fun load() {
        if (loaded) return; loaded = true
        val c = ctx ?: return
        try { val f = file(c); if (f.exists()) f.readLines().takeLast(600).forEach { buf.addLast(it) } } catch (_: Exception) {}
    }

    @Synchronized fun w(tag: String, msg: String) {
        load()
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line); while (buf.size > 600) buf.removeFirst()
        val c = ctx
        if (c != null) try {
            val f = file(c); f.appendText(line + "\n")
            if (f.length() > 3_000_000) f.writeText(f.readLines().takeLast(15000).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
        version++
    }

    @Synchronized fun recent(n: Int): List<String> { load(); return buf.toList().takeLast(n) }
    @Synchronized fun clear(c: Context) { buf.clear(); loaded = true; try { file(c).delete() } catch (_: Exception) {}; version++ }
}

object P {
    private fun sp(c: Context) = c.getSharedPreferences("diag", Context.MODE_PRIVATE)
    fun get(c: Context, k: String) = sp(c).getString(k, "") ?: ""
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
}

@SuppressLint("MissingPermission")
object Env {
    const val COMPANION = "com.aaronzandman.companion"

    fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun btPerms(c: Context) = if (Build.VERSION.SDK_INT >= 31)
        granted(c, Manifest.permission.BLUETOOTH_SCAN) && granted(c, Manifest.permission.BLUETOOTH_CONNECT)
        else granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun adapter(c: Context): BluetoothAdapter? = c.getSystemService(BluetoothManager::class.java)?.adapter

    fun name(d: BluetoothDevice?): String = try { d?.name ?: "" } catch (_: Exception) { "" }
    fun type(d: BluetoothDevice?): String = try {
        when (d?.type) { BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"; BluetoothDevice.DEVICE_TYPE_LE -> "LE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"; else -> "unknown-type" }
    } catch (_: Exception) { "?" }
    fun who(d: BluetoothDevice?): String = if (d == null) "(no device)" else "${name(d).ifBlank { "(no name)" }} ${d.address} ${type(d)}"
    fun interesting(name: String) = Regex("vlink|vgate|v-link|obd|elm|ios-vl|android-vl", RegexOption.IGNORE_CASE).containsMatchIn(name)
    fun star(name: String) = if (interesting(name)) "★ " else ""

    @Suppress("DEPRECATION")
    fun dev(i: Intent): BluetoothDevice? = try {
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    } catch (_: Exception) { null }

    fun connected(d: BluetoothDevice): String = try {
        if (d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true) "CONNECTED" else "not connected"
    } catch (_: Exception) { "connection ?" }

    private fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "NO"; null -> "?" }

    fun perms(c: Context): String {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += "scan " + yn(granted(c, Manifest.permission.BLUETOOTH_SCAN))
            l += "connect " + yn(granted(c, Manifest.permission.BLUETOOTH_CONNECT))
        }
        l += "location " + yn(granted(c, Manifest.permission.ACCESS_FINE_LOCATION))
        if (Build.VERSION.SDK_INT >= 33) l += "notifications " + yn(granted(c, Manifest.permission.POST_NOTIFICATIONS))
        return l.joinToString(" · ")
    }

    fun report(c: Context): List<String> {
        val out = mutableListOf<String>()
        val pm = c.packageManager
        out += "BT Diagnostic ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val a = adapter(c)
        if (a == null) out += "Bluetooth: no adapter"
        else out += "Bluetooth: ${if (a.isEnabled) "ON" else "OFF"} · LE ${yn(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))}" +
            " · hardware scan filters ${yn(a.isOffloadedFilteringSupported)} · hardware batching ${yn(a.isOffloadedScanBatchingSupported)}" +
            " · extended adv ${yn(a.isLeExtendedAdvertisingSupported)}"
        val lm = c.getSystemService(LocationManager::class.java)
        val locOn = try { if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled else lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
        out += "Location services: ${if (locOn == true) "ON" else if (locOn == false) "OFF (some phones give no Bluetooth scan results when off)" else "?"}"
        val pw = c.getSystemService(PowerManager::class.java)
        out += "Battery saver ${yn(pw.isPowerSaveMode)} · idle (Doze) ${yn(pw.isDeviceIdleMode)} · screen on ${yn(pw.isInteractive)}"
        out += "This app: ${perms(c)} · unrestricted battery ${yn(pw.isIgnoringBatteryOptimizations(c.packageName))}"
        try {
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(COMPANION, PackageManager.GET_PERMISSIONS)
            val req = pi.requestedPermissions ?: emptyArray()
            val flags = pi.requestedPermissionsFlags ?: IntArray(0)
            val g = HashMap<String, Boolean>()
            req.forEachIndexed { i, p -> g[p] = i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
            fun s(p: String) = g[p]?.let { yn(it) } ?: "n/a"
            out += "Companion ${pi.versionName}: Bluetooth scan ${s(Manifest.permission.BLUETOOTH_SCAN)} · connect ${s(Manifest.permission.BLUETOOTH_CONNECT)}" +
                " · location ${s(Manifest.permission.ACCESS_FINE_LOCATION)} · background location ${s("android.permission.ACCESS_BACKGROUND_LOCATION")}" +
                " · notifications ${s("android.permission.POST_NOTIFICATIONS")} · unrestricted battery ${yn(pw.isIgnoringBatteryOptimizations(COMPANION))}"
        } catch (_: PackageManager.NameNotFoundException) { out += "Companion: not installed" } catch (e: Exception) { out += "Companion: couldn't check (${e.javaClass.simpleName})" }
        if (a != null && btPerms(c)) {
            try {
                val bonded = a.bondedDevices ?: emptySet()
                out += "Paired devices (${bonded.size}):"
                bonded.sortedBy { if (interesting(name(it))) 0 else 1 }.forEach { d ->
                    val uu = try { d.uuids?.joinToString(",") { it.uuid.toString().take(8) } ?: "" } catch (_: Exception) { "" }
                    out += "  ${star(name(d))}${who(d)} · ${connected(d)}" + (if (uu.isNotBlank()) " · services $uu" else "")
                }
            } catch (e: Exception) { out += "Paired devices: couldn't read (${e.javaClass.simpleName})" }
            try {
                val gatt = c.getSystemService(BluetoothManager::class.java)?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
                out += "Connected Bluetooth LE devices: " + if (gatt.isEmpty()) "none" else gatt.joinToString("; ") { who(it) }
            } catch (_: Exception) {}
        } else out += "Paired devices: allow Bluetooth permission to list them"
        out += "Target: " + (P.get(c, "tName").ifBlank { "(none)" }) + " " + P.get(c, "tAddr") +
            " · background test ${if (P.get(c, "bg") == "1") "ON" else "off"}"
        return out
    }
}
