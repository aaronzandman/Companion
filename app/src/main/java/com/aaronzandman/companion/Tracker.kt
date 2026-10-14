package com.aaronzandman.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns location fixes into time entries:
 *  DRIVE  – moving faster than ~10 mph for 45 s
 *  STORE  – within the store radius (default 250 m) and slow for 90 s
 *  HOME   – at the saved home location (within 200 m) and slow for 90 s
 *  STOP   – stopped somewhere that isn't a store or home for 3 min
 * Each entry also keeps the GPS distance travelled ("m", meters) and a minute-by-minute distance track
 * ("trk": [seconds, meters so far]) so miles can be split exactly where they were driven when there's no odometer.
 * Entries shorter than 1 minute are dropped. State is saved so it survives the app closing.
 */
object Tracker {
    private const val DRIVE_MS = 45_000L
    private const val STORE_MS = 90_000L
    const val DEFAULT_RADIUS = "400"
    private const val STOP_MS = 180_000L
    private const val HOME_M = 200.0

    /** The saved home location, or null. Kept on this phone only. */
    fun home(c: Context): Pair<Double, Double>? = try {
        val o = JSONObject(Store.get(c, "home", "")); Pair(o.getDouble("lat"), o.getDouble("lng"))
    } catch (_: Exception) { null }
    private val lock = Any()

    fun state(c: Context) = JSONObject(Store.get(c, "trk", "{}"))
    private fun save(c: Context, s: JSONObject) = Store.put(c, "trk", s.toString())
    fun isRunning(c: Context) = state(c).optBoolean("running", false)

    fun startDay(c: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val s = JSONObject().put("running", true).put("dayStart", now).put("dep", 0L).put("reached", false).put("mark", false)
            .put("cur", JSONObject().put("type", "STOP").put("store", "").put("start", now))
        save(c, s)
    }

    fun endDay(c: Context, now: Long = System.currentTimeMillis(), auto: Boolean = false) = synchronized(lock) {
        val s = state(c)
        s.optJSONObject("cur")?.let { close(c, it, now) }
        save(c, JSONObject().put("running", false).put("lastEnd", now).put("auto", auto)
            .put("lastDayStart", s.optLong("dayStart", now)).put("lastDep", s.optLong("dep", 0L)).put("lastReached", s.optBoolean("reached")))
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val f = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US); return f.format(java.util.Date(a)) == f.format(java.util.Date(b))
    }
    /** A day ended earlier today (by you or at home). */
    fun endedToday(c: Context, now: Long = System.currentTimeMillis()): Boolean { val s = state(c); return !s.optBoolean("running") && s.optLong("lastEnd", 0L) > 0 && sameDay(s.optLong("lastEnd"), now) }
    /** Today's day ended automatically on arriving home, so it can carry on (e.g. after lunch at home). */
    fun resumable(c: Context, now: Long = System.currentTimeMillis()) = endedToday(c, now) && state(c).optBoolean("auto")

    /** Carries on the day that ended at home: same start of day, the time at home in between isn't counted. */
    fun resumeDay(c: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val o = state(c)
        save(c, JSONObject().put("running", true).put("dayStart", o.optLong("lastDayStart", now)).put("dep", o.optLong("lastDep", 0L))
            .put("reached", o.optBoolean("lastReached")).put("mark", true).put("resumed", now)
            .put("cur", JSONObject().put("type", "STOP").put("store", "").put("start", now)))
    }

    /** Undo a start that Android didn't allow (nothing was recorded yet). */
    fun cancelStart(c: Context) = synchronized(lock) { val s = state(c); if (s.optBoolean("running")) save(c, JSONObject().put("running", false)) }

    private fun close(c: Context, cur: JSONObject, end: Long) {
        val start = cur.optLong("start")
        if (end - start < 60_000L) return
        // A stop that was really a store visit (GPS too poor to tell at the time): match its average position to a store.
        if (cur.optString("type") == "STOP" && cur.optString("tag").isEmpty() && cur.optInt("sN", 0) >= 2) {
            val n = cur.optInt("sN"); val lat = cur.optDouble("sLat") / n; val lng = cur.optDouble("sLng") / n
            val radius = Store.get(c, "radius", DEFAULT_RADIUS).toDoubleOrNull() ?: 400.0
            Stores.nearest(c, lat, lng)?.takeIf { it.second <= radius + 150.0 }?.let { cur.put("type", "STORE").put("store", it.first.num) }
        }
        val segs = Store.segments(c)
        val g = JSONObject().put("type", cur.optString("type")).put("store", cur.optString("store"))
            .put("start", start).put("end", end).put("m", Math.round(cur.optDouble("m", 0.0)))
        cur.optJSONArray("trk")?.let { trk -> trk.put(JSONArray().put(end / 1000).put(Math.round(cur.optDouble("m", 0.0)))); g.put("trk", trk) }
        if (cur.optString("tag").isNotEmpty()) g.put("tag", cur.optString("tag"))
        segs.put(g)
        Store.saveSegments(c, segs)
    }

    /** Called for every location fix. Returns a short description of what's happening now. */
    fun onFix(c: Context, lat: Double, lng: Double, speedIn: Float?, t: Long, acc: Float = 20f): String {
      synchronized(lock) {
        val s = state(c)
        if (!s.optBoolean("running")) return "Not tracking"
        if (acc > 250f) return s.optJSONObject("cur")?.let { describe(c, it) } ?: "Tracking"   // too vague to use (deep indoors)
        val last = s.optJSONObject("last")
        // Trust the phone's own speed only from a good GPS fix. Otherwise work it out from the distance moved,
        // minus the fixes' uncertainty: Wi-Fi/cell positions inside a store jump around and must not look like driving.
        var speed = if (speedIn != null && acc <= 50f) speedIn.toDouble() else -1.0
        if (speed < 0 && last != null) {
            val dt = (t - last.optLong("t")) / 1000.0
            val moved = Stores.meters(last.optDouble("lat"), last.optDouble("lng"), lat, lng) - maxOf(acc.toDouble(), last.optDouble("acc", 20.0))
            if (dt > 0) speed = maxOf(0.0, moved) / dt
        }
        s.put("last", JSONObject().put("lat", lat).put("lng", lng).put("t", t).put("speed", speed).put("acc", acc.toDouble()))
        val stepM = if (last != null) Stores.meters(last.optDouble("lat"), last.optDouble("lng"), lat, lng) else 0.0

        val radius = Store.get(c, "radius", DEFAULT_RADIUS).toDoubleOrNull() ?: 400.0
        val near = Stores.nearest(c, lat, lng)?.takeIf { it.second <= radius + minOf(acc.toDouble(), 100.0) }?.first
        // How long we've been near this same store: a few minutes there counts as a visit even if GPS jitters.
        if (near != null) { val n = s.optJSONObject("near"); if (n?.optString("store") != near.num) s.put("near", JSONObject().put("store", near.num).put("since", t)) } else s.remove("near")
        val nearFor = if (near != null) t - (s.optJSONObject("near")?.optLong("since") ?: t) else 0L
        val atHome = home(c)?.let { Stores.meters(it.first, it.second, lat, lng) <= HOME_M } == true
        val cur = s.optJSONObject("cur") ?: JSONObject().put("type", "STOP").put("store", "").put("start", t)
        // GPS distance while moving (ignores parked jitter and impossible jumps)
        if (stepM in 0.0..5000.0 && speed >= 2.0) cur.put("m", cur.optDouble("m", 0.0) + stepM)
        // Where a stop is (average of good fixes), so a stop can still be matched to a store when it ends.
        if (acc <= 100f) cur.put("sLat", cur.optDouble("sLat", 0.0) + lat).put("sLng", cur.optDouble("sLng", 0.0) + lng).put("sN", cur.optInt("sN", 0) + 1)
        // Distance track: a point at least every 60 s, so miles can be split at any moment of a drive.
        run {
            val trk = cur.optJSONArray("trk") ?: JSONArray().put(JSONArray().put(cur.optLong("start") / 1000).put(0)).also { cur.put("trk", it) }
            val lastT = trk.optJSONArray(trk.length() - 1)?.optLong(0) ?: 0L
            if (t / 1000 - lastT >= 60) trk.put(JSONArray().put(t / 1000).put(Math.round(cur.optDouble("m", 0.0))))
        }
        if (btMode(c)) {   // Bluetooth decides driving vs stopped; GPS only says where you stopped
            if (cur.optString("type") != "DRIVE") {
                val (wt, ws) = when { near != null && speed < 8.0 -> Pair("STORE", near.num); atHome && speed < 8.0 -> Pair("HOME", ""); else -> Pair(cur.optString("type"), cur.optString("store")) }
                if ((wt != cur.optString("type") || ws != cur.optString("store")) && cur.optString("type") != "HOME") {
                    cur.put("type", wt).put("store", ws); if (wt == "STORE") s.put("reached", true)
                }
            }
            s.remove("cand"); if (!s.has("cur")) s.put("cur", cur)
            save(c, s); return describe(c, s.getJSONObject("cur"))
        }
        val obsType: String; val obsStore: String
        when {
            speed >= 4.5 -> { obsType = "DRIVE"; obsStore = "" }
            near != null && (speed < 2.5 || (nearFor >= 120_000L && speed < 8.0)) -> { obsType = "STORE"; obsStore = near.num }
            atHome && speed < 2.5 -> { obsType = "HOME"; obsStore = "" }
            speed in 0.0..1.5 -> { obsType = "STOP"; obsStore = "" }
            else -> { obsType = cur.optString("type"); obsStore = cur.optString("store") }
        }
        if (obsType == cur.optString("type") && obsStore == cur.optString("store")) {
            s.remove("cand")
        } else {
            val cand = s.optJSONObject("cand")
            if (cand != null && cand.optString("type") == obsType && cand.optString("store") == obsStore) {
                val need = when (obsType) { "DRIVE" -> DRIVE_MS; "STORE", "HOME" -> STORE_MS; else -> STOP_MS }
                val since = cand.optLong("since")
                if (t - since >= need) {
                    close(c, cur, since)
                    s.put("cur", JSONObject().put("type", obsType).put("store", obsStore).put("start", since))
                    if (obsType == "DRIVE" && s.optLong("dep", 0L) == 0L) s.put("dep", since)   // left home: the morning trip starts
                    if (obsType == "STORE") s.put("reached", true)
                    s.remove("cand")
                }
            } else {
                s.put("cand", JSONObject().put("type", obsType).put("store", obsStore).put("since", t))
                s.put("boundary", t)   // something may be changing (slowing down / pulling away): good moment for an odometer reading
            }
        }
        if (!s.has("cur")) s.put("cur", cur)
        save(c, s)
        return describe(c, s.getJSONObject("cur"))
      }
    }

    fun describe(c: Context, cur: JSONObject): String {
        val since = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(cur.optLong("start")))
        return when (cur.optString("type")) {
            "DRIVE" -> "Driving since $since"
            "STORE" -> "At store ${cur.optString("store")}" + (Stores.byNum(c, cur.optString("store"))?.let { " – ${it.city}" } ?: "") + " since $since"
            "HOME" -> "At home since $since"
            else -> "Stopped since $since"
        }
    }

    /** Today's entries plus the one in progress, for the screen. */
    fun today(c: Context): JSONArray {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val out = JSONArray(); val segs = Store.segments(c)
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("end") >= start) out.put(g) }
        val s = state(c)
        if (s.optBoolean("running")) s.optJSONObject("cur")?.let {
            out.put(JSONObject(it.toString()).put("end", System.currentTimeMillis()).put("live", true))
        }
        return out
    }

    /**
     * True once, when the morning trip from home has lasted 30 minutes without reaching a Publix store:
     * the moment personal miles end and work miles begin, so an odometer reading is taken right then.
     */
    fun commuteMark(c: Context, now: Long): Boolean {
        synchronized(lock) {
            val s = state(c)
            val dep = s.optLong("dep", 0L)
            if (!s.optBoolean("running") || dep == 0L || s.optBoolean("reached") || s.optBoolean("mark") || now - dep < 30 * 60_000L) return false
            s.put("mark", true); save(c, s); return true
        }
    }

    /** Marks an entry (or the one in progress) with a tag such as FUEL; empty tag clears it. */
    fun setTag(c: Context, start: Long, tag: String) = synchronized(lock) {
        val segs = Store.segments(c); val out = JSONArray()
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("start") == start) { if (tag.isEmpty()) g.remove("tag") else g.put("tag", tag) }; out.put(g) }
        Store.saveSegments(c, out)
        val s = state(c); s.optJSONObject("cur")?.let { if (it.optLong("start") == start) { if (tag.isEmpty()) it.remove("tag") else it.put("tag", tag); save(c, s) } }
    }

    /** Driving is decided by the van's Bluetooth (connected = driving), not by GPS speed. */
    fun btMode(c: Context) = Store.get(c, "autoBtDrive", "0") == "1" && Store.get(c, "autoStartAddr", "").isNotBlank()

    /** Van connected: a drive starts now. A reconnect within 90 s of a disconnect just continues the same drive. */
    fun btConnected(c: Context, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val s = state(c); if (!s.optBoolean("running")) return@synchronized false
        val cur = s.optJSONObject("cur") ?: JSONObject().put("type", "STOP").put("store", "").put("start", now)
        if (cur.optString("type") == "DRIVE") return@synchronized false
        val segs = Store.segments(c); val lastSeg = if (segs.length() > 0) segs.getJSONObject(segs.length() - 1) else null
        if (now - cur.optLong("start") < 90_000L && lastSeg != null && lastSeg.optString("type") == "DRIVE" && Math.abs(lastSeg.optLong("end") - cur.optLong("start")) < 5_000L) {
            segs.remove(segs.length() - 1); Store.saveSegments(c, segs); lastSeg.remove("end"); s.put("cur", lastSeg)
        } else {
            close(c, cur, now)
            s.put("cur", JSONObject().put("type", "DRIVE").put("store", "").put("start", now))
            if (s.optLong("dep", 0L) == 0L) s.put("dep", now)
        }
        s.remove("cand"); s.put("boundary", now); save(c, s); true
    }

    /** Van disconnected: the drive ends now. Where you are decides store / home / other stop (GPS keeps refining it). */
    fun btDisconnected(c: Context, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val s = state(c); if (!s.optBoolean("running")) return@synchronized false
        val cur = s.optJSONObject("cur") ?: return@synchronized false
        if (cur.optString("type") != "DRIVE") return@synchronized false
        close(c, cur, now)
        var type = "STOP"; var num = ""
        s.optJSONObject("last")?.takeIf { now - it.optLong("t") < 120_000L }?.let { l ->
            val lat = l.optDouble("lat"); val lng = l.optDouble("lng")
            val radius = Store.get(c, "radius", DEFAULT_RADIUS).toDoubleOrNull() ?: 400.0
            val st = Stores.nearest(c, lat, lng)?.takeIf { it.second <= radius }?.first
            if (st != null) { type = "STORE"; num = st.num }
            else if (home(c)?.let { Stores.meters(it.first, it.second, lat, lng) <= HOME_M } == true) type = "HOME"
        }
        if (type == "STORE") s.put("reached", true)
        s.put("cur", JSONObject().put("type", type).put("store", num).put("start", now))
        s.remove("cand"); s.put("boundary", now); save(c, s); true
    }

    private val TYPES = setOf("DRIVE", "STORE", "STOP", "HOME")
    /** Ticket numbers typed for a store visit: digits only, comma separated, at most 12. */
    private fun cleanOrders(v: String) = Regex("\\d{6,12}").findAll(v).map { it.value }.distinct().take(12).joinToString(",")
    private fun sorted(a: JSONArray): JSONArray {
        val l = (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optLong("start") }; val o = JSONArray(); l.forEach { o.put(it) }; return o
    }
    /** Applies your edits (type, store, start/end time, fuel tag) to a finished entry. False if the values don't make sense. */
    fun editSegment(c: Context, origStart: Long, j: JSONObject): Boolean = synchronized(lock) {
        val st = j.optLong("start"); val en = j.optLong("end"); val type = j.optString("type")
        if (en <= st || type !in TYPES || en > System.currentTimeMillis() + 60_000L) return@synchronized false
        val segs = Store.segments(c); var found = false
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("start") != origStart) continue
            found = true
            g.put("start", st).put("end", en).put("type", type).put("store", if (type == "STORE") j.optString("store").filter { it.isDigit() }.take(5) else "").put("orders", if (type == "STORE") cleanOrders(j.optString("orders")) else "")
            if (type == "STOP" && j.optBoolean("fuel")) g.put("tag", "FUEL") else g.remove("tag")
            g.put("edited", System.currentTimeMillis()) }
        if (found) Store.saveSegments(c, sorted(segs))
        found
    }
    /** Changes the entry in progress (type, store, when it started). It keeps going until the next change. */
    fun editCurrent(c: Context, origStart: Long, j: JSONObject): Boolean = synchronized(lock) {
        val s = state(c); val cur = s.optJSONObject("cur") ?: return@synchronized false
        if (!s.optBoolean("running") || cur.optLong("start") != origStart) return@synchronized false
        val st = j.optLong("start"); val type = j.optString("type"); val now = System.currentTimeMillis()
        val segs = Store.segments(c); val prevEnd = if (segs.length() > 0) segs.getJSONObject(segs.length() - 1).optLong("end") else 0L
        if (type !in TYPES || st >= now || st < prevEnd - 60_000L || st < s.optLong("dayStart", 0L) - 60_000L) return@synchronized false
        cur.put("start", st).put("type", type).put("store", if (type == "STORE") j.optString("store").filter { it.isDigit() }.take(5) else "").put("orders", if (type == "STORE") cleanOrders(j.optString("orders")) else "")
        if (type == "STOP" && j.optBoolean("fuel")) cur.put("tag", "FUEL") else cur.remove("tag")
        if (type == "STORE") s.put("reached", true)
        cur.put("edited", now); s.remove("cand"); save(c, s); true
    }

    /** Replaces a day's entries with imported ones. If that day is still running, the timer is ended first. Returns how many were saved. */
    fun importDay(c: Context, day: String, list: JSONArray): Int = synchronized(lock) {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val s = state(c)
        if (s.optBoolean("running") && f.format(java.util.Date(s.optLong("dayStart"))) == day) save(c, JSONObject().put("running", false).put("lastEnd", System.currentTimeMillis()))
        val keep = JSONArray(); val old = Store.segments(c)
        for (i in 0 until old.length()) { val g = old.getJSONObject(i); if (f.format(java.util.Date(g.optLong("start"))) != day) keep.put(g) }
        var n = 0; val now = System.currentTimeMillis()
        for (i in 0 until list.length()) { val j = list.getJSONObject(i)
            val st = j.optLong("start"); val en = j.optLong("end"); val type = j.optString("type")
            if (en <= st || type !in TYPES || en > now + 60_000L || f.format(java.util.Date(st)) != day) continue
            val g = JSONObject().put("type", type).put("store", if (type == "STORE") j.optString("store").filter { it.isDigit() }.take(5) else "").put("orders", if (type == "STORE") cleanOrders(j.optString("orders")) else "")
                .put("start", st).put("end", en).put("m", Math.round(j.optDouble("m", 0.0))).put("imported", true)
            if (type == "STOP" && j.optString("tag") == "FUEL") g.put("tag", "FUEL")
            keep.put(g); n++ }
        Store.saveSegments(c, sorted(keep)); n
    }

    /** Adds an entry you missed (for example a stop before you started the timer). */
    fun addSegment(c: Context, j: JSONObject): Boolean = synchronized(lock) {
        val st = j.optLong("start"); val en = j.optLong("end"); val type = j.optString("type")
        if (en <= st || type !in TYPES || en > System.currentTimeMillis() + 60_000L) return@synchronized false
        val g = JSONObject().put("type", type).put("store", if (type == "STORE") j.optString("store").filter { it.isDigit() }.take(5) else "").put("orders", if (type == "STORE") cleanOrders(j.optString("orders")) else "")
            .put("start", st).put("end", en).put("m", 0).put("manual", true)
        if (type == "STOP" && j.optBoolean("fuel")) g.put("tag", "FUEL")
        // Make room: entries overlapping the new one are trimmed, or split around it (a long drive becomes drive / visit / drive).
        val old = Store.segments(c); val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i); val os = o.optLong("start"); val oe = o.optLong("end")
            if (oe <= st || os >= en) { out.put(o); continue }
            val len = maxOf(1L, oe - os).toDouble(); val m = o.optDouble("m", 0.0)
            if (os < st) out.put(JSONObject(o.toString()).put("end", st).put("m", Math.round(m * (st - os) / len)).put("edited", System.currentTimeMillis()))
            if (oe > en) out.put(JSONObject(o.toString()).put("start", en).put("m", Math.round(m * (oe - en) / len)).put("edited", System.currentTimeMillis()))
        }
        out.put(g); Store.saveSegments(c, sorted(out))
        // and the entry in progress, if the new one reaches into it
        val s = state(c); val cur = s.optJSONObject("cur")
        if (s.optBoolean("running") && cur != null && cur.optLong("start") < en) { cur.put("start", en); save(c, s) }
        true
    }

    fun deleteSegment(c: Context, start: Long) = synchronized(lock) {
        val segs = Store.segments(c); val out = JSONArray()
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("start") != start) out.put(g) }
        Store.saveSegments(c, out)
    }
}
