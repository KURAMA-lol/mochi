package com.sahil.chibi

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/** Speaks Nana's replies with an on-phone voice: Piper (small and fast), Pocket-TTS (more natural) or tiny-voca (small, two English voices). */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var gen = 0
    @Volatile private var busy = false

    fun playing(): Boolean = player != null || busy

    fun raw(ctx: Context): String = ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).getString("json", "{}") ?: "{}"

    fun save(ctx: Context, json: String) {
        JSONObject(json)
        ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).edit().putString("json", json).apply()
        // picking tiny-voca starts its hidden page right away
        try { if (engine(ctx) == "tiny") TinyVoca.warm(ctx) } catch (e: Throwable) {}
    }

    fun cfg(ctx: Context): JSONObject = try { JSONObject(raw(ctx)) } catch (e: Exception) { JSONObject() }

    /** "piper", "pocket" or "tiny". Older saved engines (phone voice, cloud APIs) fall back to Piper. */
    fun engine(ctx: Context): String = when (cfg(ctx).optString("engine")) {
        "pocket" -> "pocket"
        "tiny" -> "tiny"
        else -> "piper"
    }

    private fun clean(t: String): String {
        var s = t.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        s = s.replace(Regex("[*_`#~>]+"), "")
        s = s.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > 700) {
            val cut = s.lastIndexOf('.', 700)
            s = if (cut > 200) s.substring(0, cut + 1) else s.substring(0, 700)
        }
        return s
    }

    private fun sentences(t: String): List<String> {
        val raw = t.split(Regex("(?<=[.!?\\u2026\\u0964])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = ""
        for (s in raw) {
            cur = if (cur.isEmpty()) s else "$cur $s"
            if (cur.length >= 25) { out.add(cur); cur = "" }
        }
        if (cur.isNotEmpty()) { if (out.isEmpty()) out.add(cur) else out[out.size - 1] = out[out.size - 1] + " " + cur }
        return out
    }

    /** Synthesises sentence by sentence in the background while the previous one plays. onDone(null) = playback started. */
    fun speak(ctx: Context, text: String, onDone: (String?) -> Unit) {
        val clean = clean(text)
        if (clean.isBlank()) { onDone(null); return }
        val my = ++gen
        Thread {
            val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
            var started = false
            busy = true
            try {
                val futs = sentences(clean).map { s -> ex.submit<ByteArray> { Speech.synth(ctx, s) } }
                for (f in futs) {
                    if (my != gen) break
                    val a = f.get()
                    if (my != gen) break
                    if (!started) { started = true; onDone(null) }
                    playAndWait(ctx, a, my)
                }
            } catch (e: Throwable) {
                if (!started) { started = true; onDone((e.cause?.message ?: e.message ?: e.toString()).take(240)) }
            } finally {
                ex.shutdownNow()
                if (my == gen) busy = false
                if (!started) onDone(null)
            }
        }.start()
    }

    private fun playAndWait(ctx: Context, audio: ByteArray, myGen: Int) {
        val f = File(ctx.cacheDir, "tts_" + System.nanoTime() + ".wav")
        f.writeBytes(audio)
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            if (myGen != gen) { f.delete(); latch.countDown(); return@post }
            stopPlayer()
            val mp = MediaPlayer()
            try {
                val usage = if (CallAudio.active()) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { m -> m.release(); if (player === m) player = null; latch.countDown() }
                mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; latch.countDown(); true }
                mp.setOnPreparedListener { m -> m.start() }
                player = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                mp.release(); latch.countDown()
            }
        }
        var waited = 0
        while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            waited += 100
            if (myGen != gen || waited > 90000) break
        }
        f.delete()
    }

    fun stop() { gen++; busy = false; main.post { stopPlayer() } }

    private fun stopPlayer() {
        try { player?.release() } catch (e: Exception) {}
        player = null
    }
}
