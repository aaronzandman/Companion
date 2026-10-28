package com.aaronzandman.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The "Next ticket" / "Undo" buttons on the at-store notification. "Next ticket" closes the ticket you were working and
 * starts the next one (a lap), so each ticket at the same store bills separately. The notification updates right away.
 */
class StoreLapReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        Thread {
            try {
                when (i.action) {
                    "lap" -> Tracker.storeLap(c)
                    "undo" -> Tracker.storeLapUndo(c)
                }
                TrackerService.postStoreNote(c)   // reflect the change on the notification immediately
            } catch (_: Exception) {} finally { pr.finish() }
        }.start()
    }
}
