package com.sahil.chibi

import android.content.Context
import java.io.File

/** A small terminal for Nana: one command at a time, in her own scratch folder, only after the user taps Allow. */
object Shell {
    private val BLOCK = Regex("shared_prefs|databases|/data/data|/data/user|\\.\\./|\\s\\.\\.(\\s|$)|/proc/self|agent_cfg|nana_mail|bloub", RegexOption.IGNORE_CASE)

    fun home(c: Context): File = File(c.filesDir, "shell").apply { mkdirs() }

    fun run(c: Context, command: String, timeout: Int): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return "No command given. Example: ls -la"
        if (cmd.length > 2000) return "Not run: the command is too long."
        if (BLOCK.containsMatchIn(cmd)) return "Blocked: that command reaches the app's private data or leaves the scratch folder."
        val ap = Agent.approver ?: return "Not run: the Allow card is not available. Start Nana first."
        if (!ap("Run in terminal:\n" + cmd.take(300))) {
            AuditLog.add(c, "DENIED: terminal " + cmd.take(120))
            return "Not run: the user denied it."
        }
        AuditLog.add(c, "APPROVED: terminal " + cmd.take(120))
        val dir = home(c)
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(dir).redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", dir.absolutePath); put("TMPDIR", dir.absolutePath); put("PATH", "/system/bin:/system/xbin")
            }
            val p = pb.start()
            try { p.outputStream.close() } catch (e: Exception) {}
            val sb = StringBuffer()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (sb.length < 8000) sb.append(buf, 0, n)
                        }
                    }
                } catch (e: Exception) {}
            }
            reader.start()
            val ms = timeout.coerceIn(1, 60) * 1000L
            val end = System.currentTimeMillis() + ms
            var code: Int? = null
            while (System.currentTimeMillis() < end) {
                try { code = p.exitValue(); break } catch (e: IllegalThreadStateException) { Thread.sleep(100) }
            }
            if (code == null) {
                p.destroy()
                reader.join(500)
                return "Stopped after " + (ms / 1000) + " s. Output so far:\n" + sb.toString().take(4000)
            }
            reader.join(1000)
            "exit " + code + "\n" + sb.toString().take(6000).ifBlank { "(no output)" }
        } catch (e: Throwable) { "Could not run: " + (e.message ?: e.toString()) }
    }
}
