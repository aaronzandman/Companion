package com.aaronzandman.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the SAP Fiori "OrderSet" data (captured in SapActivity) into Companion dispatch records, the same shape the
 * dispatch texts produce — so SAP calls land in your call history and flow to the Route Planner like any other call.
 * SAP gives the real creation date (StartDateTS) and due date (DueDateTS), so no severity-based guessing is needed.
 */
object Sap {
    private val FIELD = HashMap<String, Regex>()
    private fun field(name: String) = FIELD.getOrPut(name) { Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"") }
    private fun dateField(name: String) = Regex("\"$name\":\"\\\\?/Date\\((\\d+)\\)\\\\?/\"")

    /** The order-type codes with readable names, for the settings list. */
    val TYPE_NAMES = linkedMapOf(
        "ZSM4" to "Service Order Incident",
        "ZSM3" to "Service Order Project",
        "ZSM2" to "Service Order Request",
        "ZSM7" to "Other"
    )

    fun districts(c: Context) = Store.get(c, "sapDistricts", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun types(c: Context) = Store.get(c, "sapTypes", "ZSM4").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Short district label from a Work Center code, e.g. "CLT_20E" → "20E". */
    fun districtOf(workCenter: String) = workCenter.substringAfterLast('_').ifBlank { workCenter }

    /** Pulls every Order out of one captured response body. */
    fun parse(body: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (m in Regex("\"OrderId\":\"(\\d+)\"").findAll(body)) {
            val oid = m.groupValues[1]
            if (!seen.add(oid)) continue
            val lo = (m.range.first - 60).coerceAtLeast(0)
            val hi = (m.range.first + 1400).coerceAtMost(body.length)
            val w = body.substring(lo, hi)
            fun g(k: String) = field(k).find(w)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            fun gd(k: String) = dateField(k).find(w)?.groupValues?.get(1)?.toLongOrNull()
            val store = g("StoreId").ifBlank { g("CustomerId").removePrefix("P") }
            val sev = g("PriorityId").toIntOrNull() ?: 3
            val wc = g("WorkCenter")
            out.add(JSONObject()
                .put("order", oid)
                .put("description", g("OrderText"))
                .put("severity", sev)
                .put("priority", g("PriorityText").ifBlank { "$sev - Standard" })
                .put("store", store)
                .put("center", g("WorkCenterName").ifBlank { g("ServiceTeamName") })
                .put("workCenter", wc)
                .put("district", districtOf(wc))
                .put("otype", g("OrderType"))
                .put("status", g("StatusText"))
                .put("receivedAt", gd("StartDateTS") ?: System.currentTimeMillis())
                .put("due", gd("DueDateTS") ?: 0L)
                .put("city", g("City"))
                .put("src", "sap")
                .put("sent", false))
        }
        return out
    }

    /** Remembers the districts and order types seen, so Settings can list them as choices. */
    fun remember(c: Context, orders: List<JSONObject>) {
        if (orders.isEmpty()) return
        fun merge(key: String, vals: Collection<String>) {
            val set = Store.get(c, key, "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            set.addAll(vals.filter { it.isNotBlank() })
            Store.put(c, key, set.sorted().joinToString(","))
        }
        merge("sapSeenDist", orders.map { it.optString("district") })
        merge("sapSeenTypes", orders.map { it.optString("otype") })
    }

    /** Applies the saved district and order-type filters. */
    fun filtered(c: Context, orders: List<JSONObject>): List<JSONObject> {
        val d = districts(c); val t = types(c)
        return orders.filter { o ->
            (d.isEmpty() || o.optString("district") in d) && (t.isEmpty() || o.optString("otype") in t)
        }
    }

    /** Parse → remember choices → filter → add to history. Returns Pair(found, added). */
    fun importBody(c: Context, body: String): Pair<Int, Int> {
        val all = parse(body)
        remember(c, all)
        val keep = filtered(c, all)
        val added = if (keep.isEmpty()) 0 else Store.addDispatches(c, keep)
        if (all.isNotEmpty()) Store.put(c, "sapLastImport", System.currentTimeMillis().toString())
        return Pair(all.size, added)
    }

    fun seenDistricts(c: Context): JSONArray = JSONArray(Store.get(c, "sapSeenDist", "").split(",").filter { it.isNotBlank() })
    fun configured(c: Context) = Store.get(c, "sapUrl", "").isNotBlank()
}
