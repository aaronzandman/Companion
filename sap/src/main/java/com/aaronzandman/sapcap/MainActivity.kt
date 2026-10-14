package com.aaronzandman.sapcap

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * SAP Capture (test tool). Loads SAP in a WebView and, while capture is ON, records the raw data the page fetches
 * (fetch/XHR responses, which on a Fiori app is the call list as JSON/OData) plus whatever you grab from the page.
 * Capture starts PAUSED so your sign-in is never recorded. Secrets (passwords, tokens) are masked before anything
 * is stored. Nothing leaves the phone until you tap Copy. Keystrokes are never recorded.
 */
@SuppressLint("SetJavaScriptEnabled", "SetTextI18n", "AddJavascriptInterface")
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var pauseBtn: Button
    private lateinit var status: TextView
    private lateinit var copyBtn: Button

    @Volatile private var paused = true
    private data class Cap(val data: Boolean, val text: String)
    private val caps = ArrayDeque<Cap>()
    private var seq = 0
    private var dataCount = 0

    private var copyParts: List<String>? = null
    private var copyI = 0
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private val REQ_FILE = 71

    private val GREEN = Color.parseColor("#0B6E4F")
    private val RED = Color.parseColor("#C62828")
    private val INK = Color.parseColor("#1B1F24")
    private val MUTED = Color.parseColor("#5F6B7A")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = GREEN
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F2F4F6")) }

        root.addView(TextView(this).apply {
            text = "SAP Capture — test"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); setBackgroundColor(GREEN); setPadding(dp(14), dp(12), dp(14), dp(12))
        })

        // URL row
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        url = EditText(this).apply {
            hint = "Paste your SAP / Fiori web address, then Go"; setText(Store.get(this@MainActivity, "url"))
            setSingleLine(); textSize = 14f; imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        urlRow.addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(btn("Go", true) { go() })
        root.addView(urlRow)

        // capture controls
        status = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(dp(12), 0, dp(12), dp(4)) }
        root.addView(status)
        pauseBtn = btn("▶  Resume capture", true) { togglePause() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let { it.width = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        root.addView(pauseBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, dp(8), dp(4)) })

        // webview
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // action rows
        root.addView(rowOf(
            btn("Grab this page", false) { grabPage() },
            btn("Clear", false) { confirmClear() }
        ))
        copyBtn = btn("Copy log", false) { copyLog() }
        root.addView(rowOf(
            copyBtn,
            btn("Copy data only", false) { copyData() }
        ))

        setContentView(root)
        setupWeb()
        if (url.text.isNotBlank()) go() else status.text = "Capture is PAUSED. Enter your SAP address, sign in, then Resume capture."
        updateStatus()
    }

    // ---------- web ----------
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false; useWideViewPort = true; loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
            javaScriptCanOpenWindowsAutomatically = true; setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.addJavascriptInterface(Bridge(), "__SAP")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url?.toString() ?: return false
                if (!u.startsWith("http")) { try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}; return true }
                return false   // keep every http(s) navigation (incl. SSO redirects) inside this WebView
            }
            override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) { inject() }
            override fun onPageFinished(v: WebView, u: String?) { inject(); u?.let { Store.put(this@MainActivity, "url", it); runOnUiThread { if (!url.hasFocus()) url.setText(it) } } }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileChooser = cb
                return try { startActivityForResult(p.createIntent(), REQ_FILE); true } catch (_: Exception) { fileChooser = null; false }
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_FILE) { fileChooser?.onReceiveValue(FileChooserParamsResult.parse(res, data)); fileChooser = null }
    }

    private fun go() {
        var u = url.text.toString().trim()
        if (u.isBlank()) { toast("Type your SAP address first"); return }
        if (!u.startsWith("http")) u = "https://$u"
        hideKeyboard()
        web.loadUrl(u)
    }

    /** Hooks fetch and XHR so their responses can be captured. Installed once per document; survives SPA navigation. */
    private fun inject() {
        web.evaluateJavascript(HOOK, null)
    }

    // ---------- capture ----------
    inner class Bridge {
        @JavascriptInterface fun cap(json: String) { if (!paused) onCapture(json) }
    }

    private fun flagData(url: String, res: String): Boolean {
        val t = res.trimStart()
        return t.startsWith("{") || t.startsWith("[") || Regex("odata|/sap/opu|\\.svc|incident|worklist|notification", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    @Synchronized private fun onCapture(json: String) {
        try {
            val o = JSONObject(json)
            val kind = o.optString("k"); val u = o.optString("url"); val method = o.optString("method", "")
            val stat = o.optString("status", ""); val req = mask(o.optString("req", "")); val res = mask(o.optString("res", ""))
            val data = flagData(u, o.optString("res", ""))
            seq++
            if (data) dataCount++
            val sb = StringBuilder()
            sb.append("#$seq  $kind $method $stat  ${if (data) "★data" else ""}\n")
            sb.append("URL: ${u.take(400)}\n")
            if (req.isNotBlank()) sb.append("» request: ${req.take(4000)}\n")
            sb.append("« response (${res.length} chars):\n").append(res.take(120_000))
            caps.addLast(Cap(data, sb.toString()))
            while (caps.size > 250) { val r = caps.removeFirst(); if (r.data) dataCount-- }
            runOnUiThread { updateStatus() }
        } catch (_: Exception) {}
    }

    /** Masks anything that looks like a secret, in JSON values and form-encoded bodies, before it's stored. */
    private fun mask(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        val keys = "password|passwd|pwd|pin|secret|token|csrf|authorization|auth|saml\\w*|assertion|sessionid|jsessionid|mysapsso"
        out = Pattern.compile("(\"(?:$keys)\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE).matcher(out).replaceAll("$1\"***\"")
        out = Pattern.compile("\\b($keys)=[^&\\s\"]+", Pattern.CASE_INSENSITIVE).matcher(out).replaceAll("$1=***")
        return out
    }

    private fun grabPage() {
        web.evaluateJavascript(GRAB) { raw ->
            val text = try { JSONObject("{\"v\":$raw}").optString("v") } catch (_: Exception) { "" }
            if (text.isBlank()) { toast("Nothing readable found on this page"); return@evaluateJavascript }
            seq++
            caps.addLast(Cap(true, "#$seq  grabbed from the page (what's on screen now):\n" + mask(text).take(120_000)))
            dataCount++
            updateStatus(); toast("Grabbed this page")
        }
    }

    // ---------- buttons / state ----------
    private fun togglePause() {
        paused = !paused
        if (!paused) toast("Recording. Navigate to your call list.") else toast("Paused.")
        updateStatus()
    }

    private fun updateStatus() {
        pauseBtn.text = if (paused) "▶  Resume capture" else "⏸  Pause capture"
        pauseBtn.background = pill(if (paused) GREEN else RED)
        status.text = if (paused)
            "● PAUSED — safe to sign in. Captured ${caps.size} responses (${dataCount} look like data)."
        else "● RECORDING. Captured ${caps.size} responses (${dataCount} look like data)."
        status.setTextColor(if (paused) MUTED else RED)
        copyParts = null; copyBtn.text = "Copy log"
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Clear everything captured so far?")
            .setPositiveButton("Clear") { _, _ -> caps.clear(); seq = 0; dataCount = 0; updateStatus() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun header() = "SAP Capture log · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n" +
        "(secrets masked; keystrokes never recorded)\n\n"

    private fun copyText(all: String, label: String) {
        if (all.isBlank()) { toast("Nothing captured yet"); return }
        if (all.length <= PART) { clip(all); copyParts = null; copyBtn.text = "Copy log"; toast("$label copied."); return }
        if (copyParts == null || copyI >= copyParts!!.size) { copyParts = split(all); copyI = 0 }
        val n = copyParts!!.size; val i = copyI
        clip("[SAP capture part ${i + 1} of $n]\n" + copyParts!![i]); copyI++
        toast(if (i + 1 < n) "Part ${i + 1} of $n copied. Paste it, then tap Copy part ${i + 2}." else "Last part ($n of $n) copied.")
        copyBtn.text = if (copyI < n) "Copy part ${copyI + 1} of $n" else "Copy log"
    }

    private fun copyLog() = copyText(header() + caps.joinToString("\n\n————————\n\n") { it.text }, "Log")
    private fun copyData() = copyText(header() + caps.filter { it.data }.joinToString("\n\n————————\n\n") { it.text }, "Data")

    private fun split(t: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (line in t.split("\n")) {
            if (sb.length + line.length + 1 > PART && sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            sb.append(line).append("\n")
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    // ---------- helpers ----------
    private fun clip(t: String) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SAP capture", t))
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun hideKeyboard() { try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(url.windowToken, 0) } catch (_: Exception) {} }

    private fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(10).toFloat() }
    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setOnClickListener { on() }; minHeight = dp(46)
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = if (primary) pill(GREEN) else GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), GREEN) }
    }
    private fun rowOf(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), dp(2), dp(4), dp(2)) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(2), dp(8), dp(2)) })
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    companion object {
        private const val PART = 18000
        private val HOOK = """
          (function(){ if(window.__sapcap) return; window.__sapcap=true; var B=window.__SAP;
            function send(o){ try{ B&&B.cap(JSON.stringify(o)); }catch(e){} }
            try{ var of=window.fetch; if(of){ window.fetch=function(){ var a=arguments,u=(a[0]&&a[0].url)||a[0],m=(a[1]&&a[1].method)||'GET',rb=(a[1]&&a[1].body)?(''+a[1].body):'';
              return of.apply(this,a).then(function(r){ try{ r.clone().text().then(function(t){ send({k:'fetch',url:''+u,method:m,status:r.status,req:rb,res:t}); }); }catch(e){} return r; }); }; } }catch(e){}
            try{ var X=window.XMLHttpRequest; if(X){ var op=X.prototype.open, sn=X.prototype.send;
              X.prototype.open=function(m,u){ this.__m=m; this.__u=u; return op.apply(this,arguments); };
              X.prototype.send=function(b){ var s=this; try{ s.addEventListener('load',function(){ try{ send({k:'xhr',url:''+s.__u,method:s.__m,status:s.status,req:b?(''+b):'',res:s.responseText}); }catch(e){} }); }catch(e){} return sn.apply(this,arguments); }; } }catch(e){}
          })();
        """.trimIndent()
        private val GRAB = """
          (function(){ var out=[];
            try{ document.querySelectorAll('table').forEach(function(t){ var rows=[]; t.querySelectorAll('tr').forEach(function(tr){ var c=[]; tr.querySelectorAll('th,td').forEach(function(td){ c.push((td.innerText||'').trim()); }); if(c.join('').trim()) rows.push(c.join(' | ')); }); if(rows.length) out.push('[table]\n'+rows.join('\n')); }); }catch(e){}
            try{ document.querySelectorAll('.sapMLIB,.sapMObjLItem,li[role="option"],tr[role="row"]').forEach(function(li){ var t=(li.innerText||'').replace(/\s+/g,' ').trim(); if(t&&t.length<600) out.push(t); }); }catch(e){}
            return out.join('\n\n'); })();
        """.trimIndent()
    }
}

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("sap", Context.MODE_PRIVATE)
    fun get(c: Context, k: String) = sp(c).getString(k, "") ?: ""
    fun put(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
}

object FileChooserParamsResult {
    fun parse(res: Int, data: Intent?): Array<Uri>? {
        if (res != Activity.RESULT_OK || data == null) return null
        data.dataString?.let { return arrayOf(Uri.parse(it)) }
        val cd = data.clipData ?: return null
        return Array(cd.itemCount) { cd.getItemAt(it).uri }
    }
}
