package com.aaronzandman.companion

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Reads dispatch texts such as
 * "801624020 2035 scale printer scale not advancing Sev: 3 P1593 - THE VILLAGE SHOPPING CENTER Retail Stores."
 * Same rules as shared/dispatch-parser.js and the Route Planner's Import box. Other texts are ignored.
 */
object DispatchParser {
    private val RX = Regex(
        """(\d{9,10})\s+([\s\S]+?)\s+Sev\s*:?\s*(\d)\s+P0*(\d{3,5})\s*[-–—]\s*([\s\S]+?)(?=\s+\d{9,10}\s|\s*${'$'})""",
        RegexOption.IGNORE_CASE
    )
    private val PRIO = mapOf("1" to "1 - Urgent", "2" to "2 - High", "3" to "3 - Standard", "4" to "4 - Low")
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun parse(text: String, receivedAt: Long, sender: String): List<JSONObject> =
        RX.findAll(text).map { m ->
            val center = clean(m.groupValues[5]).replace(Regex("\\s*\\bRetail Stores?\\.?$", RegexOption.IGNORE_CASE), "").trimEnd('.', ' ')
            JSONObject()
                .put("order", m.groupValues[1])
                .put("description", clean(m.groupValues[2]))
                .put("severity", m.groupValues[3].toInt())
                .put("priority", PRIO[m.groupValues[3]] ?: (m.groupValues[3] + " - Standard"))
                .put("store", m.groupValues[4])
                .put("center", center)
                .put("receivedAt", receivedAt)
                .put("sender", sender)
                .put("sent", false)
        }.toList()

    /** 15-column SAP-style row the Route Planner imports; no due date (texts don't carry one), status SMS. */
    fun plannerRow(d: JSONObject): String = listOf(
        "SMS", "", "", "", d.optString("priority"), "P" + d.optString("store"), "", "", "",
        d.optString("order"), d.optString("description"), "", "SMS", "", d.optString("store")
    ).joinToString("\t")

    const val SITE = "https://aaronzandman.github.io/Route2/"

    /** 15-column row carrying the SAP due date + real status, so the Planner's age (from the due date) works. */
    fun plannerRowSap(d: JSONObject): String {
        val dueMs = d.optLong("due", 0L)
        var date = ""; var time = ""
        if (dueMs > 0) { val dt = java.util.Date(dueMs)
            date = java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US).format(dt)
            time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(dt) }
        return listOf(d.optString("otype", "ZSM4"), "", date, time, d.optString("priority"), "P" + d.optString("store"),
            "", "", d.optString("serial", ""), d.optString("order"), d.optString("description"),
            d.optString("workCenter", ""), d.optString("status", "Dispatched"), "", d.optString("store")).joinToString("\t")
    }

    /** A Route Planner link that REPLACES the whole list with the current calls (used by the SAP import). */
    fun overrideLink(c: Context): String? {
        val all = Store.dispatches(c); if (all.length() == 0) return null
        val rows = (0 until all.length()).map { plannerRowSap(all.getJSONObject(it)) }
        val o = JSONObject().put("v", 1).put("src", "companion").put("ov", 1).put("c", rows.joinToString("\n"))
        try { val u = JSONObject(Store.get(c, "uid", "")); if (Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(u.optString("i"))) o.put("u", u) } catch (_: Exception) {}
        return plannerLinkJson(SITE, Store.get(c, "edition", "VA"), o)
    }

    /** Link that opens the Route Planner and offers to ADD these calls (handoff format, data after #, never sent to a server). */
    fun plannerLink(site: String, edition: String, rows: List<String>, uid: JSONObject? = null, extra: JSONObject? = null): String {
        val o = JSONObject().put("v", 1).put("a", 1).put("src", "companion").put("c", rows.joinToString("\n"))
        extra?.keys()?.forEach { k -> o.put(k, extra.get(k)) }   // e.g. rt (reroute/keep) and gps (where you are now)
        if (uid != null) o.put("u", uid)   // anonymous ID only, so the planner counts you as the same person
        return plannerLinkJson(site, edition, o)
    }

    /** Packs any transfer payload into a Route Planner link (#sync=…), compressed the way the planner expects. */
    fun plannerLinkJson(site: String, edition: String, o: JSONObject): String {
        val id = "c" + java.lang.Long.toString(System.currentTimeMillis() % 1_679_616L, 36)
        return "${site}planners/$edition.html#sync=$id.1.1.${pack(o)}"
    }

    /** The planner's packed transfer text: "1" + compressed JSON in URL-safe Base64 (what follows ROUTE2-TRANSFER: in a file). */
    fun pack(o: JSONObject): String {
        val json = o.toString()
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return "1" + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
