package com.sahil.chibi

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Free, offline, key-less natural voice: Kokoro via sherpa-onnx running on the phone. */
object LocalTts {
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
    @Volatile var busy = false
    @Volatile var msg = ""
    private var tts: OfflineTts? = null
    private val lock = Any()

    fun dir(ctx: Context) = File(ctx.filesDir, "kokoro")
    fun installed(ctx: Context) = File(dir(ctx), "READY").exists()
    private fun disabledFlag(ctx: Context) = File(ctx.filesDir, "kokoro_disabled")
    private fun loadingFlag(ctx: Context) = File(ctx.filesDir, "kokoro_loading")

    fun status(ctx: Context): String = JSONObject()
        .put("installed", installed(ctx)).put("busy", busy).put("msg", msg)
        .put("disabled", disabledFlag(ctx).exists()).toString()

    private class CountingStream(i: InputStream) : FilterInputStream(i) {
        @Volatile var n = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) n++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) n += r; return r }
    }

    /** Downloads and unpacks the model in one streaming pass (no temp archive on disk). */
    fun install(ctx: Context) {
        if (busy) return
        busy = true
        msg = "Starting download..."
        disabledFlag(ctx).delete()
        loadingFlag(ctx).delete()
        val app = ctx.applicationContext
        Thread {
            val d = dir(app)
            try {
                d.deleteRecursively()
                d.mkdirs()
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) throw RuntimeException("download failed: HTTP " + conn.responseCode)
                val total = conn.contentLengthLong
                val counting = CountingStream(BufferedInputStream(conn.inputStream, 1 shl 16))
                val tar = TarArchiveInputStream(BZip2CompressorInputStream(counting))
                val root = d.canonicalPath + File.separator
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    var n = e.name
                    while (n.startsWith("./")) n = n.substring(2)
                    val name = n.substringAfter('/', "")
                    if (name.isEmpty()) continue
                    val out = File(d, name)
                    if (!out.canonicalPath.startsWith(root)) continue
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    var last = 0L
                    FileOutputStream(out).use { o ->
                        while (true) {
                            val c = tar.read(buf)
                            if (c < 0) break
                            o.write(buf, 0, c)
                            if (counting.n - last > 262144) {
                                last = counting.n
                                msg = if (total > 0) "Downloading voice... " + (counting.n * 100 / total) + "%" else "Downloading voice..."
                            }
                        }
                    }
                }
                tar.close()
                val hasModel = File(d, "model.int8.onnx").exists() || File(d, "model.onnx").exists()
                if (!hasModel || !File(d, "voices.bin").exists() || !File(d, "tokens.txt").exists())
                    throw RuntimeException("downloaded package is missing files")
                File(d, "READY").writeText("1")
                msg = "Ready"
            } catch (e: Throwable) {
                d.deleteRecursively()
                msg = "Failed: " + (e.message ?: e.toString()).take(160)
            } finally {
                busy = false
            }
        }.start()
    }

    fun remove(ctx: Context) {
        synchronized(lock) {
            try { tts?.release() } catch (e: Throwable) {}
            tts = null
        }
        dir(ctx).deleteRecursively()
        disabledFlag(ctx).delete()
        loadingFlag(ctx).delete()
        msg = ""
    }

    private fun get(ctx: Context): OfflineTts {
        synchronized(lock) {
            val have = tts
            if (have != null) return have
            if (!installed(ctx)) throw RuntimeException("Local voice is not downloaded yet")
            if (disabledFlag(ctx).exists()) throw RuntimeException("Local voice crashed on this phone and was turned off")
            val lf = loadingFlag(ctx)
            if (lf.exists()) {
                disabledFlag(ctx).writeText("1")
                lf.delete()
                throw RuntimeException("Local voice crashed on this phone and was turned off")
            }
            lf.writeText("1")
            val d = dir(ctx).absolutePath
            val model = if (File(d, "model.int8.onnx").exists()) "model.int8.onnx" else "model.onnx"
            val lex = listOf("lexicon-us-en.txt", "lexicon-zh.txt").filter { File(d, it).exists() }.joinToString(",") { d + "/" + it }
            val fsts = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst").filter { File(d, it).exists() }.joinToString(",") { d + "/" + it }
            val k = OfflineTtsKokoroModelConfig(
                model = d + "/" + model,
                voices = d + "/voices.bin",
                tokens = d + "/tokens.txt",
                dataDir = d + "/espeak-ng-data",
                lexicon = lex,
                dictDir = if (File(d, "dict").exists()) d + "/dict" else ""
            )
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(kokoro = k, numThreads = 4, debug = false, provider = "cpu"),
                ruleFsts = fsts
            )
            val t = OfflineTts(config = cfg)
            lf.delete()
            tts = t
            return t
        }
    }

    fun synthWav(ctx: Context, text: String, sid: Int, speed: Float): ByteArray {
        val a = synchronized(lock) { get(ctx).generate(text, sid, speed) }
        return wav(a.samples, a.sampleRate)
    }

    private fun wav(s: FloatArray, sr: Int): ByteArray {
        val n = s.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (x in s) {
            val v = (x.coerceIn(-1f, 1f) * 32767f).toInt()
            bb.putShort(v.toShort())
        }
        return bb.array()
    }
}
