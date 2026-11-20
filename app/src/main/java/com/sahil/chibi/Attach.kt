package com.sahil.chibi

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** A file the user attached to a chat message (copied into the app cache). */
class Att(val file: File, val name: String, val mime: String, val size: Long) {
    var thumb: Bitmap? = null
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** Attachments for the chat: copy in, turn into text the language model can use (Mirage describes pictures). */
object Attach {
    const val MARK = "\n\n⟦attached"
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val MAX_TEXT = 8000
    private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "toml",
        "py", "js", "ts", "kt", "java", "c", "cpp", "h", "sh", "sql", "css", "srt", "tex", "gradle", "properties")

    fun dir(ctx: Context): File = File(ctx.cacheDir, "att").apply { mkdirs() }

    private fun trim(dir: File) {
        val old = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return if (ext in TEXT_EXT) "text/plain" else "application/octet-stream"
    }

    /** Copies a content Uri into the cache. Null when it is unreadable or bigger than 25 MB. */
    fun copyIn(ctx: Context, uri: Uri): Att? {
        var name = "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {}
        if (size > MAX_BYTES) return null
        var mime = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null } ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream") mime = guessMime(name)
        val d = dir(ctx)
        trim(d)
        val f = File(d, System.currentTimeMillis().toString() + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80))
        try {
            val ins = ctx.contentResolver.openInputStream(uri) ?: return null
            var total = 0L
            ins.use { i ->
                f.outputStream().use { o ->
                    val b = ByteArray(65536)
                    while (true) {
                        val r = i.read(b); if (r <= 0) break
                        total += r
                        if (total > MAX_BYTES) { f.delete(); return null }
                        o.write(b, 0, r)
                    }
                }
            }
        } catch (e: Exception) { f.delete(); return null }
        return Att(f, name, mime, f.length())
    }

    /** Decodes with a size limit and applies the EXIF rotation (camera photos). */
    fun decode(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val deg = try {
            when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        } catch (e: Exception) { 0 }
        if (deg == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    fun thumb(f: File): Bitmap? {
        val b = decode(f, 320) ?: return null
        val s = minOf(b.width, b.height)
        return try { Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s) } catch (e: Exception) { b }
    }

    fun decodeUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0) return null
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        } catch (e: Exception) { null }
    }

    fun putExtras(i: Intent, atts: List<Att>): Intent {
        i.putExtra("paths", atts.map { it.file.path }.toTypedArray())
        i.putExtra("names", atts.map { it.name }.toTypedArray())
        i.putExtra("mimes", atts.map { it.mime }.toTypedArray())
        return i
    }

    fun fromIntent(ctx: Context, i: Intent): List<Att> {
        val p = i.getStringArrayExtra("paths") ?: return emptyList()
        val n = i.getStringArrayExtra("names")
        val m = i.getStringArrayExtra("mimes")
        val root = dir(ctx).canonicalPath
        val out = ArrayList<Att>()
        for (k in p.indices) {
            val f = File(p[k])
            if (!f.exists() || !f.canonicalPath.startsWith(root)) continue
            out.add(Att(f, n?.getOrNull(k) ?: f.name, m?.getOrNull(k) ?: guessMime(f.name), f.length()))
        }
        return out
    }

    private fun fmt(n: Long): String = if (n >= 1_048_576) "%.1f MB".format(n / 1_048_576.0) else if (n >= 1024) "%d KB".format(n / 1024) else "$n B"

    private fun isText(a: Att): Boolean =
        a.mime.startsWith("text/") || a.mime.contains("json") || a.mime.contains("xml") ||
            a.name.substringAfterLast('.', "").lowercase() in TEXT_EXT

    private fun describeImage(ctx: Context, a: Att): String {
        if (!Mirage.ready(ctx)) return "(The on-phone vision model is not downloaded, so this picture cannot be looked at. Tell the user to download it in Settings > Voice > Vision.)"
        return try {
            val b = decode(a.file, 1792) ?: return "(This picture could not be opened.)"
            Mirage.describe(ctx, b)
        } catch (t: Throwable) { "(Vision failed: " + (t.message ?: t.toString()).take(160) + ")" }
    }

    /** The text that goes to the language model: the caption plus what is inside each attachment. Slow (vision), run off the UI thread. */
    fun augment(ctx: Context, caption: String, atts: List<Att>): String {
        val sb = StringBuilder(caption.trim())
        sb.append(MARK).append(' ').append(atts.size).append(if (atts.size == 1) " item" else " items")
            .append(" - what the user sent with the message. They will probably ask follow-up questions about it, so remember it. Answer naturally and do not mention these markers⟧")
        for (a in atts) {
            sb.append("\n\n")
            when {
                a.isImage -> sb.append("[IMAGE ").append(a.name).append("]\n").append(describeImage(ctx, a))
                isText(a) -> {
                    val txt = try {
                        a.file.bufferedReader().use { r -> val buf = CharArray(MAX_TEXT); val n = r.read(buf); if (n > 0) String(buf, 0, n) else "" }
                    } catch (e: Exception) { "" }
                    sb.append("[FILE ").append(a.name).append(" (").append(fmt(a.size)).append(")]\n").append(txt)
                    if (a.size > txt.length * 2L && a.size > MAX_TEXT) sb.append("\n...(file continues, only the start is shown)")
                }
                else -> sb.append("[FILE ").append(a.name).append(", ").append(a.mime).append(", ").append(fmt(a.size)).append("] (this kind of file cannot be read here, only its name and size are known)")
            }
        }
        return sb.toString()
    }

    /** Background-thread work: small square previews for the chip and the bubble. */
    fun loadThumbs(atts: List<Att>) { for (a in atts) if (a.isImage && a.thumb == null) a.thumb = thumb(a.file) }

    /**
     * When the newest user message has no attachment but one was sent a few messages ago, returns what it contained,
     * so a follow-up such as "what does it say?" still has its subject.
     */
    fun followUp(history: List<Msg>): String? {
        if (history.isEmpty() || history.last().content.contains(MARK)) return null
        val from = maxOf(0, history.size - 14)
        for (k in history.size - 2 downTo from) {
            val m = history[k]
            if (m.role == "user" && m.content.contains(MARK)) {
                return "(Context for you: the user's new message follows the attachment they sent earlier in this chat. When they say it, this, that, the picture, the file, they mean it. What it contained:\n" +
                    m.content.substringAfter(MARK).take(4000) + ")"
            }
        }
        return null
    }

    /** What the bubble shows for a stored message (the model-only part is hidden). */
    fun shown(content: String): String {
        if (!content.contains(MARK)) return content
        val cap = content.substringBefore(MARK).trim()
        return if (cap.isEmpty()) "📎 attachment" else cap + "\n📎 attachment"
    }
}
