package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Liquid-glass radial menu around Nana. Only visible while the finger is held on her:
 * icons fly out of her one after another along the arc, the hovered icon swells, the picked one
 * flashes while the rest fade, then everything flows back into her. Releasing on nothing just
 * closes it. The arc only uses angles where icons fully fit on screen; dwell on the first/last
 * icon to flip to the next/previous group.
 */
class RadialMenu(ctx: Context) : View(ctx) {
    class Item(val id: String, val label: String, val icon: Int)

    var items: List<Item> = emptyList()
    var onPick: (Item) -> Unit = {}
    var onClose: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val D = 50f * d
    private val R0 = 106f * d
    private var rawCx = -1f; private var rawCy = -1f
    private var cx = 0f; private var cy = 0f
    private var angles = FloatArray(0)
    private var shown: List<Item> = emptyList()
    private var perPage = 4
    private var page = 0
    private var pages = 1
    private var t0 = 0L
    private var hover = -1
    private var hoverSince = 0L
    private var armed = true
    private var flipDir = 0
    private var state = 0            // 0 held/open, 1 picked, 2 closing
    private var stT = 0L
    private var picked = -1
    private var done = false
    private val hoverAnim = FloatArray(8)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12.5f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private fun fits(a: Double): Boolean {
        val x = cx + R0 * cos(a).toFloat()
        val y = cy + R0 * sin(a).toFloat()
        val m = D / 2f + 16f * d
        return x >= m && x <= width - m && y >= 40f * d + D / 2f && y <= height - m
    }

    /** Centre of Nana in raw screen coordinates. */
    fun open(rawX: Float, rawY: Float) {
        rawCx = rawX; rawCy = rawY
        t0 = SystemClock.uptimeMillis(); hover = -1; page = 0; armed = true; state = 0; done = false; picked = -1
        if (width > 0) layout()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (rawCx >= 0f) layout() }

    private fun layout() {
        val loc = IntArray(2); getLocationOnScreen(loc)
        cx = rawCx - loc[0]; cy = rawCy - loc[1]
        val step = 2.0 * PI / 180.0
        val up = -PI / 2
        val toCentre = atan2((height / 2f - cy).toDouble(), (width / 2f - cx).toDouble())
        var base = if (fits(up)) up else toCentre
        if (!fits(base)) {
            var best = base; var found = false
            var k = 0
            while (k < 90 && !found) { for (sg in intArrayOf(1, -1)) { val a = base + sg * k * step * 2; if (fits(a)) { best = a; found = true; break } }; k++ }
            base = best
        }
        var lo = base; var hi = base
        val maxHalf = 100.0 * PI / 180.0
        while (hi - base < maxHalf && fits(hi + step)) hi += step
        while (base - lo < maxHalf && fits(lo - step)) lo -= step
        val span = hi - lo
        val minStep = ((D + 10f * d) / R0).toDouble()
        perPage = ((span / minStep).toInt() + 1).coerceIn(3, 6)
        pages = max(1, (items.size + perPage - 1) / perPage)
        if (page >= pages) page = 0
        shown = items.drop(page * perPage).take(perPage)
        val n = shown.size
        val st = if (n > 1) min(span / (n - 1), 42.0 * PI / 180.0) else 0.0
        val used = st * (n - 1)
        val mid = (lo + hi) / 2.0
        angles = FloatArray(n) { (mid - used / 2.0 + st * it).toFloat() }
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    // ---- easing
    private fun outBack(k: Float): Float { val c1 = 1.5f; val x = k - 1f; return 1f + (c1 + 1f) * x * x * x + c1 * x * x }
    private fun inCubic(k: Float) = k * k * k

    /** 0..1(+overshoot) "how far out of Nana" icon i is, plus the alpha. */
    private fun vis(i: Int, now: Long): Float {
        val n = shown.size
        return if (state == 2) {
            val order = n - 1 - i
            1f - inCubic(((now - stT - order * 45L) / 300f).coerceIn(0f, 1f))
        } else {
            val order = if (flipDir < 0) n - 1 - i else i
            outBack(((now - t0 - order * 65L) / 360f).coerceIn(0f, 1f))
        }
    }

    private fun place(i: Int, v: Float): Pair<Float, Float> {
        // each icon sweeps along the arc from the first slot to its own slot while moving out
        val vv = min(max(v, 0f), 1.15f)
        val ang = angles[0] + (angles[i] - angles[0]) * min(1f, vv)
        val r = R0 * vv
        return Pair(cx + r * cos(ang.toDouble()).toFloat(), cy + r * sin(ang.toDouble()).toFloat())
    }

    private fun hit(x: Float, y: Float): Int {
        var best = -1; var bd = D * 0.9f
        for (i in shown.indices) {
            val a = angles[i].toDouble()
            val dd = hypot(x - (cx + R0 * cos(a).toFloat()), y - (cy + R0 * sin(a).toFloat()))
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    private fun setHover(h: Int) {
        if (h != hover) {
            hover = h; hoverSince = SystemClock.uptimeMillis()
            if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (h != 0 && h != shown.size - 1) armed = true
        }
    }

    private fun flip(dir: Int) {
        if (pages <= 1) return
        flipDir = dir
        page = (page + dir + pages) % pages
        armed = false; hover = -1
        layout()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun toLocal(rx: Float, ry: Float): Pair<Float, Float> {
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Pair(rx - loc[0], ry - loc[1])
    }

    /** Raw screen coordinates from the hold gesture that opened the menu (finger still down). */
    fun feed(rawX: Float, rawY: Float, up: Boolean) {
        if (state != 0) return
        val (x, y) = toLocal(rawX, rawY)
        val h = hit(x, y)
        if (!up) { setHover(h); invalidate(); return }
        val now = SystemClock.uptimeMillis()
        if (h >= 0) {
            picked = h; state = 1; stT = now
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onPick(shown[h])
        } else { state = 2; stT = now }
        invalidate()
    }

    fun cancel() { if (state == 0) { state = 2; stT = SystemClock.uptimeMillis(); invalidate() } }

    private fun al(paint: Paint, a: Float) { paint.alpha = (paint.alpha * a.coerceIn(0f, 1f)).toInt() }

    private fun glass(c: Canvas, px: Float, py: Float, r: Float, a: Float, glowK: Float) {
        // soft shadow so the glass reads on bright wallpapers
        p.shader = RadialGradient(px, py + 4f * d, r * 1.45f, intArrayOf(0x66000000, 0x00000000), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        p.alpha = 255; al(p, a); c.drawCircle(px, py + 4f * d, r * 1.45f, p)
        if (glowK > 0f) {
            p.shader = RadialGradient(px, py, r * 2.1f, intArrayOf(0xAA7CF5E4.toInt(), 0x007CF5E4), null, Shader.TileMode.CLAMP)
            p.alpha = (255 * glowK).toInt(); al(p, a); c.drawCircle(px, py, r * 2.1f, p)
        }
        // frosted body
        p.shader = null; p.color = 0x6612222E; al(p, a); c.drawCircle(px, py, r, p)
        p.shader = LinearGradient(px, py - r, px, py + r, intArrayOf(0x66FFFFFF, 0x10FFFFFF, 0x2EFFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        p.alpha = 255; al(p, a); c.drawCircle(px, py, r, p)
        if (glowK > 0f) {
            p.shader = RadialGradient(px, py, r, intArrayOf(0x007CF5E4, (0x88 * glowK).toInt() shl 24 or 0x7CF5E4), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
            p.alpha = 255; al(p, a); c.drawCircle(px, py, r, p)
        }
        // rim light
        stroke.shader = LinearGradient(px - r, py - r, px + r, py + r, intArrayOf(0xEEFFFFFF.toInt(), 0x33FFFFFF, 0x99BFFFF5.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        stroke.alpha = 255; al(stroke, a); c.drawCircle(px, py, r - 0.7f * d, stroke)
        // specular crescent
        oval.set(px - r * 0.62f, py - r * 0.94f, px + r * 0.62f, py - r * 0.34f)
        p.shader = LinearGradient(0f, oval.top, 0f, oval.bottom, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        p.alpha = 255; al(p, a); c.drawOval(oval, p)
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        if (angles.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val n = shown.size
        // edge paging
        if (state == 0 && pages > 1 && armed && hover >= 0 && now - hoverSince > 380L) {
            if (hover == n - 1) { flip(1); invalidate(); return }
            if (hover == 0 && n > 1) { flip(-1); invalidate(); return }
        }
        if (state == 1 && now - stT > 260L) { state = 2; stT = now }
        if (state == 2 && !done && now - stT > 300L + n * 45L) { done = true; post { onClose() }; return }

        // guide arc, fades with the icons
        if (n > 1) {
            val a = angles.first(); val b = angles.last()
            val gv = if (state == 2) max(0f, 1f - (now - stT) / 220f) else min(1f, (now - t0) / 260f)
            oval.set(cx - R0, cy - R0, cx + R0, cy + R0)
            arcP.color = 0x44FFFFFF; arcP.alpha = (0x44 * gv).toInt()
            c.drawArc(oval, Math.toDegrees(a.toDouble()).toFloat(), Math.toDegrees((b - a).toDouble()).toFloat(), false, arcP)
        }
        for (i in 0 until n) {
            val v = vis(i, now)
            val (px, py) = place(i, v)
            val isHover = state == 0 && i == hover
            val isPick = state >= 1 && i == picked
            val tgt = if (isHover) 1f else 0f
            hoverAnim[i] = hoverAnim[i] + (tgt - hoverAnim[i]) * 0.3f
            var sc = 1f + 0.16f * hoverAnim[i]
            var alpha = min(1f, max(0f, v) * 1.7f)
            var glow = hoverAnim[i] * 0.6f
            if (state >= 1) {
                val k = ((now - stT) / 220f).coerceIn(0f, 1f)
                if (isPick) { sc = 1f + 0.4f * min(1f, k * 1.6f); glow = 1f }
                else alpha *= 1f - 0.7f * k
            }
            val r = D / 2f * sc * min(1f, 0.35f + max(0f, v) * 0.65f)
            glass(c, px, py, r, alpha, glow)
            val dr = context.getDrawable(shown[i].icon)?.mutate()
            if (dr != null) {
                dr.setTint(0xFFFFFFFF.toInt()); dr.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
                val h = (r * 0.5f).toInt()
                dr.setBounds((px - h).toInt(), (py - h).toInt(), (px + h).toInt(), (py + h).toInt())
                dr.draw(c)
            }
        }
        // label of the hovered / picked icon
        val li = if (state == 0) hover else picked
        if (li in 0 until n && !(state == 2)) {
            val a = angles[li].toDouble()
            val px = cx + R0 * cos(a).toFloat(); val py = cy + R0 * sin(a).toFloat()
            val label = shown[li].label
            val w = txt.measureText(label) + 22f * d
            val below = py < cy
            val ly = if (below) py + D / 2f + 32f * d else py - D / 2f - 14f * d
            val rect = RectF(px - w / 2, ly - 17f * d, px + w / 2, ly + 7f * d)
            val sh = if (rect.left < 6f * d) 6f * d - rect.left else if (rect.right > width - 6f * d) (width - 6f * d) - rect.right else 0f
            rect.offset(sh, 0f)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(0xCC2A3C4A.toInt(), 0xCC121E28.toInt()), null, Shader.TileMode.CLAMP)
            p.alpha = 255; c.drawRoundRect(rect, 12f * d, 12f * d, p); p.shader = null
            stroke.shader = null; stroke.color = 0x55FFFFFF; c.drawRoundRect(rect, 12f * d, 12f * d, stroke)
            c.drawText(label, rect.centerX(), rect.centerY() + 4.5f * d, txt)
        }
        // page dots
        if (pages > 1 && state != 2) {
            val mid = (angles.first() + angles.last()) / 2f
            val rr = R0 + D / 2f + 20f * d
            val gx = cx + rr * cos(mid.toDouble()).toFloat()
            val gy = cy + rr * sin(mid.toDouble()).toFloat()
            val gap = 10f * d
            for (k in 0 until pages) {
                dotP.color = if (k == page) 0xFFFFFFFF.toInt() else 0x66FFFFFF
                c.drawCircle(gx + (k - (pages - 1) / 2f) * gap, gy, if (k == page) 3.2f * d else 2.4f * d, dotP)
            }
        }
        postInvalidateOnAnimation()
    }
}
