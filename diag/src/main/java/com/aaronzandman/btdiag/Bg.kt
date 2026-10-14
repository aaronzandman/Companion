package com.aaronzandman.btdiag

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Registers the same kind of background scan Companion uses, so we can see whether Android ever delivers it. */
@SuppressLint("MissingPermission")
object BgTest {
    fun on(c: Context) = P.get(c, "bg") == "1"

    private fun pi(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 7, Intent(c, BgScanReceiver::class.java).setAction("bg"),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    fun start(c: Context, quiet: Boolean = false) {
        stop(c)
        if (!on(c)) return
        val a = P.get(c, "tAddr"); val n = P.get(c, "tName")
        if (a.isBlank() && n.isBlank()) { L.w("BG", "background test needs a target: tap the vLinker in the device list first"); return }
        val ad = Env.adapter(c)
        if (ad == null || !ad.isEnabled) { L.w("BG", "background test can't start: Bluetooth is OFF"); return }
        val sc = ad.bluetoothLeScanner ?: run { L.w("BG", "background test can't start: no LE scanner"); return }
        val filters = mutableListOf<ScanFilter>()
        if (a.isNotBlank()) try { filters += ScanFilter.Builder().setDeviceAddress(a).build() } catch (e: Exception) { L.w("BG", "bad address $a: $e") }
        if (n.isNotBlank()) filters += ScanFilter.Builder().setDeviceName(n).build()
        val lostOk = ad.isOffloadedFilteringSupported
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).apply {
            if (lostOk) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }.build()
        try {
            val r = sc.startScan(filters, settings, pi(c))
            if (r != 0) L.w("BG", "background test registration FAILED: ${WatchService.scanFail(r)}")
            else if (!quiet) L.w("BG", "background test registered (same as Companion) for '$n' $a · low power · " +
                (if (lostOk) "reports appear AND disappear" else "this phone can only report 'appeared', not 'gone'") +
                ". It works even with this app closed.")
        } catch (e: Exception) { L.w("BG", "background test couldn't start: $e") }
    }

    fun stop(c: Context) { try { Env.adapter(c)?.bluetoothLeScanner?.stopScan(pi(c)) } catch (_: Exception) {} }
}

class BgScanReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        L.init(c)
        val err = i.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { L.w("BG", "background scan error from Android: ${WatchService.scanFail(err)}"); return }
        val type = i.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1)
        val list: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            else i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: emptyList()
        val what = when (type) { ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "APPEARED"; ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "GONE"; ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> "seen"; else -> "type $type" }
        val who = list.joinToString("; ") { r -> "${Env.name(r.device).ifBlank { r.scanRecord?.deviceName ?: "(no name)" }} ${r.device.address} ${r.rssi} dBm" }
        L.w("BG", "★ Android woke the app: target $what · $who" + if (WatchService.running) "" else " (app was closed)")
    }
}

/** Logs Bluetooth links even when the watch isn't running (the same broadcast Companion listens for). */
class StaticReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        L.init(c)
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { if (BgTest.on(c)) { L.w("BG", "phone restarted/app updated: re-registering background test"); BgTest.start(c, true) } }
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (!WatchService.running) {
                val d = Env.dev(i)
                L.w("ACL*", "${Env.star(Env.name(d))}LINK ${if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) "UP" else "DOWN"} ${Env.who(d)} (app closed)")
            }
        }
    }
}
