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
        val title = if (found.size == 1) "New call: store ${first.optString("store")}" else "${found.size} new calls"
        val text = if (found.size == 1) "${first.optString("description")} (#${first.optString("order")})"
                   else found.joinToString(", ") { "store " + it.optString("store") }
        val n = NotificationCompat.Builder(c, CH_DISPATCH)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, "texts")).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(c).notify(first.optString("order").takeLast(8).toIntOrNull() ?: 2, n) } catch (_: SecurityException) {}
    }

    fun tracker(c: Context, text: String) = NotificationCompat.Builder(c, CH_TRACKER)
        .setSmallIcon(R.drawable.ic_notify).setContentTitle("Companion timer is running").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(c, "timer")).build()
}
