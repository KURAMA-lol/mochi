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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lets Nana read and operate the screen (only when the user turns this service on in Accessibility settings). */
class NanaAccess : AccessibilityService() {
    companion object {
        @Volatile var inst: NanaAccess? = null
    }

    private val nodes = ArrayList<AccessibilityNodeInfo>()
    private var primaryScroll = ""

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** The app on screen, never Nana's own windows (chat panel, bubble, floating character, floating browser). */
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

    fun foregroundPkg(): String? = targetRoot()?.packageName?.toString()

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

    private fun labelText(n: AccessibilityNodeInfo): String {
        var l = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().replace("\n", " ")
        if (l.isEmpty() && Build.VERSION.SDK_INT >= 26) l = n.hintText?.toString()?.trim() ?: ""
        if (n.isPassword) l = "(password field)"
        return l
    }

    private fun idName(n: AccessibilityNodeInfo): String = n.viewIdResourceName?.substringAfterLast('/') ?: ""

    /** Label plus the view id name (so an unlabeled "send" icon can still be recognised). */
    fun labelOf(id: Int): String {
        val n = nodes.getOrNull(id) ?: return ""
        return (labelText(n) + " " + idName(n)).trim()
    }

    private fun canScroll(n: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val id = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return n.actionList.any { it.id == id }
    }

    @Synchronized fun read(): String {
        nodes.clear()
        primaryScroll = ""
        val root = targetRoot() ?: return "No other app is on screen right now (only Nana's own windows). Use launch_app to open one."
        val sb = StringBuilder()
        walk(root, sb)
        if (nodes.isEmpty()) sb.append("(nothing readable on this screen)\n")
        val dm = resources.displayMetrics
        val head = StringBuilder("Foreground app: ").append(root.packageName)
            .append(" | Screen ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        head.append(if (primaryScroll.isEmpty()) "Scrollable: no scrollable list on this screen.\n" else "Scrollable: " + primaryScroll + "\n")
        return (head.toString() + sb.toString()).take(6500)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= 150 || !n.isVisibleToUser) return
        val label = labelText(n)
        val scrollable = n.isScrollable
        val kind = when {
            n.isEditable -> "input"
            n.isCheckable -> "toggle"
            scrollable -> "scroll"
            n.isClickable || n.isLongClickable -> "button"
            else -> "text"
        }
        if (label.isNotEmpty() || kind != "text") {
            val id = nodes.size
            nodes.add(n)
            sb.append('[').append(id).append("] ").append(kind)
            if (kind == "scroll") {
                val desc = "can scroll down: " + (if (canScroll(n, true)) "yes" else "no") + ", up: " + (if (canScroll(n, false)) "yes" else "no")
                sb.append(" (").append(desc).append(")")
                if (primaryScroll.isEmpty()) primaryScroll = desc
            }
            sb.append(": ")
            if (label.isEmpty()) {
                sb.append("(unlabeled")
                val idn = idName(n)
                if (idn.isNotEmpty()) sb.append(" id:").append(idn)
                sb.append(")")
            } else sb.append(label.take(90))
            if (n.isChecked) sb.append(" (on)")
            if (kind != "text") {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY())
            }
            sb.append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb)
        }
    }

    private fun gesture(p: Path, dur: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
        val l = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; l.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { l.countDown() }
        }
        if (!dispatchGesture(g, cb, null)) return false
        l.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun pathAt(x: Int, y: Int): Path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    fun tapXY(x: Int, y: Int): String {
        val dm = resources.displayMetrics
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
            return "Coordinates are outside the screen (" + dm.widthPixels + "x" + dm.heightPixels + ")."
        }
        return if (gesture(pathAt(x, y), 60)) "Tapped at ($x,$y)." else "Tap at ($x,$y) failed."
    }

    fun swipe(dir: String, edge: Boolean, x1: Int, y1: Int, x2: Int, y2: Int): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        var a = x1; var b = y1; var c = x2; var d = y2
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            val cx = w / 2
            val cy = h / 2
            when (dir.lowercase()) {
                "up" -> { a = cx; b = if (edge) h - 5 else (h * 0.75f).toInt(); c = cx; d = (h * 0.25f).toInt() }
                "down" -> { a = cx; b = if (edge) 5 else (h * 0.25f).toInt(); c = cx; d = (h * 0.75f).toInt() }
                "left" -> { a = if (edge) w - 5 else (w * 0.85f).toInt(); b = cy; c = (w * 0.15f).toInt(); d = cy }
                "right" -> { a = if (edge) 5 else (w * 0.15f).toInt(); b = cy; c = (w * 0.85f).toInt(); d = cy }
                else -> return "Give a direction (up/down/left/right, finger movement) or x1,y1,x2,y2."
            }
        }
        val p = Path().apply { moveTo(a.toFloat(), b.toFloat()); lineTo(c.toFloat(), d.toFloat()) }
        return if (gesture(p, 350)) "Swiped." else "Swipe failed."
    }

    @Synchronized fun tap(id: Int): String {
        val n = nodes.getOrNull(id) ?: return "No element [$id]. Use the numbers from the newest screen list."
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$id] (" + labelText(n).take(30) + ")."
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (gesture(pathAt(r.centerX(), r.centerY()), 60)) "Tapped [$id] by position." else "Tap failed."
    }

    @Synchronized fun tapText(q: String): String {
        read()
        val low = q.lowercase().trim()
        if (low.isEmpty()) return "No text given."
        var i = nodes.indexOfFirst { labelText(it).lowercase() == low }
        if (i < 0) i = nodes.indexOfFirst { (labelText(it) + " " + idName(it)).lowercase().contains(low) }
        if (i < 0) return "Nothing on screen contains '" + q + "'. Try screen_scroll_to, then screen_read."
        return tap(i)
    }

    @Synchronized fun longPress(id: Int, text: String): String {
        var i = id
        if (i < 0 && text.isNotBlank()) {
            read()
            val low = text.lowercase()
            i = nodes.indexOfFirst { labelText(it).lowercase().contains(low) }
        }
        val n = nodes.getOrNull(i) ?: return "Nothing to long-press."
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isLongClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Long-pressed."
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (gesture(pathAt(r.centerX(), r.centerY()), 700)) "Long-pressed by position." else "Long press failed."
    }

    private fun inputNode(): AccessibilityNodeInfo? {
        val root = targetRoot() ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) return f
        return nodes.firstOrNull { it.isEditable }
    }

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

    private fun signature(): Int {
        val root = targetRoot() ?: return 0
        val sb = StringBuilder()
        var cnt = 0
        fun rec(n: AccessibilityNodeInfo) {
            if (cnt > 120 || !n.isVisibleToUser) return
            val t = labelText(n)
            if (t.isNotEmpty()) { sb.append(t).append('|'); cnt++ }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                rec(c)
            }
        }
        rec(root)
        return sb.toString().hashCode()
    }

    private fun findScrollable(n: AccessibilityNodeInfo, forward: Boolean): AccessibilityNodeInfo? {
        if (n.isScrollable && n.isVisibleToUser && canScroll(n, forward)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val f = findScrollable(c, forward)
            if (f != null) return f
        }
        return null
    }

    /** Scrolls the screen and says whether anything actually moved (so the end of a list is recognised). */
    fun scroll(down: Boolean): String {
        val root = targetRoot() ?: return "No other app is on screen."
        val before = signature()
        val s = findScrollable(root, down)
        var ok = false
        if (s != null) ok = s.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (!ok) swipe(if (down) "up" else "down", false, -1, -1, -1, -1)
        Thread.sleep(600)
        val after = signature()
        return if (after == before) {
            "Nothing moved: already at the " + (if (down) "bottom/end" else "top/start") + " (or this screen does not scroll)."
        } else "Scrolled " + (if (down) "down" else "up") + "."
    }

    /** Scrolls down until the text shows up on screen (stops at the end of the list). */
    fun scrollTo(q: String): String {
        val low = q.lowercase().trim()
        if (low.isEmpty()) return "No text given."
        for (i in 0..10) {
            if (read().lowercase().contains(low)) return if (i == 0) "'" + q + "' is already visible." else "Found '" + q + "' after " + i + " scroll(s)."
            if (scroll(true).startsWith("Nothing moved")) break
        }
        return "Did not find '" + q + "' going down. Try screen_scroll up, or another word."
    }

    fun screenWait(q: String, seconds: Int): String {
        val low = q.lowercase().trim()
        val end = System.currentTimeMillis() + seconds.coerceIn(1, 15) * 1000L
        while (System.currentTimeMillis() < end) {
            if (low.isEmpty() || read().lowercase().contains(low)) return if (low.isEmpty()) "Waited." else "'" + q + "' is on screen."
            Thread.sleep(600)
        }
        return "'" + q + "' did not appear in time."
    }

    fun press(key: String): String = when (key.lowercase().trim()) {
        "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "Pressed back." }
        "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "Went to the home screen." }
        "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); "Opened recent apps." }
        "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); "Opened the notification shade." }
        "quick_settings" -> { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); "Opened quick settings." }
        else -> "Unknown key. Use back, home, recents, notifications or quick_settings."
    }

    fun back(): String = press("back")
    fun home(): String = press("home")
}
