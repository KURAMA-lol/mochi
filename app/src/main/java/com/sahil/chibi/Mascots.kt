package com.sahil.chibi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Named mascots made in Pixel Studio, stored as one JSON file each in filesDir/mascots. */
object Mascots {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "mascots").also { it.mkdirs() }

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (s.isEmpty()) "mascot" else s.take(40)
    }

    fun list(ctx: Context): JSONArray {
        val out = JSONArray()
        val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                out.put(JSONObject()
                    .put("slug", f.name.removeSuffix(".json"))
                    .put("name", o.optString("name", f.name))
                    .put("type", o.optString("type", "pixel"))
                    .put("thumb", o.optString("f1", o.optString("art", ""))))
            } catch (e: Throwable) { }
        }
        return out
    }

    fun get(ctx: Context, slug: String): String? {
        if (slug(slug) != slug) return null
        val f = File(dir(ctx), "$slug.json")
        return if (f.exists()) f.readText() else null
    }

    fun save(ctx: Context, json: String): String {
        val o = JSONObject(json)
        val s = slug(o.optString("name", "Mascot"))
        o.put("slug", s)
        File(dir(ctx), "$s.json").writeText(o.toString())
        return s
    }

    fun delete(ctx: Context, slug: String) {
        if (slug(slug) != slug) return
        File(dir(ctx), "$slug.json").delete()
    }

    fun bitmap(dataUrl: String?): Bitmap? {
        if (dataUrl.isNullOrEmpty()) return null
        val i = dataUrl.indexOf(',')
        if (i < 0) return null
        return try {
            val b = Base64.decode(dataUrl.substring(i + 1), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Throwable) { null }
    }
}
