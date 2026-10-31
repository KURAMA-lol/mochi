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

    companion object {
        @Volatile var lastError: String = ""
        fun modelFile(c: Context) = File(c.filesDir, "model.vrm")
        fun has3d(c: Context) = modelFile(c).exists()
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
