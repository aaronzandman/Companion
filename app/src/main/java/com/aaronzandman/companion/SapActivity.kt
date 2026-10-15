package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * The SAP import screen. You sign in (capture is paused so your login is never read), then tap Resume and open your
 * call list; Companion reads the call data SAP loads and adds it to your history, filtered by your saved districts and
 * order types. Your SAP session is kept so re-opening this refreshes without a fresh login until the session lapses.
 */
@SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "AddJavascriptInterface")
class SapActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var pauseBtn: Button
    private lateinit var status: TextView

    @Volatile private var paused = true
    private var found = 0
    private var added = 0

    private val GREEN = Color.parseColor("#0B6E4F")
    private val RED = Color.parseColor("#C62828")
    private val MUTED = Color.parseColor("#5F6B7A")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = GREEN
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F2F4F6")) }
        root.addView(TextView(this).apply {
            text = "SAP import"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); setBackgroundColor(GREEN); setPadding(dp(14), dp(12), dp(14), dp(12))
        })

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        url = EditText(this).apply {
            hint = "Your SAP / Fiori web address"; setText(Store.get(this@SapActivity, "sapUrl", ""))
            setSingleLine(); textSize = 14f; imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        urlRow.addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(btn("Go", true) { go() })
        root.addView(urlRow)

        status = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(dp(12), 0, dp(12), dp(4)) }
        root.addView(status)
        pauseBtn = btn("▶  Resume & import", true) { togglePause() }
        root.addView(pauseBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, dp(8), dp(4)) })

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn("Done", false) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(8)) })

        setContentView(root)
        setupWeb()
        val u = Store.get(this, "sapUrl", "")
        if (u.isNotBlank()) { web.loadUrl(u); status.text = "Capture PAUSED — safe to sign in. Then tap Resume & import." }
        else status.text = "Enter your SAP address, sign in, then Resume & import."
        updateStatus()
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
                if (u != null && u.startsWith("http")) Store.put(this@SapActivity, "sapUrl", u)
            }
        }
    }

    private fun go() {
        var u = url.text.toString().trim(); if (u.isBlank()) { toast("Type your SAP address first"); return }
        if (!u.startsWith("http")) u = "https://$u"
        Store.put(this, "sapUrl", u); web.loadUrl(u)
    }

    inner class Bridge {
        @JavascriptInterface fun cap(json: String) {
            if (paused) return
            try {
                val res = JSONObject(json).optString("res")
                if (!res.contains("OrderId")) return
                val (f, a) = Sap.importBody(this@SapActivity, res)
                if (f > 0) { found += f; added += a; runOnUiThread { updateStatus() } }
            } catch (_: Exception) {}
        }
    }

    private fun togglePause() {
        paused = !paused
        if (!paused) toast("Importing. Open your call list / area view.") else { CookieManager.getInstance().flush(); toast("Paused.") }
        updateStatus()
    }

    private fun updateStatus() {
        pauseBtn.text = if (paused) "▶  Resume & import" else "⏸  Pause"
        pauseBtn.background = pill(if (paused) GREEN else RED)
        val d = Sap.districts(this); val t = Sap.types(this)
        val filt = "Filters: " + (if (d.isEmpty()) "all districts" else d.sorted().joinToString("/")) + " · " + (if (t.isEmpty()) "all types" else t.sorted().joinToString("/"))
        status.text = if (paused)
            (if (added > 0 || found > 0) "Imported $added new call(s) ($found seen). $filt" else "Capture PAUSED — safe to sign in. $filt")
        else "● IMPORTING — $added new call(s) added, $found seen. $filt"
        status.setTextColor(if (paused) MUTED else RED)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(10).toFloat() }
    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setOnClickListener { on() }; minHeight = dp(46); gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = if (primary) pill(GREEN) else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), GREEN) }
    }

    companion object {
        private val HOOK = """
          (function(){ if(window.__sapcap) return; window.__sapcap=true; var B=window.__SAP;
            function send(o){ try{ B&&B.cap(JSON.stringify(o)); }catch(e){} }
            try{ var of=window.fetch; if(of){ window.fetch=function(){ var a=arguments;
              return of.apply(this,a).then(function(r){ try{ r.clone().text().then(function(t){ if(t&&t.indexOf('OrderId')>=0) send({res:t}); }); }catch(e){} return r; }); }; } }catch(e){}
            try{ var X=window.XMLHttpRequest; if(X){ var sn=X.prototype.send;
              X.prototype.send=function(b){ var s=this; try{ s.addEventListener('load',function(){ try{ var t=s.responseText; if(t&&t.indexOf('OrderId')>=0) send({res:t}); }catch(e){} }); }catch(e){} return sn.apply(this,arguments); }; } }catch(e){}
          })();
        """.trimIndent()
    }
}
