package com.sahil.chibi

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Small list of lasting facts about the user. Shown to the model on every chat; the user can edit it in Settings. */
object Memory {
    private fun file(c: Context) = File(c.filesDir, "memory.json")

    @Synchronized fun all(c: Context): MutableList<String> {
        return try {
            val a = JSONArray(file(c).readText())
            val l = ArrayList<String>()
            for (i in 0 until a.length()) l.add(a.getString(i))
            l
        } catch (e: Exception) { ArrayList() }
    }

    private fun save(c: Context, l: List<String>) {
        file(c).writeText(JSONArray(l).toString())
    }

    private val BLOCK = Regex("""(?i)(password|passcode|\botp\b|\bcvv\b|aadhaar|aadhar|pan card)""")
    private val LONGDIGITS = Regex("""\d{13,19}""")

    @Synchronized fun add(c: Context, fact: String): String {
        val t = fact.trim().replace("\n", " ").take(200)
        if (t.length < 3) return "That is too short to remember."
        if (BLOCK.containsMatchIn(t) || LONGDIGITS.containsMatchIn(t.replace(Regex("[ -]"), ""))) {
            return "Refused: I do not store passwords, codes, card or ID numbers."
        }
        val l = all(c)
        if (l.any { it.equals(t, ignoreCase = true) }) return "Already remembered."
        l.add(t)
        while (l.size > 80) l.removeAt(0)
        save(c, l)
        return "Remembered."
    }

    @Synchronized fun forget(c: Context, match: String): String {
        val q = match.trim().lowercase()
        if (q.length < 2) return "Say what to forget."
        val l = all(c)
        val keep = l.filter { !it.lowercase().contains(q) }
        save(c, keep)
        val n = l.size - keep.size
        return if (n == 0) "Nothing matched." else "Forgot $n item(s)."
    }

    @Synchronized fun removeAt(c: Context, i: Int) {
        val l = all(c)
        if (i in l.indices) { l.removeAt(i); save(c, l) }
    }

    @Synchronized fun clear(c: Context) { save(c, emptyList()) }

    fun json(c: Context): String = JSONArray(all(c)).toString()

    fun block(c: Context, user: String): String {
        val l = all(c)
        if (l.isEmpty()) return ""
        val sb = StringBuilder("\n\nThings you know about ").append(user).append(" (use them naturally, do not recite them):\n")
        for (x in l.takeLast(60)) sb.append("- ").append(x).append('\n')
        return sb.toString().take(3500)
    }
}
