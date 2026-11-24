package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small "searching" orb for the chat header: a cloud of points on a slowly turning sphere with a thin scan band that
 * lights the points it crosses. Same motion as the web component used in Settings, drawn natively (sm variant).
 * It only runs while visible.
 */
class OrbView(ctx: Context) : View(ctx) {
    var ink: Int = 0xFFEDEDED.toInt()
        set(v) { field = v; body = null; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var body: RadialGradient? = null
    private var t = 0f
    private var last = 0L
    private val n = 34
    private val pts = Array(n) { i ->
        val ga = PI * (3 - sqrt(5.0))
        val y = 1 - ((i + 0.5) / n) * 2
        val r = sqrt(1 - y * y)
        val th = i * ga
        floatArrayOf((cos(th) * r).toFloat(), y.toFloat(), (sin(th) * r).toFloat())
    }

    private fun clamp01(x: Float) = max(0f, min(1f, x))
    private fun smooth(x: Float): Float { val v = clamp01(x); return v * v * (3 - 2 * v) }
    private fun withA(a: Float): Int = (ink and 0x00FFFFFF) or ((clamp01(a) * 255f).toInt() shl 24)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) { last = 0L; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); body = null }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime() / 1_000_000L
        val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1000f)
        last = now
        t += dt
        val s0 = min(width, height).toFloat()
        if (s0 <= 0f) return
        val c = s0 / 2f
        val rad = s0 * 0.37f
        val unit = s0 / 20f
        val sweep = 3f; val active = 0.8f
        val yaw = t * 0.3f; val pitch = 0.38f
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val persp = 0.12f
        val q = ((t / sweep) % 1f + 1f) % 1f
        val pass = kotlin.math.floor(t / sweep)
        val s = 1.1f - 2.2f * smooth(q / active)
        val env = if (q < active) 1f else 1f - smooth((q - active) / 0.2f)
        val tilt = 0.42f + 0.06f * sin(t * 0.4f)
        val prec = 0.5f + pass * 2.1f + q * 0.6f
        val nx = sin(tilt) * cos(prec); val ny = cos(tilt); val nz = sin(tilt) * sin(prec)
        val w = 0.2f; val trail = 0.3f

        var b = body
        if (b == null) {
            b = RadialGradient(c - rad * 0.25f, c - rad * 0.3f, rad * 1.15f, withA(0.1f), withA(0f), Shader.TileMode.CLAMP)
            body = b
        }
        paint.shader = b
        canvas.drawCircle(c, c, rad * 1.15f, paint)
        paint.shader = null

        val px = FloatArray(n); val py = FloatArray(n); val dep = FloatArray(n); val lit = FloatArray(n)
        for (i in 0 until n) {
            val p = pts[i]
            val x1 = p[0] * cy + p[2] * sy; val z1 = -p[0] * sy + p[2] * cy
            val y2 = p[1] * cp - z1 * sp; val z2 = p[1] * sp + z1 * cp
            val h = x1 * nx + y2 * ny + z2 * nz
            val d = h - s
            var l = exp(-((d / w) * (d / w)))
            if (d > 0) l = max(l, exp(-d / trail) * 0.6f)
            l *= env
            val k = (1 - persp * 0.5f) / (1 - z2 * persp)
            px[i] = c + x1 * rad * k; py[i] = c + (-y2) * rad * k
            dep[i] = (z2 + 1) / 2; lit[i] = l
        }
        val order = (0 until n).sortedBy { dep[it] }
        paint.style = Paint.Style.FILL
        for (i in order) {
            val base = 0.3f + 0.3f * dep[i]
            paint.color = withA(base + (1 - base) * lit[i] * 0.9f)
            val r = (0.72f + 0.35f * dep[i] + 0.3f * lit[i]) * unit
            canvas.drawCircle(px[i], py[i], r, paint)
        }
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
