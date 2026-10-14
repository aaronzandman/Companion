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

    /** [force]: the vLinker was seen (VanWatch), which counts as "the van connected" whatever device is picked above. */
    fun onConnected(c: Context, addr: String, name: String, force: Boolean = false) {
        DiagLog.w(c, "AUTO", "onConnected $name $addr force=$force · ${DiagLog.dayState(c)}")
        if (!force && !addr.equals(Store.get(c, "autoStartAddr", ""), true)) return
        val drive = Tracker.btMode(c)
        fun why(m: String) { if (force) VanWatch.log(c, m) else DiagLog.w(c, "AUTO", m) }
        if (Tracker.isRunning(c)) { why("day already running" + if (drive) " → driving" else ""); if (drive && Tracker.btConnected(c)) TrackerService.ping(c); return }
        if (!force && !startEnabled(c)) return
        if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION)) { why("can't start: location not allowed")
            Notify.autoDay(c, "$name connected", "Allow location for Companion so your day can start by itself. Tap to open.", true); return
        }
        val resume = Tracker.resumable(c)
        if (!resume && Tracker.endedToday(c)) {   // you ended the day yourself earlier: ask, don't assume
            why("you already ended a day today → asked with a notification instead")
            Notify.autoDay(c, "$name connected", "You already ended a day today. Tap to start a new one.", true); return
        }
        if (backgroundOk(c)) {
            try {
                if (resume) Tracker.resumeDay(c) else Tracker.startDay(c)
                if (drive) Tracker.btConnected(c)
                TrackerService.start(c)
                why(if (resume) "day picked back up" else "DAY STARTED")
                val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                Notify.autoDay(c, if (resume) "Day picked back up" else "Day started", "${if (resume) "Continued" else "Started"} at $at when $name connected.", false)
                return
            } catch (e: Exception) { why("start failed: ${e.javaClass.simpleName} → asked with a notification"); Tracker.cancelStart(c) }
        } else why("no 'Allow all the time' location → asked with a notification")
        Notify.autoDay(c, "$name connected", if (resume) "Tap to pick your day back up." else "Tap to start your day.", true)
    }

    fun onDisconnected(c: Context, addr: String, force: Boolean = false) {
        DiagLog.w(c, "AUTO", "onDisconnected $addr force=$force · btMode=${Tracker.btMode(c)} · ${DiagLog.dayState(c)}")
        if (force) { if (Store.get(c, "autoBtDrive", "0") != "1") return }
        else if (!addr.equals(Store.get(c, "autoStartAddr", ""), true) || !Tracker.btMode(c)) return
        if (Tracker.btDisconnected(c)) { TrackerService.ping(c); if (checkHome(c)) TrackerService.stop(c) }
    }

    /** Called with each location update while the day runs. True if the day was just ended at home. */
    fun checkHome(c: Context): Boolean {
        if (!endEnabled(c)) return false
        val st = Tracker.state(c); val cur = st.optJSONObject("cur") ?: return false
        if (cur.optString("type") != "HOME" || !(st.optBoolean("reached") || st.optLong("dep", 0L) > 0L)) return false
        val start = st.optLong("dayStart"); val now = System.currentTimeMillis()
        // Never in the first 10 minutes of a day, and only after a real trip (a store reached, or 10+ minutes since leaving home)
        if (now - start < 10 * 60_000L) return false
        if (!st.optBoolean("reached") && now - st.optLong("dep", now) < 10 * 60_000L) return false
        DiagLog.w(c, "HOME", "arrived home → ending day (end-at-home is on; left home=${st.optLong("dep") > 0}, reached store=${st.optBoolean("reached")}, day ${(now - start) / 1000}s old) ← ${DiagLog.caller()}")
        Tracker.endDay(c, now, auto = true)
        Notify.dayEnded(c, start, now)
        return true
    }
}

/** Android tells Companion when a Bluetooth device connects. Only the device you picked does anything. */
class BtReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED && i.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val name = try { d.name } catch (_: SecurityException) { null } ?: Store.get(c, "autoStartName", "Bluetooth")
        val pr = goAsync()
        val on = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
        DiagLog.w(c, "BT", "${if (on) "LINK UP" else "LINK DOWN"} $name ${d.address}" + when {
            d.address.equals(Store.get(c, "autoStartAddr", ""), true) -> " (= your auto-start device)"
            d.address.equals(Store.get(c, "obdAddr", ""), true) -> " (= the OBD adapter)"
            d.address.equals(Store.get(c, "vanAclAddr", ""), true) -> " (= van's own Bluetooth)"
            else -> " (not used by Companion)" })
        Thread {
            try {
                val obd = Store.get(c, "obdAddr", "")
                // Companion's own connections to the adapter (odometer reads, Test connect, the van session) are not van events.
                if (obd.isNotBlank() && (d.address.equals(obd, true) || d.address.equals(VanWatch.leAddr(c) ?: "-", true))) {
                    DiagLog.w(c, "BT", "  ↳ ignored: that's Companion's own connection to the adapter"); return@Thread
                }
                if (on) VanWatch.noteAcl(c, d.address, name)
                val van = VanWatch.aclAddr(c).isNotBlank() && d.address.equals(VanWatch.aclAddr(c), true)
                if (van && VanSession.enabled(c)) { if (on) VanSession.onPing(c) }   // van's radio pinged: connect to and hold the vLinker
                else if (van) { if (on) VanWatch.vanOn(c, name) }   // the van's own Bluetooth only links for a second: use the link, ignore the drop
                else if (on) AutoDay.onConnected(c, d.address, name) else AutoDay.onDisconnected(c, d.address)
            } catch (_: Exception) {} finally { pr.finish() }
        }.start()
    }
}

/**
 * After the phone restarts (or Companion updates itself), a day that was running carries on: the timer starts again.
 * If Android won't let it start in the background, a notification asks for one tap instead. Nothing is started if no day was running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DiagLog.w(c, "BOOT", "${if (i.action == Intent.ACTION_BOOT_COMPLETED) "phone restarted" else "Companion updated"} · ${DiagLog.dayState(c)}")
        VanWatch.start(c)   // background watch for the vLinker doesn't survive a restart or update
        VanLink.start(c)
        if (!Tracker.isRunning(c)) { StandbyService.start(c); return }   // no day running: just be on and ready
        if (Store.get(c, "autoBoot", "1") != "1") { StandbyService.start(c); return }
        val why = if (i.action == Intent.ACTION_BOOT_COMPLETED) "Your phone restarted" else "Companion updated"
        if (AutoDay.backgroundOk(c) && ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { ContextCompat.startForegroundService(c, Intent(c, TrackerService::class.java).putExtra("resumeNote", why)); return } catch (_: Exception) {}
        }
        Notify.autoDay(c, "$why", "Your day is still running. Tap to keep the timer going.", true)
    }
}
