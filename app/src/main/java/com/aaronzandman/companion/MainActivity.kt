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
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One screen: a local web page (assets/ui) that talks to Android through the "Android" bridge below. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    /** Screenshots picked for the next feedback report (copied into the app's cache). */
    private val shots = ArrayList<File>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.channels(this)
        // Remember the last crash (type, message, where) so it can be included in feedback if the user chooses.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val at = e.stackTrace.firstOrNull { it.className.startsWith("com.aaronzandman") } ?: e.stackTrace.firstOrNull()
                Store.putNow(this, "lastCrash", "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())} – ${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}${at?.let { " at ${it.fileName}:${it.lineNumber}" } ?: ""}")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
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
        // Without this, the page's "Are you sure?" questions (confirm/alert) are silently cancelled by Android.
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Companion").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Companion").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }.show()
                return true
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

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SHOT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) } ?: data.data?.let { uris.add(it) }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        for (u in uris) {
            if (shots.size >= 3) break
            try {
                val ext = if (contentResolver.getType(u)?.contains("png") == true) "png" else "jpg"
                val f = File(dir, "screenshot-${shots.size + 1}-${System.currentTimeMillis() % 100000}.$ext")
                contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                if (f.length() in 1..15_000_000) shots.add(f) else f.delete()
            } catch (_: Exception) {}
        }
        web.evaluateJavascript("onShots(${shots.size})", null)
    }

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

    /** Looks through texts from the last [days] days (0 = every text on the phone) for dispatches. Returns how many new ones were added. */
    private fun scanInbox(days: Int): Int {
        if (!granted(Manifest.permission.READ_SMS)) return -1
        val since = if (days <= 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        val found = ArrayList<JSONObject>()
        val filter = Store.get(this, "senderFilter", "").trim()
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            // Only texts that mention "Sev" can be dispatches, so the phone skips the rest quickly.
            "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.BODY} LIKE ?", arrayOf(since.toString(), "%Sev%"), "${Telephony.Sms.DATE} DESC")?.use { c ->
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
                .put("segments", Store.segments(c))
                .put("tracker", Tracker.state(c))
                .put("stores", Stores.all(c).size)
                .put("perm", JSONObject()
                    .put("sms", smsOk)
                    .put("smsAsked", Store.get(c, "smsAsked", "") == "1")
                    .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    .put("notify", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                    .put("sdk", Build.VERSION.SDK_INT))
                .put("importedAll", Store.get(c, "importedAll", "").toLongOrNull() ?: 0L)
                .put("historySent", Store.get(c, "historySent", "").toLongOrNull() ?: 0L)
                .put("mpgDone", try { JSONObject(Store.get(c, "mpgDone", "{}")) } catch (_: Exception) { JSONObject() })
                .put("odo", Obd.log(c))
                .put("obd", JSONObject().put("addr", Store.get(c, "obdAddr", "")).put("name", Store.get(c, "obdName", ""))
                    .put("type", Store.get(c, "obdType", "auto")).put("perm", btOk()))
                .put("settings", JSONObject()
                    .put("edition", Store.get(c, "edition", "VA"))
                    .put("radius", Store.get(c, "radius", "250"))
                    .put("senderFilter", Store.get(c, "senderFilter", ""))
                    .put("smsOn", Store.get(c, "smsOn", "1") == "1")
                    .put("workEmail", Store.get(c, "workEmail", ""))
                    .put("autoEmail", Store.get(c, "autoEmail", "1") == "1")
                    .put("home", Store.get(c, "home", "").let { if (it.isEmpty()) JSONObject.NULL else JSONObject(it) }))
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

        /** Reads every dispatch text on the phone (in the background). Calls onImportAll(newCount, total). */
        @JavascriptInterface fun importAll() {
            if (!granted(Manifest.permission.READ_SMS)) { askSms(); return }
            Thread {
                val n = try { scanInbox(0) } catch (e: Exception) { -2 }
                val total = Store.dispatches(this@MainActivity).length()
                Store.put(this@MainActivity, "importedAll", System.currentTimeMillis().toString())
                runOnUiThread { web.evaluateJavascript("onImportAll($n,$total)", null) }
            }.start()
        }

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
            val start = Tracker.state(this@MainActivity).optLong("dayStart", 0L)
            val end = System.currentTimeMillis()
            Tracker.endDay(this@MainActivity, end); TrackerService.stop(this@MainActivity)
            web.evaluateJavascript("refresh();window.onDayEnded&&onDayEnded($start,$end)", null)
        }

        /** Opens Outlook with a feedback report to the app's owner. The user sees everything and taps Send. */
        @JavascriptInterface fun feedback(subject: String, body: String) = runOnUiThread {
            outlook(FEEDBACK_TO, subject, body, null, ArrayList(shots))
            shots.clear()
        }

        /** Lets the user pick up to 3 screenshots to attach to the feedback email. Calls onShots(count). */
        @JavascriptInterface fun pickScreenshots() = runOnUiThread {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try { @Suppress("DEPRECATION") startActivityForResult(Intent.createChooser(i, "Choose screenshots"), REQ_SHOT) } catch (_: Exception) {}
        }

        @JavascriptInterface fun clearScreenshots() { shots.forEach { it.delete() }; shots.clear() }

        /** Troubleshooting details. Shown in full on the feedback form before anything is sent. */
        @JavascriptInterface fun deviceDetails(): String = details()

        /** Opens Outlook (and only Outlook) with the time log filled in and a .csv attached. The user taps Send. */
        @JavascriptInterface fun emailDay(to: String, subject: String, body: String, fileName: String, csv: String) = runOnUiThread {
            val addr = to.trim()
            if (!EMAIL.matches(addr)) { web.evaluateJavascript("onEmail('error')", null); return@runOnUiThread }
            val f = try {
                val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "Companion-time.csv" }
                File(dir, safe).apply { writeText(csv) }
            } catch (e: Exception) { null }
            outlook(addr, subject, body, f)
        }

        @JavascriptInterface fun setTag(start: String, tag: String) {
            Tracker.setTag(this@MainActivity, start.toLong(), if (tag == "FUEL") "FUEL" else "")
        }

        /** Saves the phone's current GPS position as home (best fix within ~20 s). Calls onHome(result). */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun saveHome() = runOnUiThread {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { askLocation(); return@runOnUiThread }
            val lm = getSystemService(android.location.LocationManager::class.java) ?: return@runOnUiThread
            var best: android.location.Location? = null
            var done = false
            fun finish() {
                if (done) return; done = true
                val b = best
                val res = if (b == null) JSONObject().put("ok", false)
                else {
                    Store.put(this@MainActivity, "home", JSONObject().put("lat", b.latitude).put("lng", b.longitude).put("acc", b.accuracy.toInt()).put("at", System.currentTimeMillis()).toString())
                    JSONObject().put("ok", true).put("acc", b.accuracy.toInt())
                }
                web.evaluateJavascript("onHome($res)", null)
            }
            val l = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    if (best == null || loc.accuracy < best!!.accuracy) best = loc
                    if (loc.accuracy <= 25f) { lm.removeUpdates(this); finish() }
                }
                @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            try { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, l, mainLooper) } catch (_: Exception) {}
            web.postDelayed({ try { lm.removeUpdates(l) } catch (_: Exception) {}; finish() }, 20_000)
        }

        @JavascriptInterface fun clearHome() { Store.put(this@MainActivity, "home", "") }

        @JavascriptInterface fun deleteSegment(start: String) { Tracker.deleteSegment(this@MainActivity, start.toLong()) }

        /** Opens the Route Planner with these calls (order numbers, comma-separated); they're added to the list there. */
        @JavascriptInterface fun send(orders: String) = runOnUiThread {
            val want = orders.split(",").map { it.trim() }.toSet()
            val rows = ArrayList<String>()
            val all = Store.dispatches(this@MainActivity)
            for (i in 0 until all.length()) { val d = all.getJSONObject(i); if (d.optString("order") in want) rows.add(DispatchParser.plannerRow(d)) }
            if (rows.isEmpty()) return@runOnUiThread
            val edition = Store.get(this@MainActivity, "edition", "VA")
            val uid = try { JSONObject(Store.get(this@MainActivity, "uid", "")).takeIf { Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(it.optString("i")) } } catch (_: Exception) { null }
            openUrl(DispatchParser.plannerLink(SITE, edition, rows, uid))
            Store.updateDispatches(this@MainActivity) { d -> if (d.optString("order") in want) d.put("sent", true) else d }
        }

        /** Opens Route Planner with past calls (received before [before] ms) to add to its History. Returns how many. */
        @JavascriptInterface fun sendHistory(before: String): Int {
            val cut = before.toLongOrNull() ?: return 0
            val all = Store.dispatches(this@MainActivity); val h = JSONArray()
            for (i in 0 until all.length()) { val d = all.getJSONObject(i); val t = d.optLong("receivedAt")
                if (t in 1 until cut) h.put(JSONArray().put(d.optString("order")).put(d.optString("store")).put(d.optInt("severity")).put(t / 1000).put(d.optString("description").take(160))) }
            if (h.length() == 0) return 0
            val o = JSONObject().put("v", 3).put("src", "companion").put("h", h)
            try { val u = JSONObject(Store.get(this@MainActivity, "uid", "")); if (Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(u.optString("i"))) o.put("u", u) } catch (_: Exception) {}
            val link = DispatchParser.plannerLinkJson(SITE, Store.get(this@MainActivity, "edition", "VA"), o)
            runOnUiThread { openUrl(link) }
            Store.put(this@MainActivity, "historySent", System.currentTimeMillis().toString())
            return h.length()
        }

        /** Opens the MPG screen that fills in each trip for you to check and Save. */
        @JavascriptInterface fun openMpg(json: String) = runOnUiThread {
            startActivity(Intent(this@MainActivity, MpgActivity::class.java).putExtra("data", json))
        }

        @JavascriptInterface fun remove(orders: String) {
            val want = orders.split(",").map { it.trim() }.toSet()
            Store.updateDispatches(this@MainActivity) { d -> if (d.optString("order") in want) null else d }
        }

        /** Keeps a copy of the anonymous ID where the Route Planner link builder can read it. */
        @JavascriptInterface fun setUid(json: String) {
            try { val o = JSONObject(json); if (Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(o.optString("i"))) Store.put(this@MainActivity, "uid", JSONObject().put("i", o.optString("i")).put("a", o.optLong("a")).toString()) } catch (_: Exception) {}
        }

        @JavascriptInterface fun setSetting(key: String, value: String) {
            if (key == "workEmail") { if (value.isEmpty() || EMAIL.matches(value.trim())) Store.put(this@MainActivity, key, value.trim()); return }
            if (key in setOf("edition", "radius", "senderFilter", "smsOn", "autoEmail")) Store.put(this@MainActivity, key, value)
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

    /** The one way Companion sends email: Outlook, with everything filled in. Nothing is sent until the user taps Send. */
    private fun outlook(to: String, subject: String, body: String, attach: File?, images: List<File> = emptyList()) {
        try {
            val i = if (images.isNotEmpty()) Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage(OUTLOOK); type = "image/*"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
                val uris = ArrayList(images.map { FileProvider.getUriForFile(this@MainActivity, "$packageName.files", it) })
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri(images[0].name, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else Intent(Intent.ACTION_SEND).apply {
                setPackage(OUTLOOK)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                if (attach != null) {
                    type = "text/csv"
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", attach)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(attach.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else type = "text/plain"
            }
            startActivity(i)
        } catch (e: android.content.ActivityNotFoundException) {
            web.evaluateJavascript("onEmail('noOutlook')", null)
        } catch (e: Exception) {
            web.evaluateJavascript("onEmail('error')", null)
        }
    }

    /** Device and app facts that help troubleshoot. No calls, texts, locations, odometer values, contacts, or device IDs. */
    @SuppressLint("MissingPermission")
    private fun details(): String {
        val c = this
        fun yn(b: Boolean) = if (b) "allowed" else "NOT allowed"
        val pm = getSystemService(android.os.PowerManager::class.java)
        val lm = getSystemService(android.location.LocationManager::class.java)
        val bt = try { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled } catch (_: Exception) { null }
        val wv = try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: Exception) { null } ?: "unknown"
        val trk = Tracker.state(c)
        val odoLast = try { JSONObject(Store.get(c, "odoLast", "{}")).optLong("t", 0L) } catch (_: Exception) { 0L }
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val obdType = Store.get(c, "obdType", "auto")
        val lines = listOf(
            "App: Companion ${BuildConfig.VERSION_NAME}",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · WebView $wv",
            "Language / time zone: ${java.util.Locale.getDefault().toLanguageTag()} / ${java.util.TimeZone.getDefault().id}",
            "Permissions: texts ${yn(granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS))} · location ${yn(granted(Manifest.permission.ACCESS_FINE_LOCATION))} · notifications ${yn(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))} · Bluetooth ${yn(btOk())}",
            "Battery optimization: ${if (pm?.isIgnoringBatteryOptimizations(packageName) == true) "off for Companion (good)" else "ON – Android may pause the timer in the background"}",
            "Location services: ${if (Build.VERSION.SDK_INT >= 28) (if (lm?.isLocationEnabled == true) "on" else "OFF") else "unknown"} · Bluetooth: ${when (bt) { true -> "on"; false -> "off"; else -> "unknown" }}",
            "Timer: ${if (trk.optBoolean("running")) "running since ${fmt.format(java.util.Date(trk.optLong("dayStart")))}" else "not running"}",
            "Data (counts only): ${Store.dispatches(c).length()} dispatch texts, ${Store.segments(c).length()} time entries, ${Stores.all(c).size} stores loaded",
            "Home location: ${if (Tracker.home(c) != null) "saved (location not included)" else "not saved"}",
            "Settings: planner ${Store.get(c, "edition", "VA")} · store distance ${Store.get(c, "radius", "250")} m · read texts ${if (Store.get(c, "smsOn", "1") == "1") "on" else "off"} · sender filter ${if (Store.get(c, "senderFilter", "").isNotBlank()) "set" else "none"} · work email ${if (Store.get(c, "workEmail", "").isNotBlank()) "set" else "not set"}",
            "OBD adapter: ${if (Obd.enabled(c)) "set up (${obdType} Bluetooth)" else "none"} · last reading ${if (odoLast > 0) fmt.format(java.util.Date(odoLast)) else "never"}",
            "Last crash: ${Store.get(c, "lastCrash", "none")}"
        )
        return lines.joinToString("\n")
    }

    companion object {
        const val REQ_SMS = 10
        const val REQ_LOC = 11
        const val REQ_NOTIFY = 12
        const val REQ_BT = 13
        const val REQ_SHOT = 14
        const val SITE = "https://aaronzandman.github.io/Route2/"
        const val OUTLOOK = "com.microsoft.office.outlook"
        const val FEEDBACK_TO = "Aaron.zandman@publix.com"
        /** Only Publix work addresses are accepted. */
        val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@publix\\.com$", RegexOption.IGNORE_CASE)
        const val DOWNLOAD = "https://github.com/AaronZandman/Companion/releases/latest/download/Companion.apk"
    }
}
