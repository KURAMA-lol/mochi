package com.sahil.chibi

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * App connections: saved intents that let Nana trigger things in other apps without tapping through them,
 * for example a Tasker task, a MacroDroid macro or a deep link. She only fires one when you ask.
 */
object Connect {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("connect", Context.MODE_PRIVATE)
    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

    fun load(c: Context): JSONArray = try { JSONArray(prefs(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
    fun json(c: Context): String = load(c).toString()

    /** o: {name, kind, action, pkg, cls, data, extras, note} */
    fun save(c: Context, json: String): String {
        val o = try { JSONObject(json) } catch (e: Exception) { return "Bad data." }
        val n = slug(o.optString("name"))
        if (n.isEmpty()) return "Give it a name."
        val kind = o.optString("kind", "broadcast")
        if (kind !in setOf("broadcast", "activity", "service")) return "Unknown type."
        if (o.optString("action").isBlank() && o.optString("data").isBlank() && o.optString("pkg").isBlank()) return "Fill at least an action, a link or an app package."
        val rec = JSONObject().put("name", n).put("kind", kind).put("action", o.optString("action").trim()).put("pkg", o.optString("pkg").trim())
            .put("cls", o.optString("cls").trim()).put("data", o.optString("data").trim()).put("extras", o.optString("extras").take(1500)).put("note", o.optString("note").trim().take(200))
        val a = load(c); val out = JSONArray()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("name") != n) out.put(x) }
        out.put(rec)
        prefs(c).edit().putString("list", out.toString()).apply()
        return "ok"
    }

    fun delete(c: Context, name: String) {
        val a = load(c); val out = JSONArray()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("name") != name) out.put(x) }
        prefs(c).edit().putString("list", out.toString()).apply()
    }

    private fun holes(o: JSONObject): List<String> =
        Regex("\\{(\\w+)\\}").findAll(o.optString("data") + "\n" + o.optString("extras")).map { it.groupValues[1] }.distinct().toList()

    /** Names + what they do + what Nana has to fill in. Used by the list tool and the system prompt. */
    fun describe(c: Context): String {
        val a = load(c)
        if (a.length() == 0) return "No app connections are set up. The user can add them in Settings > Setup > App connections."
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val h = holes(o)
            sb.append("- ").append(o.optString("name")).append(": ").append(o.optString("note").ifEmpty { o.optString("kind") })
            if (h.isNotEmpty()) sb.append(" (params: ").append(h.joinToString(", ")).append(")")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun block(c: Context): String {
        val a = load(c)
        if (a.length() == 0) return ""
        return "\n\nAPP CONNECTIONS you can fire with app_connect {\"name\":..,\"params\":{..}} when the user asks (background, no tapping):\n" + describe(c)
    }

    private fun params(raw: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val t = raw.trim()
        if (t.isEmpty()) return m
        try { val o = JSONObject(t); for (k in o.keys()) m[k] = o.optString(k); return m } catch (e: Exception) {}
        for (line in t.split("\n", ";")) { val i = line.indexOf('='); if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim() }
        return m
    }

    private fun fill(tpl: String, p: Map<String, String>, enc: Boolean): String {
        var s = tpl
        for ((k, v) in p) s = s.replace("{$k}", if (enc) Uri.encode(v) else v)
        return s
    }

    fun fire(c: Context, name: String, rawParams: String): String {
        val a = load(c)
        val n = slug(name)
        var o: JSONObject? = null
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("name") == n) o = a.getJSONObject(i)
        if (o == null) return "No connection called \"$name\". " + describe(c)
        val p = params(rawParams)
        val miss = holes(o).filter { !p.containsKey(it) }
        if (miss.isNotEmpty()) return "Missing params: " + miss.joinToString(", ")
        val intent = Intent()
        val action = o.optString("action"); if (action.isNotEmpty()) intent.action = action
        val data = fill(o.optString("data"), p, true); if (data.isNotEmpty()) intent.data = Uri.parse(data)
        val pkg = o.optString("pkg"); val cls = o.optString("cls")
        if (pkg.isNotEmpty() && cls.isNotEmpty()) intent.setClassName(pkg, cls) else if (pkg.isNotEmpty()) intent.setPackage(pkg)
        for (line in o.optString("extras").split("\n")) {
            val i = line.indexOf('='); if (i <= 0) continue
            intent.putExtra(line.substring(0, i).trim(), fill(line.substring(i + 1).trim(), p, false))
        }
        return try {
            when (o.optString("kind")) {
                "activity" -> { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(intent) }
                "service" -> c.startService(intent)
                else -> c.sendBroadcast(intent)
            }
            "Sent \"$n\" to " + (pkg.ifEmpty { "the phone" }) + ". I cannot see the result unless that app tells me."
        } catch (e: Exception) { "Could not send: " + (e.message ?: e.toString()).take(160) }
    }
}
