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
import java.net.URLEncoder
import java.util.UUID

/** Natural-voice TTS over HTTP (OpenAI-style APIs incl. Kokoro, and ElevenLabs incl. voice cloning). */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var gen = 0
    @Volatile private var piperBusy = false

    fun playing(): Boolean = player != null || piperBusy

    fun raw(ctx: Context): String = ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).getString("json", "{}") ?: "{}"

    fun save(ctx: Context, json: String) {
        JSONObject(json)
        ctx.getSharedPreferences("voice_cfg", Context.MODE_PRIVATE).edit().putString("json", json).apply()
    }

    fun cfg(ctx: Context): JSONObject = try { JSONObject(raw(ctx)) } catch (e: Exception) { JSONObject() }

    @Volatile var cuesOn = false

    fun engine(ctx: Context): String {
        val c = cfg(ctx)
        val e = c.optString("engine", "system").let { if (it == "local") "system" else it }
        cuesOn = e != "system" && c.optBoolean("sfx", true)
        return e
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            if (!headers.containsKey("User-Agent")) c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Nana")
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
        when (engine(ctx)) {
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
            "piper" -> return Speech.synth(ctx, text)
            "custom" -> return customSynth(c.optJSONObject("custom") ?: JSONObject(), text)
            else -> throw RuntimeException("no cloud voice engine selected")
        }
    }


    // ---- custom HTTP voice (prexzyapis presets or any URL) ----

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun autoStyle(t: String): String {
        val l = t.lowercase()
        return when {
            Regex("sorry|\\bsad\\b|miss you|unfortunately|cry").containsMatchIn(l) -> "sad"
            Regex("whisper|secret|shh").containsMatchIn(l) -> "whispering"
            t.contains('!') -> "cheerful"
            else -> "friendly"
        }
    }

    /** Turns an API reply into audio bytes: raw audio, or JSON holding an audio URL / base64. */
    private fun resolveAudio(bytes: ByteArray, field: String): ByteArray {
        if (bytes.isEmpty() || bytes[0] != 0x7B.toByte()) return bytes
        val j = JSONObject(String(bytes))
        var v: Any? = null
        if (field.isNotBlank()) {
            var cur: Any? = j
            for (k in field.split('.')) cur = (cur as? JSONObject)?.opt(k)
            v = cur
        } else {
            for (k in listOf("audio_url", "audioUrl", "url", "audio", "data", "result", "file")) {
                val x = j.opt(k)
                if (x is String && x.isNotBlank()) { v = x; break }
                if (x is JSONObject) {
                    for (k2 in listOf("audio_url", "url", "audio")) { val y = x.optString(k2); if (y.isNotBlank()) { v = y; break } }
                    if (v != null) break
                }
            }
        }
        val s = (v as? String)?.trim()
        if (s.isNullOrBlank()) throw RuntimeException("no audio in reply: " + String(bytes).take(160))
        if (s.startsWith("http")) return http("GET", s, emptyMap(), null, null)
        val b64 = if (s.contains("base64,")) s.substringAfter("base64,") else s
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    private fun customSynth(o: JSONObject, text: String): ByteArray {
        if (o.optString("preset", "prexzy") == "prexzy") {
            val voice = o.optString("voice", "amy").ifBlank { "amy" }
            val named = !voice.startsWith("tts-")
            val want = o.optString("style", "auto")
            val style: String? = if (!named || want == "none" || want.isBlank()) null else if (want == "auto") autoStyle(text) else want
            fun build(st: String?): String {
                val sb = StringBuilder("https://prexzyapis.com/tts/").append(voice).append("?text=").append(enc(text))
                if (o.optString("speed").isNotBlank()) sb.append("&speed=").append(enc(o.optString("speed").trim()))
                if (o.optString("pitch").isNotBlank()) sb.append("&pitch=").append(enc(o.optString("pitch").trim()))
                if (st != null) sb.append("&style=").append(enc(st))
                return sb.toString()
            }
            val first = try { resolveAudio(http("GET", build(style), emptyMap(), null, null), "") }
            catch (e: Exception) { if (style != null) null else throw e }
            return first ?: resolveAudio(http("GET", build(null), emptyMap(), null, null), "")
        }
        val tpl = o.optString("url").trim()
        if (tpl.isBlank()) throw RuntimeException("API URL is empty")
        val method = o.optString("method", "GET").uppercase()
        val hd = HashMap<String, String>()
        for (line in o.optString("headers").lines()) {
            val i = line.indexOf(':')
            if (i > 0) hd[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val url = tpl.replace("{text}", enc(text))
        var body: ByteArray? = null
        var ct: String? = null
        if (method == "POST") {
            val b = o.optString("body")
            val q = JSONObject.quote(text)
            body = (if (b.isBlank()) "{\"text\":" + q + "}" else b.replace("\"{text}\"", q).replace("{text}", q.substring(1, q.length - 1))).toByteArray()
            ct = hd.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json"
        }
        return resolveAudio(http(method, url, hd, body, ct), o.optString("field").trim())
    }

    // ---- sound cues: haha, *giggles*, *coughs* ... become real clips ----

    private class Seg(val text: String?, val sfx: String?)

    private val CUES = Regex("(?i)\\*\\s*(laughs?|laughing|giggles?|giggling|chuckles?|chuckling|coughs?|coughing|sighs?|sighing|gasps?|clears throat)\\s*\\*|\\(\\s*(laughs?|giggles?|chuckles?|coughs?|sighs?|gasps?)\\s*\\)|\\b(?:ha(?:ha)+h?|he(?:he)+h?|hihi+)\\b|😂|🤣|😆")
    private val SFXQ = mapOf("laugh" to "female laugh", "giggle" to "girl giggle", "chuckle" to "girl chuckle",
        "cough" to "cough", "sigh" to "female sigh", "gasp" to "gasp")

    private fun cueKey(s: String): String {
        val l = s.lowercase()
        return when {
            l.contains("giggl") || l.contains("hehe") || l.contains("hihi") || l.contains("😆") -> "giggle"
            l.contains("chuckl") -> "chuckle"
            l.contains("cough") || l.contains("throat") -> "cough"
            l.contains("sigh") -> "sigh"
            l.contains("gasp") -> "gasp"
            else -> "laugh"
        }
    }

    private fun segments(ctx: Context, raw: String): List<Seg> {
        val out = ArrayList<Seg>()
        if (!cfg(ctx).optBoolean("sfx", true)) {
            val c = clean(raw); if (c.isNotBlank()) out.add(Seg(c, null)); return out
        }
        var last = 0
        var n = 0
        for (m in CUES.findAll(raw)) {
            val before = clean(raw.substring(last, m.range.first))
            if (before.isNotBlank()) out.add(Seg(before, null))
            if (n < 3) { out.add(Seg(null, cueKey(m.value))); n++ }
            last = m.range.last + 1
        }
        val tail = clean(raw.substring(last))
        if (tail.isNotBlank()) out.add(Seg(tail, null))
        return out
    }

    /** Returns a cached clip for the cue, fetching up to 3 short human recordings the first time. */
    private fun sfxClip(ctx: Context, key: String): ByteArray? {
        val dir = File(ctx.filesDir, "sfx")
        dir.mkdirs()
        fun have(): List<File> = (dir.listFiles { f -> f.name.startsWith(key + "_") } ?: emptyArray()).toList()
        if (have().size < 3) {
            try {
                val q = SFXQ[key] ?: key
                val j = JSONObject(String(http("GET", "https://prexzyapis.com/sound/search?query=" + enc(q), emptyMap(), null, null)))
                val arr = j.getJSONArray("results")
                var n = have().size
                for (i in 0 until arr.length()) {
                    if (n >= 3) break
                    val o = arr.getJSONObject(i)
                    val d = o.optString("duration").toDoubleOrNull() ?: continue
                    if (d < 0.5 || d > 3.6) continue
                    val mp3 = o.optString("mp3")
                    if (!mp3.startsWith("http")) continue
                    val f = File(dir, key + "_" + o.optLong("soundId") + ".mp3")
                    if (f.exists()) continue
                    try { f.writeBytes(http("GET", mp3, emptyMap(), null, null)); n++ } catch (e: Exception) { f.delete() }
                }
            } catch (e: Exception) { }
        }
        val l = have()
        return if (l.isEmpty()) null else l[(Math.random() * l.size).toInt()].readBytes()
    }

    /** Speaks text pieces and sound cues in order; the next piece is prepared while the current one plays. */
    private fun speakSegs(ctx: Context, segs: List<Seg>, my: Int, onDone: (String?) -> Unit) {
        Thread {
            val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
            var started = false
            piperBusy = true
            val piper = engine(ctx) == "piper"
            try {
                val units = ArrayList<Seg>()
                for (s in segs) {
                    if (s.text != null && piper) for (x in sentences(s.text)) units.add(Seg(x, null)) else units.add(s)
                }
                val futs = units.map { u -> ex.submit<ByteArray?> { if (u.sfx != null) sfxClip(ctx, u.sfx) else synth(ctx, u.text ?: "") } }
                for (f in futs) {
                    if (my != gen) break
                    val a = try { f.get() } catch (e: Exception) { if (!started) throw e; null }
                    if (my != gen) break
                    if (!started) { started = true; onDone(null) }
                    if (a != null) playAndWait(ctx, a, my)
                }
            } catch (e: Throwable) {
                if (!started) { started = true; onDone((e.cause?.message ?: e.message ?: e.toString()).take(240)) }
            } finally {
                ex.shutdownNow()
                piperBusy = false
                if (!started) onDone(null)
            }
        }.start()
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
        val segs = segments(ctx, text)
        if (segs.isEmpty()) { onDone(null); return }
        val my = ++gen
        if (engine(ctx) == "piper" || segs.any { it.sfx != null }) { speakSegs(ctx, segs, my, onDone); return }
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

    private fun playAndWait(ctx: Context, audio: ByteArray, myGen: Int) {
        val isWav = audio.size > 4 && audio[0] == 0x52.toByte() && audio[1] == 0x49.toByte()
        val f = File(ctx.cacheDir, "tts_" + System.nanoTime() + (if (isWav) ".wav" else ".mp3"))
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
