package com.sahil.chibi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * tiny-voca (n4ze3m/tiny-voca, Apache-2.0): a 4.5M-parameter English voice, 24 kHz, two voices (F2 female, M2 male).
 * The model is one ONNX file run with onnxruntime-android. Text becomes phonemes with the dizione package
 * (pure Python, run by Chaquopy, see nana_tinyvoca.py), one sentence at a time, and the audio is joined.
 */
object TinyVoca {
    private const val REV = "1e73262226dbf4ef02e9df277e81aaa1db5ce16c"
    const val HF = "https://huggingface.co/n4ze3m/tiny-voca/resolve/" + REV + "/"
    private const val RATE = 24000
    private const val PEAK = 0.99f

    /** remote path to local path inside the model folder */
    val FILES = listOf(
        "onnx/model.onnx" to "model.onnx",
        "config.json" to "config.json",
        "LICENSE" to "LICENSE",
        "phonemizer/lexicon.tsv" to "phonemizer/lexicon.tsv",
        "phonemizer/align.json" to "phonemizer/align.json",
        "phonemizer/g2p.json" to "phonemizer/g2p.json",
        "phonemizer/g2p.npz" to "phonemizer/g2p.npz",
        "phonemizer/homographs.json" to "phonemizer/homographs.json",
        "phonemizer/LICENSE.cmudict" to "phonemizer/LICENSE.cmudict",
        "phonemizer/LICENSE.homograph-data" to "phonemizer/LICENSE.homograph-data",
    )
    private val NEED = listOf("model.onnx", "phonemizer/lexicon.tsv", "phonemizer/align.json", "phonemizer/g2p.json", "phonemizer/g2p.npz", "phonemizer/homographs.json")

    private var env: OrtEnvironment? = null
    private var sess: OrtSession? = null
    private var pyOk = false

    fun dir(ctx: Context): File = File(File(ctx.filesDir, "speech"), "tinyvoca")

    fun ready(ctx: Context): Boolean = NEED.all { File(dir(ctx), it).let { f -> f.exists() && f.length() > 0 } } && File(dir(ctx), "model.onnx").length() > 15_000_000

    @Synchronized
    fun release() {
        try { sess?.close() } catch (e: Throwable) {}
        sess = null
        pyOk = false
    }

    private fun copyAssets(ctx: Context, from: String, to: File) {
        val kids = ctx.assets.list(from) ?: emptyArray()
        if (kids.isEmpty()) {
            to.parentFile?.mkdirs()
            ctx.assets.open(from).use { i -> to.outputStream().use { o -> i.copyTo(o) } }
            return
        }
        to.mkdirs()
        for (k in kids) copyAssets(ctx, "$from/$k", File(to, k))
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        if (sess == null) {
            val d = dir(app)
            var err: Throwable? = null
            val s = Speech.guarded(app, "tiny") {
                try {
                    val e = OrtEnvironment.getEnvironment()
                    env = e
                    val o = OrtSession.SessionOptions()
                    o.setIntraOpNumThreads(3)
                    e.createSession(File(d, "model.onnx").absolutePath, o)
                } catch (t: Throwable) { err = t; null }
            }
            if (s == null) throw RuntimeException("tiny-voca model did not load: " + (err?.message ?: err?.toString() ?: "unknown").take(200))
            sess = s
        }
        if (!pyOk) {
            // dizione ships as plain files in assets (copied there by CI); Python needs a real folder to read its tables.
            val src = File(File(app.filesDir, "speech"), "tinyvoca_src")
            src.deleteRecursively()
            copyAssets(app, "tinyvoca/dizione", File(src, "dizione"))
            val r = PyRuntime.tinyLoad(app, src.absolutePath, File(dir(app), "phonemizer").absolutePath)
            if (r != "ok") throw RuntimeException("tiny-voca phonemizer: " + r.take(200))
            pyOk = true
        }
    }

    private fun silence(): ByteArray = Speech.wav(FloatArray(RATE / 8), RATE)

    /** Makes one WAV for [text]. Settings (Voice tab): voice F2/M2, speed, pitch in semitones, pitch range. */
    @Synchronized
    fun synth(ctx: Context, text: String): ByteArray {
        if (!ready(ctx)) throw RuntimeException("tiny-voca is not downloaded yet (Settings > Voice)")
        ensure(ctx)
        val c = Voice.cfg(ctx).optJSONObject("tiny") ?: JSONObject()
        val voice = if (c.optString("voice", "F2") == "M2") 1L else 0L
        val speed = c.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f)
        // 0.058 added to log F0 is about one semitone
        val shift = (c.optDouble("pitch", 0.0).coerceIn(-8.0, 8.0) * 0.058).toFloat()
        val range = c.optDouble("range", 1.0).toFloat().coerceIn(0f, 2f)
        val plan = JSONArray(PyRuntime.tinyPlan(ctx, text))
        // English only: a piece with nothing to read (other script, signs) is skipped, like the original program does
        if (plan.length() == 0) return silence()
        val e = env!!
        val s = sess!!
        val parts = ArrayList<FloatArray>()
        var total = 0
        for (i in 0 until plan.length()) {
            val u = plan.getJSONObject(i)
            val ids = u.getJSONArray("ids")
            val st = u.getJSONArray("stress")
            val n = ids.length()
            val a = LongArray(n) { ids.getLong(it) }
            val b = LongArray(n) { st.getLong(it) }
            val ts = ArrayList<OnnxTensor>()
            try {
                val tokens = OnnxTensor.createTensor(e, LongBuffer.wrap(a), longArrayOf(1, n.toLong())); ts.add(tokens)
                val stress = OnnxTensor.createTensor(e, LongBuffer.wrap(b), longArrayOf(1, n.toLong())); ts.add(stress)
                val vo = OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(voice)), longArrayOf(1)); ts.add(vo)
                val sp = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)); ts.add(sp)
                val ps = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(shift)), longArrayOf(1)); ts.add(ps)
                val pr = OnnxTensor.createTensor(e, FloatBuffer.wrap(floatArrayOf(range)), longArrayOf(1)); ts.add(pr)
                val r = s.run(mapOf("tokens" to tokens, "stress" to stress, "voice" to vo, "speed" to sp, "pitch_shift" to ps, "pitch_range" to pr))
                try {
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    val arr = FloatArray(fb.remaining())
                    fb.get(arr)
                    for (x in arr) if (x.isNaN() || x.isInfinite()) throw RuntimeException("tiny-voca made broken audio")
                    parts.add(arr); total += arr.size
                } finally { r.close() }
            } finally {
                for (t in ts) try { t.close() } catch (x: Throwable) {}
            }
        }
        val all = FloatArray(total)
        var at = 0
        var loud = 0f
        for (p in parts) { System.arraycopy(p, 0, all, at, p.size); at += p.size; for (x in p) { val m = Math.abs(x); if (m > loud) loud = m } }
        // the model sometimes peaks above 1.0, which would clip in a 16-bit file: scale the whole clip down
        if (loud > PEAK) { val k = PEAK / loud; for (j in all.indices) all[j] *= k }
        return Speech.wav(all, RATE)
    }
}
