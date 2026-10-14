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
    const val CH_UPDATE = "updates"
    const val CH_DAY = "day"
    const val ID_UPDATE = 3

    /** "Update ready" notification; tapping it opens Companion's install prompt. */
    fun updateReady(c: Context, tag: String) {
        channels(c)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(c, MainActivity::class.java).putExtra("install", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val n = NotificationCompat.Builder(c, CH_UPDATE).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Companion ${tag.removePrefix("v")} is ready").setContentText("Tap to install the update. Your data stays.")
            .setContentIntent(PendingIntent.getActivity(c, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(c).notify(ID_UPDATE, n) } catch (_: SecurityException) {}
    }

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_DISPATCH, "Dispatch texts", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_TRACKER, "Timer running", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_STANDBY, "Companion is on", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_DAY, "Day started / ended", NotificationManager.IMPORTANCE_HIGH))
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

    const val ID_AUTODAY = 4
    const val CH_STANDBY = "standby"
    const val ID_STANDBY = 5
    /** Automatic start/end messages. With [tapStarts], tapping opens Companion and starts (or resumes) the day. */
    fun autoDay(c: Context, title: String, text: String, tapStarts: Boolean) {
        DiagLog.w(c, "NOTE", "notification: $title — $text")
        channels(c)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(c, MainActivity::class.java).putExtra("tab", "timer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tapStarts) i.putExtra("startDay", true)
        val pi = PendingIntent.getActivity(c, if (tapStarts) 91 else 92, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, CH_DAY).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
        if (tapStarts) b.addAction(0, "Start my day", pi)
        toast(c, title)
        try { NotificationManagerCompat.from(c).notify(ID_AUTODAY, b.build()) } catch (_: SecurityException) {}
    }

    /** A quick on-screen message too (shows even when Companion is closed). */
    fun toast(c: Context, text: String) {
        if (Store.get(c, "dayToast", "1") != "1") return
        android.os.Handler(android.os.Looper.getMainLooper()).post { try { android.widget.Toast.makeText(c.applicationContext, "Companion: $text", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
    }

    /** "Day ended at home": tapping opens the Timer and, if you email your day, Outlook with the log. */
    fun dayEnded(c: Context, start: Long, end: Long) {
        channels(c)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(end))
        val i = Intent(c, MainActivity::class.java).putExtra("tab", "timer").putExtra("dayStart", start).putExtra("dayEnd", end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, 93, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "Ended at $at when you got home. Tap to review and email your time. Heading out again? Your day picks back up when you start it."
        val n = NotificationCompat.Builder(c, CH_DAY).setSmallIcon(R.drawable.ic_notify).setContentTitle("Day ended at home")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        toast(c, "Day ended at home")
        try { NotificationManagerCompat.from(c).notify(ID_AUTODAY, n) } catch (_: SecurityException) {}
    }

    fun tracker(c: Context, text: String) = NotificationCompat.Builder(c, CH_TRACKER)
        .setSmallIcon(R.drawable.ic_notify).setContentTitle("Companion timer is running").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(c, "timer")).build()
}
