package com.aaronzandman.vandash

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartdevicelink.transport.SdlBroadcastReceiver
import com.smartdevicelink.transport.TransportConstants
import com.smartdevicelink.util.AndroidTools

/** Must be named SdlRouterService and live in the same package as SdlReceiver (SDL library rule). */
class SdlRouterService : com.smartdevicelink.transport.SdlRouterService()

/** Wakes up when the phone connects to SYNC and starts SdlService. */
class SdlReceiver : SdlBroadcastReceiver() {

    override fun onSdlEnabled(context: Context, intent: Intent) {
        VLog.add(context, "SYNC found an AppLink connection, starting Van Dash on the screen")
        intent.setClass(context, SdlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On Android 12+ the service has to be started from the router service's foreground context.
            @Suppress("DEPRECATION")
            val pi = intent.getParcelableExtra<PendingIntent>(TransportConstants.PENDING_INTENT_EXTRA)
            if (pi == null) { VLog.add(context, "No start permission came from the SDL router; open Van Dash on the phone once"); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !AndroidTools.hasForegroundServiceTypePermission(context)) {
                VLog.add(context, "Nearby devices (Bluetooth) permission is off, so Van Dash can't start. Open Van Dash and allow it.")
                return
            }
            try { pi.send(context, 0, intent) } catch (e: Exception) { VLog.add(context, "Couldn't start: ${e.message}") }
        } else {
            context.startForegroundService(intent)
        }
    }

    override fun defineLocalSdlRouterClass(): Class<out com.smartdevicelink.transport.SdlRouterService> =
        SdlRouterService::class.java

    override fun getSdlServiceName(): String = SdlService::class.java.simpleName
}
