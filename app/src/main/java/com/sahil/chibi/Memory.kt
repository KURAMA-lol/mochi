package com.sahil.chibi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lasting facts about the user. Nana learns them by herself from chats; the user can only review or delete them. */
object Memory {
    private fun file(c: Context) = File(c.filesDir, "memory.json")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("memcfg", Context.MODE_PRIVATE)

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

    private val BLOCK = Regex("""(?i)(password|passcode|\botp\b|\bcvv\b|aadhaar|aadhar|pan card|pin number|bank account|card number)""")
    private val LONGDIGITS = Regex("""\d{13,19}""")

    private fun words(s: String): Set<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    private fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val inter = x.intersect(y).size.toDouble()
        return inter / (x.size + y.size - inter) >= 0.6
    }

    @Synchronized fun add(c: Context, fact: String): String {
        val t = fact.trim().replace("\n", " ").take(200)
        if (t.length < 3) return "That is too short to remember."
        if (BLOCK.containsMatchIn(t) || LONGDIGITS.containsMatchIn(t.replace(Regex("[ -]"), ""))) {
            return "Refused: I do not store passwords, codes, card or ID numbers."
        }
        val l = all(c)
        if (l.any { it.equals(t, ignoreCase = true) || similar(it, t) }) return "Already remembered."
        l.add(t)
        while (l.size > 120) l.removeAt(0)
        save(c, l)
        return "Remembered."
    }

    /** Used by the background learner. True when a new fact was really saved. */
    fun addAuto(c: Context, fact: String): Boolean {
        val t = fact.trim()
        if (t.length < 8 || t.length > 160) return false
        return add(c, t) == "Remembered."
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

    // ---- learning settings + how long we have known each other ----
    fun learnOn(c: Context): Boolean = prefs(c).getBoolean("learn", true)
    fun setLearn(c: Context, v: Boolean) { prefs(c).edit().putBoolean("learn", v).apply() }

    fun bump(c: Context) {
        val p = prefs(c)
        val e = p.edit().putInt("msgs", p.getInt("msgs", 0) + 1).putInt("since", p.getInt("since", 0) + 1)
        if (p.getLong("first", 0L) == 0L) e.putLong("first", System.currentTimeMillis())
        e.apply()
    }
    fun since(c: Context): Int = prefs(c).getInt("since", 0)
    fun resetSince(c: Context) { prefs(c).edit().putInt("since", 0).apply() }

    private fun days(c: Context): Int {
        val f = prefs(c).getLong("first", 0L)
        return if (f == 0L) 0 else ((System.currentTimeMillis() - f) / 86400000L).toInt() + 1
    }

    fun bond(c: Context): String = JSONObject().put("days", days(c)).put("msgs", prefs(c).getInt("msgs", 0)).put("facts", all(c).size).toString()

    fun block(c: Context, user: String): String {
        val l = all(c)
        val d = days(c)
        val sb = StringBuilder()
        if (d > 1) sb.append("\n\nYou and ").append(user).append(" have been talking for ").append(d).append(" days. You are close and keep growing with them.")
        if (l.isEmpty()) return sb.toString()
        sb.append("\n\nThings you know about ").append(user).append(" (use them naturally, do not recite them):\n")
        for (x in l.takeLast(60)) sb.append("- ").append(x).append('\n')
        return sb.toString().take(3800)
    }
}

/** Quietly learns lasting facts about the user from recent chat, in the background, after replies. */
object Learner {
    @Volatile private var busy = false
    @Volatile private var lastTry = 0L
    private val HOT = Regex("(?i)(my name|i am |i'm |i live|i study|class \\d|exam|birthday|i like|i love|i hate|i prefer|my (friend|mom|dad|mother|father|sister|brother|teacher|crush|school|college)|mera |meri |mujhe |main )")

    private const val SYS = "You are a careful memory extractor for a personal AI companion. From the chat, pull out LASTING facts about the USER only: name, school/class, exams, routine, likes and dislikes, people in their life, goals, habits, and how they like to be talked to. Never include passwords, OTPs, card/ID/phone numbers, secrets, or temporary things (what they are doing right now, one-off questions). Each fact is a short plain sentence under 14 words, written about the user (for example: \"Has board exams in March\"). Skip anything already known. Output ONLY a JSON array of at most 4 strings, or [] if there is nothing new."

    fun after(c: Context, conf: AppConfig, hist: List<Msg>, onLearned: (String) -> Unit) {
        if (!Memory.learnOn(c) || busy) return
        val last = hist.lastOrNull { it.role == "user" }?.content ?: return
        val now = System.currentTimeMillis()
        if (now - lastTry < 60_000L) return
        if (Memory.since(c) < 4 && !HOT.containsMatchIn(last)) return
        busy = true; lastTry = now
        Thread {
            try {
                val known = Memory.all(c)
                val tr = StringBuilder()
                for (m in hist.takeLast(8)) tr.append(if (m.role == "user") "USER: " else "NANA: ").append(m.content.take(300)).append('\n')
                val ask = "Already known:\n" + (known.takeLast(40).joinToString("\n") { "- $it" }.ifEmpty { "(nothing yet)" }) + "\n\nChat:\n" + tr + "\nNew lasting facts about the user, as a JSON array of strings:"
                val res = Router.chat(conf, SYS, listOf(Msg("user", ask)), false)
                val txt = res.text
                val a = txt.indexOf('['); val b = txt.lastIndexOf(']')
                if (a >= 0 && b > a) {
                    val arr = JSONArray(txt.substring(a, b + 1))
                    var first = ""
                    for (i in 0 until minOf(arr.length(), 4)) {
                        val f = arr.optString(i, "").trim()
                        if (Memory.addAuto(c, f) && first.isEmpty()) first = f
                    }
                    Memory.resetSince(c)
                    if (first.isNotEmpty()) onLearned(first)
                }
            } catch (e: Exception) {} finally { busy = false }
        }.start()
    }
}
