package com.aaronzandman.companion

import android.content.Context
import org.json.JSONArray
import kotlin.math.*

/** Publix store locations (from Route2's store list, bundled at build time). Used to detect store stops. */
object Stores {
    data class S(val num: String, val lat: Double, val lng: Double, val city: String, val center: String)
    @Volatile private var list: List<S>? = null

    fun all(c: Context): List<S> = list ?: synchronized(this) {
        list ?: try {
            val a = JSONArray(c.assets.open("stores.json").bufferedReader().readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val lat = o.optString("Latitude").toDoubleOrNull(); val lng = o.optString("Longitude").toDoubleOrNull()
                if (lat == null || lng == null) null
                else S(o.optString("Store #"), lat, lng, o.optString("City"), o.optString("Shopping Center"))
            }
        } catch (e: Exception) { emptyList() }.also { list = it }
    }

    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0; val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    fun nearest(c: Context, lat: Double, lng: Double): Pair<S, Double>? =
        all(c).map { it to meters(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }

    fun byNum(c: Context, n: String) = all(c).firstOrNull { it.num == n }
}
