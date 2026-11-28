package com.sahil.chibi.plugins

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny HTTPS helper for plugins. Returns status code and body; never throws for HTTP errors. */
object Http {
    class Res(val code: Int, val body: String) {
        val ok get() = code in 200..299
        fun json(): JSONObject = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
    }

    fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, contentType: String = "application/json"): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 25000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType + "; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Res(code, text)
        } finally { c.disconnect() }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun form(vararg kv: Pair<String, String>): String = kv.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    /** Short readable reason for an HTTP error from a Google or Telegram style JSON body. */
    fun why(r: Res): String {
        val j = r.json()
        val m = j.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { j.optString("description") }.ifEmpty { j.optString("error_description") }.ifEmpty { r.body.take(120) }
        return "HTTP " + r.code + (if (m.isNotEmpty()) ": " + m.take(160) else "")
    }
}
