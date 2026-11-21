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
import android.webkit.WebChromeClient
import android.webkit.JsResult
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
    private var bloub: WebView? = null
    private var bReady = false
    private var bx = 0f; private var by = 0f; private var btx = 0f; private var bty = 0f
    private var bNext = 0L; private var bLookOn = false; private var bLookNext = 0L; private var bGaze = 0L; private var bEye = 0L
    private var touching = false; private var lastTouch = 0L
    private var bvx = 0f; private var bvy = 0f; private var bLastT = 0L; private var bHop = 0L; private var bAng = 0.0; private var bWasTouch = false; private var bPoke = 0L
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

    private lateinit var rootV: LinearLayout
    private lateinit var glowV: View

    private fun applyNativeTheme() {
        if (!::rootV.isInitialized) return
        rootV.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Theme.top, Theme.bg))
        window.statusBarColor = Theme.top
        window.navigationBarColor = Theme.bg
        glowV.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(170).toFloat()
            setGradientCenter(0.5f, 0.75f)
            colors = intArrayOf(Theme.alpha(Theme.ac, 0x40), 0)
        }
        bubble.setTextColor(Theme.bubbleText)
        bubble.background = GradientDrawable().apply { setColor(Theme.bubbleBg); cornerRadius = dp(18).toFloat(); setStroke(dp(2), Theme.ac) }
        var f = 0
        if (Theme.light) { f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (android.os.Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
        window.decorView.systemUiVisibility = f
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF17181D.toInt(), 0xFF0E0F12.toInt()))
        }
        window.statusBarColor = 0xFF17181D.toInt()
        window.navigationBarColor = 0xFF0E0F12.toInt()
        header = FrameLayout(this)
        val hh = (dm.heightPixels * 0.33f).toInt().coerceIn(dp(230), dp(330))
        root.addView(header, LinearLayout.LayoutParams(-1, hh))
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(170).toFloat()
                setGradientCenter(0.5f, 0.75f)
                colors = intArrayOf(0x40FF9F6E, 0x00000000)
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
            setTextColor(0xFF1A1411.toInt())
            setPadding(dp(14), dp(9), dp(14), dp(9))
            maxWidth = (dm.widthPixels * 0.7f).toInt()
            background = GradientDrawable().apply {
                setColor(0xFFFFF4EC.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(2), 0xFFFF9F6E.toInt())
            }
            alpha = 0f
        }
        header.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        startBloub()
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); root.getWindowVisibleDisplayFrame(r)
            val kb = dm.heightPixels - r.bottom > dm.heightPixels * 0.18f
            header.visibility = if (kb) View.GONE else View.VISIBLE
        }
        rootV = root; glowV = glow; applyNativeTheme()
        setContentView(root)
        val who = try { ConfigStore.load(this).persona.userName } catch (e: Exception) { "" }
        ui.postDelayed({ sayHome(if (who.isBlank()) "Hi! I'm Nana~" else "Hi $who! I'm Nana~") }, 1200)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) { touching = true; lastTouch = android.os.SystemClock.uptimeMillis() }
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { touching = false; lastTouch = android.os.SystemClock.uptimeMillis() }
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
        try { bloub?.destroy() } catch (e: Exception) {}
        bloub = null
        if (::nana.isInitialized) nana.release()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }.show()
                return true
            }
        }
        web.setBackgroundColor(0)
        web.addJavascriptInterface(Bridge(), "Android")
        buildUi()
        web.loadUrl("file:///android_asset/settings.html")
        // ask for mic + notification permission up front (overlay permission needs the settings screen)
        askRuntimePerms()
    }

    override fun onPause() {
        ui.removeCallbacks(bloubTick)
        bloub?.evaluateJavascript("Bloub.pause()", null)
        super.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startBloub() {
        val sz = dp(52)
        val w = object : WebView(this) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean = false
        }
        w.setBackgroundColor(0)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.javaScriptEnabled = true
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(v: WebView?, u: String?) { bReady = true; applyBloub() }
        }
        header.addView(w, FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.START))
        w.loadUrl("file:///android_asset/bloub/index.html")
        bloub = w
        ui.post(bloubTick)
    }

    private fun applyBloub() {
        if (!bReady) return
        val p = getSharedPreferences("bloub", MODE_PRIVATE)
        val col = (p.getString("color", "#3b93f0") ?: "#3b93f0").filter { it.isLetterOrDigit() || it == '#' }
        val sh = (p.getString("shape", "idle") ?: "idle").filter { it.isLetter() }
        bloub?.evaluateJavascript("Bloub.color('$col');Bloub.state('$sh')", null)
    }

    private fun bloubState(id: String, ms: Long) {
        bloub?.evaluateJavascript("Bloub.state('$id')", null)
        ui.postDelayed({ applyBloub() }, ms)
    }

    private fun bloubMood() {
        val m = listOf("wink", "wide", "play", "swirl", "orbit").random()
        bloub?.evaluateJavascript("Bloub.state('$m')", null)
        ui.postDelayed({ applyBloub() }, 1800L + (Math.random() * 900).toLong())
    }

    private val bloubTick = object : Runnable {
        override fun run() {
            val w = bloub ?: return
            ui.postDelayed(this, 40)
            if (header.visibility != View.VISIBLE || header.width == 0 || w.width == 0) return
            val t = android.os.SystemClock.uptimeMillis()
            val W = header.width.toFloat(); val Hh = header.height.toFloat(); val sz = w.width.toFloat()
            val roam = getSharedPreferences("bloub", MODE_PRIVATE).getBoolean("roam", true)
            val dt = ((t - bLastT).coerceIn(1L, 64L)) / 1000f
            bLastT = t
            if (touching && !bWasTouch) { bHop = t; bloubState("wide", 1300) }
            bWasTouch = touching
            if (t > bNext) {
                if (roam) {
                    val r = Math.random()
                    var wait = 3200L + (Math.random() * 2600).toLong()
                    if (r < 0.72) {
                        bAng += (if (Math.random() < 0.5) 1 else -1) * (0.35 + Math.random() * 0.5)
                        btx = (W / 2 + Math.cos(bAng) * W * 0.34).toFloat() - sz / 2
                        bty = (Hh * 0.40 + Math.sin(bAng) * Hh * 0.26).toFloat() - sz / 2
                    } else if (r < 0.92) {
                        btx = (W / 2 + (if (Math.random() < 0.5) -1 else 1) * W * 0.2).toFloat() - sz / 2
                        bty = Hh * 0.6f - sz / 2
                    } else {
                        bHop = t; bloubState("wink", 1200)
                    }
                    if (Math.random() < 0.25) bloubMood()
                    bNext = t + wait
                } else { btx = W * 0.8f - sz / 2; bty = Hh * 0.3f; bNext = t + 4000 }
            }
            // spring: slight overshoot, so it floats and wobbles instead of gliding
            bvx += ((btx - bx) * 9f - bvx * 5.5f) * dt
            bvy += ((bty - by) * 9f - bvy * 5.5f) * dt
            bx = (bx + bvx * dt).coerceIn(0f, W - sz); by = (by + bvy * dt).coerceIn(0f, Hh - sz)
            val hopT = t - bHop
            val hop = if (hopT in 0..450) -Math.abs(Math.sin(hopT / 450.0 * Math.PI)).toFloat() * dp(18) else 0f
            val bob = (Math.sin(t / 340.0) * dp(3) + Math.sin(t / 910.0) * dp(2)).toFloat()
            w.translationX = bx
            w.translationY = by + bob + hop
            val stretch = ((Math.abs(bvx) - Math.abs(bvy)) / (W * 4f)).coerceIn(-0.1f, 0.1f)
            w.scaleX = 1f + stretch; w.scaleY = 1f - stretch * 0.8f
            w.rotation = (bvx / W * 8f).coerceIn(-6f, 6f)
            val bcx = bx + sz / 2; val bcy = by + sz / 2
            val ncx = W / 2; val ncy = Hh - nana.height * 0.7f
            if (t - bEye > 200 && bReady) {
                bEye = t
                val yaw = ((ncx - bcx) / W * 90f).coerceIn(-40f, 40f)
                val pitch = ((ncy - bcy) / Hh * 60f).coerceIn(-25f, 25f)
                w.evaluateJavascript("Bloub.look(" + "%.1f,%.1f".format(java.util.Locale.US, yaw, pitch) + ")", null)
            }
            if (t > bLookNext) {
                bLookOn = !bLookOn
                bLookNext = t + if (bLookOn) 3500 + (Math.random() * 3500).toLong() else 1500 + (Math.random() * 2000).toLong()
            }
            // Nana looks at Bloub only while no finger is on the screen (touch look wins)
            if (bLookOn && !touching && t - lastTouch > 2500 && t - bGaze > 100) {
                bGaze = t
                val nx = ((bcx - ncx) / (W / 2) * 1.4f).coerceIn(-1f, 1f)
                val ny = (-(bcy - ncy) / (Hh / 2)).coerceIn(-1f, 1f)
                nana.lookAt(nx, ny)
                // when Bloub drifts close to her face she gives a tiny happy hop, rarely
                if (Math.hypot((bcx - ncx).toDouble(), (bcy - ncy).toDouble()) < sz * 1.7 && t - bPoke > 30000) { bPoke = t; nana.poke() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bloub != null) {
            ui.removeCallbacks(bloubTick); ui.post(bloubTick)
            bloub?.evaluateJavascript("Bloub.resume()", null)
        }
        web.evaluateJavascript("window.refreshStatus && refreshStatus()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                if (CompanionService.running) CompanionService.send(this, CompanionService.ACT_WAKE)
                else if (Settings.canDrawOverlays(this)) CompanionService.send(this, CompanionService.ACT_START)
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + packageName))) } catch (e: Exception) {}
            }
        }
        web.evaluateJavascript("window.refreshStatus && refreshStatus()", null)
    }

    private var pendingCloneName = ""
    @Volatile private var pendingUpd: Updater.Info? = null

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }

    private fun restartCompanion() {
        if (!CompanionService.running) return
        stopService(Intent(this, CompanionService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ CompanionService.send(this, CompanionService.ACT_START) }, 900)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 80) {
            if (resultCode != RESULT_OK) return
            val u = data?.data ?: return
            Thread {
                try {
                    Speech.saveCustomPocket(this, u)
                    js("window.onPocketVoice && onPocketVoice(true, 'Voice saved. Tap Test voice.')")
                } catch (ex: Throwable) {
                    js("window.onPocketVoice && onPocketVoice(false, " + JSONObject.quote(ex.message ?: ex.toString()) + ")")
                }
            }.start()
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
                .put("notifAccess", NanaNotifs.inst != null)
                .toString()
        }

        @JavascriptInterface fun openOverlaySettings() {
            runOnUiThread {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        @JavascriptInterface fun getTheme(): String = Theme.json(this@MainActivity)
        @JavascriptInterface fun setTheme(a: String, m: String) { Theme.save(this@MainActivity, a, m); runOnUiThread { applyNativeTheme() } }
        @JavascriptInterface fun getAgent(): String = Agent.cfgJson(this@MainActivity)
        @JavascriptInterface fun setAgent(on: Boolean, act: Boolean, kb: Boolean, ask: Boolean) { Agent.save(this@MainActivity, on, act, kb, ask) }
        @JavascriptInterface fun getAskMode(): String = Agent.askMode(this@MainActivity)
        @JavascriptInterface fun getWatchAlong(): Boolean = getSharedPreferences("nana_watch", MODE_PRIVATE).getBoolean("on", false)
        @JavascriptInterface fun getBloubCfg(): String {
            val p = getSharedPreferences("bloub", MODE_PRIVATE)
            return org.json.JSONObject().put("color", p.getString("color", "#3b93f0")).put("shape", p.getString("shape", "idle")).put("roam", p.getBoolean("roam", true)).toString()
        }
        @JavascriptInterface fun setBloubCfg(color: String, shape: String, roam: Boolean) {
            getSharedPreferences("bloub", MODE_PRIVATE).edit().putString("color", color).putString("shape", shape).putBoolean("roam", roam).apply()
            runOnUiThread { applyBloub() }
        }
        @JavascriptInterface fun setWatchAlong(on: Boolean) { getSharedPreferences("nana_watch", MODE_PRIVATE).edit().putBoolean("on", on).apply() }
        @JavascriptInterface fun watchLink(url: String) {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity)) { Toast.makeText(this@MainActivity, "Allow display over other apps first", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                try {
                    startForegroundService(Intent(this@MainActivity, CompanionService::class.java)
                        .setAction(CompanionService.ACT_ASK).putExtra("text", url.trim().take(2000)))
                } catch (e: Exception) {}
            }
        }
        @JavascriptInterface fun setAskMode(v: String) { Agent.setAskMode(this@MainActivity, v) }
        @JavascriptInterface fun getWakeState(): String = CompanionService.wakeState
        @JavascriptInterface fun batteryOk(): Boolean = try { (this@MainActivity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) } catch (e: Exception) { false }
        @JavascriptInterface fun batteryRequest() {
            runOnUiThread {
                try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + packageName))) }
                catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) {} }
            }
        }
        @JavascriptInterface fun setWake(on: Boolean): String {
            Agent.setWake(this@MainActivity, on)
            if (!on) { if (CompanionService.running) CompanionService.send(this@MainActivity, CompanionService.ACT_WAKE); return "off" }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7) }
                return "mic"
            }
            if (!Settings.canDrawOverlays(this@MainActivity)) { openOverlaySettings(); return "overlay" }
            if (!CompanionService.running) CompanionService.send(this@MainActivity, CompanionService.ACT_START)
            else CompanionService.send(this@MainActivity, CompanionService.ACT_WAKE)
            return "ok"
        }
        @JavascriptInterface fun getWakeHeard(): String = CompanionService.wakeHeard
        @JavascriptInterface fun speechStatus(): String = Speech.status(this@MainActivity).toString()
        @JavascriptInterface fun piperVoices(): String = Speech.voicesJson(this@MainActivity)
        @JavascriptInterface fun speechInstall(kind: String, id: String) { Speech.install(this@MainActivity, kind, id) }
        @JavascriptInterface fun speechRemove(kind: String, id: String) { Speech.remove(this@MainActivity, kind, id); earsApply() }
        @JavascriptInterface fun speechCancel() { Speech.cancel() }
        @JavascriptInterface fun mirageTest() {
            Thread {
                try {
                    Thread.sleep(4000)
                    val r = Mirage.lookScreen(this@MainActivity)
                    js("window.onMirageTest && onMirageTest(" + !r.startsWith("Could not") + ", " + JSONObject.quote(r.take(1200)) + ")")
                } catch (e: Throwable) {
                    js("window.onMirageTest && onMirageTest(false, " + JSONObject.quote(e.message ?: e.toString()) + ")")
                }
            }.start()
        }
        @JavascriptInterface fun earsApply() { if (CompanionService.running) CompanionService.send(this@MainActivity, CompanionService.ACT_WAKE) }
        @JavascriptInterface fun openAppSettings() {
            runOnUiThread { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + packageName))) } catch (e: Exception) {} }
        }
        @JavascriptInterface fun openAssistSettings() {
            runOnUiThread {
                val tries = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
                for (a in tries) {
                    try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break } catch (e: Exception) {}
                }
            }
        }
        @JavascriptInterface fun getReminders(): String = Reminders.json(this@MainActivity)
        @JavascriptInterface fun cancelReminder(id: String) { id.toLongOrNull()?.let { Reminders.cancelId(this@MainActivity, it) } }
        @JavascriptInterface fun openNotifAccess() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        @JavascriptInterface fun getLog(): String = AuditLog.json(this@MainActivity)
        @JavascriptInterface fun clearLog() { AuditLog.clear(this@MainActivity) }
        @JavascriptInterface fun getSearch(): String = Search.json(this@MainActivity)

        @JavascriptInterface fun setSearch(e: String, u: String, k: String) { Search.save(this@MainActivity, e, u, k) }

        @JavascriptInterface fun testSearch() { Thread { val r = Search.test(this@MainActivity); js("srTested(" + JSONObject.quote(r) + ")") }.start() }

        @JavascriptInterface fun memItems(q: String): String = Memory.itemsJson(this@MainActivity, q)

        @JavascriptInterface fun memPin(id: Int, on: Boolean) { Memory.pin(this@MainActivity, id.toLong(), on) }

        @JavascriptInterface fun memEdit(id: Int, t: String): String = Memory.edit(this@MainActivity, id.toLong(), t)

        @JavascriptInterface fun memDelete(id: Int) { Memory.delete(this@MainActivity, id.toLong()) }

        @JavascriptInterface fun memImp(id: Int, v: Int) { Memory.importance(this@MainActivity, id.toLong(), v) }

        @JavascriptInterface fun memAdd(t: String, pin: Boolean): String = Memory.add(this@MainActivity, t, "manual", 4, pin)

        @JavascriptInterface fun updState(): String {
            val s = Updater.installed(this@MainActivity)
            return JSONObject().put("installed", s.first).put("date", s.second).put("token", Updater.token(this@MainActivity).isNotEmpty()).toString()
        }

        @JavascriptInterface fun updSetToken(t: String) { Updater.setToken(this@MainActivity, t) }

        @JavascriptInterface fun updDismiss(sha: String) { Updater.dismiss(this@MainActivity, sha) }

        @JavascriptInterface fun updCheck(force: Boolean) {
            Thread {
                val c = this@MainActivity
                if (Updater.token(c).isEmpty()) { if (force) js("updErr('Paste a GitHub token first.')"); return@Thread }
                val p = c.getSharedPreferences("updater", android.content.Context.MODE_PRIVATE)
                if (!force && System.currentTimeMillis() - p.getLong("last", 0L) < 1_800_000L) return@Thread
                p.edit().putLong("last", System.currentTimeMillis()).apply()
                try {
                    val i = Updater.check(c)
                    pendingUpd = i
                    if (i == null) { if (force) js("updNone('You have the latest build.')") }
                    else js("updFound(" + JSONObject.quote(i.json()) + "," + (force || Updater.dismissed(c) != i.sha) + ")")
                } catch (e: Exception) { if (force) js("updErr(" + JSONObject.quote(e.message ?: "Check failed") + ")") }
            }.start()
        }

        @JavascriptInterface fun updInstall() {
            Thread {
                val c = this@MainActivity
                val i = pendingUpd
                if (i == null) { js("updStatus('Check for updates first.')"); return@Thread }
                if (!Updater.canInstall(c)) {
                    runOnUiThread { Updater.openInstallPermission(c) }
                    js("updStatus('Allow Install unknown apps for Nana, come back and tap Update now again.')")
                    return@Thread
                }
                try {
                    Updater.onMsg = { m -> js("updStatus(" + JSONObject.quote(m) + ")") }
                    val f = Updater.download(c, i) { pr -> js("updProgress($pr)") }
                    js("updProgress(100)")
                    Updater.install(c, f)
                } catch (e: Exception) { js("updStatus(" + JSONObject.quote("Update failed: " + (e.message ?: e.toString()).take(160)) + ")") }
            }.start()
        }

        @JavascriptInterface fun getMemory(): String = Memory.json(this@MainActivity)
        @JavascriptInterface fun getLearn(): Boolean = Memory.learnOn(this@MainActivity)
        @JavascriptInterface fun setLearn(on: Boolean) { Memory.setLearn(this@MainActivity, on) }
        @JavascriptInterface fun getBond(): String = Memory.bond(this@MainActivity)
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

        @JavascriptInterface fun pocketVoices(): String = Speech.pocketVoicesJson(this@MainActivity)

        @JavascriptInterface fun pickPocketVoice() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
                startActivityForResult(i, 80)
            }
        }

        @JavascriptInterface fun pocketRemoveCustom() { Speech.removeCustomPocket(this@MainActivity) }

        @JavascriptInterface fun teamState(since: Int): String = Team.state(this@MainActivity, since)

        @JavascriptInterface fun teamPost(text: String, target: String) { Thread { Team.post(this@MainActivity, text, target) }.start() }

        @JavascriptInterface fun teamStop() { Team.stopAll("Stopped by you.") }

        @JavascriptInterface fun connList(): String = Connect.json(this@MainActivity)

        @JavascriptInterface fun connSave(json: String): String = Connect.save(this@MainActivity, json)

        @JavascriptInterface fun connDelete(name: String) { Connect.delete(this@MainActivity, name) }

        @JavascriptInterface fun connTest(name: String): String = Connect.fire(this@MainActivity, name, "")

        @JavascriptInterface fun teamEmp(json: String) { try { Team.setEmp(this@MainActivity, json) } catch (e: Exception) {} }

        @JavascriptInterface fun teamClear() { Team.clear(this@MainActivity) }

        @JavascriptInterface fun teamConfig(json: String) { try { Team.setConfig(this@MainActivity, json) } catch (e: Exception) {} }

        @JavascriptInterface fun skillsList(): String = Skills.listJson(this@MainActivity)

        @JavascriptInterface fun skillSave(name: String, desc: String, body: String): String = Skills.save(this@MainActivity, name, desc, body)

        @JavascriptInterface fun skillDelete(name: String) { Skills.delete(this@MainActivity, name) }

        @JavascriptInterface fun skillToggle(name: String, on: Boolean) { Skills.toggle(this@MainActivity, name, on) }

        @JavascriptInterface fun skillImport(url: String) {
            Thread {
                val r = Skills.importUrl(this@MainActivity, url)
                js("skImported(" + JSONObject.quote(r) + ")")
            }.start()
        }

        @JavascriptInterface fun callStart() { CompanionService.send(this@MainActivity, CompanionService.ACT_CALL) }

        @JavascriptInterface fun callStop() { CompanionService.send(this@MainActivity, CompanionService.ACT_HANGUP) }

        @JavascriptInterface fun callState(): String = JSONObject().put("active", Call.active).put("state", Call.state).put("running", CompanionService.running).toString()

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

        /** Asks ONE provider's API which models it offers. Result arrives via window.onModels. */
        @JavascriptInterface fun fetchModels(json: String) {
            Thread {
                var ok = false
                var out: String
                try {
                    val p = Provider.fromJson(JSONObject(json))
                    out = JSONArray(Adapters.listModels(p)).toString()
                    ok = true
                } catch (e: Exception) {
                    out = e.message ?: e.toString()
                }
                val id = try { JSONObject(json).optString("id") } catch (e: Exception) { "" }
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onModels && onModels(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(out)})", null
                    )
                }
            }.start()
        }

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
