package com.sahil.chibi

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

/**
 * Small Markdown renderer for chat bubbles: headings, bold, italic, strike, inline code, fenced code blocks,
 * bullet / numbered / checkbox lists, quotes, rules, tables (drawn in monospace) and clickable links.
 */
object Md {
    private const val EX = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private val INLINE = Regex(
        "\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|`([^`\\n]+)`|\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)" +
        "|(?<![\\w*])\\*([^*\\s](?:[^*\\n]*?[^*\\s])?)\\*(?![\\w*])|(?<![\\w_])_([^_\\s](?:[^_\\n]*?[^_\\s])?)_(?![\\w_])" +
        "|(https?://[^\\s<>\"']+)"
    )
    private val HEAD = Regex("^(#{1,6})\\s+(.*)$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val RULE = Regex("^([-*_])\\1{2,}$")
    private val SEP = Regex("^:?-+:?$")

    private fun codeSpans(sb: SpannableStringBuilder, st: Int) {
        if (sb.length <= st) return
        sb.setSpan(TypefaceSpan("monospace"), st, sb.length, EX)
        sb.setSpan(BackgroundColorSpan(0x33FFFFFF), st, sb.length, EX)
        sb.setSpan(RelativeSizeSpan(0.92f), st, sb.length, EX)
    }

    private fun style(sb: SpannableStringBuilder, st: Int, what: Any) {
        if (sb.length > st) sb.setSpan(what, st, sb.length, EX)
    }

    private fun link(sb: SpannableStringBuilder, st: Int, url: String, open: (String) -> Unit) {
        if (sb.length <= st) return
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(w: View) { open(url) }
            override fun updateDrawState(ds: TextPaint) { ds.color = ds.linkColor; ds.isUnderlineText = true }
        }, st, sb.length, EX)
    }

    private fun inline(sb: SpannableStringBuilder, s: String, open: (String) -> Unit) {
        var last = 0
        for (m in INLINE.findAll(s)) {
            sb.append(s, last, m.range.first)
            val st = sb.length
            val g = m.groups
            when {
                g[1] != null -> { inline(sb, g[1]!!.value, open); style(sb, st, StyleSpan(Typeface.BOLD)) }
                g[2] != null -> { inline(sb, g[2]!!.value, open); style(sb, st, StyleSpan(Typeface.BOLD)) }
                g[3] != null -> { inline(sb, g[3]!!.value, open); style(sb, st, StrikethroughSpan()) }
                g[4] != null -> { sb.append(g[4]!!.value); codeSpans(sb, st) }
                g[5] != null -> { inline(sb, g[5]!!.value, open); link(sb, st, g[6]!!.value, open) }
                g[7] != null -> { inline(sb, g[7]!!.value, open); style(sb, st, StyleSpan(Typeface.ITALIC)) }
                g[8] != null -> { inline(sb, g[8]!!.value, open); style(sb, st, StyleSpan(Typeface.ITALIC)) }
                else -> {
                    val core = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '\'', '"')
                    sb.append(core); link(sb, st, core, open)
                    sb.append(m.value.substring(core.length))
                }
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
    }

    private fun gap(sb: SpannableStringBuilder) {
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
    }

    private fun cells(row: String): List<String> =
        row.trim().trim('|').split("|").map { it.trim().replace("**", "").replace("`", "") }

    fun render(src: String, open: (String) -> Unit): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.startsWith("```")) {
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.add(lines[i]); i++ }
                i++
                gap(sb)
                val st = sb.length
                sb.append(code.joinToString("\n").ifEmpty { " " })
                codeSpans(sb, st)
                continue
            }
            if (t.isEmpty()) {
                gap(sb)
                if (sb.isNotEmpty() && !(sb.length >= 2 && sb[sb.length - 2] == '\n')) sb.append('\n')
                i++; continue
            }
            if (t.startsWith("|") && t.count { it == '|' } >= 2) {
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val c = cells(lines[i])
                    if (!c.all { SEP.matches(it) }) rows.add(c)
                    i++
                }
                if (rows.isNotEmpty()) {
                    val n = rows.maxOf { it.size }
                    val w = IntArray(n) { k -> rows.maxOf { (it.getOrNull(k) ?: "").length } }
                    gap(sb)
                    val st = sb.length
                    for ((ri, r) in rows.withIndex()) {
                        if (ri > 0) sb.append('\n')
                        val rs = sb.length
                        sb.append((0 until n).joinToString(" │ ") { k -> (r.getOrNull(k) ?: "").padEnd(w[k]) })
                        if (ri == 0) {
                            sb.setSpan(StyleSpan(Typeface.BOLD), rs, sb.length, EX)
                            if (rows.size > 1) sb.append('\n').append((0 until n).joinToString("─┼─") { k -> "─".repeat(w[k]) })
                        }
                    }
                    codeSpans(sb, st)
                }
                continue
            }
            val h = HEAD.find(t)
            if (h != null) {
                gap(sb)
                val st = sb.length
                inline(sb, h.groupValues[2], open)
                style(sb, st, StyleSpan(Typeface.BOLD))
                style(sb, st, RelativeSizeSpan(when (h.groupValues[1].length) { 1 -> 1.3f; 2 -> 1.18f; else -> 1.07f }))
                i++; continue
            }
            if (RULE.matches(t)) {
                gap(sb)
                val st = sb.length
                sb.append("──────────")
                style(sb, st, ForegroundColorSpan(0x66FFFFFF))
                i++; continue
            }
            if (t.startsWith(">")) {
                gap(sb)
                val st = sb.length
                sb.append("│ ")
                inline(sb, t.removePrefix(">").trim(), open)
                style(sb, st, StyleSpan(Typeface.ITALIC))
                style(sb, st, ForegroundColorSpan(0xCCFFFFFF.toInt()))
                i++; continue
            }
            val l = LIST.find(raw)
            if (l != null) {
                gap(sb)
                val depth = l.groupValues[1].replace("\t", "  ").length / 2
                val mk = l.groupValues[2]
                var body = l.groupValues[3]
                body = body.replace(Regex("^\\[ \\]\\s*"), "☐ ").replace(Regex("^\\[[xX]\\]\\s*"), "☑ ")
                sb.append("    ".repeat(depth.coerceAtMost(4)))
                sb.append(if (mk[0].isDigit()) mk.replace(')', '.') else if (depth > 0) "◦" else "•").append(' ')
                inline(sb, body, open)
                i++; continue
            }
            gap(sb)
            inline(sb, raw, open)
            i++
        }
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }
}
