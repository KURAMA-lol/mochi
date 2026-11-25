package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.widget.LinearLayout

/** Input row with a soft light beam that rides its border while active (focused or busy). */
class BeamRow(c: Context) : LinearLayout(c) {
    var ink: Int = 0xFFFFFFFF.toInt()
        set(v) { field = v; shader = null; invalidate() }
    var active: Boolean = false
        set(v) { if (field != v) { field = v; if (v) postInvalidateOnAnimation() else invalidate() } }
    var radiusDp: Float = 26f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val mx = Matrix()
    private var shader: SweepGradient? = null
    private var sw = 0; private var sh = 0
    private var t0 = 0L

    init { setWillNotDraw(false) }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!active || visibility != View.VISIBLE || width == 0) return
        val d = resources.displayMetrics.density
        if (shader == null || sw != width || sh != height) {
            sw = width; sh = height
            val a = ink and 0x00FFFFFF
            shader = SweepGradient(width / 2f, height / 2f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, (0x00 shl 24) or a, (0xE6 shl 24) or a, Color.TRANSPARENT, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 0.62f, 0.78f, 0.80f, 1f))
        }
        if (t0 == 0L) t0 = System.nanoTime()
        val ang = ((System.nanoTime() - t0) / 1_000_000L % 2600L) / 2600f * 360f
        mx.setRotate(ang, width / 2f, height / 2f)
        shader!!.setLocalMatrix(mx)
        p.shader = shader
        p.strokeWidth = 1.6f * d
        val h = p.strokeWidth / 2f + 0.5f * d
        rect.set(h, h, width - h, height - h)
        val r = radiusDp * d
        canvas.drawRoundRect(rect, r, r, p)
        postInvalidateOnAnimation()
    }
}
