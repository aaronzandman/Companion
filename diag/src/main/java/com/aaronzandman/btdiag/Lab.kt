package com.aaronzandman.btdiag

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import java.util.UUID

/**
 * Auto-connect lab: runs each way Companion could notice the van on its own, side by side, and logs every step.
 *  LE-REQ   a standing connection request to the vLinker's LE side (Android connects when it wakes up)
 *  CHECK    an alarm every minute that connects over Bluetooth 3.0 and reads the voltage (like Test connect)
 *  SCAN-*   background scans filtered by name, by address, and by the OBD service 18F0
 */
@SuppressLint("MissingPermission")
object Lab {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var armedAt = 0L
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val SVC = ParcelUuid.fromString("000018F0-0000-1000-8000-00805F9B34FB")
    private val VL = Regex("vlink|vgate", RegexOption.IGNORE_CASE)

    fun on(c: Context) = P.get(c, "lab") == "1"
    fun screen(c: Context) = if (c.getSystemService(PowerManager::class.java).isInteractive) "screen on" else "screen OFF"

    /** Finds the vLinker's two sides: the paired Bluetooth 3.0 one (FD-Android) and its LE twin (FD-IOS). */
    fun findTargets(c: Context) {
        val ad = Env.adapter(c) ?: return
        val bonded = try { ad.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val classic = bonded.firstOrNull { VL.containsMatchIn(Env.name(it)) && it.type != BluetoothDevice.DEVICE_TYPE_LE }
        if (classic != null) { P.put(c, "cAddr", classic.address); P.put(c, "cName", Env.name(classic)) }
        val seenLe = WatchService.seen.values.filter { it.kind == "LE" && VL.containsMatchIn(it.name) }.maxByOrNull { it.last }
        if (seenLe != null) { P.put(c, "lAddr", seenLe.addr); P.put(c, "lName", seenLe.name) }
        else if (classic != null) {
            val a = classic.address.uppercase()
            P.put(c, "lAddr", "%02X".format((a.substring(0, 2).toInt(16) + 1) and 0xFF) + a.substring(2))
            P.put(c, "lName", Env.name(classic).replace(Regex("android", RegexOption.IGNORE_CASE), "IOS"))
        }
        L.w("LAB", "targets: Bluetooth 3.0 = ${P.get(c, "cName").ifBlank { "(none found — pair the vLinker)" }} ${P.get(c, "cAddr")} · LE = ${P.get(c, "lName").ifBlank { "(none)" }} ${P.get(c, "lAddr")}")
    }

    fun start(c: Context) {
        P.put(c, "lab", "1"); P.put(c, "checkN", "0")
        findTargets(c)
        L.w("LAB", "alarm permission (exact every-minute checks): ${if (exactOk(c)) "allowed" else "NOT allowed — checks may run late"}")
        arm(c); scans(c); schedule(c, 3_000L)
    }

    fun stop(c: Context) {
        P.put(c, "lab", "0")
        disarm(); h.removeCallbacksAndMessages(null)
        try { c.getSystemService(AlarmManager::class.java).cancel(alarmPi(c)) } catch (_: Exception) {}
        val sc = try { Env.adapter(c)?.bluetoothLeScanner } catch (_: Exception) { null }
        for (v in VARIANTS) try { sc?.stopScan(scanPi(c, v)) } catch (_: Exception) {}
        L.w("LAB", "lab stopped")
    }

    // ---------- LE-REQ: standing connection request ----------
    fun arm(c: Context) {
        disarm()
        if (!on(c)) return
        val a = P.get(c, "lAddr"); if (a.isBlank()) { L.w("LE-REQ", "no LE address — skipped"); return }
        val ad = Env.adapter(c) ?: return
        val app = c.applicationContext
        try {
            val dev = if (Build.VERSION.SDK_INT >= 33) ad.getRemoteLeDevice(a, BluetoothDevice.ADDRESS_TYPE_RANDOM) else ad.getRemoteDevice(a)
            armedAt = System.currentTimeMillis()
            gatt = dev.connectGatt(app, true, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                    val s = (System.currentTimeMillis() - armedAt) / 1000
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        L.w("LE-REQ", "★ CONNECTED ${s}s after arming (${screen(app)}) — this is the van-on signal. Hanging up, re-arming in 30 s.")
                        try { g.disconnect(); g.close() } catch (_: Exception) {}
                        if (gatt === g) gatt = null
                        h.postDelayed({ arm(app) }, 30_000L)
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && gatt === g) {
                        L.w("LE-REQ", "request ended after ${s}s, status $status${if (status == 133) " (generic failure)" else ""} — re-arming in 30 s")
                        try { g.close() } catch (_: Exception) {}
                        gatt = null
                        h.postDelayed({ arm(app) }, 30_000L)
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            L.w("LE-REQ", "armed: waiting for $a to wake up (random address type)")
        } catch (e: Exception) { L.w("LE-REQ", "couldn't arm: $e") }
    }
    private fun disarm() { val g = gatt; gatt = null; if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {} }

    // ---------- CHECK: every minute over Bluetooth 3.0 ----------
    fun exactOk(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun alarmPi(c: Context) = PendingIntent.getBroadcast(c, 21, Intent(c, LabAlarmReceiver::class.java).setAction("check"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, delay: Long = 60_000L) {
        if (!on(c) || P.get(c, "cAddr").isBlank()) return
        val at = System.currentTimeMillis() + delay
        P.put(c, "checkAt", at.toString())
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            if (exactOk(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(c))
        } catch (e: Exception) { L.w("CHECK", "couldn't schedule: $e") }
    }

    fun check(c: Context) {
        if (!on(c)) return
        val n = (P.get(c, "checkN").toIntOrNull() ?: 0) + 1; P.put(c, "checkN", n.toString())
        val late = (System.currentTimeMillis() - (P.get(c, "checkAt").toLongOrNull() ?: System.currentTimeMillis())) / 1000
        val head = "#$n (${screen(c)}, ${if (late > 2) "${late}s LATE" else "on time"})"
        if (Probe.busy) { L.w("CHECK", "$head skipped: Test connect is running"); schedule(c); return }
        val ad = Env.adapter(c)
        val addr = P.get(c, "cAddr")
        if (ad == null || !ad.isEnabled || addr.isBlank()) { L.w("CHECK", "$head skipped: Bluetooth off or no target"); schedule(c); return }
        val dev = ad.getRemoteDevice(addr)
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        val t0 = System.currentTimeMillis()
        var how = "insecure"
        val sock = try { dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (e1: Exception) {
            how = "secure"
            try { dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (e2: Exception) {
                L.w("CHECK", "$head no answer after ${System.currentTimeMillis() - t0} ms (${e2.message ?: e1.message})"); schedule(c); return
            }
        }
        val ms = System.currentTimeMillis() - t0
        try {
            val inp = sock.inputStream; val out = sock.outputStream
            fun send(cmd: String, wait: Long): String {
                while (inp.available() > 0) inp.read()
                out.write("$cmd\r".toByteArray()); out.flush()
                val sb = StringBuilder(); val end = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < end) { if (inp.available() > 0) { val ch = inp.read().toChar(); if (ch == '>') break; sb.append(ch) } else Thread.sleep(20) }
                return sb.toString().replace("\r", " ").trim()
            }
            send("ATZ", 2500); send("ATE0", 1000)
            val v = send("ATRV", 1500)
            val volts = Regex("(\\d{1,2}\\.\\d)").find(v)?.value?.toDoubleOrNull()
            L.w("CHECK", "$head ★ CONNECTED ($how) in $ms ms · $v" + when { volts == null -> ""; volts >= 13.0 -> " → ENGINE RUNNING"; else -> " → engine off" })
        } catch (e: Exception) { L.w("CHECK", "$head connected in $ms ms but talk failed: ${e.message}") }
        finally { try { sock.close() } catch (_: Exception) {} }
        schedule(c)
    }

    // ---------- SCAN-*: background scans, three ways ----------
    val VARIANTS = listOf("name", "address", "service18F0")
    private fun scanPi(c: Context, v: String) = PendingIntent.getBroadcast(c, 30 + VARIANTS.indexOf(v), Intent(c, LabScanReceiver::class.java).setAction(v),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    private fun scans(c: Context) {
        val ad = Env.adapter(c) ?: return
        val sc = ad.bluetoothLeScanner ?: run { L.w("SCAN", "no LE scanner (Bluetooth off?)"); return }
        val lostOk = ad.isOffloadedFilteringSupported
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).apply {
            if (lostOk) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }.build()
        for (v in VARIANTS) {
            val f = try {
                when (v) {
                    "name" -> P.get(c, "lName").takeIf { it.isNotBlank() }?.let { ScanFilter.Builder().setDeviceName(it).build() }
                    "address" -> P.get(c, "lAddr").takeIf { it.isNotBlank() }?.let { ScanFilter.Builder().setDeviceAddress(it).build() }
                    else -> ScanFilter.Builder().setServiceUuid(SVC).build()
                }
            } catch (e: Exception) { L.w("SCAN-$v", "bad filter: $e"); null }
            if (f == null) { L.w("SCAN-$v", "skipped (no target)"); continue }
            try { sc.stopScan(scanPi(c, v)) } catch (_: Exception) {}
            val r = try { sc.startScan(listOf(f), settings, scanPi(c, v)) } catch (e: Exception) { L.w("SCAN-$v", "couldn't start: $e"); continue }
            L.w("SCAN-$v", if (r == 0) "registered" else "registration FAILED: ${WatchService.scanFail(r)}")
        }
    }
}

class LabAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        L.init(c)
        val pr = goAsync()
        Thread { try { Lab.check(c) } catch (e: Exception) { L.w("CHECK", "error: $e"); Lab.schedule(c) } finally { pr.finish() } }.start()
    }
}

class LabScanReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        L.init(c)
        val v = i.action ?: "?"
        val err = i.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { L.w("SCAN-$v", "error from Android: ${WatchService.scanFail(err)}"); return }
        val type = i.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1)
        val list: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            else i.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: emptyList()
        val what = when (type) { ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> "★ APPEARED"; ScanSettings.CALLBACK_TYPE_MATCH_LOST -> "GONE"; else -> "seen" }
        L.w("SCAN-$v", "$what " + list.joinToString("; ") { "${Env.name(it.device).ifBlank { it.scanRecord?.deviceName ?: "(no name)" }} ${it.device.address} ${it.rssi} dBm" } +
            " (${Lab.screen(c)}${if (WatchService.running) "" else ", app closed"})")
    }
}
