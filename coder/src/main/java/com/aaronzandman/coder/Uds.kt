package com.aaronzandman.coder

/**
 * Minimal UDS (ISO 14229) client over an ELM327/STN adapter.
 * Reads with service 0x22 (ReadDataByIdentifier), writes with 0x2E (WriteDataByIdentifier),
 * session control 0x10, and security access 0x27. ISO-TP framing and flow control are left to the
 * adapter (headers off, auto-format on), which is fine for the short as-built blocks used here.
 */
class Uds(private val link: Elm.Link, val log: StringBuilder? = null) {

    fun cmd(c: String, t: Long = 4000): String {
        val r = link.send(c, t); log?.append("$c → ${r.trim().replace("\r", " ")}\n"); return r
    }
    private fun hex(s: String) = s.uppercase().replace(Regex("[^0-9A-F]"), "")

    /** Returns true if the vehicle answered the supported-PIDs query (the bus is reachable). */
    fun init(protocol: String): Boolean {
        cmd("ATD", 1200); cmd("ATZ", 3000); Thread.sleep(300)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAL", "ATCAF1", "ATAT1")) cmd(c, 1500)
        cmd("ATSP$protocol", 1500)
        return hex(cmd("0100", 4000)).contains("4100")
    }

    fun setModule(reqId: String, respId: String) {
        cmd("ATSH$reqId", 1000)
        cmd("ATCRA$respId", 1000)
        cmd("ATFCSH$reqId", 1000)
        cmd("ATFCSD300000", 1000)
        cmd("ATFCSM1", 1000)
    }

    data class Resp(val data: String?, val raw: String, val error: String?) { val ok get() = error == null && data != null }

    private fun request(payload: String, positive: String, timeout: Long = 6000): Resp {
        val raw = cmd(payload, timeout); val h = hex(raw)
        Regex("7F[0-9A-F]{2}([0-9A-F]{2})").find(h)?.let {
            return Resp(null, raw, "rejected — NRC ${it.groupValues[1]} (${nrc(it.groupValues[1])})")
        }
        val idx = h.indexOf(positive)
        if (idx < 0) return Resp(null, raw,
            if (h.isEmpty() || h.contains("NODATA")) "no response from this module (wrong address, or it doesn't have this item)"
            else if (h.contains("UNABLETOCONNECT") || h.contains("SEARCHING")) "couldn't open the bus (protocol / CAN FD / van not fully on)"
            else "unexpected reply")
        return Resp(h.substring(idx + positive.length), raw, null)
    }

    /** Extended diagnostic session (needed before writing on most modules). */
    fun session(level: String = "03") = request("10$level", "50$level", 4000)

    fun readDid(did: String) = request("22${did.uppercase()}", "62${did.uppercase()}", 6000)
    fun writeDid(did: String, data: String) = request("2E${did.uppercase()}${data.uppercase()}", "6E${did.uppercase()}", 6000)

    /** Security access: ask for the seed. The key is computed by a routine this app does not include (Ford-proprietary). */
    fun requestSeed(level: String = "01") = request("27$level", "67$level", 4000)
    fun sendKey(level: String, key: String): Resp {
        val next = String.format("%02X", (level.toInt(16) + 1))
        return request("27$next${key.uppercase()}", "67$next", 4000)
    }

    fun nrc(code: String) = when (code.uppercase()) {
        "10" -> "general reject"; "11" -> "service not supported"; "12" -> "sub-function not supported"
        "13" -> "wrong length"; "22" -> "conditions not correct"; "31" -> "request out of range"
        "33" -> "security access denied"; "35" -> "invalid key"; "36" -> "too many attempts"
        "37" -> "time delay not expired"; "78" -> "busy, response pending"
        "7E", "7F" -> "not supported in this session"; else -> "UDS error $code"
    }
}
