package com.aaronzandman.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything Companion saves lives here, on the phone only (private app storage). */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val lock = Any()

    fun get(c: Context, key: String, def: String): String = prefs(c).getString(key, def) ?: def
    fun put(c: Context, key: String, value: String) { prefs(c).edit().putString(key, value).apply() }

    fun dispatches(c: Context): JSONArray = JSONArray(get(c, "dispatches", "[]"))

    /** Adds new dispatches (newest first, no duplicate order numbers). Returns how many were new. */
    fun addDispatches(c: Context, found: List<JSONObject>): Int {
      synchronized(lock) {
        val cur = dispatches(c)
        val have = HashSet<String>()
        for (i in 0 until cur.length()) have.add(cur.getJSONObject(i).optString("order"))
        val fresh = found.filter { have.add(it.optString("order")) }
        if (fresh.isEmpty()) return 0
        val out = JSONArray()
        fresh.sortedByDescending { it.optLong("receivedAt") }.forEach { out.put(it) }
        for (i in 0 until minOf(cur.length(), 500)) out.put(cur.getJSONObject(i))
        put(c, "dispatches", out.toString())
        return fresh.size
      }
    }

    fun updateDispatches(c: Context, fn: (JSONObject) -> JSONObject?) = synchronized(lock) {
        val cur = dispatches(c); val out = JSONArray()
        for (i in 0 until cur.length()) fn(cur.getJSONObject(i))?.let { out.put(it) }
        put(c, "dispatches", out.toString())
    }

    fun segments(c: Context): JSONArray = JSONArray(get(c, "segments", "[]"))
    fun saveSegments(c: Context, a: JSONArray) {
        // keep about 60 days
        val cut = System.currentTimeMillis() - 60L * 86_400_000L
        val out = JSONArray()
        for (i in 0 until a.length()) { val s = a.getJSONObject(i); if (s.optLong("end", s.optLong("start")) >= cut) out.put(s) }
        put(c, "segments", out.toString())
    }
}
