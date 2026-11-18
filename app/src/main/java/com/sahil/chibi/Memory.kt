package com.sahil.chibi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Lasting facts about the user, in SQLite with full-text search (FTS4).
 * Each memory has importance (1-5), a pin, a source (who saved it) and a note (what triggered it).
 * Recall ranks by relevance to the current message, importance, recency and pin.
 */
object Memory {
    class Item(val id: Long, val text: String, val pinned: Boolean, val importance: Int, val source: String, val note: String, val created: Long, val updated: Long, val uses: Int)

    private class Db(c: Context) : SQLiteOpenHelper(c.applicationContext, "memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE mem(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, importance INTEGER NOT NULL DEFAULT 3, source TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, updated INTEGER NOT NULL, uses INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE VIRTUAL TABLE mem_fts USING fts4(text)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var helper: Db? = null
    @Volatile private var migrated = false
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("memcfg", Context.MODE_PRIVATE)

    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Db(c).also { helper = it } }
        val d = h.writableDatabase
        if (!migrated) { migrated = true; migrate(c, d) }
        return d
    }

    /** Old versions kept a plain list in memory.json: bring those facts over once. */
    private fun migrate(c: Context, d: SQLiteDatabase) {
        val f = File(c.filesDir, "memory.json")
        if (!f.exists()) return
        try {
            val a = JSONArray(f.readText())
            val now = System.currentTimeMillis()
            for (i in 0 until a.length()) insert(d, a.getString(i), false, 3, "imported", "", now - (a.length() - i) * 1000L)
            f.renameTo(File(c.filesDir, "memory.json.bak"))
        } catch (e: Exception) {}
    }

    private fun insert(d: SQLiteDatabase, text: String, pinned: Boolean, imp: Int, source: String, note: String, created: Long): Long {
        val v = ContentValues()
        v.put("text", text); v.put("pinned", if (pinned) 1 else 0); v.put("importance", imp)
        v.put("source", source); v.put("note", note); v.put("created", created); v.put("updated", created)
        val id = d.insert("mem", null, v)
        if (id > 0) { val f = ContentValues(); f.put("docid", id); f.put("text", text); d.insert("mem_fts", null, f) }
        return id
    }

    private fun readAll(d: SQLiteDatabase): List<Item> {
        val out = ArrayList<Item>()
        d.query("mem", null, null, null, null, null, "created ASC").use { cu ->
            while (cu.moveToNext()) out.add(Item(cu.getLong(0), cu.getString(1), cu.getInt(2) == 1, cu.getInt(3), cu.getString(4), cu.getString(5), cu.getLong(6), cu.getLong(7), cu.getInt(8)))
        }
        return out
    }

    // ---- sensitive information never gets stored ----
    private val BLOCK = Regex("""(?i)(password|passcode|passwd|\botp\b|one.?time (pass|code)|\bcvv\b|\bpin\b ?(is|number|code)|aadhaar|aadhar|pan card|bank account|card number|\bifsc\b|upi pin|secret key|api[ _-]?key|private key|seed phrase|recovery phrase)""")
    private val KEYS = Regex("""(sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{20,}|xox[bpa]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})""")
    private val LONGDIGITS = Regex("""\d{13,19}""")

    fun sensitive(t: String): Boolean = BLOCK.containsMatchIn(t) || KEYS.containsMatchIn(t) || LONGDIGITS.containsMatchIn(t.replace(Regex("[ -]"), ""))

    private fun tokens(s: String): List<String> = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }.distinct()
    private fun words(s: String): Set<String> = tokens(s).toSet()

    private fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val inter = x.intersect(y).size.toDouble()
        return inter / (x.size + y.size - inter) >= 0.6
    }

    // ---- ranking: relevance + importance + recency + pin ----
    private fun rel(it: Item, toks: List<String>, ftsHit: Boolean): Double {
        if (toks.isEmpty()) return 0.0
        val tw = words(it.text)
        val hits = toks.count { t -> tw.any { w -> w.startsWith(t) || t.startsWith(w) } }
        return hits.toDouble() / toks.size + (if (ftsHit) 0.3 else 0.0)
    }

    private fun score(it: Item, rel: Double): Double {
        val ageDays = (System.currentTimeMillis() - it.updated) / 86400000.0
        return (if (it.pinned) 100.0 else 0.0) + it.importance * 4 + 6 * exp(-ageDays / 30.0) + rel * 20 + min(it.uses, 5) * 0.5
    }

    private fun ftsHits(d: SQLiteDatabase, toks: List<String>): Set<Long> {
        val out = HashSet<Long>()
        val ascii = toks.map { it.filter { ch -> ch.isLetterOrDigit() && ch.code < 128 } }.filter { it.length > 2 }.take(8)
        if (ascii.isEmpty()) return out
        try {
            d.rawQuery("SELECT docid FROM mem_fts WHERE mem_fts MATCH ?", arrayOf(ascii.joinToString(" OR ") { "$it*" })).use { cu -> while (cu.moveToNext()) out.add(cu.getLong(0)) }
        } catch (e: Exception) {}
        return out
    }

    @Synchronized fun search(c: Context, q: String, limit: Int = 8): List<Item> {
        val d = db(c)
        val toks = tokens(q)
        val hits = ftsHits(d, toks)
        return readAll(d).map { Triple(it, rel(it, toks, hits.contains(it.id)), 0) }
            .filter { toks.isEmpty() || it.second > 0 }
            .sortedByDescending { score(it.first, it.second) }
            .take(limit).map { it.first }
    }

    // ---- CRUD ----
    @Synchronized fun all(c: Context): MutableList<String> = readAll(db(c)).map { it.text }.toMutableList()

    @Synchronized fun add(c: Context, fact: String, source: String = "chat", importance: Int = 3, pinned: Boolean = false, note: String = ""): String {
        val t = fact.trim().replace("\n", " ").take(240)
        if (t.length < 3) return "That is too short to remember."
        if (sensitive(t)) return "Refused: I do not store passwords, codes, card or ID numbers."
        val d = db(c)
        val now = System.currentTimeMillis()
        val list = readAll(d)
        val ex = list.firstOrNull { it.text.equals(t, ignoreCase = true) || similar(it.text, t) }
        if (ex != null) {
            val v = ContentValues(); v.put("updated", now); v.put("importance", max(ex.importance, importance.coerceIn(1, 5))); if (pinned) v.put("pinned", 1)
            d.update("mem", v, "id=?", arrayOf(ex.id.toString()))
            return "Already remembered."
        }
        insert(d, t, pinned, importance.coerceIn(1, 5), source, note.take(120), now)
        if (list.size + 1 > 400) {
            val drop = list.filter { !it.pinned }.sortedWith(compareBy({ it.importance }, { it.updated })).take(list.size + 1 - 400)
            for (x in drop) { d.delete("mem", "id=?", arrayOf(x.id.toString())); d.delete("mem_fts", "docid=?", arrayOf(x.id.toString())) }
        }
        return "Remembered."
    }

    /** Used by the background learner. True when a new fact was really saved. */
    fun addAuto(c: Context, fact: String, importance: Int = 3, note: String = ""): Boolean {
        val t = fact.trim()
        if (t.length < 8 || t.length > 160) return false
        return add(c, t, "chat", importance, false, note) == "Remembered."
    }

    @Synchronized fun edit(c: Context, id: Long, text: String): String {
        val t = text.trim().replace("\n", " ").take(240)
        if (t.length < 3) return "That is too short."
        if (sensitive(t)) return "Refused: I do not store passwords, codes, card or ID numbers."
        val d = db(c)
        val v = ContentValues(); v.put("text", t); v.put("updated", System.currentTimeMillis())
        d.update("mem", v, "id=?", arrayOf(id.toString()))
        val f = ContentValues(); f.put("text", t)
        d.update("mem_fts", f, "docid=?", arrayOf(id.toString()))
        return "ok"
    }

    @Synchronized fun pin(c: Context, id: Long, on: Boolean) {
        val v = ContentValues(); v.put("pinned", if (on) 1 else 0)
        db(c).update("mem", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun importance(c: Context, id: Long, imp: Int) {
        val v = ContentValues(); v.put("importance", imp.coerceIn(1, 5))
        db(c).update("mem", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun delete(c: Context, id: Long) {
        val d = db(c)
        d.delete("mem", "id=?", arrayOf(id.toString())); d.delete("mem_fts", "docid=?", arrayOf(id.toString()))
    }

    @Synchronized fun forget(c: Context, match: String): String {
        val q = match.trim().lowercase()
        if (q.length < 2) return "Say what to forget."
        val d = db(c)
        val hit = readAll(d).filter { it.text.lowercase().contains(q) }
        for (x in hit) { d.delete("mem", "id=?", arrayOf(x.id.toString())); d.delete("mem_fts", "docid=?", arrayOf(x.id.toString())) }
        return if (hit.isEmpty()) "Nothing matched." else "Forgot ${hit.size} item(s)."
    }

    /** Old index-based delete (position in all()). */
    @Synchronized fun removeAt(c: Context, i: Int) {
        val l = readAll(db(c))
        if (i in l.indices) delete(c, l[i].id)
    }

    @Synchronized fun clear(c: Context) {
        val d = db(c)
        d.delete("mem", null, null); d.delete("mem_fts", null, null)
    }

    fun json(c: Context): String = JSONArray(all(c)).toString()

    /** Full records for the Memory screen. q blank = everything (pinned first), otherwise ranked search. */
    @Synchronized fun itemsJson(c: Context, q: String): String {
        val list = if (q.isBlank()) readAll(db(c)).sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.updated }) else search(c, q, 100)
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("id", x.id).put("text", x.text).put("pinned", x.pinned).put("imp", x.importance).put("source", x.source).put("note", x.note).put("created", x.created).put("uses", x.uses))
        return a.toString()
    }

    fun searchText(c: Context, q: String): String {
        val r = search(c, q, 8)
        if (r.isEmpty()) return "Nothing in memory matches that."
        return r.joinToString("\n") { "- " + it.text + (if (it.pinned) " [pinned]" else "") }
    }

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

    @Synchronized fun bond(c: Context): String = JSONObject().put("days", days(c)).put("msgs", prefs(c).getInt("msgs", 0)).put("facts", readAll(db(c)).size).toString()

    /** What goes into the system prompt: pinned + the most relevant to this message + the most important, within a size budget. */
    @Synchronized fun block(c: Context, user: String, query: String = ""): String {
        val d = db(c)
        val l = readAll(d)
        val dd = days(c)
        val sb = StringBuilder()
        if (dd > 1) sb.append("\n\nYou and ").append(user).append(" have been talking for ").append(dd).append(" days. You are close and keep growing with them.")
        if (l.isEmpty()) return sb.toString()
        val toks = tokens(query)
        val hits = ftsHits(d, toks)
        val ranked = l.map { Triple(it, rel(it, toks, hits.contains(it.id)), 0) }.sortedByDescending { score(it.first, it.second) }
        val picked = ArrayList<Item>()
        var chars = 0
        for (r in ranked) {
            if (picked.size >= 45 || chars + r.first.text.length > 3300) break
            picked.add(r.first); chars += r.first.text.length + 3
            if (r.second > 0) { try { d.execSQL("UPDATE mem SET uses=uses+1 WHERE id=?", arrayOf<Any>(r.first.id)) } catch (e: Exception) {} }
        }
        sb.append("\n\nThings you know about ").append(user).append(" (most relevant first; use them naturally, do not recite them):\n")
        for (x in picked) sb.append("- ").append(x.text).append('\n')
        return sb.toString()
    }
}

/** Quietly learns lasting facts about the user from recent chat, in the background, after replies. */
object Learner {
    @Volatile private var busy = false
    @Volatile private var lastTry = 0L
    private val HOT = Regex("(?i)(my name|i am |i'm |i live|i study|class \\d|exam|birthday|i like|i love|i hate|i prefer|my (friend|mom|dad|mother|father|sister|brother|teacher|crush|school|college)|mera |meri |mujhe |main |remember|yaad)")

    private const val SYS = "You are a careful memory extractor for a personal AI companion. From the chat, pull out LASTING facts about the USER only: name, school/class, exams, routine, likes and dislikes, people in their life, goals, habits, and how they like to be talked to. Never include passwords, OTPs, card/ID/phone numbers, API keys, secrets, or temporary things (what they are doing right now, one-off questions). Each fact is a short plain sentence under 14 words, written about the user (for example: \"Has board exams in March\"). Rate importance 1-5 (5 = core identity or a strong preference, 1 = minor). Skip anything already known. Output ONLY a JSON array of at most 4 objects like {\"fact\":\"...\",\"importance\":3}, or [] if there is nothing new."

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
                val ask = "Already known:\n" + (known.takeLast(40).joinToString("\n") { "- $it" }.ifEmpty { "(nothing yet)" }) + "\n\nChat:\n" + tr + "\nNew lasting facts about the user, as a JSON array:"
                val res = Router.chat(conf, SYS, listOf(Msg("user", ask)), false)
                val txt = res.text
                val a = txt.indexOf('['); val b = txt.lastIndexOf(']')
                if (a >= 0 && b > a) {
                    val arr = JSONArray(txt.substring(a, b + 1))
                    var first = ""
                    val note = last.replace("\n", " ").take(100)
                    for (i in 0 until minOf(arr.length(), 4)) {
                        val o = arr.opt(i)
                        val f = if (o is JSONObject) o.optString("fact", "") else arr.optString(i, "")
                        val imp = if (o is JSONObject) o.optInt("importance", 3) else 3
                        if (Memory.addAuto(c, f.trim(), imp, note) && first.isEmpty()) first = f.trim()
                    }
                    Memory.resetSince(c)
                    if (first.isNotEmpty()) onLearned(first)
                }
            } catch (e: Exception) {} finally { busy = false }
        }.start()
    }
}

/**
 * Keeps the prompt small without forgetting: repairs the history (no empty or repeated-role messages, starts with the user),
 * keeps the recent messages word for word, and folds older ones into a short rolling summary kept on the phone.
 */
object Compactor {
    @Volatile private var busy = false
    private const val BUDGET_CHARS = 18000

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("compact", Context.MODE_PRIVATE)
    private fun key(m: Msg) = (m.role + ":" + m.content).hashCode().toString()
    private fun chars(l: List<Msg>) = l.sumOf { it.content.length }

    /** History repair: drop empty messages, merge neighbours with the same role, make it start with the user. */
    fun repair(list: List<Msg>): List<Msg> {
        val out = ArrayList<Msg>()
        for (m in list) {
            val t = m.content.trim()
            if (t.isEmpty()) continue
            val role = if (m.role == "assistant") "assistant" else "user"
            if (out.isNotEmpty() && out.last().role == role) out[out.size - 1] = Msg(role, out.last().content + "\n\n" + t)
            else out.add(Msg(role, t))
        }
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }

    fun build(c: Context, conf: AppConfig, full: List<Msg>): List<Msg> {
        val rep = repair(full)
        var recent = rep.takeLast(max(2, conf.persona.history))
        while (recent.size > 4 && chars(recent) > BUDGET_CHARS) recent = recent.drop(1)
        while (recent.isNotEmpty() && recent.first().role != "user") recent = recent.drop(1)
        val older = rep.dropLast(recent.size)
        val p = prefs(c)
        if (older.isEmpty()) {
            if (p.getString("sum", "")!!.isNotEmpty()) p.edit().remove("sum").remove("last").apply()
            return recent
        }
        val last = p.getString("last", "") ?: ""
        val idx = older.indexOfLast { key(it) == last }
        val fresh = if (idx >= 0) older.drop(idx + 1) else older
        if (fresh.size >= 4 && !busy) summarize(c, conf, p.getString("sum", "") ?: "", fresh, key(older.last()))
        val sum = p.getString("sum", "") ?: ""
        if (sum.isEmpty() || recent.isEmpty()) return recent
        val first = recent.first()
        return listOf(Msg("user", "[Earlier in this chat, summary: $sum]\n\n" + first.content)) + recent.drop(1)
    }

    private fun summarize(c: Context, conf: AppConfig, prev: String, fresh: List<Msg>, lastKey: String) {
        busy = true
        Thread {
            try {
                val tr = StringBuilder()
                for (m in fresh.takeLast(30)) tr.append(if (m.role == "user") "USER: " else "NANA: ").append(m.content.take(400)).append('\n')
                val ask = (if (prev.isNotEmpty()) "Summary so far:\n$prev\n\n" else "") + "New messages:\n" + tr + "\nWrite the updated summary."
                val sys = "You compress a chat between a user and their AI companion Nana into a short running summary (max 120 words): topics, decisions, open tasks, promises, the user's mood. Plain sentences, no lists, no secrets, passwords or numbers like OTPs. Output only the summary."
                val s = Router.chat(conf, sys, listOf(Msg("user", ask)), false).text.trim()
                if (s.isNotEmpty() && !Memory.sensitive(s)) prefs(c).edit().putString("sum", s.take(900)).putString("last", lastKey).apply()
            } catch (e: Exception) {} finally { busy = false }
        }.start()
    }
}
