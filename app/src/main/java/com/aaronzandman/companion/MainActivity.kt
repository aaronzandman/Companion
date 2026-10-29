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
    private val bridge by lazy { Bridge() }
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
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                val input = android.widget.EditText(this@MainActivity).apply { setText(defaultValue ?: ""); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Companion").setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
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
        web.addJavascriptInterface(bridge, "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html" + (intent?.getStringExtra("tab")?.let { "#$it" } ?: ""))
        handlePlanner(intent)
        handleAuto(intent)
        StandbyService.start(this)
        VanWatch.start(this); VanLink.start(this)
        migrateVan()
    }

    /**
     * 1.15: "your van" must be the van's own radio, not the OBD adapter. If the adapter was picked there, switch to the
     * van radio Companion has heard (e.g. "Ford Transit") and turn on holding the vLinker connection — what you asked for.
     */
    private fun migrateVan() {
        if (Store.get(this, "mig115", "") == "1") return
        Store.put(this, "mig115", "1")
        val obd = Store.get(this, "obdAddr", "")
        val acl = Store.get(this, "vanAclAddr", "")
        if (obd.isBlank() || (acl.isNotBlank() && !acl.equals(obd, true))) return
        val rec = try { JSONArray(Store.get(this, "recentAcl", "[]")) } catch (_: Exception) { JSONArray() }
        val van = (0 until rec.length()).mapNotNull { rec.optJSONObject(it) }
            .firstOrNull { !it.optString("addr").equals(obd, true) && Regex("transit|ford|sync", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("name")) }
        if (van != null) {
            Store.put(this, "vanAclAddr", van.optString("addr")); Store.put(this, "vanAclName", van.optString("name")); Store.put(this, "vanHold", "1")
            DiagLog.w(this, "SET", "moved 'your van' from the adapter to ${van.optString("name")} ${van.optString("addr")}; holding the vLinker connection is on")
        } else if (acl.isNotBlank()) { Store.put(this, "vanAclAddr", ""); Store.put(this, "vanAclName", "") }
    }

    /** Starts the day, or picks today's day back up if it ended automatically at home (e.g. lunch at home). */
    private fun doStartDay() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { bridge.askLocation(); return }
        if (!Tracker.isRunning(this)) { if (Tracker.resumable(this)) Tracker.resumeDay(this) else Tracker.startDay(this) }
        TrackerService.start(this)
        web.evaluateJavascript("refresh()", null)
    }

    /** From the automatic start/end notifications. */
    private fun handleAuto(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("startDay", false)) { i.removeExtra("startDay"); getSystemService(android.app.NotificationManager::class.java).cancel(Notify.ID_AUTODAY); web.postDelayed({ doStartDay() }, 600) }
        val st = i.getLongExtra("dayStart", 0L); val en = i.getLongExtra("dayEnd", 0L)
        if (st > 0 && en > 0) { i.removeExtra("dayStart"); i.removeExtra("dayEnd"); web.postDelayed({ web.evaluateJavascript("window.onDayEnded&&onDayEnded($st,$en)", null) }, 900) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuto(intent)
        intent.getStringExtra("tab")?.let { web.evaluateJavascript("showTab('$it')", null) }
        handlePlanner(intent)
        if (intent.getBooleanExtra("install", false)) { intent.removeExtra("install"); install() }
    }

    private fun install() {
        if (Updater.ready(this) == null) { Toast.makeText(this, "No update is waiting.", Toast.LENGTH_SHORT).show(); return }
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Allow Companion to install updates, then come back and tap Install now.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            return
        }
        try { startActivity(Updater.installIntent(this)) } catch (e: Exception) { Toast.makeText(this, "Couldn't open the installer.", Toast.LENGTH_SHORT).show() }
    }

    /** From a new-call notification: send those calls to Route Planner with "reroute" or "keep". */
    private fun handlePlanner(i: Intent?) {
        val mode = i?.getStringExtra("planner") ?: return
        val orders = i.getStringExtra("orders")?.split(",")?.map { it.trim() }?.toSet() ?: return
        i.removeExtra("planner")
        androidx.core.app.NotificationManagerCompat.from(this).cancel(i.getIntExtra("notif", 0))
        openPlanner(orders, if (mode == "keep") "keep" else "reroute")
        if (Tracker.isRunning(this)) moveTaskToBack(true)
    }

    /** Opens Route Planner with these calls; [rt] tells it to re-plan from here or keep the current order. */
    fun openPlanner(want: Set<String>, rt: String?) {
        val rows = ArrayList<String>()
        val all = Store.dispatches(this)
        for (k in 0 until all.length()) { val d = all.getJSONObject(k); if (d.optString("order") in want) rows.add(DispatchParser.plannerRow(d)) }
        if (rows.isEmpty()) return
        val edition = Store.get(this, "edition", "VA")
        val uid = try { JSONObject(Store.get(this, "uid", "")).takeIf { Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(it.optString("i")) } } catch (_: Exception) { null }
        val extra = JSONObject()
        if (rt != null) {
            extra.put("rt", rt)
            Tracker.state(this).optJSONObject("last")?.takeIf { System.currentTimeMillis() - it.optLong("t") < 10 * 60_000L }?.let { extra.put("gps", JSONObject().put("lat", it.optDouble("lat")).put("lng", it.optDouble("lng"))) }
        }
        openUrl(DispatchParser.plannerLink(SITE, edition, rows, uid, extra))
        Store.updateDispatches(this) { d -> if (d.optString("order") in want) d.put("sent", true) else d }
    }

    // Every time Companion comes to the front: refresh, and check for an update first (the page limits it to once a minute).
    override fun onResume() { super.onResume(); if (::web.isInitialized) { web.evaluateJavascript("window.refresh&&refresh()", null); startupUpdateCheck() } }

    /**
     * Whenever Companion opens or comes back to the front (at most once a minute), check GitHub for a newer version first.
     * Done by the app itself, not the page: a short "Checking for updates…" message, the download (with progress on the page),
     * then an Install dialog, or "up to date".
     */
    private var lastUpdCheck = 0L
    private fun startupUpdateCheck() {
        val now = System.currentTimeMillis(); if (now - lastUpdCheck < 60_000L) return; lastUpdCheck = now
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        web.evaluateJavascript("window.onUpdChecking&&onUpdChecking()", null)
        Thread {
            val r = Updater.check(this, 0L) { pct -> runOnUiThread { web.evaluateJavascript("window.onUpdProgress&&onUpdProgress($pct,true)", null) } }
            r.put("startup", true).put("native", true)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                web.evaluateJavascript("window.onUpdate&&onUpdate($r)", null)
                val tag = r.optString("tag").removePrefix("v")
                when {
                    r.optBoolean("ready") -> android.app.AlertDialog.Builder(this).setTitle("Update ready")
                        .setMessage("Companion $tag is ready (you have ${BuildConfig.VERSION_NAME}).\n\nInstall it now? Your data stays.")
                        .setPositiveButton("Install") { _, _ -> install() }.setNegativeButton("Later", null).show()
                    r.optBoolean("upToDate") -> Toast.makeText(this, "Companion is up to date (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    r.has("error") -> Toast.makeText(this, "Couldn't check for updates: " + (r.optString("why").ifBlank { "no connection to GitHub" }), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            if (scan.contents == null) web.evaluateJavascript("window.tcScanFail&&tcScanFail('Scan canceled.')", null)
            else handleScanChunk(scan.contents)
            return
        }
        if (requestCode == REQ_FORD) { web.evaluateJavascript("refresh()", null); return }
        if (requestCode == REQ_CSV) {
            val u = data?.data
            if (resultCode != RESULT_OK || u == null) { web.evaluateJavascript("onCsv(null)", null); return }
            // A Google Timeline export (JSON, can be large): read it here as a stream, hand the page only the last 60 days.
            val first = try { contentResolver.openInputStream(u)?.use { s -> var b: Int; do { b = s.read() } while (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == 0xEF || b == 0xBB || b == 0xBF); b } ?: -1 } catch (_: Exception) { -1 }
            if (first == '{'.code) {
                Thread {
                    val r = try { contentResolver.openInputStream(u)?.use { Timeline.read(this, it) } ?: JSONObject().put("err", "Couldn't open that file.") } catch (e: Exception) { JSONObject().put("err", "Couldn't read that file.") }
                    DiagLog.w(this, "IMPORT", "Google Timeline file: ${r.optString("err").ifBlank { "${r.optJSONArray("entries")?.length() ?: 0} entries, ${r.optJSONArray("days")?.length() ?: 0} days" }}")
                    runOnUiThread { web.evaluateJavascript("onTimeline(${JSONObject.quote(r.toString())})", null) }
                }.start()
                return
            }
            val text = try { contentResolver.openInputStream(u)?.use { it.readBytes().take(600_000).toByteArray().toString(Charsets.UTF_8) } ?: "" } catch (_: Exception) { "" }
            web.evaluateJavascript("onCsv(${JSONObject.quote(text)})", null); return
        }
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

    // ---- SAP time-check barcode scanning (ZXing) ----
    private val tcChunks = HashMap<Int, String>()
    private var tcId = ""
    private var tcTotal = 0

    private fun launchScan(prompt: String) = runOnUiThread {
        try {
            com.google.zxing.integration.android.IntentIntegrator(this).apply {
                setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                setPrompt(prompt); setBeepEnabled(false); setOrientationLocked(false)
            }.initiateScan()
        } catch (e: Exception) { web.evaluateJavascript("window.tcScanFail&&tcScanFail('Couldn\\'t open the camera.')", null) }
    }

    /** One scanned code: RP47.<id>.<i>.<n>.<chunk>. Collect all n, then decode and hand the data to the page. */
    private fun handleScanChunk(text: String) {
        val parts = text.split(".", limit = 5)
        if (parts.size < 5 || parts[0] != "RP47") { web.evaluateJavascript("window.tcScanFail&&tcScanFail('That isn\\'t a SAP time-check barcode.')", null); return }
        val id = parts[1]; val i = parts[2].toIntOrNull() ?: 0; val n = parts[3].toIntOrNull() ?: 0; val chunk = parts[4]
        if (i < 1 || n < 1) { web.evaluateJavascript("window.tcScanFail&&tcScanFail('That barcode was unreadable.')", null); return }
        if (id != tcId) { tcChunks.clear(); tcId = id; tcTotal = n }
        tcChunks[i] = chunk
        if (tcChunks.size < n) {
            web.evaluateJavascript("window.tcScanMsg&&tcScanMsg('Got code ${tcChunks.size} of $n — scan the next one.')", null)
            launchScan("Scan code ${tcChunks.size + 1} of $n")
            return
        }
        val json = try { decodeTc() } catch (e: Exception) { null }
        if (json == null) web.evaluateJavascript("window.tcScanFail&&tcScanFail('Couldn\\'t read that barcode set. Start over.')", null)
        else web.evaluateJavascript("window.onTimeCheck(${JSONObject.quote(json)})", null)
        tcChunks.clear(); tcId = ""; tcTotal = 0
    }

    private fun decodeTc(): String? {
        if (tcTotal <= 0) return null
        val sb = StringBuilder()
        for (i in 1..tcTotal) sb.append(tcChunks[i] ?: return null)
        val packed = sb.toString(); if (packed.isEmpty()) return null
        val bytes = android.util.Base64.decode(packed.substring(1), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return if (packed[0] == '1') {
            val inf = java.util.zip.Inflater(true); inf.setInput(bytes)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val k = inf.inflate(buf); if (k == 0 && (inf.finished() || inf.needsInput())) break; out.write(buf, 0, k) }
            inf.end(); out.toString("UTF-8")
        } else String(bytes, Charsets.UTF_8)
    }

    inner class Bridge {
        @JavascriptInterface fun state(): String {
            val c = this@MainActivity
            val smsOk = granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS)
            return JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("tier", Tier.level(c)).put("tierName", Tier.name(Tier.level(c)))
                .put("dispatches", Store.dispatches(c))
                .put("sap", JSONObject()
                    .put("configured", Sap.configured(c))
                    .put("url", Sap.url(c))
                    .put("lastImport", Store.get(c, "sapLastImport", "0").toLongOrNull() ?: 0L)
                    .put("districts", JSONArray(Sap.districts(c).toList()))
                    .put("types", JSONArray(Sap.types(c).toList()))
                    .put("seenDist", Sap.seenDistricts(c))
                    .put("seenTypes", JSONArray(Store.get(c, "sapSeenTypes", "").split(",").filter { it.isNotBlank() }))
                    .put("autoSms", Store.get(c, "sapAutoSms", "1") == "1"))
                .put("today", Tracker.today(c))
                .put("segments", Store.segments(c))
                .put("overrides", try { JSONObject(Store.get(c, "overrides", "{}")) } catch (_: Exception) { JSONObject() })
                .put("tracker", Tracker.state(c))
                .put("stores", Stores.all(c).size)
                .put("perm", JSONObject()
                    .put("sms", smsOk)
                    .put("smsAsked", Store.get(c, "smsAsked", "") == "1")
                    .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                    .put("notify", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("battery", getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true)
                    .put("install", Updater.canInstall(c))
                    .put("bt", btOk()).put("btNeeded", Obd.enabled(c) || AutoDay.startEnabled(c))
                    .put("bgLoc", AutoDay.backgroundOk(c))
                    .put("maker", Build.MANUFACTURER.lowercase()))
                .put("importedAll", Store.get(c, "importedAll", "").toLongOrNull() ?: 0L)
                .put("historySent", Store.get(c, "historySent", "").toLongOrNull() ?: 0L)
                .put("upd", JSONObject().put("latest", Store.get(c, "updLatest", "")).put("checked", Store.get(c, "updOk", "0").toLongOrNull() ?: 0L)
                    .put("err", Store.get(c, "updErr", "")).put("ready", Updater.ready(c) ?: ""))
                .put("callsExported", Store.get(c, "callsExported", "").toLongOrNull() ?: 0L)
                .put("mpgDone", try { JSONObject(Store.get(c, "mpgDone", "{}")) } catch (_: Exception) { JSONObject() })
                .put("odo", Obd.log(c))
                .put("obd", JSONObject().put("addr", Store.get(c, "obdAddr", "")).put("name", Store.get(c, "obdName", ""))
                    .put("type", Store.get(c, "obdType", "auto")).put("perm", btOk()))
                .put("settings", JSONObject()
                    .put("edition", Store.get(c, "edition", "VA"))
                    .put("radius", Store.get(c, "radius", Tracker.DEFAULT_RADIUS))
                    .put("senderFilter", Store.get(c, "senderFilter", ""))
                    .put("smsOn", Store.get(c, "smsOn", "1") == "1")
                    .put("autoPlanner", Store.get(c, "autoPlanner", "0") == "1")
                    .put("autoAddPlanner", Store.get(c, "autoAddPlanner", "0") == "1")
                    .put("workEmail", Store.get(c, "workEmail", ""))
                    .put("autoEmail", Store.get(c, "autoEmail", "1") == "1")
                    .put("autoStartBt", Store.get(c, "autoStartBt", "0") == "1")
                    .put("autoStartAddr", Store.get(c, "autoStartAddr", "")).put("autoStartName", Store.get(c, "autoStartName", ""))
                    .put("autoEndHome", Store.get(c, "autoEndHome", "0") == "1")
                    .put("autoBtDrive", Store.get(c, "autoBtDrive", "0") == "1")
                    .put("autoBoot", Store.get(c, "autoBoot", "1") == "1")
                    .put("standby", StandbyService.enabled(c))
                    .put("vanWatch", VanWatch.on(c)).put("vanAddr", VanWatch.addr(c)).put("vanName", VanWatch.name(c))
                    .put("dayToast", Store.get(c, "dayToast", "1") == "1").put("vanLog", Store.get(c, "vanLog", "")).put("vanHold", Store.get(c, "vanHold", "0") == "1").put("vanSession", VanSession.active()).put("engineOn", VanSession.engineOn)
                    .put("odoDrift", try { JSONArray(Store.get(c, "odoDrift", "[]")) } catch (_: Exception) { JSONArray() })
                    .put("diagRec", DiagLog.on(c)).put("diagSince", Store.get(c, "diagSince", "0").toLongOrNull() ?: 0L).put("diagLines", DiagLog.lines(c)).put("exactAlarm", VanLink.exactOk(c)).put("vanVolts", Store.get(c, "vanVolts", "")).put("vanAclAddr", Store.get(c, "vanAclAddr", "")).put("vanAclName", Store.get(c, "vanAclName", ""))
                    .put("recentAcl", try { JSONArray(Store.get(c, "recentAcl", "[]")) } catch (_: Exception) { JSONArray() })
                    .put("vanSeen", Store.get(c, "vanSeen", "0").toLongOrNull() ?: 0L).put("vanErr", Store.get(c, "vanWatchErr", ""))
                    .put("ford", JSONObject().put("configured", Ford.configured(c)).put("connected", Ford.connected(c))
                        .put("vehicle", Store.get(c, "fordVehicle", "")).put("use", Store.get(c, "fordOdo", "1") == "1")
                        .put("idEnd", Store.get(c, "fordId", "").takeLast(4))
                        .put("oil", try { JSONObject(Store.get(c, "fordOil", "")) } catch (_: Exception) { JSONObject.NULL })
                        .put("lastFetch", Store.get(c, "fordFetch", "0").toLongOrNull() ?: 0L))
                    .put("oilLast", try { JSONObject(Store.get(c, "oilLast", "")) } catch (_: Exception) { JSONObject.NULL })
                    .put("oilInterval", Store.get(c, "oilInterval", "5000").toIntOrNull() ?: 5000)
                    .put("odoBase", try { JSONObject(Store.get(c, "odoBase", "")) } catch (_: Exception) { JSONObject.NULL })
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

        /** "Allow all the time" location, so the day can start while Companion is closed (Android shows its own screen). */
        @JavascriptInterface fun askBgLocation() = runOnUiThread {
            if (Build.VERSION.SDK_INT < 29) return@runOnUiThread
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) { askLocation(); return@runOnUiThread }
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BGLOC)
        }

        @JavascriptInterface fun askNotify() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        /** Asks Android to let Companion keep running in the background (battery optimization off). */
        @SuppressLint("BatteryLife")
        @JavascriptInterface fun askBattery() = runOnUiThread {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
        }

        @JavascriptInterface fun openAppSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun scan(): Int = scanInbox(7)

        /** Scan the SAP time-check barcode(s) from Route Planner (IW47 logged time), then compare in the page. */
        @JavascriptInterface fun scanTimeCheck() { tcChunks.clear(); tcId = ""; tcTotal = 0; launchScan("Scan the SAP time-check barcode") }

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

        /** Looks for the vLinker over Bluetooth LE for ~8 s (turn the van on first) and remembers it for the background watch. Calls onVanFind(result). */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun vanFind() {
            if (!btOk()) { askBluetooth(); return }
            val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: run { web.evaluateJavascript("onVanFind({error:'Bluetooth is off'})", null); return }
            val found = LinkedHashMap<String, String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val name = try { r.device.name } catch (_: SecurityException) { null } ?: r.scanRecord?.deviceName ?: return
                    if (Regex("vlinker|vgate", RegexOption.IGNORE_CASE).containsMatchIn(name)) found[r.device.address] = name
                }
            }
            try { scanner.startScan(cb) } catch (_: Exception) { web.evaluateJavascript("onVanFind({error:'Couldn\\'t scan'})", null); return }
            web.postDelayed({
                try { scanner.stopScan(cb) } catch (_: Exception) {}
                val e = found.entries.firstOrNull()
                if (e != null) { Store.put(this@MainActivity, "vanBleAddr", e.key); Store.put(this@MainActivity, "vanBleName", e.value); VanWatch.start(this@MainActivity); VanLink.start(this@MainActivity) }
                web.evaluateJavascript("onVanFind(${JSONObject().put("addr", e?.key ?: "").put("name", e?.value ?: "")})", null)
            }, 8000)
        }

        /** Diagnostics recorder: start/stop, read, clear. */
        @JavascriptInterface fun diagRec(on: Boolean) { if (on) DiagLog.start(this@MainActivity) else DiagLog.stop(this@MainActivity) }
        @JavascriptInterface fun diagText(): String = DiagLog.text(this@MainActivity)
        @JavascriptInterface fun diagClear() = DiagLog.clear(this@MainActivity)
        @JavascriptInterface fun diagMark(note: String) = DiagLog.w(this@MainActivity, "YOU", note.take(200))

        /** "Alarms & reminders": lets the vLinker check run every minute even when the phone is idle. */
        @JavascriptInterface fun askExactAlarm() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31) try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }

        @JavascriptInterface fun setObd(addr: String, name: String, type: String) {
            Store.put(this@MainActivity, "obdAddr", addr); Store.put(this@MainActivity, "obdName", name)
            Store.put(this@MainActivity, "obdType", if (type in setOf("classic", "ble")) type else "auto")
            VanWatch.start(this@MainActivity); VanLink.start(this@MainActivity)
        }

        /** Connects and reads the odometer once; calls onObdTest(result) with the reading and a short log. */
        @JavascriptInterface fun obdTest() {
            Thread {
                val log = StringBuilder()
                DiagLog.w(this@MainActivity, "OBD", "Test connect tapped")
                val mi = Obd.readMiles(this@MainActivity, log)
                DiagLog.w(this@MainActivity, "OBD", "Test connect result: ${mi?.let { "%.1f mi".format(it) } ?: "no reading"} · ${log.toString().replace("\n", " | ").take(300)}")
                if (mi != null) Obd.record(this@MainActivity, System.currentTimeMillis(), mi)
                val res = JSONObject().put("ok", mi != null).put("miles", mi ?: JSONObject.NULL).put("log", log.toString())
                runOnUiThread { web.evaluateJavascript("onObdTest($res)", null) }
            }.start()
        }

        @JavascriptInterface fun startDay() = runOnUiThread { doStartDay() }

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

        @JavascriptInterface fun editSegment(start: String, json: String): Boolean =
            try { Tracker.editSegment(this@MainActivity, start.toLong(), JSONObject(json)) } catch (_: Exception) { false }
        /** Forgets which of a day's trips were logged in MPG (after you deleted them there), so they can be logged again. */
        @JavascriptInterface fun mpgReset(day: String) { Tracker.mpgReset(this@MainActivity, day) }

        /** Lets you pick a .csv log (from Companion's email, or one made for you) to bring into the log window. Calls onCsv(text). */
        @JavascriptInterface fun pickCsv() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/json", "application/octet-stream"))
            try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_CSV) } catch (_: Exception) { web.evaluateJavascript("onCsv(null)", null) }
        }
        /** Replaces one day's entries with imported ones ([day] = yyyy-MM-dd; entries [{type,store,start,end,m,tag}]). */
        @JavascriptInterface fun importDay(day: String, json: String): Int =
            try { val n = Tracker.importDay(this@MainActivity, day, JSONArray(json)); if (n > 0 && !Tracker.isRunning(this@MainActivity)) TrackerService.stop(this@MainActivity); n } catch (_: Exception) { -1 }

        @JavascriptInterface fun editCurrent(start: String, json: String): Boolean =
            try { Tracker.editCurrent(this@MainActivity, start.toLong(), JSONObject(json)) } catch (_: Exception) { false }
        @JavascriptInterface fun addSegment(json: String): Boolean = try { Tracker.addSegment(this@MainActivity, JSONObject(json)) } catch (_: Exception) { false }
        /** Work-time overrides for one day: {"ws": work started (ms), "we": work ended (ms)}; empty clears. */
        @JavascriptInterface fun setOverride(day: String, json: String) {
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(day)) return
            val all = try { JSONObject(Store.get(this@MainActivity, "overrides", "{}")) } catch (_: Exception) { JSONObject() }
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val clean = JSONObject(); listOf("ws", "we").forEach { k -> o.optLong(k, 0L).takeIf { it > 0 }?.let { clean.put(k, it) } }
            if (clean.length() == 0) all.remove(day) else all.put(day, clean)
            val cut = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() - 400L * 86_400_000L))
            all.keys().asSequence().toList().forEach { if (it < cut) all.remove(it) }
            Store.put(this@MainActivity, "overrides", all.toString())
        }

        @JavascriptInterface fun deleteSegment(start: String) { Tracker.deleteSegment(this@MainActivity, start.toLong()) }

        /** Opens the Route Planner with these calls (order numbers, comma-separated); they're added to the list there. */
        @JavascriptInterface fun send(orders: String) = runOnUiThread {
            openPlanner(orders.split(",").map { it.trim() }.toSet(), null)
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

        /** Emails every saved dispatch-text call (Outlook, work address only) as a file the desktop Route Planner
         *  imports into History (History → Import Companion file). Returns how many calls, or -1 for a bad address. */
        /** Opens the SAP import screen. */
        @JavascriptInterface fun openSap() = runOnUiThread { startActivity(Intent(this@MainActivity, SapActivity::class.java)) }

        @JavascriptInterface fun exportCalls(to: String): Int {
            val addr = to.trim(); if (!EMAIL.matches(addr)) return -1
            val all = Store.dispatches(this@MainActivity); val h = JSONArray(); var first = Long.MAX_VALUE; var last = 0L
            for (i in 0 until all.length()) { val d = all.getJSONObject(i); val t = d.optLong("receivedAt"); if (t <= 0) continue
                first = minOf(first, t); last = maxOf(last, t)
                h.put(JSONArray().put(d.optString("order")).put(d.optString("store")).put(d.optInt("severity")).put(t / 1000).put(d.optString("description").take(160))) }
            if (h.length() == 0) return 0
            val o = JSONObject().put("v", 3).put("src", "companion").put("h", h)
            try { val u = JSONObject(Store.get(this@MainActivity, "uid", "")); if (Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(u.optString("i"))) o.put("u", u) } catch (_: Exception) {}
            val df = java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US); val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val range = "${df.format(java.util.Date(first))} to ${df.format(java.util.Date(last))}"
            val text = "Companion past calls for Route Planner: ${h.length()} calls, $range.\r\n" +
                "On your computer: save this file, open Route Planner, choose History, then Import Companion file, and pick this file.\r\n" +
                "Calls already in History are skipped. Nothing here is uploaded anywhere.\r\n\r\nROUTE2-TRANSFER:" + DispatchParser.pack(o) + "\r\n"
            val f = try {
                val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                File(dir, "Companion-calls-$stamp.txt").apply { writeText(text) }
            } catch (_: Exception) { return 0 }
            runOnUiThread { outlook(addr, "Companion calls for Route Planner ($stamp)",
                "Attached: ${h.length()} calls from dispatch texts ($range).\n\nOn your computer: save the attachment, open Route Planner, choose History, then Import Companion file.", f) }
            Store.put(this@MainActivity, "callsExported", System.currentTimeMillis().toString())
            return h.length()
        }

        /** Opens the MPG screen that fills in each trip for you to check and Save. */
        @JavascriptInterface fun openMpg(json: String) = runOnUiThread {
            startActivity(Intent(this@MainActivity, MpgActivity::class.java).putExtra("data", json))
        }

        /** Unlocks Companion+ or Companion++ with a code. Returns the new level, or 0 if the code is wrong. */
        @JavascriptInterface fun unlock(code: String): Int = Tier.unlock(this@MainActivity, code)
        @JavascriptInterface fun lockTier() { Tier.reset(this@MainActivity) }

        // ---- Ford (FordConnect Query) ----
        @JavascriptInterface fun fordSave(id: String, secret: String) { Ford.saveCredentials(this@MainActivity, id, secret) }
        @JavascriptInterface fun fordConnect() = runOnUiThread {
            if (!Tier.ford(this@MainActivity) || !Ford.configured(this@MainActivity)) return@runOnUiThread
            @Suppress("DEPRECATION") startActivityForResult(Intent(this@MainActivity, FordActivity::class.java), REQ_FORD)
        }
        @JavascriptInterface fun fordDisconnect() { Ford.disconnect(this@MainActivity) }
        /** Everything Ford returns (vehicle info and all readings), unedited; calls onFordRaw(result). */
        @JavascriptInterface fun fordRaw() {
            Thread {
                val c = this@MainActivity
                val out = JSONObject().put("fetched", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
                fun parse(text: String): Any = try { if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text) } catch (_: Exception) { text }
                val df = java.text.DateFormat.getDateTimeInstance()
                // The vehicle itself rarely changes: reuse what Ford sent in the last week, so the one request goes to telemetry.
                val g = Ford.cachedGarage(c, 7 * 86_400_000L)
                if (g != null) out.put("garage", JSONObject().put("status", 200).put("saved", df.format(java.util.Date(g.first))).put("data", parse(g.second)))
                else { val (code, text) = Ford.get(c, "garage", patient = true) { s -> runOnUiThread { web.evaluateJavascript("onFordWait($s)", null) } }
                    out.put("garage", JSONObject().put("status", code).put("data", parse(text))) }
                val (code, text) = Ford.get(c, "telemetry", patient = true) { s -> runOnUiThread { web.evaluateJavascript("onFordWait($s)", null) } }
                val body = parse(text)
                out.put("telemetry", JSONObject().put("status", code).put("data", body))
                if (code in 200..299 && body is JSONObject) Ford.saveExtras(c, body)
                out.put("fetched", df.format(java.util.Date()))
                runOnUiThread { web.evaluateJavascript("onFordRaw(${JSONObject.quote(out.toString(2))})", null) }
            }.start()
        }
        /** Quietly gets the latest odometer and oil life from Ford (at most every 30 minutes), then refreshes the screen. */
        @JavascriptInterface fun fordRefresh() {
            val c = this@MainActivity
            if (!Tier.ford(c) || !Ford.connected(c) || !Ford.autoReadDue(c) || System.currentTimeMillis() - (Store.get(c, "fordFetch", "0").toLongOrNull() ?: 0L) < 30 * 60_000L) return
            Store.put(c, "fordFetch", System.currentTimeMillis().toString())
            Thread { Ford.readMiles(c)?.let { (mi, t) -> Obd.record(c, t, mi) }; runOnUiThread { web.evaluateJavascript("refresh()", null) } }.start()
        }
        @JavascriptInterface fun fordUse(on: Boolean) { Store.put(this@MainActivity, "fordOdo", if (on) "1" else "0") }
        /** Asks Ford for the van's data once; calls onFordTest(result) with the odometer and what Ford returned. */
        @JavascriptInterface fun fordTest() {
            Thread {
                val c = this@MainActivity
                val (code, text) = Ford.get(c, "telemetry", patient = true)
                val res = JSONObject().put("code", code)
                try {
                    val j = JSONObject(text); val m = j.optJSONObject("metrics"); Ford.saveExtras(c, j)
                    res.put("updateTime", j.optString("updateTime"))
                    res.put("keys", JSONArray(m?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList<String>()))
                    m?.optJSONObject("odometer")?.let { o -> val km = o.optDouble("value", Double.NaN)
                        if (!km.isNaN()) { res.put("miles", km / 1.609344).put("odoTime", o.optString("updateTime"))
                            val t = Ford.parseTime(o.optString("updateTime").ifBlank { j.optString("updateTime") }) ?: System.currentTimeMillis()
                            Obd.record(c, t, km / 1.609344) } }
                } catch (_: Exception) { res.put("error", text.take(300)) }
                runOnUiThread { web.evaluateJavascript("onFordTest($res)", null) }
            }.start()
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
            if (key == "odoBase") {   // {"mi": whole miles, "t": time entered}
                try { val o = JSONObject(value); val mi = o.getDouble("mi"); if (mi in 0.0..2_000_000.0) Store.put(this@MainActivity, key, JSONObject().put("mi", Math.round(mi)).put("t", System.currentTimeMillis()).toString()) } catch (_: Exception) {}
                return
            }
            if (key == "oilLast") {   // {"mi": odometer at the oil change}
                try { val mi = JSONObject(value).getDouble("mi"); if (mi in 0.0..2_000_000.0) Store.put(this@MainActivity, key, JSONObject().put("mi", Math.round(mi)).put("t", System.currentTimeMillis()).toString()) } catch (_: Exception) {}
                return
            }
            if (key == "odoDrift") { Store.put(this@MainActivity, key, value.take(4000)); return }   // how far off the virtual odometer was at each weekly check
            if (key == "oilInterval") { value.toIntOrNull()?.takeIf { it in 1000..20000 }?.let { Store.put(this@MainActivity, key, it.toString()) }; return }
            if (key == "workEmail") { if (value.isEmpty() || EMAIL.matches(value.trim())) Store.put(this@MainActivity, key, value.trim()); return }
            if (key in setOf("edition", "radius", "senderFilter", "smsOn", "autoEmail", "autoPlanner", "autoAddPlanner", "autoStartBt", "autoStartAddr", "autoStartName", "autoEndHome", "autoBtDrive", "autoBoot", "standby", "vanWatch", "vanAclAddr", "vanAclName", "dayToast", "vanHold", "sapDistricts", "sapTypes", "sapAutoSms", "sapUrl")) {
                Store.put(this@MainActivity, key, value.take(80))
                if (key == "vanWatch") { VanWatch.start(this@MainActivity); VanLink.start(this@MainActivity) }
                if (key == "vanHold" || key == "vanAclAddr") { DiagLog.w(this@MainActivity, "SET", "$key = $value"); if (key == "vanHold" && value != "1") VanSession.stop(); VanWatch.start(this@MainActivity) }
                if (key == "standby") { if (value == "1") StandbyService.start(this@MainActivity) else StandbyService.stop(this@MainActivity) }
            }
        }

        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Companion", text))
            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface fun open(url: String) = runOnUiThread { openUrl(url) }

        /** Checks GitHub for a newer Companion and downloads it; calls onUpdate(result). [force] skips the 6-hour wait. */
        @JavascriptInterface fun checkUpdate(force: Boolean) {
            Thread {
                val r = Updater.check(this@MainActivity, if (force) 0L else 6 * 3600_000L) { pct ->
                    if (force) runOnUiThread { web.evaluateJavascript("window.onUpdProgress&&onUpdProgress($pct)", null) } }
                r.put("manual", force)
                runOnUiThread { web.evaluateJavascript("onUpdate($r)", null) }
            }.start()
        }

        /** Run first whenever Companion opens: asks GitHub for a newer version (at most every 10 minutes), downloads it with
         *  progress, and calls onUpdate({..., startup:true}) so the page can offer the install straight away. */
        @JavascriptInterface fun checkUpdateStartup() {
            Thread {
                val r = Updater.check(this@MainActivity, 60_000L) { pct -> runOnUiThread { web.evaluateJavascript("window.onUpdProgress&&onUpdProgress($pct,true)", null) } }
                r.put("startup", true)
                runOnUiThread { web.evaluateJavascript("onUpdate($r)", null) }
            }.start()
        }

        /** Opens Android's install screen for the downloaded update (or the setting to allow it first). */
        @JavascriptInterface fun installUpdate() = runOnUiThread { install() }

        @JavascriptInterface fun allowInstall() = runOnUiThread {
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) { openAppSettings() }
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
                    type = if (attach.name.endsWith(".txt")) "text/plain" else "text/csv"
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
            "App: ${Tier.name(Tier.level(c))} ${BuildConfig.VERSION_NAME}",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · WebView $wv",
            "Language / time zone: ${java.util.Locale.getDefault().toLanguageTag()} / ${java.util.TimeZone.getDefault().id}",
            "Permissions: texts ${yn(granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS))} · location ${yn(granted(Manifest.permission.ACCESS_FINE_LOCATION))} · notifications ${yn(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))} · Bluetooth ${yn(btOk())}",
            "Battery optimization: ${if (pm?.isIgnoringBatteryOptimizations(packageName) == true) "off for Companion (good)" else "ON – Android may pause the timer in the background"}",
            "Location services: ${if (Build.VERSION.SDK_INT >= 28) (if (lm?.isLocationEnabled == true) "on" else "OFF") else "unknown"} · Bluetooth: ${when (bt) { true -> "on"; false -> "off"; else -> "unknown" }}",
            "Timer: ${if (trk.optBoolean("running")) "running since ${fmt.format(java.util.Date(trk.optLong("dayStart")))}" else "not running"}",
            "Data (counts only): ${Store.dispatches(c).length()} dispatch texts, ${Store.segments(c).length()} time entries, ${Stores.all(c).size} stores loaded",
            "Home location: ${if (Tracker.home(c) != null) "saved (location not included)" else "not saved"}",
            "Ford connection: ${if (Ford.connected(c)) "connected" else if (Ford.configured(c)) "set up, not signed in" else "not set up"}",
            "Settings: planner ${Store.get(c, "edition", "VA")} · store distance ${Store.get(c, "radius", Tracker.DEFAULT_RADIUS)} m · read texts ${if (Store.get(c, "smsOn", "1") == "1") "on" else "off"} · sender filter ${if (Store.get(c, "senderFilter", "").isNotBlank()) "set" else "none"} · work email ${if (Store.get(c, "workEmail", "").isNotBlank()) "set" else "not set"}",
            "OBD adapter: ${if (Obd.enabled(c)) "set up (${obdType} Bluetooth)" else "none"} · last reading ${if (odoLast > 0) fmt.format(java.util.Date(odoLast)) else "never"}",
            "Last crash: ${Store.get(c, "lastCrash", "none")}"
        )
        return lines.joinToString("\n")
    }

    companion object {
        const val REQ_SMS = 10
        const val REQ_LOC = 11
        const val REQ_BGLOC = 21
        const val REQ_NOTIFY = 12
        const val REQ_BT = 13
        const val REQ_SHOT = 14
        const val REQ_CSV = 22
        const val REQ_FORD = 15
        const val SITE = "https://aaronzandman.github.io/Route2/"
        const val OUTLOOK = "com.microsoft.office.outlook"
        const val FEEDBACK_TO = "Aaron.zandman@publix.com"
        /** Only Publix work addresses are accepted. */
        val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@publix\\.com$", RegexOption.IGNORE_CASE)
        const val DOWNLOAD = "https://github.com/AaronZandman/Companion/releases/latest/download/Companion.apk"
    }
}
