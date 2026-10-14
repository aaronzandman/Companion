package com.aaronzandman.btdiag

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

class Seen(var name: String, val addr: String, val kind: String) {
    var rssi = 0; var first = 0L; var last = 0L; var since = 0L; var count = 0; var present = false; var info = ""
}

/** Watches everything Bluetooth the phone reports and writes it to the log, screen on or off. */
@SuppressLint("MissingPermission")
class WatchService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var startedAt = 0L
        val seen = ConcurrentHashMap<String, Seen>()
        const val CH = "watch"

        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, WatchService::class.java)) }
        fun restartScans(c: Context) { if (running) c.startService(Intent(c, WatchService::class.java).setAction("rescan")) }

        fun scanFail(e: Int) = when (e) {
            1 -> "already started"; 2 -> "app registration failed (too many scans? toggle Bluetooth)"; 3 -> "internal error"
            4 -> "feature unsupported"; 5 -> "out of hardware resources"; 6 -> "scanning too frequently"; else -> "code $e"
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var ticks = 0
    private var openOn = false
    private var targetOn = false

    private val cb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) = onBle(r, "")
        override fun onBatchScanResults(l: MutableList<ScanResult>) { l.forEach { onBle(it, "") } }
        override fun onScanFailed(e: Int) { L.w("BLE", "open scan FAILED: ${scanFail(e)}") }
    }
    private val tcb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) = onBle(r, " (target scan)")
        override fun onBatchScanResults(l: MutableList<ScanResult>) { l.forEach { onBle(it, " (target scan)") } }
        override fun onScanFailed(e: Int) { L.w("BLE", "target scan FAILED: ${scanFail(e)}") }
    }

    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { try { onEvent(i) } catch (e: Exception) { L.w("ERR", "event: $e") } } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        L.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Bluetooth watch", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, note("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, note("Starting…"))
        } catch (e: Exception) { L.w("ERR", "couldn't start the watch: $e (allow Bluetooth permission first)"); stopSelf(); return }
        running = true; startedAt = System.currentTimeMillis()
        wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btdiag:watch").apply { setReferenceCounted(false); acquire(12 * 3600_000L) }
        L.w("WATCH", "===== Watching started =====")
        Env.report(this).forEach { L.w("PHONE", it) }
        val f = IntentFilter().apply {
            listOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED,
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_NAME_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED", "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
            ).forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_EXPORTED)
        startScans()
        h.postDelayed(tick, 15_000)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "rescan") { stopScans(); startScans() }
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        stopScans()
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        try { Env.adapter(this)?.cancelDiscovery() } catch (_: Exception) {}
        try { wl?.release() } catch (_: Exception) {}
        if (running) L.w("WATCH", "===== Watching stopped =====")
        running = false
        super.onDestroy()
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Diagnostic is logging").setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    private fun targetFilters(): List<ScanFilter> {
        val l = mutableListOf<ScanFilter>()
        val a = P.get(this, "tAddr"); val n = P.get(this, "tName")
        if (a.isNotBlank()) try { l += ScanFilter.Builder().setDeviceAddress(a).build() } catch (_: Exception) {}
        if (n.isNotBlank()) l += ScanFilter.Builder().setDeviceName(n).build()
        return l
    }

    private fun startScans() {
        val a = Env.adapter(this)
        val sc = if (a != null && a.isEnabled) a.bluetoothLeScanner else null
        if (sc == null) { L.w("BLE", "can't scan: Bluetooth is OFF"); return }
        val fast = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(null, fast, cb); openOn = true; L.w("BLE", "open scan started (Android only delivers this while the screen is on)") }
        catch (e: Exception) { L.w("BLE", "open scan couldn't start: $e") }
        val tf = targetFilters()
        if (tf.isNotEmpty()) try { sc.startScan(tf, fast, tcb); targetOn = true; L.w("BLE", "target scan started for ${P.get(this, "tName")} ${P.get(this, "tAddr")} (keeps working with the screen off)") }
        catch (e: Exception) { L.w("BLE", "target scan couldn't start: $e") }
        else L.w("BLE", "no target chosen yet: pick the vLinker in the device list so it's watched with the screen off too")
    }

    private fun stopScans() {
        val sc = try { Env.adapter(this)?.bluetoothLeScanner } catch (_: Exception) { null }
        if (openOn) try { sc?.stopScan(cb) } catch (_: Exception) {}
        if (targetOn) try { sc?.stopScan(tcb) } catch (_: Exception) {}
        openOn = false; targetOn = false
    }

    private fun onBle(r: ScanResult, via: String) {
        val d = r.device
        val name = Env.name(d).ifBlank { r.scanRecord?.deviceName ?: "" }
        val now = System.currentTimeMillis()
        val s = seen.getOrPut("LE " + d.address) { Seen(name, d.address, "LE").also { it.first = now } }
        if (name.isNotBlank()) s.name = name
        s.rssi = r.rssi; s.last = now; s.count++
        if (!s.present) {
            s.present = true; s.since = now
            val rec = r.scanRecord
            val uu = rec?.serviceUuids?.joinToString(",") { it.uuid.toString().take(8) } ?: ""
            val mf = rec?.manufacturerSpecificData?.let { sa -> (0 until sa.size()).joinToString(",") { "0x%04X".format(sa.keyAt(it)) } } ?: ""
            val conn = if (Build.VERSION.SDK_INT >= 26) (if (r.isConnectable) "connectable" else "not connectable") else ""
            s.info = listOf(conn, if (uu.isNotBlank()) "services $uu" else "", if (mf.isNotBlank()) "maker $mf" else "").filter { it.isNotBlank() }.joinToString(" · ")
            val back = if (s.count > 1) " (back)" else ""
            L.w("BLE", "${Env.star(s.name)}APPEARED$back ${s.name.ifBlank { "(no name)" }} ${d.address} ${Env.type(d)} · ${r.rssi} dBm · ${s.info}$via")
        }
    }

    private fun onClassic(d: BluetoothDevice?, rssi: Short) {
        d ?: return
        val now = System.currentTimeMillis(); val name = Env.name(d)
        val s = seen.getOrPut("BR " + d.address) { Seen(name, d.address, "classic").also { it.first = now } }
        if (name.isNotBlank()) s.name = name
        val again = s.last != 0L && now - s.last < 5 * 60_000L
        s.rssi = rssi.toInt(); s.last = now; s.count++; s.present = true
        if (!again) L.w("CLASSIC", "${Env.star(s.name)}FOUND ${Env.who(d)} · $rssi dBm · ${Env.connected(d)}")
    }

    private fun onEvent(i: Intent) {
        val d = Env.dev(i); val w = Env.who(d); val st = Env.star(Env.name(d))
        fun stName(v: Int) = when (v) { 0 -> "disconnected"; 1 -> "connecting"; 2 -> "CONNECTED"; 3 -> "disconnecting"; else -> "state $v" }
        when (i.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> L.w("ACL", "${st}LINK UP $w (the phone connected to it)")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> L.w("ACL", "${st}LINK DOWN $w")
            BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> L.w("ACL", "${st}disconnect requested $w")
            BluetoothDevice.ACTION_FOUND -> onClassic(d, i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE))
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> L.w("PAIR", "$st$w → " + when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0)) { 10 -> "not paired"; 11 -> "pairing…"; 12 -> "PAIRED"; else -> "?" })
            BluetoothDevice.ACTION_NAME_CHANGED -> if (Env.interesting(Env.name(d))) L.w("NAME", "$st$w")
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val v = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)
                L.w("BT", "Bluetooth " + when (v) { 10 -> "OFF"; 11 -> "turning on"; 12 -> "ON"; 13 -> "turning off"; else -> "state $v" })
                if (v == 12) h.postDelayed({ stopScans(); startScans() }, 1500)
            }
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> L.w("BT", "phone Bluetooth connection: ${stName(i.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1))} $w")
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> L.w("AUDIO", "media audio ${stName(i.getIntExtra("android.bluetooth.profile.extra.STATE", -1))} $w")
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> L.w("AUDIO", "phone calls ${stName(i.getIntExtra("android.bluetooth.profile.extra.STATE", -1))} $w")
            Intent.ACTION_SCREEN_ON -> L.w("PHONE", "screen ON")
            Intent.ACTION_SCREEN_OFF -> L.w("PHONE", "screen OFF (open scan pauses; target scan and background test keep going)")
            Intent.ACTION_POWER_CONNECTED -> L.w("PHONE", "charging started (plugged in)")
            Intent.ACTION_POWER_DISCONNECTED -> L.w("PHONE", "charging stopped (unplugged)")
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> L.w("PHONE", "Doze " + if (getSystemService(PowerManager::class.java).isDeviceIdleMode) "ON (phone asleep)" else "off")
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> L.w("PHONE", "battery saver " + if (getSystemService(PowerManager::class.java).isPowerSaveMode) "ON" else "off")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            val now = System.currentTimeMillis()
            for (s in seen.values) if (s.kind == "LE" && s.present && now - s.last > 45_000) {
                s.present = false
                L.w("BLE", "${Env.star(s.name)}GONE ${s.name.ifBlank { "(no name)" }} ${s.addr} · was around ${(s.last - s.since) / 1000}s · last ${s.rssi} dBm")
            }
            if (ticks % 12 == 0) try { val a = Env.adapter(this@WatchService); if (a?.isEnabled == true && !a.isDiscovering) a.startDiscovery() } catch (_: Exception) {}
            if (ticks % 20 == 0) {
                val here = seen.values.count { it.kind == "LE" && it.present }
                val t = targetSeen()
                L.w("WATCH", "still watching · $here LE devices around · screen ${if (getSystemService(PowerManager::class.java).isInteractive) "on" else "off"}" + (if (t != null) " · target last seen ${(now - t.last) / 1000}s ago" else ""))
            }
            if (ticks % 80 == 0) { stopScans(); startScans() }   // Android quietly weakens scans left running for 30 min
            try {
                val here = seen.values.count { it.present }
                getSystemService(NotificationManager::class.java).notify(1, note("$here devices around · ${L.recent(1).firstOrNull()?.substringAfter("] ")?.take(60) ?: ""}"))
            } catch (_: Exception) {}
            h.postDelayed(this, 15_000)
        }
    }

    private fun targetSeen(): Seen? {
        val a = P.get(this, "tAddr"); val n = P.get(this, "tName")
        return seen.values.filter { (a.isNotBlank() && it.addr == a) || (n.isNotBlank() && it.name == n) }.maxByOrNull { it.last }
    }
}
