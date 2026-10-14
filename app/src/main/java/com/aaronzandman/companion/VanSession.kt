package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID

/**
 * The van's own radio ("Ford Transit") pings the phone about every 100 seconds while the van is on. Companion only
 * listens to those pings (nothing is paired or connected to the van). The first ping makes Companion connect to the
 * vLinker and keep that connection:
 *  - every 30 s it reads the battery voltage: 13 V or more = engine running (and the link is still alive);
 *  - engine running → van on: the day starts if it hasn't, GPS goes to full speed, the odometer is read;
 *  - engine off (about a minute of low voltage) or the vLinker drops → van off: a drive ends where you stopped moving;
 *  - after 2 minutes with the engine off Companion lets go of the vLinker so it can sleep and not drain the battery.
 * Odometer reads go through this same connection while it's held.
 */
@SuppressLint("MissingPermission")
object VanSession {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val RUNNING_V = 13.0
    @Volatile private var held: Obd.SockLink? = null
    @Volatile private var worker: Thread? = null
    @Volatile var engineOn = false
        private set
    @Volatile private var lastPingLog = 0L

    fun enabled(c: Context) = Store.get(c, "vanHold", "0") == "1" && Store.get(c, "obdAddr", "").isNotBlank() && Store.get(c, "vanAclAddr", "").isNotBlank()
    fun active() = worker?.isAlive == true
    fun link(): Obd.Link? = held

    /** A ping from the van's radio. */
    fun onPing(c: Context) {
        if (!enabled(c)) return
        val now = System.currentTimeMillis()
        if (active()) { if (now - lastPingLog > 10 * 60_000L) { lastPingLog = now; DiagLog.w(c, "VAN", "van ping (vLinker connection already held, engine ${if (engineOn) "running" else "off"})") }; return }
        val app = c.applicationContext
        worker = Thread({ run(app) }, "van-session").also { it.start() }
    }

    fun stop() { worker?.interrupt(); try { held?.close() } catch (_: Exception) {} }

    private fun connect(c: Context): BluetoothSocket? {
        val ad = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!ad.isEnabled) return null
        val dev = try { ad.getRemoteDevice(Store.get(c, "obdAddr", "")) } catch (_: Exception) { return null }
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        return try { dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (_: Exception) {
            try { dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (_: Exception) { null }
        }
    }

    private fun volts(r: String): Double? = Regex("(\\d{1,2}\\.\\d)\\s*V", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun run(c: Context) {
        VanWatch.log(c, "van pinged → connecting to the vLinker")
        val t0 = System.currentTimeMillis()
        val sock = connect(c) ?: run { VanWatch.log(c, "vLinker didn't answer (${System.currentTimeMillis() - t0} ms); trying again at the next van ping"); return }
        val link = Obd.SockLink(sock); held = link
        val since = System.currentTimeMillis()
        VanWatch.log(c, "★ vLinker connected in ${since - t0} ms — holding the connection")
        var lowSince = 0L; var fails = 0
        try {
            synchronized(Obd.lock) { link.send("ATZ", 2500); link.send("ATE0", 1000) }
            while (!Thread.currentThread().isInterrupted) {
                val v = synchronized(Obd.lock) { volts(link.send("ATRV", 2000)) }
                val now = System.currentTimeMillis()
                if (v == null) {
                    fails++; DiagLog.w(c, "VAN", "voltage check: no answer ($fails)")
                    if (fails >= 2) { VanWatch.log(c, "vLinker stopped answering"); break }
                } else {
                    fails = 0; Store.put(c, "vanVolts", "%.1f".format(v)); DiagLog.w(c, "VAN", "voltage %.1f V".format(v))
                    if (v >= RUNNING_V) { lowSince = 0L; if (!engineOn) { engineOn = true; engine(c, true, v) } }
                    else {
                        if (lowSince == 0L) lowSince = now
                        if (engineOn && now - lowSince >= 45_000L) { engineOn = false; engine(c, false, v) }   // two low readings in a row
                        if (!engineOn && now - lowSince >= 120_000L) { VanWatch.log(c, "engine off for 2 min → letting go of the vLinker so it can sleep"); break }
                    }
                }
                Thread.sleep(30_000L)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { VanWatch.log(c, "vLinker connection error: ${e.message}") }
        finally {
            held = null
            link.close()
            if (engineOn) { engineOn = false; engine(c, false, null) }
            VanWatch.log(c, "vLinker connection closed after ${(System.currentTimeMillis() - since) / 60_000} min")
        }
    }

    private fun engine(c: Context, on: Boolean, v: Double?) {
        try {
            if (on) {
                VanWatch.log(c, "engine running (%.1f V) → van ON".format(v ?: 0.0))
                Tracker.vanEngine(c, true)
                VanWatch.vanOn(c, "your van")              // starts the day if it isn't running
                if (Tracker.isRunning(c)) TrackerService.ping(c)   // full-speed GPS + odometer reading
            } else {
                VanWatch.log(c, "engine off${v?.let { " (%.1f V)".format(it) } ?: ""} → van OFF")
                Store.put(c, "vanPresent", "0")
                if (Tracker.isRunning(c)) { TrackerService.ping(c); Tracker.vanEngine(c, false) }   // final odometer reading, then end the drive
            }
        } catch (e: Exception) { DiagLog.w(c, "VAN", "engine handler error: $e") }
    }
}
