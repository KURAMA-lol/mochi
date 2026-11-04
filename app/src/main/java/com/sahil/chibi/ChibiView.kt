package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import android.widget.FrameLayout
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * Character view. If a VRM model was picked in settings it shows the animated 3D model,
 * otherwise (or if loading fails) the static reference sprite with an eye blink.
 * Public vars are set by CompanionService.
 */
class ChibiView(ctx: Context) : FrameLayout(ctx) {
    @Volatile var mood = "neutral"
    @Volatile var action = "idle"
    @Volatile var facingRight = true
    @Volatile var talking = false
    @Volatile var pixel = false
    @Volatile var yaw = 0f      // body turn (deg) while moving, set by the service
    @Volatile var vsx = 1f      // vent squash/stretch scale
    @Volatile var vsy = 1f
    @Volatile var vopen = 0f    // vent opening 0..1
    @Volatile var vdy = 0f      // character vertical offset in px (down = into the vent)

    companion object {
        @Volatile var lastError: String = ""
        fun modelFile(c: Context) = File(c.filesDir, "model.vrm")
        /** Uses the picked model, otherwise the bundled default (assets/default.vrm) if the app ships one. */
        fun charId(c: Context): String =
            c.getSharedPreferences("char_cfg", Context.MODE_PRIVATE).getString("id", "amongus") ?: "amongus"

        fun has3d(c: Context): Boolean {
            if (charId(c) != "amongus") return false
            val f = modelFile(c)
            if (!f.exists()) {
                try {
                    c.assets.open("default.vrm").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                } catch (e: Exception) { f.delete() }
            }
            return f.exists()
        }
    }

    private var gl: GlView? = null

    private fun load(name: String): Bitmap? = try {
        val s = context.assets.open(name).bufferedReader().use { it.readText() }.trim()
        val b = Base64.decode(s, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (ex: Exception) { null }

    private val open: Bitmap? by lazy { load("mascot.b64") }
    private val closed: Bitmap? by lazy { load("mascot_closed.b64") }
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()
    private var nextBlink = SystemClock.uptimeMillis() + 1500L
    private var blinkUntil = 0L
    private var pendingDouble = false

    private var pc: PixelChar? = null
    private var wv: android.webkit.WebView? = null
    private var lastS = ""
    private val stateTick: Runnable = object : Runnable {
        override fun run() {
            val w = wv ?: return
            val s = action + "|" + facingRight + "|" + talking
            if (s != lastS) {
                lastS = s
                w.evaluateJavascript("window.PX_S && PX_S('" + action + "'," + facingRight + "," + talking + ")", null)
            }
            postDelayed(this, 150)
        }
    }

    init {
        setWillNotDraw(false)
        pc = when (charId(ctx)) { "lumi" -> Lumi(); "webb" -> Webb(); "custom" -> Custom(ctx).takeIf { it.ok() }; else -> null }
        loadMascot(ctx)
        if (has3d(ctx)) {
            val g = GlView(ctx, this, modelFile(ctx))
            gl = g
            addView(g, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Called when the 3D model could not be loaded: drop back to the sprite. */
    fun fallback() {
        gl?.let { removeView(it) }
        gl = null
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

    /** Named mascots from Pixel Studio ("mascot:<slug>"): pixel frames are drawn natively, code mascots run in a transparent WebView. */
    private fun loadMascot(ctx: Context) {
        val id = charId(ctx)
        if (!id.startsWith("mascot:")) return
        val json = try { Mascots.get(ctx, id.substring(7)) } catch (e: Throwable) { null } ?: return
        val o = try { org.json.JSONObject(json) } catch (e: Throwable) { return }
        if (o.optString("type") == "pixel") {
            pc = Custom(Mascots.bitmap(o.optString("f1")), Mascots.bitmap(o.optString("f2"))).takeIf { it.ok() }
        } else if (o.optString("type") == "code") {
            startCode(json)
        }
    }

    private fun startCode(json: String) {
        try {
            val w = android.webkit.WebView(context)
            w.setBackgroundColor(0)
            w.isVerticalScrollBarEnabled = false
            w.isHorizontalScrollBarEnabled = false
            w.settings.javaScriptEnabled = true
            w.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(v: android.webkit.WebView, u: String) {
                    v.evaluateJavascript("window.PX_RUN && PX_RUN(" + org.json.JSONObject.quote(json) + ")", null)
                }
            }
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            w.loadUrl("file:///android_asset/studio.html#run")
            wv = w
            postDelayed(stateTick, 300)
        } catch (e: Throwable) {
            wv = null
        }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = wv != null

    override fun onDetachedFromWindow() {
        removeCallbacks(stateTick)
        wv?.destroy()
        wv = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (gl != null || wv != null) return
        val pcl = pc
        if (pcl != null) {
            val s = minOf(width / pcl.w, height / pcl.h)
            if (s >= 1) {
                c.save()
                if (!facingRight) c.scale(-1f, 1f, width / 2f, 0f)
                c.translate((width - pcl.w * s) / 2f, (height - pcl.h * s).toFloat())
                c.scale(s.toFloat(), s.toFloat())
                pcl.draw(Px(c), SystemClock.uptimeMillis() / 1000f, action, talking)
                c.restore()
            }
            postInvalidateDelayed(40)
            return
        }
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
        val cl = closed
        val bm = if (now < blinkUntil && cl != null) cl else base
        val s = min(width / bm.width.toFloat(), height / bm.height.toFloat())
        val w = bm.width * s
        val h = bm.height * s
        val x = (width - w) / 2f
        val y = (height - h) / 2f
        dst.set(x, y, x + w, y + h)
        c.save()
        c.scale(vsx, vsy, width / 2f, height.toFloat())
        if (!facingRight) c.scale(-1f, 1f, width / 2f, 0f)
        c.drawBitmap(bm, null, dst, bp)
        c.restore()
        postInvalidateDelayed(60)
    }
}
