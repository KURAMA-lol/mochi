package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
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
 * Radial (pie) menu drawn around Nana. It knows where she is: icons are only placed on angles
 * where they fit on screen, so in the middle they fan out above and to the sides, and in a
 * corner they all go to the free side. If there are more items than slots, the last slot is a
 * "more" button that pops out the next page (hover it while holding, or tap it).
 */
class RadialMenu(ctx: Context) : View(ctx) {
    class Item(val id: String, val label: String, val icon: Int)

    var items: List<Item> = emptyList()
    var onPick: (Item) -> Unit = {}
    var onClose: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val D = 46f * d            // icon diameter
    private val R0 = 108f * d          // distance from Nana's centre
    private var cx = 0f; private var cy = 0f
    private var sw = 0; private var sh = 0
    private var slotAngles = FloatArray(0)
    private var shown: List<Item> = emptyList()
    private var page = 0
    private var slots = 0
    private var hasMore = false
    private var t0 = 0L
    private var hover = -1
    private var hoverSince = 0L
    private var held = true
    private var closing = false
    private val more = Item("more", "More", R.drawable.ni_more)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12.5f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(5f * d, 0f, 1f * d, 0xCC000000.toInt())
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC10202A.toInt() }
    private val scrim = Paint()
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0x22FFFFFF }

    private fun fits(a: Double, r: Float): Boolean {
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        val m = D / 2f + 6f * d
        return x >= m && x <= sw - m && y >= 34f * d + D / 2f && y <= sh - m
    }

    /** Lay the icons out around (cx, cy), the centre of Nana. */
    fun open(centerX: Float, centerY: Float, screenW: Int, screenH: Int) {
        cx = centerX; cy = centerY; sw = screenW; sh = screenH
        t0 = SystemClock.uptimeMillis(); held = true; closing = false; hover = -1; page = 0
        layout()
    }

    private fun layout() {
        val step = 3.0 * PI / 180.0
        val up = -PI / 2
        val toCentre = atan2((sh / 2f - cy).toDouble(), (sw / 2f - cx).toDouble())
        var base = if (fits(up, R0)) up else toCentre
        if (!fits(base, R0)) base = toCentre
        var lo = base; var hi = base
        val maxHalf = 105.0 * PI / 180.0
        while (hi - base < maxHalf && fits(hi + step, R0)) hi += step
        while (base - lo < maxHalf && fits(lo - step, R0)) lo -= step
        val span = hi - lo
        val minStep = ((D + 8f * d) / R0).toDouble()
        slots = (span / minStep).toInt() + 1
        slots = slots.coerceIn(3, 7)
        hasMore = items.size > slots
        val per = if (hasMore) slots - 1 else min(slots, items.size)
        val start = page * per
        val pageItems = if (items.isEmpty()) emptyList() else items.drop(start).take(per).ifEmpty { page = 0; items.take(per) }
        shown = if (hasMore) pageItems + more else pageItems
        val n = shown.size
        val st = if (n > 1) min(span / (n - 1), 40.0 * PI / 180.0) else 0.0
        val used = st * (n - 1)
        val mid = (lo + hi) / 2.0
        slotAngles = FloatArray(n) { (mid - used / 2.0 + st * it).toFloat() }
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun pos(i: Int, p: Float): Pair<Float, Float> {
        val a = slotAngles[i].toDouble()
        val r = R0 * p
        return Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }

    private fun prog(i: Int, now: Long): Float {
        val k = ((now - t0 - i * 55L) / 230f).coerceIn(0f, 1f)
        // ease out back
        val c1 = 1.7f; val c3 = c1 + 1f
        val x = k - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun hit(x: Float, y: Float): Int {
        var best = -1; var bd = D * 0.85f
        val now = SystemClock.uptimeMillis()
        for (i in shown.indices) {
            val (px, py) = pos(i, min(1f, prog(i, now)))
            val dd = hypot(x - px, y - py)
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    private fun setHover(h: Int) {
        if (h != hover) {
            hover = h; hoverSince = SystemClock.uptimeMillis()
            if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun pick(i: Int) {
        if (i < 0 || i >= shown.size) return
        val it = shown[i]
        if (it === more) { page++; hover = -1; layout(); return }
        closing = true
        onPick(it)
    }

    private fun toLocal(rx: Float, ry: Float): Pair<Float, Float> {
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Pair(rx - loc[0], ry - loc[1])
    }

    /** Raw screen coordinates from the gesture that opened the menu (finger still down). */
    fun feed(rawX: Float, rawY: Float, up: Boolean) {
        val (x, y) = toLocal(rawX, rawY)
        if (up) {
            val h = hit(x, y)
            held = false
            if (h >= 0) pick(h) else setHover(-1)
        } else setHover(hit(x, y))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { setHover(hit(e.x, e.y)); invalidate() }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                if (h >= 0) pick(h) else onClose()
            }
            MotionEvent.ACTION_CANCEL -> onClose()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val a0 = min(1f, (now - t0) / 220f)
        scrim.shader = RadialGradient(cx, cy, R0 * 2.1f, intArrayOf((0x88 * a0).toInt() shl 24, 0x00000000), floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        // faint guide ring
        if (shown.size > 1) {
            val a = slotAngles.first(); val b = slotAngles.last()
            val rr = R0 * min(1f, a0)
            val oval = android.graphics.RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, Math.toDegrees(a.toDouble()).toFloat(), Math.toDegrees((b - a).toDouble()).toFloat(), false, arcP)
        }
        // held-hover auto-advance of the "more" page
        if (held && hover >= 0 && shown[hover] === more && now - hoverSince > 650L) { page++; hover = -1; layout(); invalidate(); return }
        for (i in shown.indices) {
            val p = prog(i, now)
            val (px, py) = pos(i, max(0f, p))
            val hv = i == hover
            val sc = (if (hv) 1.22f else 1f) * min(1f, max(0.2f, p))
            val r = D / 2f * sc
            glow.shader = RadialGradient(px, py, r * 1.9f, intArrayOf(if (hv) 0x665EEAD4 else 0x2A5EEAD4, 0x005EEAD4), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, r * 1.9f, glow)
            fill.shader = android.graphics.LinearGradient(px, py - r, px, py + r,
                if (hv) intArrayOf(0xF05EEAD4.toInt(), 0xF02BB5A6.toInt()) else intArrayOf(0xE61B2E3A.toInt(), 0xE60E1A24.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, r, fill)
            ring.color = if (hv) 0xFFFFFFFF.toInt() else 0x66B8FFF3
            c.drawCircle(px, py, r, ring)
            val dr = context.getDrawable(shown[i].icon)?.mutate()
            if (dr != null) {
                dr.setTint(if (hv) 0xFF03211F.toInt() else 0xFFE8FFFB.toInt())
                val h = (r * 0.52f).toInt()
                dr.setBounds((px - h).toInt(), (py - h).toInt(), (px + h).toInt(), (py + h).toInt())
                dr.draw(c)
            }
            if (hv) {
                val label = shown[i].label
                val w = txt.measureText(label) + 18f * d
                val above = py > cy
                val ly = if (above) py + r + 22f * d else py - r - 8f * d
                val rect = android.graphics.RectF(px - w / 2, ly - 16f * d, px + w / 2, ly + 6f * d)
                val shift = if (rect.left < 4f * d) 4f * d - rect.left else if (rect.right > sw - 4f * d) (sw - 4f * d) - rect.right else 0f
                rect.offset(shift, 0f)
                c.drawRoundRect(rect, 11f * d, 11f * d, pill)
                c.drawText(label, rect.centerX(), rect.centerY() + 4.5f * d, txt)
            }
        }
        if (now - t0 < 900L || hover >= 0) postInvalidateOnAnimation()
    }
}
