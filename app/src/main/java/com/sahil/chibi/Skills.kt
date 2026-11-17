package com.sahil.chibi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Skills: short instruction sheets (like SKILL.md). Nana only sees the name + one-line description of the ON skills.
 * When a job matches, she opens the full text with the use_skill tool. Stored on the phone, editable in Settings > Nana > Skills.
 */
object Skills {
    private fun prefs(c: Context) = c.getSharedPreferences("nana_skills", Context.MODE_PRIVATE)

    private val SEED = listOf(
        Triple("scrape-a-site", "Pull data out of a web page or a few linked pages (prices, headlines, lists, tables).",
            "1. Use the scrape tool: scrape{url, fields, follow, max_pages}.\n" +
            "2. fields is a map of name to CSS selector, e.g. {\"title\":\"h1::text\",\"price\":\".price::text\"}. Use ::attr(href) for links. Prefix xpath: for XPath.\n" +
            "3. No fields = you get title, headings and text of each page.\n" +
            "4. follow is a regex on link URLs to crawl next (same site only). max_pages stays small (default 5, hard cap 15).\n" +
            "5. If a page is empty it probably needs JavaScript: say so, or try browser_open instead.\n" +
            "6. Only scrape what the user asked for. Report what you found, with the URL it came from."),
        Triple("web-research", "Answer a question that needs current facts from the internet, with sources.",
            "1. web_search first, then web_read the 2-3 best results (or scrape for lists).\n" +
            "2. Compare sources. If they disagree, say so.\n" +
            "3. Give a short answer, then the links you used. Never invent a source."),
        Triple("study-buddy", "Explain a school topic simply (CBSE Class 12 style) and quiz the user.",
            "1. Explain in short steps, simple words, one example. Hinglish is fine if the user writes Hinglish.\n" +
            "2. Give the formula or key point in one line.\n" +
            "3. End with one quick question to check understanding. Do not dump a whole chapter.")
    )

    private fun load(c: Context): JSONArray {
        val raw = prefs(c).getString("list", null)
        if (raw == null) {
            val a = JSONArray()
            SEED.forEach { a.put(JSONObject().put("name", it.first).put("desc", it.second).put("body", it.third).put("on", true)) }
            prefs(c).edit().putString("list", a.toString()).apply()
            return a
        }
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    private fun store(c: Context, a: JSONArray) { prefs(c).edit().putString("list", a.toString()).apply() }

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    fun listJson(c: Context): String = load(c).toString()

    /** Create or replace a skill. Returns "ok" or an error text. */
    fun save(c: Context, name: String, desc: String, body: String, on: Boolean = true): String {
        val n = slug(name)
        if (n.isEmpty()) return "Give the skill a name."
        if (body.isBlank()) return "The instructions are empty."
        val a = load(c)
        val out = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("name") != n) out.put(o) }
        out.put(JSONObject().put("name", n).put("desc", desc.trim().take(160)).put("body", body.trim().take(8000)).put("on", on))
        store(c, out)
        return "ok"
    }

    fun delete(c: Context, name: String) {
        val a = load(c); val out = JSONArray()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("name") != name) out.put(o) }
        store(c, out)
    }

    fun toggle(c: Context, name: String, on: Boolean) {
        val a = load(c)
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("name") == name) o.put("on", on) }
        store(c, a)
    }

    /** Index of ON skills, appended to the system prompt. Empty when there are none. */
    fun block(c: Context): String {
        val a = load(c); val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!o.optBoolean("on", true)) continue
            sb.append("- ").append(o.optString("name")).append(": ").append(o.optString("desc")).append('\n')
        }
        if (sb.isEmpty()) return ""
        return "\n\nSKILLS you can use. When the user's job matches one, call use_skill {\"name\":\"...\"} first and follow it:\n" + sb.toString().trimEnd()
    }

    /** Full text of one ON skill, for the use_skill tool. */
    fun text(c: Context, name: String): String {
        val a = load(c); val n = slug(name)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("name") == n && o.optBoolean("on", true)) return "SKILL " + n + ":\n" + o.optString("body")
        }
        val names = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("on", true) }.joinToString(", ") { it.optString("name") }
        return "No skill called \"" + name + "\". Available: " + (if (names.isEmpty()) "none" else names)
    }

    /** Reads a SKILL.md style file: optional --- front matter with name: and description:, then the body. */
    fun parse(raw: String, fallbackName: String): Triple<String, String, String> {
        var name = fallbackName; var desc = ""; var body = raw.trim()
        val m = Regex("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n?([\\s\\S]*)$").find(body)
        if (m != null) {
            for (line in m.groupValues[1].lines()) {
                val k = line.substringBefore(':', "").trim().lowercase(); val v = line.substringAfter(':', "").trim().trim('"', '\'')
                if (k == "name" && v.isNotEmpty()) name = v
                if (k == "description" && v.isNotEmpty()) desc = v
            }
            body = m.groupValues[2].trim()
        }
        if (desc.isEmpty()) desc = body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(140) ?: ""
        return Triple(name, desc, body)
    }

    /** Downloads a SKILL.md from a link (GitHub blob links are turned into raw links) and saves it. Run off the main thread. */
    fun importUrl(c: Context, link: String): String {
        var u = link.trim()
        if (!u.startsWith("http")) return "Paste a full https link."
        Regex("^https://github.com/([^/]+)/([^/]+)/blob/(.+)$").find(u)?.let { u = "https://raw.githubusercontent.com/" + it.groupValues[1] + "/" + it.groupValues[2] + "/" + it.groupValues[3] }
        return try {
            val cn = URL(u).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000; cn.readTimeout = 20000
            val raw = cn.inputStream.bufferedReader().use { it.readText().take(20000) }
            val fb = u.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
            val p = parse(raw, if (fb.equals("SKILL", true)) u.substringBeforeLast('/').substringAfterLast('/') else fb)
            val r = save(c, p.first, p.second, p.third)
            if (r == "ok") "Added skill: " + slug(p.first) else r
        } catch (e: Exception) { "Could not download: " + (e.message ?: e.toString()).take(120) }
    }
}
