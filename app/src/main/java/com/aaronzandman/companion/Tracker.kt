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
        val segs = Store.segments(c)
        val g = JSONObject().put("type", cur.optString("type")).put("store", cur.optString("store"))
            .put("start", start).put("end", end).put("m", Math.round(cur.optDouble("m", 0.0)))
        cur.optJSONArray("trk")?.let { trk -> trk.put(JSONArray().put(end / 1000).put(Math.round(cur.optDouble("m", 0.0)))); g.put("trk", trk) }
        if (cur.optString("tag").isNotEmpty()) g.put("tag", cur.optString("tag"))
        segs.put(g)
        Store.saveSegments(c, segs)
    }

    /** Called for every location fix. Returns a short description of what's happening now. */
    fun onFix(c: Context, lat: Double, lng: Double, speedIn: Float?, t: Long): String {
      synchronized(lock) {
        val s = state(c)
        if (!s.optBoolean("running")) return "Not tracking"
        val last = s.optJSONObject("last")
        var speed = speedIn?.toDouble() ?: -1.0
        if (speed < 0 && last != null) {
            val dt = (t - last.optLong("t")) / 1000.0
            if (dt > 0) speed = Stores.meters(last.optDouble("lat"), last.optDouble("lng"), lat, lng) / dt
        }
        s.put("last", JSONObject().put("lat", lat).put("lng", lng).put("t", t).put("speed", speed))
        val stepM = if (last != null) Stores.meters(last.optDouble("lat"), last.optDouble("lng"), lat, lng) else 0.0

        val radius = Store.get(c, "radius", "250").toDoubleOrNull() ?: 250.0
        val near = Stores.nearest(c, lat, lng)?.takeIf { it.second <= radius }?.first
        val atHome = home(c)?.let { Stores.meters(it.first, it.second, lat, lng) <= HOME_M } == true
        val cur = s.optJSONObject("cur") ?: JSONObject().put("type", "STOP").put("store", "").put("start", t)
        // GPS distance while moving (ignores parked jitter and impossible jumps)
        if (stepM in 0.0..5000.0 && speed >= 2.0) cur.put("m", cur.optDouble("m", 0.0) + stepM)
        // Distance track: a point at least every 60 s, so miles can be split at any moment of a drive.
        run {
            val trk = cur.optJSONArray("trk") ?: JSONArray().put(JSONArray().put(cur.optLong("start") / 1000).put(0)).also { cur.put("trk", it) }
            val lastT = trk.optJSONArray(trk.length() - 1)?.optLong(0) ?: 0L
            if (t / 1000 - lastT >= 60) trk.put(JSONArray().put(t / 1000).put(Math.round(cur.optDouble("m", 0.0))))
        }
        val obsType: String; val obsStore: String
        when {
            speed >= 4.5 -> { obsType = "DRIVE"; obsStore = "" }
            near != null && speed < 2.5 -> { obsType = "STORE"; obsStore = near.num }
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

    fun deleteSegment(c: Context, start: Long) = synchronized(lock) {
        val segs = Store.segments(c); val out = JSONArray()
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("start") != start) out.put(g) }
        Store.saveSegments(c, out)
    }
}
