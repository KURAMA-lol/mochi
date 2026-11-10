package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Character view. Nana is a 2D "paper doll" built from layers cut out of one illustration
 * (body, head, two forearms). Layers are rotated around their joints for idle / think / wave /
 * talk / drag animations; eyes blink and the mouth opens by drawing on top of the head.
 * Public vars are set by CompanionService.
 */
class ChibiView(ctx: Context) : View(ctx) {
    @Volatile var mood = "neutral"
    @Volatile var action = "idle"
    @Volatile var facingRight = true
    @Volatile var talking = false
    @Volatile var thinking = false
    @Volatile var pixel = false
    @Volatile var yaw = 0f
    @Volatile var vsx = 1f
    @Volatile var vsy = 1f
    @Volatile var vopen = 0f    // vent opening 0..1
    @Volatile var vdy = 0f      // character vertical offset in px (down = into the vent)

    companion object {
        @Volatile var lastError: String = ""
        fun charId(c: Context): String = "nana"
        fun has3d(c: Context): Boolean = true
        // layer size and joints (layer pixel coords)
        private const val LW = 340f
        private const val LH = 500f
        private const val HEAD_X = 172f; private const val HEAD_Y = 176f
        private const val JR = 40f
        private const val EL_X = 63f; private const val ER_X = 284f; private const val EB_Y = 351f
    }

    private fun bmp(n: String): Bitmap? = try {
        context.assets.open("sprite/$n.webp").use { BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) { lastError = (e.message ?: e.toString()).take(160); null }

    private val body: Bitmap? by lazy { bmp("n_body") }
    private val head: Bitmap? by lazy { bmp("n_head") }
    private val armL: Bitmap? by lazy { bmp("n_arml") }
    private val armR: Bitmap? by lazy { bmp("n_armr") }

    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE4DC.toInt() }
    private val lash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A2F57.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND
    }
    private val mouthP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9B2F4A.toInt() }
    private val tongueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2768F.toInt() }
    private val oval = RectF()
    private val joint = Path()

    // eased pose
    private var aL = 0f; private var aR = 0f; private var hR = 0f
    private var bobY = 0f; private var sway = 0f
    private var lookX = 0f
    private var nextBlink = SystemClock.uptimeMillis() + 1500L
    private var blinkUntil = 0L
    private var pokeUntil = 0L

    init { setWillNotDraw(false) }

    fun fallback() { invalidate() }
    fun lookAt(nx: Float, ny: Float) { lookX = nx.coerceIn(-1f, 1f) }
    fun poke() { pokeUntil = SystemClock.uptimeMillis() + 1100L }
    fun release() { }

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

    private fun ease(cur: Float, tgt: Float, k: Float = 0.2f) = cur + (tgt - cur) * k

    override fun onDraw(c: Canvas) {
        val b = body ?: return
        val hd = head ?: return
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val act = action
        val poking = now < pokeUntil
        val think = thinking && act != "drag"

        // ---- pose targets
        var tL = 1.5f * sin(t * 1.8f)
        var tR = -1.5f * sin(t * 1.7f + 1f)
        var tH = 1.2f * sin(t * 1.3f) + lookX * 2f + (yaw / 60f).coerceIn(-1f, 1f) * 2f
        var tBob = 0f
        var tSway = 0f
        var closed = false
        when {
            act == "drag" -> {
                tL = 16f * sin(t * 12f); tR = -16f * sin(t * 12f + 1f); tH = 4f * sin(t * 9f)
                tSway = 3f * sin(t * 9f)
            }
            think -> {
                tR = 142f + 3f * sin(t * 2.4f)       // hand up to the chin
                tL = 3f + 1.5f * sin(t * 1.5f)
                tH = 4f + 1f * sin(t * 1.6f)
                tBob = 0.8f * sin(t * 2.2f)
            }
            poking || act == "wave" -> {
                tR = -62f + 12f * sin(t * 9.5f)
                tH = -3f
                tBob = -2f * abs(sin(t * 9f))
            }
            act == "walk" || act == "run" -> {
                val f = if (act == "run") 13f else 8f
                val amp = if (act == "run") 20f else 12f
                tL = amp * sin(t * f); tR = -amp * sin(t * f)
                tBob = -abs(sin(t * f)) * (if (act == "run") 5f else 3f)
                tSway = 2.2f * sin(t * f)
            }
            act == "sleep" -> {
                tH = 6f + 0.6f * sin(t * 0.9f); tL = 2f; tR = -2f
                tBob = 2f * sin(t * 0.9f)
                closed = true
            }
            act == "sit" -> { tBob = 2.5f }
            act == "sus" -> { tH = -4f; tR = -4f }
            else -> { tBob = 1.2f * sin(t * 2.1f) }
        }
        aL = ease(aL, tL, 0.15f); aR = ease(aR, tR, 0.15f)
        hR = ease(hR, tH.coerceIn(-6f, 6f), 0.12f); bobY = ease(bobY, tBob); sway = ease(sway, tSway)

        // ---- blink
        if (now >= nextBlink && now >= blinkUntil) {
            blinkUntil = now + 130L
            nextBlink = now + Random.nextLong(2200L, 5200L)
        }
        val blink = closed || now < blinkUntil

        // ---- place sprite: bottom-aligned, centred, flipped if she faces left
        val s = min(width / LW, height / LH) * 0.98f
        val ox = (width - LW * s) / 2f
        val oy = height - LH * s
        c.save()
        c.scale(vsx, vsy, width / 2f, height.toFloat())
        if (!facingRight) c.scale(-1f, 1f, width / 2f, 0f)
        c.translate(ox, oy + bobY * s)
        c.scale(s, s)
        c.rotate(sway * 0.5f, LW / 2f, LH)

        c.drawBitmap(b, 0f, 0f, bp)
        armL?.let { c.save(); c.rotate(aL, EL_X, EB_Y); c.drawBitmap(it, 0f, 0f, bp); c.restore() }
        armR?.let { c.save(); c.rotate(aR, ER_X, EB_Y); c.drawBitmap(it, 0f, 0f, bp); c.restore() }
        // round elbow joint: the un-rotated sleeve stays inside a disc, so the bend has no flat seam
        armL?.let { c.save(); joint.reset(); joint.addCircle(EL_X, EB_Y, JR, Path.Direction.CW); c.clipPath(joint); c.drawBitmap(it, 0f, 0f, bp); c.restore() }
        armR?.let { c.save(); joint.reset(); joint.addCircle(ER_X, EB_Y, JR, Path.Direction.CW); c.clipPath(joint); c.drawBitmap(it, 0f, 0f, bp); c.restore() }

        c.save()
        c.translate(0f, 1.2f * sin(t * 2.2f))
        c.rotate(hR, HEAD_X, HEAD_Y)
        c.drawBitmap(hd, 0f, 0f, bp)
        if (blink) {
            oval.set(145f - 17f, 105f - 14f, 145f + 17f, 105f + 14f); c.drawOval(oval, skin)
            oval.set(198f - 20f, 103f - 16f, 198f + 20f, 103f + 16f); c.drawOval(oval, skin)
            c.drawArc(oval.apply { set(145f - 15f, 98f, 145f + 15f, 116f) }, 15f, 150f, false, lash)
            c.drawArc(oval.apply { set(198f - 17f, 96f, 198f + 17f, 114f) }, 15f, 150f, false, lash)
        }
        if (talking) {
            val m = 0.25f + 0.75f * abs(sin(t * 16f))
            oval.set(174f - 7f, 131f - 2f, 174f + 7f, 131f + 2f + 7f * m)
            c.drawOval(oval, mouthP)
            if (m > 0.6f) { oval.set(174f - 4f, 131f + 4f * m, 174f + 4f, 131f + 2f + 7f * m); c.drawOval(oval, tongueP) }
        }
        c.restore()
        c.restore()
        postInvalidateOnAnimation()
    }
}
