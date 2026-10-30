package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/**
 * Static reference character (Hina) with an eye-blink animation.
 * Two pre-rendered sprites (eyes open / eyes closed) are swapped for a few frames.
 * Public vars are kept because CompanionService sets them.
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"
    var action = "idle"
    var facingRight = true
    var talking = false
    var pixel = false

    private fun load(name: String): Bitmap? = try {
        val s = context.assets.open(name).bufferedReader().use { it.readText() }.trim()
        val b = Base64.decode(s, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (ex: Exception) { null }

    private val open: Bitmap? = load("mascot.b64")
    private val closed: Bitmap? = load("mascot_closed.b64")
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    private var nextBlink = SystemClock.uptimeMillis() + 1500L
    private var blinkUntil = 0L
    private var pendingDouble = false

    override fun onDraw(c: Canvas) {
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
        val bm = if (now < blinkUntil && closed != null) closed else base
        val s = min(width / bm.width.toFloat(), height / bm.height.toFloat())
        val w = bm.width * s
        val h = bm.height * s
        val x = (width - w) / 2f
        val y = (height - h) / 2f
        dst.set(x, y, x + w, y + h)
        c.save()
        if (!facingRight) c.scale(-1f, 1f, width / 2f, 0f)
        c.drawBitmap(bm, null, dst, bp)
        c.restore()
        postInvalidateDelayed(60)
    }
}
