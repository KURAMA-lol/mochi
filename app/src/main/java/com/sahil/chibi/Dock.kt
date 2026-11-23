package com.sahil.chibi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small frosted-glass pill at the bottom of the screen. Tap it and it grows into a rounded card of quick actions;
 * tap outside or pick something and it shrinks back. Everything is one overlay window with one animated surface.
 */
class Dock(private val ctx: Context, private val wm: WindowManager, private val onAct: (String) -> Unit) {
    companion object {
        fun enabled(c: Context): Boolean = c.getSharedPreferences("chibi_cfg", Context.MODE_PRIVATE).getBoolean("dock", true)
        fun set(c: Context, v: Boolean) { c.getSharedPreferences("chibi_cfg", Context.MODE_PRIVATE).edit().putBoolean("dock", v).apply() }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private val pw = dp(132)
    private val ph = dp(26)
    private val cw = dp(276)
    private val ch = dp(176)
    private val pad = dp(8)

    private var root: FrameLayout? = null
    private var shell: FrameLayout? = null
    private var content: LinearLayout? = null
    private var bg: GradientDrawable? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var open = false
    private var frac = 0f
    private var anim: ValueAnimator? = null

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    private fun build() {
        if (root != null) return
        val r = FrameLayout(ctx)
        val s = FrameLayout(ctx)
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF4F8F9FA.toInt(), 0xE8D6DBDE.toInt()))
        g.setStroke(dp(1), 0xAAFFFFFF.toInt())
        g.cornerRadius = ph / 2f
        s.background = g
        s.contentDescription = "Nana quick menu"
        s.setOnClickListener { if (!open) toggle(true) }
        // the little notch on top of the surface
        val notch = View(ctx)
        notch.background = GradientDrawable().apply { setColor(0x2E000000); cornerRadius = dp(2).toFloat() }
        s.addView(notch, FrameLayout.LayoutParams(dp(22), dp(3), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.alpha = 0f
        c.visibility = View.GONE
        c.setPadding(dp(10), dp(18), dp(10), dp(10))
        val rows = listOf(
            listOf(Triple("chat", "Chat", R.drawable.ni_chat), Triple("browser", "Browser", R.drawable.ni_globe), Triple("screen", "Screen", R.drawable.ni_eye)),
            listOf(Triple("remind", "Remind", R.drawable.ni_alarm), Triple(if (Mail.ready(ctx)) "mail" else "battery", if (Mail.ready(ctx)) "Mail" else "Battery", if (Mail.ready(ctx)) R.drawable.ni_doc else R.drawable.ni_battery), Triple("settings", "Settings", R.drawable.ni_settings))
        )
        for (row in rows) {
            val lr = LinearLayout(ctx)
            lr.orientation = LinearLayout.HORIZONTAL
            for ((key, label, icon) in row) lr.addView(tile(key, label, icon), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            c.addView(lr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        s.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(s, FrameLayout.LayoutParams(pw, ph, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = pad / 2 })
        r.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && open) toggle(false)
            false
        }
        root = r; shell = s; content = c; bg = g
        lp = WindowManager.LayoutParams(
            pw + pad, ph + pad, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(22) }
    }

    private fun tile(key: String, label: String, icon: Int): View {
        val t = LinearLayout(ctx)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(0xFF2B3136.toInt())
        t.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 11f
        tv.setTextColor(0xFF3A4147.toInt())
        tv.setPadding(0, dp(5), 0, 0)
        t.addView(tv)
        t.setOnClickListener { toggle(false); onAct(key) }
        t.contentDescription = label
        return t
    }

    private fun apply(f: Float) {
        val s = shell ?: return
        val w = lerp(pw, cw, f)
        val h = lerp(ph, ch, f)
        val rr = lerp(ph / 2, dp(28), f).coerceAtMost(minOf(w, h) / 2)
        bg?.cornerRadius = rr.toFloat()
        val p = s.layoutParams as FrameLayout.LayoutParams
        p.width = w; p.height = h
        s.layoutParams = p
        val a = ((f - 0.45f) / 0.55f).coerceIn(0f, 1f)
        content?.alpha = a
        content?.visibility = if (a > 0f) View.VISIBLE else View.GONE
    }

    private fun resizeWindow(big: Boolean) {
        val l = lp ?: return
        l.width = (if (big) cw else pw) + pad
        l.height = (if (big) ch else ph) + pad
        if (attached) try { wm.updateViewLayout(root, l) } catch (ex: Exception) {}
    }

    fun toggle(expand: Boolean) {
        if (!attached) return
        open = expand
        anim?.cancel()
        if (expand) resizeWindow(true)
        val from = frac
        val to = if (expand) 1f else 0f
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = if (expand) 380L else 260L
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { frac = it.animatedValue as Float; apply(frac) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(an: android.animation.Animator) { if (!cancelled && !open) resizeWindow(false) }
        })
        anim = a
        a.start()
    }

    fun show() {
        if (attached || !android.provider.Settings.canDrawOverlays(ctx)) return
        build()
        frac = 0f; open = false
        apply(0f); resizeWindow(false)
        try { wm.addView(root, lp); attached = true } catch (ex: Exception) { attached = false }
    }

    fun hide() {
        anim?.cancel()
        open = false; frac = 0f
        if (attached) { try { wm.removeView(root) } catch (ex: Exception) {}; attached = false }
        apply(0f)
    }

    fun destroy() { hide(); root = null; shell = null; content = null }
}
