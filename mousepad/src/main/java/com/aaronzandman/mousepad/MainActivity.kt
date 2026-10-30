package com.aaronzandman.mousepad

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Mouse Pad Survey — a small, fully local field tool.
 * Walk the store, and for each workstation tap Yes (mouse pad in use) or No (not).
 * The app keeps a running tally per store. Export emails a CSV of the counts.
 * Everything stays on this phone. Nothing is uploaded.
 */
@SuppressLint("SetTextI18n")
class MainActivity : android.app.Activity() {

    private val GREEN = Color.parseColor("#1F6B4F")
    private val RED = Color.parseColor("#B23A3A")
    private val MUTED = Color.parseColor("#5F6B7A")
    private val BG = Color.parseColor("#F2F4F6")
    private val REQ_LOC = 2

    private lateinit var storeIn: EditText
    private lateinit var storeStatus: TextView
    private lateinit var badge: TextView
    private lateinit var tally: TextView
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var recipientIn: EditText
    private var curLat: Double? = null
    private var curLng: Double? = null
    private var activeStore = ""      // store we're currently counting for
    private var dismissedStore = ""   // store for which the "already surveyed" prompt was already answered
    private val DEFAULT_RECIPIENT = "Aaron.zandman@publix.com"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val entriesFile by lazy { File(filesDir, "entries.json") }
    private fun prefs() = getSharedPreferences("mp", Context.MODE_PRIVATE)

    private fun storeLabel(num: String) = "S" + num.filter { it.isDigit() }.padStart(4, '0')

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        root.addView(col)

        col.addView(TextView(this).apply {
            text = "Mouse Pad Survey"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
        })
        col.addView(TextView(this).apply {
            text = "Walk the store. At each workstation, tap Yes if a mouse pad is in use, or No if there isn't one. The app tallies the score per store. Everything stays on this phone."
            textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, dp(10))
        })

        // Store number (auto-detected from GPS; editable)
        col.addView(TextView(this).apply { text = "Store number (auto-detected from your location)"; textSize = 13f; setTextColor(MUTED) })
        storeIn = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "detecting…"
            setText(prefs().getString("store", "")); textSize = 18f
            addTextChangedListener(watcher { prefs().edit().putString("store", it).apply(); syncStore() })
        }
        col.addView(storeIn, mw())
        storeStatus = TextView(this).apply { textSize = 12f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0) }
        col.addView(storeStatus)
        col.addView(btn("📍  Detect store from my location", false, GREEN) { detectStore(true) }, lp(dp(6)))

        // Clear indicator of whether the current store has been surveyed yet
        badge = TextView(this).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE
        }
        col.addView(badge, lp(dp(10)))

        // The two tally buttons
        col.addView(TextView(this).apply {
            text = "Mouse pad in use at this workstation?"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(bigBtn("✓  Yes", GREEN) { count(true) }, half())
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        btnRow.addView(bigBtn("✗  No", RED) { count(false) }, half())
        col.addView(btnRow, lp(0))

        tally = TextView(this).apply { textSize = 16f; setTextColor(GREEN); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, 0) }
        col.addView(tally)
        col.addView(btn("↶  Undo last tap", false, MUTED) { undoLast() }, lp(dp(6)))

        summary = TextView(this).apply { textSize = 14f; setTextColor(MUTED); setPadding(0, dp(14), 0, dp(2)) }
        col.addView(summary)

        col.addView(TextView(this).apply { text = "Email the results to (saved)"; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(10), 0, 0) })
        recipientIn = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefs().getString("recipient", DEFAULT_RECIPIENT)); textSize = 15f; setSingleLine()
            addTextChangedListener(watcher { prefs().edit().putString("recipient", it).apply() })
        }
        col.addView(recipientIn, mw())
        col.addView(btn("📤  Export & email results", true, GREEN) { export() }, lp(dp(6)))
        col.addView(btn("Clear all counts", false, MUTED) { clearAll() }, lp(dp(2)))
        col.addView(btn("✓  Save & close", false, GREEN) { saveAndClose() }, lp(dp(2)))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        col.addView(list)

        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC) } catch (_: Exception) {}
        render()
        // Don't nag with the "already surveyed" prompt on launch for the store we open to.
        activeStore = storeIn.text.toString().trim().filter { it.isDigit() }
        dismissedStore = activeStore
        detectStore(false)
    }

    // ---------- small view helpers ----------
    private fun watcher(on: (String) -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { on(s?.toString()?.trim() ?: "") }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    }
    private fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun half() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun btn(t: String, primary: Boolean, stroke: Int, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; minHeight = dp(50); setOnClickListener { on() }
        setTextColor(if (primary) Color.WHITE else stroke)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (primary) setColor(stroke) else { setColor(Color.WHITE); setStroke(dp(1), stroke) }
        }
    }

    private fun bigBtn(t: String, color: Int, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 22f; minHeight = dp(78); setOnClickListener { on() }
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
    }

    // ---------- counting ----------
    private fun count(inUse: Boolean) {
        val store = storeIn.text.toString().trim().filter { it.isDigit() }
        if (store.isEmpty()) { toast("Enter the store number first."); storeIn.requestFocus(); return }
        val e = JSONObject().put("id", System.currentTimeMillis()).put("store", store)
            .put("use", inUse).put("t", System.currentTimeMillis())
        (if (curLat != null && curLng != null) Pair(curLat!!, curLng!!) else lastLocation())?.let { e.put("lat", it.first).put("lng", it.second) }
        val a = load(); a.put(e); save(a)
        feedback(inUse)
        toast(if (inUse) "Yes — in use ✓" else "No — none ✗")
        render()
    }

    // ---------- audio + haptic feedback ----------
    private var tone: ToneGenerator? = null
    private fun toneGen(): ToneGenerator? {
        if (tone == null) try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85) } catch (_: Exception) {}
        return tone
    }
    override fun onDestroy() { super.onDestroy(); try { tone?.release() } catch (_: Exception) {}; tone = null }

    /** Distinct cue per answer: Yes = high ack beep + double buzz; No = low error tone + one long buzz. */
    private fun feedback(inUse: Boolean) {
        try {
            if (inUse) toneGen()?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            else toneGen()?.startTone(ToneGenerator.TONE_SUP_ERROR, 350)
        } catch (_: Exception) {}
        vibrate(inUse)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(inUse: Boolean) {
        val pattern = if (inUse) longArrayOf(0, 50, 90, 50) else longArrayOf(0, 400)
        try {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else v.vibrate(pattern, -1)
        } catch (_: Exception) {}
    }

    private fun undoLast() {
        val store = storeIn.text.toString().trim().filter { it.isDigit() }
        val a = load()
        // Remove the most recent tap for the current store (or the most recent overall if the store is blank).
        var idx = -1; var newest = -1L
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            if (store.isNotEmpty() && e.optString("store") != store) continue
            if (e.optLong("t") > newest) { newest = e.optLong("t"); idx = i }
        }
        if (idx < 0) { toast("Nothing to undo."); return }
        val out = JSONArray(); for (i in 0 until a.length()) if (i != idx) out.put(a.getJSONObject(i))
        save(out); toast("Removed last tap."); render()
    }

    /** Counts are already written to disk on every tap; this just confirms and exits the app. */
    private fun saveAndClose() {
        toast("Survey saved.")
        try { if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish() } catch (_: Exception) { finish() }
    }

    private fun clearAll() {
        if (load().length() == 0) { toast("Nothing to clear."); return }
        AlertDialog.Builder(this).setTitle("Clear all counts?")
            .setMessage("This deletes every tally on this phone. Export first if you haven't.")
            .setPositiveButton("Delete all") { _, _ -> entriesFile.delete(); render(); toast("Cleared.") }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- storage ----------
    private fun load(): JSONArray = try { JSONArray(entriesFile.readText()) } catch (_: Exception) { JSONArray() }
    private fun save(a: JSONArray) { try { entriesFile.writeText(a.toString()) } catch (_: Exception) {} }

    /** Per-store counts, kept in store-number order. */
    private data class Tally(var yes: Int = 0, var no: Int = 0) { val total get() = yes + no }
    private fun counts(): LinkedHashMap<String, Tally> {
        val a = load(); val m = HashMap<String, Tally>()
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i); val s = e.optString("store"); if (s.isEmpty()) continue
            val t = m.getOrPut(s) { Tally() }; if (e.optBoolean("use")) t.yes++ else t.no++
        }
        val out = LinkedHashMap<String, Tally>()
        m.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { out[it] = m[it]!! }
        return out
    }

    // ---------- display ----------
    private fun render() { renderTally(); renderList() }

    /** Called whenever the store value changes (typed or auto-detected). Alerts once if already surveyed. */
    private fun syncStore() {
        val s = storeIn.text.toString().trim().filter { it.isDigit() }
        renderTally()
        if (s == activeStore) return
        activeStore = s
        if (s.isNotEmpty() && s != dismissedStore && ((counts()[s]?.total ?: 0) > 0)) showAlready(s)
    }

    /** A store we just landed on already has counts — ask whether to wipe it or keep adding. */
    private fun showAlready(store: String) {
        dismissedStore = store
        val t = counts()[store] ?: return
        AlertDialog.Builder(this)
            .setTitle("Store $store already surveyed")
            .setMessage("This store already has ${t.total} counted (Yes ${t.yes}, No ${t.no}).\n\nDelete the previous survey and start over, or keep adding to it?")
            .setPositiveButton("Delete & start over") { _, _ ->
                val a = load(); val out = JSONArray()
                for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optString("store") != store) out.put(e) }
                save(out); render(); toast("Cleared store $store — starting fresh.")
            }
            .setNegativeButton("Keep adding", null)
            .setCancelable(false).show()
    }

    private fun renderTally() {
        val store = storeIn.text.toString().trim().filter { it.isDigit() }
        if (store.isEmpty()) {
            badge.visibility = View.GONE
            tally.text = "Enter or detect a store to start counting."
            return
        }
        val t = counts()[store]
        val total = t?.total ?: 0
        badge.visibility = View.VISIBLE
        if (total == 0) {
            badge.text = "🆕  New store — not surveyed yet"
            badge.background = pill(GREEN)
        } else {
            badge.text = "✓  Already surveyed — $total counted here"
            badge.background = pill(Color.parseColor("#B5791F"))
        }
        tally.text = "Store ${store} — Yes ${t?.yes ?: 0} · No ${t?.no ?: 0} · $total counted"
    }

    private fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(10).toFloat(); setColor(color)
    }

    private fun renderList() {
        val c = counts()
        list.removeAllViews()
        var yes = 0; var no = 0
        c.forEach { (store, t) ->
            yes += t.yes; no += t.no
            list.addView(storeRow(store, t))
        }
        summary.text = if (c.isEmpty()) "No counts yet."
            else "${c.size} store${if (c.size == 1) "" else "s"} · Yes $yes · No $no · ${yes + no} total"
    }

    private fun storeRow(store: String, t: Tally): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply { text = storeLabel(store) + "  (Store $store)"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        mid.addView(TextView(this).apply {
            text = "Yes ${t.yes}  ·  No ${t.no}  ·  ${t.total} counted"; textSize = 13f; setTextColor(MUTED)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "✕"; isAllCaps = false; setTextColor(MUTED); background = null; minWidth = dp(40)
            setOnClickListener { deleteStore(store) }
        })
        return LinearLayout(this).apply { setPadding(0, 0, 0, dp(8)); addView(row, mw()) }
    }

    private fun deleteStore(store: String) {
        AlertDialog.Builder(this).setTitle("Clear ${storeLabel(store)}?")
            .setMessage("Removes all counts for store $store.")
            .setPositiveButton("Clear") { _, _ ->
                val a = load(); val out = JSONArray()
                for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optString("store") != store) out.put(e) }
                save(out); render()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- export ----------
    private fun export() {
        val c = counts()
        if (c.isEmpty()) { toast("No counts to export yet."); return }
        var yes = 0; var no = 0; c.values.forEach { yes += it.yes; no += it.no }

        val csv = StringBuilder("Store,StoreLabel,InUse_Yes,NotUsed_No,Total\r\n")
        c.forEach { (store, t) ->
            csv.append(csvCell(store)).append(",").append(csvCell(storeLabel(store))).append(",")
                .append(t.yes).append(",").append(t.no).append(",").append(t.total).append("\r\n")
        }
        csv.append("TOTAL,,").append(yes).append(",").append(no).append(",").append(yes + no).append("\r\n")

        val dir = File(cacheDir, "exports").apply { mkdirs() }; dir.listFiles()?.forEach { it.delete() }
        val csvFile = File(dir, "MousePadSurvey_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.csv")
        try { csvFile.writeText(csv.toString()) } catch (ex: Exception) { toast("Couldn't write the file: ${ex.message}"); return }

        val labels = c.keys.map { storeLabel(it) }
        val subject = "Mouse survey " + labels.joinToString(", ")
        val body = StringBuilder("Mouse pad survey results.\n\n")
        c.forEach { (store, t) -> body.append("${storeLabel(store)} (Store $store): in use ${t.yes}, none ${t.no}, ${t.total} counted.\n") }
        body.append("\nTotal across ${c.size} store(s): in use $yes, none $no, ${yes + no} counted.\nResults attached as a CSV.")

        val backup = saveBackup(csvFile)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", csvFile)
        val recipient = (prefs().getString("recipient", DEFAULT_RECIPIENT) ?: DEFAULT_RECIPIENT).trim()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
            if (recipient.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject.trim())
            putExtra(Intent.EXTRA_TEXT, body.toString()); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (backup != null) toast("Backup saved: $backup")
        try { startActivity(Intent.createChooser(send, "Email the results")) }
        catch (_: Exception) { toast("Saved a backup, but no email app to attach it. File: $backup") }
    }

    /** Keep a local copy of the exported CSV (public Downloads where possible), so you have a backup. */
    private fun saveBackup(file: File): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MousePadSurvey")
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (u != null) { contentResolver.openOutputStream(u)?.use { it.write(file.readBytes()) }; return "Download/MousePadSurvey/${file.name}" }
            }
            val dir = File(getExternalFilesDir(null), "backups").apply { mkdirs() }
            val dst = File(dir, file.name); file.copyTo(dst, overwrite = true); dst.absolutePath
        } catch (_: Exception) { null }
    }

    private fun csvCell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    // ---------- store auto-detect from location ----------
    private var locListener: LocationListener? = null

    override fun onResume() { super.onResume(); startLocation() }
    override fun onPause() { super.onPause(); stopLocation() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocation()
    }

    /** The Detect button just ensures permission; location updates run automatically while the app is open. */
    private fun detectStore(userTap: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (userTap) try { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC) } catch (_: Exception) {}
            return
        }
        startLocation()
    }

    /** Continuously update the detected store from location while the app is in the foreground (no tap needed). */
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { applyNearest(it.latitude, it.longitude) } } catch (_: Exception) {}
        stopLocation()
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { applyNearest(l.latitude, l.longitude) }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        locListener = listener
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, mainLooper)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener, mainLooper)
            if (storeStatus.text.isNullOrEmpty()) storeStatus.text = "Getting your location…"
        } catch (_: Exception) {}
    }

    private fun stopLocation() {
        val l = locListener ?: return; locListener = null
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(l) } catch (_: Exception) {}
    }

    private fun applyNearest(lat: Double, lng: Double) {
        curLat = lat; curLng = lng
        val n = Stores.nearest(this, lat, lng) ?: run { if (storeStatus.text.isNullOrEmpty()) storeStatus.text = "Store list unavailable — type the store number."; return }
        val (s, m) = n
        if (storeIn.text.toString().trim() != s.num) storeIn.setText(s.num)
        storeStatus.text = "📍 Store ${s.num}${if (s.city.isNotEmpty()) " · " + s.city else ""} · ${m.toInt()} m away"
    }

    private fun lastLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val l = lm.getProviders(true).mapNotNull { @Suppress("MissingPermission") lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            l?.let { Pair(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Publix store locations (bundled from Route2's list at build time) — to detect which store you're at. */
object Stores {
    data class S(val num: String, val lat: Double, val lng: Double, val city: String)
    @Volatile private var list: List<S>? = null
    fun all(c: Context): List<S> = list ?: synchronized(this) {
        list ?: (try {
            val a = JSONArray(c.assets.open("stores.json").bufferedReader().readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val lat = o.optString("Latitude").toDoubleOrNull(); val lng = o.optString("Longitude").toDoubleOrNull()
                if (lat == null || lng == null) null else S(o.optString("Store #"), lat, lng, o.optString("City"))
            }
        } catch (e: Exception) { emptyList() }).also { list = it }
    }
    private fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0; val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
        val h = Math.pow(Math.sin(dLat / 2), 2.0) + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.pow(Math.sin(dLng / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(h))
    }
    fun nearest(c: Context, lat: Double, lng: Double): Pair<S, Double>? =
        all(c).map { it to meters(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }
}
