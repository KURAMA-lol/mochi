package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.View
import kotlin.math.min

/**
 * Hina: static sprite of the approved character reference (assets/mascot.b64 = base64 WebP with alpha).
 * No animation for now. The public vars are kept so CompanionService keeps working;
 * only facingRight is used (the sprite is mirrored when she walks left).
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"
    var action = "idle"
    var facingRight = true
    var talking = false
    var pixel = false

    private val sprite: Bitmap? = try {
        val s = ctx.assets.open("mascot.b64").bufferedReader().use { it.readText() }.trim()
        val b = Base64.decode(s, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)
    } catch (ex: Exception) { null }

    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        val bm = sprite ?: return
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
        postInvalidateDelayed(250)
    }
}
