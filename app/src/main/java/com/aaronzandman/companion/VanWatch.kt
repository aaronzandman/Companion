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
import android.os.ParcelUuid

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
    /** The vLinker's Bluetooth LE address (FD-IOS), for the standing connection request. */
    fun leAddr(c: Context): String? { val o = Store.get(c, "obdAddr", ""); return addrs(c).firstOrNull { !it.equals(o, true) } }

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
        if (!on(c)) { log(c, "watch off (switch is off)"); return }
        val aa = addrs(c); val nn = names(c)
        val sc = scanner(c) ?: run { log(c, "can't watch: Bluetooth is off"); Store.put(c, "vanWatchErr", "Bluetooth is off"); return }
        val filters = mutableListOf<ScanFilter>()
        for (a in aa) {
            try { filters.add(ScanFilter.Builder().setDeviceAddress(a).build()) } catch (_: Exception) {}
        }
        for (n in nn) filters.add(ScanFilter.Builder().setDeviceName(n).build())
        // vLinker FD-IOS advertises the OBD service 18F0; matches are checked in [accept] so other adapters are ignored.
        filters.add(ScanFilter.Builder().setServiceUuid(SVC).build())
        val ad = c.getSystemService(BluetoothManager::class.java)?.adapter
        val lostOk = ad?.isOffloadedFilteringSupported == true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).apply {
            if (lostOk) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }.build()
        Store.put(c, "vanLostOk", if (lostOk) "1" else "0")
        try {
            val r = sc.startScan(filters, settings, pi(c))
            Store.put(c, "vanWatchErr", if (r == 0) "" else "scan error $r")
            log(c, if (r == 0) "watching for ${(nn + aa).joinToString(", ")} + service 18F0" + (if (lostOk) "" else " (this phone can't report 'gone')") else "watch FAILED to start: error $r")
        } catch (e: Exception) { Store.put(c, "vanWatchErr", e.javaClass.simpleName); log(c, "watch FAILED to start: $e") }
    }

    private val SVC = ParcelUuid.fromString("000018F0-0000-1000-8000-00805F9B34FB")

    /** Is this scan result our vLinker (and not some other adapter that also uses service 18F0)? */
    fun accept(c: Context, r: ScanResult): Boolean {
        val n = (try { r.device.name } catch (_: SecurityException) { null } ?: r.scanRecord?.deviceName ?: "")
        val a = r.device.address ?: ""
        return addrs(c).any { it.equals(a, true) } || names(c).any { it.equals(n, true) } || VL.containsMatchIn(n) ||
            (n.isBlank() && addrs(c).isEmpty() && names(c).isEmpty())
    }

    /** A short history of what the watch saw and did (Settings → Van watch log). */
    @Synchronized fun log(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("M/d h:mm:ss a", java.util.Locale.US).format(java.util.Date())
        val old = Store.get(c, "vanLog", "").lines().filter { it.isNotBlank() }.takeLast(59)
        Store.put(c, "vanLog", (old + "$t  $msg").joinToString("\n"))
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
        if (now - last < 3 * 60_000L && Store.get(c, "vanPresent", "0") == "1") { log(c, "van still on (seen ${(now - last) / 1000}s ago), nothing to do"); return }
        Store.put(c, "vanPresent", "1")
        log(c, "VAN ON via ${via.ifBlank { "vLinker" }}")
        AutoDay.onConnected(c, "", via.ifBlank { name(c).ifBlank { "vLinker" } }, force = true)
        if (Tracker.isRunning(c)) TrackerService.ping(c)   // read the odometer through the adapter now
    }

    /** Adapter stopped advertising (van off; only on phones that can report it). */
    fun vanOff(c: Context) {
        Store.put(c, "vanPresent", "0")
        log(c, "VAN OFF (vLinker gone)")
        AutoDay.onDisconnected(c, "", force = true)
    }
}

/** Android calls this with the background scan's results. */
class VanScanReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(c: Context, i: Intent) {
        val err = i.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { VanWatch.log(c, "Android reported scan error $err"); return }
        val type = i.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        @Suppress("DEPRECATION")
        val list: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            else i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: emptyList()
        val pr = goAsync()
        Thread {
            try {
                val lost = type == ScanSettings.CALLBACK_TYPE_MATCH_LOST
                val ours = list.filter { VanWatch.accept(c, it) }
                val who = list.joinToString("; ") { r -> "${(try { r.device.name } catch (_: Exception) { null }) ?: r.scanRecord?.deviceName ?: "?"} ${r.device.address} ${r.rssi}dBm" }
                VanWatch.log(c, "Android: ${if (lost) "GONE" else "SEEN"} $who" + if (list.isNotEmpty() && ours.isEmpty()) " (not our vLinker, ignored)" else "")
                if (list.isEmpty() || ours.isNotEmpty()) { if (lost) VanWatch.vanOff(c) else VanWatch.vanOn(c) }
            } catch (e: Exception) { VanWatch.log(c, "error: $e") } finally { pr.finish() }
        }.start()
    }
}
