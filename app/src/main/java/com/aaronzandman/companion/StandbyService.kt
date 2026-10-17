package com.aaronzandman.companion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * "Standby": being ready for the van before the day starts, with NO notification.
 *
 * It needs no foreground service — van detection runs entirely on things Android wakes the app for on its own:
 *   • the offloaded Bluetooth LE scan (VanScanReceiver), which the system runs even with Companion closed;
 *   • the van's Bluetooth link (BtReceiver, ACL_CONNECTED);
 *   • the minute voltage check (VanPollReceiver, an AlarmManager alarm).
 * So "start" just arms those watchers. The first time the van connects, the day starts and the timer shows its own
 * notification — which is the first notification you see. Nothing appears in the shade beforehand.
 */
class StandbyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // Defensive: if anything (e.g. a leftover intent from an older version) starts this service, don't go foreground
    // — just arm the watchers and stop. No notification is ever posted here.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { arm(this) } catch (_: Exception) {}
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        fun enabled(c: Context) = Store.get(c, "standby", "1") == "1"

        private fun arm(c: Context) {
            // Remove the old "Companion is on" notification if a previous version left one behind.
            try { androidx.core.app.NotificationManagerCompat.from(c).cancel(Notify.ID_STANDBY) } catch (_: Exception) {}
            if (!enabled(c) || Tracker.isRunning(c)) return
            VanWatch.start(c)   // offloaded LE scan → VanScanReceiver (no notification)
            VanLink.start(c)    // standing LE connect + minute voltage check → VanPollReceiver (no notification)
            // A good moment to look for an update (at most every 6 hours). The notification is gated until the day starts.
            Thread { try { val r = Updater.check(c, 6 * 3600_000L); if (r.optBoolean("ready")) Notify.updateReady(c, r.optString("tag")) } catch (_: Exception) {} }.start()
        }

        /** Be ready for the van, silently. No foreground service, so nothing shows in the notification shade. */
        fun start(c: Context) { arm(c) }

        /** Clear any lingering standby foreground service from an older version (new installs run none). */
        fun stop(c: Context) { try { c.stopService(Intent(c, StandbyService::class.java)) } catch (_: Exception) {} }
    }
}
