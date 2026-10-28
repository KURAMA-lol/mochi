package com.sahil.chibi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Http {
    class HttpError(val code: Int, message: String) : Exception(message)

    fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = minOf(timeoutMs, 15000)
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, "HTTP $code: ${text.take(300)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}

object Adapters {
    fun call(p: Provider, system: String, msgs: List<Msg>): String {
        val clean = normalize(msgs)
        return when (p.type) {
            "anthropic" -> anthropic(p, system, clean)
            "gemini" -> gemini(p, system, clean)
            else -> openai(p, system, clean)
        }
    }

    /** Merge consecutive same-role messages and make sure the list starts with "user". */
    private fun normalize(msgs: List<Msg>): List<Msg> {
        val out = ArrayList<Msg>()
        for (m in msgs) {
            if (m.content.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) out[out.size - 1] = Msg(m.role, last.content + "\n" + m.content)
            else out.add(m)
        }
        while (out.isNotEmpty() && out[0].role != "user") out.removeAt(0)
        return out
    }

    private fun openai(p: Provider, system: String, msgs: List<Msg>): String {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", p.model).put("messages", arr)
            .put("max_tokens", p.maxTokens).put("temperature", p.temperature)
        val headers = HashMap<String, String>()
        if (p.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${p.apiKey}"
        val res = Http.postJson(p.baseUrl.trimEnd('/') + "/chat/completions", headers, body.toString(), p.timeoutSec * 1000)
        val o = JSONObject(res)
        return o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    private fun anthropic(p: Provider, system: String, msgs: List<Msg>): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", p.model).put("max_tokens", p.maxTokens)
            .put("system", system).put("messages", arr).put("temperature", p.temperature.coerceAtMost(1.0))
        val headers = mapOf("x-api-key" to p.apiKey, "anthropic-version" to "2023-06-01")
        val res = Http.postJson(p.baseUrl.trimEnd('/') + "/v1/messages", headers, body.toString(), p.timeoutSec * 1000)
        val content = JSONObject(res).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString()
    }

    private fun gemini(p: Provider, system: String, msgs: List<Msg>): String {
        val contents = JSONArray()
        msgs.forEach {
            val role = if (it.role == "assistant") "model" else "user"
            contents.put(JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", it.content))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", p.maxTokens).put("temperature", p.temperature))
        val url = p.baseUrl.trimEnd('/') + "/v1beta/models/${p.model}:generateContent"
        val res = Http.postJson(url, mapOf("x-goog-api-key" to p.apiKey), body.toString(), p.timeoutSec * 1000)
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }
}
