package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
 * SAP import. You sign in, then open your call list (the button jumps to the Service Technician team list). Companion
 * reads only responses that carry call data (OrderId) and adds them to your history, filtered by your saved districts and
 * order types — your login is never read, because it isn't call data. Capture runs the whole time; the session is kept.
 */
@SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "AddJavascriptInterface")
class SapActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bigBtn: Button

    private enum class Mode { SIGN_IN, IMPORTING, DONE }
    @Volatile private var mode = Mode.SIGN_IN
    private var found = 0
    private var added = 0

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
        bigBtn = btn("Go to my team call list", true) { web.loadUrl(base() + Sap.MONITOR); mode = Mode.IMPORTING; updateStatus() }
        root.addView(bigBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) })
        root.addView(btn("Done", false) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(8)) })
        setContentView(root)

        setupWeb()
        status.text = "Sign in, then open your call list (or tap the button below). Calls import on their own. Your login is never read."
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
                if (u != null && u.contains("flpassociateapps.publix.com") && u.contains("#")) { mode = Mode.IMPORTING; runOnUiThread { bigBtn.visibility = Button.VISIBLE; updateStatus() } }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface fun cap(json: String) {
            try {
                val res = JSONObject(json).optString("res")
                if (!res.contains("OrderId")) return
                val (f, a) = Sap.importBody(this@SapActivity, res)
                if (f > 0) { found += f; added += a; runOnUiThread { updateStatus() } }
            } catch (_: Exception) {}
        }
    }

    private fun updateStatus() {
        val d = Sap.districts(this); val t = Sap.types(this)
        val filt = (if (d.isEmpty()) "all districts" else d.sorted().joinToString("/")) + " · " + (if (t.isEmpty()) "all types" else t.sorted().joinToString("/"))
        status.text = if (found == 0) "Sign in, then open your call list. Importing automatically · $filt"
            else "Imported $added new call(s) ($found seen) · $filt"
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
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
