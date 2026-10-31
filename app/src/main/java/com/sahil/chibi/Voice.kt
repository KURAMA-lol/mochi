package com.sahil.chibi

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Natural-voice TTS over HTTP (OpenAI-style APIs incl. Kokoro, and ElevenLabs incl. voice cloning). */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var gen = 0

    fun raw(ctx: Context): String = ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).getString("json", "{}") ?: "{}"

    fun save(ctx: Context, json: String) {
        JSONObject(json)
        ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).edit().putString("json", json).apply()
    }

    fun cfg(ctx: Context): JSONObject = try { JSONObject(raw(ctx)) } catch (e: Exception) { JSONObject() }

    fun engine(ctx: Context): String = cfg(ctx).optString("engine", "system")

    private fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = try { (c.errorStream ?: c.inputStream).bufferedReader().readText() } catch (e: Exception) { "" }
                throw RuntimeException("HTTP " + code + " " + err.take(200))
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun synth(ctx: Context, text: String): ByteArray {
        val c = cfg(ctx)
        when (c.optString("engine", "system")) {
            "openai" -> {
                val o = c.optJSONObject("openai") ?: JSONObject()
                val url = o.optString("url")
                if (url.isBlank()) throw RuntimeException("API URL is empty")
                val body = JSONObject().put("model", o.optString("model", "tts-1")).put("input", text)
                    .put("voice", o.optString("voice", "alloy")).put("response_format", "mp3")
                val sp = o.optDouble("speed", 1.0)
                if (sp > 0.0 && sp != 1.0) body.put("speed", sp)
                val h = HashMap<String, String>()
                if (o.optString("key").isNotBlank()) h["Authorization"] = "Bearer " + o.optString("key").trim()
                return http("POST", url.trim(), h, body.toString().toByteArray(), "application/json")
            }
            "eleven" -> {
                val e = c.optJSONObject("eleven") ?: JSONObject()
                val vid = e.optString("voiceId").trim()
                if (vid.isBlank()) throw RuntimeException("Voice ID is empty")
                val body = JSONObject().put("text", text).put("model_id", e.optString("model", "eleven_multilingual_v2"))
                return http("POST", "https://api.elevenlabs.io/v1/text-to-speech/" + vid + "?output_format=mp3_44100_128",
                    mapOf("xi-api-key" to e.optString("key").trim()), body.toString().toByteArray(), "application/json")
            }
            "local" -> {
                val l = c.optJSONObject("local") ?: JSONObject()
                return LocalTts.synthWav(ctx, text, l.optInt("sid", 3), l.optDouble("speed", 1.0).toFloat())
            }
            else -> throw RuntimeException("no cloud voice engine selected")
        }
    }

    private fun clean(t: String): String {
        var s = t.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        s = s.replace(Regex("[*_`#~>]+"), "")
        s = s.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > 600) {
            val cut = s.lastIndexOf('.', 600)
            s = if (cut > 200) s.substring(0, cut + 1) else s.substring(0, 600)
        }
        return s
    }

    /** Synthesises and plays. onDone(null) = playback started, otherwise an error message. */
    fun speak(ctx: Context, text: String, onDone: (String?) -> Unit) {
        val clean = clean(text)
        if (clean.isBlank()) { onDone(null); return }
        val my = ++gen
        if (engine(ctx) == "local") {
            Thread {
                try {
                    speakLocal(ctx, clean, my)
                    onDone(null)
                } catch (e: Throwable) {
                    onDone((e.message ?: e.toString()).take(240))
                }
            }.start()
            return
        }
        Thread {
            try {
                val audio = synth(ctx, clean)
                play(ctx, audio, my)
                onDone(null)
            } catch (e: Throwable) {
                onDone((e.message ?: e.toString()).take(240))
            }
        }.start()
    }

    private fun splitSentences(s: String): List<String> {
        val out = ArrayList<String>()
        val parts = s.split(Regex("(?<=[.!?\u0964])\s+"))
        var cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && cur.length + p.length > 140) { out.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Synthesises sentence by sentence; the next one is generated while the current one plays. */
    private fun speakLocal(ctx: Context, text: String, my: Int) {
        val l = cfg(ctx).optJSONObject("local") ?: JSONObject()
        val sid = l.optInt("sid", 3)
        val speed = l.optDouble("speed", 1.0).toFloat()
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val futs = splitSentences(text).map { s ->
                ex.submit(java.util.concurrent.Callable { LocalTts.synthWav(ctx, s, sid, speed) })
            }
            for (f in futs) {
                if (my != gen) break
                val audio = f.get()
                if (my != gen) break
                playAndWait(ctx, audio, my)
            }
        } finally {
            ex.shutdownNow()
        }
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
        while (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            waited += 200
            if (myGen != gen || waited > 90000) break
        }
        f.delete()
    }

    fun stop() { gen++; main.post { stopPlayer() } }

    private fun stopPlayer() {
        try { player?.release() } catch (e: Exception) {}
        player = null
    }

    private fun play(ctx: Context, audio: ByteArray, myGen: Int) {
        val wav = audio.size > 4 && audio[0] == 0x52.toByte() && audio[1] == 0x49.toByte()
        val f = File(ctx.cacheDir, "tts_" + System.currentTimeMillis() + (if (wav) ".wav" else ".mp3"))
        f.writeBytes(audio)
        main.post {
            if (myGen != gen) { f.delete(); return@post }
            stopPlayer()
            val mp = MediaPlayer()
            try {
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { m -> m.release(); if (player === m) player = null; f.delete() }
                mp.setOnErrorListener { m, _, _ -> m.release(); if (player === m) player = null; f.delete(); true }
                mp.setOnPreparedListener { m -> m.start() }
                player = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                mp.release(); f.delete()
            }
        }
    }

    fun listVoices(ctx: Context): JSONArray {
        val e = cfg(ctx).optJSONObject("eleven") ?: JSONObject()
        val res = http("GET", "https://api.elevenlabs.io/v1/voices", mapOf("xi-api-key" to e.optString("key").trim()), null, null)
        val vs = JSONObject(String(res)).getJSONArray("voices")
        val out = JSONArray()
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i)
            out.put(JSONObject().put("id", v.getString("voice_id")).put("name", v.optString("name")))
        }
        return out
    }

    /** Instant voice clone from an audio sample (ElevenLabs). Returns the new voice id. */
    fun clone(ctx: Context, name: String, uri: Uri): String {
        val e = cfg(ctx).optJSONObject("eleven") ?: JSONObject()
        val type = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
        val data = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val boundary = "----hina" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fun part(s: String) { out.write(s.toByteArray()) }
        part("--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n")
        part("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"sample\"\r\nContent-Type: " + type + "\r\n\r\n")
        out.write(data)
        part("\r\n--" + boundary + "--\r\n")
        val res = http("POST", "https://api.elevenlabs.io/v1/voices/add", mapOf("xi-api-key" to e.optString("key").trim()),
            out.toByteArray(), "multipart/form-data; boundary=" + boundary)
        return JSONObject(String(res)).getString("voice_id")
    }
}
