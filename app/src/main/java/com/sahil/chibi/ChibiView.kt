package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.random.Random

/**
 * Character view. Shows Nana (LivLiv Player, a 2.5D character) in a transparent WebView.
 * Assets live in assets/nana/ and are served to the page from https://hina.local/.
 * If the player cannot start, a static sprite is drawn instead.
 * Public vars are set by CompanionService.
 */
class ChibiView(ctx: Context) : FrameLayout(ctx) {
    @Volatile var mood = "neutral"
    @Volatile var action = "idle"
    @Volatile var facingRight = true
    @Volatile var talking = false
    @Volatile var thinking = false   // unused (no animation)
    @Volatile var pixel = false
    @Volatile var yaw = 0f
    @Volatile var vsx = 1f
    @Volatile var vsy = 1f
    @Volatile var vopen = 0f    // vent opening 0..1
    @Volatile var vdy = 0f      // character vertical offset in px (down = into the vent)
    /** Chat open: show the resting sprite (head on arms) instead of the 3D player. */
    var pose: Boolean = false
        set(v) {
            field = v
            wv?.visibility = if (v) INVISIBLE else VISIBLE
            postInvalidate()
        }

    companion object {
        @Volatile var lastError: String = ""
        private const val PATCH_X = 124f
        private const val PATCH_Y = 122f
        fun charId(c: Context): String = "nana"
        fun has3d(c: Context): Boolean = true
    }

    private fun load(name: String): Bitmap? = try {
        val s = context.assets.open(name).bufferedReader().use { it.readText() }.trim()
        val b = Base64.decode(s, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (ex: Exception) { null }

    // Resting pose (head on folded arms). rest_blink.b64 is only the eye area with the eyes closed,
    // drawn on top of the pose at PATCH_X/PATCH_Y (bitmap pixels) while she blinks.
    private val open: Bitmap? by lazy { load("rest.b64") }
    private val blinkPatch: Bitmap? by lazy { load("rest_blink.b64") }
    private val patchDst = RectF()
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()
    private var nextBlink = SystemClock.uptimeMillis() + 1500L
    private var blinkUntil = 0L
    private var pendingDouble = false

    private var wv: WebView? = null
    private var lastS = ""
    private val stateTick: Runnable = object : Runnable {
        override fun run() {
            val w = wv ?: return
            val m = mood.replace("'", "")
            val s = action + "|" + facingRight + "|" + talking + "|" + m
            if (s != lastS) {
                lastS = s
                w.evaluateJavascript("window.H && H.s('" + action.replace("'", "") + "'," + facingRight + "," + talking + ",'" + m + "')", null)
            }
            postDelayed(this, 120)
        }
    }

    inner class NanaBridge {
        @JavascriptInterface fun err(m: String) {
            lastError = m.take(200)
            post { fallback() }
        }
        @JavascriptInterface fun ready() { lastError = "" }
    }

    init {
        setWillNotDraw(false)
        startNana()
    }

    private fun mime(p: String): String = when {
        p.endsWith(".html") -> "text/html"
        p.endsWith(".js") -> "text/javascript"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".webp") -> "image/webp"
        p.endsWith(".png") -> "image/png"
        else -> "text/plain"
    }

    private fun reply(mt: String, data: ByteArray, code: Int): WebResourceResponse =
        WebResourceResponse(mt, "UTF-8", code, if (code == 200) "OK" else "Not Found",
            mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(data))

    private fun serve(u: android.net.Uri): WebResourceResponse {
        if (u.host != "hina.local") return reply("text/plain", ByteArray(0), 404)
        var p = (u.path ?: "/").trimStart('/')
        if (p.isEmpty()) p = "index.html"
        if (p.contains("..")) return reply("text/plain", ByteArray(0), 404)
        return try {
            val data = context.assets.open("nana/" + p).use { it.readBytes() }
            reply(mime(p), data, 200)
        } catch (e: Exception) { reply("text/plain", ByteArray(0), 404) }
    }

    private fun startNana() {
        try {
            val w = WebView(context)
            w.setBackgroundColor(0)
            w.isVerticalScrollBarEnabled = false
            w.isHorizontalScrollBarEnabled = false
            w.overScrollMode = OVER_SCROLL_NEVER
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.addJavascriptInterface(NanaBridge(), "HinaNative")
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = serve(request.url)
            }
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            w.loadUrl("https://hina.local/index.html")
            wv = w
            postDelayed(stateTick, 300)
        } catch (e: Throwable) {
            lastError = (e.message ?: e.toString()).take(200)
            wv = null
        }
    }

    /** Called when the player could not start: drop back to the sprite. */
    fun fallback() {
        removeCallbacks(stateTick)
        wv?.let { try { removeView(it); it.destroy() } catch (e: Throwable) { } }
        wv = null
        invalidate()
    }

    private val ventRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A8494.toInt() }
    private val ventHole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0D12.toInt() }
    private val ventSlat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4150.toInt(); strokeWidth = 2f }
    private val ventRect = RectF()

    override fun draw(c: Canvas) {
        val o = vopen
        val dy = vdy
        if (o <= 0f && dy == 0f) { super.draw(c); return }
        val d = resources.displayMetrics.density
        val line = height - 6f * d
        val cx = width / 2f
        val rx = min(width * 0.3f, 36f * d) * o
        val ry = 9f * d * o
        if (o > 0f) {
            ventRect.set(cx - rx - 3f * d, line - ry - 2f * d, cx + rx + 3f * d, line + ry + 2f * d)
            c.drawOval(ventRect, ventRim)
            ventRect.set(cx - rx, line - ry, cx + rx, line + ry)
            c.drawOval(ventRect, ventHole)
            if (o > 0.6f) for (i in -1..1) c.drawLine(cx + i * rx * 0.45f, line - ry * 0.7f, cx + i * rx * 0.45f, line + ry * 0.7f, ventSlat)
        }
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), line)
        c.translate(0f, dy)
        super.draw(c)
        c.restore()
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = wv != null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (wv != null) { removeCallbacks(stateTick); postDelayed(stateTick, 100) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stateTick)
        super.onDetachedFromWindow()
    }

    fun lookAt(nx: Float, ny: Float) { wv?.evaluateJavascript("window.H&&H.look($nx,$ny)", null) }
    fun poke() { wv?.evaluateJavascript("window.H&&H.poke()", null) }

    fun release() {
        removeCallbacks(stateTick)
        try { wv?.destroy() } catch (e: Exception) {}
        wv = null
    }

    override fun onDraw(c: Canvas) {
        if (wv != null && !pose) return
        val base = open ?: return
        val now = SystemClock.uptimeMillis()
        if (now >= nextBlink && now >= blinkUntil) {
            blinkUntil = now + 140L
            if (pendingDouble) {
                pendingDouble = false
                nextBlink = now + 320L
            } else {
                pendingDouble = Random.nextInt(5) == 0
                nextBlink = now + Random.nextLong(2500L, 5500L)
            }
        }
        val pb = blinkPatch
        val blink = now < blinkUntil && pb != null
        val s = min(width / base.width.toFloat(), height / base.height.toFloat())
        val w = base.width * s
        val h = base.height * s
        val x = (width - w) / 2f
        val dn = resources.displayMetrics.density
        // chat open: the arms rest right on the top edge of the chat panel
        val y = if (pose) height - h - 26f * dn + 3f * dn else (height - h) / 2f
        dst.set(x, y, x + w, y + h)
        c.save()
        c.scale(vsx, vsy, width / 2f, height.toFloat())
        if (!facingRight && !pose) c.scale(-1f, 1f, width / 2f, 0f)
        c.drawBitmap(base, null, dst, bp)
        if (blink && pb != null) {
            patchDst.set(x + PATCH_X * s, y + PATCH_Y * s, x + (PATCH_X + pb.width) * s, y + (PATCH_Y + pb.height) * s)
            c.drawBitmap(pb, null, patchDst, bp)
        }
        c.restore()
        postInvalidateDelayed(60)
    }
}
