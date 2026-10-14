package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The phone never connects to an OBD adapter by itself, so Companion watches for the vLinker instead:
 * a low-power Bluetooth LE scan that Android runs in the background (even with Companion closed) and that
 * wakes Companion when the adapter starts advertising, i.e. when the van is started.
 */
object VanWatch {
    fun on(c: Context) = Store.get(c, "vanWatch", "0") == "1"
    /** The adapter's Bluetooth LE address/name: found with "Find my vLinker", or the adapter chosen in Van → Adapter. */
    fun addr(c: Context) = Store.get(c, "vanBleAddr", "").ifBlank { Store.get(c, "obdAddr", "") }
    fun name(c: Context) = names(c).firstOrNull() ?: ""

    private val VL = Regex("vlinker", RegexOption.IGNORE_CASE)
    /**
     * The vLinker FD+ is two radios: "vLinker FD-Android" (Bluetooth 3.0, the one you pair and read through, never advertises)
     * and "vLinker FD-IOS" (Bluetooth LE, advertises whenever the van is on). Its LE address is the classic one with the
     * first byte one higher (DC:0D:… → DD:0D:…). We watch for the LE twin of whatever adapter you picked.
     */
    fun names(c: Context): List<String> {
        val l = mutableListOf(Store.get(c, "vanBleName", ""))
        val o = Store.get(c, "obdName", "")
        if (VL.containsMatchIn(o)) l += o.replace(Regex("android", RegexOption.IGNORE_CASE), "IOS")
        l += o
        return l.filter { it.isNotBlank() }.distinct()
    }
    fun addrs(c: Context): List<String> {
        val l = mutableListOf(Store.get(c, "vanBleAddr", ""))
        val o = Store.get(c, "obdAddr", "").uppercase()
        if (VL.containsMatchIn(Store.get(c, "obdName", "")) && Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(o)) {
            val first = (o.substring(0, 2).toInt(16) + 1) and 0xFF
            l += "%02X".format(first) + o.substring(2)
        }
        l += o
        return l.filter { it.isNotBlank() }.distinct()
    }

    private fun pi(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 41, Intent(c, VanScanReceiver::class.java).setAction("van"),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
    private fun scanner(c: Context): BluetoothLeScanner? = try { c.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner } catch (_: Exception) { null }

    /** Starts (or restarts) the background watch if it's turned on and we know the adapter. */
    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        stop(c)
        if (!on(c)) return
        val aa = addrs(c); val nn = names(c); if (aa.isEmpty() && nn.isEmpty()) return
        val sc = scanner(c) ?: return
        val filters = mutableListOf<ScanFilter>()
        for (a in aa) try { filters.add(ScanFilter.Builder().setDeviceAddress(a).build()) } catch (_: Exception) {}
        for (n in nn) filters.add(ScanFilter.Builder().setDeviceName(n).build())
        val ad = c.getSystemService(BluetoothManager::class.java)?.adapter
        val lostOk = ad?.isOffloadedFilteringSupported == true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).apply {
            if (lostOk) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }.build()
        Store.put(c, "vanLostOk", if (lostOk) "1" else "0")
        try { val r = sc.startScan(filters, settings, pi(c)); Store.put(c, "vanWatchErr", if (r == 0) "" else "scan error $r") } catch (e: Exception) { Store.put(c, "vanWatchErr", e.javaClass.simpleName) }
    }

    @SuppressLint("MissingPermission")
    fun stop(c: Context) { try { scanner(c)?.stopScan(pi(c)) } catch (_: Exception) {} }

    /** The van's own Bluetooth (e.g. "Ford Transit") briefly links to the phone when the van starts: another "van on" signal. */
    fun aclAddr(c: Context) = Store.get(c, "vanAclAddr", "")

    /** Every Bluetooth link Android reports, newest first, so you can pick the van's own Bluetooth from a list. */
    fun noteAcl(c: Context, addr: String, name: String) {
        val now = System.currentTimeMillis()
        val old = try { org.json.JSONArray(Store.get(c, "recentAcl", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        val out = org.json.JSONArray().put(org.json.JSONObject().put("addr", addr).put("name", name).put("t", now))
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("addr") != addr && out.length() < 10) out.put(o) }
        Store.put(c, "recentAcl", out.toString())
    }

    /** Van started (adapter seen after a quiet spell of 3+ minutes). */
    fun vanOn(c: Context, via: String = "") {
        val now = System.currentTimeMillis(); val last = Store.get(c, "vanSeen", "0").toLongOrNull() ?: 0L
        Store.put(c, "vanSeen", now.toString())
        if (now - last < 3 * 60_000L && Store.get(c, "vanPresent", "0") == "1") return
        Store.put(c, "vanPresent", "1")
        AutoDay.onConnected(c, "", via.ifBlank { name(c).ifBlank { "vLinker" } }, force = true)
        if (Tracker.isRunning(c)) TrackerService.ping(c)   // read the odometer through the adapter now
    }

    /** Adapter stopped advertising (van off; only on phones that can report it). */
    fun vanOff(c: Context) {
        Store.put(c, "vanPresent", "0")
        AutoDay.onDisconnected(c, "", force = true)
    }
}

/** Android calls this with the background scan's results. */
class VanScanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
        val type = i.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        val pr = goAsync()
        Thread { try { if (type == ScanSettings.CALLBACK_TYPE_MATCH_LOST) VanWatch.vanOff(c) else VanWatch.vanOn(c) } catch (_: Exception) {} finally { pr.finish() } }.start()
    }
}
