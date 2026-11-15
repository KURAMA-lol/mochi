package com.sahil.chibi

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/** Embedded Python (Chaquopy). Started on first use, so the app opens as fast as before. */
object PyRuntime {
    @Synchronized
    private fun py(ctx: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        return Python.getInstance()
    }

    /** Runs one Agent-Reach command line, e.g. "github read owner/repo", and returns text for the model. */
    fun reach(ctx: Context, command: String): String {
        if (command.isBlank()) return "No command given. Example: youtube subtitle <link>"
        val home = java.io.File(ctx.filesDir, "reach").absolutePath
        val raw = py(ctx).getModule("nana_reach").callAttr("run", command, home).toString()
        val o = JSONObject(raw)
        val out = o.optString("out").trim()
        val err = o.optString("err").trim()
        if (out.isEmpty()) return if (err.isNotEmpty()) "Agent-Reach error: " + err.take(500) else "Agent-Reach returned nothing."
        return compact(out).take(6000)
    }

    /** Agent-Reach prints a JSON envelope; keep only what a model needs. */
    private fun compact(out: String): String {
        if (!out.startsWith("{")) return out
        return try {
            val o = JSONObject(out)
            if (!o.optBoolean("ok", true)) {
                val e = o.optJSONObject("error")
                return "Failed: " + (e?.optString("message") ?: out.take(400))
            }
            val items: JSONArray = o.optJSONArray("items") ?: return out
            val sb = StringBuilder()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(it.optString("title")).append('\n')
                val url = it.optString("url"); if (url.isNotEmpty() && url != "null") sb.append("   ").append(url).append('\n')
                val who = it.optString("author"); if (who.isNotEmpty() && who != "null") sb.append("   by ").append(who).append('\n')
                val txt = it.optString("text"); if (txt.isNotEmpty() && txt != "null") sb.append("   ").append(txt.take(1800).replace('\n', ' ')).append('\n')
            }
            if (sb.isEmpty()) out else sb.toString()
        } catch (e: Exception) { out }
    }
}
