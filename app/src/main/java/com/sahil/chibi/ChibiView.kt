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
 * Draws Hina as the approved lavender phone mascot with smooth anime-inspired linework.
 * Logical drawing box is 100 x 130 units.
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

    /**
     * Hina is a hand-built pixel sprite, not vector shapes reduced to pixels.
     * Each character below is one pixel in a 24x42 sprite grid; nearest-neighbour
     * bitmap scaling in onDraw keeps every pixel crisp on high-density screens.
     */

    // Hina uses the approved lavender phone-mascot design: bobbed violet hair,
    // luminous purple eyes, pearl hoodie dress, lilac sleeves, wing antennae and sneakers.
    private fun drawGirl(t: Float, now: Long) {
        val bob = when (action) {
            "walk" -> abs(sin(t * 8f)) * 1.4f
            "wave" -> sin(t * 4f) * 0.7f
            "sleep" -> 1.4f
            else -> sin(t * 2.4f) * 0.8f
        }
        cv.save()
        cv.translate(0f, bob)

        val hair = 0xFF38294D.toInt()
        val hairShade = 0xFF241D37.toInt()
        val hairLight = 0xFF7657A7.toInt()
        val pearl = 0xFFF8F6FF.toInt()
        val lilac = 0xFFCAB4F0.toInt()
        val lilacDeep = 0xFF9B7ACB.toInt()
        val violet = 0xFF9D65ED.toInt()
        val skin = 0xFFFFDCCF.toInt()
        val coral = 0xFFFF8B72.toInt()
        val blue = 0xFFBCE8FF.toInt()
        val outline = 0xFF514064.toInt()

        // Translucent wing/antenna ornaments behind the silhouette.
        fillOval(8, 43, 28, 53, blue, 150)
        strokeOval(8, 43, 28, 53, 0xFF9CCEF3.toInt(), 0.8f, 220)
        line(10, 48, 25, 46, 0xFFFFFFFF.toInt(), 0.7f, 210)
        fillOval(72, 42, 92, 52, blue, 145)
        strokeOval(72, 42, 92, 52, 0xFF9CCEF3.toInt(), 0.8f, 220)
        line(75, 47, 90, 45, 0xFFFFFFFF.toInt(), 0.7f, 210)
        // Small antenna tips.
        line(20, 45, 13, 34, outline, 0.8f)
        fillCircle(12.5f, 33.5f, 1.5f, violet)
        line(79, 44, 86, 35, outline, 0.8f)
        fillCircle(86.5f, 34.5f, 1.5f, violet)

        // Back-mounted phone device, visible over the left shoulder.
        beginPath(); moveTo(21, 43); lineTo(29, 41); lineTo(34, 53)
        lineTo(27, 56); closePath(); fillPath(outline)
        beginPath(); moveTo(22, 43.5f); lineTo(28.5f, 42); lineTo(32.5f, 52.5f)
        lineTo(27.5f, 54.5f); closePath(); fillPath(0xFFD9D9E8.toInt())
        beginPath(); moveTo(23, 44); lineTo(28, 43); lineTo(31, 50)
        lineTo(26.5f, 52); closePath(); fillPath(0xFF262435.toInt())
        fillCircle(27.5f, 53.2f, 0.6f, coral)

        // Legs behind the hoodie dress.
        beginPath(); moveTo(38, 78); quadTo(34, 88, 31, 98)
        lineTo(37, 100); lineTo(47, 84); lineTo(51, 79); closePath()
        fillPath(skin); strokePath(outline, 0.65f)
        beginPath(); moveTo(54, 78); quadTo(58, 89, 63, 99)
        lineTo(70, 97); lineTo(66, 83); lineTo(64, 77); closePath()
        fillPath(skin); strokePath(outline, 0.65f)
        // Socks and chunky lavender sneakers.
        fillPath(pearl); beginPath(); moveTo(31, 95); lineTo(37, 96)
        lineTo(36, 102); lineTo(30, 102); closePath(); fillPath(pearl)
        beginPath(); moveTo(63, 94); lineTo(69, 94); lineTo(72, 101)
        lineTo(65, 102); closePath(); fillPath(pearl)
        beginPath(); moveTo(29, 100); quadTo(23, 101, 23, 106)
        quadTo(24, 110, 34, 109); lineTo(41, 107); lineTo(37, 101); closePath()
        fillPath(lilac); strokePath(outline, 0.75f)
        beginPath(); moveTo(62, 100); quadTo(61, 105, 65, 109)
        lineTo(78, 109); quadTo(82, 107, 78, 103); lineTo(71, 99); closePath()
        fillPath(lilac); strokePath(outline, 0.75f)
        line(27, 105, 37, 105, pearl, 1.4f); line(66, 105, 77, 105, pearl, 1.4f)
        line(30, 102, 35, 106, lilacDeep, 0.6f); line(68, 102, 73, 106, lilacDeep, 0.6f)
        fillOval(23, 108, 41, 110, pearl); fillOval(63, 108, 81, 110, pearl)

        // Hoodie dress silhouette with soft rounded puff sleeves.
        beginPath(); moveTo(39, 43); quadTo(32, 44, 29, 49)
        lineTo(23, 61); quadTo(22, 66, 27, 68)
        lineTo(34, 64); lineTo(32, 77)
        quadTo(49, 83, 68, 77); lineTo(66, 63)
        lineTo(73, 68); quadTo(78, 66, 76, 61)
        lineTo(70, 49); quadTo(66, 44, 59, 43); closePath()
        fillPath(pearl); strokePath(outline, 0.8f)
        // Lilac side panels and rounded sleeve shading.
        beginPath(); moveTo(34, 48); quadTo(28, 53, 27, 62)
        lineTo(33, 64); lineTo(39, 50); closePath(); fillPath(lilac)
        beginPath(); moveTo(64, 48); quadTo(71, 54, 72, 62)
        lineTo(66, 65); lineTo(60, 50); closePath(); fillPath(lilac)
        fillOval(22, 59, 33, 68, lilac); fillOval(66, 59, 77, 68, lilac)
        strokeOval(22, 59, 33, 68, outline, 0.65f)
        strokeOval(66, 59, 77, 68, outline, 0.65f)
        // Dress hem, pale trim and central panel.
        beginPath(); moveTo(34, 62); quadTo(49, 68, 65, 62)
        lineTo(69, 77); quadTo(50, 84, 31, 77); closePath()
        fillPath(0xFFEDE5FB.toInt())
        beginPath(); moveTo(36, 63); quadTo(50, 69, 63, 63)
        lineTo(66, 76); quadTo(50, 81, 34, 76); closePath()
        fillPath(pearl); strokePath(0xFFB7A1D8.toInt(), 0.55f)
        line(35, 77, 65, 77, lilacDeep, 0.7f)
        // Hood behind head and dark inner collar.
        beginPath(); moveTo(37, 42); quadTo(31, 43, 32, 49)
        quadTo(34, 54, 41, 55); lineTo(46, 49); closePath()
        fillPath(0xFFE7E1F5.toInt()); strokePath(outline, 0.7f)
        beginPath(); moveTo(58, 42); quadTo(66, 43, 65, 49)
        quadTo(62, 54, 56, 55); lineTo(51, 49); closePath()
        fillPath(0xFFE7E1F5.toInt()); strokePath(outline, 0.7f)
        beginPath(); moveTo(40, 44); lineTo(59, 44); lineTo(55, 51)
        lineTo(45, 51); closePath(); fillPath(hairShade)
        // Hood drawstrings and coral chest emblem.
        line(43, 50, 42, 61, 0xFFB5A2D2.toInt(), 0.65f)
        line(56, 50, 57, 61, 0xFFB5A2D2.toInt(), 0.65f)
        fillCircle(42, 61, 1.1f, lilacDeep); fillCircle(57, 61, 1.1f, lilacDeep)
        fillCircle(50, 56, 3.2f, coral); strokeCircle(50, 56, 3.2f, 0xFFE36E61.toInt(), 0.55f)
        fillCircle(49, 55, 0.9f, 0xFFFFD6A0.toInt())

        // Arms and hands, slightly open welcoming pose.
        beginPath(); moveTo(29, 49); quadTo(24, 51, 21, 58)
        lineTo(17, 63); quadTo(16, 65, 18, 66)
        lineTo(24, 62); lineTo(31, 55); closePath()
        fillPath(skin); strokePath(outline, 0.65f)
        beginPath(); moveTo(70, 49); quadTo(76, 51, 78, 58)
        lineTo(83, 62); quadTo(85, 64, 83, 66)
        lineTo(77, 63); lineTo(68, 55); closePath()
        fillPath(skin); strokePath(outline, 0.65f)
        // Puffy cuffs.
        fillOval(23, 58, 32, 65, lilac); strokeOval(23, 58, 32, 65, outline, 0.6f)
        fillOval(67, 58, 76, 65, lilac); strokeOval(67, 58, 76, 65, outline, 0.6f)
        // Pose-specific wave arm and tiny phone-peek gesture.
        if (action == "wave") {
            line(73, 57, 78, 47, skin, 3.2f)
            line(78, 47, 80, 43, skin, 2.0f)
            line(78, 47, 83, 45, skin, 1.2f)
        }

        // Face, ears, and rounded bob silhouette.
        fillOval(34, 17, 39, 26, skin); fillOval(61, 17, 66, 26, skin)
        fillOval(33, 8, 67, 45, hairShade)
        fillOval(34, 7, 66, 43, hair)
        fillOval(36, 8, 64, 40, skin)
        // Face blush and nose.
        fillOval(37, 27, 44, 31, 0xFFFFB6C4.toInt(), 100)
        fillOval(56, 27, 63, 31, 0xFFFFB6C4.toInt(), 100)
        fillCircle(50, 29, 0.45f, 0xFFE8A99E.toInt())
        // Large purple eyes with layered iris and highlights.
        val sleepy = action == "sleep" || mood == "sleepy"
        if (sleepy) {
            line(39, 25, 46, 25, hairShade, 1.1f)
            line(54, 25, 61, 25, hairShade, 1.1f)
        } else {
            fillOval(38, 21, 47, 29, 0xFF342446.toInt())
            fillOval(53, 21, 62, 29, 0xFF342446.toInt())
            fillOval(39, 21.5f, 46, 28, violet)
            fillOval(54, 21.5f, 61, 28, violet)
            fillOval(41, 23, 44, 27, 0xFFCB9CFF.toInt())
            fillOval(56, 23, 59, 27, 0xFFCB9CFF.toInt())
            fillCircle(42, 22.5f, 1.2f, pearl); fillCircle(57, 22.5f, 1.2f, pearl)
            fillCircle(44, 26, 0.65f, 0xFFFFD9FF.toInt())
            fillCircle(59, 26, 0.65f, 0xFFFFD9FF.toInt())
            line(38, 22, 40, 20, hairShade, 0.7f)
            line(60, 20, 62, 22, hairShade, 0.7f)
        }
        if (mood == "happy" || mood == "excited") {
            arc(45, 29, 55, 34, 15, 150, false, 0xFFB85C75.toInt(), 0.8f)
        } else if (mood == "sad" || mood == "shy") {
            line(48, 32, 52, 32, 0xFFB85C75.toInt(), 0.7f)
        } else {
            line(48.5f, 32, 51.5f, 32, 0xFFB85C75.toInt(), 0.6f)
        }
        // Side locks, bobbed ends, fringe and purple highlight strands.
        beginPath(); moveTo(35, 15); quadTo(31, 25, 36, 36)
        lineTo(40, 40); lineTo(37, 29); lineTo(39, 18); closePath(); fillPath(hair)
        beginPath(); moveTo(65, 15); quadTo(69, 25, 64, 36)
        lineTo(60, 40); lineTo(63, 29); lineTo(61, 18); closePath(); fillPath(hair)
        beginPath(); moveTo(35, 17); quadTo(35, 7, 47, 7)
        quadTo(58, 3, 65, 13); quadTo(64, 18, 59, 22)
        lineTo(57, 15); lineTo(53, 20); lineTo(51, 14)
        lineTo(46, 20); lineTo(44, 14); lineTo(40, 22); lineTo(38, 17); closePath()
        fillPath(hair); strokePath(hairShade, 0.5f)
        line(39, 12, 45, 8, hairLight, 0.7f)
        line(48, 9, 53, 7, hairLight, 0.6f)
        line(57, 10, 61, 14, hairLight, 0.7f)
        // Tiny star hair ornament.
        line(36, 17, 38, 15, 0xFFE5D5FF.toInt(), 1.0f)
        line(38, 15, 40, 17, 0xFFE5D5FF.toInt(), 1.0f)
        line(40, 17, 38, 19, 0xFFE5D5FF.toInt(), 1.0f)
        line(38, 19, 36, 17, 0xFFE5D5FF.toInt(), 1.0f)

        // Gentle sparkle accents and floating sleep/talking effects.
        if (action == "sleep") {
            text("z", 76, 25 - bob, 5, violet, 220)
            text("z", 82, 19 - bob, 6, violet, 180)
        } else if (talking) {
            text("♪", 78, 28 - abs(sin(t * 3f)) * 3, 5, violet, 210)
        }
        cv.restore()
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
