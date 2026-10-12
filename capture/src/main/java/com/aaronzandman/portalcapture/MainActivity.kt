package com.aaronzandman.portalcapture

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portal Capture: open a web portal, sign in yourself, and record how its forms are laid out
 * (field labels, names, types, buttons) plus the buttons you tap, so a mileage auto-fill can be built.
 * It never reads what is typed into fields, never sees your password, and sends nothing anywhere:
 * the report stays on this phone until you copy or share it yourself.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var recBtn: Button
    private lateinit var repBtn: Button
    private val prefs by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private var pages = JSONArray()     // captured page snapshots
    private var steps = JSONArray()     // recorded steps
    private val hosts = LinkedHashSet<String>()
    private var recording = false
    private var pending: JSONObject? = null
    private var injected = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = JSONArray(prefs.getString("pages", "[]")); steps = JSONArray(prefs.getString("steps", "[]"))
        JSONArray(prefs.getString("hosts", "[]")).let { for (i in 0 until it.length()) hosts.add(it.getString(i)) }

        val green = Color.parseColor("#1F6B4F")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(6)); setBackgroundColor(green); gravity = Gravity.CENTER_VERTICAL }
        urlBox = EditText(this).apply {
            hint = "Portal web address"; setText(prefs.getString("url", "")); setSingleLine(); textSize = 15f
            inputType = InputType.TYPE_TEXT_VARIATION_URI; imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false }
        }
        top.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btn("Go", Color.WHITE, green) { go() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        root.addView(top)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)   // sign-in pop-ups open in the same view
        web.webChromeClient = android.webkit.WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                val u = Uri.parse(url)
                if (u.host != null) {
                    hosts.add(u.host!!)
                    if (recording) addStep(JSONObject().put("ev", "page").put("site", u.host).put("path", u.path ?: "").put("title", red(view.title ?: "")))
                }
                if (!injected) view.evaluateJavascript(script(), null)   // older WebView: main page only
            }
        }
        // The capture script runs in every page and frame, and talks only to this app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "R2cap", setOf("*")) { _, msg: WebMessageCompat, origin, _, _ -> onMessage(msg.data ?: "", origin.toString()) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script(), setOf("*"))
        } else injected = false
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.parseColor("#E9EDF0")) }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) } }
        bar.addView(btn("Capture page", Color.WHITE, green) { capture() }, lp())
        recBtn = btn("Record steps", green, Color.WHITE) { toggleRec() }; bar.addView(recBtn, lp())
        repBtn = btn("Report", green, Color.WHITE) { showReport() }; bar.addView(repBtn, lp())
        bar.addView(btn("⋮", green, Color.WHITE) { more() }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar)
        setContentView(root)
        updateButtons()

        if (!prefs.getBoolean("introSeen", false)) intro()
        else prefs.getString("url", "")?.takeIf { it.isNotBlank() }?.let { web.loadUrl(it) }
    }

    @Deprecated("Back handling")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun script() = assets.open("capture.js").bufferedReader().readText()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(text: String, fg: Int, bg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f; setTextColor(fg); setBackgroundColor(bg); setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener { onClick() }
    }
    private fun red(t: String) = t.replace(Regex("\\s+"), " ").trim().take(120)
        .replace(Regex("[^\\s@]+@[^\\s@]+\\.[a-zA-Z]{2,}"), "[email]").replace(Regex("\\d{6,}")) { "#".repeat(it.value.length) }

    private fun go() {
        var u = urlBox.text.toString().trim(); if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        prefs.edit().putString("url", u).apply(); web.loadUrl(u)
    }

    private fun onMessage(data: String, origin: String) {
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        when (o.optString("k")) {
            "scan" -> pending?.let { p -> if (p.optString("id") == o.optString("id")) p.getJSONArray("frames").put(o.optJSONObject("data")?.put("origin", red(origin))) }
            "step" -> if (recording) { o.remove("k"); addStep(o) }
        }
    }

    private fun addStep(o: JSONObject) {
        if (steps.length() >= 400) return
        steps.put(o.put("t", System.currentTimeMillis() / 1000)); save(); updateButtons()
    }

    private fun capture() {
        if (web.url.isNullOrBlank()) { toast("Open the portal first."); return }
        val id = System.currentTimeMillis().toString()
        val p = JSONObject().put("id", id).put("t", System.currentTimeMillis() / 1000).put("frames", JSONArray())
        pending = p
        web.evaluateJavascript("window.__r2scanAll&&window.__r2scanAll('$id')") { }
        web.postDelayed({
            pending = null
            val fr = p.getJSONArray("frames")
            if (fr.length() == 0) { toast("Nothing captured yet. Wait for the page to finish loading and try again."); return@postDelayed }
            if (pages.length() >= 25) { toast("That's 25 pages, the most one report holds. Send this report, then Start over."); return@postDelayed }
            p.remove("id"); pages.put(p); save(); updateButtons()
            var n = 0; for (i in 0 until fr.length()) { val f = fr.getJSONObject(i).optJSONArray("fields") ?: continue; for (j in 0 until f.length()) if (f.getJSONObject(j).optString("kind") != "hidden") n++ }
            toast("Captured $n fields from ${fr.length()} part${if (fr.length() == 1) "" else "s"} of this page.")
        }, 1500)
    }

    private fun toggleRec() {
        recording = !recording
        if (recording) { addStep(JSONObject().put("ev", "recording started").put("site", Uri.parse(web.url ?: "").host ?: "")); toast("Recording: tap through the mileage entry as you normally would. Values you type are never recorded.") }
        else { addStep(JSONObject().put("ev", "recording stopped")); toast("Recording stopped.") }
        updateButtons()
    }

    private fun updateButtons() {
        recBtn.text = if (recording) "● Stop" else "Record steps"
        recBtn.setTextColor(if (recording) Color.parseColor("#B8322A") else Color.parseColor("#1F6B4F"))
        repBtn.text = "Report (${pages.length()}/${steps.length()})"
    }

    private fun report(): String {
        val wv = try { WebViewCompat.getCurrentWebViewPackage(this)?.versionName } catch (_: Exception) { null } ?: "unknown"
        val o = JSONObject()
            .put("tool", "route2-portal-capture").put("v", 1).put("app", BuildConfig.VERSION_NAME)
            .put("device", JSONObject().put("android", Build.VERSION.RELEASE).put("webview", wv).put("allFrames", injected))
            .put("sitesVisited", JSONArray(hosts.toList()))
            .put("pages", pages).put("steps", steps)
        return "ROUTE2-PORTAL-CAPTURE\n" + o.toString(1)
    }

    private fun showReport() {
        val text = report()
        val tv = TextView(this).apply { this.text = text; typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val intro = TextView(this).apply {
            this.text = "${pages.length()} page${if (pages.length() == 1) "" else "s"} captured, ${steps.length()} step${if (steps.length() == 1) "" else "s"} recorded. " +
                "This lists labels, field types and buttons only. Nothing you typed is in it, and nothing has been sent. Long numbers and emails are hidden. Check it, then Copy and paste it to Claude."
            setPadding(dp(14), dp(10), dp(14), dp(4)); textSize = 13f
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(intro); addView(ScrollView(this@MainActivity).apply { addView(tv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))) }
        AlertDialog.Builder(this).setTitle("Capture report").setView(box)
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Portal capture report", text)); toast("Report copied.")
            }
            .setNeutralButton("Share") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share report")) }
            .setNegativeButton("Close", null).show()
    }

    private fun more() {
        AlertDialog.Builder(this).setTitle("Portal Capture")
            .setItems(arrayOf("Start over (clear the report)", "Sign out and clear this browser", "What this app does")) { _, w ->
                when (w) {
                    0 -> confirm("Clear all captured pages and steps?") { pages = JSONArray(); steps = JSONArray(); hosts.clear(); save(); updateButtons(); toast("Report cleared.") }
                    1 -> confirm("Sign out of the portal here and clear its cookies and saved data in this app?") {
                        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
                        web.clearCache(true); web.clearHistory(); web.loadUrl("about:blank"); toast("Signed out and cleared.")
                    }
                    2 -> intro()
                }
            }.show()
    }

    private fun confirm(msg: String, yes: () -> Unit) { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK") { _, _ -> yes() }.setNegativeButton("Cancel", null).show() }

    private fun intro() {
        AlertDialog.Builder(this).setTitle("Portal Capture")
            .setMessage(
                "This app helps set up mileage auto-fill by recording how your company's mileage form is laid out.\n\n" +
                "1. Type the mileage portal's web address at the top and tap Go.\n" +
                "2. Sign in as you normally do.\n" +
                "3. Open the page where you enter mileage and tap Capture page. Do the same on each page of the entry (for example the Add trip screen).\n" +
                "4. Optional: tap Record steps, then click through an entry without submitting, so the order of screens and buttons is recorded.\n" +
                "5. Tap Report, check it, and tap Copy.\n\n" +
                "What it records: field labels, names and types, drop-down choices, button names, page addresses (without anything after the ?), and the buttons you tap.\n\n" +
                "What it never records: anything you type, including your password. Nothing is sent anywhere; the report stays on this phone until you copy or share it.\n\n" +
                "Tip: if sign-in doesn't work inside this app, say so in your report. That tells us auto-fill needs to run in Chrome instead.\n\n" +
                "Check with IT before using tools on company systems.")
            .setPositiveButton("Got it") { _, _ -> prefs.edit().putBoolean("introSeen", true).apply() }
            .setCancelable(false).show()
    }

    private fun save() {
        prefs.edit().putString("pages", pages.toString()).putString("steps", steps.toString()).putString("hosts", JSONArray(hosts.toList()).toString()).apply()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
