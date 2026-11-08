package com.sahil.chibi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Hosts the settings UI (assets/settings.html) in a WebView and bridges it to the native router/service. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var nana: ChibiView
    private lateinit var header: FrameLayout
    private lateinit var bubble: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var lastLook = 0L
    private val hideBubbleRun = Runnable { bubble.animate().alpha(0f).setDuration(250).start() }
    private val pokeLines = listOf(
        "Hehe, that tickles!", "Yes? I'm listening~", "Don't poke too much!", "Nana is here~",
        "Kyaa! You surprised me!", "Want to chat? Press Start below!", "Padhai kar rahe ho na?"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sayHome(text: String) {
        bubble.text = text
        bubble.animate().cancel()
        bubble.alpha = 1f
        ui.removeCallbacks(hideBubbleRun)
        ui.postDelayed(hideBubbleRun, 3200)
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B3033.toInt(), 0xFF071416.toInt()))
        }
        window.statusBarColor = 0xFF0B3033.toInt()
        window.navigationBarColor = 0xFF0C1B1D.toInt()
        header = FrameLayout(this)
        val hh = (dm.heightPixels * 0.33f).toInt().coerceIn(dp(230), dp(330))
        root.addView(header, LinearLayout.LayoutParams(-1, hh))
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(170).toFloat()
                setGradientCenter(0.5f, 0.75f)
                colors = intArrayOf(0x665EEAD4, 0x00000000)
            }
        }
        header.addView(glow, FrameLayout.LayoutParams(-1, -1))
        nana = ChibiView(this)
        nana.action = "idle"
        val nh = hh - dp(18)
        val nw = (nh * 0.86f).toInt()
        header.addView(nana, FrameLayout.LayoutParams(nw, nh, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        nana.setOnClickListener {
            nana.poke()
            sayHome(pokeLines.random())
        }
        bubble = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF0E2A2B.toInt())
            setPadding(dp(14), dp(9), dp(14), dp(9))
            maxWidth = (dm.widthPixels * 0.7f).toInt()
            background = GradientDrawable().apply {
                setColor(0xFFEFFEFA.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(2), 0xFF5EEAD4.toInt())
            }
            alpha = 0f
        }
        header.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); root.getWindowVisibleDisplayFrame(r)
            val kb = dm.heightPixels - r.bottom > dm.heightPixels * 0.18f
            header.visibility = if (kb) View.GONE else View.VISIBLE
        }
        setContentView(root)
        val who = try { ConfigStore.load(this).persona.userName } catch (e: Exception) { "" }
        ui.postDelayed({ sayHome(if (who.isBlank()) "Hi! I'm Nana~" else "Hi $who! I'm Nana~") }, 1200)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) && ::nana.isInitialized && header.visibility == View.VISIBLE) {
            val now = System.currentTimeMillis()
            if (a == MotionEvent.ACTION_DOWN || now - lastLook > 60) {
                lastLook = now
                val loc = IntArray(2); nana.getLocationOnScreen(loc)
                val cx = loc[0] + nana.width / 2f
                val cy = loc[1] + nana.height * 0.3f
                val hw = resources.displayMetrics.widthPixels / 2f
                val hh = resources.displayMetrics.heightPixels / 2f
                nana.lookAt(((ev.rawX - cx) / hw * 1.4f).coerceIn(-1f, 1f), (-(ev.rawY - cy) / hh * 1.4f).coerceIn(-1f, 1f))
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::nana.isInitialized) nana.release()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0)
        web.addJavascriptInterface(Bridge(), "Android")
        buildUi()
        web.loadUrl("file:///android_asset/settings.html")
        // ask for mic + notification permission up front (overlay permission needs the settings screen)
        askRuntimePerms()
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.refreshStatus && refreshStatus()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        web.evaluateJavascript("window.refreshStatus && refreshStatus()", null)
    }

    private var pendingCloneName = ""

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }

    private fun restartCompanion() {
        if (!CompanionService.running) return
        stopService(Intent(this, CompanionService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ CompanionService.send(this, CompanionService.ACT_START) }, 900)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78) {
            if (resultCode != RESULT_OK) return
            val u = data?.data ?: return
            val nm = pendingCloneName
            Thread {
                try {
                    val id = Voice.clone(this, nm, u)
                    val c = Voice.cfg(this)
                    val e = c.optJSONObject("eleven") ?: JSONObject()
                    e.put("voiceId", id)
                    c.put("eleven", e).put("engine", "eleven")
                    Voice.save(this, c.toString())
                    js("window.onVoiceClone && onVoiceClone(true, " + JSONObject.quote(id) + ")")
                } catch (ex: Throwable) {
                    js("window.onVoiceClone && onVoiceClone(false, " + JSONObject.quote(ex.message ?: ex.toString()) + ")")
                }
            }.start()
            return
        }
    }

    private fun askRuntimePerms() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    inner class Bridge {
        @JavascriptInterface fun getConfig(): String = ConfigStore.raw(this@MainActivity)

        @JavascriptInterface fun saveConfig(json: String): Boolean = try {
            ConfigStore.save(this@MainActivity, json)
            if (CompanionService.running) CompanionService.send(this@MainActivity, CompanionService.ACT_RELOAD)
            true
        } catch (e: Exception) { false }

        @JavascriptInterface fun getStatus(): String {
            val notif = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return JSONObject()
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                .put("notif", notif)
                .put("running", CompanionService.running)
                .put("access", NanaAccess.inst != null)
                .toString()
        }

        @JavascriptInterface fun openOverlaySettings() {
            runOnUiThread {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        @JavascriptInterface fun getAgent(): String = Agent.cfgJson(this@MainActivity)
        @JavascriptInterface fun setAgent(on: Boolean, act: Boolean, kb: Boolean) { Agent.save(this@MainActivity, on, act, kb) }
        @JavascriptInterface fun getMemory(): String = Memory.json(this@MainActivity)
        @JavascriptInterface fun addMemory(t: String): String = Memory.add(this@MainActivity, t)
        @JavascriptInterface fun deleteMemory(i: Int) { Memory.removeAt(this@MainActivity, i) }
        @JavascriptInterface fun clearMemory() { Memory.clear(this@MainActivity) }
        @JavascriptInterface fun openAccessibility() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        @JavascriptInterface fun openBrowser() {
            runOnUiThread { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        }

        @JavascriptInterface fun requestPerms() { runOnUiThread { askRuntimePerms() } }

        @JavascriptInterface fun start() {
            if (!Settings.canDrawOverlays(this@MainActivity)) { openOverlaySettings(); return }
            CompanionService.send(this@MainActivity, CompanionService.ACT_START)
        }

        @JavascriptInterface fun summon() {
            if (!Settings.canDrawOverlays(this@MainActivity)) { openOverlaySettings(); return }
            CompanionService.send(this@MainActivity, CompanionService.ACT_SUMMON)
        }

        @JavascriptInterface fun stop() {
            stopService(Intent(this@MainActivity, CompanionService::class.java))
        }

        @JavascriptInterface fun getVoice(): String = Voice.raw(this@MainActivity)

        @JavascriptInterface fun saveVoice(json: String): Boolean = try { Voice.save(this@MainActivity, json); true } catch (e: Exception) { false }

        @JavascriptInterface fun testVoice(text: String) {
            Voice.speak(this@MainActivity, text) { err ->
                js("window.onVoiceTest && onVoiceTest(" + (err == null) + ", " + JSONObject.quote(err ?: "ok") + ")")
            }
        }

        @JavascriptInterface fun listVoices() {
            Thread {
                try {
                    val a = Voice.listVoices(this@MainActivity)
                    js("window.onVoiceList && onVoiceList(true, " + a.toString() + ")")
                } catch (e: Throwable) {
                    js("window.onVoiceList && onVoiceList(false, " + JSONObject.quote(e.message ?: e.toString()) + ")")
                }
            }.start()
        }

        @JavascriptInterface fun cloneVoice(name: String) {
            pendingCloneName = name
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
                startActivityForResult(i, 78)
            }
        }

        @JavascriptInterface fun pickMascotImage() {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(i, 79)
        }

        @JavascriptInterface fun saveMascot(f1: String, f2: String) {
            try {
                File(filesDir, "mascot_user1.png").writeBytes(android.util.Base64.decode(f1, android.util.Base64.DEFAULT))
                val p2 = File(filesDir, "mascot_user2.png")
                if (f2.isNotBlank()) p2.writeBytes(android.util.Base64.decode(f2, android.util.Base64.DEFAULT)) else p2.delete()
                getSharedPreferences("char_cfg", MODE_PRIVATE).edit().putString("id", "custom").apply()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Mascot saved", Toast.LENGTH_SHORT).show()
                    restartCompanion()
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Could not save: " + (e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface fun saveToDownloads(name: String, mime: String, b64: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT < 29) throw RuntimeException("needs Android 10 or newer")
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads: " + name, Toast.LENGTH_LONG).show() }
                true
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Could not save: " + (e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
                false
            }
        }

        @JavascriptInterface fun getChar(): String = ChibiView.charId(this@MainActivity)

        @JavascriptInterface fun setChar(id: String) {
            getSharedPreferences("char_cfg", MODE_PRIVATE).edit().putString("id", id).apply()
            runOnUiThread { restartCompanion() }
        }

        @JavascriptInterface fun modelInfo(): String = JSONObject().put("error", ChibiView.lastError).toString()

        @JavascriptInterface fun getStats(): String {
            val cfg = ConfigStore.load(this@MainActivity)
            val now = System.currentTimeMillis()
            val a = JSONArray()
            for (p in cfg.providers) {
                val s = Router.statOf(p.id)
                a.put(JSONObject()
                    .put("name", p.name).put("calls", s.calls).put("fails", s.fails)
                    .put("avgMs", s.ewmaMs.toInt())
                    .put("cooldownSec", ((s.cooldownUntil - now) / 1000).coerceAtLeast(0))
                    .put("lastError", s.lastError))
            }
            return a.toString()
        }

        @JavascriptInterface fun resetStats() { Router.resetStats() }

        @JavascriptInterface fun defaultPrompt(): String = DEFAULT_PROMPT

        @JavascriptInterface fun dryRun(text: String, voice: Boolean): String {
            val cfg = ConfigStore.load(this@MainActivity)
            val plan = Router.plan(cfg, text, voice, false)
            val names = JSONArray()
            plan.order.forEach { names.put(it.name) }
            return JSONObject().put("rule", plan.rule ?: JSONObject.NULL).put("order", names).toString()
        }

        /** Sends a tiny test prompt straight to ONE provider (bypasses routing). Result arrives via window.onTest. */
        @JavascriptInterface fun testProvider(json: String) {
            Thread {
                var ok = false
                var out: String
                try {
                    val p = Provider.fromJson(JSONObject(json))
                    val t0 = System.currentTimeMillis()
                    val r = Adapters.call(p, "You are a test. Reply very briefly.", listOf(Msg("user", "Say hi in 3 words.")))
                    out = r.trim().take(120) + "  (" + (System.currentTimeMillis() - t0) + "ms)"
                    ok = true
                } catch (e: Exception) {
                    out = e.message ?: e.toString()
                }
                val id = try { JSONObject(json).optString("id") } catch (e: Exception) { "" }
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onTest && onTest(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(out)})", null
                    )
                }
            }.start()
        }
    }
}
