package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Small blobs, one per team member, that hop around Nana while that member works and bump into each other. */
class Crew(private val ctx: Context, private val wm: WindowManager, private val anchor: () -> FloatArray?) {
    private val d = ctx.resources.displayMetrics.density
    private val v = CrewView(ctx)
    private var shown = false
    private var cfgRaw = ""
    private var cfg = JSONObject()
    private val bmps = HashMap<String, Pair<Int, Bitmap?>>()
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init { v.onEmpty = { hide() } }

    private fun loadCfg() {
        try {
            val s = ctx.getSharedPreferences("crew", 0).getString("j", "{}") ?: "{}"
            if (s != cfgRaw) { cfgRaw = s; cfg = JSONObject(s) }
        } catch (e: Throwable) {}
    }

    fun sync(m: Map<String, String>) {
        loadCfg()
        if (m.isNotEmpty() && !shown) {
            try { wm.addView(v, lp); shown = true } catch (e: Throwable) { return }
        }
        v.sync(m)
    }

    fun hide() {
        if (!shown) return
        try { wm.removeView(v) } catch (e: Throwable) {}
        shown = false
    }

    fun destroy() { v.stopAll(); hide() }

    private fun bitmapFor(name: String, data: String): Bitmap? {
        val c = bmps[name]
        if (c != null && c.first == data.length) return c.second
        var bm: Bitmap? = null
        try {
            val i = data.indexOf("base64,")
            if (i > 0) { val raw = Base64.decode(data.substring(i + 7), Base64.DEFAULT); bm = BitmapFactory.decodeByteArray(raw, 0, raw.size) }
        } catch (e: Throwable) {}
        bmps[name] = Pair(data.length, bm)
        return bm
    }

    private inner class B(val name: String) {
        var x = 0f; var y = 0f; var tx = 0f; var ty = 0f
        var ph = Random.nextFloat() * 6f; var a = 0f; var gone = false
        var label = ""; var bump = 0f; var wait = 0f; var next = 3f + Random.nextFloat() * 3f
        var partner: B? = null; var face = 1f; var moving = false
        var seen = System.nanoTime(); var blink = 2f + Random.nextFloat() * 3f
        val h = (name.hashCode() and 0x7fffffff)
        var col = Color.HSVToColor(floatArrayOf((h % 360).toFloat(), 0.55f, 1f))
        var shape = h / 7 % 4
        val lane = h / 3 % 3
        var emo = ""
        var bmp: Bitmap? = null
        var disp = name
    }

    private inner class CrewView(c: Context) : View(c) {
        val list = ArrayList<B>()
        var onEmpty: () -> Unit = {}
        private var last = 0L
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10f * d; typeface = Typeface.DEFAULT_BOLD }
        private val r = RectF()
        private val path = Path()
        private val sparks = ArrayList<FloatArray>()

        fun sync(m: Map<String, String>) {
            val now = System.nanoTime()
            for ((n, s) in m) {
                var b = list.firstOrNull { it.name == n }
                if (b == null) {
                    b = B(n)
                    val an = anchor()
                    b.x = (an?.get(0) ?: (resources.displayMetrics.widthPixels / 2f)) + (Random.nextFloat() - 0.5f) * 120f * d
                    b.y = (an?.get(1) ?: resources.displayMetrics.heightPixels.toFloat()) - 30f * d
                    b.tx = b.x; b.ty = b.y
                    list.add(b)
                }
                b.seen = now; b.gone = false
                b.label = s.removePrefix("using ").take(16)
                val o = cfg.optJSONObject(n)
                if (o != null) {
                    try { val cs = o.optString("col", ""); if (cs.startsWith("#")) b.col = Color.parseColor(cs) } catch (e: Throwable) {}
                    if (o.has("shp")) b.shape = o.optInt("shp", b.shape).coerceIn(0, 3)
                    b.emo = o.optString("emo", "")
                    val im = o.optString("img", "")
                    b.bmp = if (im.isNotEmpty()) bitmapFor(n, im) else null
                    val al = o.optString("alias", "")
                    if (al.isNotEmpty()) b.disp = al
                }
            }
            for (b in list) if (!m.containsKey(b.name) && now - b.seen > 4_000_000_000L) b.gone = true
            if (last == 0L) { last = now; postInvalidateOnAnimation() }
        }

        fun stopAll() { list.clear(); last = 0L }

        override fun onDraw(cv: Canvas) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now
            val an = anchor()
            val sw = width.toFloat()
            val ax = an?.get(0) ?: (sw - 60f * d)
            val ay = an?.get(1) ?: (height - 10f * d)
            val iter = list.iterator()
            while (iter.hasNext()) {
                val b = iter.next()
                step(b, dt, ax, ay, sw)
                if (b.gone && b.a <= 0f) { iter.remove(); continue }
                draw(cv, b)
            }
            val si = sparks.iterator()
            while (si.hasNext()) {
                val s = si.next(); s[2] -= dt; s[1] -= 30f * d * dt
                if (s[2] <= 0f) { si.remove(); continue }
                p.color = Color.argb((s[2].coerceAtMost(1f) * 255).toInt(), 255, 110, 160); p.style = Paint.Style.FILL
                heart(cv, s[0], s[1], 5f * d)
            }
            if (list.isEmpty() && sparks.isEmpty()) { last = 0L; post { onEmpty() } } else postInvalidateOnAnimation()
        }

        private fun heart(cv: Canvas, x: Float, y: Float, s: Float) {
            path.reset()
            path.moveTo(x, y + s * 0.9f)
            path.cubicTo(x - s * 1.6f, y - s * 0.2f, x - s * 0.6f, y - s * 1.3f, x, y - s * 0.4f)
            path.cubicTo(x + s * 0.6f, y - s * 1.3f, x + s * 1.6f, y - s * 0.2f, x, y + s * 0.9f)
            cv.drawPath(path, p)
        }

        private fun step(b: B, dt: Float, ax: Float, ay: Float, sw: Float) {
            b.a = if (b.gone) (b.a - dt * 2.5f).coerceAtLeast(0f) else (b.a + dt * 3f).coerceAtMost(1f)
            val gy = ay - 4f * d - b.lane * 11f * d
            if (b.bump > 0f) b.bump = (b.bump - dt * 2f).coerceAtLeast(0f)
            b.blink -= dt; if (b.blink < -0.13f) b.blink = 2f + Random.nextFloat() * 3.5f
            b.next -= dt
            if (b.next <= 0f && b.partner == null) {
                val others = list.filter { it !== b && !it.gone }
                if (others.isNotEmpty()) b.partner = others.random()
                b.next = 4f + Random.nextFloat() * 4f
            }
            val pa = b.partner
            if (pa != null && (pa.gone || !list.contains(pa))) b.partner = null
            if (b.wait > 0f) { b.wait -= dt; b.moving = false }
            else {
                val q = b.partner
                if (q != null) { b.tx = q.x + (if (b.x < q.x) -34f else 34f) * d; b.ty = gy }
                val dx = b.tx - b.x
                if (abs(dx) < 4f * d) {
                    if (q != null && hypot(q.x - b.x, q.y - b.y) < 70f * d) {
                        b.bump = 1f; q.bump = 1f; b.wait = 0.9f; q.wait = 0.9f; b.partner = null
                        b.face = if (q.x > b.x) 1f else -1f; q.face = -b.face
                        sparks.add(floatArrayOf((b.x + q.x) / 2, b.y - 50f * d, 1.3f))
                        sparks.add(floatArrayOf((b.x + q.x) / 2 + 9f * d, b.y - 42f * d, 1.0f))
                    } else {
                        b.tx = (ax + (Random.nextFloat() - 0.5f) * 340f * d).coerceIn(24f * d, sw - 24f * d)
                        b.ty = gy; b.wait = 0.3f + Random.nextFloat() * 1.4f
                    }
                    b.moving = false
                } else {
                    val sp = 85f * d * dt
                    b.x += if (dx > 0) minOf(sp, dx) else maxOf(-sp, dx)
                    b.face = if (dx > 0) 1f else -1f
                    b.moving = true
                }
            }
            b.y += (gy - b.y) * 0.2f
            b.ph += dt * (if (b.moving) 10f else 2f)
        }

        private fun draw(cv: Canvas, b: B) {
            val s = 17f * d
            val hop = if (b.moving) abs(sin(b.ph)) * 10f * d else if (b.bump > 0f) abs(sin(b.bump * 6f)) * 12f * d else 0f
            val breathe = if (!b.moving) sin(b.ph) * 0.03f else 0f
            val sx = 1f + breathe + (if (b.moving) 0.06f * (1f - abs(sin(b.ph))) else 0f)
            val sy = 1f - breathe + (if (b.moving) 0.1f * abs(sin(b.ph)) else 0f)
            val al = (b.a * 255).toInt()
            p.style = Paint.Style.FILL
            p.color = Color.argb((al * 0.22f).toInt(), 0, 0, 0)
            cv.drawOval(b.x - s * 0.8f, b.y - 3f * d, b.x + s * 0.8f, b.y + 3f * d, p)
            cv.save()
            cv.translate(b.x, b.y - hop)
            cv.scale(sx, sy, 0f, 0f)
            r.set(-s, -2f * s, s, 0f)
            p.color = (b.col and 0x00FFFFFF) or (al shl 24)
            when (b.shape) {
                0 -> cv.drawOval(r, p)
                1 -> cv.drawRoundRect(r, s * 0.55f, s * 0.55f, p)
                2 -> { r.set(-s, -1.9f * s, s, 0f); cv.drawRoundRect(r, s, s * 0.7f, p); r.set(-s * 0.35f, -2.35f * s, s * 0.35f, -1.6f * s); cv.drawOval(r, p) }
                else -> { r.set(-s * 1.1f, -1.7f * s, s * 1.1f, 0f); cv.drawRoundRect(r, s * 0.9f, s * 0.9f, p) }
            }
            p.color = Color.argb((al * 0.3f).toInt(), 255, 255, 255)
            r.set(-s * 0.7f, -1.8f * s, -s * 0.15f, -1.5f * s); cv.drawOval(r, p)
            val bm = b.bmp
            if (bm != null) {
                val rad = s * 0.72f
                val cy = -s * 1.0f
                cv.save()
                path.reset(); path.addCircle(0f, cy, rad, Path.Direction.CW); cv.clipPath(path)
                p.alpha = al
                cv.drawBitmap(bm, null, RectF(-rad, cy - rad, rad, cy + rad), p)
                cv.restore()
            } else if (b.emo.isNotEmpty()) {
                t.textSize = s * 1.15f; t.color = Color.argb(al, 255, 255, 255)
                cv.drawText(b.emo, 0f, -s * 0.62f, t); t.textSize = 10f * d
            } else {
                p.color = Color.argb(al, 30, 25, 40)
                val ex = b.face * s * 0.12f
                val eh = if (b.blink < 0f) s * 0.03f else s * 0.14f
                r.set(-s * 0.35f + ex - s * 0.13f, -s - eh, -s * 0.35f + ex + s * 0.13f, -s + eh); cv.drawOval(r, p)
                r.set(s * 0.35f + ex - s * 0.13f, -s - eh, s * 0.35f + ex + s * 0.13f, -s + eh); cv.drawOval(r, p)
            }
            cv.restore()
            if (b.label.isNotEmpty()) {
                t.textSize = 9f * d
                val w = t.measureText(b.label) + 14f * d
                val top = b.y - hop - 2f * s - 22f * d
                p.color = Color.argb((al * 0.78f).toInt(), 20, 20, 26)
                r.set(b.x - w / 2, top, b.x + w / 2, top + 17f * d); cv.drawRoundRect(r, 9f * d, 9f * d, p)
                t.color = Color.argb(al, 255, 255, 255); cv.drawText(b.label, b.x, top + 12.5f * d, t)
                t.textSize = 10f * d
            }
            t.color = Color.argb((al * 0.9f).toInt(), 255, 255, 255)
            cv.drawText(b.disp.take(10), b.x, b.y + 13f * d, t)
        }
    }
}
