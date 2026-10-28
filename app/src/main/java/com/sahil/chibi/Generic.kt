package com.sahil.chibi

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * "Custom endpoint" provider: any plain URL that returns an answer.
 *
 * URL / body / header templates can use:
 *   {prompt}  the text sent to the AI (personality + recent chat, or just your message - see promptMode)
 *   {message} only your latest message      {system} the personality prompt
 *   {model}   the Model field               {key}    the API key field
 * GET: placeholders are URL-encoded. If the URL has no {prompt} the prompt is appended at the end
 * (so "https://site/ai/chat?prompt=" just works).
 * POST: body template (default {"prompt":"{prompt}"}) with JSON-escaped placeholders.
 * The answer is read from responsePath (e.g. result.text) or auto-detected.
 */
object Generic {
    fun call(p: Provider, system: String, msgs: List<Msg>): String {
        val lastUser = msgs.lastOrNull { it.role == "user" }?.content ?: ""
        val prompt = if (p.promptMode == "last") lastUser else buildPrompt(system, msgs)
        val post = p.method.equals("POST", ignoreCase = true)

        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun esc(s: String): String { val q = JSONObject.quote(s); return q.substring(1, q.length - 1) }
        fun fill(t: String, f: (String) -> String) = t
            .replace("{prompt}", f(prompt)).replace("{message}", f(lastUser))
            .replace("{system}", f(system)).replace("{model}", f(p.model)).replace("{key}", f(p.apiKey))

        var url = fill(p.baseUrl.trim(), ::enc)
        if (!post && !p.baseUrl.contains("{prompt}") && !p.baseUrl.contains("{message}")) url += enc(prompt)

        val headers = LinkedHashMap<String, String>()
        for (line in p.headers.lines()) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim()] = line.substring(i + 1).replace("{key}", p.apiKey).trim()
        }

        val body: String? = if (post) {
            fill(p.bodyTemplate.ifBlank { "{\"prompt\":\"{prompt}\"}" }, ::esc)
        } else null

        val raw = Http.request(if (post) "POST" else "GET", url, headers, body, p.timeoutSec * 1000)
        return extract(raw, p.responsePath)
    }

    private fun buildPrompt(system: String, msgs: List<Msg>): String {
        val lines = ArrayList<String>()
        var size = 0
        for (m in msgs.asReversed()) {
            val l = (if (m.role == "user") "User: " else "You: ") + m.content
            if (size + l.length > 2500 && lines.isNotEmpty()) break
            lines.add(0, l); size += l.length
        }
        return system + "\n\nConversation so far:\n" + lines.joinToString("\n") + "\nYou:"
    }

    private val KEYS = listOf("result", "response", "answer", "message", "text", "reply", "output", "content", "data", "completion")

    fun extract(raw: String, path: String): String {
        val t = raw.trim()
        val json: Any? = try {
            if (t.startsWith("{")) JSONObject(t) else if (t.startsWith("[")) JSONArray(t) else null
        } catch (e: Exception) { null }
        if (json == null) return t
        if (path.isNotBlank()) {
            var cur: Any? = json
            for (part in path.split(".").map { it.trim() }.filter { it.isNotEmpty() }) {
                val c0 = cur
                cur = when (c0) {
                    is JSONObject -> c0.opt(part)
                    is JSONArray -> part.toIntOrNull()?.let { c0.opt(it) }
                    else -> null
                }
            }
            val s = cur?.let { if (it is String) it else it.toString() }
            if (!s.isNullOrBlank()) return s
            throw RuntimeException("responsePath '$path' not found in: " + t.take(120))
        }
        return auto(json) ?: throw RuntimeException("couldn't find the answer text in: " + t.take(160))
    }

    private fun auto(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.isNotBlank() }
        is JSONObject -> {
            var r: String? = null
            // OpenAI-style
            val ch = v.optJSONArray("choices")
            if (ch != null && ch.length() > 0) r = auto(ch.opt(0))
            if (r == null) for (k in KEYS) { if (v.has(k)) { r = auto(v.opt(k)); if (r != null) break } }
            r
        }
        is JSONArray -> if (v.length() > 0) auto(v.opt(0)) else null
        else -> null
    }
}
