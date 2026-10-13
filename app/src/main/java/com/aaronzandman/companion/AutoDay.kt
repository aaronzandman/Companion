package com.aaronzandman.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Hands-free day: start when the phone connects to a chosen Bluetooth device (the van's radio or the adapter),
 * and end when you arrive home. If the day ended at home earlier today (lunch at home), connecting again
 * picks the same day back up instead of starting a new one.
 */
object AutoDay {
    fun startEnabled(c: Context) = Store.get(c, "autoStartBt", "0") == "1" && Store.get(c, "autoStartAddr", "").isNotBlank()
    fun endEnabled(c: Context) = Store.get(c, "autoEndHome", "0") == "1"

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    /** "Allow all the time" location: lets Android start the timer while Companion is closed. */
    fun backgroundOk(c: Context) = Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun onConnected(c: Context, addr: String, name: String) {
        if (!startEnabled(c) || !addr.equals(Store.get(c, "autoStartAddr", ""), true)) return
        if (Tracker.isRunning(c)) return
        if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION)) {
            Notify.autoDay(c, "$name connected", "Allow location for Companion so your day can start by itself. Tap to open.", true); return
        }
        val resume = Tracker.resumable(c)
        if (!resume && Tracker.endedToday(c)) {   // you ended the day yourself earlier: ask, don't assume
            Notify.autoDay(c, "$name connected", "You already ended a day today. Tap to start a new one.", true); return
        }
        if (backgroundOk(c)) {
            try {
                if (resume) Tracker.resumeDay(c) else Tracker.startDay(c)
                TrackerService.start(c)
                val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                Notify.autoDay(c, if (resume) "Day picked back up" else "Day started", "${if (resume) "Continued" else "Started"} at $at when $name connected.", false)
                return
            } catch (_: Exception) { Tracker.cancelStart(c) }
        }
        Notify.autoDay(c, "$name connected", if (resume) "Tap to pick your day back up." else "Tap to start your day.", true)
    }

    /** Called with each location update while the day runs. True if the day was just ended at home. */
    fun checkHome(c: Context): Boolean {
        if (!endEnabled(c)) return false
        val st = Tracker.state(c); val cur = st.optJSONObject("cur") ?: return false
        if (cur.optString("type") != "HOME" || !(st.optBoolean("reached") || st.optLong("dep", 0L) > 0L)) return false
        val start = st.optLong("dayStart"); val now = System.currentTimeMillis()
        Tracker.endDay(c, now, auto = true)
        Notify.dayEnded(c, start, now)
        return true
    }
}

/** Android tells Companion when a Bluetooth device connects. Only the device you picked does anything. */
class BtReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val name = try { d.name } catch (_: SecurityException) { null } ?: Store.get(c, "autoStartName", "Bluetooth")
        val pr = goAsync()
        Thread { try { AutoDay.onConnected(c, d.address, name) } finally { pr.finish() } }.start()
    }
}
