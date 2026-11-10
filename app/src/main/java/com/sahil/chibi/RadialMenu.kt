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
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Radial menu around Nana. Icons sit on an arc that only uses angles where they fully fit on
 * screen (middle of the screen: above and to the sides; right corner: the free left side).
 * Slide the finger along the arc; when you reach the end of the arc and stay there for a moment,
 * the arc flips to the next group of icons (or back to the previous one).
 */
class RadialMenu(ctx: Context) : View(ctx) {
    class Item(val id: String, val label: String, val icon: Int)

    var items: List<Item> = emptyList()
    var onPick: (Item) -> Unit = {}
    var onClose: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val D = 48f * d
    private val R0 = 104f * d
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
    private var armed = true          // edge paging only re-arms after the finger leaves the end slot
    private var flipDir = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12.5f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE610202A.toInt() }
    private val scrim = Paint()
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0x30FFFFFF }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private fun fits(a: Double): Boolean {
        val x = cx + R0 * cos(a).toFloat()
        val y = cy + R0 * sin(a).toFloat()
        val m = D / 2f + 14f * d
        return x >= m && x <= width - m && y >= 40f * d + D / 2f && y <= height - m
    }

    /** Centre of Nana in raw screen coordinates. */
    fun open(rawX: Float, rawY: Float) {
        rawCx = rawX; rawCy = rawY
        t0 = SystemClock.uptimeMillis(); hover = -1; page = 0; armed = true
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
            // search the circle for any angle that fits
            var best = base; var found = false
            var k = 0
            while (k < 180 && !found) { for (sg in intArrayOf(1, -1)) { val a = base + sg * k * step * 2; if (fits(a)) { best = a; found = true; break } }; k++ }
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
        // when flipping forward icons slide in from the end, backward from the start
        angles = FloatArray(n) { (mid - used / 2.0 + st * it).toFloat() }
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun pos(i: Int, p: Float): Pair<Float, Float> {
        val a = angles[i].toDouble()
        val r = R0 * p
        return Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }

    private fun prog(i: Int, now: Long): Float {
        val order = if (flipDir < 0) shown.size - 1 - i else i
        val k = ((now - t0 - order * 50L) / 220f).coerceIn(0f, 1f)
        val c1 = 1.6f; val c3 = c1 + 1f
        val x = k - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun hit(x: Float, y: Float): Int {
        var best = -1; var bd = D * 0.9f
        for (i in shown.indices) {
            val (px, py) = pos(i, 1f)
            val dd = hypot(x - px, y - py)
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
        val (x, y) = toLocal(rawX, rawY)
        val h = hit(x, y)
        if (up) {
            if (h >= 0) { onPick(shown[h]) } else if (hover < 0) { /* released on nothing: stay open, tap to choose */ }
        } else setHover(h)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { setHover(hit(e.x, e.y)); invalidate() }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                if (h >= 0) onPick(shown[h]) else onClose()
            }
            MotionEvent.ACTION_CANCEL -> onClose()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (angles.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        // edge paging: dwell on the first/last icon -> flip group
        if (pages > 1 && armed && hover >= 0 && now - hoverSince > 380L) {
            if (hover == shown.size - 1) { flip(1); invalidate(); return }
            if (hover == 0 && shown.size > 1) { flip(-1); invalidate(); return }
        }
        val a0 = min(1f, (now - t0) / 200f)
        scrim.shader = RadialGradient(cx, cy, R0 * 2.2f, intArrayOf(((0x88 * a0).toInt() shl 24), 0x00000000), floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        if (angles.size > 1) {
            val a = angles.first(); val b = angles.last()
            oval.set(cx - R0, cy - R0, cx + R0, cy + R0)
            c.drawArc(oval, Math.toDegrees(a.toDouble()).toFloat(), Math.toDegrees((b - a).toDouble()).toFloat(), false, arcP)
        }
        for (i in shown.indices) {
            val p = prog(i, now)
            val (px, py) = pos(i, max(0f, p))
            val hv = i == hover
            val sc = (if (hv) 1.2f else 1f) * min(1f, max(0.2f, p))
            val r = D / 2f * sc
            glow.shader = RadialGradient(px, py, r * 1.8f, intArrayOf(if (hv) 0x665EEAD4 else 0x225EEAD4, 0x005EEAD4), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, r * 1.8f, glow)
            fill.shader = LinearGradient(px, py - r, px, py + r,
                if (hv) intArrayOf(0xF05EEAD4.toInt(), 0xF02BB5A6.toInt()) else intArrayOf(0xEB1B2E3A.toInt(), 0xEB0E1A24.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, r, fill)
            ring.color = if (hv) 0xFFFFFFFF.toInt() else 0x66B8FFF3
            c.drawCircle(px, py, r, ring)
            val dr = context.getDrawable(shown[i].icon)?.mutate()
            if (dr != null) {
                dr.setTint(if (hv) 0xFF03211F.toInt() else 0xFFE8FFFB.toInt())
                val h = (r * 0.5f).toInt()
                dr.setBounds((px - h).toInt(), (py - h).toInt(), (px + h).toInt(), (py + h).toInt())
                dr.draw(c)
            }
        }
        // label of the hovered icon, kept on screen
        if (hover in shown.indices) {
            val (px, py) = pos(hover, 1f)
            val label = shown[hover].label
            val w = txt.measureText(label) + 20f * d
            val below = py < cy
            val ly = if (below) py + D / 2f + 30f * d else py - D / 2f - 12f * d
            val rect = RectF(px - w / 2, ly - 17f * d, px + w / 2, ly + 7f * d)
            val sh = if (rect.left < 6f * d) 6f * d - rect.left else if (rect.right > width - 6f * d) (width - 6f * d) - rect.right else 0f
            rect.offset(sh, 0f)
            c.drawRoundRect(rect, 12f * d, 12f * d, pill)
            c.drawText(label, rect.centerX(), rect.centerY() + 4.5f * d, txt)
        }
        // page dots in the middle of the arc, one step further out
        if (pages > 1) {
            val mid = (angles.first() + angles.last()) / 2f
            val rr = R0 + D / 2f + 18f * d
            val gx = cx + rr * cos(mid.toDouble()).toFloat()
            val gy = cy + rr * sin(mid.toDouble()).toFloat()
            val gap = 10f * d
            for (k in 0 until pages) {
                dotP.color = if (k == page) 0xFFFFFFFF.toInt() else 0x66FFFFFF
                c.drawCircle(gx + (k - (pages - 1) / 2f) * gap, gy, if (k == page) 3.2f * d else 2.4f * d, dotP)
            }
        }
        if (now - t0 < 900L || hover >= 0) postInvalidateOnAnimation()
    }
}
