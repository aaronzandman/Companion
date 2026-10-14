package com.aaronzandman.companion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Companion running in the background when no day is running (after a restart, or after you open it),
 * so it's ready for dispatch texts, the van's Bluetooth and updates. Shows a quiet "Companion is on" notification,
 * which Android requires. Uses no GPS. When your day starts, the timer's notification takes over.
 */
class StandbyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enabled(this) || Tracker.isRunning(this)) { stop(); return START_NOT_STICKY }
        Notify.channels(this)
        val n = NotificationCompat.Builder(this, Notify.CH_STANDBY).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Companion is on").setContentText("Ready for dispatch texts, your van's Bluetooth and updates.")
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notify.openApp(this, "timer")).build()
        try {
            ServiceCompat.startForeground(this, Notify.ID_STANDBY, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        // A good moment to look for an update (at most every 6 hours)
        Thread { try { val r = Updater.check(this, 6 * 3600_000L); if (r.optBoolean("ready")) Notify.updateReady(this, r.optString("tag")) } catch (_: Exception) {} }.start()
        return START_STICKY
    }

    private fun stop() { try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}; stopSelf() }

    companion object {
        fun enabled(c: Context) = Store.get(c, "standby", "1") == "1"
        /** Start standby if it's on and no day is running (the timer has its own notification). */
        fun start(c: Context) {
            if (!enabled(c) || Tracker.isRunning(c)) return
            try { ContextCompat.startForegroundService(c, Intent(c, StandbyService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) { try { c.stopService(Intent(c, StandbyService::class.java)) } catch (_: Exception) {} }
    }
}
