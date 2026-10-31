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
        fun has3d(c: Context): Boolean {
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

    init {
        setWillNotDraw(false)
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

    override fun onDraw(c: Canvas) {
        if (gl != null) return
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
