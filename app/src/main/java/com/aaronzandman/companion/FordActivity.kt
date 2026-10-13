package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Signs in to Ford (FordConnect) on Ford's own pages. When Ford sends you back to the registered
 * address (http://localhost:8080/ford/callback), Companion catches it here; nothing is sent anywhere else.
 */
class FordActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var msg: TextView
    private val state = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        msg = TextView(this).apply {
            text = "Sign in with your Ford account (the one your van is linked to in the FordPass / Ford app), then choose the van and allow access."
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1E4E8C")); setPadding(30, 24, 30, 24); textSize = 14f
        }
        root.addView(msg)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = catch(req.url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { catch(Uri.parse(url)) }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        web.loadUrl(Ford.authUrl(this, state))
    }

    @Deprecated("Back handling")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    /** True when the address is Ford sending us back with the sign-in result. */
    private fun catch(u: Uri): Boolean {
        if (!u.toString().startsWith(Ford.REDIRECT) || done) return false
        done = true
        web.stopLoading(); web.loadUrl("about:blank")
        val err = u.getQueryParameter("error_description") ?: u.getQueryParameter("error")
        val code = u.getQueryParameter("code")
        if (err != null || code == null) { finishWith(false, "Ford didn't allow access: ${err ?: "no code returned"}"); return true }
        if (u.getQueryParameter("state") != state) { finishWith(false, "The sign-in didn't match. Please try again."); return true }
        msg.text = "Signed in. Finishing up with Ford…"
        Thread {
            val e = Ford.exchange(this, code)
            if (e != null) { runOnUiThread { finishWith(false, e) }; return@Thread }
            val (gc, gt) = Ford.get(this, "garage")
            val vehicle = describe(gt)
            if (gc in 200..299) Store.put(this, "fordVehicle", vehicle)
            runOnUiThread { finishWith(true, if (gc in 200..299) "Connected to $vehicle." else "Signed in, but Ford didn't return your vehicle yet (code $gc). Try Test Ford data in a few minutes.") }
        }.start()
        return true
    }

    private fun describe(json: String): String = try {
        val j = JSONObject(json)
        val v = when { j.has("vehicles") -> j.getJSONArray("vehicles").optJSONObject(0) ?: j; json.trim().startsWith("[") -> JSONArray(json).optJSONObject(0) ?: j; else -> j }
        listOf(v.optString("modelYear"), v.optString("modelName"), v.optString("vehicleType")).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "your Ford" }
    } catch (_: Exception) { "your Ford" }

    private fun finishWith(ok: Boolean, text: String) {
        AlertDialog.Builder(this).setTitle(if (ok) "Ford connected" else "Ford connection").setMessage(text)
            .setPositiveButton("OK") { _, _ -> setResult(if (ok) RESULT_OK else RESULT_CANCELED); finish() }
            .setCancelable(false).show()
    }
}
