package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tiny pixel-art painter: every call draws one crisp rectangle in logical pixels. */
class Px(val c: Canvas) {
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    fun r(x: Int, y: Int, w: Int, h: Int, col: Int) {
        p.color = col
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
    }
    fun a(x: Int, y: Int, w: Int, h: Int, rgb: Int, alpha: Int) = r(x, y, w, h, (rgb and 0xFFFFFF) or (alpha shl 24))
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, col: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var e = dx + dy
        var guard = 0
        while (guard++ < 200) {
            r(x, y, 1, 1, col)
            if (x == x1 && y == y1) break
            val e2 = 2 * e
            if (e2 >= dy) { e += dy; x += sx }
            if (e2 <= dx) { e += dx; y += sy }
        }
    }
}

abstract class PixelChar {
    abstract val w: Int
    abstract val h: Int
    abstract fun draw(g: Px, t: Float, act: String, talking: Boolean)
}

private fun k(v: Long): Int = v.toInt()
private const val TAU = (PI * 2).toFloat()

/** Lumi - a lantern jellyfish (original design). */
class Lumi : PixelChar() {
    override val w = 44
    override val h = 52
    private val mask = ByteArray(44 * 52)
    private val bell = intArrayOf(k(0xFF7BE0FF), k(0xFF58B8FF), k(0xFF7C8CFF), k(0xFF5A5AD8))
    private val core = intArrayOf(k(0xFFFF5FA8), k(0xFFFF8A5C), k(0xFFFFD166), k(0xFFFFF3B0))
    private val tent = intArrayOf(k(0xFF8FD0FF), k(0xFF6AA6FF), k(0xFF8A7DFF))
    private val line = k(0xFF1B1440)
    private val eye = k(0xFF150F33)
    private val hi = k(0xFFE9FBFF)
    private val cheek = k(0xFFFF8FC7)

    override fun draw(g: Px, t: Float, act: String, talking: Boolean) {
        val sleeping = act == "sleep"
        val speed = if (act == "run") 1.6f else if (sleeping) 0.6f else 1f
        val ph = ((t * speed) % 6f) / 6f
        val br = sin(ph * TAU)
        val bob = (sin(ph * TAU + 1.2f) * 1.6f).roundToInt()
        val pk = if (act == "wave") 0.5f + 0.5f * sin(t * 9f) else 0f
        val pkS = pk * 0.7f
        val sx = 1f + 0.07f * br + 0.10f * pkS
        val sy = 1f - 0.07f * br - 0.10f * pkS
        val cx = w / 2f
        val cy = 19f + bob
        val rx = 12f * sx
        val ry = 11f * sy
        val cxi = cx.roundToInt()

        // dithered halo
        val haloR = 21f + 1.5f * br
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = (y - (cy + 3f)) / 1.1f
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < haloR && d > rx - 2f && ((x + y) and 1) == 0) {
                val a = 0.20f * (1f - (d - rx) / (haloR - rx)) + 0.05f * pkS
                if (a > 0.02f) g.a(x, y, 1, 1, 0x7CC8FF, (a * 255f).toInt())
            }
        }

        // tentacles
        val n = 5
        for (i in 0 until n) {
            val bx = (cx - rx * 0.62f + (i * rx * 1.24f) / (n - 1)).roundToInt()
            val by = (cy + ry * 0.55f).roundToInt()
            val len = 15 + (if (i % 2 == 1) 3 else 0) + (2f * br * (if (i % 2 == 1) -1f else 1f)).roundToInt()
            for (j in 0 until len) {
                val u = j.toFloat() / len
                val sw = sin(ph * TAU * 2f - j * 0.42f + i * 1.1f) * (0.6f + u * 3.2f)
                val x = (bx + sw).roundToInt()
                val y = by + j
                g.r(x, y, 1, 1, tent[minOf(2, (u * 3f).toInt())])
                if (j % 4 == 0 && j > 3) g.r(x + (if (i % 2 == 1) 1 else -1), y, 1, 1, tent[2])
            }
            val tipX = (bx + sin(ph * TAU * 2f - len * 0.42f + i * 1.1f) * 3.8f).roundToInt()
            g.r(tipX, by + len, 1, 1, core[1 + ((ph * 12f).toInt() + i) % 3])
        }

        // bell mask
        java.util.Arrays.fill(mask, 0)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            val inside = if (dy <= 0f) dx * dx + dy * dy <= 1f else {
                val skirt = 0.5f + 0.1f * sin((x - cx) * 1.6f + ph * TAU * 2f)
                dy <= skirt && abs(dx) <= 1f - dy * 0.25f
            }
            if (inside) mask[y * w + x] = 1
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x].toInt() != 0) continue
            val near = (x > 0 && mask[y * w + x - 1].toInt() == 1) || (x < w - 1 && mask[y * w + x + 1].toInt() == 1) ||
                (y > 0 && mask[(y - 1) * w + x].toInt() == 1) || (y < h - 1 && mask[(y + 1) * w + x].toInt() == 1)
            if (near) mask[y * w + x] = 2
        }
        for (y in 0 until h) for (x in 0 until w) {
            val m = mask[y * w + x].toInt()
            if (m == 0) continue
            if (m == 2) { g.r(x, y, 1, 1, line); continue }
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            var v = 0.5f + dy * 0.55f + dx * 0.18f
            v += (((x and 1) + 2 * (y and 1)) - 1.5f) * 0.06f
            g.r(x, y, 1, 1, if (v < 0.28f) bell[0] else if (v < 0.55f) bell[1] else if (v < 0.8f) bell[2] else bell[3])
        }
        for (p in arrayOf(floatArrayOf(-0.55f, -0.62f), floatArrayOf(-0.45f, -0.7f), floatArrayOf(-0.35f, -0.76f), floatArrayOf(-0.62f, -0.5f), floatArrayOf(-0.66f, -0.38f)))
            g.r((cx + p[0] * rx).roundToInt(), (cy + p[1] * ry).roundToInt(), 1, 1, hi)

        // lantern core
        val pulse = (if (sleeping) 0.2f else 0.5f + 0.5f * sin(ph * TAU * 3f + 0.6f)) + pk
        val lvl = (pulse * 2.2f).toInt().coerceIn(0, 3)
        val coreY = (cy - ry * 0.55f).roundToInt()
        g.r(cxi - 2, coreY - 1, 4, 3, core[maxOf(0, lvl - 1)])
        g.r(cxi - 1, coreY - 2, 2, 5, core[maxOf(0, lvl - 1)])
        g.r(cxi - 1, coreY - 1, 2, 3, core[lvl])
        g.r(cxi - 1, coreY, 1, 1, core[3])

        // face
        val blinking = sleeping || (ph > 0.70f && ph < 0.73f) || (ph > 0.76f && ph < 0.78f)
        val look = if (act == "sus") (if (sin(t * 1.9f) > 0f) 1 else -1) else 0
        val ey = (cy + ry * 0.12f).roundToInt()
        val ex = (rx * 0.42f).roundToInt()
        for (s in intArrayOf(-1, 1)) {
            val x0 = cxi + s * ex - 1 + look
            if (blinking) g.r(x0, ey + 1, 3, 1, eye)
            else {
                g.r(x0, ey - 1, 2, 4, eye); g.r(x0 - 1, ey, 4, 2, eye)
                g.r(x0, ey - 1, 1, 1, hi); g.r(x0 + 1, ey + 1, 1, 1, hi)
            }
            g.r(x0 + s * 3 - (if (s > 0) 0 else 1), ey + 3, 3, 1, cheek)
        }
        g.r(cxi - 1, ey + 4, 2, 1, eye)
        if (talking && ((t * 8f).toInt() % 2 == 0)) g.r(cxi - 1, ey + 5, 2, 1, eye)

        // orbiting motes
        for (i in 0 until 5) {
            val kk = 1 + (i % 2)
            val a = (i / 5f + ph * kk) * TAU
            val rr = 17f + (i * 7 % 4)
            val mx = (cx + kotlin.math.cos(a) * rr).roundToInt()
            val my = (cy + 4f + sin(a) * rr * 0.55f).roundToInt()
            if (((ph * 12f).toInt() + i) % 3 == 0) continue
            g.r(mx, my, 1, 1, k(0xFFFFF3B0))
            g.a(mx - 1, my, 1, 1, 0xFFF3B0, 110); g.a(mx + 1, my, 1, 1, 0xFFF3B0, 110)
            g.a(mx, my - 1, 1, 1, 0xFFF3B0, 110); g.a(mx, my + 1, 1, 1, 0xFFF3B0, 110)
        }
    }
}

/** Webb - a tiny jumping-spider hero that dangles on a silk thread (original design). */
class Webb : PixelChar() {
    override val w = 40
    override val h = 48
    private val outline = k(0xFF120D2E)
    private val d0 = k(0xFF251D52)
    private val d1 = k(0xFF3A2F7A)
    private val d2 = k(0xFF5645B0)
    private val gold = k(0xFFFFC247)
    private val scarf = k(0xFFFF7A3D)
    private val scarfD = k(0xFFD9531F)
    private val eyeC = k(0xFF0E0B22)
    private val hi = k(0xFFFFFFFF)
    private val silk = k(0xFFDFE8FF)

    private fun ell(g: Px, cx: Float, cy: Float, rx: Float, ry: Float, outlineOnly: Boolean) {
        val y0 = (cy - ry - 1f).toInt(); val y1 = (cy + ry + 1f).toInt()
        val x0 = (cx - rx - 1f).toInt(); val x1 = (cx + rx + 1f).toInt()
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            if (dx * dx + dy * dy > 1f) continue
            if (outlineOnly) g.r(x, y, 1, 1, outline)
            else {
                var v = 0.5f + dy * 0.45f + dx * 0.25f
                v += (((x and 1) + 2 * (y and 1)) - 1.5f) * 0.05f
                g.r(x, y, 1, 1, if (v < 0.35f) d2 else if (v < 0.65f) d1 else d0)
            }
        }
    }

    override fun draw(g: Px, t: Float, act: String, talking: Boolean) {
        val sleeping = act == "sleep"
        val moving = act == "walk" || act == "run"
        val ph = (t % 4f) / 4f
        val swing = sin(ph * TAU) * (if (sleeping) 1f else 3f)
        val bob = sin(ph * TAU * 2f) * 1f
        val bx = (20f + swing).roundToInt()
        val by = (22f + bob).roundToInt() + (if (act == "sit") 3 else 0)

        // silk thread
        val topY = by - 10
        for (y in 0..topY) {
            val u = if (topY == 0) 1f else y.toFloat() / topY
            g.r((20f + swing * 0.3f + (bx - 20f - swing * 0.3f) * u).roundToInt(), y, 1, 1, silk)
        }

        // legs (behind body)
        val spd = if (act == "run") 5f else 4f
        for (s in intArrayOf(-1, 1)) for (i in 0 until 4) {
            val wig = if (moving) sin(ph * TAU * spd + i * 1.6f + (if (s > 0) PI.toFloat() else 0f)) * 2.5f
                      else sin(ph * TAU * 2f + i) * 0.8f
            val sy0 = by + 1 + i * 3
            val sx0 = bx + s * (6 + (if (i == 0) 0 else 1))
            val kx = bx + s * (12 + (i % 2) * 2)
            var ky = (sy0 - 4 + wig).roundToInt()
            var fx = bx + s * (15 + i)
            var fy = (by + 17 + wig * 0.5f).roundToInt()
            if (act == "wave" && s < 0 && i == 0) {
                fx = bx - 16; fy = (by - 12 + sin(t * 9f) * 3f).roundToInt(); ky = by - 5
            }
            g.line(sx0, sy0, kx, ky, d0)
            g.line(kx, ky, fx, fy, d0)
            g.r(kx, ky, 1, 1, gold)
        }

        // body silhouette then fills
        ell(g, bx.toFloat(), by + 8f, 9f, 8f, true)
        ell(g, bx.toFloat(), by - 3f, 8f, 7f, true)
        ell(g, bx.toFloat(), by + 8f, 8f, 7f, false)
        ell(g, bx.toFloat(), by - 3f, 7f, 6f, false)

        // gold markings on abdomen
        g.r(bx - 1, by + 6, 2, 2, gold)
        g.r(bx - 2, by + 8, 4, 2, gold)
        g.r(bx - 1, by + 10, 2, 2, gold)

        // hero scarf + flapping tail
        g.r(bx - 7, by + 2, 14, 3, scarf)
        g.r(bx - 7, by + 4, 14, 1, scarfD)
        for (kk in 0 until 6) {
            val yy = by + 3 + (sin(ph * TAU * 2f - kk * 0.6f) * 1.5f).roundToInt()
            g.r(bx + 7 + kk, yy, 1, 2, if (kk % 2 == 0) scarf else scarfD)
        }

        // eyes
        val blinking = sleeping || (ph > 0.50f && ph < 0.54f)
        val look = if (act == "sus") (if (sin(t * 1.9f) > 0f) 1 else -1) else 0
        for (s in intArrayOf(-1, 1)) {
            val ex = if (s < 0) bx - 6 else bx + 2
            if (blinking) g.r(ex, by - 4, 4, 1, eyeC)
            else {
                g.r(ex, by - 6, 4, 4, eyeC)
                g.r(ex + 1 + (if (look > 0) 1 else 0), by - 6, 1, 1, hi)
                g.r(ex + 2 + (if (look < 0) -1 else 0), by - 3, 1, 1, hi)
            }
        }
        g.r(bx - 3, by - 10, 2, 2, eyeC)
        g.r(bx + 1, by - 10, 2, 2, eyeC)
        if (talking && ((t * 8f).toInt() % 2 == 0)) g.r(bx - 1, by, 2, 1, eyeC)
        if (sleeping && ((t * 1.5f).toInt() % 2 == 0)) {
            g.r(bx + 9, by - 12, 3, 1, hi); g.r(bx + 10, by - 11, 1, 1, hi); g.r(bx + 9, by - 10, 3, 1, hi)
        }
    }
}

private fun loadMascotFile(ctx: Context, n: String): Bitmap? = try {
    val f = File(ctx.filesDir, n)
    if (f.exists()) BitmapFactory.decodeFile(f.path) else null
} catch (e: Throwable) { null }

/** A pixel mascot: one or two frames drawn in Pixel Studio (frame 2 = blink / talking). */
class Custom(private val f1: Bitmap?, private val f2: Bitmap?) : PixelChar() {
    constructor(ctx: Context) : this(loadMascotFile(ctx, "mascot_user1.png"), loadMascotFile(ctx, "mascot_user2.png"))

    override val w = f1?.width ?: 32
    override val h = f1?.height ?: 32
    private val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

    fun ok(): Boolean = f1 != null

    override fun draw(g: Px, t: Float, act: String, talking: Boolean) {
        val b = f1 ?: return
        val sleeping = act == "sleep"
        val moving = act == "walk" || act == "run"
        val ph = (t % 4f) / 4f
        val breath = sin(ph * TAU)
        val hop = if (moving) abs(sin(t * (if (act == "run") 12f else 7f))) * 2f else 0f
        val sy = 1f + (if (sleeping) 0.05f else 0.03f) * breath
        val sx = 1f - 0.02f * breath
        val rot = if (act == "wave") 10f * sin(t * 9f) else if (act == "sus") 6f * sin(t * 2f) else 0f
        val alt = f2 != null && (sleeping || (ph > 0.60f && ph < 0.64f) || (talking && ((t * 8f).toInt() % 2 == 0)))
        val c = g.c
        c.save()
        c.translate(w / 2f, h.toFloat() - hop)
        c.rotate(rot)
        c.scale(sx, sy)
        c.translate(-w / 2f, -h.toFloat())
        c.drawBitmap(if (alt) f2!! else b, 0f, 0f, p)
        c.restore()
    }
}
