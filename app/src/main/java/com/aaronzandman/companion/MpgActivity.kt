package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logs a day's trips in the company's MPG mileage site, one trip at a time.
 * You sign in on Microsoft's own page (Companion never sees your password). For each trip Companion fills in
 * Purpose, Starting and Ending odometer and Personal Yes/No, highlights them, and waits: you check and tap Save.
 * Companion never taps Save itself, except in a debug Test submit, where you type the test trip and confirm first.
 *
 * Debug mode (Settings) records what happens in MPG so problems can be fixed: pages opened, requests sent
 * (address only, no contents), errors, pop-up messages, and the trip fields Companion fills. It never records
 * your password, MPG's hidden security code, or any other field's value.
 */
class MpgActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var banner: TextView
    private var trips = JSONArray()
    private var date = JSONObject()
    private var idx = 0
    private var awaitingSave = false
    private var filledUrl = ""
    private var debug = false
    private var test = false
    private var testAsked = false
    private var testSubmitted = false
    private val log = JSONArray()
    private val t0 = System.currentTimeMillis()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = JSONObject(intent.getStringExtra("data") ?: "{}")
        trips = data.optJSONArray("trips") ?: JSONArray(); date = data.optJSONObject("date") ?: JSONObject()
        test = intent.getBooleanExtra("test", false)
        debug = test || Store.get(this, "debug", "0") == "1"
        idx = savedInstanceState?.getInt("idx") ?: 0
        dbg("start", JSONObject().put("mode", if (test) "test submit" else "log trips").put("trips", trips.length()).put("app", BuildConfig.VERSION_NAME))

        val green = Color.parseColor("#1F6B4F")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (test) Color.parseColor("#8A4B08") else green); setPadding(dp(12), dp(10), dp(12), dp(8)) }
        banner = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        top.addView(banner)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(6), 0, 0) }
        if (test) {
            row.addView(btn("Enter test trip") { testDialog() })
        } else {
            row.addView(btn("Fill again") { fill() })
            row.addView(btn("Skip trip") { confirm("Skip this trip without logging it?") { next(false) } })
        }
        if (debug) row.addView(btn("Debug report") { showReport() })
        row.addView(btn("Close") { finish() })
        top.addView(HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled = false })
        root.addView(top)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                dbg("mpg question", JSONObject().put("text", red(message ?: "")))
                AlertDialog.Builder(this@MpgActivity).setTitle("MPG").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> dbg("answered", JSONObject().put("ok", true)); result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> dbg("answered", JSONObject().put("ok", false)); result.cancel() }
                    .setOnCancelListener { result.cancel() }.show(); return true
            }
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                dbg("mpg message", JSONObject().put("text", red(message ?: "")))
                AlertDialog.Builder(this@MpgActivity).setTitle("MPG").setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }.setOnCancelListener { result.confirm() }.show(); return true
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    dbg("page " + m.messageLevel().name.lowercase(), JSONObject().put("text", red(m.message())).put("where", path(m.sourceId()) + ":" + m.lineNumber()))
                return false
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = false
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                // Debug: note requests to MPG (method and address only, never their contents). Static files are skipped.
                val u = req.url
                if (debug && (u.host ?: "").equals(MPG_HOST, true) && !Regex("\\.(js|css|png|jpg|gif|svg|woff2?|ico|map)$", RegexOption.IGNORE_CASE).containsMatchIn(u.path ?: ""))
                    runOnUiThread { dbg("request", JSONObject().put("method", req.method).put("path", u.path ?: "").put("page", req.isForMainFrame)) }
                return null
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                dbg("http error", JSONObject().put("status", res.statusCode).put("site", req.url.host ?: "").put("path", req.url.path ?: "").put("page", req.isForMainFrame))
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) dbg("load error", JSONObject().put("code", err.errorCode).put("text", err.description?.toString() ?: "").put("site", req.url.host ?: ""))
            }
            override fun onPageFinished(view: WebView, url: String) { onPage(url) }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (!test && trips.length() == 0) { finish(); return }
        show(if (test) "TEST SUBMIT (debug). Opening MPG's Create New Trip…" else "Opening MPG…")
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
    private fun red(t: String) = t.replace(Regex("\\s+"), " ").trim().take(300)
        .replace(Regex("[^\\s@]+@[^\\s@]+\\.[a-zA-Z]{2,}"), "[email]")
    private fun path(u: String?) = try { Uri.parse(u ?: "").let { (it.host ?: "") + (it.path ?: "") } } catch (_: Exception) { "" }

    /** Adds one line to the debug log (only in debug mode). */
    private fun dbg(ev: String, o: JSONObject = JSONObject()) {
        if (!debug || log.length() >= 600) return
        log.put(o.put("ev", ev).put("s", (System.currentTimeMillis() - t0) / 100 / 10.0))
    }

    private fun onPage(url: String) {
        val u = Uri.parse(url); val host = u.host ?: ""; val p = (u.path ?: "").lowercase()
        dbg("page", JSONObject().put("site", host).put("path", u.path ?: "").put("title", red(web.title ?: "")))
        if (!host.equals(MPG_HOST, true)) {
            if (host.contains("microsoft") || host.contains("login")) show("Sign in with your Publix Microsoft account. Companion doesn't see or keep your password.")
            return
        }
        if (debug) snapshot("page loaded")
        if (p.contains("/mileageentry/editjournalentry/-1")) {
            if (test) { if (testSubmitted) show("TEST: MPG stayed on the form after Save. Check for a message on the page, then tap Debug report."); else if (!testAsked) web.postDelayed({ testDialog() }, 500) }
            else if (filledUrl != url || !awaitingSave) fill()
        } else if (p.trimEnd('/') == "/mpg") {
            if (test) {
                if (testSubmitted) { show("TEST: MPG went back to its main page after Save, so the test trip was saved. Delete it in MPG when you're done. Tap Debug report and send it to Claude."); dbg("result", JSONObject().put("saved", true)); web.postDelayed({ showReport() }, 1200) }
                else web.loadUrl(NEW_TRIP)
            } else if (awaitingSave) next(true)          // MPG went back to its main page after Save
            else web.loadUrl(NEW_TRIP)
        }
    }

    private fun next(saved: Boolean) {
        if (saved) markDone(trip().optString("key"))
        dbg(if (saved) "trip saved" else "trip skipped", JSONObject().put("trip", idx + 1))
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

    /** The script that fills MPG's Create New Trip form. Never presses Save (Test submit presses it separately). */
    private fun fillJs(t: JSONObject, d: JSONObject) = """
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
  var dateSet='kept', dateBefore=dt?dt.value:'';
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
  return JSON.stringify({ok:true, start:start, end:end, mpgStart:mpgStart, dateSet:dateSet, dateBefore:dateBefore, dateAfter:dt?dt.value:'',
    jq:!!window.jQuery, picker:!!(window.jQuery&&jQuery.fn&&jQuery.fn.datepicker)});
})($t, $d);
""".trimIndent()

    /** Fills the Create New Trip form for the current trip. Never presses Save. */
    private fun fill() {
        if (idx >= trips.length()) return
        val t = trip()
        web.evaluateJavascript(fillJs(t, date)) { res ->
            val r = parse(res)
            dbg("filled", JSONObject().put("trip", idx + 1).put("result", r))
            if (!r.optBoolean("ok")) { show("${head()}\nWaiting for MPG's Create New Trip form…"); return@evaluateJavascript }
            awaitingSave = true; filledUrl = web.url ?: ""
            val start = r.opt("start"); val end = r.opt("end"); val mpgStart = r.opt("mpgStart")
            var msg = "${head()}\nOdometer $start → $end. Check the yellow fields, then tap Save."
            if (t.isNull("startOdo") && mpgStart != null && mpgStart != JSONObject.NULL) msg += "\nStarting odometer is MPG's own; ending = start + ${t.optInt("miles")} mi measured by Companion."
            else if (mpgStart is Number && start is Number && Math.abs(mpgStart.toInt() - start.toInt()) > 1)
                msg += "\nNote: MPG suggested $mpgStart as the starting odometer; Companion used $start from your odometer readings."
            show(msg)
            if (debug) snapshot("after fill")
        }
    }

    private fun parse(res: String?) = try { JSONObject(JSONArray("[$res]").getString(0)) } catch (_: Exception) { JSONObject() }

    // ---------- debug: page snapshots ----------
    /** Records the form's layout, the five trip fields' values, visible messages and buttons. No other values. */
    private fun snapshot(why: String) {
        val js = """
(function(){
  function txt(e){ return (e.innerText||e.textContent||'').replace(/\s+/g,' ').trim().slice(0,160); }
  function vis(el){ var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }
  var out={path:location.pathname,title:document.title,fields:[],trip:{},messages:[],buttons:[],tables:[]};
  document.querySelectorAll('input,select,textarea').forEach(function(el){ out.fields.push({name:el.name||'',id:el.id||'',type:(el.type||el.tagName).toLowerCase(),visible:vis(el),readonly:!!el.readOnly}); });
  ['entryDate','purposeDesc','startOdo','endOdo','personalUse'].forEach(function(id){ var el=document.getElementById(id); if(el) out.trip[id]= el.tagName==='SELECT' ? (el.selectedOptions[0]?el.selectedOptions[0].text:'') : el.value; });
  document.querySelectorAll('.field-validation-error,.validation-summary-errors,.text-danger,.alert,.error,[role=alert],.invalid-feedback,.toast,.modal.show,.ui-dialog').forEach(function(el){ if(vis(el)&&txt(el)) out.messages.push(txt(el)); });
  document.querySelectorAll('button,a,input[type=submit],input[type=button]').forEach(function(b){ if(vis(b)){ var t=txt(b)||b.value||''; if(t) out.buttons.push(t.slice(0,40)); } });
  document.querySelectorAll('table').forEach(function(tb){ out.tables.push({headers:Array.prototype.map.call(tb.querySelectorAll('th'),txt).slice(0,15),rows:tb.querySelectorAll('tbody tr').length}); });
  out.buttons=out.buttons.filter(function(v,i,a){return a.indexOf(v)===i;}).slice(0,40);
  return JSON.stringify(out);
})();
""".trimIndent()
        web.evaluateJavascript(js) { res -> dbg("snapshot: $why", parse(res)) }
    }

    // ---------- debug: test submit ----------
    /** Asks you to type the test trip; then fills it and, after one more confirmation, taps MPG's Save. */
    private fun testDialog() {
        testAsked = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        fun field(label: String, value: String, number: Boolean): EditText {
            box.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8), 0, 0) })
            return EditText(this).apply { setText(value); setSingleLine(); if (number) inputType = InputType.TYPE_CLASS_NUMBER; box.addView(this) }
        }
        val now = java.util.Calendar.getInstance()
        val dateBox = field("Date of trip (MM/DD/YYYY)", "%02d/%02d/%d".format(now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH), now.get(java.util.Calendar.YEAR)), false)
        val purpose = field("Purpose / Description", "TEST from Companion - please delete", false)
        val start = field("Starting odometer", Store.get(this, "odoLast", "").let { try { JSONObject(it).optDouble("mi").toInt().toString() } catch (_: Exception) { "" } }, true)
        val end = field("Ending odometer", "", true)
        box.addView(TextView(this).apply { text = "Personal use"; textSize = 13f; setPadding(0, dp(8), 0, 0) })
        val per = Spinner(this).apply { adapter = ArrayAdapter(this@MpgActivity, android.R.layout.simple_spinner_dropdown_item, listOf("No", "Yes")) }
        box.addView(per)
        box.addView(TextView(this).apply { text = "This saves a real trip in MPG. Use test numbers you'll recognize, and delete the trip in MPG afterwards."; textSize = 12f; setPadding(0, dp(10), 0, dp(4)); setTextColor(Color.parseColor("#8A4B08")) })
        AlertDialog.Builder(this).setTitle("Test trip").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fill in MPG") { _, _ ->
                val s = start.text.toString().toIntOrNull(); val e = end.text.toString().toIntOrNull()
                val dm = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(dateBox.text.toString().trim())
                if (s == null || e == null || e < s || dm == null) { Toast.makeText(this, "Enter a date (MM/DD/YYYY) and odometer numbers, with the ending one not lower than the starting one.", Toast.LENGTH_LONG).show(); web.postDelayed({ testDialog() }, 300); return@setPositiveButton }
                val (mm, dd, yy) = dm.destructured
                val isToday = mm.toInt() == now.get(java.util.Calendar.MONTH) + 1 && dd.toInt() == now.get(java.util.Calendar.DAY_OF_MONTH) && yy.toInt() == now.get(java.util.Calendar.YEAR)
                val t = JSONObject().put("purpose", purpose.text.toString().take(100)).put("startOdo", s).put("endOdo", e).put("miles", e - s)
                    .put("personal", per.selectedItemPosition == 1).put("label", "Test trip")
                val d = JSONObject().put("y", yy.toInt()).put("m", mm.toInt()).put("d", dd.toInt()).put("today", isToday)
                dbg("test trip entered", JSONObject().put("trip", t).put("date", d))
                web.evaluateJavascript(fillJs(t, d)) { res ->
                    val r = parse(res); dbg("filled", JSONObject().put("trip", "test").put("result", r))
                    if (!r.optBoolean("ok")) { show("TEST: MPG's Create New Trip form isn't showing yet. Wait for it, then tap Enter test trip."); return@evaluateJavascript }
                    snapshot("after test fill")
                    show("TEST: the form is filled in (yellow). Look it over.")
                    web.postDelayed({
                        AlertDialog.Builder(this).setTitle("Submit test trip?")
                            .setMessage("Companion will now tap Save in MPG for this test trip:\n\n${purpose.text}\nOdometer $s → $e (${e - s} mi)\nPersonal: ${if (per.selectedItemPosition == 1) "Yes" else "No"}\nDate: ${r.optString("dateAfter")}\n\nIt becomes a real trip in MPG; delete it afterwards.")
                            .setPositiveButton("Submit") { _, _ -> submitTest() }
                            .setNegativeButton("Not yet") { _, _ -> show("TEST: not submitted. Check the form, then tap Save yourself or Enter test trip again.") }
                            .show()
                    }, 600)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> show("TEST: cancelled. Tap Enter test trip to try again, or Close.") }
            .show()
    }

    private fun submitTest() {
        testSubmitted = true
        dbg("test submit: tapping Save")
        show("TEST: tapped Save. Watching what MPG does…")
        web.evaluateJavascript("(function(){ var b=document.getElementById('EditJournalSubmitBtn')||Array.prototype.find.call(document.querySelectorAll('button,input[type=submit]'),function(x){return /^save$/i.test((x.innerText||x.value||'').trim());}); if(!b) return 'no save button'; b.click(); return 'clicked'; })()") { r ->
            dbg("save button", JSONObject().put("result", r.trim('"')))
            // If MPG stays on the same page (for example a message about a field), record what it shows.
            web.postDelayed({ snapshot("3 seconds after Save") }, 3000)
        }
    }

    private fun report(): String {
        val o = JSONObject().put("tool", "companion-mpg-debug").put("v", 1).put("app", BuildConfig.VERSION_NAME)
            .put("android", android.os.Build.VERSION.RELEASE).put("events", log)
        return "COMPANION-MPG-DEBUG\n" + o.toString(1)
    }

    private fun showReport() {
        val text = report()
        val tv = TextView(this).apply { this.text = text; typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val intro = TextView(this).apply {
            this.text = "${log.length()} events recorded. Contains pages, request addresses, messages, and the trip fields only. No password, no hidden security code, no other field values. Copy it and paste it to Claude."
            setPadding(dp(14), dp(10), dp(14), dp(4)); textSize = 13f
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(intro); addView(ScrollView(this@MpgActivity).apply { addView(tv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))) }
        AlertDialog.Builder(this).setTitle("Debug report").setView(box)
            .setPositiveButton("Copy") { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MPG debug report", text)); Toast.makeText(this, "Debug report copied.", Toast.LENGTH_SHORT).show() }
            .setNeutralButton("Share") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share debug report")) }
            .setNegativeButton("Close", null).show()
    }

    companion object {
        const val MPG_HOST = "mobilityapps.publix.org"
        const val NEW_TRIP = "https://mobilityapps.publix.org/mpg/mileageentry/editjournalentry/-1"
    }
}
