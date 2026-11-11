package com.sahil.chibi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Live agent timeline: shows what Nana is doing, stage by stage (done / working / waiting).
 * Tap a stage to inspect its details. When the task ends it collapses into a one-line summary.
 */
class Timeline(ctx: Context, private val request: String) : LinearLayout(ctx) {
    private val dn = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dn).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private class Dot(ctx: Context, private val dn: Float) : View(ctx) {
        var state = 0 // 0 waiting, 1 working, 2 done, 3 failed
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = 7f * dn
            p.strokeCap = Paint.Cap.ROUND
            when (state) {
                2, 3 -> {
                    p.style = Paint.Style.FILL; p.color = if (state == 2) 0xFFE8E9EC.toInt() else 0xFFE9778F.toInt()
                    c.drawCircle(cx, cy, r, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * dn; p.color = 0xFF0E0F12.toInt()
                    if (state == 2) {
                        c.drawLine(cx - 3f * dn, cy, cx - 1f * dn, cy + 2.4f * dn, p)
                        c.drawLine(cx - 1f * dn, cy + 2.4f * dn, cx + 3.2f * dn, cy - 2.4f * dn, p)
                    } else {
                        c.drawLine(cx - 2.6f * dn, cy - 2.6f * dn, cx + 2.6f * dn, cy + 2.6f * dn, p)
                        c.drawLine(cx - 2.6f * dn, cy + 2.6f * dn, cx + 2.6f * dn, cy - 2.6f * dn, p)
                    }
                }
                1 -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * dn; p.color = Theme.ac
                    c.drawCircle(cx, cy, r, p)
                    val ph = (SystemClock.uptimeMillis() % 1100L) / 1100f
                    val rr = (2f + 2.8f * (0.5f + 0.5f * sin(ph * 2f * PI.toFloat()))) * dn
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, rr, p)
                    postInvalidateOnAnimation()
                }
                else -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * dn; p.color = 0x55FFFFFF
                    c.drawCircle(cx, cy, r, p)
                }
            }
        }
    }

    private inner class Row(var title: String, var state: Int) {
        val dot = Dot(context, dn)
        val line = View(context).apply { setBackgroundColor(0x33FFFFFF) }
        val tv = TextView(context).apply { textSize = 13.5f; minHeight = dp(18); gravity = Gravity.CENTER_VERTICAL }
        val tm = TextView(context).apply { textSize = 11f; setTextColor(0x88F2F3F5.toInt()) }
        val det = TextView(context).apply {
            textSize = 11.5f; typeface = Typeface.MONOSPACE; setTextColor(0xB3F2F3F5.toInt()); setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        val box = LinearLayout(context)
        var detail = ""
        var t0 = 0L
        var ms = -1L
        var open = false

        init {
            box.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            left.addView(dot, lp(dp(18), dp(18)))
            left.addView(line, lp(dp(1), 0, 1f))
            val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(0), 0, dp(10)) }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(tm, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            right.addView(head); right.addView(det)
            box.addView(left, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            box.addView(right, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.setOnClickListener { open = !open; refresh() }
            refresh()
        }

        fun refresh() {
            dot.state = state; dot.invalidate()
            tv.text = title
            tv.setTextColor(when (state) { 0 -> 0x88F2F3F5.toInt(); 1 -> 0xFFFFFFFF.toInt(); 3 -> 0xFFFFB4C0.toInt(); else -> 0xFFF2F3F5.toInt() })
            tv.typeface = if (state == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tm.text = if (ms >= 0 && state >= 2) fmt(ms) else ""
            if (open) { det.visibility = View.VISIBLE; det.text = if (detail.isEmpty()) "No details for this step." else detail }
            else det.visibility = View.GONE
        }
    }

    private fun fmt(ms: Long) = if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)

    private val header = TextView(ctx).apply { textSize = 11.5f; setTextColor(0xAAF2F3F5.toInt()); setPadding(0, 0, 0, dp(8)) }
    private val rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val order = ArrayList<Row>()
    private val start = SystemClock.uptimeMillis()
    private val r1: Row
    private val prep: Row
    private var analyse: Row? = null
    private var lastTool: Row? = null
    private var tools = 0
    private var finished = false
    private var summary = ""

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val now = SystemClock.uptimeMillis()
            for (r in order) if (r.state == 1) r.tm.text = fmt(now - r.t0)
            header.text = "Nana is working · " + fmt(now - start) + " · tap a step to inspect"
            postDelayed(this, 400)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(0))
        background = GradientDrawable().apply { setColor(0x0FFFFFFF); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x1FFFFFFF) }
        addView(header)
        addView(rowsBox)
        val r0 = Row("Request received", 2).apply { detail = request.take(400) }
        r1 = Row("Understanding your request", 1).apply { t0 = start }
        prep = Row("Preparing response", 0)
        for (r in listOf(r0, r1, prep)) { order.add(r); rowsBox.addView(r.box) }
        fixLines()
        header.text = "Nana is working · tap a step to inspect"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!finished) post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick) }

    private fun fixLines() { for (r in order) r.line.visibility = if (r === order.last()) View.INVISIBLE else View.VISIBLE }

    private fun insertBefore(row: Row, before: Row) {
        val i = order.indexOf(before)
        order.add(i, row); rowsBox.addView(row.box, i); fixLines()
    }

    private fun label(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("search") -> "Searching the web"
            n.contains("screen_read") -> "Reading the screen"
            n.contains("read") -> "Reading the page"
            n.contains("tap") || n.contains("click") -> "Tapping on screen"
            n.contains("type") -> "Typing"
            n.contains("scroll") -> "Scrolling"
            n.contains("launch") -> "Opening an app"
            n.contains("open") || n.contains("url") -> "Opening a page"
            n.contains("alarm") || n.contains("timer") || n.contains("remind") -> "Setting it up"
            n.contains("status") -> "Checking phone status"
            n.contains("dial") || n.contains("call") -> "Preparing the call"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    fun stepCount() = tools

    fun onEvent(kind: String, text: String) {
        if (finished) return
        val now = SystemClock.uptimeMillis()
        when (kind) {
            "think", "check" -> if (tools > 0) {
                val a = analyse ?: Row("Analysing results", 0).also { analyse = it; insertBefore(it, prep) }
                a.title = if (kind == "check") "Checking my work" else "Analysing results"
                a.state = 1; a.t0 = now; a.ms = -1; a.refresh()
            }
            "tool" -> {
                if (r1.state == 1) { r1.state = 2; r1.ms = now - r1.t0; r1.refresh() }
                analyse?.let { it.state = 0; it.ms = -1; it.title = "Analysing results"; it.refresh() }
                val row = Row(label(text.substringBefore('\u0001')), 1)
                row.detail = text.substringAfter('\u0001', "").take(500); row.t0 = now
                insertBefore(row, analyse ?: prep)
                lastTool = row; tools++
            }
            "ok", "fail" -> lastTool?.let {
                it.state = if (kind == "ok") 2 else 3; it.ms = now - it.t0
                it.detail += (if (it.detail.isEmpty()) "" else "\n") + "-> " + text
                it.refresh()
            }
        }
    }

    /** status: done | stopped | failed. Collapses the card to a one-line summary (tap it to expand). */
    fun finish(status: String) {
        if (finished) return
        finished = true
        removeCallbacks(tick)
        val now = SystemClock.uptimeMillis()
        for (r in order) {
            if (r.state == 1) {
                r.ms = now - r.t0
                r.state = if (status == "done") 2 else 3
                if (status != "done") r.detail += (if (r.detail.isEmpty()) "" else "\n") + "-> " + (if (status == "stopped") "Stopped" else "Failed")
            } else if (r.state == 0 && status == "done") { r.state = 2 }
            r.refresh()
        }
        val n = tools.toString() + (if (tools == 1) " step" else " steps")
        summary = when (status) {
            "done" -> "Done in " + fmt(now - start) + " · " + n
            "stopped" -> "Stopped · " + n
            else -> "Failed · " + n
        }
        rowsBox.visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(10))
        header.setPadding(0, 0, 0, 0)
        refreshHeader()
        header.setOnClickListener {
            rowsBox.visibility = if (rowsBox.visibility == View.GONE) View.VISIBLE else View.GONE
            header.setPadding(0, 0, 0, if (rowsBox.visibility == View.GONE) 0 else dp(8))
            refreshHeader()
        }
    }

    private fun refreshHeader() {
        header.text = summary + (if (rowsBox.visibility == View.GONE) "  \u25BE" else "  \u25B4")
    }
}
