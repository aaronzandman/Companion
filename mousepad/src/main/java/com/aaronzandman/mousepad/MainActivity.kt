package com.aaronzandman.mousepad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Mouse Pad Survey — a small, fully local field tool.
 * At each store: take a photo of the workstation, mark whether a mouse pad is present and in use.
 * Everything is stored on the device. Export stamps each photo with the store number and bundles
 * them plus a CSV into a ZIP you can email. Nothing is uploaded.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private val GREEN = Color.parseColor("#1F6B4F")
    private val MUTED = Color.parseColor("#5F6B7A")
    private val BG = Color.parseColor("#F2F4F6")
    private val REQ_PHOTO = 1
    private val REQ_LOC = 2

    private lateinit var storeIn: EditText
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var pendingPhoto: File? = null

    /** Label Sxxxxyyyy: S + 4-digit store + 4-digit workstation id (padded with leading zeros). */
    private fun label(store: String, ws: String): String {
        val s = store.filter { it.isDigit() }.padStart(4, '0')
        val w = ws.filter { it.isDigit() }.padStart(4, '0')
        return "S$s$w"
    }
    private fun label(e: JSONObject) = label(e.optString("store"), e.optString("ws"))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val photosDir by lazy { File(filesDir, "photos").apply { mkdirs() } }
    private val entriesFile by lazy { File(filesDir, "entries.json") }
    private fun prefs() = getSharedPreferences("mp", Context.MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        root.addView(col)

        col.addView(TextView(this).apply {
            text = "Mouse Pad Survey"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
        })
        col.addView(TextView(this).apply {
            text = "Photograph each workstation, mark if a mouse pad is present and in use, then export + email. Everything stays on this phone."
            textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, dp(10))
        })

        // Store number
        col.addView(TextView(this).apply { text = "Store number (stamped on every photo)"; textSize = 13f; setTextColor(MUTED) })
        storeIn = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "e.g. 1566"
            setText(prefs().getString("store", "")); textSize = 18f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { prefs().edit().putString("store", s?.toString()?.trim() ?: "").apply() }
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            })
        }
        col.addView(storeIn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(btn("📷  Take workstation photo", true) { takePhoto() }, lp(dp(10)))
        summary = TextView(this).apply { textSize = 14f; setTextColor(GREEN); setPadding(0, dp(12), 0, dp(4)); setTypeface(typeface, Typeface.BOLD) }
        col.addView(summary)
        col.addView(btn("📤  Export & email (ZIP)", false) { export() }, lp(dp(4)))
        col.addView(btn("Clear all entries", false) { clearAll() }, lp(dp(2)))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        col.addView(list)

        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC) } catch (_: Exception) {}
        render()
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; minHeight = dp(50); setOnClickListener { on() }
        setTextColor(if (primary) Color.WHITE else GREEN)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (primary) setColor(GREEN) else { setColor(Color.WHITE); setStroke(dp(1), GREEN) }
        }
    }

    // ---------- camera ----------
    private fun takePhoto() {
        val store = storeIn.text.toString().trim()
        if (store.isEmpty()) { toast("Enter the store number first."); storeIn.requestFocus(); return }
        val f = File(photosDir, "p${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (i.resolveActivity(packageManager) == null) { toast("No camera app found."); return }
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_PHOTO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        val f = pendingPhoto; pendingPhoto = null
        if (resultCode != RESULT_OK || f == null || !f.exists() || f.length() == 0L) { f?.delete(); return }
        askTags(f)
    }

    /** After a photo, mark mouse pad present / in use, add a note, then save. */
    private fun askTags(photo: File) {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), 0) }
        v.addView(ImageView(this).apply {
            setImageBitmap(thumb(photo, dp(220)))
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        v.addView(TextView(this).apply { text = "Workstation ID (4-digit machine ID, e.g. 0099)"; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0) })
        val wsIn = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "e.g. 0099"; textSize = 18f; setText(prefs().getString("ws", "")) }
        v.addView(wsIn)
        val lbl = TextView(this).apply { textSize = 14f; setTextColor(GREEN); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) }
        val upd = { lbl.text = "Label: " + label(storeIn.text.toString(), wsIn.text.toString()) }; upd()
        wsIn.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { upd() }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        v.addView(lbl)

        val pad = switchRow("Mouse pad present", true)
        val use = switchRow("Mouse pad in use", true)
        v.addView(pad.first); v.addView(use.first)
        val note = EditText(this).apply { hint = "Note (optional)"; textSize = 15f }
        v.addView(note, lp(dp(6)))

        AlertDialog.Builder(this)
            .setTitle("Store ${storeIn.text} — record it")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                val ws = wsIn.text.toString().trim()
                if (ws.filter { it.isDigit() }.isEmpty()) { toast("Enter the workstation ID."); photo.delete(); return@setPositiveButton }
                prefs().edit().putString("ws", ws).apply()
                saveEntry(photo, ws, pad.second.isChecked, use.second.isChecked, note.text.toString().trim())
            }
            .setNegativeButton("Discard") { _, _ -> photo.delete() }
            .setCancelable(false)
            .show()
    }

    private fun switchRow(label: String, on: Boolean): Pair<LinearLayout, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
        return Pair(row, sw)
    }

    // ---------- storage ----------
    private fun load(): JSONArray = try { JSONArray(entriesFile.readText()) } catch (_: Exception) { JSONArray() }
    private fun save(a: JSONArray) { try { entriesFile.writeText(a.toString()) } catch (_: Exception) {} }

    private fun saveEntry(photo: File, ws: String, pad: Boolean, use: Boolean, note: String) {
        val store = storeIn.text.toString().trim()
        val e = JSONObject().put("id", System.currentTimeMillis()).put("store", store)
            .put("ws", ws).put("pad", pad).put("use", use).put("note", note).put("t", System.currentTimeMillis()).put("photo", photo.name)
        lastLocation()?.let { e.put("lat", it.first).put("lng", it.second) }
        val a = load(); a.put(e); save(a)
        toast("Saved — " + label(store, ws))
        render()
    }

    private fun deleteEntry(id: Long) {
        val a = load(); val out = JSONArray()
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optLong("id") == id) File(photosDir, e.optString("photo")).delete() else out.put(e) }
        save(out); render()
    }

    private fun clearAll() {
        if (load().length() == 0) { toast("Nothing to clear."); return }
        AlertDialog.Builder(this).setTitle("Clear all entries?")
            .setMessage("This deletes every photo and record on this phone. Export first if you haven't.")
            .setPositiveButton("Delete all") { _, _ -> photosDir.listFiles()?.forEach { it.delete() }; entriesFile.delete(); render(); toast("Cleared.") }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- list ----------
    private fun render() {
        val a = load()
        var pads = 0; var inUse = 0; val stores = HashSet<String>()
        list.removeAllViews()
        for (i in a.length() - 1 downTo 0) {
            val e = a.getJSONObject(i)
            if (e.optBoolean("pad")) pads++; if (e.optBoolean("use")) inUse++
            stores.add(e.optString("store"))
            list.addView(entryRow(e))
        }
        summary.text = if (a.length() == 0) "No photos yet — take one at the first station."
            else "${a.length()} photo${if (a.length() == 1) "" else "s"} · ${stores.size} store${if (stores.size == 1) "" else "s"} · pad present ${pads} · in use ${inUse}"
    }

    private fun entryRow(e: JSONObject): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
        }
        (row.layoutParams as? ViewGroup.MarginLayoutParams)
        row.addView(ImageView(this).apply {
            val t = thumb(File(photosDir, e.optString("photo")), dp(64)); if (t != null) setImageBitmap(t)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        mid.addView(TextView(this).apply { text = label(e); textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        mid.addView(TextView(this).apply {
            text = "Pad: ${if (e.optBoolean("pad")) "Present" else "None"}  ·  In use: ${if (e.optBoolean("use")) "Yes" else "No"}"
            textSize = 13f; setTextColor(if (e.optBoolean("pad") && e.optBoolean("use")) GREEN else MUTED)
        })
        val when_ = SimpleDateFormat("M/d h:mm a", Locale.US).format(Date(e.optLong("t")))
        mid.addView(TextView(this).apply { text = when_ + (if (e.optString("note").isNotEmpty()) " · " + e.optString("note") else ""); textSize = 12f; setTextColor(MUTED) })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "✕"; isAllCaps = false; setTextColor(MUTED); background = null; minWidth = dp(40)
            setOnClickListener { deleteEntry(e.optLong("id")) }
        })
        val wrap = LinearLayout(this).apply { setPadding(0, 0, 0, dp(8)); addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        return wrap
    }

    // ---------- images ----------
    private fun thumb(f: File, max: Int): Bitmap? {
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            rotate(f, bmp)
        } catch (_: Exception) { null }
    }

    private fun rotate(f: File, bmp: Bitmap): Bitmap {
        return try {
            val o = ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
            if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        } catch (_: Exception) { bmp }
    }

    /** Full photo, downscaled and stamped with store/pad/use/date (+GPS), as JPEG bytes. */
    private fun stamp(f: File, e: JSONObject): ByteArray {
        val max = 1600
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return ByteArray(0)
        bmp = rotate(f, bmp)
        if (bmp.config == null || !bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp); val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val fs = (w * 0.035f).coerceAtLeast(26f)
        val pad = w * 0.02f; val lineH = fs * 1.3f
        val lines = listOf(
            "${label(e)}    Pad: ${if (e.optBoolean("pad")) "YES" else "NO"}    In use: ${if (e.optBoolean("use")) "YES" else "NO"}",
            SimpleDateFormat("M/d/yyyy h:mm a", Locale.US).format(Date(e.optLong("t"))) +
                (if (e.has("lat")) "   ${"%.5f".format(e.optDouble("lat"))}, ${"%.5f".format(e.optDouble("lng"))}" else "")
        ).filter { it.isNotBlank() } + (if (e.optString("note").isNotEmpty()) listOf(e.optString("note")) else emptyList())
        val bh = lines.size * lineH + pad * 2
        c.drawRect(0f, h - bh, w, h, Paint().apply { color = Color.argb(160, 0, 0, 0) })
        val tp = Paint().apply { color = Color.WHITE; textSize = fs; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        lines.forEachIndexed { i, l -> c.drawText(l, pad, h - bh + pad + fs + i * lineH, tp) }
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out); return out.toByteArray()
    }

    // ---------- export ----------
    private fun export() {
        val a = load()
        if (a.length() == 0) { toast("No entries to export yet."); return }
        val dir = File(cacheDir, "exports").apply { mkdirs() }; dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "MousePadSurvey_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
        val csv = StringBuilder("Label,Store,Workstation,MousePad,InUse,Note,When,Lat,Lng,File\r\n")
        val used = HashMap<String, Int>()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i); val src = File(photosDir, e.optString("photo")); if (!src.exists()) continue
                    val bytes = stamp(src, e); if (bytes.isEmpty()) continue
                    val base = label(e); val n = (used[base] ?: 0) + 1; used[base] = n
                    val fname = if (n == 1) "$base.jpg" else "${base}_$n.jpg"
                    zos.putNextEntry(ZipEntry(fname)); zos.write(bytes); zos.closeEntry()
                    csv.append(csvCell(base)).append(",").append(csvCell(e.optString("store"))).append(",").append(csvCell(e.optString("ws"))).append(",").append(if (e.optBoolean("pad")) "Yes" else "No").append(",")
                        .append(if (e.optBoolean("use")) "Yes" else "No").append(",").append(csvCell(e.optString("note"))).append(",")
                        .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(e.optLong("t")))).append(",")
                        .append(if (e.has("lat")) e.optDouble("lat").toString() else "").append(",")
                        .append(if (e.has("lng")) e.optDouble("lng").toString() else "").append(",").append(csvCell(fname)).append("\r\n")
                }
                zos.putNextEntry(ZipEntry("survey.csv")); zos.write(csv.toString().toByteArray(Charsets.UTF_8)); zos.closeEntry()
            }
        } catch (ex: Exception) { toast("Couldn't build the ZIP: ${ex.message}"); return }

        var pads = 0; var inUse = 0; val stores = HashSet<String>()
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (e.optBoolean("pad")) pads++; if (e.optBoolean("use")) inUse++; stores.add(e.optString("store")) }
        val body = "Mouse pad survey.\n${a.length()} photos across ${stores.size} stores.\nMouse pad present: $pads.\nMouse pad in use: $inUse.\n\nPhotos are stamped with the store number; the ZIP also includes survey.csv."
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mouse pad survey — ${a.length()} photos, ${stores.size} stores")
            putExtra(Intent.EXTRA_TEXT, body); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "Email the survey")) } catch (_: Exception) { toast("No app to share the ZIP.") }
    }

    private fun csvCell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    // ---------- location (best-effort) ----------
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
