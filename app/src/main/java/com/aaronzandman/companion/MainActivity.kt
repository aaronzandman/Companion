package com.aaronzandman.companion

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One screen: a local web page (assets/ui) that talks to Android through the "Android" bridge below. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.channels(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            // Links to anything outside the app's own page open in the browser (Route Planner, Maps, GitHub).
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith("file:///android_asset/")) return false
                openUrl(u); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html" + (intent?.getStringExtra("tab")?.let { "#$it" } ?: ""))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { web.evaluateJavascript("showTab('$it')", null) }
    }

    override fun onResume() { super.onResume(); if (::web.isInitialized) web.evaluateJavascript("window.refresh&&refresh()", null) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS && granted(Manifest.permission.READ_SMS)) scanInbox(7)
        if (requestCode == REQ_LOC && granted(Manifest.permission.ACCESS_FINE_LOCATION) && Tracker.isRunning(this)) TrackerService.start(this)
        web.evaluateJavascript("window.refresh&&refresh()", null)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun btOk() = Build.VERSION.SDK_INT < 31 ||
        (granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN))

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        catch (e: Exception) { Toast.makeText(this, "No app can open that link.", Toast.LENGTH_SHORT).show() }
    }

    /** Looks through recent texts (last [days] days) for dispatches. Returns how many new ones were added. */
    private fun scanInbox(days: Int): Int {
        if (!granted(Manifest.permission.READ_SMS)) return -1
        val since = System.currentTimeMillis() - days * 86_400_000L
        val found = ArrayList<JSONObject>()
        val filter = Store.get(this, "senderFilter", "").trim()
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(1) ?: ""
                if (filter.isNotEmpty() && !sender.contains(filter, ignoreCase = true)) continue
                found.addAll(DispatchParser.parse(c.getString(0) ?: "", c.getLong(2), sender))
            }
        }
        return Store.addDispatches(this, found)
    }

    inner class Bridge {
        @JavascriptInterface fun state(): String {
            val c = this@MainActivity
            val smsOk = granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS)
            return JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("dispatches", Store.dispatches(c))
                .put("today", Tracker.today(c))
                .put("tracker", Tracker.state(c))
                .put("stores", Stores.all(c).size)
                .put("perm", JSONObject()
                    .put("sms", smsOk)
                    .put("smsAsked", Store.get(c, "smsAsked", "") == "1")
                    .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    .put("notify", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                    .put("sdk", Build.VERSION.SDK_INT))
                .put("odo", Obd.log(c))
                .put("obd", JSONObject().put("addr", Store.get(c, "obdAddr", "")).put("name", Store.get(c, "obdName", ""))
                    .put("type", Store.get(c, "obdType", "auto")).put("perm", btOk()))
                .put("settings", JSONObject()
                    .put("edition", Store.get(c, "edition", "VA"))
                    .put("radius", Store.get(c, "radius", "250"))
                    .put("senderFilter", Store.get(c, "senderFilter", ""))
                    .put("smsOn", Store.get(c, "smsOn", "1") == "1"))
                .toString()
        }

        @JavascriptInterface fun storeInfo(num: String): String =
            Stores.byNum(this@MainActivity, num)?.let { JSONObject().put("city", it.city).put("center", it.center).toString() } ?: "{}"

        @JavascriptInterface fun askSms() = runOnUiThread {
            Store.put(this@MainActivity, "smsAsked", "1")
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), REQ_SMS)
        }

        @JavascriptInterface fun askLocation() = runOnUiThread {
            val p = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), REQ_LOC)
        }

        @JavascriptInterface fun askNotify() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        @JavascriptInterface fun openAppSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun scan(): Int = scanInbox(7)

        // ---- OBD adapter (odometer) ----
        @JavascriptInterface fun askBluetooth() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQ_BT)
        }

        /** Paired Bluetooth devices (the vLinker shows up here once paired in Android Bluetooth settings). */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun obdPaired(): String {
            if (!btOk()) return "[]"
            val bt = getSystemService(BluetoothManager::class.java)?.adapter ?: return "[]"
            val a = JSONArray()
            try { bt.bondedDevices?.forEach { d -> a.put(JSONObject().put("addr", d.address).put("name", d.name ?: d.address).put("kind", "paired")) } } catch (_: SecurityException) {}
            return a.toString()
        }

        /** Looks for Bluetooth LE adapters nearby for about 6 seconds; calls onObdScan(list) in the page. */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun obdScan() {
            if (!btOk()) { askBluetooth(); return }
            val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
            val found = LinkedHashMap<String, JSONObject>()
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val name = try { r.device.name } catch (_: SecurityException) { null } ?: r.scanRecord?.deviceName ?: return
                    found[r.device.address] = JSONObject().put("addr", r.device.address).put("name", name).put("kind", "ble")
                }
            }
            try { scanner.startScan(cb) } catch (_: Exception) { return }
            web.postDelayed({
                try { scanner.stopScan(cb) } catch (_: Exception) {}
                web.evaluateJavascript("onObdScan(${JSONArray(found.values.toList())})", null)
            }, 6000)
        }

        @JavascriptInterface fun setObd(addr: String, name: String, type: String) {
            Store.put(this@MainActivity, "obdAddr", addr); Store.put(this@MainActivity, "obdName", name)
            Store.put(this@MainActivity, "obdType", if (type in setOf("classic", "ble")) type else "auto")
        }

        /** Connects and reads the odometer once; calls onObdTest(result) with the reading and a short log. */
        @JavascriptInterface fun obdTest() {
            Thread {
                val log = StringBuilder()
                val mi = Obd.readMiles(this@MainActivity, log)
                if (mi != null) Obd.record(this@MainActivity, System.currentTimeMillis(), mi)
                val res = JSONObject().put("ok", mi != null).put("miles", mi ?: JSONObject.NULL).put("log", log.toString())
                runOnUiThread { web.evaluateJavascript("onObdTest($res)", null) }
            }.start()
        }

        @JavascriptInterface fun startDay() = runOnUiThread {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { askLocation(); return@runOnUiThread }
            if (!Tracker.isRunning(this@MainActivity)) Tracker.startDay(this@MainActivity)
            TrackerService.start(this@MainActivity)
            web.evaluateJavascript("refresh()", null)
        }

        @JavascriptInterface fun endDay() = runOnUiThread {
            Tracker.endDay(this@MainActivity); TrackerService.stop(this@MainActivity)
            web.evaluateJavascript("refresh()", null)
        }

        @JavascriptInterface fun deleteSegment(start: String) { Tracker.deleteSegment(this@MainActivity, start.toLong()) }

        /** Opens the Route Planner with these calls (order numbers, comma-separated); they're added to the list there. */
        @JavascriptInterface fun send(orders: String) = runOnUiThread {
            val want = orders.split(",").map { it.trim() }.toSet()
            val rows = ArrayList<String>()
            val all = Store.dispatches(this@MainActivity)
            for (i in 0 until all.length()) { val d = all.getJSONObject(i); if (d.optString("order") in want) rows.add(DispatchParser.plannerRow(d)) }
            if (rows.isEmpty()) return@runOnUiThread
            val edition = Store.get(this@MainActivity, "edition", "VA")
            openUrl(DispatchParser.plannerLink(SITE, edition, rows))
            Store.updateDispatches(this@MainActivity) { d -> if (d.optString("order") in want) d.put("sent", true) else d }
        }

        @JavascriptInterface fun remove(orders: String) {
            val want = orders.split(",").map { it.trim() }.toSet()
            Store.updateDispatches(this@MainActivity) { d -> if (d.optString("order") in want) null else d }
        }

        @JavascriptInterface fun setSetting(key: String, value: String) {
            if (key in setOf("edition", "radius", "senderFilter", "smsOn")) Store.put(this@MainActivity, key, value)
        }

        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Companion", text))
            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface fun open(url: String) = runOnUiThread { openUrl(url) }

        /** Asks GitHub for the newest release; calls onUpdate(json) in the page. */
        @JavascriptInterface fun checkUpdate() {
            Thread {
                val res = try {
                    val con = URL("https://api.github.com/repos/AaronZandman/Companion/releases/latest").openConnection() as HttpURLConnection
                    con.setRequestProperty("Accept", "application/vnd.github+json"); con.connectTimeout = 10_000; con.readTimeout = 10_000
                    val j = JSONObject(con.inputStream.bufferedReader().readText())
                    JSONObject().put("tag", j.optString("tag_name")).put("url", DOWNLOAD).toString()
                } catch (e: Exception) { JSONObject().put("error", true).toString() }
                runOnUiThread { web.evaluateJavascript("onUpdate($res)", null) }
            }.start()
        }
    }

    companion object {
        const val REQ_SMS = 10
        const val REQ_LOC = 11
        const val REQ_NOTIFY = 12
        const val REQ_BT = 13
        const val SITE = "https://aaronzandman.github.io/Route2/"
        const val DOWNLOAD = "https://github.com/AaronZandman/Companion/releases/latest/download/Companion.apk"
    }
}
