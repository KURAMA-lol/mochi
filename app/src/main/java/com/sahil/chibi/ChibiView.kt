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

/**
 * Draws Mochi: an original chibi girl (pink hair, cat-ear hairband, cream hoodie) with Canvas.
 * Logical drawing box is 100 x 130 units. The drawing code mirrors design/draw.js 1:1.
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"          // happy excited shy sad angry sleepy smug neutral
    var action = "idle"           // idle walk sit sleep wave drag
    var facingRight = true
    var talking = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val ov = RectF()
    private lateinit var cv: Canvas

    private val INK = 0xFF4A3358.toInt()
    private val SKIN = 0xFFFFE8D8.toInt()
    private val SKIN_SH = 0xFFF6CDB8.toInt()
    private val HAIR = 0xFFF6A9C9.toInt()
    private val HAIR_D = 0xFFDE82AD.toInt()
    private val HAIR_L = 0xFFFFD9EA.toInt()
    private val CREAM = 0xFFFFF6EA.toInt()
    private val CREAM_SH = 0xFFEBD8C4.toInt()
    private val PINK = 0xFFFF7FA8.toInt()
    private val LAV = 0xFFB9A4FF.toInt()
    private val IRIS = 0xFF7E63F0.toInt()
    private val IRIS_L = 0xFFC9B6FF.toInt()
    private val BLUSH = 0xFFFF6F95.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val BOOT = 0xFF5A3F6B.toInt()
    private val SOCK = 0xFFE9DEFF.toInt()

    private var nextBlink = 0L
    private var blinkUntil = 0L

    // ---------------------------------------------------------------- drawing helpers
    private fun f(n: Number) = n.toFloat()

    private fun fillP(c: Int, a: Int) { p.style = Paint.Style.FILL; p.color = c; p.alpha = a }
    private fun strokeP(c: Int, w: Number, a: Int) {
        p.style = Paint.Style.STROKE; p.color = c; p.strokeWidth = f(w)
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.alpha = a
    }

    private fun fillOval(l: Number, t: Number, r: Number, b: Number, c: Int, a: Int = 255) {
        ov.set(f(l), f(t), f(r), f(b)); fillP(c, a); cv.drawOval(ov, p)
    }
    private fun strokeOval(l: Number, t: Number, r: Number, b: Number, c: Int, w: Number, a: Int = 255) {
        ov.set(f(l), f(t), f(r), f(b)); strokeP(c, w, a); cv.drawOval(ov, p)
    }
    private fun fillCircle(x: Number, y: Number, r: Number, c: Int, a: Int = 255) {
        fillP(c, a); cv.drawCircle(f(x), f(y), f(r), p)
    }
    private fun strokeCircle(x: Number, y: Number, r: Number, c: Int, w: Number, a: Int = 255) {
        strokeP(c, w, a); cv.drawCircle(f(x), f(y), f(r), p)
    }
    private fun fillRect(l: Number, t: Number, r: Number, b: Number, c: Int, a: Int = 255) {
        fillP(c, a); cv.drawRect(f(l), f(t), f(r), f(b), p)
    }
    private fun line(x1: Number, y1: Number, x2: Number, y2: Number, c: Int, w: Number, a: Int = 255) {
        strokeP(c, w, a); cv.drawLine(f(x1), f(y1), f(x2), f(y2), p)
    }
    private fun arc(l: Number, t: Number, r: Number, b: Number, st: Number, sw: Number,
                    useCenter: Boolean, c: Int, w: Number, a: Int = 255) {
        ov.set(f(l), f(t), f(r), f(b))
        if (f(w) > 0f) strokeP(c, w, a) else fillP(c, a)
        cv.drawArc(ov, f(st), f(sw), useCenter, p)
    }
    private fun beginPath() { path.reset() }
    private fun moveTo(x: Number, y: Number) = path.moveTo(f(x), f(y))
    private fun lineTo(x: Number, y: Number) = path.lineTo(f(x), f(y))
    private fun quadTo(a: Number, b: Number, x: Number, y: Number) = path.quadTo(f(a), f(b), f(x), f(y))
    private fun cubicTo(a: Number, b: Number, c: Number, d: Number, x: Number, y: Number) =
        path.cubicTo(f(a), f(b), f(c), f(d), f(x), f(y))
    private fun closePath() = path.close()
    private fun fillPath(c: Int, a: Int = 255) { fillP(c, a); cv.drawPath(path, p) }
    private fun strokePath(c: Int, w: Number, a: Int = 255) { strokeP(c, w, a); cv.drawPath(path, p) }
    private fun text(s: String, x: Number, y: Number, sz: Number, c: Int, a: Int) {
        fillP(c, a.coerceIn(0, 255)); p.textSize = f(sz); cv.drawText(s, f(x), f(y), p)
    }
    private fun rot(deg: Number, px: Number, py: Number) = cv.rotate(f(deg), f(px), f(py))

    // ---------------------------------------------------------------- view
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val s = min(width / 100f, height / 130f)
        c.save()
        c.translate((width - 100f * s) / 2f, (height - 130f * s) / 2f)
        c.scale(s, s)
        if (!facingRight) c.scale(-1f, 1f, 50f, 0f)
        cv = c
        drawGirl(t, now)
        c.restore()
        postInvalidateDelayed(40)
    }

    private fun drawGirl(t: Float, now: Long) {
        val walking = action == "walk"
        val sitting = action == "sit"
        val sleeping = action == "sleep"
        val dragging = action == "drag"

        if (now > nextBlink) { blinkUntil = now + 130; nextBlink = now + 2500 + (now % 2300) }
        val blink = now < blinkUntil

        val bob = when {
            walking -> -abs(sin(t * 9)) * 3
            sleeping -> sin(t * 1.5f) * 0.8f
            sitting -> sin(t * 2) * 0.8f
            dragging -> sin(t * 12) * 1.5f
            else -> sin(t * 3) * 1.3f
        }
        val hy = 47 + bob + (if (sitting) 14 else if (sleeping) 16 else 0)
        val swing = if (walking) sin(t * 9) * 5 else 0f

        // --- pigtails (behind) ---
        val sway = sin(t * 3) * 4 + (if (walking) sin(t * 9) * 9 else 0f)
        for (side in intArrayOf(-1, 1)) {
            cv.save(); rot(sway * side + side * 6, 50 + side * 28, hy + 4)
            fillOval(50 + side * 31 - 5.5, hy + 4, 50 + side * 31 + 5.5, hy + 34, HAIR_D)
            strokeOval(50 + side * 31 - 5.5, hy + 4, 50 + side * 31 + 5.5, hy + 34, INK, 1.1)
            fillOval(50 + side * 31 - 3.4, hy + 8, 50 + side * 31 - 0.6, hy + 26, HAIR, 190)
            cv.restore()
            fillCircle(50 + side * 29, hy + 4, 3.3, PINK); strokeCircle(50 + side * 29, hy + 4, 3.3, INK, 1)
        }
        // --- back hair ---
        fillOval(17, hy - 30, 83, hy + 34, HAIR_D)

        // --- legs ---
        if (!sitting && !sleeping) {
            for (s in intArrayOf(-1, 1)) {
                val x0 = 44 + (if (s > 0) 12 else 0)
                val x1 = x0 + (if (s > 0) -swing else swing)
                line(x0, hy + 52, x1, 117, SKIN, 5.2)
                line(x0, hy + 60, x1, 117, SOCK, 5.6)
                line(x0, 113, x1, 117, LAV, 5.6)
                fillOval(x1 - 6, 116, x1 + 6, 124, BOOT); fillOval(x1 - 6, 122, x1 + 6, 125, WHITE)
            }
        } else {
            fillOval(32, 116, 46, 125, BOOT); fillOval(54, 116, 68, 125, BOOT)
            line(40, hy + 50, 38, 119, SOCK, 5); line(60, hy + 50, 62, 119, SOCK, 5)
        }

        // --- hoodie body ---
        beginPath(); moveTo(38, hy + 24); lineTo(62, hy + 24)
        cubicTo(68, hy + 34, 72, hy + 46, 71, hy + 55); quadTo(50, hy + 60, 29, hy + 55)
        cubicTo(28, hy + 46, 32, hy + 34, 38, hy + 24); closePath()
        fillPath(CREAM); strokePath(INK, 1.3)
        beginPath(); moveTo(29.3, hy + 51); quadTo(50, hy + 57, 70.7, hy + 51); lineTo(71, hy + 55)
        quadTo(50, hy + 60, 29, hy + 55); closePath()
        fillPath(PINK)
        fillOval(50, hy + 28, 70, hy + 54, CREAM_SH, 120)
        fillCircle(50, hy + 43, 3.1, PINK)
        fillCircle(45.7, hy + 38.6, 1.35, PINK); fillCircle(50, hy + 37.2, 1.35, PINK); fillCircle(54.3, hy + 38.6, 1.35, PINK)
        line(46, hy + 27, 45, hy + 36, PINK, 1.3); line(54, hy + 27, 55, hy + 36, PINK, 1.3)

        // --- arms (sleeves) ---
        val lax: Float; val lay: Float; val rax: Float; val ray: Float
        when {
            dragging -> { lax = 26f; lay = hy + 8; rax = 74f; ray = hy + 8 }
            action == "wave" -> { lax = 31f; lay = hy + 46; rax = 76f; ray = hy + 4 + sin(t * 11) * 6 }
            walking -> { lax = 32f; lay = hy + 44 + swing; rax = 68f; ray = hy + 44 - swing }
            sleeping -> { lax = 35f; lay = hy + 47; rax = 65f; ray = hy + 47 }
            else -> { lax = 32f; lay = hy + 45; rax = 68f; ray = hy + 45 }
        }
        line(38, hy + 30, lax, lay, INK, 8.4); line(62, hy + 30, rax, ray, INK, 8.4)
        line(38, hy + 30, lax, lay, CREAM, 6.6); line(62, hy + 30, rax, ray, CREAM, 6.6)
        fillCircle(lax, lay + 1, 3.2, SKIN); strokeCircle(lax, lay + 1, 3.2, INK, 1)
        fillCircle(rax, ray + 1, 3.2, SKIN); strokeCircle(rax, ray + 1, 3.2, INK, 1)

        // --- head (tilts) ---
        val tilt = (if (sleeping) 8f else if (mood == "shy") 3f else 0f) + sin(t * 2) * 1.1f
        cv.save(); rot(tilt, 50, hy + 28)

        // side locks
        for (side in intArrayOf(-1, 1)) {
            val x = 50 + side * 31
            beginPath(); moveTo(x, hy - 8); quadTo(x + side * 5, hy + 10, x + side * 0.5, hy + 27)
            quadTo(x - side * 5, hy + 15, x - side * 4, hy - 2); closePath()
            fillPath(HAIR_D)
        }
        // face
        fillOval(20, hy - 28, 80, hy + 28, SKIN)
        arc(20, hy - 28, 80, hy + 28, 25, 130, false, SKIN_SH, 5, 140)
        strokeOval(20, hy - 28, 80, hy + 28, INK, 1.3)

        // blush
        val bl = if (mood == "shy" || mood == "happy" || mood == "excited") 190 else 95
        fillOval(24, hy + 11, 36, hy + 19, BLUSH, bl); fillOval(64, hy + 11, 76, hy + 19, BLUSH, bl)
        if (mood == "shy") {
            for (i in 0..2) {
                line(27 + i * 3, hy + 13, 26 + i * 3, hy + 17, WHITE, 0.9, 200)
                line(67 + i * 3, hy + 13, 66 + i * 3, hy + 17, WHITE, 0.9, 200)
            }
        }

        // eyes
        val look = if (walking) 1.5f else if (mood == "shy") -2f else 0f
        eye(37.5f, hy + 5, blink, look)
        eye(62.5f, hy + 5, blink, look)
        // brows
        val by = hy - 9
        if (mood == "sad") {
            line(30, by + 3, 43, by, INK, 1.5); line(57, by, 70, by + 3, INK, 1.5)
        } else if (mood == "angry") {
            line(30, by, 43, by + 3.5, INK, 1.7); line(57, by + 3.5, 70, by, INK, 1.7)
        } else if (mood == "excited") {
            line(31, by + 1, 43, by - 1, INK, 1.3, 200); line(57, by - 1, 69, by + 1, INK, 1.3, 200)
        }
        fillCircle(50, hy + 14.5, 0.9, SKIN_SH)
        mouth(t, hy)

        // hair cap + fringe
        beginPath(); moveTo(19, hy + 8); cubicTo(12, hy - 30, 88, hy - 30, 81, hy + 8)
        quadTo(80, hy + 14, 76, hy + 10); quadTo(74, hy - 6, 69, hy + 3); quadTo(64, hy - 4, 60, hy - 9)
        quadTo(56, hy + 3, 50, hy - 6); quadTo(44, hy + 3, 40, hy - 9); quadTo(36, hy - 4, 31, hy + 3)
        quadTo(26, hy - 6, 24, hy + 10); quadTo(20, hy + 14, 19, hy + 8); closePath()
        fillPath(HAIR); strokePath(INK, 1.3)
        beginPath(); moveTo(27, hy - 6); quadTo(28, hy - 20, 41, hy - 24); quadTo(33, hy - 18, 31, hy - 6); closePath()
        fillPath(HAIR_L, 200)
        beginPath(); moveTo(72, hy - 8); quadTo(72, hy - 16, 66, hy - 21); quadTo(70, hy - 12, 69, hy - 8); closePath()
        fillPath(HAIR_D, 150)

        // cat-ear hairband
        for (side in intArrayOf(-1, 1)) {
            cv.save(); rot(sin(t * 2.2 + side) * (if (talking) 4.0 else 1.5) * side, 50 + side * 18, hy - 24)
            beginPath(); moveTo(50 + side * 9, hy - 25); lineTo(50 + side * 16, hy - 44)
            quadTo(50 + side * 28, hy - 40, 50 + side * 29, hy - 20); closePath()
            fillPath(CREAM); strokePath(INK, 1.3)
            beginPath(); moveTo(50 + side * 13.5, hy - 26); lineTo(50 + side * 18, hy - 38)
            quadTo(50 + side * 25, hy - 35, 50 + side * 25.5, hy - 23); closePath()
            fillPath(PINK, 230)
            cv.restore()
        }
        // hair clip
        fillCircle(66, hy - 14, 2.6, LAV); strokeCircle(66, hy - 14, 2.6, INK, 0.9)
        fillCircle(66, hy - 14, 0.9, WHITE)
        // ahoge
        beginPath(); moveTo(50, hy - 29); quadTo(46, hy - 38, 53, hy - 40)
        strokePath(INK, 3.6); strokePath(HAIR, 2.2)
        cv.restore()

        // --- floating extras ---
        if (sleeping) {
            for (i in 0..2) {
                val ph = (t * 0.6 + i * 0.33) % 1.0
                text("z", 70 + ph * 10, hy - 14 - ph * 26, 8 + i * 3, 0xFF9FB4FF.toInt(), ((1 - ph) * 255).toInt())
            }
        } else if (talking && (mood == "happy" || mood == "excited")) {
            val ph = (t * 0.9) % 1.0
            text(if (mood == "happy") "♥" else "✦", 78 + ph * 6, hy - 22 - ph * 18, 11, PINK, ((1 - ph) * 255).toInt())
        } else if (mood == "shy" || mood == "sad") {
            fillOval(76, hy - 6, 81, hy + 4, 0xFF9FD8FF.toInt(), 210)
        }
        p.alpha = 255
    }

    private fun eye(cx: Float, cy: Float, blink: Boolean, look: Float) {
        if (action == "sleep") { arc(cx - 5.5f, cy - 3, cx + 5.5f, cy + 5, 20, 140, false, INK, 1.9); return }
        if (mood == "happy") { arc(cx - 6, cy - 3, cx + 6, cy + 8, 205, 130, false, INK, 2.3); return }
        val rx = 6.4f
        val ry = if (blink) 1.3f else if (mood == "sleepy") 4.6f else 8.2f
        fillOval(cx - rx, cy - ry, cx + rx, cy + ry, INK)
        if (!blink) {
            val l = look * 0.35f
            fillOval(cx - rx + 1 + l, cy - ry + 1.2f, cx + rx - 1 + l, cy + ry - 0.6f, IRIS)
            fillOval(cx - rx + 1.8f + l, cy - ry * 0.15f, cx + rx - 1.8f + l, cy + ry - 0.8f, IRIS_L, 215)
            fillOval(cx - 2.7f + l, cy - ry * 0.45f, cx + 2.7f + l, cy + ry * 0.35f, INK)
            val big = if (mood == "excited") 2.7f else 2.1f
            fillCircle(cx - 2.1f + look * 0.4f, cy - ry * 0.5f, big, WHITE)
            fillCircle(cx + 2.4f + look * 0.4f, cy + ry * 0.35f, 1.0f, WHITE)
            if (mood == "excited") fillCircle(cx + 2.8f, cy - ry * 0.3f, 0.8f, WHITE)
            arc(cx - rx - 0.4f, cy - ry - 1, cx + rx + 0.4f, cy + ry * 0.6f, 195, 150, false, INK, 2.1)
            val o = if (cx < 50f) -1 else 1
            line(cx + o * rx, cy - ry * 0.55f, cx + o * (rx + 2.4f), cy - ry * 0.95f, INK, 1.4)
        }
        if (mood == "smug" || mood == "sleepy") {
            fillRect(cx - rx - 1.5f, cy - ry - 1.5f, cx + rx + 1.5f, cy - ry * 0.05f, SKIN)
            line(cx - rx - 0.5f, cy - ry * 0.05f, cx + rx + 0.5f, cy - ry * 0.05f, INK, 2.1)
        }
    }

    private fun mouth(t: Float, hy: Float) {
        val my = hy + 19.5f
        val dark = 0xFFB03060.toInt()
        val tongue = 0xFFFF8FA3.toInt()
        when {
            action == "sleep" -> {
                val r = 1.4f + (sin(t * 1.5f) + 1f) * 0.55f
                fillOval(50 - r, my - r, 50 + r, my + r, dark)
            }
            talking -> {
                val o = 1.6f + abs(sin(t * 12)) * 3.2f
                fillOval(46.2, my - 1, 53.8, my - 1 + o * 1.6f, dark)
                fillOval(47.6, my - 1 + o * 0.9f, 52.4, my - 1 + o * 1.55f, tongue)
            }
            mood == "excited" -> {
                arc(43, my - 7, 57, my + 7, 0, 180, true, dark, 0)
                fillOval(47, my + 1.5f, 53, my + 5.5f, tongue)
            }
            mood == "happy" -> arc(43.5, my - 6, 56.5, my + 3, 15, 150, false, INK, 1.9)
            mood == "sad" -> arc(45, my, 55, my + 8, 200, 140, false, INK, 1.8)
            mood == "angry" -> line(45, my + 1, 55, my + 1, INK, 1.9)
            mood == "shy" -> {
                arc(45.5, my - 3, 50, my + 2, 10, 160, false, INK, 1.6)
                arc(50, my - 3, 54.5, my + 2, 10, 160, false, INK, 1.6)
            }
            mood == "smug" -> arc(43, my - 5, 58, my + 3, 10, 110, false, INK, 1.9)
            else -> arc(45.5, my - 4, 54.5, my + 2, 20, 140, false, INK, 1.7)
        }
    }
}
