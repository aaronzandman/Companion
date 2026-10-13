package com.aaronzandman.companion

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * FordConnect Query: reads the van's odometer from Ford's servers after you sign in with your Ford account.
 * Your Client ID and Secret (from Ford's developer portal) and the sign-in tokens are kept on this phone only.
 */
object Ford {
    private const val BASE = "https://api.vehicle.ford.com"
    private const val AUTH = "$BASE/fcon-public/v1/auth/init"
    private const val TOKEN = "$BASE/dah2vb2cprod.onmicrosoft.com/oauth2/v2.0/token?p=B2C_1A_FCON_AUTHORIZE"
    private const val QUERY = "$BASE/fcon-query/v1"
    const val REDIRECT = "http://localhost:8080/ford/callback"
    private const val KM_PER_MILE = 1.609344

    fun configured(c: Context) = Store.get(c, "fordId", "").isNotBlank() && Store.get(c, "fordSecret", "").isNotBlank()
    fun connected(c: Context) = Store.get(c, "fordRefresh", "").isNotBlank()
    fun useForOdometer(c: Context) = Tier.ford(c) && connected(c) && Store.get(c, "fordOdo", "1") == "1"

    fun saveCredentials(c: Context, id: String, secret: String) {
        if (id.isNotBlank()) Store.put(c, "fordId", id.trim())
        if (secret.isNotBlank()) Store.put(c, "fordSecret", secret.trim())
    }

    fun disconnect(c: Context) { listOf("fordAccess", "fordRefresh", "fordExp", "fordVehicle", "fordGarage").forEach { Store.put(c, it, "") } }

    /** The Ford sign-in page. [state] must be 16 characters or fewer (Ford's limit). */
    fun authUrl(c: Context, state: String): String = Uri.parse(AUTH).buildUpon()
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", Store.get(c, "fordId", ""))
        .appendQueryParameter("redirect_uri", REDIRECT)
        .appendQueryParameter("state", state).build().toString()

    /** Trades the sign-in code for tokens. Returns null on success, or a short error. Run off the main thread. */
    fun exchange(c: Context, code: String): String? = token(c, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT))

    private fun refresh(c: Context): String? = token(c, mapOf("grant_type" to "refresh_token", "refresh_token" to Store.get(c, "fordRefresh", "")))

    private fun token(c: Context, form: Map<String, String>): String? {
        val body = (form + mapOf("client_id" to Store.get(c, "fordId", ""), "client_secret" to Store.get(c, "fordSecret", "")))
            .entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return try {
            val con = URL(TOKEN).openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 20_000; con.readTimeout = 30_000
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            con.outputStream.use { it.write(body.toByteArray()) }
            val ok = con.responseCode in 200..299
            val text = (if (ok) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) return "Ford sign-in failed (${con.responseCode}): " + text.take(200)
            val j = JSONObject(text)
            Store.put(c, "fordAccess", j.optString("access_token"))
            if (j.optString("refresh_token").isNotBlank()) Store.put(c, "fordRefresh", j.optString("refresh_token"))
            Store.put(c, "fordExp", (System.currentTimeMillis() + (j.optLong("expires_in", 1200) - 60) * 1000).toString())
            null
        } catch (e: Exception) { "Couldn't reach Ford: ${e.javaClass.simpleName}" }
    }

    /** When Ford's rate limit lets us ask again (from its "Please wait N seconds" answers). Shared by every caller. */
    @Volatile private var retryAt = 0L
    private val lock = Any()

    /** GET a FordConnect Query path ("garage", "telemetry"). Refreshes the sign-in when needed.
     *  Ford allows only so many requests; when it says to wait, the next call waits (if [patient]) or skips.
     *  [onWait] is told how many seconds we're waiting. Run off the main thread. */
    fun get(c: Context, path: String, patient: Boolean = false, onWait: ((Int) -> Unit)? = null): Pair<Int, String> = synchronized(lock) {
        for (attempt in 0..1) {
            val wait = retryAt - System.currentTimeMillis()
            if (wait > 0) {
                if (!patient || wait > 70_000L) return@synchronized Pair(429, "{\"message\":\"Ford asked us to wait ${(wait + 999) / 1000} more seconds before asking again.\"}")
                onWait?.invoke(((wait + 999) / 1000).toInt()); Thread.sleep(wait)
            }
            val r = request(c, path)
            if (r.first != 429) {
                if (path == "garage" && r.first in 200..299) Store.put(c, "fordGarage", JSONObject().put("t", System.currentTimeMillis()).put("body", r.second).toString())
                return@synchronized r
            }
            val secs = Regex("wait (\\d+) second").find(r.second)?.groupValues?.get(1)?.toIntOrNull() ?: 30
            retryAt = System.currentTimeMillis() + secs * 1000L + 1500L
            if (!patient || attempt == 1) return@synchronized r
        }
        Pair(429, "")
    }

    /** Ford's garage answer (the vehicle itself) saved at sign-in or the last ask, if newer than [maxAgeMs]. */
    fun cachedGarage(c: Context, maxAgeMs: Long): Pair<Long, String>? = try {
        val j = JSONObject(Store.get(c, "fordGarage", "")); val t = j.optLong("t")
        if (System.currentTimeMillis() - t < maxAgeMs && j.optString("body").isNotBlank()) Pair(t, j.optString("body")) else null
    } catch (_: Exception) { null }

    private fun request(c: Context, path: String): Pair<Int, String> {
        if (System.currentTimeMillis() > (Store.get(c, "fordExp", "0").toLongOrNull() ?: 0L)) refresh(c)?.let { return Pair(401, it) }
        return try {
            val con = URL("$QUERY/$path").openConnection() as HttpURLConnection
            con.connectTimeout = 20_000; con.readTimeout = 45_000
            con.setRequestProperty("Authorization", "Bearer " + Store.get(c, "fordAccess", ""))
            con.setRequestProperty("Accept", "application/json")
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            Pair(code, text)
        } catch (e: Exception) { Pair(0, "Couldn't reach Ford: ${e.javaClass.simpleName}") }
    }

    /** The odometer in miles and the time Ford last updated it, or null. Ford reports kilometers. */
    fun readMiles(c: Context): Pair<Double, Long>? {
        val (code, text) = get(c, "telemetry")
        if (code !in 200..299) return null
        return try {
            val j = JSONObject(text)
            saveExtras(c, j)
            val m = j.optJSONObject("metrics")?.optJSONObject("odometer") ?: return null
            val km = m.optDouble("value", Double.NaN); if (km.isNaN()) return null
            val t = parseTime(m.optString("updateTime").ifBlank { j.optString("updateTime") }) ?: System.currentTimeMillis()
            Pair(km / KM_PER_MILE, t)
        } catch (_: Exception) { null }
    }

    /** Keeps Ford's oil-life percentage (and when Ford reported it) for the oil life meter. */
    fun saveExtras(c: Context, j: JSONObject) {
        val o = j.optJSONObject("metrics")?.optJSONObject("oilLifeRemaining") ?: return
        val v = o.opt("value"); val pct = (v as? Number)?.toDouble() ?: (v as? JSONObject)?.optDouble("value") ?: return
        val t = parseTime(o.optString("updateTime").ifBlank { j.optString("updateTime") }) ?: System.currentTimeMillis()
        Store.put(c, "fordOil", JSONObject().put("pct", pct).put("t", t).toString())
    }

    fun parseTime(s: String): Long? {
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val base = f.parse(s.take(19))?.time ?: return null
            val ms = Regex("\\.(\\d{1,3})").find(s)?.groupValues?.get(1)?.padEnd(3, '0')?.toLong() ?: 0L
            base + ms
        } catch (_: Exception) { null }
    }
}
