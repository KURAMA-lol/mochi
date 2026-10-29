package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
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
 * Draws Hina as a hand-built pixel-grid sprite with crisp nearest-neighbour scaling.
 * Logical drawing box is 100 x 130 units; the character is a discrete 24x42 pixel sprite.
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"          // happy excited shy sad angry sleepy smug neutral
    var action = "idle"           // idle walk sit sleep wave drag
    var facingRight = true
    var talking = false
    var pixel = true              // true = pixel-art, false = smooth

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val ov = RectF()
    private lateinit var cv: Canvas

    private val INK = 0xFF4A3358.toInt()
    private val SKIN = 0xFFFFE8D8.toInt()
    private val SKIN_SH = 0xFFF6CDB8.toInt()
    private val HAIR = 0xFF28243F.toInt()
    private val HAIR_D = 0xFF17172C.toInt()
    private val HAIR_L = 0xFF62568E.toInt()
    private val CREAM = 0xFFF1EFF7.toInt()
    private val CREAM_SH = 0xFFD6D0E5.toInt()
    private val PINK = 0xFF9B83D0.toInt()
    private val LAV = 0xFFB7A3E8.toInt()
    private val IRIS = 0xFFBEB2E9.toInt()
    private val IRIS_L = 0xFFEAE4FF.toInt()
    private val BLUSH = 0xFFE9A6C0.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val BOOT = 0xFF29263F.toInt()
    private val SOCK = 0xFFDCD6EB.toInt()

    // pixel-art: draw into a tiny bitmap, then scale up with no smoothing
    private val PW = 64
    private val PH = 84
    private val PS = 0.64f
    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private val bp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    init { p.isAntiAlias = false }

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
        if (!pixel) {
            p.isAntiAlias = true
            val sc = min(width / 100f, height / 130f)
            c.save()
            c.translate((width - 100f * sc) / 2f, (height - 130f * sc) / 2f)
            c.scale(sc, sc)
            if (!facingRight) c.scale(-1f, 1f, 50f, 0f)
            cv = c
            drawGirl(t, now)
            c.restore()
            postInvalidateDelayed(40)
            return
        }
        p.isAntiAlias = false
        if (bmp == null) { bmp = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888); bc = Canvas(bmp!!) }
        val b = bmp!!; val g = bc!!
        b.eraseColor(0)
        g.save(); g.scale(PS, PS)
        cv = g
        drawGirl(t, now)
        g.restore()
        val s = min(width / 100f, height / 130f)
        val dw = PW / PS * s; val dh = PH / PS * s
        val dx = (width - 100f * s) / 2f; val dy = (height - 130f * s) / 2f
        c.save()
        if (!facingRight) c.scale(-1f, 1f, width / 2f, 0f)
        c.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), bp)
        c.restore()
        postInvalidateDelayed(40)
    }

    /**
     * Hina is a hand-built pixel sprite, not vector shapes reduced to pixels.
     * Each character below is one pixel in a 24x42 sprite grid; nearest-neighbour
     * bitmap scaling in onDraw keeps every pixel crisp on high-density screens.
     */
    private fun drawGirl(t: Float, now: Long) {
        val grid = Array(42) { CharArray(24) { '.' } }
        fun row(y: Int, left: Int, right: Int, ch: Char) {
            if (y in grid.indices) for (x in left..right) if (x in 0..23) grid[y][x] = ch
        }
        fun box(x1: Int, y1: Int, x2: Int, y2: Int, ch: Char) {
            for (y in y1..y2) row(y, x1, x2, ch)
        }
        fun px(x: Int, y: Int, ch: Char) { if (x in 0..23 && y in 0..41) grid[y][x] = ch }

        val palette = mapOf(
            'K' to 0xFF171A2B.toInt(), 'D' to 0xFF25253D.toInt(),
            'H' to 0xFF343452.toInt(), 'h' to 0xFF7775B7.toInt(),
            'S' to 0xFFFFD8C8.toInt(), 's' to 0xFFE9AFA6.toInt(),
            'B' to 0xFFF19AA9.toInt(), 'W' to 0xFFFFF8FF.toInt(),
            'I' to 0xFFBEBBEF.toInt(), 'i' to 0xFF77749F.toInt(),
            'L' to 0xFFA99ACF.toInt(), 'V' to 0xFF8976B8.toInt(),
            'C' to 0xFFEAE6F0.toInt(), 'c' to 0xFFD0C9DE.toInt(),
            'N' to 0xFF303751.toInt(), 'n' to 0xFF424967.toInt(),
            'T' to 0xFFE2E0EA.toInt(), 'G' to 0xFFBFC2D2.toInt(),
            'g' to 0xFF55596F.toInt(), 'O' to 0xFF171D30.toInt(),
            'o' to 0xFF646C89.toInt(), 'P' to 0xFFCB9A91.toInt()
        )

        // Hair silhouette: rounded crown, long straight locks and tapered ends.
        val hair = arrayOf(
            8 to 15, 6 to 17, 4 to 19, 3 to 20, 2 to 21, 2 to 21,
            1 to 22, 1 to 22, 1 to 22, 1 to 22, 1 to 22, 1 to 22,
            1 to 22, 1 to 22, 1 to 22, 1 to 22, 1 to 22, 1 to 22,
            2 to 21, 2 to 21, 3 to 20, 3 to 20, 4 to 19, 4 to 19
        )
        for (y in hair.indices) row(y, hair[y].first, hair[y].second, 'K')
        for (y in 1..22) {
            row(y, if (y < 4) 4 else 2, if (y < 4) 19 else 21, 'D')
        }
        // Indigo shading and discrete pixel highlights.
        for (y in 2..21) {
            row(y, 4, 5, 'H'); row(y, 18, 19, 'H')
        }
        row(2, 8, 15, 'H'); row(3, 6, 17, 'h')
        row(4, 5, 7, 'h'); row(4, 9, 11, 'h'); row(4, 14, 16, 'h')
        row(5, 5, 6, 'H'); row(5, 17, 18, 'H')
        row(5, 5, 18, 'D'); row(6, 4, 19, 'D')

        // Face shape inset into the hair; tiny pixel eyes and shy blush.
        row(5, 7, 16, 'S'); row(6, 6, 17, 'S'); row(7, 6, 17, 'S')
        row(8, 6, 17, 'S'); row(9, 6, 17, 'S'); row(10, 6, 17, 'S')
        row(11, 7, 16, 'S'); row(12, 8, 15, 'S'); row(13, 9, 14, 'S')
        row(8, 6, 7, 's'); row(8, 16, 17, 's')
        row(9, 7, 8, 'B'); row(9, 15, 16, 'B')
        row(7, 8, 10, 'K'); row(7, 13, 15, 'K')
        if (mood == "angry") {
            px(8, 7, 'K'); px(9, 6, 'K'); px(14, 6, 'K'); px(15, 7, 'K')
        }
        val blink = now < blinkUntil || action == "sleep"
        if (!blink) {
            row(8, 8, 10, 'W'); row(8, 13, 15, 'W')
            px(9, 8, 'I'); px(10, 8, 'I'); px(13, 8, 'I'); px(14, 8, 'I')
            px(9, 9, 'I'); px(14, 9, 'I')
        } else {
            row(8, 8, 10, 'D'); row(8, 13, 15, 'D')
        }
        row(11, 11, 12, 's')
        if (mood == "happy" || mood == "excited" || mood == "shy") {
            px(10, 12, 'P'); px(11, 12, 'P'); px(12, 12, 'P')
        } else {
            px(11, 12, 's'); px(12, 12, 's')
        }

        // Blunt fringe; no forehead plate, matching Hinata’s neck-protector look.
        row(4, 6, 17, 'D'); row(5, 6, 8, 'D'); row(5, 11, 12, 'D'); row(5, 15, 17, 'D')
        row(5, 8, 10, 'h'); row(5, 13, 14, 'h')
        // Keep the forehead clean: Hinata's protector sits at her neck in this outfit.

        // Long side locks over the shoulders.
        for (y in 10..23) {
            row(y, 2, 4, if (y < 17) 'D' else 'K')
            row(y, 19, 21, if (y < 17) 'D' else 'K')
        }
        for (y in 11..21) { px(3, y, 'H'); px(20, y, 'H') }
        row(22, 3, 5, 'K'); row(22, 18, 20, 'K')
        row(23, 4, 6, 'K'); row(23, 17, 19, 'K')

        // Neck and forehead-protector collar.
        row(14, 9, 14, 'S')
        row(15, 8, 15, 'K'); row(16, 8, 15, 'G')
        row(17, 9, 14, 'g')
        px(10, 16, 'K'); px(11, 16, 'G'); px(12, 16, 'K'); px(13, 16, 'G')

        // Jacket outline, pale sleeves, lavender torso and zipper.
        row(17, 6, 17, 'K'); row(18, 5, 18, 'K'); row(19, 4, 19, 'K')
        row(19, 4, 19, 'K'); row(20, 4, 19, 'K'); row(21, 4, 19, 'K')
        row(22, 4, 19, 'K'); row(23, 5, 18, 'K'); row(24, 5, 18, 'K')
        row(25, 6, 17, 'K'); row(26, 7, 16, 'K')
        row(16, 7, 16, 'C'); row(17, 6, 17, 'C')
        row(18, 5, 7, 'C'); row(19, 5, 7, 'C'); row(20, 5, 7, 'C')
        row(21, 6, 8, 'C'); row(22, 7, 9, 'C'); row(23, 8, 10, 'C')
        row(18, 16, 18, 'C'); row(19, 16, 18, 'C'); row(20, 16, 18, 'C')
        row(21, 15, 17, 'C'); row(22, 14, 16, 'C'); row(23, 13, 15, 'C')
        row(17, 8, 15, 'L'); row(18, 8, 15, 'V'); row(19, 8, 15, 'V')
        row(20, 8, 15, 'V'); row(21, 9, 14, 'V'); row(22, 9, 14, 'V')
        row(23, 9, 14, 'L'); row(24, 7, 16, 'L'); row(25, 7, 16, 'V')
        row(26, 8, 15, 'V')
        for (y in 17..25) px(11, y, if (y % 2 == 0) 'g' else 'G')
        // White folded hood and dark collar details.
        row(16, 8, 10, 'C'); row(16, 13, 15, 'C')
        row(17, 9, 10, 'c'); row(17, 13, 14, 'c')
        row(18, 10, 13, 'K'); row(19, 10, 13, 'G')

        // Hands held together in Hinata's shy idle pose.
        row(24, 10, 12, 'S'); row(25, 10, 13, 'S')
        px(10, 24, 'P'); px(13, 24, 'P'); px(11, 25, 's'); px(12, 25, 's')

        // Navy shinobi pants, pale thigh wraps and utility pouch.
        row(27, 7, 16, 'K'); row(28, 7, 16, 'N'); row(29, 7, 16, 'N')
        row(30, 7, 16, 'N'); row(31, 7, 16, 'N'); row(32, 7, 16, 'N')
        row(33, 7, 16, 'N'); row(34, 7, 16, 'N'); row(35, 7, 16, 'N')
        row(36, 7, 16, 'K'); row(37, 8, 15, 'K')
        row(28, 8, 10, 'n'); row(28, 13, 15, 'n')
        row(31, 7, 10, 'T'); row(32, 7, 10, 'T'); row(33, 7, 10, 'T')
        row(31, 8, 9, 'c'); row(33, 8, 9, 'c')
        row(29, 5, 7, 'K'); row(30, 5, 7, 'D'); row(31, 5, 7, 'D')
        row(32, 5, 7, 'K'); row(33, 5, 7, 'D'); row(34, 5, 7, 'K')
        px(6, 30, 'g'); px(6, 32, 'g')
        row(37, 8, 10, 'S'); row(37, 13, 15, 'S'); row(38, 8, 10, 'S'); row(38, 13, 15, 'S')
        // Open-toe ninja sandals, with visible toe pixels.
        row(39, 7, 11, 'K'); row(39, 12, 16, 'K')
        row(40, 6, 11, 'O'); row(40, 12, 17, 'O')
        row(41, 7, 10, 'O'); row(41, 13, 16, 'O')
        row(40, 8, 9, 'S'); row(40, 14, 15, 'S')
        px(10, 40, 'S'); px(16, 40, 'S')

        // Walk cycle alternates pixel feet; waving raises one blocky sleeve/hand.
        if (action == "walk") {
            val phase = (t * 8f).toInt() % 2
            if (phase == 0) { row(39, 6, 10, '.'); row(40, 5, 10, 'O'); row(41, 6, 10, 'O') }
            else { row(39, 13, 17, '.'); row(40, 13, 18, 'O'); row(41, 14, 17, 'O') }
        }
        if (action == "wave") {
            row(17, 16, 18, 'C'); row(16, 17, 19, 'C'); row(15, 18, 20, 'S')
            row(14, 19, 20, 'S'); px(20, 13, 'S'); px(21, 14, 'S')
        }
        if (action == "sleep") {
            row(8, 8, 10, 'D'); row(8, 13, 15, 'D')
            px(10, 12, 's'); px(11, 12, 's')
        }

        val bob = if (action == "walk") abs(sin(t * 8f)) * 1.5f else sin(t * 2.4f) * 0.4f
        val cell = 3f
        val ox = 14f
        val oy = 2f + bob
        val pixelPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false; isFilterBitmap = false }
        for (y in grid.indices) {
            for (x in grid[y].indices) {
                val ch = grid[y][x]
                val color = palette[ch] ?: continue
                pixelPaint.color = color
                cv.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, pixelPaint)
            }
        }
        if (action == "sleep") {
            text("z", 78, 12 - bob, 6, 0xFFCBC6F4.toInt(), 220)
            text("z", 84, 5 - bob, 8, 0xFFCBC6F4.toInt(), 180)
        } else if (talking) {
            val ph = (t * 0.8f) % 1f
            text("♪", 77 + ph * 4, 12 - ph * 7 - bob, 6, 0xFFD9C8FF.toInt(), ((1 - ph) * 230).toInt())
        }
    }

    private fun eye(cx: Float, cy: Float, blink: Boolean, look: Float) {
        if (action == "sleep") { line(cx - 3.8f, cy, cx + 3.8f, cy + 0.2f, INK, 1.15f); return }
        val rx = 3.7f
        val ry = if (blink) 0.65f else 4.4f
        fillOval(cx - rx, cy - ry, cx + rx, cy + ry, 0xFFFFFDFE.toInt())
        if (!blink) {
            fillOval(cx - 2.6f + look, cy - 2.7f, cx + 2.6f + look, cy + 3.1f, 0xFFBDBBE8.toInt())
            fillOval(cx - 1.45f + look, cy - 1.8f, cx + 1.45f + look, cy + 2.6f, 0xFF77779F.toInt())
            fillOval(cx - 0.85f + look, cy - 1.1f, cx + 0.85f + look, cy + 2.1f, 0xFF37354C.toInt())
            fillCircle(cx - 1.05f + look, cy - 1.8f, 0.95f, WHITE)
            fillCircle(cx + 1.2f + look, cy + 0.5f, 0.42f, 0xFFDAD9F6.toInt())
        }
        strokeP(INK, 0.85f, 255); cv.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), p)
        line(cx - 3.2f, cy - ry + 0.2f, cx - 1.5f, cy - ry - 0.8f, HAIR_D, 0.75f)
        line(cx + 1.2f, cy - ry - 0.65f, cx + 3.1f, cy - ry + 0.1f, HAIR_D, 0.65f)
    }

    private fun mouth(t: Float, hy: Float) {
        val my = hy + 18.5f
        val dark = 0xFF9C626B.toInt()
        when {
            action == "sleep" -> line(48.5f, my, 51.5f, my, dark, 0.7f)
            talking -> { val o = 0.7f + abs(sin(t * 12f)) * 1.1f; fillOval(48.5f, my - 0.5f, 51.5f, my + o, dark); if (o > 1.2f) fillOval(49.2f, my + 0.3f, 50.8f, my + o, 0xFFFFB2B8.toInt()) }
            mood == "excited" -> { fillOval(47.5f, my - 1.2f, 52.5f, my + 2.6f, dark); fillOval(48.5f, my + 0.8f, 51.5f, my + 2.1f, 0xFFFFB2B8.toInt()) }
            mood == "happy" -> arc(47.5f, my - 1f, 52.5f, my + 2f, 15, 150, false, INK, 0.8f)
            mood == "sad" -> arc(48f, my + 0.2f, 52f, my + 3.5f, 195, 145, false, INK, 0.75f)
            mood == "angry" -> line(48.2f, my + 1f, 51.8f, my + 1f, INK, 0.8f)
            mood == "shy" -> { arc(48f, my - 0.4f, 50f, my + 1.6f, 10, 150, false, INK, 0.7f); arc(50f, my - 0.4f, 52f, my + 1.6f, 10, 150, false, INK, 0.7f) }
            else -> arc(48.5f, my - 0.2f, 51.5f, my + 1.8f, 15, 150, false, INK, 0.7f)
        }
    }


}
