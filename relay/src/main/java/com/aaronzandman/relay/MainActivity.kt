package com.aaronzandman.relay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Next Stop — receives a route from the web planner ("Send to Next Stop app") and shows a punch list.
 * Tap a stop and it starts turn-by-turn navigation to it (Google Maps), which shows on the Ford SYNC
 * screen when the phone is connected to Android Auto. Everything stays on this phone.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private val GREEN = Color.parseColor("#1F6B4F")
    private val BLUE = Color.parseColor("#1E4E8C")
    private val MUTED = Color.parseColor("#5F6B7A")
    private val BG = Color.parseColor("#F2F4F6")
    private val DONEBG = Color.parseColor("#E7ECEF")

    private lateinit var listBox: LinearLayout
    private lateinit var nextBox: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var updateBar: TextView
    private val io = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("nextstop", Context.MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        root.addView(col)

        col.addView(TextView(this).apply { text = "Next Stop"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN) })
        subtitle = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, dp(10)) }
        col.addView(subtitle)

        // Silent update bar — hidden unless a newer build has been downloaded.
        updateBar = TextView(this).apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setBackgroundColor(BLUE); setPadding(dp(12), dp(10), dp(12), dp(10)); gravity = Gravity.CENTER
            visibility = View.GONE; setOnClickListener { install() }
        }
        col.addView(updateBar, lp(dp(2)))

        nextBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(nextBox)

        col.addView(TextView(this).apply { text = "All stops"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(BLUE); setPadding(0, dp(14), 0, dp(4)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(btn("↺  Reset day", false, MUTED) { clearDone() }, half())
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow.addView(btn("🔧  Diagnostics", false, MUTED) { showDiagnostics() }, half())
        col.addView(btnRow, lp(dp(14)))

        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); render(); showUpdateBar(); silentUpdateCheck() }
    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }

    // ---------- auto update (silent unless one is ready) ----------
    private fun showUpdateBar() {
        val tag = Updater.ready(this)
        if (tag != null) { updateBar.text = "⬆ Update available — tap to install"; updateBar.visibility = View.VISIBLE }
        else updateBar.visibility = View.GONE
    }
    private fun silentUpdateCheck() {
        io.execute {
            val r = try { Updater.check(this, 6 * 60 * 60 * 1000L) } catch (_: Exception) { null }   // at most every 6 h
            if (r?.optBoolean("ready") == true) runOnUiThread { showUpdateBar() }                      // only surfaces when ready
        }
    }
    private fun install() {
        if (Updater.ready(this) == null) { toast("No update is waiting."); return }
        if (!Updater.canInstall(this)) {
            toast("Allow Next Stop to install updates, then tap the bar again.")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            return
        }
        try { startActivity(Updater.installIntent(this)) } catch (_: Exception) { toast("Couldn't open the installer.") }
    }

    // ---------- receiving the route ----------
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == "nextstop") {
            val d = data.getQueryParameter("d")
            if (d != null) {
                try {
                    val json = String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                    val o = JSONObject(json)
                    val stops = o.optJSONArray("stops") ?: JSONArray()
                    if (stops.length() > 0) {
                        prefs().edit().putString("route", json).putString("done", "[]").apply()  // a new route starts a fresh day
                        toast("Loaded ${stops.length()} stops from the planner.")
                    }
                } catch (e: Exception) { toast("Couldn't read that route. Re-send it from the planner.") }
            }
        }
        render()
    }

    private fun route(): JSONArray = try { JSONObject(prefs().getString("route", "{}") ?: "{}").optJSONArray("stops") ?: JSONArray() } catch (_: Exception) { JSONArray() }
    private fun doneSet(): MutableSet<String> {
        val out = HashSet<String>()
        try { val a = JSONArray(prefs().getString("done", "[]")); for (i in 0 until a.length()) out.add(a.getString(i)) } catch (_: Exception) {}
        return out
    }
    private fun setDone(num: String, done: Boolean) {
        val s = doneSet(); if (done) s.add(num) else s.remove(num)
        prefs().edit().putString("done", JSONArray(s.toList()).toString()).apply(); render()
    }
    private fun clearDone() { prefs().edit().putString("done", "[]").apply(); render(); toast("Day reset.") }

    // ---------- navigation ----------
    private fun startNav(lat: Double?, lng: Double?, label: String) {
        val uris = ArrayList<Uri>()
        if (lat != null && lng != null) {
            uris.add(Uri.parse("google.navigation:q=$lat,$lng&mode=d"))
            uris.add(Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
            uris.add(Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving&dir_action=navigate"))
        } else {
            uris.add(Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(label)}&travelmode=driving&dir_action=navigate"))
        }
        for (u in uris) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        toast("No navigation app found to open directions.")
    }

    // ---------- display ----------
    private fun render() {
        val stops = route(); val done = doneSet()
        nextBox.removeAllViews(); listBox.removeAllViews()

        if (stops.length() == 0) {
            subtitle.text = "No route yet."
            nextBox.addView(TextView(this).apply {
                text = "Open the Service Route Planner on this phone, build your route, and tap “📲 Send to Next Stop app.” Your stops will appear here, and you tap one to start driving to it on the SYNC screen (with Android Auto connected)."
                textSize = 14f; setTextColor(MUTED); setPadding(dp(2), dp(6), dp(2), 0)
            })
            return
        }

        val total = stops.length(); val remaining = (0 until total).count { !done.contains(stops.getJSONObject(it).optString("n")) }
        subtitle.text = "$remaining of $total stops left · tap a stop to navigate"

        // The next (first not-done) stop as a big card
        var nextIdx = -1
        for (i in 0 until total) { if (!done.contains(stops.getJSONObject(i).optString("n"))) { nextIdx = i; break } }
        if (nextIdx >= 0) {
            val s = stops.getJSONObject(nextIdx)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(2), GREEN) }
            }
            card.addView(TextView(this).apply { text = "NEXT STOP  ·  #${nextIdx + 1} of $total"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN) })
            card.addView(TextView(this).apply { text = s.optString("name"); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, 0) })
            card.addView(TextView(this).apply { text = s.optString("addr"); textSize = 14f; setTextColor(MUTED) })
            card.addView(bigBtn("▶  Navigate to this stop") { startNav(num(s, "lat"), num(s, "lng"), s.optString("name")) }, lp(dp(10)))
            card.addView(btn("✓  Mark done & go to next", false, GREEN) { setDone(s.optString("n"), true) }, lp(dp(6)))
            nextBox.addView(card, lp(dp(6)))
        } else {
            nextBox.addView(TextView(this).apply {
                text = "✓ All stops done for today. Reset the day below to run the list again."
                textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN); setPadding(dp(2), dp(8), dp(2), dp(4))
            })
        }

        // Full punch list
        for (i in 0 until total) {
            val s = stops.getJSONObject(i); val n = s.optString("n"); val isDone = done.contains(n)
            val rowWrap = LinearLayout(this).apply { setPadding(0, 0, 0, dp(8)) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(if (isDone) DONEBG else Color.WHITE); cornerRadius = dp(10).toFloat() }
            }
            val cb = CheckBox(this).apply { isChecked = isDone; setOnClickListener { setDone(n, isChecked) } }
            row.addView(cb)
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(TextView(this).apply {
                text = "${i + 1}. ${s.optString("name")}"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                if (isDone) { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG; setTextColor(MUTED) }
            })
            mid.addView(TextView(this).apply { text = s.optString("addr"); textSize = 12.5f; setTextColor(MUTED) })
            row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "▶"; isAllCaps = false; textSize = 18f; setTextColor(GREEN); background = null; minWidth = dp(44)
                setOnClickListener { startNav(num(s, "lat"), num(s, "lng"), s.optString("name")) }
            })
            rowWrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            listBox.addView(rowWrap)
        }
    }

    private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.optDouble(k).let { if (it.isNaN()) null else it }

    // ---------- diagnostics ----------
    private fun navAvailable(): Boolean = try {
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=37.5,-77.4")).resolveActivity(packageManager) != null ||
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:37.5,-77.4?q=37.5,-77.4")).resolveActivity(packageManager) != null
    } catch (_: Exception) { false }

    private fun report(): String {
        val stops = route(); val done = doneSet()
        val lastRecv = try { JSONObject(prefs().getString("route", "{}") ?: "{}").optLong("made", 0L) } catch (_: Exception) { 0L }
        val latest = prefs().getString("updLatest", ""); val ready = Updater.ready(this)
        val sb = StringBuilder()
        sb.append("Next Stop ").append(BuildConfig.VERSION_NAME).append("\n")
        sb.append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append("\n\n")
        sb.append("Route loaded: ").append(if (stops.length() > 0) "${stops.length()} stops (${done.size} done)" else "none").append("\n")
        if (lastRecv > 0) sb.append("Received: ").append(java.text.SimpleDateFormat("EEE M/d h:mm a", java.util.Locale.US).format(java.util.Date(lastRecv))).append("\n")
        sb.append("Navigation app: ").append(if (navAvailable()) "found ✓" else "NOT found ✗").append("\n")
        sb.append("Can install updates: ").append(if (Updater.canInstall(this)) "yes" else "no (allow in settings)").append("\n")
        sb.append("Update: ").append(when { ready != null -> "ready to install ($ready)"; latest.isNullOrBlank() -> "not checked yet"; else -> "up to date" }).append("\n")
        return sb.toString()
    }

    private fun testNavigation() {
        val stops = route(); val done = doneSet(); var target: JSONObject? = null
        for (i in 0 until stops.length()) { val s = stops.getJSONObject(i); if (!done.contains(s.optString("n"))) { target = s; break } }
        if (target == null && stops.length() > 0) target = stops.getJSONObject(0)
        if (target != null) { toast("Launching navigation to the next stop…"); startNav(num(target, "lat"), num(target, "lng"), target.optString("name")) }
        else {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Publix")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); toast("Opened your maps app (no route loaded).") }
            catch (_: Exception) { toast("No maps/navigation app found.") }
        }
    }

    private fun showDiagnostics() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
        val txt = TextView(this).apply { text = report(); textSize = 13.5f; setTextColor(Color.parseColor("#263238")); typeface = Typeface.MONOSPACE }
        box.addView(txt)
        box.addView(btn("📋  Copy report", false, MUTED) {
            try { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NextStop", txt.text)); toast("Copied.") } catch (_: Exception) {}
        }, lp(dp(10)))
        val d = AlertDialog.Builder(this).setTitle("Diagnostics")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Test navigation") { _, _ -> testNavigation() }
            .setNegativeButton("Check updates", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                toast("Checking for updates…")
                io.execute {
                    val r = try { Updater.check(this, 0L) } catch (_: Exception) { JSONObject().put("error", "x") }
                    runOnUiThread {
                        showUpdateBar(); txt.text = report()
                        toast(when {
                            r.optBoolean("ready") -> "Update downloaded — tap the blue bar to install"
                            r.optBoolean("upToDate") -> "Up to date (${BuildConfig.VERSION_NAME})"
                            r.has("error") -> "Couldn't check right now"
                            else -> "Checked"
                        })
                    }
                }
            }
        }
        d.show()
    }

    // ---------- view helpers ----------
    private fun half() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun btn(t: String, primary: Boolean, stroke: Int, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; minHeight = dp(46); setOnClickListener { on() }
        setTextColor(if (primary) Color.WHITE else stroke)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (primary) setColor(stroke) else { setColor(Color.WHITE); setStroke(dp(1), stroke) }
        }
    }
    private fun bigBtn(t: String, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 19f; minHeight = dp(64); setOnClickListener { on() }
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(GREEN) }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
