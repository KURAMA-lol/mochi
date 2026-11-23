package com.sahil.chibi

import android.app.Application
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Offline chat: a small GGUF model (for example Qwen3-0.6B) runs on the phone through llama.cpp (MIT), compiled by CI
 * into libnana_llm.so. The file is picked once in Settings > AI and copied into the app's private folder.
 * It is a provider like any other (type "local"), so the router can use it first, last, or only when offline.
 */
object LocalLlm {
    private external fun nativeLoad(path: String, nctx: Int, threads: Int): ByteArray
    private external fun nativeGenerate(prompt: String, maxNew: Int, temp: Float): ByteArray
    private external fun nativeStop()

    private const val NCTX = 3072
    private const val MAX_PROMPT_CHARS = 6200
    private var libTried = false
    private var libErr = ""
    private var loaded = false

    fun file(ctx: Context): File = File(File(ctx.filesDir, "llm"), "model.gguf")
    fun ready(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 20_000_000 }

    fun remove(ctx: Context) {
        synchronized(this) { loaded = false }
        file(ctx).delete()
    }

    fun statusJson(ctx: Context): String {
        val f = file(ctx)
        val has = try { ConfigStore.load(ctx).providers.any { it.type == "local" } } catch (e: Exception) { false }
        return JSONObject().put("ready", ready(ctx)).put("mb", (f.length() / 1_000_000L).toInt()).put("has", has).toString()
    }

    private fun app(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application

    @Synchronized
    private fun ensure(ctx: Context) {
        if (!libTried) {
            libTried = true
            try { System.loadLibrary("nana_llm") } catch (t: Throwable) { libErr = t.message ?: t.toString() }
        }
        if (libErr.isNotEmpty()) throw RuntimeException("the offline engine did not load: " + libErr.take(200))
        if (loaded) return
        if (!ready(ctx)) throw RuntimeException("no offline model yet (Settings > AI > Offline model)")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val r = String(nativeLoad(file(ctx).absolutePath, NCTX, threads), Charsets.UTF_8)
        if (r.startsWith("ERR:")) throw RuntimeException(r.removePrefix("ERR:").trim().take(200))
        loaded = true
    }

    private val THINK = Regex("<think>[\\s\\S]*?</think>")

    /** ChatML (Qwen, many others). The empty think block keeps Qwen3 from reasoning out loud. */
    private fun prompt(system: String, msgs: List<Msg>): String {
        val sys = system.take(2400)
        var budget = MAX_PROMPT_CHARS - sys.length
        val keep = ArrayList<Msg>()
        for (m in msgs.takeLast(8).reversed()) {
            val c = m.content.take(1500)
            if (c.length > budget && keep.isNotEmpty()) break
            budget -= c.length
            keep.add(0, Msg(m.role, c))
        }
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        for (m in keep) sb.append("<|im_start|>").append(if (m.role == "assistant") "assistant" else "user").append('\n').append(m.content).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString()
    }

    fun call(p: Provider, system: String, msgs: List<Msg>): String {
        val ctx = app()
        ensure(ctx)
        val max = p.maxTokens.coerceIn(32, 512)
        val raw = String(synchronized(this) { nativeGenerate(prompt(system, msgs), max, p.temperature.toFloat().coerceIn(0f, 1.2f)) }, Charsets.UTF_8)
        if (raw.startsWith("ERR:")) throw RuntimeException(raw.removePrefix("ERR:").trim().take(200))
        var t = THINK.replace(raw, "").trim()
        if (t.isEmpty()) throw RuntimeException("the offline model gave an empty answer")
        if (system.contains("mood tag") && !t.startsWith("[")) t = "[neutral] $t"
        return t
    }

    fun test(ctx: Context): Pair<Boolean, String> {
        return try {
            val t0 = System.currentTimeMillis()
            ensure(ctx)
            val r = call(Provider("t", "Offline", "local", "on-device", "", "local", true, 1, 1, "", 120, 48, 0.3),
                "You are a friendly assistant. Reply in one short sentence.", listOf(Msg("user", "Say hello and tell me you work offline.")))
            true to (r.take(160) + "  (" + (System.currentTimeMillis() - t0) / 1000 + " s)")
        } catch (t: Throwable) { false to (t.message ?: t.toString()).take(200) }
    }

    fun stop() { try { nativeStop() } catch (t: Throwable) {} }
}
