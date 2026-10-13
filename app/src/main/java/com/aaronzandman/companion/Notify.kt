package com.aaronzandman.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object Notify {
    const val CH_DISPATCH = "dispatch"
    const val CH_TRACKER = "tracker"
    const val ID_TRACKER = 1

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_DISPATCH, "Dispatch texts", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_TRACKER, "Timer running", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun openApp(c: Context, tab: String): PendingIntent {
        val i = Intent(c, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, tab.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun newDispatch(c: Context, found: List<JSONObject>) {
        channels(c)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val first = found.first()
        val id = first.optString("order").takeLast(8).toIntOrNull() ?: 2
        val title = if (found.size == 1) "New call: store ${first.optString("store")}" + (Stores.byNum(c, first.optString("store"))?.let { " – ${it.city}" } ?: "")
                    else "${found.size} new calls"
        var text = if (found.size == 1) "${first.optString("description")} · Sev ${first.optString("severity")} (#${first.optString("order")})"
                   else found.joinToString(", ") { "store " + it.optString("store") + " (Sev " + it.optString("severity") + ")" }
        val b = NotificationCompat.Builder(c, CH_DISPATCH).setSmallIcon(R.drawable.ic_notify).setContentTitle(title)
            .setContentIntent(openApp(c, "texts")).setAutoCancel(true)
        if (Store.get(c, "autoPlanner", "0") == "1") {
            // Route Planner choices right on the notification, with a suggestion.
            val (pick, why) = suggest(c, found)
            text += "\nSuggested: " + (if (pick == "reroute") "Reroute now" else "Keep my route") + " ($why)"
            val orders = found.joinToString(",") { it.optString("order") }
            b.addAction(0, if (pick == "reroute") "★ Reroute now" else "Reroute now", plannerAction(c, "reroute", orders, id))
             .addAction(0, if (pick == "keep") "★ Keep my route" else "Keep my route", plannerAction(c, "keep", orders, id))
        }
        b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        try { NotificationManagerCompat.from(c).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    /** Opens Companion just long enough to send the calls to Route Planner with your choice. */
    private fun plannerAction(c: Context, mode: String, orders: String, id: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).putExtra("planner", mode).putExtra("orders", orders).putExtra("notif", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, (orders + mode).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Suggests rerouting for urgent calls (severity 1 or 2) or calls within about 10 miles of where you are;
     * otherwise suggests keeping your route and adding the call at the end.
     */
    fun suggest(c: Context, found: List<JSONObject>): Pair<String, String> {
        val sev = found.minOf { it.optInt("severity", 3) }
        val last = Tracker.state(c).optJSONObject("last")?.takeIf { System.currentTimeMillis() - it.optLong("t") < 15 * 60_000L }
        val miles = last?.let { l -> found.mapNotNull { Stores.byNum(c, it.optString("store")) }.minOfOrNull { s -> Stores.meters(l.optDouble("lat"), l.optDouble("lng"), s.lat, s.lng) / 1609.344 } }
        return when {
            sev <= 2 -> Pair("reroute", "Sev $sev is urgent")
            miles != null && miles <= 10 -> Pair("reroute", "about ${miles.toInt().coerceAtLeast(1)} mi from you")
            miles != null -> Pair("keep", "about ${miles.toInt()} mi away, not urgent")
            else -> Pair("keep", "not urgent")
        }
    }

    fun tracker(c: Context, text: String) = NotificationCompat.Builder(c, CH_TRACKER)
        .setSmallIcon(R.drawable.ic_notify).setContentTitle("Companion timer is running").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(c, "timer")).build()
}
