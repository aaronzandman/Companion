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
    fun name(c: Context) = Store.get(c, "vanBleName", "").ifBlank { Store.get(c, "obdName", "") }

    private fun pi(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 41, Intent(c, VanScanReceiver::class.java).setAction("van"),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
    private fun scanner(c: Context): BluetoothLeScanner? = try { c.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner } catch (_: Exception) { null }

    /** Starts (or restarts) the background watch if it's turned on and we know the adapter. */
    @SuppressLint("MissingPermission")
    fun start(c: Context) {
        stop(c)
        if (!on(c)) return
        val a = addr(c); val n = name(c); if (a.isBlank() && n.isBlank()) return
        val sc = scanner(c) ?: return
        val filters = mutableListOf<ScanFilter>()
        if (a.isNotBlank()) try { filters.add(ScanFilter.Builder().setDeviceAddress(a).build()) } catch (_: Exception) {}
        if (n.isNotBlank()) filters.add(ScanFilter.Builder().setDeviceName(n).build())
        val ad = c.getSystemService(BluetoothManager::class.java)?.adapter
        val lostOk = ad?.isOffloadedFilteringSupported == true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).apply {
            if (lostOk) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }.build()
        Store.put(c, "vanLostOk", if (lostOk) "1" else "0")
        try { sc.startScan(filters, settings, pi(c)); Store.put(c, "vanWatchErr", "") } catch (e: Exception) { Store.put(c, "vanWatchErr", e.javaClass.simpleName) }
    }

    @SuppressLint("MissingPermission")
    fun stop(c: Context) { try { scanner(c)?.stopScan(pi(c)) } catch (_: Exception) {} }

    /** Van started (adapter seen after a quiet spell of 3+ minutes). */
    fun vanOn(c: Context) {
        val now = System.currentTimeMillis(); val last = Store.get(c, "vanSeen", "0").toLongOrNull() ?: 0L
        Store.put(c, "vanSeen", now.toString())
        if (now - last < 3 * 60_000L && Store.get(c, "vanPresent", "0") == "1") return
        Store.put(c, "vanPresent", "1")
        AutoDay.onConnected(c, "", name(c).ifBlank { "vLinker" }, force = true)
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
