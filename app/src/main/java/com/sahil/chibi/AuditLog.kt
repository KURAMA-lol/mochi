package com.sahil.chibi

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What Nana did on the phone and in the browser. Shown in Settings so nothing happens unseen. */
object AuditLog {
    private fun file(c: Context) = File(c.filesDir, "audit.log")

    @Synchronized fun add(c: Context, line: String) {
        try {
            val f = file(c)
            val t = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(Date())
            val old = if (f.exists()) f.readLines() else emptyList()
            val all = (old + (t + "  " + line.replace('\n', ' ').take(220))).takeLast(150)
            f.writeText(all.joinToString("\n"))
        } catch (e: Exception) {}
    }

    @Synchronized fun json(c: Context): String {
        return try {
            val f = file(c)
            val a = JSONArray()
            if (f.exists()) f.readLines().takeLast(60).reversed().forEach { a.put(it) }
            a.toString()
        } catch (e: Exception) { "[]" }
    }

    @Synchronized fun clear(c: Context) { try { file(c).delete() } catch (e: Exception) {} }
}
