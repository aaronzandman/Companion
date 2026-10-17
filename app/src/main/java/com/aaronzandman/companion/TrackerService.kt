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
    private var storeShown = ""

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastFixLog = 0L
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagLog.w(this, "TIMER", "service command: ${intent?.action ?: "start"} · ${DiagLog.dayState(this)}")
        if (intent?.action == ACTION_STOP || !Tracker.isRunning(this)) { stopEverything(); return START_NOT_STICKY }
        if (intent?.action == ACTION_BT && lm != null) {   // van connected/disconnected: odometer reading, full GPS while driving, fresh status
            readOdometer(System.currentTimeMillis(), force = true)
            if (Tracker.state(this).optJSONObject("cur")?.optString("type") == "DRIVE") moving()
            val t = Tracker.state(this).optJSONObject("cur")?.let { Tracker.describe(this, it) } ?: ""
            if (t.isNotEmpty() && t != lastText) { lastText = t; getSystemService(NotificationManager::class.java).notify(Notify.ID_TRACKER, Notify.tracker(this, t)) }
            updateStoreNote(System.currentTimeMillis())
            return START_STICKY
        }
        Notify.channels(this)
        val n = Notify.tracker(this, Tracker.state(this).optJSONObject("cur")?.let { Tracker.describe(this, it) } ?: "Starting…")
        try {
            ServiceCompat.startForeground(this, Notify.ID_TRACKER, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {   // Android didn't allow starting from the background: ask with a notification instead
            val resume = intent?.getStringExtra("resumeNote")
            if (resume != null) Notify.autoDay(this, resume, "Your day is still running. Tap to keep the timer going.", true)   // keep the day; just ask
            else { Tracker.cancelStart(this); Notify.autoDay(this, "Your day didn't start", "Tap to start your day.", true) }
            stopSelf(); return START_NOT_STICKY
        }
        intent?.getStringExtra("resumeNote")?.let { Notify.autoDay(this, "Timer picked back up", "$it. Your day carried on by itself.", false) }
        if (lm == null) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) { stopEverything(); return START_NOT_STICKY }
            lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            moving()
        }
        readOdometer(System.currentTimeMillis(), force = true)
        // Starting the day is a good moment to look for an app update (at most every 6 hours).
        Thread { val r = Updater.check(this, 6 * 3600_000L); if (r.optBoolean("ready") && !r.optBoolean("notified")) Notify.updateReady(this, r.optString("tag")) }.start()
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.provider == LocationManager.NETWORK_PROVIDER && loc.accuracy > 150) return
        val now = System.currentTimeMillis()
        val text = Tracker.onFix(this, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else null, now, if (loc.hasAccuracy()) loc.accuracy else 50f)
        if (DiagLog.on(this) && now - lastFixLog > 20_000L) {
            lastFixLog = now
            val home = Tracker.home(this)?.let { Stores.meters(it.first, it.second, loc.latitude, loc.longitude).toInt() }
            val st = Stores.nearest(this, loc.latitude, loc.longitude)
            DiagLog.w(this, "GPS", "${loc.provider} · speed ${if (loc.hasSpeed()) "%.1f mph".format(loc.speed * 2.237) else "?"} · ±${loc.accuracy.toInt()} m · home ${home?.let { "$it m" } ?: "not set"} · nearest store ${st?.let { "${it.first.num} ${it.second.toInt()} m" } ?: "-"} · $text")
        }
        if (AutoDay.checkHome(this)) { lastText = ""; stopEverything(); return }   // arrived home: day over
        val st = Tracker.state(this)
        adjustPower(loc, st, now)
        if (!parked && lm != null && wantRate() != rateMs) moving()   // started or stopped driving: change the GPS rate
        // Odometer: once a minute while driving (the throttle in readOdometer), forced at the commute mark.
        // Drive start/end are read via the van connect/disconnect (ACTION_BT), which force a read.
        if (Tracker.commuteMark(this, now)) readOdometer(now, force = true)   // 30 minutes into the trip from home
        else if (st.optJSONObject("cur")?.optString("type") == "DRIVE") readOdometer(now)
        if (text != lastText) { lastText = text; getSystemService(NotificationManager::class.java).notify(Notify.ID_TRACKER, Notify.tracker(this, text)) }
        updateStoreNote(now)
    }

    /**
     * While parked at a store, keep a notification showing time there so far and the drive that got you there —
     * the two figures to copy into SAP. Cleared the moment you're no longer at a store.
     */
    private fun updateStoreNote(now: Long) {
        val cur = Tracker.state(this).optJSONObject("cur")
        if (cur == null || cur.optString("type") != "STORE") {
            if (storeShown.isNotEmpty()) { Notify.clearStore(this); storeShown = "" }
            return
        }
        val store = cur.optString("store")
        val start = cur.optLong("start")
        // Travel = door-to-door since you left the last store/home: everything between the previous visit and this one
        // (drives plus any stop in between, e.g. fuel). If this is the first store, measure from when you left home.
        val st = Tracker.state(this)
        val dayStart = st.optLong("dayStart", start)
        var travelStart = st.optLong("dep", 0L).takeIf { it in 1L until start } ?: dayStart
        var firstStore = true   // first Publix of the day (so the Clock-in option shows only once)
        val segs = Store.segments(this)
        var travelFound = false
        for (i in segs.length() - 1 downTo 0) {
            val g = segs.getJSONObject(i)
            if (g.optLong("end") > start + 1000) continue
            val type = g.optString("type")
            if (type == "STORE") { if (g.optLong("start") >= dayStart) firstStore = false; if (!travelFound) { travelStart = g.optLong("end"); travelFound = true } }
            else if (type == "HOME" && !travelFound) { travelStart = g.optLong("end"); travelFound = true }
        }
        Notify.atStore(this, store, now - start, (start - travelStart).coerceAtLeast(0L), firstStore)
        storeShown = store
    }

    /** GPS rate: every second while driving (accurate miles), every 5 seconds otherwise (to tell driving from stopping). */
    private var rateMs = 0L
    private fun wantRate(): Long = if (Tracker.state(this).optJSONObject("cur")?.optString("type") == "DRIVE") 1_000L else 5_000L

    /** Full GPS. */
    private fun moving() {
        val m = lm ?: return
        try {
            m.removeUpdates(this)
            rateMs = wantRate()
            DiagLog.w(this, "GPS", "full GPS every ${rateMs / 1000}s")
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, rateMs, 0f, this, Looper.getMainLooper())
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
    private fun readOdometer(t: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastOdoTry < 60_000L) return   // at most once a minute while driving
        if (!Obd.enabled(this)) { readFord(); return }
        if (!odoBusy.compareAndSet(false, true)) return
        lastOdoTry = now
        Thread {
            // Stamp the reading halfway through the read (it takes a few seconds), so its time matches the miles.
            try {
                val t0 = System.currentTimeMillis(); DiagLog.w(this, "OBD", "odometer read starting (connects to the adapter)")
                val mi = Obd.readMiles(this)
                DiagLog.w(this, "OBD", "odometer read done in ${System.currentTimeMillis() - t0} ms: ${mi?.let { "%.1f mi".format(it) } ?: "no reading"}")
                mi?.let { Obd.record(this, (t0 + System.currentTimeMillis()) / 2, it) }
            } finally { odoBusy.set(false) }
        }.start()
    }

    /** Without the adapter: ask Ford for the odometer (at most once every 5 minutes). Ford's own time stamp is used. */
    private fun readFord() {
        if (!Ford.useForOdometer(this)) return
        val now = System.currentTimeMillis(); if (now - lastFordTry < Ford.AUTO_GAP_MS || !Ford.autoReadDue(this) || !odoBusy.compareAndSet(false, true)) return
        lastFordTry = now
        Thread {
            try { Ford.readMiles(this)?.let { (mi, t) -> if (System.currentTimeMillis() - t < 15 * 60_000L) Obd.record(this, t, mi) } } finally { odoBusy.set(false) }
        }.start()
    }

    private fun stopEverything() {
        DiagLog.w(this, "TIMER", "timer stopping · ${DiagLog.dayState(this)} ← ${DiagLog.caller()}")
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        lm = null
        Notify.clearStore(this); storeShown = ""
        if (!Tracker.isRunning(this)) StandbyService.start(this)   // day over: back to standby
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { try { lm?.removeUpdates(this) } catch (_: Exception) {}; super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_BT = "bt"
        /** Tell the running timer that the van connected or disconnected. */
        fun ping(c: Context) { try { c.startService(Intent(c, TrackerService::class.java).setAction(ACTION_BT)) } catch (_: Exception) {} }
        fun start(c: Context) { StandbyService.stop(c); ContextCompat.startForegroundService(c, Intent(c, TrackerService::class.java)) }
        fun stop(c: Context) = c.startService(Intent(c, TrackerService::class.java).setAction(ACTION_STOP))
    }
}
