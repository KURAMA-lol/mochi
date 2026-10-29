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
 * Draws Hina in a smooth, full-height Hinata-inspired anime style using Canvas.
 * Logical drawing box is 100 x 130 units with full-body anime proportions.
 */
class ChibiView(ctx: Context) : View(ctx) {
    var mood = "neutral"          // happy excited shy sad angry sleepy smug neutral
    var action = "idle"           // idle walk sit sleep wave drag
    var facingRight = true
    var talking = false
    var pixel = false              // true = pixel-art, false = smooth

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

    private fun drawGirl(t: Float, now: Long) {
        val walking = action == "walk"
        val sitting = action == "sit"
        val sleeping = action == "sleep"
        val waving = action == "wave"
        if (now > nextBlink) { blinkUntil = now + 130; nextBlink = now + 2500 + (now % 2300) }
        val blink = now < blinkUntil
        val bob = if (walking) -abs(sin(t * 9f)) * 1.2f else sin(t * 2.4f) * 0.25f
        val top = 12f + bob
        val sway = if (walking) sin(t * 9f) * 1.5f else 0f

        // Long, straight indigo hair forms a continuous silhouette behind the body.
        beginPath(); moveTo(37, top + 3); cubicTo(36, top - 6, 43, top - 9, 50, top - 8)
        cubicTo(61, top - 8, 66, top - 1, 66, top + 10); cubicTo(69, top + 29, 68 + sway, 57, 65 + sway, 77)
        cubicTo(61, 81, 39, 81, 35 - sway, 76); cubicTo(32, 63, 31, top + 27, 33, top + 11)
        cubicTo(34, top + 6, 35, top + 4, 37, top + 3); closePath(); fillPath(HAIR_D); strokePath(INK, 0.7f)
        beginPath(); moveTo(39, top + 3); cubicTo(36, 25, 37, 55, 39, 74); cubicTo(40, 77, 41, 78, 42, 79); closePath(); fillPath(HAIR_L, 95)
        beginPath(); moveTo(59, top + 2); cubicTo(63, 27, 62, 57, 60, 76); lineTo(58, 79); closePath(); fillPath(HAIR, 150)
        line(43, top + 2, 41, 58, HAIR_L, 0.65f, 150); line(57, top + 2, 59, 58, HAIR_L, 0.65f, 150)

        // Slender navy trousers, thigh bandage, utility pouch and open-toe sandals.
        if (!sleeping) {
            beginPath(); moveTo(40, 76); cubicTo(43, 75, 48, 76, 49, 79); lineTo(48, 98); lineTo(47, 114); lineTo(38, 114); cubicTo(37, 103, 38, 92, 37, 80); closePath(); fillPath(0xFF303958.toInt()); strokePath(INK, 0.65f)
            beginPath(); moveTo(51, 79); cubicTo(53, 76, 59, 76, 61, 79); lineTo(62, 96); lineTo(60, 114); lineTo(51, 114); cubicTo(50, 103, 50, 91, 51, 79); closePath(); fillPath(0xFF303958.toInt()); strokePath(INK, 0.65f)
            line(42, 96, 40, 108, 0xFF4A5273.toInt(), 0.7f, 180); line(56, 96, 58, 108, 0xFF4A5273.toInt(), 0.7f, 180)
            fillRect(34, 84, 40, 92, 0xFF20263D.toInt()); fillRect(34.7f, 84.7f, 39.5f, 86.4f, 0xFFD9D9E2.toInt())
            fillRect(34.7f, 87.4f, 39.5f, 89.1f, 0xFFD9D9E2.toInt()); fillRect(34.7f, 90f, 39.5f, 91.2f, 0xFFD9D9E2.toInt())
            fillRect(31, 85, 36, 96, 0xFF252B40.toInt()); strokeP(INK, 0.65f, 255); cv.drawRect(31f, 85f, 36f, 96f, p)
            fillRect(39, 113, 46, 120, SKIN); fillRect(53, 113, 59, 120, SKIN)
            beginPath(); moveTo(37, 118); cubicTo(40, 117, 45, 117, 47, 119); lineTo(48, 124); cubicTo(47, 127, 37, 127, 35, 124); closePath(); fillPath(0xFF171D30.toInt()); strokePath(INK, 0.7f)
            beginPath(); moveTo(52, 119); cubicTo(54, 117, 59, 117, 61, 119); lineTo(63, 124); cubicTo(62, 127, 52, 127, 50, 124); closePath(); fillPath(0xFF171D30.toInt()); strokePath(INK, 0.7f)
            line(37, 121, 46, 121, 0xFF737B97.toInt(), 1.4f); line(52, 121, 61, 121, 0xFF737B97.toInt(), 1.4f)
            line(39, 119, 42, 123, 0xFF3E4561.toInt(), 1.5f); line(57, 119, 55, 123, 0xFF3E4561.toInt(), 1.5f)
            for (i in 0..2) { fillCircle(39 + i * 2.1f, 124, 0.4f, SKIN); fillCircle(54 + i * 2.1f, 124, 0.4f, SKIN) }
        } else {
            beginPath(); moveTo(39, 78); cubicTo(49, 78, 55, 84, 56, 91); cubicTo(58, 98, 51, 103, 43, 100); lineTo(34, 93); closePath(); fillPath(0xFF303958.toInt()); strokePath(INK, 0.7f)
            beginPath(); moveTo(42, 94); cubicTo(51, 92, 58, 98, 58, 104); lineTo(49, 108); lineTo(36, 101); closePath(); fillPath(0xFF303958.toInt()); strokePath(INK, 0.7f)
            fillOval(38, 101, 48, 106, BOOT); fillOval(49, 103, 60, 108, BOOT)
        }

        // Fitted lavender jacket, pale sleeves, seams and folded hood.
        beginPath(); moveTo(39, 41); cubicTo(34, 42, 32, 47, 32, 52); lineTo(29, 69); cubicTo(30, 74, 34, 77, 39, 78)
        lineTo(61, 78); cubicTo(66, 77, 70, 74, 71, 69); lineTo(68, 52); cubicTo(68, 47, 66, 42, 61, 41); lineTo(56, 39); lineTo(44, 39); closePath(); fillPath(0xFFB7A7D8.toInt()); strokePath(INK, 0.75f)
        beginPath(); moveTo(35, 47); cubicTo(33, 56, 33, 66, 34, 73); lineTo(41, 76); lineTo(43, 47); closePath(); fillPath(0xFFF0EDF4.toInt())
        beginPath(); moveTo(65, 47); cubicTo(67, 56, 67, 66, 66, 73); lineTo(59, 76); lineTo(57, 47); closePath(); fillPath(0xFFECE8F2.toInt())
        beginPath(); moveTo(43, 44); lineTo(50, 49); lineTo(57, 44); lineTo(60, 76); lineTo(40, 76); closePath(); fillPath(0xFF9B88C8.toInt())
        line(50, 49, 50, 77, 0xFF5E527F.toInt(), 0.8f); line(43, 52, 42, 72, 0xFFDCD3EB.toInt(), 0.7f, 210); line(57, 52, 58, 72, 0xFFDCD3EB.toInt(), 0.7f, 210)
        beginPath(); moveTo(39, 40); cubicTo(43, 39, 46, 43, 50, 47); cubicTo(54, 43, 58, 39, 61, 40); lineTo(57, 49); lineTo(50, 54); lineTo(43, 49); closePath(); fillPath(0xFFF5F2F7.toInt()); strokePath(0xFF6E687E.toInt(), 0.6f)
        beginPath(); moveTo(43, 44); lineTo(50, 50); lineTo(57, 44); lineTo(54, 51); lineTo(50, 54); lineTo(46, 51); closePath(); fillPath(0xFFD4C8E8.toInt())
        if (waving) {
            beginPath(); moveTo(38, 44); cubicTo(32, 45, 31, 49, 32, 53); lineTo(27, 43); cubicTo(26, 39, 29, 37, 32, 39); lineTo(43, 48); closePath(); fillPath(0xFFF1EEF5.toInt()); strokePath(INK, 0.7f)
            beginPath(); moveTo(61, 44); cubicTo(67, 45, 68, 50, 66, 55); lineTo(59, 68); lineTo(54, 65); lineTo(61, 51); closePath(); fillPath(0xFFE9E4F0.toInt()); strokePath(INK, 0.7f)
            fillOval(24, 35 + sin(t * 11f) * 1.5f, 30, 42 + sin(t * 11f) * 1.5f, SKIN)
        } else {
            beginPath(); moveTo(36, 43); cubicTo(31, 44, 30, 49, 32, 55); lineTo(42, 72); cubicTo(44, 75, 47, 73, 47, 70); lineTo(41, 51); closePath(); fillPath(0xFFF1EEF5.toInt()); strokePath(INK, 0.7f)
            beginPath(); moveTo(64, 43); cubicTo(69, 44, 70, 49, 68, 55); lineTo(58, 72); cubicTo(56, 75, 53, 73, 53, 70); lineTo(59, 51); closePath(); fillPath(0xFFE9E4F0.toInt()); strokePath(INK, 0.7f)
            line(35, 49, 39, 62, 0xFFD4CEDF.toInt(), 0.8f); line(65, 49, 61, 62, 0xFFD4CEDF.toInt(), 0.8f)
            fillOval(44, 68, 56, 76, SKIN); fillOval(45, 69, 55, 75, 0xFFFFD8C8.toInt())
            line(50, 69, 50, 74, 0xFFE2A995.toInt(), 0.5f); line(47, 72, 49, 74, 0xFFE2A995.toInt(), 0.45f)
        }
        // Dark collar plate and tiny Leaf-inspired emblem.
        fillRect(41, 34, 59, 41, 0xFF24283A.toInt()); fillRect(42, 34, 58, 39, 0xFFBFC1D0.toInt()); strokeP(INK, 0.55f, 255); cv.drawRect(42f, 34f, 58f, 39f, p)
        beginPath(); moveTo(47, 35); cubicTo(44, 36, 44, 38, 47, 38); lineTo(53, 38); cubicTo(56, 38, 56, 36, 53, 35); lineTo(52, 36); lineTo(48, 36); closePath(); fillPath(0xFF44485B.toInt())

        // Small face, pale lavender Byakugan eyes and blunt fringe.
        val hy = top - 1f
        cv.save(); rot(if (sleeping) 4f else if (mood == "shy") 1.2f else 0f, 50, hy + 12)
        fillOval(38, hy + 7, 41, hy + 13, SKIN); fillOval(59, hy + 7, 62, hy + 13, SKIN)
        beginPath(); moveTo(40, hy + 2); cubicTo(43, hy - 2, 57, hy - 2, 60, hy + 2)
        cubicTo(62, hy + 7, 61, hy + 18, 57, hy + 22); cubicTo(54, hy + 25, 46, hy + 25, 43, hy + 22)
        cubicTo(39, hy + 18, 38, hy + 7, 40, hy + 2); closePath(); fillPath(SKIN); strokePath(0xFFB77F7E.toInt(), 0.45f)
        fillOval(40, hy + 14, 44, hy + 17, BLUSH, if (mood == "shy" || mood == "happy") 155 else 75)
        fillOval(56, hy + 14, 60, hy + 17, BLUSH, if (mood == "shy" || mood == "happy") 155 else 75)
        val by = hy + 7
        if (mood == "angry") { line(42, by + 1, 47, by - 0.3f, HAIR_D, 0.8f); line(53, by - 0.3f, 58, by + 1, HAIR_D, 0.8f) }
        else { line(42, by, 47, by - 0.2f, HAIR_D, 0.6f); line(53, by - 0.2f, 58, by, HAIR_D, 0.6f) }
        eye(45f, hy + 12f, blink, if (mood == "shy") -0.5f else 0f); eye(55f, hy + 12f, blink, if (mood == "shy") -0.5f else 0f)
        line(50, hy + 13, 49.5f, hy + 16, 0xFFD99E91.toInt(), 0.35f); mouth(t, hy)
        // Smooth indigo cap and blunt, separated bangs.
        beginPath(); moveTo(38, hy + 8); cubicTo(36, hy + 2, 39, hy - 5, 45, hy - 6)
        cubicTo(51, hy - 8, 58, hy - 5, 61, hy + 1); lineTo(62, hy + 8)
        cubicTo(59, hy + 5, 56, hy + 6, 54, hy + 9); lineTo(52, hy + 6); lineTo(50, hy + 9)
        lineTo(48, hy + 6); lineTo(46, hy + 9); lineTo(44, hy + 6); lineTo(41, hy + 9); closePath(); fillPath(HAIR_D); strokePath(INK, 0.6f)
        line(42, hy - 2, 44, hy, HAIR_L, 0.7f, 180); line(47, hy - 4, 48, hy, HAIR_L, 0.7f, 180)
        line(52, hy - 4, 54, hy, HAIR_L, 0.7f, 180); line(57, hy - 2, 58, hy, HAIR_L, 0.7f, 180)
        // Metal forehead plate and small Leaf-inspired emblem.
        fillRect(43, hy + 0.5f, 57, hy + 4.5f, 0xFF272B3D.toInt()); fillRect(44, hy + 1f, 56, hy + 4f, 0xFFBFC1D0.toInt())
        strokeP(0xFF676A80.toInt(), 0.4f, 255); cv.drawRect(44f, hy + 1f, 56f, hy + 4f, p)
        beginPath(); moveTo(47, hy + 2); cubicTo(45.5f, hy + 2, 45.5f, hy + 3.5f, 47, hy + 3.5f)
        lineTo(52, hy + 3.5f); cubicTo(53.5f, hy + 3.5f, 53.5f, hy + 2, 52, hy + 2)
        lineTo(51, hy + 2.5f); lineTo(48, hy + 2.5f); closePath(); fillPath(0xFF484B60.toInt())
        // Long face-framing locks fall over the shoulders.
        beginPath(); moveTo(39, hy + 5); cubicTo(37, hy + 17, 38, 34, 39 + sway, 51)
        cubicTo(40, 56, 40, 59, 41, 61); cubicTo(40, 47, 41, 30, 43, hy + 8); closePath(); fillPath(HAIR_D); strokePath(INK, 0.5f)
        beginPath(); moveTo(61, hy + 5); cubicTo(63, hy + 18, 62, 35, 61 + sway, 51)
        cubicTo(60, 56, 60, 59, 59, 61); cubicTo(60, 47, 59, 30, 57, hy + 8); closePath(); fillPath(HAIR_D); strokePath(INK, 0.5f)
        line(40, hy + 13, 40, 49, HAIR_L, 0.5f, 145); line(60, hy + 13, 60, 49, HAIR_L, 0.5f, 145)
        cv.restore()
        if (sleeping) { for (i in 0..1) { val ph = (t * 0.6f + i * 0.33f) % 1f; text("z", 68 + ph * 8, top - 4 - ph * 12, 5 + i * 2, 0xFF9F9BC4.toInt(), ((1 - ph) * 220).toInt()) } }
        else if (talking && (mood == "happy" || mood == "excited")) { val ph = (t * 0.9f) % 1f; text(if (mood == "happy") "♪" else "♥", 73 + ph * 3, top - 2 - ph * 8, 6, 0xFFB7A3E8.toInt(), ((1 - ph) * 230).toInt()) }
        p.alpha = 255
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
