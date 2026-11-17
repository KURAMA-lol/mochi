package com.sahil.chibi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-phone speech: Piper voices (text to speech), a small streaming recognizer, and an offline "Hey Nana" spotter.
 * All three run on sherpa-onnx (Apache-2.0). Models are downloaded once from the sherpa-onnx release page.
 */
object Speech {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
    private const val KWS_URL = BASE + "kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    private const val POCKET_URL = BASE + "tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"
    private val POCKET_FILES = listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json")
    private const val ASR_URL = BASE + "asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2"

    class PV(val id: String, val label: String, val mb: Int)

    val VOICES = listOf(
        PV("en_US-amy-medium", "Amy - US English, friendly female", 21),
        PV("en_US-kristin-medium", "Kristin - US English, calm female", 21),
        PV("en_US-hfc_female-medium", "US English female (clear)", 21),
        PV("en_US-ljspeech-medium", "LJ - US English female (storyteller)", 21),
        PV("en_US-kathleen-low", "Kathleen - US English female (light, fast)", 21),
        PV("en_GB-jenny_dioco-medium", "Jenny - British English female", 21),
        PV("en_GB-alba-medium", "Alba - Scottish English female", 21),
        PV("en_GB-cori-medium", "Cori - British English female", 21),
        PV("en_GB-southern_english_female-medium", "Southern English female", 24),
        PV("en_US-lessac-medium", "Lessac - US English (neutral)", 21),
        PV("en_US-ryan-medium", "Ryan - US English male", 21),
        PV("en_US-joe-medium", "Joe - US English male", 21),
        PV("en_US-john-medium", "John - US English male", 21),
        PV("hi_IN-priyamvada-medium", "Priyamvada - Hindi (Devanagari text only)", 21),
        PV("hi_IN-pratham-medium", "Pratham - Hindi male (Devanagari text only)", 21),
        PV("hi_IN-rohan-medium", "Rohan - Hindi male (Devanagari text only)", 21),
    )

    @Volatile private var busy = ""
    @Volatile private var msg = ""
    @Volatile private var pct = -1
    @Volatile private var busyId = ""
    @Volatile private var lastKind = ""
    @Volatile private var lastId = ""
    @Volatile private var cancelFlag = false

    private fun root(ctx: Context) = File(ctx.filesDir, "speech").apply { mkdirs() }
    private fun voiceDir(ctx: Context, id: String) = File(File(root(ctx), "voices"), id)
    private fun modelDir(ctx: Context, kind: String) = File(root(ctx), kind)

    private fun guard(ctx: Context) = ctx.getSharedPreferences("speech_guard", Context.MODE_PRIVATE)

    /** Marks a model as "loading" so a native crash while loading turns it off instead of crash-looping. */
    fun <T> guarded(ctx: Context, name: String, block: () -> T): T {
        val g = guard(ctx)
        if (g.getBoolean(name, false)) throw RuntimeException("$name crashed on this phone and was turned off. Delete and download it again.")
        g.edit().putBoolean(name, true).commit()
        val r = block()
        g.edit().putBoolean(name, false).commit()
        return r
    }

    fun ears(ctx: Context): JSONObject = Voice.cfg(ctx).optJSONObject("ears") ?: JSONObject()

    fun ready(ctx: Context, kind: String): Boolean = when (kind) {
        "kws" -> File(modelDir(ctx, "kws"), "tokens.txt").exists() && File(modelDir(ctx, "kws"), "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").exists()
        "asr" -> File(modelDir(ctx, "asr"), "tokens.txt").exists() && File(modelDir(ctx, "asr"), "encoder.onnx").exists()
        "pocket" -> POCKET_FILES.all { File(modelDir(ctx, "pocket"), it).exists() }
        else -> false
    }

    fun kwsOk(ctx: Context): Boolean = ready(ctx, "kws") && !guard(ctx).getBoolean("kws", false)
    /** Wake by reading the live transcript for "Hey Nana" (works with any accent the recognizer understands). */
    fun asrWake(ctx: Context): Boolean = ears(ctx).optBoolean("accurate", true) && ready(ctx, "asr") && !guard(ctx).getBoolean("asr", false)
    fun wakeLocal(ctx: Context): Boolean = ears(ctx).optString("wake", "local") == "local" && (kwsOk(ctx) || asrWake(ctx))

    private val WAKE_STRONG = Regex("\\b(?:n[aeu]+n+[aeu]+h?|na\\s+na|nan\\s+a|naan[aeu]?)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)
    private val WAKE_WEAK = Regex("\\b(?:hey|hi|hello|ok|okay)\\s+(?:anna|hanna|hannah|nina|nona|lana|nonna|mana)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)

    /** The words after the wake word when the text starts a "Hey Nana" call, otherwise null. */
    fun wakeMatch(t: String): String? {
        val m = WAKE_STRONG.find(t) ?: WAKE_WEAK.find(t) ?: return null
        if (m.range.first > 14) return null
        return t.substring(m.range.last + 1).trim()
    }
    fun asrLocal(ctx: Context): Boolean = ears(ctx).optString("asr", "local") == "local" && ready(ctx, "asr") && !guard(ctx).getBoolean("asr", false)
    fun screenOffOk(ctx: Context): Boolean = ears(ctx).optBoolean("screenOff", false)

    private fun thr(ctx: Context): String = when (ears(ctx).optString("sens", "normal")) {
        "low" -> "0.45"
        "high" -> "0.20"
        else -> "0.30"
    }

    fun wakeKey(ctx: Context): String = thr(ctx)

    fun kwsConfig(ctx: Context): KeywordSpotterConfig {
        val d = modelDir(ctx, "kws")
        val t = thr(ctx)
        val kwf = File(root(ctx), "keywords_nana.txt")
        val raw = ctx.assets.open("nana/keywords_nana.txt").bufferedReader().use { it.readText() }
        kwf.writeText(raw.replace("#0.30", "#$t"))
        return KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(d, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    decoder = File(d, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(d, "joiner-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                ),
                tokens = File(d, "tokens.txt").absolutePath,
                numThreads = 1,
                provider = "cpu",
            ),
            keywordsFile = kwf.absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = t.toFloat(),
        )
    }

    fun asrConfig(ctx: Context): OnlineRecognizerConfig {
        val d = modelDir(ctx, "asr")
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(d, "encoder.onnx").absolutePath,
                    decoder = File(d, "decoder.onnx").absolutePath,
                    joiner = File(d, "joiner.onnx").absolutePath,
                ),
                tokens = File(d, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    }

    // ------------------------------------------------------------ downloads

    fun voicesJson(ctx: Context): String {
        val a = JSONArray()
        for (v in VOICES) a.put(JSONObject().put("id", v.id).put("label", v.label).put("mb", v.mb).put("installed", voiceReady(ctx, v.id)))
        return a.toString()
    }

    private fun voiceReady(ctx: Context, id: String): Boolean {
        val d = voiceDir(ctx, id)
        return File(d, "tokens.txt").exists() && (d.listFiles { f -> f.name.endsWith(".onnx") }?.isNotEmpty() == true)
    }

    fun status(ctx: Context): JSONObject {
        val installed = JSONArray()
        for (v in VOICES) if (voiceReady(ctx, v.id)) installed.put(v.id)
        val g = guard(ctx)
        val dis = JSONArray()
        for (k in listOf("tts", "kws", "asr", "pocket")) if (g.getBoolean(k, false)) dis.put(k)
        return JSONObject().put("busy", busy.isNotEmpty()).put("busyKind", busy).put("busyId", busyId).put("lastKind", lastKind).put("lastId", lastId).put("msg", msg).put("pct", pct)
            .put("kws", ready(ctx, "kws")).put("asr", ready(ctx, "asr")).put("pocket", ready(ctx, "pocket")).put("voices", installed).put("disabled", dis)
    }

    fun cancel() { cancelFlag = true }

    private fun download(url: String, out: File, label: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("Download failed: HTTP " + c.responseCode)
            val total = c.contentLengthLong
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val b = ByteArray(65536)
                    var n = 0L
                    var lastUi = 0L
                    while (true) {
                        val r = i.read(b)
                        if (r <= 0) break
                        if (cancelFlag) throw RuntimeException("Cancelled")
                        o.write(b, 0, r)
                        n += r
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 400) {
                            lastUi = now
                            pct = if (total > 0) (n * 100 / total).toInt() else -1
                            msg = "Downloading: " + (n / 1000000) + (if (total > 0) " / " + (total / 1000000) else "") + " MB" + (if (pct >= 0) " (" + pct + "%)" else "")
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    fun install(ctx: Context, kind: String, id: String) {
        if (busy.isNotEmpty()) return
        val app = ctx.applicationContext
        val url: String
        val dest: File
        val label: String
        when (kind) {
            "kws" -> { url = KWS_URL; dest = modelDir(app, "kws"); label = "wake word model" }
            "asr" -> { url = ASR_URL; dest = modelDir(app, "asr"); label = "speech recognition model" }
            "pocket" -> { url = POCKET_URL; dest = modelDir(app, "pocket"); label = "Pocket-TTS voice" }
            "voice" -> {
                val v = VOICES.firstOrNull { it.id == id } ?: return
                url = BASE + "tts-models/vits-piper-" + v.id + "-int8.tar.bz2"
                dest = voiceDir(app, v.id); label = v.id
            }
            else -> return
        }
        val have = when (kind) { "kws", "asr", "pocket" -> ready(app, kind); else -> voiceReady(app, id) }
        if (have) { lastKind = kind; lastId = id; msg = "Already downloaded."; return }
        cancelFlag = false
        busy = kind; busyId = id; lastKind = kind; lastId = id; msg = "Starting..."; pct = 0
        Thread {
            val tar = File(app.cacheDir, "dl_" + System.nanoTime() + ".tar.bz2")
            try {
                download(url, tar, label)
                msg = "Unpacking $label..."; pct = -1
                val r = PyRuntime.unpack(app, tar.absolutePath, dest.absolutePath)
                if (r != "ok") throw RuntimeException(r)
                val good = when (kind) { "kws", "asr", "pocket" -> ready(app, kind); else -> voiceReady(app, id) }
                if (!good) throw RuntimeException("The download did not contain the expected files.")
                guard(app).edit().putBoolean(if (kind == "voice") "tts" else kind, false).commit()
                if (kind == "voice") synchronized(this) { releaseTts() }
                msg = "Done."
            } catch (e: Throwable) {
                msg = if (e.message == "Cancelled") "Cancelled." else "Failed: " + (e.message ?: e.toString()).take(160)
            } finally {
                tar.delete()
                pct = -1
                busy = ""
                busyId = ""
            }
        }.start()
    }

    fun remove(ctx: Context, kind: String, id: String) {
        when (kind) {
            "kws" -> modelDir(ctx, "kws").deleteRecursively()
            "asr" -> modelDir(ctx, "asr").deleteRecursively()
            "pocket" -> { synchronized(this) { releasePocket() }; modelDir(ctx, "pocket").deleteRecursively() }
            "voice" -> { if (VOICES.any { it.id == id }) voiceDir(ctx, id).deleteRecursively(); synchronized(this) { releaseTts() } }
        }
        guard(ctx).edit().putBoolean(if (kind == "voice") "tts" else kind, false).commit()
    }

    // ------------------------------------------------------------ Piper text to speech

    private var tts: OfflineTts? = null
    private var ttsId = ""

    private fun releaseTts() {
        try { tts?.release() } catch (e: Throwable) {}
        tts = null; ttsId = ""
    }

    fun synth(ctx: Context, text: String): ByteArray =
        if (Voice.engine(ctx) == "pocket") synthPocket(ctx, text) else synthPiper(ctx, text)

    // ------------------------------------------------------------ Pocket-TTS (Kyutai, 100M, runs on sherpa-onnx)

    private var pTts: OfflineTts? = null
    private var pRef: FloatArray? = null
    private var pRefSr = 0

    private fun releasePocket() {
        try { pTts?.release() } catch (e: Throwable) {}
        pTts = null; pRef = null
    }

    /** Reads a 16-bit PCM wav into mono floats. */
    private fun readWav(f: File): Pair<FloatArray, Int> {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var ch = 1
        var sr = 16000
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") { ch = bb.getShort(pos + 10).toInt(); sr = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") {
                if (bits != 16) throw RuntimeException("Reference voice must be 16-bit wav")
                val end = minOf(pos + 8 + len, b.size)
                val frames = (end - pos - 8) / (2 * ch)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += bb.getShort(pos + 8 + (i * ch + c) * 2) / 32768f
                    out[i] = s / ch
                }
                return Pair(out, sr)
            }
            pos += 8 + len + (len and 1)
        }
        throw RuntimeException("Reference voice wav has no audio")
    }

    private fun customRef(ctx: Context) = File(root(ctx), "pocket_custom.wav")

    fun pocketVoicesJson(ctx: Context): String {
        val a = JSONArray()
        val ws = File(modelDir(ctx, "pocket"), "test_wavs").listFiles { x -> x.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()
        for (f in ws) a.put(JSONObject().put("id", f.name).put("label", "Built-in: " + f.name.removeSuffix(".wav")))
        if (customRef(ctx).exists()) a.put(JSONObject().put("id", "custom").put("label", "My voice (from your file)"))
        return a.toString()
    }

    fun removeCustomPocket(ctx: Context) { customRef(ctx).delete(); synchronized(this) { pRef = null; pRefKey = "" } }

    /** Decodes any audio file (mp3, m4a, wav, ogg...) into a short mono reference clip for Pocket-TTS. */
    fun saveCustomPocket(ctx: Context, uri: Uri): String {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var idx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = f; break }
        }
        if (idx < 0 || fmt == null) throw RuntimeException("That file has no audio")
        ex.selectTrack(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 24000
        var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val out = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    val b = codec.getOutputBuffer(oi)!!
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val sb = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val c = if (ch < 1) 1 else ch
                    while (sb.remaining() >= c) {
                        var s = 0f
                        for (k in 0 until c) s += sb.get() / 32768f
                        out.add(s / c)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (out.size > sr * 20) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            try { codec.stop() } catch (e: Throwable) {}
            try { codec.release() } catch (e: Throwable) {}
            ex.release()
        }
        if (out.size < sr * 2) throw RuntimeException("Clip is too short. Use 5 to 15 seconds of one clear voice.")
        val keep = minOf(out.size, sr * 15)
        val arr = FloatArray(keep) { out[it] }
        customRef(ctx).writeBytes(wav(arr, sr))
        synchronized(this) { pRef = null; pRefKey = "" }
        return "ok"
    }

    private var pRefKey = ""

    @Synchronized
    fun synthPocket(ctx: Context, text: String): ByteArray {
        if (!ready(ctx, "pocket")) throw RuntimeException("Pocket-TTS is not downloaded yet (Settings > Voice)")
        val d = modelDir(ctx, "pocket")
        if (pTts == null) {
            pTts = guarded(ctx, "pocket") {
                OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            pocket = OfflineTtsPocketModelConfig(
                                lmFlow = File(d, "lm_flow.int8.onnx").absolutePath,
                                lmMain = File(d, "lm_main.int8.onnx").absolutePath,
                                encoder = File(d, "encoder.onnx").absolutePath,
                                decoder = File(d, "decoder.int8.onnx").absolutePath,
                                textConditioner = File(d, "text_conditioner.onnx").absolutePath,
                                vocabJson = File(d, "vocab.json").absolutePath,
                                tokenScoresJson = File(d, "token_scores.json").absolutePath,
                            ),
                            numThreads = 3,
                            provider = "cpu",
                        )
                    )
                )
            }
        }
        val p = Voice.cfg(ctx).optJSONObject("pocket") ?: JSONObject()
        val want = p.optString("voice", "")
        val key = if (want == "custom" && customRef(ctx).exists()) "custom" else want
        if (pRef == null || pRefKey != key) {
            val ref = if (key == "custom") customRef(ctx)
                else (File(d, "test_wavs").listFiles { x -> x.name == want }?.firstOrNull()
                    ?: File(d, "test_wavs").listFiles { x -> x.name.endsWith(".wav") }?.sortedBy { it.name }?.firstOrNull()
                    ?: throw RuntimeException("Pocket-TTS download has no reference voice"))
            val (s, sr) = readWav(ref)
            pRef = s; pRefSr = sr; pRefKey = key
        }
        val sp = p.optDouble("speed", 1.0).toFloat().coerceIn(0.6f, 1.6f)
        val a = pTts!!.generateWithConfig(text, GenerationConfig(speed = sp, referenceAudio = pRef, referenceSampleRate = pRefSr))
        return wav(a.samples, a.sampleRate)
    }

    @Synchronized
    fun synthPiper(ctx: Context, text: String): ByteArray {
        val p = Voice.cfg(ctx).optJSONObject("piper") ?: JSONObject()
        val id = p.optString("voice", "en_US-amy-medium")
        val d = voiceDir(ctx, id)
        val onnx = d.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
        if (onnx == null || !File(d, "tokens.txt").exists()) throw RuntimeException("Piper voice is not downloaded yet (Settings > Voice)")
        if (tts == null || ttsId != id) {
            releaseTts()
            tts = guarded(ctx, "tts") {
                OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = onnx.absolutePath,
                                tokens = File(d, "tokens.txt").absolutePath,
                                dataDir = File(d, "espeak-ng-data").absolutePath,
                            ),
                            numThreads = 2,
                            provider = "cpu",
                        )
                    )
                )
            }
            ttsId = id
        }
        val sp = p.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f)
        val a = tts!!.generate(text, 0, sp)
        return wav(a.samples, a.sampleRate)
    }

    private fun wav(s: FloatArray, sr: Int): ByteArray {
        val n = s.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (x in s) bb.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return bb.array()
    }
}

/**
 * One microphone loop for "Hey Nana" and for dictation. The wake spotter runs while idle; when a listen
 * request comes in, the same audio goes to the streaming recognizer until you stop talking.
 */
object Ears {
    interface Host {
        fun onWake(keyword: String, rest: String)
        fun speaking(): Boolean
        fun state(s: String)
        fun heard(text: String)
    }

    private class Req(val onPartial: (String) -> Unit, val onFinal: (String) -> Unit, val onFail: (String) -> Unit)

    @Volatile private var th: Thread? = null
    @Volatile private var wakeOn = false
    @Volatile private var paused = false
    @Volatile private var req: Req? = null
    @Volatile private var key = ""
    @Volatile private var host: Host? = null
    @Volatile private var app: Context? = null

    fun listening(): Boolean = req != null

    @Synchronized fun ensureWake(ctx: Context, h: Host) {
        app = ctx.applicationContext; host = h
        wakeOn = true
        key = Speech.wakeKey(ctx)
        start()
    }

    @Synchronized fun stopWake() { wakeOn = false }

    fun pause(p: Boolean) { paused = p }

    @Synchronized fun listen(ctx: Context, h: Host, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onFail: (String) -> Unit) {
        app = ctx.applicationContext; host = h
        req = Req(onPartial, onFinal, onFail)
        start()
    }

    fun cancelListen() { req = null }

    @Synchronized fun stopAll() { wakeOn = false; req = null }

    @Synchronized private fun start() {
        if (th != null) return
        val t = Thread({ loop() }, "nana-ears")
        t.isDaemon = true
        th = t
        t.start()
    }

    private fun openRec(): AudioRecord? {
        return try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null }
            r
        } catch (e: Throwable) { null }
    }

    private fun closeRec(r: AudioRecord?) {
        try { r?.stop() } catch (e: Throwable) {}
        try { r?.release() } catch (e: Throwable) {}
    }

    private fun loop() {
        val ctx = app ?: run { th = null; return }
        var rec: AudioRecord? = null
        var kws: KeywordSpotter? = null
        var ks: OnlineStream? = null
        var kKey = ""
        var wst: OnlineStream? = null
        var wText = ""
        var wChange = 0L
        var asr: OnlineRecognizer? = null
        var ast: OnlineStream? = null
        var aText = ""
        var aStart = 0L
        var aHeard = false
        var asrIdle = 0L
        var zero = 0
        var fails = 0
        var cool = 0L
        var lastScreen = 0L
        var screenOn = true
        val buf = ShortArray(1600)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            while (true) {
                val r = req
                val wake = wakeOn
                if (!wake && r == null) {
                    var quit = false
                    synchronized(this) { if (!wakeOn && req == null) { th = null; quit = true } }
                    if (quit) break
                }
                val h = host
                val now = System.currentTimeMillis()
                if (now - lastScreen > 1000) { screenOn = pm.isInteractive; lastScreen = now }

                if (asr != null && r == null && ast == null && wst == null && now - asrIdle > 45000) { try { asr.release() } catch (e: Throwable) {}; asr = null }

                val allowWake = wake && !paused && (screenOn || Speech.screenOffOk(ctx))
                if (paused || (r == null && !allowWake)) {
                    closeRec(rec); rec = null
                    if (r == null) h?.state(if (paused) "paused" else "screen-off")
                    Thread.sleep(250)
                    continue
                }

                if (r != null) {
                    if (wst != null) { try { wst.release() } catch (e: Throwable) {}; wst = null }
                    if (asr == null) {
                        h?.state("loading")
                        try { asr = Speech.guarded(ctx, "asr") { OnlineRecognizer(config = Speech.asrConfig(ctx)) } }
                        catch (e: Throwable) { req = null; r.onFail("model: " + (e.message ?: "")); continue }
                    }
                    if (ast == null) { ast = asr!!.createStream(); aText = ""; aStart = now; aHeard = false }
                } else {
                    if (ast != null) { try { ast.release() } catch (e: Throwable) {}; ast = null }
                    val useKws = Speech.kwsOk(ctx)
                    val useAsr = Speech.asrWake(ctx)
                    if (!useKws && kws != null) {
                        try { ks?.release() } catch (e: Throwable) {}
                        try { kws.release() } catch (e: Throwable) {}
                        kws = null; ks = null
                    }
                    if (useKws && (kws == null || kKey != key)) {
                        h?.state("loading")
                        try { ks?.release() } catch (e: Throwable) {}
                        try { kws?.release() } catch (e: Throwable) {}
                        kws = null; ks = null
                        try {
                            val k = Speech.guarded(ctx, "kws") { KeywordSpotter(config = Speech.kwsConfig(ctx)) }
                            kws = k; ks = k.createStream(); kKey = key
                        } catch (e: Throwable) {
                            if (!useAsr) { h?.state("error:" + (e.message ?: "model").take(60)); Thread.sleep(5000); continue }
                        }
                    }
                    if (useAsr) {
                        if (asr == null) {
                            h?.state("loading")
                            try { asr = Speech.guarded(ctx, "asr") { OnlineRecognizer(config = Speech.asrConfig(ctx)) } }
                            catch (e: Throwable) { if (kws == null) { h?.state("error:" + (e.message ?: "model").take(60)); Thread.sleep(5000); continue } }
                        }
                        if (asr != null && wst == null) { wst = asr.createStream(); wText = ""; wChange = now }
                    } else if (wst != null) { try { wst.release() } catch (e: Throwable) {}; wst = null }
                    if (kws == null && wst == null) { h?.state("error:no-wake-model"); Thread.sleep(2000); continue }
                }

                if (rec == null) {
                    rec = openRec()
                    if (rec == null) {
                        h?.state("error:mic")
                        if (r != null) { req = null; r.onFail("mic") }
                        Thread.sleep(2000); continue
                    }
                }

                val n = rec!!.read(buf, 0, buf.size)
                if (n <= 0) {
                    fails++
                    if (fails > 30) { closeRec(rec); rec = null; fails = 0 }
                    Thread.sleep(20); continue
                }
                fails = 0
                var peak = 0
                val f = FloatArray(n)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                    f[i] = v / 32768f
                }
                if (peak == 0) zero++ else zero = 0
                if (zero > 40) { h?.state("mic-blocked"); continue }
                if (h != null && h.speaking()) { aStart = now; continue }

                if (r != null) {
                    val a = ast!!
                    val m = asr!!
                    a.acceptWaveform(f, 16000)
                    while (m.isReady(a)) m.decode(a)
                    val t = m.getResult(a).text.trim()
                    if (t.isNotEmpty() && t != aText) { aText = t; aHeard = true; r.onPartial(t) }
                    val end = m.isEndpoint(a)
                    val timeout = now - aStart > 25000
                    val silent = !aHeard && now - aStart > 6000
                    if (end || timeout || silent) {
                        req = null
                        try { a.release() } catch (e: Throwable) {}
                        ast = null
                        asrIdle = now
                        cool = now + 1500
                        if (aText.isBlank()) r.onFail(if (silent) "silence" else "empty") else r.onFinal(aText)
                        val k = kws; val s = ks
                        if (k != null && s != null) k.reset(s)
                    }
                } else {
                    val k = kws
                    val s = ks
                    if (k != null && s != null) {
                        s.acceptWaveform(f, 16000)
                        while (k.isReady(s)) {
                            k.decode(s)
                            val res = k.getResult(s)
                            if (res.keyword.isNotEmpty()) {
                                k.reset(s)
                                if (now > cool) {
                                    cool = now + 2000
                                    wst?.let { w -> asr?.reset(w) }; wText = ""
                                    h?.onWake(res.keyword, "")
                                }
                            }
                        }
                    }
                    val w = wst
                    val m2 = asr
                    if (w != null && m2 != null) {
                        w.acceptWaveform(f, 16000)
                        while (m2.isReady(w)) m2.decode(w)
                        val t = m2.getResult(w).text.trim()
                        if (t != wText) { wText = t; wChange = now }
                        val end = m2.isEndpoint(w)
                        val rest = if (t.isNotEmpty()) Speech.wakeMatch(t) else null
                        if (rest != null && (end || now - wChange > 700) && now > cool) {
                            cool = now + 2000
                            m2.reset(w); wText = ""
                            if (k != null && s != null) k.reset(s)
                            h?.heard(t)
                            h?.onWake("nana", rest)
                        } else if (end) {
                            if (t.isNotEmpty()) h?.heard(t)
                            m2.reset(w); wText = ""
                        } else if (t.length > 70 || (t.isNotEmpty() && now - wChange > 6000)) {
                            m2.reset(w); wText = ""
                        }
                    }
                    if (zero == 0) h?.state("listening-local")
                }
            }
        } catch (e: Throwable) {
            host?.state("error:" + (e.message ?: e.javaClass.simpleName).take(60))
            val r = req
            req = null
            r?.onFail?.invoke("error")
            synchronized(this) { th = null }
        } finally {
            closeRec(rec)
            try { wst?.release() } catch (e: Throwable) {}
            try { ast?.release() } catch (e: Throwable) {}
            try { ks?.release() } catch (e: Throwable) {}
            try { kws?.release() } catch (e: Throwable) {}
            try { asr?.release() } catch (e: Throwable) {}
        }
    }
}
