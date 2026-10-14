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
 * Purpose, Ending odometer and Personal Yes/No (MPG fills the date and starting odometer), highlights them, and waits: you check and tap Save.
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
        row.addView(btn("Fill again") { fillNow() })
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
            if (filledUrl != url || !awaitingSave) fill()   // waits for MPG to finish setting up the form first
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

    private var waitToken = 0

    /**
     * MPG finishes setting up its form a moment after the page loads (it puts in the date and the starting odometer).
     * Filling before that gets overwritten, so wait until the form is there, has MPG's starting odometer and date,
     * and hasn't changed for about a second (or 10 seconds have passed), then fill.
     */
    private fun fill() {
        if (idx >= trips.length()) return
        val token = ++waitToken; val started = System.currentTimeMillis(); var last = ""; var same = 0
        show("${head()}\nWaiting for MPG to finish loading the form…")
        val probe = "(function(){var s=document.getElementById('startOdo'),e=document.getElementById('endOdo'),p=document.getElementById('purposeDesc'),d=document.getElementById('entryDate');" +
            "if(!s||!e||!p) return 'none'; return [s.value,d?d.value:'',document.readyState,(window.jQuery&&jQuery.active)||0].join('|');})()"
        fun poll() {
            if (token != waitToken || idx >= trips.length()) return
            web.evaluateJavascript(probe) { raw ->
                val v = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { "" }
                val parts = v.split("|")
                val ready = v != "none" && parts.size >= 4 && parts[0].trim().toIntOrNull() != null && parts[1].isNotBlank() && parts[2] == "complete" && parts[3] == "0"
                if (ready && v == last) same++ else same = 0
                last = v
                val waited = System.currentTimeMillis() - started
                when {
                    ready && same >= 2 -> fillNow()                       // steady for ~1 s
                    waited > 10_000L && v != "none" -> fillNow()           // fill anyway; the checks at Save still apply
                    waited > 20_000L -> show("${head()}\nMPG's form didn't load. Tap Fill again when it's showing.")
                    else -> web.postDelayed({ poll() }, 500)
                }
            }
        }
        web.postDelayed({ poll() }, 300)
    }

    /** Fills the Create New Trip form for the current trip. Never presses Save. */
    private fun fillNow() {
        waitToken++
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
  // MPG fills in the date and starting odometer itself; Companion leaves the start alone.
  var start = mpgStart;
  var end = (t.endOdo!=null) ? t.endOdo : (start!=null ? start + (t.miles||0) : null);
  set(p, String(t.purpose||'').slice(0,100));
  if(end!=null) set(e, end);
  if(per){ for(var i=0;i<per.options.length;i++){ if(per.options[i].text.trim().toLowerCase()===(t.personal?'yes':'no')){ per.selectedIndex=i; } } set(per, per.value); }
  var dateSet='kept', dateBefore=dt?dt.value:'';
  // Is a date string (any of m/d/yyyy, mm/dd/yyyy, yyyy-mm-dd, with or without a time) this trip's day?
  function sameDay(v){ v=String(v||''); var a=v.match(/(\d{4})-(\d{1,2})-(\d{1,2})/), b=v.match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
    if(a) return +a[1]===d.y&&+a[2]===d.m&&+a[3]===d.d; if(b) return +b[3]===d.y&&+b[1]===d.m&&+b[2]===d.d; return false; }
  // Write a value the way the page's own scripts will notice (the input's native setter, then the usual events).
  function force(el,v){ try{ var ro=el.hasAttribute('readonly'); if(ro) el.removeAttribute('readonly');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);
    ['input','change','blur'].forEach(function(e){ el.dispatchEvent(new Event(e,{bubbles:true})); }); if(window.jQuery) jQuery(el).trigger('change');
    if(ro) el.setAttribute('readonly','readonly'); }catch(x){} }
  function fmtLike(cur){ var y4=String(d.y), m2=('0'+d.m).slice(-2), d2=('0'+d.d).slice(-2);
    var time=(String(cur).match(/\s+\d{1,2}:\d{2}.*$/)||[''])[0];
    if(/^\d{4}-/.test(cur)) return y4+'-'+m2+'-'+d2+(String(cur).indexOf('T')>0?String(cur).slice(10):time);
    var pad=/^\d{2}\//.test(cur)||!cur; return (pad?m2:d.m)+'/'+(pad?d2:d.d)+'/'+y4+time; }
  if(dt && !d.today){
    var nd=new Date(d.y, d.m-1, d.d);
    if(dt._flatpickr){ try{ dt._flatpickr.setDate(nd,true); dateSet='flatpickr'; }catch(x){} }
    if(window.jQuery){ var jd=jQuery(dt);
      try{ if(jd.data('datepicker')||jd.data('DateTimePicker')||(jQuery.fn.datepicker&&jd.hasClass('hasDatepicker'))){ jd.datepicker('setDate', nd); dateSet='picker'; } }catch(x){}
      try{ if(jd.data('DateTimePicker')){ jd.data('DateTimePicker').date(nd); dateSet='dtp'; } }catch(x){}
      try{ if(jd.data('daterangepicker')){ jd.data('daterangepicker').setStartDate(nd); jd.data('daterangepicker').setEndDate(nd); dateSet='drp'; } }catch(x){} }
    if(!sameDay(dt.value)){ force(dt, fmtLike(dt.value||'')); if(dateSet==='kept') dateSet='typed'; }
    // Hidden copies of the date the form really sends (common with picker widgets)
    var hid=[].slice.call(document.querySelectorAll('input[type=hidden]')).filter(function(h){ return /date/i.test((h.name||'')+' '+(h.id||'')) && /\d/.test(h.value||''); });
    hid.forEach(function(h){ if(!sameDay(h.value)) force(h, fmtLike(h.value)); });
    mark(dt);
    // Keep it: some pages put the original date back right after loading
    [500,1500,3000].forEach(function(ms){ setTimeout(function(){ if(!sameDay(dt.value)) force(dt, fmtLike(dt.value||'')); hid.forEach(function(h){ if(!sameDay(h.value)) force(h, fmtLike(h.value)); }); }, ms); });
  }
  // MPG's own page scripts can reset Personal right after loading: set it again shortly, unless you've changed it yourself.
  var want=t.personal?'yes':'no', touched=false;
  function perText(){ return per&&per.selectedIndex>=0 ? per.options[per.selectedIndex].text.trim().toLowerCase() : ''; }
  function setPer(){ if(!per||touched) return; for(var k=0;k<per.options.length;k++){ if(per.options[k].text.trim().toLowerCase()===want){ if(per.selectedIndex!==k){ per.selectedIndex=k; set(per, per.value); } } } }
  if(per && !per.__cpw){ per.__cpw=1; per.addEventListener('change',function(ev){ if(ev.isTrusted) touched=true; }); }
  [400,1000,2000,3500].forEach(function(ms){ setTimeout(setPer, ms); });
  // At Save: if the date or Personal doesn't match this trip, ask first.
  var save=q('EditJournalSubmitBtn');
  if(save && !save.__cpw){ save.__cpw=1; save.addEventListener('click',function(ev){
    if(!d.today && dt && !sameDay(dt.value) && !window.confirm('This trip is for '+d.m+'/'+d.d+'/'+d.y+', but the form\'s date says '+(dt.value||'nothing')+'.\n\nOK = save it with that date anyway.\nCancel = go back and set the date (tap the date box).')){ ev.preventDefault(); ev.stopImmediatePropagation(); return; }
    var got=perText();
    if(per && got && got!==want && !window.confirm('Companion has this trip as Personal: '+(t.personal?'Yes':'No')+', but the form says '+(got==='yes'?'Yes':'No')+'.\n\nOK = save it as it is now.\nCancel = go back and check.')){ ev.preventDefault(); ev.stopImmediatePropagation(); }
  }, true); }
  if(t.endOdo==null && s && !s.__cpw){ s.__cpw=1; var lastS=s.value;
    setInterval(function(){ if(s.value!==lastS){ lastS=s.value; var ns=parseInt(s.value,10); if(!isNaN(ns)&&document.activeElement!==e) set(e, ns+(t.miles||0)); } }, 700); }
  window.scrollTo(0,0);
  return JSON.stringify({ok:true, start:start, end:end, mpgStart:mpgStart, dateSet:dateSet, dateOk:d.today||!dt||sameDay(dt.value), hidden:(typeof hid!=='undefined'?hid.length:0), dateBefore:dateBefore, dateAfter:dt?dt.value:'',
    jq:!!window.jQuery, picker:!!(window.jQuery&&jQuery.fn&&jQuery.fn.datepicker)});
})(${t}, ${date});
""".trimIndent()
        web.evaluateJavascript(js) { res ->
            val r = try { JSONObject(JSONArray("[$res]").getString(0)) } catch (_: Exception) { JSONObject() }
            if (!r.optBoolean("ok")) { show("${head()}\nWaiting for MPG's Create New Trip form…"); return@evaluateJavascript }
            awaitingSave = true; filledUrl = web.url ?: ""
            val start = r.opt("start"); val end = r.opt("end")
            var msg = "${head()}\nOdometer $start (MPG) → $end. Check the yellow fields, then tap Save."
            if (t.isNull("endOdo")) msg += "\nEnding = MPG's start + ${t.optInt("miles")} mi measured by Companion."
            if (!date.optBoolean("today")) msg += if (r.optBoolean("dateOk")) "\nDate set to ${date.optInt("m")}/${date.optInt("d")}/${date.optInt("y")}: check it before Save."
                else "\n⚠ Set Date Of Trip to ${date.optInt("m")}/${date.optInt("d")}/${date.optInt("y")} yourself (tap the date box)."
            val est = t.opt("estStart")
            if (start is Number && est is Number && Math.abs(start.toInt() - est.toInt()) > 2)
                msg += "\nHeads up: Companion expected about $est at the start. If MPG's number is wrong, fix it here and update your odometer in Companion's Settings."
            show(msg)
        }
    }

    companion object {
        const val MPG_HOST = "mobilityapps.publix.org"
        const val NEW_TRIP = "https://mobilityapps.publix.org/mpg/mileageentry/editjournalentry/-1"
    }
}
