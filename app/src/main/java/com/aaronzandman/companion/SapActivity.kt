package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * SAP import. You tap Import, sign in, and the app takes over: once it sees you're signed into the launchpad it opens
 * the Service Technician team call list and pulls the calls into your history in the background, filtered by your saved
 * districts and order types. Your login is never read (capture is off until you're past sign-in). Session is kept.
 */
@SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "AddJavascriptInterface")
class SapActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bigBtn: Button
    private val h = Handler(Looper.getMainLooper())

    private enum class Mode { SIGN_IN, IMPORTING, DONE }
    @Volatile private var mode = Mode.SIGN_IN
    private var found = 0
    private var added = 0
    private var lastAdd = 0L

    private val GREEN = Color.parseColor("#0B6E4F")
    private val MUTED = Color.parseColor("#5F6B7A")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun base() = Sap.url(this).substringBefore("#")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = GREEN
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F2F4F6")) }
        root.addView(TextView(this).apply {
            text = "SAP import"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); setBackgroundColor(GREEN); setPadding(dp(14), dp(12), dp(14), dp(12))
        })
        status = TextView(this).apply { textSize = 14f; setTextColor(MUTED); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        root.addView(status)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bigBtn = btn("Import my calls now", true) { startImport(manual = true) }
        root.addView(bigBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) })
        root.addView(btn("Done", false) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(8)) })
        setContentView(root)

        setupWeb()
        status.text = "Opening SAP… sign in when it appears. Companion won't read your login."
        bigBtn.visibility = Button.GONE
        web.loadUrl(base())
    }

    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            useWideViewPort = true; loadWithOverviewMode = true; mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.addJavascriptInterface(Bridge(), "__SAP")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) { web.evaluateJavascript(HOOK, null) }
            override fun onPageFinished(v: WebView, u: String?) {
                web.evaluateJavascript(HOOK, null)
                if (u != null && u.contains("flpassociateapps.publix.com") && mode == Mode.SIGN_IN) {
                    // Only once the launchpad shell is actually rendered (i.e. we're past the sign-in pages).
                    web.evaluateJavascript("(function(){try{return (window.sap&&sap.ushell)||document.getElementById('shell')?1:0}catch(e){return 0}})()") { r ->
                        if (r.trim('"') == "1") { status.text = "Signed in — loading your team calls…"; bigBtn.visibility = Button.VISIBLE; startImport(manual = false) }
                    }
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface fun cap(json: String) {
            if (mode == Mode.SIGN_IN) return
            try {
                val res = JSONObject(json).optString("res")
                if (!res.contains("OrderId")) return
                val (f, a) = Sap.importBody(this@SapActivity, res)
                if (f > 0) { found += f; added += a; lastAdd = System.currentTimeMillis(); runOnUiThread { updateStatus() } }
            } catch (_: Exception) {}
        }
    }

    private var navigated = false
    private fun startImport(manual: Boolean) {
        if (mode == Mode.IMPORTING && !manual) return
        mode = Mode.IMPORTING
        bigBtn.text = "Importing…"; bigBtn.visibility = Button.VISIBLE
        // Open the team call list; its data load is captured by the JS hook and merged as it arrives.
        if (!navigated || manual) { navigated = true; web.loadUrl(base() + Sap.MONITOR) }
        updateStatus()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ checkDone(0) }, 8000)
    }

    /** Waits for the call data to stop arriving, then reports. */
    private fun checkDone(idleChecks: Int) {
        if (mode != Mode.IMPORTING) return
        val idle = System.currentTimeMillis() - lastAdd > 6000
        if (found > 0 && idle || idleChecks >= 5) {
            mode = Mode.DONE
            bigBtn.text = "Import again"
            updateStatus()
            if (found == 0) status.text = "No calls loaded. Make sure your call list opened, then tap Import again."
            CookieManager.getInstance().flush()
        } else h.postDelayed({ checkDone(idleChecks + 1) }, 4000)
    }

    private fun updateStatus() {
        val d = Sap.districts(this); val t = Sap.types(this)
        val filt = (if (d.isEmpty()) "all districts" else d.sorted().joinToString("/")) + " · " + (if (t.isEmpty()) "all types" else t.sorted().joinToString("/"))
        status.text = when (mode) {
            Mode.IMPORTING -> "● Importing your team calls — $added added, $found seen · $filt"
            Mode.DONE -> "Done — $added new call(s) added ($found seen). Filters: $filt. Tap Done."
            else -> "Opening SAP… sign in when it appears."
        }
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onBackPressed() { if (web.canGoBack() && mode == Mode.SIGN_IN) web.goBack() else super.onBackPressed() }

    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setOnClickListener { on() }; minHeight = dp(46); gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = if (primary) GradientDrawable().apply { setColor(GREEN); cornerRadius = dp(10).toFloat() }
                    else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), GREEN) }
    }

    companion object {
        private val HOOK = """
          (function(){ if(window.__sapcap) return; window.__sapcap=true; var B=window.__SAP;
            function send(t){ try{ if(t&&t.indexOf('OrderId')>=0) B&&B.cap(JSON.stringify({res:t})); }catch(e){} }
            try{ var of=window.fetch; if(of){ window.fetch=function(){ return of.apply(this,arguments).then(function(r){ try{ r.clone().text().then(send); }catch(e){} return r; }); }; } }catch(e){}
            try{ var X=window.XMLHttpRequest; if(X){ var sn=X.prototype.send;
              X.prototype.send=function(b){ var s=this; try{ s.addEventListener('load',function(){ try{ send(s.responseText); }catch(e){} }); }catch(e){} return sn.apply(this,arguments); }; } }catch(e){}
          })();
        """.trimIndent()
    }
}
