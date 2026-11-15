package com.sahil.chibi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineTts
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
        else -> false
    }

    fun wakeLocal(ctx: Context): Boolean = ears(ctx).optString("wake", "local") == "local" && ready(ctx, "kws") && !guard(ctx).getBoolean("kws", false)
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
        for (k in listOf("tts", "kws", "asr")) if (g.getBoolean(k, false)) dis.put(k)
        return JSONObject().put("busy", busy.isNotEmpty()).put("busyKind", busy).put("msg", msg).put("pct", pct)
            .put("kws", ready(ctx, "kws")).put("asr", ready(ctx, "asr")).put("voices", installed).put("disabled", dis)
    }

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
                        o.write(b, 0, r)
                        n += r
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 400) {
                            lastUi = now
                            pct = if (total > 0) (n * 100 / total).toInt() else -1
                            msg = "Downloading $label: " + (n / 1000000) + (if (total > 0) " / " + (total / 1000000) else "") + " MB"
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
            "voice" -> {
                val v = VOICES.firstOrNull { it.id == id } ?: return
                url = BASE + "tts-models/vits-piper-" + v.id + "-int8.tar.bz2"
                dest = voiceDir(app, v.id); label = v.id
            }
            else -> return
        }
        busy = kind; msg = "Starting..."; pct = 0
        Thread {
            val tar = File(app.cacheDir, "dl_" + System.nanoTime() + ".tar.bz2")
            try {
                download(url, tar, label)
                msg = "Unpacking $label..."; pct = -1
                val r = PyRuntime.unpack(app, tar.absolutePath, dest.absolutePath)
                if (r != "ok") throw RuntimeException(r)
                val good = when (kind) { "kws" -> ready(app, "kws"); "asr" -> ready(app, "asr"); else -> voiceReady(app, id) }
                if (!good) throw RuntimeException("The download did not contain the expected files.")
                guard(app).edit().putBoolean(if (kind == "voice") "tts" else kind, false).commit()
                if (kind == "voice") synchronized(this) { releaseTts() }
                msg = "Done."
            } catch (e: Throwable) {
                msg = "Failed: " + (e.message ?: e.toString()).take(160)
            } finally {
                tar.delete()
                pct = -1
                busy = ""
            }
        }.start()
    }

    fun remove(ctx: Context, kind: String, id: String) {
        when (kind) {
            "kws" -> modelDir(ctx, "kws").deleteRecursively()
            "asr" -> modelDir(ctx, "asr").deleteRecursively()
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

    @Synchronized
    fun synth(ctx: Context, text: String): ByteArray {
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
        fun onWake(keyword: String)
        fun speaking(): Boolean
        fun state(s: String)
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

                if (asr != null && r == null && ast == null && now - asrIdle > 45000) { try { asr.release() } catch (e: Throwable) {}; asr = null }

                val allowWake = wake && !paused && (screenOn || Speech.screenOffOk(ctx))
                if (paused || (r == null && !allowWake)) {
                    closeRec(rec); rec = null
                    if (r == null) h?.state(if (paused) "paused" else "screen-off")
                    Thread.sleep(250)
                    continue
                }

                if (r != null) {
                    if (asr == null) {
                        h?.state("loading")
                        try { asr = Speech.guarded(ctx, "asr") { OnlineRecognizer(config = Speech.asrConfig(ctx)) } }
                        catch (e: Throwable) { req = null; r.onFail("model: " + (e.message ?: "")); continue }
                    }
                    if (ast == null) { ast = asr!!.createStream(); aText = ""; aStart = now; aHeard = false }
                } else {
                    if (ast != null) { try { ast.release() } catch (e: Throwable) {}; ast = null }
                    if (kws == null || kKey != key) {
                        h?.state("loading")
                        try { ks?.release() } catch (e: Throwable) {}
                        try { kws?.release() } catch (e: Throwable) {}
                        kws = null; ks = null
                        try {
                            val k = Speech.guarded(ctx, "kws") { KeywordSpotter(config = Speech.kwsConfig(ctx)) }
                            kws = k; ks = k.createStream(); kKey = key
                        } catch (e: Throwable) {
                            h?.state("error:" + (e.message ?: "model").take(60)); Thread.sleep(5000); continue
                        }
                    }
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
                    val k = kws!!
                    val s = ks!!
                    s.acceptWaveform(f, 16000)
                    while (k.isReady(s)) {
                        k.decode(s)
                        val res = k.getResult(s)
                        if (res.keyword.isNotEmpty()) {
                            k.reset(s)
                            if (now > cool) { cool = now + 2000; h?.onWake(res.keyword) }
                        }
                    }
                    if (zero == 0) h?.state("listening-local")
                }
            }
        } catch (e: Throwable) {
            host?.state("error:" + (e.message ?: e.javaClass.simpleName).take(60))
            val r = req
            req = null
            r?.onFail("error")
            synchronized(this) { th = null }
        } finally {
            closeRec(rec)
            try { ast?.release() } catch (e: Throwable) {}
            try { ks?.release() } catch (e: Throwable) {}
            try { kws?.release() } catch (e: Throwable) {}
            try { asr?.release() } catch (e: Throwable) {}
        }
    }
}
