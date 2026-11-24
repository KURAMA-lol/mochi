package com.sahil.chibi

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A dynamic island at the top of the screen. Black pill with Bloub (Jeremy Perret, MIT) inside. It peeks for a few
 * seconds on charging and on Nana's replies, and a tap opens it into a small card (time, battery, mail, next reminder,
 * quick buttons). It only shows things; it never acts by itself. Lives in Nana's service, so it is there while Nana is on.
 */
class Island(private val ctx: Context, private val wm: WindowManager, private val onAct: (String) -> Unit) {
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("chibi_cfg", Context.MODE_PRIVATE)
        fun enabled(c: Context): Boolean = prefs(c).getBoolean("island", true)
        fun nudge(c: Context): Int = prefs(c).getInt("island_y", 0)
        fun set(c: Context, on: Boolean, y: Int) { prefs(c).edit().putBoolean("island", on).putInt("island_y", y.coerceIn(-12, 24)).apply() }
        fun battery(c: Context): Int {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            return if (l < 0 || s <= 0) -1 else l * 100 / s
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private val cW = dp(118); private val cH = dp(34)
    private val pW = dp(264); private val pH = dp(42)
    private val oW = dp(332); private val oH = dp(176)

    private var root: FrameLayout? = null
    private var shell: FrameLayout? = null
    private var bg: GradientDrawable? = null
    private var bloub: WebView? = null
    private var peekT: TextView? = null
    private var openC: LinearLayout? = null
    private var infoT: TextView? = null
    private var titleT: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var bReady = false
    private var mode = 0           // 0 pill, 1 peek, 2 open
    private var curW = cW; private var curH = cH
    private var anim: ValueAnimator? = null

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    private val sleepR = Runnable { if (mode == 0) js("Bloub.pause()") }
    private val backR = Runnable { if (mode == 1) morph(0) }
    private val aliveR = object : Runnable {
        override fun run() {
            if (attached && mode == 0) {
                val m = listOf("wink", "wide", "play").random()
                js("Bloub.resume();Bloub.state('$m')")
                main.postDelayed({ applyBloub(); }, 2200)
                main.postDelayed(sleepR, 4500)
            }
            main.postDelayed(this, 50_000)
        }
    }

    private fun js(code: String) { if (bReady) try { bloub?.evaluateJavascript(code, null) } catch (e: Throwable) {} }

    private fun applyBloub() {
        val p = ctx.getSharedPreferences("bloub", Context.MODE_PRIVATE)
        val col = (p.getString("color", "#3b93f0") ?: "#3b93f0").filter { it.isLetterOrDigit() || it == '#' }
        val sh = (p.getString("shape", "idle") ?: "idle").filter { it.isLetter() }
        js("Bloub.color('$col');Bloub.state('$sh')")
    }

    private fun wake(ms: Long = 7000) {
        js("Bloub.resume()")
        main.removeCallbacks(sleepR)
        main.postDelayed(sleepR, ms)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build() {
        if (root != null) return
        val r = FrameLayout(ctx)
        val s = FrameLayout(ctx)
        val g = GradientDrawable()
        g.setColor(0xFF000000.toInt())
        g.setStroke(dp(1), 0x1CFFFFFF)
        g.cornerRadius = cH / 2f
        s.background = g
        s.contentDescription = "Nana island"
        s.setOnClickListener {
            if (mode != 2) morph(2)
            else { js("Bloub.resume();Bloub.state('wink')"); main.postDelayed({ applyBloub() }, 1400); wake() }
        }
        val w = object : WebView(ctx) { override fun dispatchTouchEvent(e: MotionEvent): Boolean = false }
        w.setBackgroundColor(0)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.javaScriptEnabled = true
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(v: WebView?, u: String?) { bReady = true; applyBloub(); wake(6000) }
        }
        w.loadUrl("file:///android_asset/bloub/index.html")
        s.addView(w, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.START))
        val pt = TextView(ctx)
        pt.setTextColor(0xFFFFFFFF.toInt()); pt.textSize = 13f; pt.maxLines = 1
        pt.ellipsize = android.text.TextUtils.TruncateAt.END
        pt.gravity = Gravity.CENTER_VERTICAL
        pt.alpha = 0f
        s.addView(pt, FrameLayout.LayoutParams(dp(100), cH, Gravity.TOP or Gravity.START))
        // open card
        val oc = LinearLayout(ctx)
        oc.orientation = LinearLayout.VERTICAL
        oc.setPadding(dp(18), dp(16), dp(16), dp(14))
        oc.alpha = 0f
        oc.visibility = View.GONE
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.addView(View(ctx), LinearLayout.LayoutParams(dp(68), dp(56)))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        val tt = TextView(ctx)
        tt.setTextColor(0xFFFFFFFF.toInt()); tt.textSize = 17f; tt.typeface = Typeface.DEFAULT_BOLD
        col.addView(tt)
        val it2 = TextView(ctx)
        it2.setTextColor(0xFF9AA0AC.toInt()); it2.textSize = 12.5f
        it2.setPadding(0, dp(2), 0, 0)
        it2.text = "Tap me, I wink"
        col.addView(it2)
        head.addView(col, LinearLayout.LayoutParams(0, dp(56), 1f))
        oc.addView(head)
        val info = TextView(ctx)
        info.setTextColor(0xFFC9CDD6.toInt()); info.textSize = 13f
        info.setPadding(0, dp(10), 0, 0)
        oc.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        for ((key, label) in listOf("chat" to "Chat", "browser" to "Browser", "settings" to "Settings")) {
            val b = TextView(ctx)
            b.text = label; b.textSize = 12.5f; b.gravity = Gravity.CENTER
            b.setTextColor(0xFFFFFFFF.toInt()); b.typeface = Typeface.DEFAULT_BOLD
            b.background = GradientDrawable().apply { setColor(0xFF1E1F24.toInt()); cornerRadius = dp(18).toFloat() }
            b.setOnClickListener { morph(0); onAct(key) }
            row.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (key != "chat") marginStart = dp(8) })
        }
        oc.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        s.addView(oc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(s, FrameLayout.LayoutParams(cW, cH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        r.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && mode == 2) morph(0)
            false
        }
        root = r; shell = s; bg = g; bloub = w; peekT = pt; openC = oc; infoT = info; titleT = tt
        val sbId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sbId > 0) ctx.resources.getDimensionPixelSize(sbId) else dp(28)
        lp = WindowManager.LayoutParams(
            cW, cH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ((sb - cH) / 2 + dp(nudge(ctx))).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun apply(w: Int, h: Int) {
        val s = shell ?: return
        curW = w; curH = h
        val e = ((h - cH).toFloat() / (oH - cH)).coerceIn(0f, 1f)
        bg?.cornerRadius = minOf(lerp(cH / 2, dp(34), e), minOf(w, h) / 2).toFloat()
        val p = s.layoutParams as FrameLayout.LayoutParams
        p.width = w; p.height = h
        s.layoutParams = p
        val bs = lerp(dp(26), dp(56), e)
        val bl = lerp(dp(10), dp(18), e)
        val bt = lerp((h - bs) / 2, dp(16), e)
        bloub?.let { v ->
            val q = v.layoutParams as FrameLayout.LayoutParams
            q.width = bs; q.height = bs; q.leftMargin = bl; q.topMargin = bt
            v.layoutParams = q
        }
        peekT?.let { v ->
            val q = v.layoutParams as FrameLayout.LayoutParams
            q.leftMargin = bl + bs + dp(8); q.width = (w - q.leftMargin - dp(16)).coerceAtLeast(dp(20)); q.height = h
            v.layoutParams = q
            v.alpha = if (mode == 1) ((w - cW).toFloat() / (pW - cW) - 0.55f).div(0.45f).coerceIn(0f, 1f) else 0f
        }
        openC?.let { v ->
            val a = if (mode == 2) ((h - dp(90)).toFloat() / (oH - dp(90))).coerceIn(0f, 1f) else 0f
            v.alpha = a
            v.visibility = if (a > 0f) View.VISIBLE else View.GONE
        }
    }

    private fun resizeWindow(w: Int, h: Int) {
        val l = lp ?: return
        l.width = w; l.height = h
        if (attached) try { wm.updateViewLayout(root, l) } catch (ex: Exception) {}
    }

    private fun fill() {
        val c = ctx
        titleT?.text = try { ConfigStore.load(c).persona.name } catch (e: Exception) { "Nana" }
        val sb = StringBuilder()
        val b = battery(c)
        sb.append(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
        if (b >= 0) sb.append("  \u00b7  ").append(b).append("% battery")
        try { val u = Mail.unreadCached(c); if (u >= 0) sb.append("\nMail: ").append(u).append(" unread") } catch (e: Throwable) {}
        try { val n = NanaWidget.nextReminder(c).trim(); if (n.isNotBlank()) sb.append("\n").append(n.take(44)) } catch (e: Throwable) {}
        infoT?.text = sb.toString()
    }

    private fun morph(to: Int) {
        if (!attached) return
        anim?.cancel()
        main.removeCallbacks(backR)
        val tw = when (to) { 2 -> oW; 1 -> pW; else -> cW }
        val th = when (to) { 2 -> oH; 1 -> pH; else -> cH }
        val bigger = tw >= curW && th >= curH
        mode = to
        if (to == 2) fill()
        if (to != 0) wake(9000)
        if (bigger) resizeWindow(tw, th)
        val fw = curW; val fh = curH
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (to == 0) 300L else 420L
        a.interpolator = DecelerateInterpolator(1.8f)
        a.addUpdateListener { f -> val v = f.animatedValue as Float; apply(lerp(fw, tw, v), lerp(fh, th, v)) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(an: android.animation.Animator) {
                if (cancelled) return
                if (!bigger) resizeWindow(tw, th)
                if (to == 0) main.postDelayed(sleepR, 1500)
            }
        })
        anim = a
        a.start()
    }

    /** A short message in the island, for a few seconds. Ignored while the card is open. */
    fun peek(text: String, ms: Long = 3200) {
        main.post {
            if (!attached || mode == 2) return@post
            peekT?.text = text
            js("Bloub.resume();Bloub.state('wide')")
            main.postDelayed({ applyBloub() }, 1500)
            morph(1)
            main.removeCallbacks(backR)
            main.postDelayed(backR, ms)
        }
    }

    fun show() {
        if (attached || !android.provider.Settings.canDrawOverlays(ctx)) return
        build()
        mode = 0; curW = cW; curH = cH
        apply(cW, cH); resizeWindow(cW, cH)
        try { wm.addView(root, lp); attached = true } catch (ex: Exception) { attached = false; return }
        main.postDelayed(aliveR, 40_000)
    }

    fun hide() {
        anim?.cancel()
        main.removeCallbacksAndMessages(null)
        if (attached) { try { wm.removeView(root) } catch (ex: Exception) {}; attached = false }
    }

    fun destroy() {
        hide()
        try { bloub?.destroy() } catch (e: Throwable) {}
        bloub = null; root = null; shell = null; bReady = false
    }
}
