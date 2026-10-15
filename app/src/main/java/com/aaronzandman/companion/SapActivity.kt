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
 * SAP import. You sign in, then open your call list (the button jumps to the Service Technician team list). Companion
 * reads only responses that carry call data (OrderId) and adds them to your history, filtered by your saved districts and
 * order types — your login is never read, because it isn't call data. Capture runs the whole time; the session is kept.
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
        bigBtn = btn("Go to my team call list", true) { toTeamList() }
        root.addView(bigBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(2)) })
        root.addView(btn("Done", false) { commit(); finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(2), dp(8), dp(8)) })
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
                if (u != null && u.contains("flpassociateapps.publix.com")) {
                    mode = Mode.IMPORTING; runOnUiThread { bigBtn.visibility = Button.VISIBLE; updateStatus() }
                    // Signed in and on the launchpad: open the team call list on its own, once.
                    if (!autoStarted && !u.contains("ZFIELDTECH")) { autoStarted = true; h.postDelayed({ toTeamList() }, 1500) }
                }
            }
        }
    }

    private var autoStarted = false
    /** Opens the Service Technician monitor and auto-taps Team Queue (and dismisses the truncation alert). */
    private fun toTeamList() {
        mode = Mode.IMPORTING
        if (!web.url.orEmpty().contains("ZFIELDTECH")) web.loadUrl(base() + Sap.MONITOR)
        runOnUiThread { bigBtn.text = "Loading team calls…"; updateStatus() }
        // The app loads async; keep nudging the Team Queue tile until the data arrives.
        for (d in longArrayOf(1500, 3000, 5000, 8000, 12000)) h.postDelayed({ web.evaluateJavascript(AUTO, null) }, d)
    }

    // All orders seen this session (deduped by order number), before filtering. The whole call list is replaced from this.
    private val buffer = LinkedHashMap<String, JSONObject>()
    @Volatile private var committed = false

    inner class Bridge {
        @JavascriptInterface fun cap(json: String) {
            try {
                val res = JSONObject(json).optString("res")
                if (!res.contains("OrderId")) return
                var changed = false
                for (o in Sap.parse(res)) { val k = o.optString("order"); if (!buffer.containsKey(k)) changed = true; buffer[k] = o }
                if (changed) {
                    found = buffer.size; added = Sap.filtered(this@SapActivity, buffer.values.toList()).size
                    // Auto-finish once the data stops arriving (hands-off flow); replaces the whole list.
                    h.removeCallbacksAndMessages("idle")
                    runOnUiThread { updateStatus(); h.postAtTime({ commit() }, "idle", android.os.SystemClock.uptimeMillis() + 7000) }
                }
            } catch (_: Exception) {}
        }
    }

    /** Replaces the entire call list with this session's SAP orders (filtered). Safe: never wipes on an empty pull. */
    private fun commit() {
        if (committed || buffer.isEmpty()) return
        committed = true
        val (kept, seen) = Sap.replace(this, buffer.values.toList())
        runOnUiThread { status.text = "Done — your call list is now the $kept call(s) from SAP ($seen seen). Tap Done."; Toast.makeText(this, "Call list replaced: $kept calls", Toast.LENGTH_LONG).show() }
    }

    private fun updateStatus() {
        val d = Sap.districts(this); val t = Sap.types(this)
        val filt = (if (d.isEmpty()) "all districts" else d.sorted().joinToString("/")) + " · " + (if (t.isEmpty()) "all types" else t.sorted().joinToString("/"))
        status.text = if (found == 0) "Sign in, then open your call list. Importing automatically · $filt"
            else "$added call(s) to keep ($found seen) · $filt · replaces your whole list when done"
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
        /** Taps the Team Queue tile and dismisses the "truncated" alert; the data loads behind it and is captured. */
        private val AUTO = """
          (function(){ if(window.__sapauto) return; window.__sapauto=true; var n=0;
            var iv=setInterval(function(){ n++;
              try{ Array.prototype.slice.call(document.querySelectorAll('button,.sapMBtn,.sapMBtnContent,bdi')).forEach(function(b){ if(((b.innerText||b.textContent)||'').trim()==='OK'){ (b.closest('.sapMBtn')||b).click(); } }); }catch(e){}
              if(!window.__teamTapped){ try{
                var nodes=document.querySelectorAll('bdi,span,div,a');
                for(var i=0;i<nodes.length;i++){ var el=nodes[i]; var t=((el.innerText||el.textContent)||'').trim();
                  if(/team\s*que/i.test(t) && t.length<40 && el.offsetParent!==null){
                    var c=el.closest('[role="button"],.sapMGT,.sapFCard,.sapMObjectListItem,li,div[tabindex]')||el;
                    try{ c.click(); }catch(e){}
                    try{ ['pointerdown','mousedown','mouseup','click'].forEach(function(ev){ c.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true})); }); }catch(e){}
                    window.__teamTapped=true; break; } } }catch(e){} }
              if(n>20) clearInterval(iv);
            },1000);
          })();
        """.trimIndent()

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
