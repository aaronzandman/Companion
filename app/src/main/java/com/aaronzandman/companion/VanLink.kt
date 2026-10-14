package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * The phone never connects to the vLinker on its own, so Companion starts the handshake itself, two ways:
 *  1. A standing connection request to the vLinker's Bluetooth LE side (FD-IOS). Android's Bluetooth chip waits for it
 *     at almost no battery cost and connects the moment the vLinker wakes up: that connection = van on.
 *  2. A check every minute: a quick Bluetooth 3.0 connection (like "Test connect") that reads the battery voltage.
 *     13 V or more means the engine is running (alternator charging), so a parked van with the vLinker still awake isn't "on".
 * Both only run while no day is running and "Start when the vLinker turns on" is on.
 */
@SuppressLint("MissingPermission")
object VanLink {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var gatt: BluetoothGatt? = null
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val POLL_MS = 60_000L
    const val RUNNING_V = 13.0

    private fun wanted(c: Context) = VanWatch.on(c) && !Tracker.isRunning(c) && !VanSession.enabled(c)   // the van session holds the vLinker instead
    private fun adapter(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter

    /** Start (or restart) both: the standing LE connection request and the minute check. */
    fun start(c: Context) { arm(c); schedulePoll(c, 5_000L) }
    fun stop(c: Context) { disarm(); cancelPoll(c) }

    // ---------- 1. standing LE connection request ----------
    fun arm(c: Context) {
        disarm()
        h.removeCallbacksAndMessages(null)
        if (!wanted(c)) return
        val a = VanWatch.leAddr(c) ?: run { VanWatch.log(c, "auto-connect: no Bluetooth LE address for the vLinker yet (tap Find my vLinker with the van on)"); return }
        val ad = adapter(c)
        if (ad == null || !ad.isEnabled) { VanWatch.log(c, "auto-connect: Bluetooth is off"); return }
        val app = c.applicationContext
        try {
            val dev: BluetoothDevice = if (Build.VERSION.SDK_INT >= 33) try { ad.getRemoteLeDevice(a, BluetoothDevice.ADDRESS_TYPE_RANDOM) } catch (_: Exception) { ad.getRemoteDevice(a) } else ad.getRemoteDevice(a)
            gatt = dev.connectGatt(app, true, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        VanWatch.log(app, "★ vLinker answered the standing connection request (LE)")
                        try { g.disconnect(); g.close() } catch (_: Exception) {}
                        if (gatt === g) gatt = null
                        Thread { try { VanWatch.vanOn(app, "vLinker") } catch (_: Exception) {} }.start()
                        h.postDelayed({ arm(app) }, 5 * 60_000L)   // re-arm later; while the van stays on it just answers again (ignored)
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && gatt === g && status != 0) {
                        VanWatch.log(app, "auto-connect request dropped (status $status); re-arming in 1 min")
                        try { g.close() } catch (_: Exception) {}
                        gatt = null
                        h.postDelayed({ arm(app) }, 60_000L)
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            VanWatch.log(c, "auto-connect armed for $a")
        } catch (e: Exception) { VanWatch.log(c, "auto-connect couldn't arm: $e") }
    }

    private fun disarm() { val g = gatt; gatt = null; if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {} }

    // ---------- 2. minute check over Bluetooth 3.0 ----------
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 42, Intent(c, VanPollReceiver::class.java).setAction("poll"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun exactOk(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedulePoll(c: Context, delay: Long = POLL_MS) {
        if (!wanted(c) || Store.get(c, "obdAddr", "").isBlank()) { cancelPoll(c); return }
        val am = c.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delay
        try {
            if (exactOk(c)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(c))
        } catch (e: Exception) { VanWatch.log(c, "minute check couldn't be scheduled: $e") }
    }
    fun cancelPoll(c: Context) { try { c.getSystemService(AlarmManager::class.java).cancel(pi(c)) } catch (_: Exception) {} }

    /** One check: connect, read the voltage, hang up. */
    fun poll(c: Context) {
        if (!wanted(c)) return
        val v = voltage(c)
        val was = Store.get(c, "pollState", "")
        val now = when { v == null -> "none"; v >= RUNNING_V -> "run"; else -> "idle" }
        if (now != was) {
            Store.put(c, "pollState", now)
            VanWatch.log(c, when (now) {
                "none" -> "minute check: vLinker not answering (van off)"
                "idle" -> "minute check: vLinker awake, %.1f V — engine not running".format(v)
                else -> "minute check: ★ vLinker answered, %.1f V — engine running".format(v)
            })
        }
        DiagLog.w(c, "CHECK", "minute check: " + if (v == null) "no answer" else "answered, %.1f V".format(v))
        if (v != null) Store.put(c, "vanVolts", "%.1f".format(v))
        if (now == "run") VanWatch.vanOn(c, "vLinker")
        if (!Tracker.isRunning(c)) schedulePoll(c, if (now == "idle") 20_000L else POLL_MS)
    }

    private fun voltage(c: Context): Double? {
        val ad = adapter(c) ?: return null
        if (!ad.isEnabled) return null
        val addr = Store.get(c, "obdAddr", ""); if (addr.isBlank()) return null
        val dev = try { ad.getRemoteDevice(addr) } catch (_: Exception) { return null }
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        val sock = try { dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (_: Exception) {
            try { dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (_: Exception) { return null }
        }
        try {
            val inp = sock.inputStream; val out = sock.outputStream
            fun send(cmd: String, ms: Long): String {
                while (inp.available() > 0) inp.read()
                out.write("$cmd\r".toByteArray()); out.flush()
                val sb = StringBuilder(); val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) { if (inp.available() > 0) { val ch = inp.read().toChar(); if (ch == '>') break; sb.append(ch) } else Thread.sleep(20) }
                return sb.toString()
            }
            send("ATZ", 2500); send("ATE0", 1000)
            val r = send("ATRV", 1500)
            return Regex("(\\d{1,2}\\.\\d)\\s*V", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) { return 0.0 } finally { try { sock.close() } catch (_: Exception) {} }
    }
}

class VanPollReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        Thread { try { VanLink.poll(c) } catch (e: Exception) { VanWatch.log(c, "minute check error: $e"); VanLink.schedulePoll(c) } finally { pr.finish() } }.start()
    }
}
