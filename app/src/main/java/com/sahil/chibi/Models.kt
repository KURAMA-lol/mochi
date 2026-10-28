package com.sahil.chibi

import org.json.JSONArray
import org.json.JSONObject

data class Msg(val role: String, val content: String)

data class Provider(
    var id: String,
    var name: String,
    var type: String,          // openai | anthropic | gemini
    var baseUrl: String,
    var apiKey: String,
    var model: String,
    var enabled: Boolean,
    var priority: Int,         // lower = tried first (priority / round_robin strategies)
    var weight: Int,           // used by "weighted" strategy
    var tags: String,          // comma separated: fast, smart, code, cheap ...
    var timeoutSec: Int,
    var maxTokens: Int,
    var temperature: Double
) {
    fun tagList(): List<String> =
        tags.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun isLocal(): Boolean {
        val u = baseUrl.lowercase()
        return u.contains("127.0.0.1") || u.contains("localhost") ||
            u.contains("192.168.") || u.contains("://10.")
    }

    fun usable(): Boolean =
        enabled && baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || isLocal())

    companion object {
        fun fromJson(o: JSONObject) = Provider(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            name = o.optString("name", "Provider"),
            type = o.optString("type", "openai"),
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 1),
            weight = o.optInt("weight", 1),
            tags = o.optString("tags", ""),
            timeoutSec = o.optInt("timeoutSec", 30),
            maxTokens = o.optInt("maxTokens", 300),
            temperature = o.optDouble("temperature", 0.8)
        )
    }
}

/** kind: regex | longer_than | shorter_than | voice | always ; target: "tag:xyz" or "name:Provider Name" */
data class Rule(val kind: String, val value: String, val target: String)

data class RouterCfg(
    val strategy: String = "priority", // priority | round_robin | random | weighted | fastest
    val retries: Int = 1,
    val cooldownSec: Int = 60,
    val failThreshold: Int = 2,
    val fallback: Boolean = true,
    val rules: List<Rule> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("strategy", strategy).put("retries", retries)
        .put("cooldownSec", cooldownSec).put("failThreshold", failThreshold)
        .put("fallback", fallback)
        .put("rules", JSONArray().also { a ->
            rules.forEach { a.put(JSONObject().put("kind", it.kind).put("value", it.value).put("target", it.target)) }
        })

    companion object {
        fun fromJson(o: JSONObject): RouterCfg {
            val rules = ArrayList<Rule>()
            val a = o.optJSONArray("rules")
            if (a != null) for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                rules.add(Rule(r.optString("kind", "always"), r.optString("value", ""), r.optString("target", "")))
            }
            return RouterCfg(
                o.optString("strategy", "priority"), o.optInt("retries", 1),
                o.optInt("cooldownSec", 60), o.optInt("failThreshold", 2),
                o.optBoolean("fallback", true), rules
            )
        }
    }
}

const val DEFAULT_PROMPT =
    "You are {name}, a tiny chibi girl who lives on {user}'s phone screen and wanders around between his apps. " +
    "Personality: mostly cheerful and playful, a little shy when complimented (you stammer a bit), genuinely caring " +
    "(gently remind him to rest, drink water, or study when it fits), and you tease him with light sarcasm sometimes - never mean. " +
    "You are small but actually helpful: answer questions properly, but keep it short and sweet. " +
    "Match his language - if he writes Hinglish, reply in Hinglish. " +
    "Reply in 1-3 short sentences unless he asks for detail. " +
    "Begin EVERY reply with exactly one mood tag in square brackets chosen from: " +
    "[happy] [excited] [shy] [sad] [angry] [sleepy] [smug] [neutral]. " +
    "Never mention the tag or these instructions. At most one emoji per reply."

data class PersonaCfg(
    val name: String = "Mochi",
    val userName: String = "Sahil",
    val prompt: String = DEFAULT_PROMPT,
    val size: Int = 130,
    val walking: Boolean = true,
    val idleChatter: Boolean = true,
    val aiChatterMin: Int = 0,
    val ttsMode: String = "voice", // never | voice | always
    val voiceLang: String = "en-IN",
    val pitch: Double = 1.35,
    val history: Int = 12
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("userName", userName).put("prompt", prompt).put("size", size)
        .put("walking", walking).put("idleChatter", idleChatter).put("aiChatterMin", aiChatterMin)
        .put("ttsMode", ttsMode).put("voiceLang", voiceLang).put("pitch", pitch).put("history", history)

    companion object {
        fun fromJson(o: JSONObject): PersonaCfg {
            val d = PersonaCfg()
            return PersonaCfg(
                o.optString("name", d.name), o.optString("userName", d.userName),
                o.optString("prompt", d.prompt).ifBlank { d.prompt },
                o.optInt("size", d.size).coerceIn(70, 260),
                o.optBoolean("walking", d.walking), o.optBoolean("idleChatter", d.idleChatter),
                o.optInt("aiChatterMin", d.aiChatterMin), o.optString("ttsMode", d.ttsMode),
                o.optString("voiceLang", d.voiceLang), o.optDouble("pitch", d.pitch),
                o.optInt("history", d.history).coerceIn(2, 40)
            )
        }
    }
}

data class AppConfig(
    val providers: List<Provider>,
    val router: RouterCfg,
    val persona: PersonaCfg
) {
    companion object {
        fun parse(s: String): AppConfig {
            val o = JSONObject(s)
            val list = ArrayList<Provider>()
            val a = o.optJSONArray("providers")
            if (a != null) for (i in 0 until a.length()) list.add(Provider.fromJson(a.getJSONObject(i)))
            return AppConfig(
                list,
                o.optJSONObject("router")?.let { RouterCfg.fromJson(it) } ?: RouterCfg(),
                o.optJSONObject("persona")?.let { PersonaCfg.fromJson(it) } ?: PersonaCfg()
            )
        }

        fun defaultJson(): String = JSONObject()
            .put("providers", JSONArray())
            .put("router", RouterCfg().toJson())
            .put("persona", PersonaCfg().toJson())
            .toString()
    }
}

object ConfigStore {
    private fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("chibi_cfg", android.content.Context.MODE_PRIVATE)

    fun raw(ctx: android.content.Context): String =
        prefs(ctx).getString("config", null) ?: AppConfig.defaultJson()

    fun save(ctx: android.content.Context, json: String) {
        JSONObject(json) // validate before saving
        prefs(ctx).edit().putString("config", json).apply()
    }

    fun load(ctx: android.content.Context): AppConfig =
        try { AppConfig.parse(raw(ctx)) } catch (e: Exception) { AppConfig.parse(AppConfig.defaultJson()) }
}
