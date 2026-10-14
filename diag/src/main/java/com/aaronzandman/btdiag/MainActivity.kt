package com.aaronzandman.btdiag

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var watchBtn: Button
    private lateinit var bgSw: Switch
    private lateinit var targetTv: TextView
    private lateinit var testBtn: Button
    private lateinit var devBox: LinearLayout
    private lateinit var logTv: TextView
    private lateinit var stepTitle: TextView
    private lateinit var stepBody: TextView
    private lateinit var stepTimer: TextView
    private lateinit var nextBtn: Button
    private lateinit var actBtn: Button
    private lateinit var stopGuideBtn: Button
    private var lastVer = -1
    private var lastDevs = ""

    private val BLUE = Color.parseColor("#0D47A1")
    private val INK = Color.parseColor("#1B1F24")
    private val MUTED = Color.parseColor("#5F6B7A")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        L.init(this)
        window.statusBarColor = BLUE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F3F5F8")) }
        root.addView(TextView(this).apply {
            text = "BT Diagnostic"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setBackgroundColor(BLUE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        scroll.addView(col); root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        // ---------- guided test ----------
        col.addView(card().apply {
            stepTitle = text("", 17f, BLUE).apply { setTypeface(typeface, Typeface.BOLD) }
            stepBody = text("", 15f, INK).apply { setPadding(0, dp(6), 0, dp(6)) }
            stepTimer = text("", 22f, INK).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
            addView(stepTitle); addView(stepBody); addView(stepTimer)
            nextBtn = btn("Start guided test", true) { nextStep() }
            actBtn = btn("", false) { stepAction() }
            addView(row(nextBtn, actBtn))
            addView(btn("Stop the guided test", false) { endGuide("stopped by you") }.also { stopGuideBtn = it }, LinearLayout.LayoutParams(-1, -2))
        })
        status = text("", 14f, INK).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(8)) }
        col.addView(status)

        watchBtn = btn("Start watching", true) { toggleWatch() }
        col.addView(row(watchBtn, btn("Phone check", false) { phoneCheck() }))
        col.addView(row(btn("Copy log", false) { copyLog() }, btn("Clear log", false) { confirmClear() }))
        col.addView(row(btn("Allow in background", false) { allowBg() }, btn("Allow alarms", false) { allowAlarms() }))
        col.addView(head("Manual tools"))

        col.addView(card().apply {
            targetTv = text("", 14f, INK); addView(targetTv)
            bgSw = Switch(this@MainActivity).apply {
                text = "Background test: watch for the target the same way Companion does (works with this app closed)"
                textSize = 13f; setTextColor(INK); setPadding(0, dp(8), 0, dp(4))
                isChecked = BgTest.on(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    P.put(this@MainActivity, "bg", if (on) "1" else "0")
                    if (on) BgTest.start(this@MainActivity) else { BgTest.stop(this@MainActivity); L.w("BG", "background test turned off") }
                }
            }
            addView(bgSw)
            testBtn = btn("Test connect to target", false) { testTarget() }
            addView(testBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        })

        col.addView(head("Devices (tap one)"))
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devBox)
        col.addView(head("Log (newest first)"))
        logTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(INK); setTextIsSelectable(true)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat(); setStroke(1, Color.parseColor("#D6DCE4")) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(logTv)
        askPerms()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 1000) }
    }

    // ---------- building blocks ----------
    private fun text(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color) }
    private fun head(t: String) = text(t, 13f, MUTED).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(6)); isAllCaps = true }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(1, Color.parseColor("#D6DCE4")) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun btn(t: String, primary: Boolean, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setOnClickListener { on() }
        setTextColor(if (primary) Color.WHITE else BLUE)
        background = GradientDrawable().apply { setColor(if (primary) BLUE else Color.WHITE); cornerRadius = dp(8).toFloat(); setStroke(dp(1), BLUE) }
        minHeight = dp(44)
    }
    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); bottomMargin = dp(8) })
        addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); bottomMargin = dp(8) })
    }

    // ---------- screen ----------
    private fun ago(t: Long): String { val s = (System.currentTimeMillis() - t) / 1000; return if (s < 60) "${s}s ago" else if (s < 3600) "${s / 60}m ago" else "${s / 3600}h ago" }

    private fun render() {
        val n = WatchService.seen.values.count { it.present }
        status.text = if (WatchService.running) "● Watching since ${SimpleDateFormat("h:mm a", Locale.US).format(Date(WatchService.startedAt))} · $n devices around"
                      else if (!Env.btPerms(this)) "Allow the Bluetooth permission to start" else "Not watching"
        status.setTextColor(if (WatchService.running) Color.parseColor("#2E7D32") else MUTED)
        watchBtn.text = if (WatchService.running) "Stop watching" else "Start watching"
        val tn = P.get(this, "tName"); val ta = P.get(this, "tAddr")
        targetTv.text = if (ta.isBlank() && tn.isBlank()) "Target: none yet. Tap the vLinker in the device list." else "Target: ${tn.ifBlank { "(no name)" }}  $ta"
        testBtn.isEnabled = ta.isNotBlank() && !Probe.busy
        testBtn.text = if (Probe.busy) "Testing… (see log)" else "Test connect to target"
        if (L.version != lastVer) { lastVer = L.version; logTv.text = L.recent(400).asReversed().joinToString("\n").ifBlank { "(empty)" } }
        renderDevices()
        renderStep()
    }

    private class Row(val key: String, val name: String, val addr: String, val kind: String, val rssi: Int, val last: Long, val present: Boolean, val paired: Boolean, val info: String)

    private fun devices(): List<Row> {
        val out = LinkedHashMap<String, Row>()
        val ta = P.get(this, "tAddr")
        for ((k, s) in WatchService.seen) out[s.addr + s.kind] = Row(k, s.name, s.addr, s.kind, s.rssi, s.last, s.present, false, s.info)
        if (Env.btPerms(this)) try {
            Env.adapter(this)?.bondedDevices?.forEach { d ->
                val e = out.values.firstOrNull { it.addr == d.address }
                if (e == null) out["P" + d.address] = Row("P" + d.address, Env.name(d), d.address, "paired " + Env.type(d), 0, 0, false, true, Env.connected(d))
            }
        } catch (_: Exception) {}
        return out.values.sortedWith(compareBy<Row>({ if (it.addr == ta) 0 else 1 }, { if (Env.interesting(it.name)) 0 else 1 }, { if (it.present) 0 else 1 }, { if (it.name.isBlank()) 1 else 0 }, { -it.rssi.coerceAtLeast(-200) + if (it.rssi == 0) 999 else 0 })).take(30)
    }

    private fun renderDevices() {
        val list = devices()
        val sig = list.joinToString("|") { "${it.addr}${it.name}${it.present}${it.rssi / 5}${(System.currentTimeMillis() - it.last) / 10000}" } + P.get(this, "tAddr")
        if (sig == lastDevs) return
        lastDevs = sig
        devBox.removeAllViews()
        if (list.isEmpty()) { devBox.addView(text(if (WatchService.running) "Looking…" else "Tap Start watching to see nearby devices.", 13f, MUTED)); return }
        val ta = P.get(this, "tAddr")
        for (r in list) {
            val tv = text("", 13f, INK).apply {
                val star = if (r.addr == ta) "🎯 " else if (Env.interesting(r.name)) "★ " else ""
                val where = if (r.paired) r.info else if (r.present) "${r.rssi} dBm · now" else "last ${ago(r.last)}"
                text = "$star${r.name.ifBlank { "(no name)" }}\n${r.addr} · ${r.kind} · $where"
                if (Env.interesting(r.name) || r.addr == ta) setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (r.present || r.paired) INK else MUTED)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { setColor(if (r.addr == ta) Color.parseColor("#E3F2FD") else Color.WHITE); cornerRadius = dp(8).toFloat() }
                setOnClickListener { pick(r) }
            }
            devBox.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    private fun pick(r: Row) {
        AlertDialog.Builder(this).setTitle(r.name.ifBlank { r.addr })
            .setItems(arrayOf("Watch this one (target)", "Test connect now", "Copy name and address")) { _, w ->
                when (w) {
                    0 -> setTarget(r)
                    1 -> { setTarget(r); Probe.run(this, r.addr) }
                    2 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("device", "${r.name} ${r.addr}")); toast("Copied") }
                }
            }.show()
    }

    private fun setTarget(r: Row) {
        P.put(this, "tAddr", r.addr); P.put(this, "tName", r.name)
        L.w("TARGET", "target set: ${r.name.ifBlank { "(no name)" }} ${r.addr} (${r.kind})")
        if (Env.interesting(r.name) && !r.kind.contains("LE")) L.w("TARGET", "note: this is a classic (Bluetooth 3.0) identity, which never advertises. To test the background watch, pick its LE twin instead (e.g. vLinker FD-IOS).")
        WatchService.restartScans(this)
        if (BgTest.on(this)) BgTest.start(this)
        lastDevs = ""
    }

    // ---------- guided test ----------
    private class Step(val title: String, val body: String, val waitSec: Int, val action: String = "")
    private val STEPS = listOf(
        Step("Guided test (about 12 minutes)", "Do this at the van with the keys, starting with the van OFF. Keep the phone's Bluetooth on. Each step tells you exactly what to do; tap Next only when it says so.\n\nTap “Start guided test” when you're ready.", 0),
        Step("Step 1 of 6 · Van OFF", "Leave the van OFF and keep this screen open.\nThe app is now trying to reach the vLinker on its own, every way Companion can. Wait for the timer, then tap Next.", 120),
        Step("Step 2 of 6 · Lock the phone, then start the van", "1. Press the power button to lock the phone (screen OFF).\n2. Start the van and let it idle.\n3. Don't touch the phone for 3 minutes.\n4. Unlock, come back to this screen and tap Next.\n\nDon't tap Test connect yet.", 0),
        Step("Step 3 of 6 · Van running, screen on", "Keep the van running and leave this screen open. Wait for the timer, then tap Next.", 120),
        Step("Step 4 of 6 · Test connect", "With the van still running, tap “Test connect” (right). Wait until the log below says “Test finished”, then tap Next.", 0, "test"),
        Step("Step 5 of 6 · Turn the van off", "Turn the van off and take the key out. Leave this screen open. Wait for the timer, then tap Next.", 180),
        Step("Step 6 of 6 · Send it to Claude", "Done. Tap “Copy log” (right), then paste it into the chat with Claude.", 0, "copy")
    )
    private fun step() = P.get(this, "step").toIntOrNull() ?: 0
    private fun stepAt() = P.get(this, "stepAt").toLongOrNull() ?: 0L

    private fun nextStep() {
        val n = step()
        if (n == 0) {
            if (!Env.btPerms(this)) { askPerms(); toast("Allow Bluetooth (Nearby devices) first"); return }
            L.clear(this); lastVer = -1
            if (!WatchService.running) WatchService.start(this)
            h.postDelayed({ Lab.start(this) }, 2500)   // let the watch see the vLinker first
        }
        if (n == STEPS.size - 1) { endGuide("finished"); return }
        if (n == 4 && Probe.busy) { toast("Wait for the test to finish"); return }
        val m = n + 1
        P.put(this, "step", m.toString()); P.put(this, "stepAt", System.currentTimeMillis().toString())
        L.w("STEP", "===== ${STEPS[m].title} =====")
        if (m == STEPS.size - 1) { Lab.stop(this) }
    }

    private fun endGuide(why: String) {
        if (step() == 0) return
        L.w("STEP", "===== guided test $why =====")
        Lab.stop(this)
        P.put(this, "step", "0")
    }

    private fun stepAction() {
        when (STEPS[step()].action) {
            "test" -> { val a = P.get(this, "cAddr").ifBlank { P.get(this, "tAddr") }; if (a.isBlank()) toast("No vLinker found — is it paired?") else Probe.run(this, a) }
            "copy" -> copyLog()
        }
    }

    private fun renderStep() {
        val n = step(); val s = STEPS[n]
        stepTitle.text = s.title; stepBody.text = s.body
        val left = if (s.waitSec > 0) (s.waitSec - (System.currentTimeMillis() - stepAt()) / 1000).coerceAtLeast(0) else 0
        val since = (System.currentTimeMillis() - stepAt()) / 1000
        stepTimer.text = when { n == 0 -> ""; s.waitSec > 0 && left > 0 -> "Wait %d:%02d".format(left / 60, left % 60); s.waitSec > 0 -> "✓ Time's up — tap Next"; else -> "%d:%02d on this step".format(since / 60, since % 60) }
        stepTimer.visibility = if (n == 0) View.GONE else View.VISIBLE
        nextBtn.text = when (n) { 0 -> "Start guided test"; STEPS.size - 1 -> "Finish"; else -> "Next" }
        nextBtn.isEnabled = !(s.waitSec > 0 && left > 0) && !(n == 4 && Probe.busy)
        nextBtn.alpha = if (nextBtn.isEnabled) 1f else 0.45f
        actBtn.visibility = if (s.action.isBlank()) View.GONE else View.VISIBLE
        actBtn.text = when (s.action) { "test" -> if (Probe.busy) "Testing…" else "Test connect"; "copy" -> "Copy log"; else -> "" }
        actBtn.isEnabled = !(s.action == "test" && Probe.busy)
        stopGuideBtn.visibility = if (n == 0) View.GONE else View.VISIBLE
    }

    private fun copyLog() {
        val lines = try { L.file(this).readLines() } catch (_: Exception) { emptyList() }
        var text = "BT Diagnostic log\n" + Env.report(this).joinToString("\n") + "\n\n--- log (oldest first) ---\n" + lines.takeLast(3000).joinToString("\n")
        if (text.length > 200_000) text = "(older lines cut)\n" + text.takeLast(200_000)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BT diagnostic log", text))
        toast("Log copied (${lines.size} lines). Paste it into the chat.")
    }

    private fun allowAlarms() {
        if (Lab.exactOk(this)) { toast("Alarms already allowed"); return }
        if (Build.VERSION.SDK_INT >= 31) try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    // ---------- actions ----------
    private fun askPerms() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) want += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { !Env.granted(this, it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        L.w("PHONE", "permissions now: ${Env.perms(this)}")
    }

    private fun toggleWatch() {
        if (WatchService.running) { WatchService.stop(this); return }
        if (!Env.btPerms(this)) { askPerms(); toast("Allow Bluetooth (Nearby devices) first"); return }
        WatchService.start(this)
    }

    private fun phoneCheck() {
        val lines = Env.report(this)
        lines.forEach { L.w("PHONE", it) }
        AlertDialog.Builder(this).setTitle("Phone check").setMessage(lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
    }

    @SuppressLint("BatteryLife")
    private fun allowBg() {
        val pw = getSystemService(PowerManager::class.java)
        if (pw.isIgnoringBatteryOptimizations(packageName)) { toast("Already allowed to run in the background"); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun testTarget() {
        val a = P.get(this, "tAddr"); if (a.isBlank()) { toast("Pick a target first"); return }
        if (WatchService.running) toast("Testing… results appear in the log")
        Probe.run(this, a)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Clear the whole log?").setPositiveButton("Clear") { _, _ -> L.clear(this); lastVer = -1 }
            .setNegativeButton("Cancel", null).show()
    }

    private fun export() {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "BT-diagnostic-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.txt")
            val log = try { L.file(this).readText() } catch (_: Exception) { "" }
            f.writeText("Phone check at export:\n" + Env.report(this).joinToString("\n") + "\n\n--- log (oldest first) ---\n" + log)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Send the log"))
        } catch (e: Exception) { toast("Couldn't export: ${e.message}") }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
