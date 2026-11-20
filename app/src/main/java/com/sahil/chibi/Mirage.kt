package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Mirage (Cactus Compute, Apache-2.0): a 51M-parameter vision model that runs on the phone. One pass gives a scene
 * description, objects with boxes, text (OCR, Latin script), relations and more. It runs in the Cactus engine through
 * libmirage_jni.so, which CI builds from app/src/main/cpp. Nana uses it only when the user asks her to look.
 */
object Mirage {
    private const val REV = "76c1e9e53381aca8fc3843c636216c96ff220122"
    const val URL_MODEL = "https://huggingface.co/Cactus-Compute/mirage/resolve/" + REV + "/mirage.cact"
    const val SHA256 = "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237"
    private const val MAX_SIDE = 896
    private const val MAX_CHARS = 4000

    private external fun nativeLoad(path: String): ByteArray
    private external fun nativeDescribe(rgb: ByteArray, w: Int, h: Int, options: String, language: String): ByteArray
    private external fun nativeThreads(n: Int)

    private var libTried = false
    private var libErr = ""
    private var loaded = false

    fun file(ctx: Context): File = File(File(File(ctx.filesDir, "speech"), "mirage"), "mirage.cact")

    fun ready(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 20_000_000 }

    @Synchronized
    fun release() { loaded = false }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(65536)
            while (true) { val r = i.read(b); if (r <= 0) break; md.update(b, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (!libTried) {
            libTried = true
            try { System.loadLibrary("mirage_jni") } catch (t: Throwable) { libErr = t.message ?: t.toString() }
        }
        if (libErr.isNotEmpty()) throw RuntimeException("the vision engine did not load: " + libErr.take(200))
        if (loaded) return
        if (!ready(ctx)) throw RuntimeException("the vision model is not downloaded (Settings > Voice > Vision)")
        var err: Throwable? = null
        val r = Speech.guarded(ctx, "mirage") {
            try {
                nativeThreads(0)
                String(nativeLoad(file(ctx).absolutePath), Charsets.UTF_8)
            } catch (t: Throwable) { err = t; null }
        }
        if (r == null) throw RuntimeException("the vision model did not load: " + (err?.message ?: err?.toString() ?: "unknown").take(200))
        if (r.startsWith("ERR:")) throw RuntimeException(r.removePrefix("ERR:").trim().take(200))
        loaded = true
    }

    private val HEAVY = Regex("mask|polygon|outline|bitmap|segment|contour", RegexOption.IGNORE_CASE)

    /** Drops the bulky pixel data (masks and outlines) so the answer is small enough for a language model. */
    private fun strip(x: Any?): Any? = when (x) {
        is JSONObject -> JSONObject().also { o ->
            val it = x.keys()
            while (it.hasNext()) { val k = it.next(); if (!HEAVY.containsMatchIn(k)) o.put(k, strip(x.opt(k))) }
        }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(strip(x.opt(i))) }
        else -> x
    }

    /** Describes a picture. Returns compact JSON text for the model. */
    @Synchronized
    fun describe(ctx: Context, src: Bitmap): String {
        ensure(ctx)
        val sc = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height).toFloat())
        val bmp = if (sc < 1f) Bitmap.createScaledBitmap(src, (src.width * sc).toInt().coerceAtLeast(1), (src.height * sc).toInt().coerceAtLeast(1), true) else src
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val p = px[i]
            rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte()
        }
        val opts = JSONObject().put("max_objects", 24).put("max_text_lines", 20).toString()
        var err: Throwable? = null
        val raw = Speech.guarded(ctx, "mirage") {
            try { String(nativeDescribe(rgb, w, h, opts, "en"), Charsets.UTF_8) } catch (t: Throwable) { err = t; null }
        }
        if (raw == null) throw RuntimeException("vision failed: " + (err?.message ?: err?.toString() ?: "unknown").take(200))
        if (raw.startsWith("ERR:")) throw RuntimeException(raw.removePrefix("ERR:").trim().take(200))
        val text = try { strip(JSONObject(raw)).toString() } catch (e: Exception) { raw }
        val body = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + " ...(cut)" else text
        return "VISION (on-phone Mirage model). Boxes are [x1,y1,x2,y2] on a 0-1000 scale. Text reading can be wrong, say so if unsure.\n" + body
    }

    /** Screenshot of what is on the phone now, described. Needs the Accessibility service. */
    fun lookScreen(ctx: Context): String {
        if (!ready(ctx)) return "Could not look: the vision model is not downloaded (Settings > Voice > Vision)."
        val acc = NanaAccess.inst ?: return "Could not look: screen control is OFF. Turn Nana on in Accessibility settings."
        val bmp = acc.screenshot() ?: return "Could not take a screenshot (needs Android 11 or newer and Nana's accessibility service switched off and on once after this update; some apps block screenshots)."
        return describe(ctx, bmp)
    }

    /** Picture from a web link, described. */
    fun lookUrl(ctx: Context, url: String): String {
        if (!ready(ctx)) return "Could not look: the vision model is not downloaded (Settings > Voice > Vision)."
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return "Could not look: give a full http or https link to an image."
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val data: ByteArray
        try {
            if (c.responseCode !in 200..299) return "Could not look: the link answered HTTP " + c.responseCode
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { i ->
                val b = ByteArray(65536)
                while (true) {
                    val r = i.read(b); if (r <= 0) break
                    out.write(b, 0, r)
                    if (out.size() > 12_000_000) return "Could not look: the image is bigger than 12 MB."
                }
            }
            data = out.toByteArray()
        } finally { c.disconnect() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return "Could not look: that link is not an image I can open."
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > MAX_SIDE * 2) s *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return "Could not look: that link is not an image I can open."
        return describe(ctx, bmp)
    }
}
