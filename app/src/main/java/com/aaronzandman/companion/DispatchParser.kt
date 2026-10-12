package com.aaronzandman.companion

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

    /** Link that opens the Route Planner and offers to ADD these calls (handoff format, data after #, never sent to a server). */
    fun plannerLink(site: String, edition: String, rows: List<String>, uid: JSONObject? = null): String {
        val o = JSONObject().put("v", 1).put("a", 1).put("src", "companion").put("c", rows.joinToString("\n"))
        if (uid != null) o.put("u", uid)   // anonymous ID only, so the planner counts you as the same person
        val json = o.toString()
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val id = "c" + java.lang.Long.toString(System.currentTimeMillis() % 1_679_616L, 36)
        return "${site}planners/$edition.html#sync=$id.1.1.1$b64"
    }
}
