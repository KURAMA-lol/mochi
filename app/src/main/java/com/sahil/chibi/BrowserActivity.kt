package com.sahil.chibi

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Shows Nana's own browser so you can watch it work, log in to sites, or browse yourself. */
class BrowserActivity : Activity() {
    private lateinit var et: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0F12.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        et = EditText(this).apply {
            setSingleLine()
            hint = "Search or type a link"
            setTextColor(0xFFF2F3F5.toInt())
            setHintTextColor(0x88F2F3F5.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = Button(this).apply { text = "Go" }
        bar.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val holder = FrameLayout(this)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val w = AgentBrowser.view(this)
        (w.parent as? ViewGroup)?.removeView(w)
        holder.addView(w, FrameLayout.LayoutParams(-1, -1))
        go.setOnClickListener { goTo(et.text.toString()) }
        et.setOnEditorActionListener { _, _, _ -> goTo(et.text.toString()); true }
    }

    private fun goTo(q: String) {
        val s = q.trim()
        if (s.isEmpty()) return
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") -> "https://duckduckgo.com/?q=" + Uri.encode(s)
            else -> "https://" + s
        }
        AgentBrowser.loadUrl(this, u)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (AgentBrowser.canGoBack()) AgentBrowser.goBackUi() else super.onBackPressed()
    }

    override fun onDestroy() {
        AgentBrowser.release(this)
        super.onDestroy()
    }
}
