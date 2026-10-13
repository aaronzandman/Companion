package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logs a day's trips in the company's MPG mileage site, one trip at a time.
 * You sign in on Microsoft's own page (Companion never sees your password). For each trip Companion fills in
 * Purpose, Starting and Ending odometer and Personal Yes/No, highlights them, and waits: you check and tap Save.
 * Companion never taps Save itself. When MPG goes back to its main page after Save, the next trip opens.
 */
class MpgActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var banner: TextView
    private lateinit var trips: JSONArray
    private lateinit var date: JSONObject
    private var idx = 0
    private var awaitingSave = false
    private var filledUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = JSONObject(intent.getStringExtra("data") ?: "{}")
        trips = data.optJSONArray("trips") ?: JSONArray(); date = data.optJSONObject("date") ?: JSONObject()
        idx = savedInstanceState?.getInt("idx") ?: 0

        val green = Color.parseColor("#1F6B4F")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(green); setPadding(dp(12), dp(10), dp(12), dp(8)) }
        banner = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        top.addView(banner)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0) }
        row.addView(btn("Fill again") { fill() })
        row.addView(btn("Skip trip") { confirm("Skip this trip without logging it?") { next(false) } })
        row.addView(btn("Close") { finish() })
        top.addView(row)
        root.addView(top)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MpgActivity).setTitle("MPG").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }.setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show(); return true
            }
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MpgActivity).setTitle("MPG").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }.setOnCancelListener { result.confirm() }.show(); return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) { onPage(url) }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (trips.length() == 0) { finish(); return }
        show("Opening MPG…")
        web.loadUrl(NEW_TRIP)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("idx", idx) }
    @Deprecated("Back handling")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(Color.parseColor("#1F6B4F")); setBackgroundColor(Color.WHITE)
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { on() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
    }
    private fun confirm(m: String, yes: () -> Unit) { AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK") { _, _ -> yes() }.setNegativeButton("Cancel", null).show() }
    private fun show(t: String) { banner.text = t }
    private fun trip() = trips.getJSONObject(idx)
    private fun head() = "Trip ${idx + 1} of ${trips.length()}: ${trip().optString("label")} · Personal: ${if (trip().optBoolean("personal")) "Yes" else "No"}"

    private fun onPage(url: String) {
        val u = Uri.parse(url); val host = u.host ?: ""; val path = (u.path ?: "").lowercase()
        if (!host.equals(MPG_HOST, true)) {
            if (host.contains("microsoft") || host.contains("login")) show("Sign in with your Publix Microsoft account. Companion doesn't see or keep your password.")
            return
        }
        if (path.contains("/mileageentry/editjournalentry/-1")) {
            if (filledUrl != url || !awaitingSave) fill()
        } else if (path.trimEnd('/') == "/mpg") {
            if (awaitingSave) next(true)          // MPG went back to its main page after Save
            else web.loadUrl(NEW_TRIP)
        }
    }

    private fun next(saved: Boolean) {
        if (saved) markDone(trip().optString("key"))
        awaitingSave = false; filledUrl = ""
        idx++
        if (idx >= trips.length()) {
            show("All done. ${trips.length()} trip${if (trips.length() == 1) "" else "s"} handled. You can close this screen.")
            AlertDialog.Builder(this).setTitle("Trips logged").setMessage("Every trip for this day has been handled. Check MPG's main page to see them.")
                .setPositiveButton("Close") { _, _ -> finish() }.setNegativeButton("Stay here", null).show()
            return
        }
        show("Opening trip ${idx + 1}…")
        web.postDelayed({ web.loadUrl(NEW_TRIP) }, 400)
    }

    private fun markDone(key: String) {
        if (key.isEmpty()) return
        val o = try { JSONObject(Store.get(this, "mpgDone", "{}")) } catch (_: Exception) { JSONObject() }
        o.put(key, System.currentTimeMillis())
        val cut = System.currentTimeMillis() - 60L * 86_400_000L
        o.keys().asSequence().toList().forEach { if (o.optLong(it) < cut) o.remove(it) }
        Store.put(this, "mpgDone", o.toString())
    }

    /** Fills the Create New Trip form for the current trip. Never presses Save. */
    private fun fill() {
        if (idx >= trips.length()) return
        val t = trip()
        val js = """
(function(t, d){
  function q(id){ return document.getElementById(id); }
  function mark(el){ el.style.outline='3px solid #F2C14E'; el.style.background='#FFF8DC'; }
  function set(el, v){ if(!el) return false; el.value = v; ['input','change','blur'].forEach(function(e){ el.dispatchEvent(new Event(e,{bubbles:true})); }); if(window.jQuery) jQuery(el).trigger('change'); mark(el); return true; }
  var s=q('startOdo'), e=q('endOdo'), p=q('purposeDesc'), per=q('personalUse')||document.querySelector('[name=isPersonal]'), dt=q('entryDate');
  if(!s||!e||!p) return JSON.stringify({ok:false});
  var mpgStart=parseInt(s.value,10); if(isNaN(mpgStart)) mpgStart=null;
  var start = (t.startOdo!=null) ? t.startOdo : mpgStart;
  var end = (t.endOdo!=null) ? t.endOdo : (start!=null ? start + (t.miles||0) : null);
  set(p, String(t.purpose||'').slice(0,100));
  if(start!=null) set(s, start);
  if(end!=null) set(e, end);
  if(per){ for(var i=0;i<per.options.length;i++){ if(per.options[i].text.trim().toLowerCase()===(t.personal?'yes':'no')){ per.selectedIndex=i; } } set(per, per.value); }
  var dateSet='kept';
  if(dt && !d.today){
    var nd=new Date(d.y, d.m-1, d.d);
    if(window.jQuery && jQuery(dt).datepicker){ try{ jQuery(dt).datepicker('setDate', nd); dateSet='picker'; }catch(x){} }
    if(dateSet!=='picker'){
      var cur=dt.value||'', pad=/^\d{2}\//.test(cur), y4=String(d.y);
      var mm=pad?('0'+d.m).slice(-2):String(d.m), dd=pad?('0'+d.d).slice(-2):String(d.d);
      dt.removeAttribute('readonly'); set(dt, /^\d{4}-/.test(cur) ? (y4+'-'+('0'+d.m).slice(-2)+'-'+('0'+d.d).slice(-2)) : (mm+'/'+dd+'/'+y4)); dt.setAttribute('readonly','readonly'); dateSet='typed';
    }
    mark(dt);
  }
  window.scrollTo(0,0);
  return JSON.stringify({ok:true, start:start, end:end, mpgStart:mpgStart, dateSet:dateSet});
})(${t}, ${date});
""".trimIndent()
        web.evaluateJavascript(js) { res ->
            val r = try { JSONObject(JSONArray("[$res]").getString(0)) } catch (_: Exception) { JSONObject() }
            if (!r.optBoolean("ok")) { show("${head()}\nWaiting for MPG's Create New Trip form…"); return@evaluateJavascript }
            awaitingSave = true; filledUrl = web.url ?: ""
            val start = r.opt("start"); val end = r.opt("end"); val mpgStart = r.opt("mpgStart")
            var msg = "${head()}\nOdometer $start → $end. Check the yellow fields, then tap Save."
            if (t.isNull("startOdo") && mpgStart != null && mpgStart != JSONObject.NULL) msg += "\nStarting odometer is MPG's own; ending = start + ${t.optInt("miles")} mi measured by Companion."
            else if (mpgStart is Number && start is Number && Math.abs(mpgStart.toInt() - start.toInt()) > 1)
                msg += "\nNote: MPG suggested $mpgStart as the starting odometer; Companion used $start from your odometer readings."
            show(msg)
        }
    }

    companion object {
        const val MPG_HOST = "mobilityapps.publix.org"
        const val NEW_TRIP = "https://mobilityapps.publix.org/mpg/mileageentry/editjournalentry/-1"
    }
}
