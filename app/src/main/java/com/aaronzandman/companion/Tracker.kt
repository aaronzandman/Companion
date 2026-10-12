package com.aaronzandman.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns location fixes into time entries:
 *  DRIVE  – moving faster than ~10 mph for 45 s
 *  STORE  – within the store radius (default 250 m) and slow for 90 s
 *  STOP   – stopped somewhere that isn't a store for 3 min
 * Entries shorter than 1 minute are dropped. State is saved so it survives the app closing.
 */
object Tracker {
    private const val DRIVE_MS = 45_000L
    private const val STORE_MS = 90_000L
    private const val STOP_MS = 180_000L
    private val lock = Any()

    fun state(c: Context) = JSONObject(Store.get(c, "trk", "{}"))
    private fun save(c: Context, s: JSONObject) = Store.put(c, "trk", s.toString())
    fun isRunning(c: Context) = state(c).optBoolean("running", false)

    fun startDay(c: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val s = JSONObject().put("running", true).put("dayStart", now)
            .put("cur", JSONObject().put("type", "STOP").put("store", "").put("start", now))
        save(c, s)
    }

    fun endDay(c: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val s = state(c)
        s.optJSONObject("cur")?.let { close(c, it, now) }
        save(c, JSONObject().put("running", false).put("lastEnd", now))
    }

    private fun close(c: Context, cur: JSONObject, end: Long) {
        val start = cur.optLong("start")
        if (end - start < 60_000L) return
        val segs = Store.segments(c)
        segs.put(JSONObject().put("type", cur.optString("type")).put("store", cur.optString("store"))
            .put("start", start).put("end", end))
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

        val radius = Store.get(c, "radius", "250").toDoubleOrNull() ?: 250.0
        val near = Stores.nearest(c, lat, lng)?.takeIf { it.second <= radius }?.first
        val cur = s.optJSONObject("cur") ?: JSONObject().put("type", "STOP").put("store", "").put("start", t)
        val obsType: String; val obsStore: String
        when {
            speed >= 4.5 -> { obsType = "DRIVE"; obsStore = "" }
            near != null && speed < 2.5 -> { obsType = "STORE"; obsStore = near.num }
            speed in 0.0..1.5 -> { obsType = "STOP"; obsStore = "" }
            else -> { obsType = cur.optString("type"); obsStore = cur.optString("store") }
        }
        if (obsType == cur.optString("type") && obsStore == cur.optString("store")) {
            s.remove("cand")
        } else {
            val cand = s.optJSONObject("cand")
            if (cand != null && cand.optString("type") == obsType && cand.optString("store") == obsStore) {
                val need = when (obsType) { "DRIVE" -> DRIVE_MS; "STORE" -> STORE_MS; else -> STOP_MS }
                val since = cand.optLong("since")
                if (t - since >= need) {
                    close(c, cur, since)
                    s.put("cur", JSONObject().put("type", obsType).put("store", obsStore).put("start", since))
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

    fun deleteSegment(c: Context, start: Long) = synchronized(lock) {
        val segs = Store.segments(c); val out = JSONArray()
        for (i in 0 until segs.length()) { val g = segs.getJSONObject(i); if (g.optLong("start") != start) out.put(g) }
        Store.saveSegments(c, out)
    }
}
