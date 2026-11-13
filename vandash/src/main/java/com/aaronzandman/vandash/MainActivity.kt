package com.aaronzandman.vandash

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** The phone side: permission, Android Auto setup steps, nearby stores, and a log. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var storesBox: LinearLayout
    private lateinit var lastText: TextView
    private lateinit var logText: TextView

    private val dp get() = resources.displayMetrics.density
    private val autoPkg = "com.google.android.projection.gearhead"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("Van Dash", 24f, bold = true))
        root.addView(text("Shows a map of the nearest Publix stores in Android Auto. Tap a store and directions start " +
            "in your Android Auto navigation app (Google Maps or Waze).", 14f, gray = true))

        root.addView(header("1. Location"))
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusBox)

        root.addView(header("2. Let Android Auto show Van Dash (one time)"))
        root.addView(text(
            "Android Auto hides apps that didn't come from the Play Store until you allow them:\n\n" +
            "a. Open Android Auto settings (button below).\n" +
            "b. Scroll to the bottom and tap Version 10 times, then tap OK.\n" +
            "c. Tap ⋮ (top right) → Developer settings → turn on Unknown sources.\n" +
            "d. Go back → Customize launcher → make sure Van Dash is checked.\n" +
            "e. Reconnect to the van. Van Dash appears in the Android Auto app list.", 14f))
        root.addView(button("Open Android Auto settings") { openAndroidAuto() })

        root.addView(header("Status"))
        lastText = text("", 14f)
        root.addView(lastText)

        root.addView(header("Nearby stores"))
        root.addView(text("Tap one to open it in Google Maps on the phone.", 13f, gray = true))
        storesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(storesBox)

        root.addView(header("Log"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Copy log") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Van Dash log", logForSharing()))
            toast("Log copied")
        })
        row.addView(button("Share log") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Van Dash log").putExtra(Intent.EXTRA_TEXT, logForSharing()), "Share log"))
        })
        row.addView(button("Clear") { VLog.clear(this); refresh() })
        root.addView(row)
        logText = text("", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(logText)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        statusBox.removeAllViews()
        permRow("Location", "to sort stores by distance and show where you are on the map",
            has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }

        val auto = try { packageManager.getPackageInfo(autoPkg, 0); true } catch (_: Exception) { false }
        lastText.text = (if (auto) "Android Auto is installed" else "Android Auto app not found on this phone") +
            "\n" + Stores.lastStore(this) + "\n${Stores.all(this).size} stores in the list"

        storesBox.removeAllViews()
        val here = Stores.phoneLocation(this)
        if (here == null) storesBox.addView(text("Location not known yet.", 14f, gray = true))
        else Stores.nearest(this, here.first, here.second, 8).forEach { (s, mi) ->
            storesBox.addView(text("Store ${s.num} · ${s.city} · ${"%.1f".format(mi)} mi\n${s.address}", 15f).apply {
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener {
                    val uri = Uri.parse("geo:0,0?q=${s.lat},${s.lng}(${Uri.encode("Publix ${s.num}")})")
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("No maps app found") }
                }
            })
        }
        logText.text = VLog.read(this).lines().takeLast(120).reversed().joinToString("\n")
    }

    private fun openAndroidAuto() {
        val tries = listOf(
            Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(autoPkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", autoPkg, null)),
        )
        for (i in tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        toast("Open Settings and search for Android Auto")
    }

    private fun logForSharing(): String =
        "Van Dash ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            VLog.read(this)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- tiny view helpers ----------

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun permRow(name: String, why: String, ok: Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        row.addView(text("${if (ok) "✅" else "⚠️"}  $name\n$why", 14f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!ok) row.addView(button("Allow") { fix() })
        statusBox.addView(row)
    }

    private fun header(s: String) = text(s, 17f, bold = true).apply { setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt()) }

    private fun text(s: String, size: Float, bold: Boolean = false, gray: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (gray) Color.parseColor("#5F6368") else Color.parseColor("#202124"))
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (8 * dp).toInt() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
