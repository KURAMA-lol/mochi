package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Draws the chibi girl with Canvas. Logical drawing box is 100 x 130 units. */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"          // happy excited shy sad angry sleepy smug neutral
    var action = "idle"           // idle walk sit sleep wave drag
    var facingRight = true
    var talking = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    private val skin = 0xFFFFE3D1.toInt()
    private val hair = 0xFF8E7CFF.toInt()
    private val hairDark = 0xFF6F5BE0.toInt()
    private val dress = 0xFFFF6F91.toInt()
    private val ink = 0xFF3B2A5E.toInt()
    private val blushC = 0xFFFF8FA3.toInt()

    private var nextBlink = 0L
    private var blinkUntil = 0L

    private fun fill(color: Int, alpha: Int = 255): Paint {
        p.style = Paint.Style.FILL; p.color = color; p.alpha = alpha; return p
    }

    private fun line(color: Int, w: Float): Paint {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND; p.alpha = 255; return p
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val s = min(width / 100f, height / 130f)
        c.save()
        c.translate((width - 100f * s) / 2f, (height - 130f * s) / 2f)
        c.scale(s, s)
        if (!facingRight) c.scale(-1f, 1f, 50f, 0f)
        drawGirl(c, t, now)
        c.restore()
        postInvalidateDelayed(40)
    }

    private fun drawGirl(c: Canvas, t: Float, now: Long) {
        val walking = action == "walk"
        val sitting = action == "sit"
        val sleeping = action == "sleep"
        val dragging = action == "drag"

        // blink scheduling
        if (now > nextBlink) { blinkUntil = now + 130; nextBlink = now + 2500 + (now % 2300) }
        val blink = now < blinkUntil

        val bob = when {
            walking -> -abs(sin(t * 9f)) * 3f
            sleeping -> sin(t * 1.5f) * 0.8f
            sitting -> sin(t * 2f) * 0.8f
            dragging -> sin(t * 12f) * 1.5f
            else -> sin(t * 3f) * 1.4f
        }
        val hy = 48f + bob + (if (sitting) 14f else if (sleeping) 16f else 0f)
        val swing = if (walking) sin(t * 9f) * 5f else 0f

        // twin tails (behind everything)
        val tailAng = sin(t * 3f) * 4f + (if (walking) sin(t * 9f) * 9f else 0f)
        for (side in intArrayOf(-1, 1)) {
            c.save()
            c.rotate(tailAng * side, 50f + side * 22f, hy - 14f)
            r.set(50f + side * 28f - 8f, hy - 2f, 50f + side * 28f + 8f, hy + 40f)
            c.drawOval(r, fill(hairDark))
            c.restore()
        }
        // back hair
        c.drawCircle(50f, hy - 2f, 33f, fill(hairDark))

        // legs
        if (!sitting && !sleeping) {
            c.drawLine(44f, hy + 54f, 44f + swing, 122f, line(skin, 5f))
            c.drawLine(56f, hy + 54f, 56f - swing, 122f, line(skin, 5f))
            r.set(39f + swing, 120f, 49f + swing, 126f); c.drawOval(r, fill(ink))
            r.set(51f - swing, 120f, 61f - swing, 126f); c.drawOval(r, fill(ink))
        } else {
            r.set(34f, 118f, 46f, 125f); c.drawOval(r, fill(ink))
            r.set(54f, 118f, 66f, 125f); c.drawOval(r, fill(ink))
        }

        // dress
        path.reset()
        path.moveTo(40f, hy + 27f); path.lineTo(60f, hy + 27f)
        path.lineTo(69f, hy + 56f); path.lineTo(31f, hy + 56f); path.close()
        c.drawPath(path, fill(dress))
        path.reset()
        path.moveTo(44f, hy + 27f); path.lineTo(56f, hy + 27f); path.lineTo(50f, hy + 36f); path.close()
        c.drawPath(path, fill(0xFFFFFFFF.toInt()))

        // arms
        val lax: Float; val lay: Float; val rax: Float; val ray: Float
        when {
            dragging -> { lax = 28f; lay = hy + 10f; rax = 72f; ray = hy + 10f }
            action == "wave" -> { lax = 33f; lay = hy + 46f; rax = 74f; ray = hy + 6f + sin(t * 11f) * 6f }
            walking -> { lax = 33f; lay = hy + 44f + swing; rax = 67f; ray = hy + 44f - swing }
            sleeping -> { lax = 36f; lay = hy + 48f; rax = 64f; ray = hy + 48f }
            else -> { lax = 33f; lay = hy + 45f; rax = 67f; ray = hy + 45f }
        }
        c.drawLine(40f, hy + 31f, lax, lay, line(skin, 5f))
        c.drawLine(60f, hy + 31f, rax, ray, line(skin, 5f))

        // ---- head group (can tilt) ----
        val tilt = (if (sleeping) 8f else if (mood == "shy") 3f else 0f) + sin(t * 2f) * 1.2f
        c.save()
        c.rotate(tilt, 50f, hy + 30f)

        c.drawCircle(50f, hy, 30f, fill(skin))

        // blush
        val blushA = if (mood == "shy" || mood == "happy" || mood == "excited") 170 else 80
        r.set(26f, hy + 7f, 38f, hy + 14f); c.drawOval(r, fill(blushC, blushA))
        r.set(62f, hy + 7f, 74f, hy + 14f); c.drawOval(r, fill(blushC, blushA))

        // eyes
        val look = if (walking) 1.5f else if (mood == "shy") -2f else 0f
        drawEye(c, 38f, hy + 4f, blink, look, t)
        drawEye(c, 62f, hy + 4f, blink, look, t)

        // brows
        val by = hy - 8f
        if (mood == "sad") {
            c.drawLine(31f, by + 3f, 43f, by, line(ink, 1.6f)); c.drawLine(57f, by, 69f, by + 3f, line(ink, 1.6f))
        } else if (mood == "angry") {
            c.drawLine(31f, by, 43f, by + 3.5f, line(ink, 1.8f)); c.drawLine(57f, by + 3.5f, 69f, by, line(ink, 1.8f))
        }

        drawMouth(c, t, hy)

        // bangs
        r.set(20f, hy - 30f, 80f, hy + 30f)
        c.drawArc(r, 180f, 180f, true, fill(hair))
        path.reset()
        path.moveTo(21f, hy - 2f); path.lineTo(30f, hy + 12f); path.lineTo(38f, hy - 4f)
        path.lineTo(48f, hy + 14f); path.lineTo(58f, hy - 4f); path.lineTo(68f, hy + 12f)
        path.lineTo(79f, hy - 2f); path.lineTo(79f, hy - 16f); path.lineTo(21f, hy - 16f); path.close()
        c.drawPath(path, fill(hair))

        // ribbons
        c.drawCircle(27f, hy - 16f, 4.5f, fill(dress)); c.drawCircle(27f, hy - 16f, 1.5f, fill(0xFFFFFFFF.toInt()))
        c.drawCircle(73f, hy - 16f, 4.5f, fill(dress)); c.drawCircle(73f, hy - 16f, 1.5f, fill(0xFFFFFFFF.toInt()))

        // ahoge
        path.reset()
        path.moveTo(50f, hy - 31f); path.quadTo(43f, hy - 43f, 53f, hy - 46f)
        c.drawPath(path, line(hair, 2.6f))

        c.restore()

        // floating extras
        p.style = Paint.Style.FILL
        if (sleeping) {
            for (i in 0..2) {
                val ph = (t * 0.6f + i * 0.33f) % 1f
                p.color = 0xFF9FB4FF.toInt(); p.alpha = ((1f - ph) * 255).toInt(); p.textSize = 8f + i * 3f
                c.drawText("z", 70f + ph * 10f, hy - 14f - ph * 26f, p)
            }
        } else if (talking && (mood == "happy" || mood == "excited")) {
            val ph = (t * 0.9f) % 1f
            p.color = 0xFFFF6F91.toInt(); p.alpha = ((1f - ph) * 255).toInt(); p.textSize = 11f
            c.drawText(if (mood == "happy") "♥" else "✦", 78f + ph * 6f, hy - 22f - ph * 18f, p)
        } else if (mood == "shy" || mood == "sad") {
            r.set(74f, hy - 8f, 79f, hy + 2f); c.drawOval(r, fill(0xFF9FD8FF.toInt(), 200))
        }
        p.alpha = 255
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, blink: Boolean, look: Float, t: Float) {
        if (action == "sleep") {
            r.set(cx - 5f, cy - 3f, cx + 5f, cy + 4f)
            c.drawArc(r, 20f, 140f, false, line(ink, 1.8f)); return
        }
        if (mood == "happy") {
            r.set(cx - 5.5f, cy - 3f, cx + 5.5f, cy + 7f)
            c.drawArc(r, 205f, 130f, false, line(ink, 2f)); return
        }
        val h = if (blink || mood == "sleepy") (if (blink) 1.3f else 3.4f) else 6.5f
        r.set(cx - 5f, cy - h, cx + 5f, cy + h); c.drawOval(r, fill(ink))
        if (!blink) {
            r.set(cx - 3.7f + look * 0.3f, cy - h * 0.75f + 1f, cx + 3.7f + look * 0.3f, cy + h * 0.85f)
            c.drawOval(r, fill(0xFF6C8CFF.toInt()))
            val hl = if (mood == "excited") 2.6f else 1.9f
            c.drawCircle(cx - 1.5f + look * 0.4f, cy - h * 0.4f, hl, fill(0xFFFFFFFF.toInt()))
            c.drawCircle(cx + 1.8f + look * 0.4f, cy + h * 0.4f, 0.9f, fill(0xFFFFFFFF.toInt()))
        }
        if (mood == "smug" || mood == "sleepy") {
            // heavy eyelid
            r.set(cx - 6f, cy - h - 1f, cx + 6f, cy - h * 0.1f)
            c.drawRect(r, fill(skin))
            c.drawLine(cx - 5.5f, cy - h * 0.1f, cx + 5.5f, cy - h * 0.1f, line(ink, 1.8f))
        }
    }

    private fun drawMouth(c: Canvas, t: Float, hy: Float) {
        val my = hy + 17f
        val dark = 0xFFB03060.toInt()
        when {
            action == "sleep" -> {
                val rr = 1.5f + (sin(t * 1.5f) + 1f) * 0.5f
                r.set(50f - rr, my - rr, 50f + rr, my + rr); c.drawOval(r, fill(dark))
            }
            talking -> {
                val open = 2f + abs(sin(t * 12f)) * 3.2f
                r.set(46.5f, my - 1f, 53.5f, my - 1f + open * 1.6f); c.drawOval(r, fill(dark))
            }
            mood == "excited" -> {
                r.set(43f, my - 7f, 57f, my + 7f); c.drawArc(r, 0f, 180f, true, fill(dark))
            }
            mood == "happy" -> { r.set(44f, my - 6f, 56f, my + 3f); c.drawArc(r, 20f, 140f, false, line(ink, 1.8f)) }
            mood == "sad" -> { r.set(45f, my, 55f, my + 8f); c.drawArc(r, 200f, 140f, false, line(ink, 1.8f)) }
            mood == "angry" -> c.drawLine(45f, my + 1f, 55f, my + 1f, line(ink, 1.8f))
            mood == "shy" -> { r.set(46f, my - 3f, 54f, my + 2f); c.drawArc(r, 20f, 140f, false, line(ink, 1.6f)) }
            mood == "smug" -> { r.set(44f, my - 5f, 58f, my + 3f); c.drawArc(r, 10f, 110f, false, line(ink, 1.8f)) }
            else -> { r.set(46f, my - 4f, 54f, my + 2f); c.drawArc(r, 20f, 140f, false, line(ink, 1.6f)) }
        }
    }
}
