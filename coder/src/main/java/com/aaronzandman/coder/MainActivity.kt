package com.aaronzandman.coder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Van Coder — a FORScan-style module reader/writer for a personal Ford Transit, over the same
 * OBD adapter Companion uses. It READS module data freely; WRITES are guarded (read first, keep a
 * backup, type to confirm) and may be refused by the vehicle's security access (see the banner).
 * Nothing leaves the phone.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private val BLUE = Color.parseColor("#1F3A5F")
    private val RED = Color.parseColor("#B23A3A")
    private val MUTED = Color.parseColor("#5F6B7A")
    private val BG = Color.parseColor("#F2F4F6")

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var link: Elm.Link? = null
    @Volatile private var uds: Uds? = null
    private val connected get() = uds != null

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var reqIn: EditText
    private lateinit var respIn: EditText
    private lateinit var didIn: EditText
    private lateinit var curView: TextView
    private lateinit var valIn: EditText
    private lateinit var backupsBox: LinearLayout

    private var adapters: List<Pair<String, String>> = emptyList()
    private var adapterSel = 0
    private val linkTypes = listOf("Auto", "Classic (SPP)", "BLE")
    private var linkTypeSel = 0
    private val protocols = listOf("Auto (SP0)", "CAN 11/500 (SP6)", "CAN 29/500 (SP7)", "CAN 11/250 (SP8)", "CAN 29/250 (SP9)")
    private val protocolCodes = listOf("0", "6", "7", "8", "9")
    private var protocolSel = 0

    // module presets: name -> (request id, response id)
    private val modules = listOf(
        "BCM / Body (726)" to Pair("726", "72E"),
        "IPC / Cluster (720)" to Pair("720", "728"),
        "APIM / SYNC (7D0)" to Pair("7D0", "7D8"),
        "PCM / Engine (7E0)" to Pair("7E0", "7E8"),
        "Custom…" to Pair("", "")
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("coder", Context.MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPerms()
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(28)) }
        root.addView(col)

        col.addView(TextView(this).apply { text = "Van Coder"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(BLUE) })
        col.addView(TextView(this).apply {
            text = "Read and change Ford module settings (BCM, cluster, SYNC) on your own Transit, over the OBD adapter. Everything stays on this phone."
            textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, dp(8))
        })
        // Safety banner
        col.addView(TextView(this).apply {
            text = "⚠ Changing module settings can disable a module if the address or value is wrong — that's dealer-reflash territory. This app always reads and backs up the original bytes first and makes you confirm. Writes also need the vehicle's security access, whose key is Ford-proprietary and NOT included here, so a secured module may refuse to write. Verify every value against a trusted source before writing."
            textSize = 12.5f; setTextColor(Color.WHITE); setBackgroundColor(RED)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, lp(dp(2)))

        // ---- connection ----
        card(col, "1 · Connect")
        adapters = Elm.bonded(this)
        adapterSel = adapters.indexOfFirst { it.second == prefs().getString("addr", "") }.coerceAtLeast(0)
        col.addView(label("Adapter (pair it in Android Bluetooth settings first)"))
        col.addView(spinner(adapters.map { "${it.first} · ${it.second}" }.ifEmpty { listOf("No paired adapters found") }, adapterSel) { adapterSel = it })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label("Link")); addView(spinner(linkTypes, 0) { linkTypeSel = it }) }, half())
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label("Protocol")); addView(spinner(protocols, 0) { protocolSel = it }) }, half())
        col.addView(row, lp(dp(2)))
        col.addView(btn("Connect & test bus", true) { doConnect() }, lp(dp(8)))
        status = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(0, dp(6), 0, 0); text = "Not connected." }
        col.addView(status)
        logView = TextView(this).apply {
            textSize = 11.5f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#263238"))
            setBackgroundColor(Color.parseColor("#E7ECEF")); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
        }
        col.addView(logView, lp(dp(6)))
        col.addView(btn("Copy log", false) { copy(logView.text.toString()) }, lp(dp(4)))

        // ---- module ----
        card(col, "2 · Module")
        reqIn = hexField("Request ID", "726"); respIn = hexField("Response ID", "72E")
        col.addView(label("Target module"))
        col.addView(spinner(modules.map { it.first }, 0) { i ->
            val (rq, rs) = modules[i].second; if (rq.isNotEmpty()) { reqIn.setText(rq); respIn.setText(rs) }
        })
        val mr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mr.addView(wrapField("Request ID", reqIn), half()); mr.addView(View(this), LinearLayout.LayoutParams(dp(10), 1)); mr.addView(wrapField("Response ID", respIn), half())
        col.addView(mr, lp(dp(2)))

        // ---- catalog ----
        card(col, "3 · Settings catalog")
        col.addView(TextView(this).apply { text = "Tap a setting to load its module and see what to change. The exact address/value must be confirmed by reading your own module first — addresses vary by model year and VIN."; textSize = 12.5f; setTextColor(MUTED); setPadding(0, 0, 0, dp(6)) })
        Catalog.ALL.forEach { item -> col.addView(catalogRow(item), lp(dp(4))) }

        // ---- editor ----
        card(col, "4 · Read / write")
        didIn = hexField("Data identifier (DID), e.g. DE00", "")
        col.addView(wrapField("Data identifier (DID)", didIn))
        col.addView(btn("Read this item", false) { doRead() }, lp(dp(6)))
        curView = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(BLUE); setPadding(0, dp(6), 0, dp(2)); text = "—" }
        col.addView(curView)
        valIn = hexField("New value (full bytes, hex)", "")
        col.addView(wrapField("New value to write (full bytes, hex)", valIn))
        col.addView(btn("Write (careful)", true) { doWrite() }, lp(dp(6)))

        // ---- backups ----
        card(col, "5 · Backups (original values saved before each write)")
        backupsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(backupsBox)

        setContentView(root)
        renderBackups()
    }

    // ---------- connection ----------
    private fun doConnect() {
        if (adapters.isEmpty()) { toast("Pair your OBD adapter in Android Bluetooth settings first."); return }
        val addr = adapters[adapterSel].second
        prefs().edit().putString("addr", addr).apply()
        val type = when (linkTypeSel) { 1 -> "classic"; 2 -> "ble"; else -> "auto" }
        val proto = protocolCodes[protocolSel]
        status.text = "Connecting…"; logView.visibility = View.VISIBLE; logView.text = ""
        io.execute {
            val lg = StringBuilder()
            try {
                closeLink()
                val l = Elm.open(this, addr, type); link = l
                val u = Uds(l, lg)
                val talks = u.init(proto)
                uds = u
                runOnUiThread {
                    logView.text = lg.toString()
                    status.text = if (talks) "✓ Connected — the van answered on the bus." else "Adapter connected, but the van didn't answer (0100). Try a specific protocol, make sure the engine is running, or it may be CAN FD."
                    status.setTextColor(if (talks) BLUE else RED)
                }
            } catch (e: Exception) {
                uds = null
                runOnUiThread { logView.text = lg.toString() + "\nError: ${e.message}"; status.text = "Couldn't connect: ${e.message}"; status.setTextColor(RED) }
            }
        }
    }

    private fun needConn(): Boolean { if (!connected) { toast("Connect first."); return true }; return false }

    private fun doRead() {
        if (needConn()) return
        val did = didIn.text.toString().trim().filter { it.isLetterOrDigit() }
        if (did.length < 4) { toast("Enter a 4-hex DID, e.g. DE00."); return }
        val req = reqIn.text.toString().trim(); val resp = respIn.text.toString().trim()
        curView.text = "Reading $did…"
        io.execute {
            val lg = StringBuilder(); val tmp = Uds(link!!, lg)
            tmp.setModule(req, resp)
            val r = tmp.readDid(did)
            runOnUiThread {
                logView.visibility = View.VISIBLE; logView.text = (logView.text.toString() + "\n" + lg.toString()).takeLast(4000)
                curView.text = if (r.ok) "DID $did = ${spaced(r.data!!)}" else "Read failed: ${r.error}"
                curView.setTextColor(if (r.ok) BLUE else RED)
                if (r.ok && valIn.text.isNullOrEmpty()) valIn.setText(r.data)
            }
        }
    }

    private fun doWrite() {
        if (needConn()) return
        val did = didIn.text.toString().trim().filter { it.isLetterOrDigit() }
        val value = valIn.text.toString().trim().filter { it.isLetterOrDigit() }
        val req = reqIn.text.toString().trim(); val resp = respIn.text.toString().trim()
        if (did.length < 4 || value.length < 2 || value.length % 2 != 0) { toast("Enter a valid DID and an even number of hex digits to write."); return }
        // Typed confirmation
        val input = EditText(this).apply { hint = "type WRITE" }
        AlertDialog.Builder(this)
            .setTitle("Write $did on module $req?")
            .setMessage("New value: ${spaced(value)}\n\nVan Coder will read and save the current value first, then write. A wrong value can disable this module. Type WRITE to continue.")
            .setView(input)
            .setPositiveButton("Write") { _, _ ->
                if (input.text.toString().trim().uppercase() != "WRITE") { toast("Not written — you didn't type WRITE."); return@setPositiveButton }
                performWrite(req, resp, did, value)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun performWrite(req: String, resp: String, did: String, value: String) {
        status.text = "Writing…"
        io.execute {
            val lg = StringBuilder(); val u = Uds(link!!, lg)
            u.setModule(req, resp)
            // 1) read + back up the current value
            val before = u.readDid(did)
            if (!before.ok) { runOnUiThread { finishWrite(lg, "Stopped — couldn't read the current value first (${before.error}). Nothing was written.") }; return@execute }
            saveBackup(req, did, before.data!!)
            // 2) extended session + security access (key not included; seed shown for reference)
            u.session("03")
            val seed = u.requestSeed("01")
            if (seed.ok) lg.append("(security seed: ${seed.data}. No key routine is built in, so the write may be refused.)\n")
            // 3) write
            val w = u.writeDid(did, value)
            // 4) read back
            val after = u.readDid(did)
            runOnUiThread {
                renderBackups()
                val msg = if (w.ok) "✓ Wrote $did. Now reads ${spaced(after.data ?: "?")}."
                    else "Write refused: ${w.error}.\nOriginal value (backed up): ${spaced(before.data)}." +
                         (if (w.error?.contains("security", true) == true || w.error?.contains("33") == true)
                             "\n\nThis module needs Ford security access to write, which this app can't unlock on its own." else "")
                finishWrite(lg, msg)
            }
        }
    }

    private fun finishWrite(lg: StringBuilder, msg: String) {
        logView.visibility = View.VISIBLE; logView.text = (logView.text.toString() + "\n" + lg.toString()).takeLast(4000)
        status.text = msg; status.setTextColor(if (msg.startsWith("✓")) BLUE else RED)
        AlertDialog.Builder(this).setTitle("Write result").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------- backups ----------
    private fun backups(): JSONArray = try { JSONArray(prefs().getString("backups", "[]")) } catch (_: Exception) { JSONArray() }
    private fun saveBackup(module: String, did: String, value: String) {
        val a = backups()
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("module", module).put("did", did.uppercase()).put("value", value.uppercase()))
        prefs().edit().putString("backups", a.toString()).apply()
    }
    private fun renderBackups() {
        val a = backups(); backupsBox.removeAllViews()
        if (a.length() == 0) { backupsBox.addView(TextView(this).apply { text = "No backups yet. The original value is saved here automatically before any write."; textSize = 12.5f; setTextColor(MUTED) }); return }
        for (i in a.length() - 1 downTo 0) {
            val e = a.getJSONObject(i)
            val rowV = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            rowV.addView(TextView(this).apply {
                text = "Module ${e.optString("module")} · DID ${e.optString("did")}\n${spaced(e.optString("value"))}"
                textSize = 12.5f; typeface = Typeface.MONOSPACE; setTextColor(BLUE)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowV.addView(Button(this).apply {
                text = "Restore"; isAllCaps = false; setTextColor(RED); background = null
                setOnClickListener {
                    reqIn.setText(e.optString("module")); respIn.setText(respFor(e.optString("module")))
                    didIn.setText(e.optString("did")); valIn.setText(e.optString("value"))
                    toast("Loaded the original into the editor. Review, then Write to restore it.")
                }
            })
            backupsBox.addView(rowV)
        }
    }
    private fun respFor(req: String) = modules.firstOrNull { it.second.first.equals(req, true) }?.second?.second
        ?: (req.toIntOrNull(16)?.let { String.format("%X", it + 8) } ?: "")

    // ---------- catalog ----------
    private fun catalogRow(item: Catalog.Item): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
            setOnClickListener {
                reqIn.setText(item.reqId); respIn.setText(respFor(item.reqId))
                if (item.did.isNotEmpty()) didIn.setText(item.did)
                AlertDialog.Builder(this@MainActivity).setTitle(item.title)
                    .setMessage(item.detail())
                    .setPositiveButton("Load into editor", null).show()
            }
        }
        v.addView(TextView(this).apply { text = item.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(BLUE) })
        v.addView(TextView(this).apply { text = "${item.group} · ${item.forscan}"; textSize = 12f; setTextColor(MUTED) })
        return v
    }

    // ---------- view helpers ----------
    private fun spaced(hex: String) = hex.uppercase().chunked(2).joinToString(" ")
    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun half() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12.5f; setTextColor(MUTED); setPadding(0, dp(6), 0, dp(2)) }
    private fun card(col: LinearLayout, t: String) = col.addView(TextView(this).apply {
        text = t; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(BLUE); setPadding(0, dp(16), 0, dp(2))
    })
    private fun hexField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 16f; typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setSingleLine()
    }
    private fun wrapField(lbl: String, field: EditText) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label(lbl)); addView(field, mw()) }
    private fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f; minHeight = dp(50); setOnClickListener { on() }
        setTextColor(if (primary) Color.WHITE else BLUE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (primary) setColor(BLUE) else { setColor(Color.WHITE); setStroke(dp(1), BLUE) }
        }
    }
    private fun spinner(items: List<String>, sel: Int, on: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        if (sel in items.indices) setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { on(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun copy(s: String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VanCoder", s)); toast("Copied.") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun requestPerms() {
        val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = perms.any { ContextCompat_check(it) != PackageManager.PERMISSION_GRANTED }
        if (missing) try { requestPermissions(perms, 1) } catch (_: Exception) {}
    }
    private fun ContextCompat_check(p: String) = checkSelfPermission(p)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        adapters = Elm.bonded(this)
    }

    private fun closeLink() { try { link?.close() } catch (_: Exception) {}; link = null; uds = null }
    override fun onDestroy() { super.onDestroy(); closeLink(); io.shutdownNow() }
}
