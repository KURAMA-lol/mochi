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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Hosts the settings UI (assets/settings.html) in a WebView and bridges it to the native router/service. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF15101F.toInt())
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
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
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            var msg: String
            try {
                val tmp = File(filesDir, "model.tmp")
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val m = VrmLoader.load(tmp)
                val dst = ChibiView.modelFile(this)
                dst.delete()
                tmp.renameTo(dst)
                ChibiView.lastError = ""
                msg = "3D model loaded (" + m.vertexCount + " vertices)"
                runOnUiThread { restartCompanion() }
            } catch (e: Throwable) {
                File(filesDir, "model.tmp").delete()
                msg = "Could not use that file: " + (e.message ?: e.toString())
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                web.evaluateJavascript("window.onModel && onModel()", null)
            }
        }.start()
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
                .toString()
        }

        @JavascriptInterface fun openOverlaySettings() {
            runOnUiThread {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
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

        @JavascriptInterface fun pickModel() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                startActivityForResult(i, 77)
            }
        }

        @JavascriptInterface fun removeModel() {
            ChibiView.modelFile(this@MainActivity).delete()
            ChibiView.lastError = ""
            runOnUiThread { restartCompanion() }
        }

        @JavascriptInterface fun modelInfo(): String {
            val f = ChibiView.modelFile(this@MainActivity)
            return JSONObject().put("has", f.exists()).put("sizeKb", f.length() / 1024)
                .put("error", ChibiView.lastError).toString()
        }

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
