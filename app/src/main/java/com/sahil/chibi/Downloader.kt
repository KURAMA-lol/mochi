package com.sahil.chibi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Saves a file from a link into Downloads/Nana (no storage permission needed). Run off the main thread. */
object Downloader {
    private const val MAX = 100L * 1024 * 1024

    private fun copy(i: InputStream, o: OutputStream): Long {
        val buf = ByteArray(32768); var n = 0L
        while (true) {
            val r = i.read(buf); if (r < 0) break
            n += r
            if (n > MAX) throw RuntimeException("File is bigger than 100 MB.")
            o.write(buf, 0, r)
        }
        return n
    }

    fun fetch(ctx: Context, link: String, wanted: String?): String {
        val u = link.trim()
        if (!u.startsWith("http")) return "Give a full http(s) link."
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 30000; cn.instanceFollowRedirects = true
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Nana")
        val code = cn.responseCode
        if (code >= 400) return "Server answered HTTP $code."
        if (cn.contentLengthLong > MAX) return "File is bigger than 100 MB."
        var fn = (wanted ?: "").trim()
        if (fn.isEmpty()) {
            val cd = cn.getHeaderField("Content-Disposition") ?: ""
            fn = Regex("filename=\"?([^\";]+)").find(cd)?.groupValues?.get(1) ?: u.substringBefore('?').trimEnd('/').substringAfterLast('/')
        }
        fn = fn.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifEmpty { "download" }
        val mime = (cn.contentType ?: "application/octet-stream").substringBefore(';').trim()
        val n: Long
        val where: String
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fn)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nana")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return "Could not create the file in Downloads."
            try {
                n = ctx.contentResolver.openOutputStream(uri)!!.use { o -> cn.inputStream.use { i -> copy(i, o) } }
            } catch (e: Exception) { try { ctx.contentResolver.delete(uri, null, null) } catch (x: Exception) {}; throw e }
            where = "Downloads/Nana/" + fn
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            val f = File(dir, fn)
            try { n = f.outputStream().use { o -> cn.inputStream.use { i -> copy(i, o) } } } catch (e: Exception) { f.delete(); throw e }
            where = f.absolutePath
        }
        return "Saved " + (n / 1024) + " KB to " + where
    }
}
