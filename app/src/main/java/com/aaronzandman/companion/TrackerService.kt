package com.aaronzandman.companion

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the timer running with the screen off (Android requires the ongoing notification for this).
 * Battery: full GPS (every 10 s) only while moving. After 5 minutes parked it switches to low-power location
 * (Wi-Fi/cell about once a minute) and turns full GPS back on as soon as you drive away.
 */
class TrackerService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var parked = false
    private var parkedAt: Location? = null
    private var away = 0
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Tracker.isRunning(this)) { stopEverything(); return START_NOT_STICKY }
        Notify.channels(this)
        val n = Notify.tracker(this, Tracker.state(this).optJSONObject("cur")?.let { Tracker.describe(this, it) } ?: "Starting…")
        ServiceCompat.startForeground(this, Notify.ID_TRACKER, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        if (lm == null) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) { stopEverything(); return START_NOT_STICKY }
            lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            moving()
        }
        readOdometer(System.currentTimeMillis())
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.provider == LocationManager.NETWORK_PROVIDER && loc.accuracy > 150) return
        val now = System.currentTimeMillis()
        val before = Tracker.state(this).optLong("boundary", 0)
        val text = Tracker.onFix(this, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else null, now)
        val st = Tracker.state(this)
        adjustPower(loc, st, now)
        // Odometer: read at each possible arrival/departure, and every 2 minutes while driving.
        val boundary = st.optLong("boundary", 0)
        if (Tracker.commuteMark(this, now)) { lastOdoTry = 0L; readOdometer(now) }   // 30 minutes into the trip from home
        else if (boundary != before && boundary > 0) readOdometer(boundary)
        else if (st.optJSONObject("cur")?.optString("type") == "DRIVE" && now - lastOdoTry > 120_000L) readOdometer(now)
        if (text != lastText) { lastText = text; getSystemService(NotificationManager::class.java).notify(Notify.ID_TRACKER, Notify.tracker(this, text)) }
    }

    /** Full GPS: every 10 seconds (needed to tell driving from stopping). */
    private fun moving() {
        val m = lm ?: return
        try {
            m.removeUpdates(this)
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) { stopEverything() }
        parked = false; parkedAt = null; away = 0
    }

    /** Low power while parked: Wi-Fi/cell location about once a minute (GPS once a minute if that isn't available). */
    private fun parkedMode(at: Location) {
        val m = lm ?: return
        try {
            m.removeUpdates(this)
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, this, Looper.getMainLooper())
            else m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 0f, this, Looper.getMainLooper())
            m.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())   // free: fixes other apps request
        } catch (_: SecurityException) { stopEverything() }
        parked = true; parkedAt = at; away = 0
    }

    private fun adjustPower(loc: Location, st: org.json.JSONObject, now: Long) {
        val cur = st.optJSONObject("cur") ?: return
        if (!parked) {
            // Parked for 5 minutes with nothing changing → low power.
            if (cur.optString("type") != "DRIVE" && !st.has("cand") && now - cur.optLong("start") >= 5 * 60_000L) parkedMode(loc)
        } else {
            val p = parkedAt ?: return moving()
            val far = p.distanceTo(loc) > maxOf(150f, 2 * loc.accuracy)
            val fast = loc.hasSpeed() && loc.speed > 4f
            if (far || fast) { away++; if (away >= 2 || (loc.accuracy <= 100f && (far || fast))) moving() } else away = 0
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Volatile private var lastOdoTry = 0L
    private val odoBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFordTry = 0L
    private fun readOdometer(t: Long) {
        if (!Obd.enabled(this)) { readFord(); return }
        if (!odoBusy.compareAndSet(false, true)) return
        lastOdoTry = System.currentTimeMillis()
        Thread {
            // Stamp the reading halfway through the read (it takes a few seconds), so its time matches the miles.
            try { val t0 = System.currentTimeMillis(); Obd.readMiles(this)?.let { Obd.record(this, (t0 + System.currentTimeMillis()) / 2, it) } } finally { odoBusy.set(false) }
        }.start()
    }

    /** Without the adapter: ask Ford for the odometer (at most once a minute). Ford's own time stamp is used. */
    private fun readFord() {
        if (!Ford.useForOdometer(this)) return
        val now = System.currentTimeMillis(); if (now - lastFordTry < 60_000L || !odoBusy.compareAndSet(false, true)) return
        lastFordTry = now
        Thread {
            try { Ford.readMiles(this)?.let { (mi, t) -> if (System.currentTimeMillis() - t < 15 * 60_000L) Obd.record(this, t, mi) } } finally { odoBusy.set(false) }
        }.start()
    }

    private fun stopEverything() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        lm = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { try { lm?.removeUpdates(this) } catch (_: Exception) {}; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, TrackerService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, TrackerService::class.java).setAction(ACTION_STOP))
    }
}
