package com.sahil.chibi

import android.content.Context
import java.io.File

/** Nana's terminal. Shell commands run in her scratch folder; they need no card when the user's message told her to run something. Python and pip always show an Allow card. */
object Shell {
    private val BLOCK = Regex("shared_prefs|databases|/data/data|/data/user|\\.\\.|/proc/self|agent_cfg|nana_mail|bloub|dirname|realpath|readlink", RegexOption.IGNORE_CASE)
    private val RUN_REQ = Regex("\\b(run|execute|exec|terminal|shell|command|bash|python|script|pip|chala\\w*|ls|cat|grep|ping)\\b", RegexOption.IGNORE_CASE)

    fun home(c: Context): File = File(c.filesDir, "shell").apply { mkdirs() }

    /** Null when the user allowed it, else the message to give back. */
    private fun allow(c: Context, text: String): String? {
        val ap = Agent.approver ?: return "Not run: the Allow card is not available. Start Nana first."
        val tag = text.take(120).replace('\n', ' ')
        if (!ap(text)) { AuditLog.add(c, "DENIED: " + tag); return "Not run: the user denied it." }
        AuditLog.add(c, "APPROVED: " + tag)
        return null
    }

    fun run(c: Context, command: String, timeout: Int): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return "No command given. Example: ls -la"
        if (cmd.length > 2000) return "Not run: the command is too long."
        if (BLOCK.containsMatchIn(cmd)) return "Blocked: that command reaches the app's private data or leaves the scratch folder."
        if (RUN_REQ.containsMatchIn(Agent.requestText())) AuditLog.add(c, "RUN: terminal " + cmd.take(120))
        else allow(c, "Run in terminal:\n" + cmd.take(300))?.let { return it }
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

    fun py(c: Context, code: String, timeout: Int): String {
        val src = code.trim()
        if (src.isEmpty()) return "No code given."
        if (src.length > 20000) return "Not run: the code is too long."
        allow(c, "Run Python:\n" + src.take(300))?.let { return it }
        return try { PyRuntime.pyRun(c, src, timeout) } catch (e: Throwable) { "Could not run: " + (e.message ?: e.toString()).take(300) }
    }

    fun pip(c: Context, pkg: String): String {
        val name = pkg.trim()
        if (!Regex("^[A-Za-z0-9._-]{1,60}$").matches(name)) return "Give just the package name, for example: beautifulsoup4"
        allow(c, "Install Python package:\n" + name)?.let { return it }
        return try { PyRuntime.pip(c, name) } catch (e: Throwable) { "Could not install: " + (e.message ?: e.toString()).take(300) }
    }
}
