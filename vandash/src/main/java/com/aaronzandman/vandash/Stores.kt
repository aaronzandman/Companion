package com.aaronzandman.vandash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONArray

/** Publix stores (copied from Route2's stores.json when the app is built). */
object Stores {
    data class S(
        val num: String, val center: String, val address: String, val city: String,
        val state: String, val zip: String, val lat: Double, val lng: Double, val phone: String,
    )

    @Volatile private var list: List<S>? = null

    fun all(c: Context): List<S> = list ?: synchronized(this) {
        list ?: (try {
            val a = JSONArray(c.assets.open("stores.json").bufferedReader().readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val lat = o.optDouble("Latitude"); val lng = o.optDouble("Longitude")
                if (lat.isNaN() || lng.isNaN()) null
                else S(o.optString("Store #"), o.optString("Shopping Center"), o.optString("Address"),
                    o.optString("City"), o.optString("State"), o.optString("Zip"), lat, lng, o.optString("Phone"))
            }
        } catch (e: Exception) { VLog.add(c, "Could not read the store list: ${e.message}"); emptyList() })
            .also { list = it }
    }

    fun byNumber(c: Context, num: String): S? = all(c).firstOrNull { it.num == num.trim().trimStart('0') }

    fun miles(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 3958.8; val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
        val h = Math.pow(Math.sin(dLat / 2), 2.0) +
            Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.pow(Math.sin(dLng / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    /** Nearest stores to a point, closest first. */
    fun nearest(c: Context, lat: Double, lng: Double, count: Int): List<Pair<S, Double>> =
        all(c).map { it to miles(lat, lng, it.lat, it.lng) }.sortedBy { it.second }.take(count)

    /** Best guess at where we are: the phone's last known fix, else the last one we saved. */
    @SuppressLint("MissingPermission")
    fun phoneLocation(c: Context): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val l = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                if (l != null) { saveLocation(c, l.latitude, l.longitude); return l.latitude to l.longitude }
            } catch (_: Exception) {}
        }
        val p = c.getSharedPreferences("vd", Context.MODE_PRIVATE)
        val lat = p.getFloat("lastLat", Float.NaN); val lng = p.getFloat("lastLng", Float.NaN)
        return if (lat.isNaN() || lng.isNaN()) null else lat.toDouble() to lng.toDouble()
    }

    fun saveLocation(c: Context, lat: Double, lng: Double) {
        c.getSharedPreferences("vd", Context.MODE_PRIVATE).edit()
            .putFloat("lastLat", lat.toFloat()).putFloat("lastLng", lng.toFloat()).apply()
    }
}

/** A small log kept on the phone so test results can be copied and shared. */
object VLog {
    private const val MAX = 400
    private val fmt = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.US)

    @Synchronized fun add(c: Context, line: String) {
        android.util.Log.i("VanDash", line)
        try {
            val f = java.io.File(c.filesDir, "log.txt")
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${fmt.format(java.util.Date())}  $line"
            f.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized fun read(c: Context): String = try {
        java.io.File(c.filesDir, "log.txt").takeIf { it.exists() }?.readText() ?: ""
    } catch (_: Exception) { "" }

    @Synchronized fun clear(c: Context) { try { java.io.File(c.filesDir, "log.txt").delete() } catch (_: Exception) {} }
}
