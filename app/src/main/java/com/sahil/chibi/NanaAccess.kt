package com.sahil.chibi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Lets Nana read and operate the screen (only when the user turns this service on in Accessibility settings). */
class NanaAccess : AccessibilityService() {
    companion object {
        @Volatile var inst: NanaAccess? = null
    }

    private val nodes = ArrayList<AccessibilityNodeInfo>()

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** The app on screen, never Nana's own windows (chat panel, bubble, floating character). */
    private fun targetRoot(): AccessibilityNodeInfo? {
        try {
            var best: AccessibilityNodeInfo? = null
            var bestLayer = -1
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                if (w.layer > bestLayer) { bestLayer = w.layer; best = r }
            }
            if (best != null) return best
        } catch (e: Exception) {}
        val r = rootInActiveWindow
        return if (r != null && r.packageName?.toString() != packageName) r else null
    }

    /** Top edge of the on-screen keyboard, or null when it is hidden. */
    fun imeTop(): Int? {
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    if (r.height() > 120) return r.top
                }
            }
        } catch (e: Exception) {}
        return null
    }

    @Synchronized fun read(): String {
        nodes.clear()
        val root = targetRoot() ?: return "No other app is on screen right now (only Nana's own windows). Use launch_app to open one, then screen_read again."
        nodes.clear()
        val sb = StringBuilder("Foreground app: ").append(root.packageName).append("\n")
        walk(root, sb)
        if (nodes.isEmpty()) sb.append("(nothing readable on this screen)\n")
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= 150 || !n.isVisibleToUser) return
        var label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().replace("\n", " ")
        if (n.isPassword) label = "(password field)"
        val kind = when {
            n.isEditable -> "input"
            n.isCheckable -> "toggle"
            n.isClickable || n.isLongClickable -> "button"
            else -> "text"
        }
        if (label.isNotEmpty() || kind != "text") {
            val id = nodes.size
            nodes.add(n)
            sb.append('[').append(id).append("] ").append(kind).append(": ")
                .append(if (label.isEmpty()) "(unlabeled)" else label.take(90))
            if (n.isChecked) sb.append(" (on)")
            sb.append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb)
        }
    }

    @Synchronized fun tap(id: Int): String {
        val n = nodes.getOrNull(id) ?: return "No element [$id]. Call screen_read again."
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$id]."
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (gestureTap(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped [$id] by position." else "Tap failed."
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    @Synchronized fun tapText(q: String): String {
        read()
        val low = q.lowercase().trim()
        if (low.isEmpty()) return "No text given."
        val i = nodes.indexOfFirst { (it.text?.toString() ?: it.contentDescription?.toString() ?: "").lowercase().contains(low) }
        if (i < 0) return "Nothing on screen contains '" + q + "'. Try screen_scroll, then screen_read."
        return tap(i)
    }

    private fun inputNode(): AccessibilityNodeInfo? {
        val root = targetRoot() ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) return f
        return nodes.firstOrNull { it.isEditable }
    }

    fun foregroundPkg(): String? = targetRoot()?.packageName?.toString()

    /** Types into the given field (or the focused / first text field), replacing old text; submit presses search/enter. */
    @Synchronized fun type(id: Int, text: String, submit: Boolean = false): String {
        var n: AccessibilityNodeInfo? = nodes.getOrNull(id)
        if (n == null) { read(); n = inputNode() }
        var t = n ?: return "No text field found on screen. Tap the search bar first, then type."
        if (t.isPassword) return "Refused: password fields are off limits."
        if (!t.isFocused) {
            t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(500)
            read()
            val again = inputNode()
            if (again != null) t = again
        }
        if (t.isPassword) return "Refused: password fields are off limits."
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "Typing failed."
        if (!submit) return "Typed '" + text.take(40) + "' (old text replaced)."
        Thread.sleep(350)
        val sent = Build.VERSION.SDK_INT >= 30 && t.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (sent) "Typed '" + text.take(40) + "' and pressed search/enter." else "Typed '" + text.take(40) + "' but could not press enter: tap the search button."
    }

    fun scroll(down: Boolean): String {
        val root = targetRoot() ?: return "No other app is on screen."
        val s = findScrollable(root) ?: return "Nothing scrollable here."
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (s.performAction(act)) "Scrolled." else "Could not scroll."
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val f = findScrollable(c)
            if (f != null) return f
        }
        return null
    }

    fun back(): String { performGlobalAction(GLOBAL_ACTION_BACK); return "Went back." }
    fun home(): String { performGlobalAction(GLOBAL_ACTION_HOME); return "Went to the home screen." }
}
