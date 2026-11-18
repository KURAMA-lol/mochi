package com.sahil.chibi

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/** Web search backends: DuckDuckGo (no key), SearXNG (your own or a public instance, no key), Brave Search (API key). */
object Search {
    const val DEFAULT_SEARX = "https://search.lumy.live"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("search", Context.MODE_PRIVATE)
    fun engine(c: Context): String = prefs(c).getString("engine", "searxng") ?: "searxng"
    fun searxUrl(c: Context): String = (prefs(c).getString("searx", DEFAULT_SEARX) ?: DEFAULT_SEARX).trim().trimEnd('/').ifEmpty { DEFAULT_SEARX }
    fun braveKey(c: Context): String = (prefs(c).getString("brave", "") ?: "").trim()

    fun save(c: Context, engine: String, searx: String, brave: String) {
        prefs(c).edit().putString("engine", if (engine in setOf("ddg", "searxng", "brave")) engine else "searxng")
            .putString("searx", searx.trim()).putString("brave", brave.trim()).apply()
    }

    fun json(c: Context): String = JSONObject().put("engine", engine(c)).put("searx", searxUrl(c)).put("brave", braveKey(c)).toString()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    /** category: "" (general), images, videos, news, "stock images". Categories need SearXNG or Brave. */
    fun run(c: Context, q: String, category: String = ""): String {
        if (q.isBlank()) return "Empty query."
        val cat = category.trim().lowercase().replace(Regex("[^a-z ]"), "").take(20)
        val eng = engine(c)
        var err = ""
        try {
            when (eng) {
                "searxng" -> return searx(c, q, cat)
                "brave" -> {
                    if (braveKey(c).isEmpty()) err = "Brave has no API key yet (Settings > Setup > Web search)."
                    else return brave(c, q, cat)
                }
            }
        } catch (e: Exception) { err = (e.message ?: e.toString()).take(120) }
        if (eng == "ddg") return Agent.webSearch(q)
        val fb = try { Agent.webSearch(q) } catch (e: Exception) { "DuckDuckGo failed too: " + (e.message ?: "").take(80) }
        return "(" + eng + " failed: " + err + ". Used DuckDuckGo instead.)\n" + fb
    }

    private fun searx(c: Context, q: String, cat: String): String {
        var u = searxUrl(c) + "/search?q=" + enc(q) + "&format=json"
        if (cat.isNotEmpty() && cat != "general") u += "&categories=" + enc(cat)
        val raw = Http.request("GET", u, mapOf("Accept" to "application/json", "User-Agent" to UA), null, 20000)
        val o = try { JSONObject(raw) } catch (e: Exception) { throw RuntimeException("that SearXNG did not return JSON (the instance may block format=json)") }
        val a = o.optJSONArray("results") ?: throw RuntimeException("no results field")
        if (a.length() == 0) return "No results."
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until a.length()) {
            if (n >= 8) break
            val r = a.optJSONObject(i) ?: continue
            n++
            sb.append(n).append(". ").append(clean(r.optString("title"))).append("\n   ").append(r.optString("url"))
            val img = r.optString("img_src")
            if (img.isNotEmpty() && cat.contains("image")) sb.append("\n   image: ").append(img)
            val d = clean(r.optString("content"))
            if (d.isNotEmpty()) sb.append("\n   ").append(d.take(200))
            val dt = r.optString("publishedDate")
            if (dt.isNotEmpty() && dt != "null") sb.append("\n   ").append(dt.take(10))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun brave(c: Context, q: String, cat: String): String {
        val path = when { cat.contains("news") -> "news"; cat.contains("image") -> "images"; cat.contains("video") -> "videos"; else -> "web" }
        val raw = Http.request("GET", "https://api.search.brave.com/res/v1/$path/search?count=8&q=" + enc(q),
            mapOf("Accept" to "application/json", "X-Subscription-Token" to braveKey(c), "User-Agent" to UA), null, 20000)
        val o = JSONObject(raw)
        val arr = if (path == "web") o.optJSONObject("web")?.optJSONArray("results") else o.optJSONArray("results")
        if (arr == null || arr.length() == 0) return "No results."
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until arr.length()) {
            if (n >= 8) break
            val r = arr.optJSONObject(i) ?: continue
            n++
            sb.append(n).append(". ").append(clean(r.optString("title"))).append("\n   ").append(r.optString("url"))
            val img = r.optJSONObject("properties")?.optString("url") ?: ""
            if (img.isNotEmpty()) sb.append("\n   image: ").append(img)
            val d = clean(r.optString("description"))
            if (d.isNotEmpty()) sb.append("\n   ").append(d.take(200))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** For the Test button: returns a one-line verdict. */
    fun test(c: Context): String = try {
        val r = run(c, "test", "")
        if (r.startsWith("(")) r.substringBefore('\n').take(160) else "Works: " + engine(c) + " returned results."
    } catch (e: Exception) { "Failed: " + (e.message ?: e.toString()).take(140) }
}
