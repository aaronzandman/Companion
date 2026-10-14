package com.aaronzandman.companion

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.time.OffsetDateTime

/**
 * Google Maps Timeline export (Settings → Location → Timeline → Export Timeline data → Timeline.json) → Companion log
 * entries. The file can be large (years of history), so it's read as a stream and only the last [days] days are kept.
 * Visits become a store (within the store radius), home, or a stop; drives keep Google's distance.
 */
object Timeline {
    private val VEHICLE = setOf("IN_PASSENGER_VEHICLE", "IN_VEHICLE", "DRIVING", "IN_CAR", "MOTORCYCLING", "IN_TAXI")

    private fun time(s: String): Long = try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
    private fun latLng(s: String?): Pair<Double, Double>? {
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\D+?(-?\\d+(?:\\.\\d+)?)").find(s ?: return null) ?: return null
        return Pair(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }

    /** Reads any JSON value into org.json (only used for one segment at a time, so it stays small). */
    private fun value(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> { val o = JSONObject(); r.beginObject(); while (r.hasNext()) o.put(r.nextName(), value(r)); r.endObject(); o }
        JsonToken.BEGIN_ARRAY -> { val a = JSONArray(); r.beginArray(); while (r.hasNext()) a.put(value(r)); r.endArray(); a }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextDouble()
        JsonToken.BOOLEAN -> r.nextBoolean()
        else -> { r.nextNull(); null }
    }

    /** Returns {"entries":[{type,store,start,end,m}], "days":[...]} or {"err": "..."}. */
    fun read(c: Context, input: InputStream, days: Int = 60): JSONObject {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val raw = mutableListOf<JSONObject>()
        try {
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) return JSONObject().put("err", "That isn't a Google Timeline file.")
                r.beginObject()
                var found = false
                while (r.hasNext()) {
                    if (r.nextName() == "semanticSegments") {
                        found = true
                        r.beginArray()
                        while (r.hasNext()) {
                            val seg = value(r) as? JSONObject ?: continue
                            if (time(seg.optString("endTime")) >= cutoff) raw += seg
                        }
                        r.endArray()
                    } else r.skipValue()
                }
                r.endObject()
                if (!found) return JSONObject().put("err", "No Timeline entries in that file. Export it from Settings → Location → Timeline → Export Timeline data.")
            }
        } catch (e: Exception) { return JSONObject().put("err", "Couldn't read that file (${e.javaClass.simpleName}).") }

        val radius = Store.get(c, "radius", Tracker.DEFAULT_RADIUS).toDoubleOrNull() ?: 400.0
        val home = Tracker.home(c)
        val out = mutableListOf<JSONObject>()
        for (seg in raw.sortedBy { time(it.optString("startTime")) }) {
            val s = time(seg.optString("startTime")); val e = time(seg.optString("endTime"))
            if (s == 0L || e <= s) continue
            val visit = seg.optJSONObject("visit"); val act = seg.optJSONObject("activity")
            val g = when {
                visit != null -> {
                    val top = visit.optJSONObject("topCandidate")
                    val ll = latLng(top?.optJSONObject("placeLocation")?.optString("latLng"))
                    val st = ll?.let { Stores.nearest(c, it.first, it.second) }?.takeIf { it.second <= radius + 100.0 }?.first
                    val atHome = top?.optString("semanticType") == "HOME" || (ll != null && home != null && Stores.meters(home.first, home.second, ll.first, ll.second) <= 250.0)
                    when { st != null -> JSONObject().put("type", "STORE").put("store", st.num); atHome -> JSONObject().put("type", "HOME").put("store", ""); else -> JSONObject().put("type", "STOP").put("store", "") }
                }
                act != null -> {
                    val m = act.optDouble("distanceMeters", 0.0)
                    val kind = act.optJSONObject("topCandidate")?.optString("type") ?: ""
                    val mph = if (e > s) m / ((e - s) / 1000.0) * 2.237 else 0.0
                    if (kind in VEHICLE || (m > 1500 && mph > 12)) JSONObject().put("type", "DRIVE").put("store", "").put("m", m)
                    else if (e - s >= 3 * 60_000L) JSONObject().put("type", "STOP").put("store", "")   // a walk etc. between visits
                    else null
                }
                else -> null
            } ?: continue
            g.put("start", s).put("end", e).put("tag", "")
            if (!g.has("m")) g.put("m", 0.0)
            val p = out.lastOrNull()
            if (p != null && p.optString("type") == g.optString("type") && p.optString("store") == g.optString("store") && g.optLong("start") - p.optLong("end") < 10 * 60_000L) {
                p.put("end", g.optLong("end")).put("m", p.optDouble("m") + g.optDouble("m"))   // same place / one drive split in two
            } else out += g
        }
        if (out.isEmpty()) return JSONObject().put("err", "No visits or drives in the last $days days in that file.")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return JSONObject().put("entries", JSONArray(out)).put("days", JSONArray(out.map { fmt.format(java.util.Date(it.optLong("start"))) }.distinct()))
    }
}
