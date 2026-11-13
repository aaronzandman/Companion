package com.aaronzandman.vandash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.managers.screen.AlertView
import com.smartdevicelink.managers.screen.SoftButtonObject
import com.smartdevicelink.managers.screen.choiceset.ChoiceCell
import com.smartdevicelink.managers.screen.choiceset.ChoiceSet
import com.smartdevicelink.managers.screen.choiceset.ChoiceSetSelectionListener
import com.smartdevicelink.managers.screen.menu.MenuCell
import com.smartdevicelink.managers.screen.menu.MenuSelectionListener
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.RPCResponse
import com.smartdevicelink.proxy.rpc.GetVehicleData
import com.smartdevicelink.proxy.rpc.GetVehicleDataResponse
import com.smartdevicelink.proxy.rpc.OasisAddress
import com.smartdevicelink.proxy.rpc.OnButtonEvent
import com.smartdevicelink.proxy.rpc.OnButtonPress
import com.smartdevicelink.proxy.rpc.OnHMIStatus
import com.smartdevicelink.proxy.rpc.SendLocation
import com.smartdevicelink.proxy.rpc.enums.AppHMIType
import com.smartdevicelink.proxy.rpc.enums.DeliveryMode
import com.smartdevicelink.proxy.rpc.enums.HMILevel
import com.smartdevicelink.proxy.rpc.enums.InteractionMode
import com.smartdevicelink.proxy.rpc.enums.Language
import com.smartdevicelink.proxy.rpc.enums.PredefinedWindows
import com.smartdevicelink.proxy.rpc.enums.Result
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType
import com.smartdevicelink.proxy.rpc.enums.TriggerSource
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.util.SystemInfo
import java.util.Vector

/**
 * Runs while the phone is connected to SYNC. Shows the Van Dash screen on the van's display
 * and sends the chosen store to Ford's built-in navigation (SendLocation).
 * It is a non-media app, so it never takes over the van's audio.
 */
class SdlService : Service() {

    companion object {
        const val APP_NAME = "Van Dash"
        // Not a Ford-issued app ID yet. Ford decides permissions by this ID; see the test notes in the app.
        const val APP_ID = "vandash-dev-0001"
        const val ACTION_SEND_STORE = "com.aaronzandman.vandash.SEND_STORE"
        const val EXTRA_STORE = "store"
        private const val NOTIF_ID = 4711
        private const val LIST_SIZE = 25

        @Volatile var connected = false
            private set
    }

    private var sdl: SdlManager? = null
    private val main = Handler(Looper.getMainLooper())
    private var vehicleLat: Double? = null
    private var vehicleLng: Double? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_STORE) {
            val num = intent.getStringExtra(EXTRA_STORE) ?: ""
            val s = Stores.byNumber(this, num)
            if (s == null) VLog.add(this, "Store $num isn't in the list")
            else if (!connected) VLog.add(this, "Not connected to SYNC yet, so store ${s.num} wasn't sent")
            else sendStore(s, "phone")
        }
        startProxy()
        return START_STICKY
    }

    override fun onDestroy() {
        connected = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        sdl?.dispose(); sdl = null
        VLog.add(this, "Disconnected from SYNC")
        super.onDestroy()
    }

    private fun enterForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("vandash", "Van Dash connection", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            val b = Notification.Builder(this, ch.id)
                .setContentTitle("Van Dash is on the van's screen")
                .setSmallIcon(R.drawable.ic_stat)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            startForeground(NOTIF_ID, b.build())
        } catch (e: Exception) {
            VLog.add(this, "Couldn't show the connection notification: ${e.message}")
        }
    }

    private fun startProxy() {
        if (sdl != null) return
        VLog.add(this, "Connecting to SYNC as \"$APP_NAME\" (app ID $APP_ID)")
        val transport = MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF)
        val types = Vector<AppHMIType>().apply { add(AppHMIType.DEFAULT) }

        val listener = object : SdlManagerListener {
            override fun onStart() {
                connected = true
                VLog.add(this@SdlService, "Connected to SYNC")
                sdl?.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, object : OnRPCNotificationListener() {
                    override fun onNotified(notification: RPCNotification?) {
                        val st = notification as? OnHMIStatus ?: return
                        if (st.windowID != null && st.windowID != PredefinedWindows.DEFAULT_WINDOW.value) return
                        VLog.add(this@SdlService, "SYNC screen state: ${st.hmiLevel}")
                        if (st.hmiLevel == HMILevel.HMI_FULL && st.firstRun == true) firstShow()
                    }
                })
            }
            override fun onDestroy() { connected = false; this@SdlService.stopSelf() }
            override fun onError(info: String?, e: Exception?) { VLog.add(this@SdlService, "SYNC error: $info ${e?.message ?: ""}") }
            override fun managerShouldUpdateLifecycle(language: Language?, hmiLanguage: Language?): LifecycleConfigurationUpdate? = null
            override fun onSystemInfoReceived(systemInfo: SystemInfo?): Boolean {
                VLog.add(this@SdlService, "Vehicle: ${systemInfo?.vehicleType?.make ?: "?"} ${systemInfo?.vehicleType?.model ?: ""} " +
                    "${systemInfo?.vehicleType?.modelYear ?: ""}, SYNC software ${systemInfo?.systemSoftwareVersion ?: "?"}")
                return true
            }
        }

        val builder = SdlManager.Builder(this, APP_ID, APP_NAME, listener)
        builder.setAppTypes(types)
        builder.setTransportType(transport)
        sdl = builder.build()
        sdl?.start()
    }

    // ---------- What shows on the van's screen ----------

    private fun firstShow() {
        val m = sdl ?: return
        val navOk = try { m.systemCapabilityManager.isCapabilitySupported(SystemCapabilityType.NAVIGATION) } catch (_: Exception) { false }
        val allowed = try { m.permissionManager.isRPCAllowed(FunctionID.SEND_LOCATION) } catch (_: Exception) { false }
        VLog.add(this, "Built-in navigation reported: ${if (navOk) "yes" else "no"}. Send-to-navigation allowed for this app: ${if (allowed) "YES" else "no"}")
        Prefs.setLastCheck(this, navOk, allowed)

        m.screenManager.setMenu(listOf(
            MenuCell("Nearby stores", null, null, MenuSelectionListener { _: TriggerSource? -> showStoreList() }),
            MenuCell("Send nearest store", null, null, MenuSelectionListener { _: TriggerSource? -> sendNearest() }),
        ))
        m.screenManager.setSoftButtonObjects(listOf(
            button("nearest", "Nearest") { sendNearest() },
            button("list", "Store list") { showStoreList() },
        ))
        requestVehicleGps()
        refreshHome()
    }

    private fun button(name: String, text: String, onTap: () -> Unit) =
        SoftButtonObject(name, text, null, object : SoftButtonObject.OnEventListener {
            override fun onPress(softButtonObject: SoftButtonObject?, onButtonPress: OnButtonPress?) { onTap() }
            override fun onEvent(softButtonObject: SoftButtonObject?, onButtonEvent: OnButtonEvent?) {}
        })

    private fun refreshHome() {
        val m = sdl ?: return
        val here = here()
        val near = here?.let { Stores.nearest(this, it.first, it.second, 1).firstOrNull() }
        m.screenManager.beginTransaction()
        m.screenManager.setTitle(APP_NAME)
        if (near == null) {
            m.screenManager.setTextField1("Location not known yet")
            m.screenManager.setTextField2("Open Van Dash on the phone once to allow location")
        } else {
            val (s, mi) = near
            m.screenManager.setTextField1("Nearest: Store ${s.num} · ${s.city}")
            m.screenManager.setTextField2("${"%.1f".format(mi)} mi · ${s.address}")
        }
        m.screenManager.setTextField3(Prefs.lastResult(this))
        m.screenManager.commit(null)
    }

    private fun showStoreList() {
        val m = sdl ?: return
        val here = here()
        if (here == null) { alert("Location not known yet", "Open Van Dash on the phone and allow location"); return }
        val near = Stores.nearest(this, here.first, here.second, LIST_SIZE)
        val cells = near.map { (s, mi) ->
            ChoiceCell("${s.num} · ${s.city}", "${"%.1f".format(mi)} mi · ${s.address}", s.center, null, null, null)
        }
        val set = ChoiceSet("Pick a store", cells, object : ChoiceSetSelectionListener {
            override fun onChoiceSelected(choiceCell: ChoiceCell?, triggerSource: TriggerSource?, rowIndex: Int) {
                near.getOrNull(rowIndex)?.let { sendStore(it.first, "list") }
            }
            override fun onError(error: String?) { VLog.add(this@SdlService, "Store list error: $error") }
        })
        m.screenManager.presentChoiceSet(set, InteractionMode.MANUAL_ONLY)
    }

    private fun sendNearest() {
        val here = here()
        val s = here?.let { Stores.nearest(this, it.first, it.second, 1).firstOrNull()?.first }
        if (s == null) alert("Location not known yet", "Open Van Dash on the phone and allow location")
        else sendStore(s, "nearest")
    }

    // ---------- Send the store to Ford's navigation ----------

    private fun sendStore(s: Stores.S, from: String) {
        val m = sdl ?: return
        val mode = Prefs.deliveryMode(this)
        val street = s.address.trim()
        val houseNum = street.substringBefore(' ').takeIf { it.isNotEmpty() && it.first().isDigit() }
        val road = if (houseNum != null) street.substringAfter(' ') else street
        val addr = OasisAddress()
            .setCountryName("United States").setCountryCode("US")
            .setAdministrativeArea(s.state).setLocality(s.city)
            .setPostalCode(s.zip.take(5)).setThoroughfare(road)
        if (houseNum != null) addr.setSubThoroughfare(houseNum)

        val req = SendLocation()
            .setLatitudeDegrees(s.lat).setLongitudeDegrees(s.lng)
            .setLocationName("Publix ${s.num}")
            .setLocationDescription(s.center.ifEmpty { s.city })
            .setAddressLines(listOf(street, "${s.city}, ${s.state} ${s.zip.take(5)}"))
            .setAddress(addr)
            .setDeliveryMode(mode)
        if (s.phone.isNotEmpty()) req.setPhoneNumber(s.phone.filter { it.isDigit() })

        VLog.add(this, "Sending store ${s.num} (${s.lat}, ${s.lng}) to Ford navigation, mode $mode, from $from")
        req.setOnRPCResponseListener(object : OnRPCResponseListener() {
            override fun onResponse(correlationId: Int, response: RPCResponse?) {
                val code = response?.resultCode
                val info = response?.info ?: ""
                VLog.add(this@SdlService, "Ford navigation answered: $code ${if (info.isNotEmpty()) "($info)" else ""}")
                val (title, detail) = when (code) {
                    Result.SUCCESS -> "Store ${s.num} sent to navigation" to ""
                    Result.DISALLOWED, Result.USER_DISALLOWED ->
                        "Ford blocked it ($code)" to "This app isn't approved to set destinations yet"
                    Result.UNSUPPORTED_RESOURCE -> "No built-in navigation" to "Is Connected Navigation active?"
                    Result.INVALID_DATA -> "Ford rejected the address" to info
                    else -> "Not sent: $code" to info
                }
                Prefs.setLastResult(this@SdlService, "Last: store ${s.num} → $code")
                main.post { alert(title, detail); refreshHome() }
            }
        })
        m.sendRPC(req)
    }

    // ---------- Where are we? ----------

    private fun here(): Pair<Double, Double>? {
        val v = vehicleLat; val w = vehicleLng
        if (v != null && w != null) return v to w
        return Stores.phoneLocation(this)
    }

    /** Ask the van for its own GPS. Often restricted too; the phone's location is the fallback. */
    private fun requestVehicleGps() {
        val m = sdl ?: return
        val req = GetVehicleData().setGps(true)
        req.setOnRPCResponseListener(object : OnRPCResponseListener() {
            override fun onResponse(correlationId: Int, response: RPCResponse?) {
                val r = response as? GetVehicleDataResponse
                val gps = r?.gps
                val la: Double? = gps?.latitudeDegrees
                val lo: Double? = gps?.longitudeDegrees
                if (r?.success == true && la != null && lo != null && !(la == 0.0 && lo == 0.0)) {
                    vehicleLat = la; vehicleLng = lo
                    Stores.saveLocation(this@SdlService, la, lo)
                    VLog.add(this@SdlService, "Using the van's GPS for distances")
                    main.post { refreshHome() }
                } else {
                    VLog.add(this@SdlService, "Van GPS not available to this app (${response?.resultCode}); using the phone's location")
                }
            }
        })
        m.sendRPC(req)
    }

    private fun alert(title: String, detail: String) {
        val m = sdl ?: return
        val b = AlertView.Builder().setText(title).setTimeout(5)
        if (detail.isNotEmpty()) b.setSecondaryText(detail)
        m.screenManager.presentAlert(b.build(), null)
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("vd", Context.MODE_PRIVATE)

    /** ask = SYNC asks before navigating, go = start right away, stop = add as a waypoint */
    fun mode(c: Context): String = p(c).getString("mode", "ask") ?: "ask"
    fun setMode(c: Context, m: String) = p(c).edit().putString("mode", m).apply()
    fun deliveryMode(c: Context): DeliveryMode = when (mode(c)) {
        "go" -> DeliveryMode.DESTINATION
        "stop" -> DeliveryMode.QUEUE
        else -> DeliveryMode.PROMPT
    }

    fun lastResult(c: Context): String = p(c).getString("lastResult", "") ?: ""
    fun setLastResult(c: Context, s: String) = p(c).edit().putString("lastResult", s).putLong("lastResultAt", System.currentTimeMillis()).apply()

    fun setLastCheck(c: Context, nav: Boolean, allowed: Boolean) =
        p(c).edit().putBoolean("navReported", nav).putBoolean("sendAllowed", allowed).putLong("checkAt", System.currentTimeMillis()).apply()
    fun lastCheck(c: Context): String {
        val at = p(c).getLong("checkAt", 0L)
        if (at == 0L) return "Not connected to SYNC yet"
        val d = java.text.SimpleDateFormat("MM/dd h:mm a", java.util.Locale.US).format(java.util.Date(at))
        return "Last SYNC check $d: navigation ${if (p(c).getBoolean("navReported", false)) "found" else "not reported"}, " +
            "send-to-navigation ${if (p(c).getBoolean("sendAllowed", false)) "ALLOWED" else "not allowed"}"
    }
}
