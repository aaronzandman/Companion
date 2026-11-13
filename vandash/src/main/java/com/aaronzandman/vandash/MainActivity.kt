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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.smartdevicelink.transport.SdlBroadcastReceiver

/** The phone side: permissions, settings, a test list, and the log of what Ford answered. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var storesBox: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var logText: TextView

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("Van Dash", 24f, bold = true))
        root.addView(text("Shows nearby Publix stores on the van's SYNC screen. Tap one there and it goes to Ford's " +
            "built-in navigation. It doesn't use your phone's navigation or the van's audio.", 14f, gray = true))

        root.addView(header("1. Permissions"))
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusBox)

        root.addView(header("2. When I tap a store on the van screen"))
        val group = RadioGroup(this)
        listOf("ask" to "Ford asks before navigating", "go" to "Start navigating right away", "stop" to "Add it as a stop on the current route")
            .forEachIndexed { i, (key, label) ->
                group.addView(RadioButton(this).apply {
                    id = 100 + i; text = label; isChecked = Prefs.mode(this@MainActivity) == key
                    setOnClickListener { Prefs.setMode(this@MainActivity, key) }
                })
            }
        root.addView(group)

        root.addView(header("3. Test status"))
        resultText = text("", 14f)
        root.addView(resultText)

        root.addView(header("Nearby stores"))
        root.addView(text("While connected to SYNC, tapping a store here sends it to Ford's navigation too.", 13f, gray = true))
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
                .putExtra(Intent.EXTRA_SUBJECT, "Van Dash test log").putExtra(Intent.EXTRA_TEXT, logForSharing()), "Share log"))
        })
        row.addView(button("Clear") { VLog.clear(this); refresh() })
        root.addView(row)
        logText = text("", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(logText)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // If the phone is already connected to SYNC, make sure our connection starts.
        if (has(Manifest.permission.BLUETOOTH_CONNECT)) SdlBroadcastReceiver.queryForConnectedService(this)
    }

    private fun refresh() {
        statusBox.removeAllViews()
        permRow("Nearby devices (Bluetooth)", "needed to connect to SYNC", has(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) permRow("Notifications", "shows when Van Dash is connected", has(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        permRow("Location", "to sort stores by distance", fine) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
        }
        if (fine) permRow("Location: Allow all the time", "so distances work with the phone in your pocket", has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // Android only offers "Allow all the time" on the app's settings page.
            toast("Tap Permissions → Location → Allow all the time")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        val conn = if (SdlService.connected) "Connected to SYNC now" else "Not connected to SYNC right now"
        val last = Prefs.lastResult(this).ifEmpty { "No store sent yet" }
        resultText.text = "$conn\n${Prefs.lastCheck(this)}\n$last"

        storesBox.removeAllViews()
        val here = Stores.phoneLocation(this)
        if (here == null) storesBox.addView(text("Location not known yet.", 14f, gray = true))
        else Stores.nearest(this, here.first, here.second, 8).forEach { (s, mi) ->
            storesBox.addView(text("Store ${s.num} · ${s.city} · ${"%.1f".format(mi)} mi\n${s.address}", 15f).apply {
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener {
                    if (!SdlService.connected) { toast("Connect to the van first"); return@setOnClickListener }
                    startService(Intent(this@MainActivity, SdlService::class.java)
                        .setAction(SdlService.ACTION_SEND_STORE).putExtra(SdlService.EXTRA_STORE, s.num))
                    toast("Sending store ${s.num}…")
                    logText.postDelayed({ refresh() }, 2500)
                }
            })
        }
        logText.text = VLog.read(this).lines().takeLast(120).reversed().joinToString("\n")
    }

    private fun logForSharing(): String =
        "Van Dash ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "${Prefs.lastCheck(this)}\n\n" + VLog.read(this)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (has(Manifest.permission.BLUETOOTH_CONNECT)) SdlBroadcastReceiver.queryForConnectedService(this)
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
