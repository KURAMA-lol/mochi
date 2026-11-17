package com.sahil.chibi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A team of your providers. Each provider is its own agent with its own chat. They split a job between
 * themselves, message each other, and (if you allow it) ask Nana to use the phone. You can talk to the
 * whole room, to one agent, or interrupt at any time.
 */
object Team {
    class Ev(val id: Int, val from: String, val to: String, val text: String, val kind: String, val t: Long)

    private val events = ArrayList<Ev>()
    private var nextId = 1
    private var loaded = false
    private val pool = Executors.newCachedThreadPool()
    private val inbox = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val scheduled = ConcurrentHashMap<String, AtomicBoolean>()
    private val status = ConcurrentHashMap<String, String>()
    private val inflight = AtomicInteger(0)
    @Volatile private var gen = 0
    @Volatile private var turns = 0
    @Volatile private var app: Context? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("team", Context.MODE_PRIVATE)
    private fun maxTurns(ctx: Context) = prefs(ctx).getInt("max", 20).coerceIn(2, 60)
    private fun toolsOn(ctx: Context) = prefs(ctx).getBoolean("tools", false)

    private fun roster(ctx: Context): List<Pair<String, Provider>> {
        val out = ArrayList<Pair<String, Provider>>()
        val seen = HashSet<String>()
        for (p in ConfigStore.load(ctx).providers.filter { it.usable() }) {
            var n = p.name.trim().ifEmpty { "Agent" }
            var k = 2
            while (!seen.add(n.lowercase())) { n = p.name.trim() + " " + k; k++ }
            out.add(Pair(n, p))
        }
        return out
    }

    private fun leadOf(ctx: Context, ro: List<Pair<String, Provider>>): String {
        val want = prefs(ctx).getString("lead", "") ?: ""
        ro.firstOrNull { it.first == want }?.let { return it.first }
        ro.firstOrNull { e -> e.second.tagList().any { it == "lead" || it == "smart" } }?.let { return it.first }
        return ro.minByOrNull { it.second.priority }?.first ?: ""
    }

    private fun ensureLoaded(ctx: Context) {
        synchronized(events) {
            if (loaded) return
            loaded = true
            try {
                val a = JSONArray(prefs(ctx).getString("events", "[]"))
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    events.add(Ev(nextId++, o.optString("f"), o.optString("to"), o.optString("x"), o.optString("k"), o.optLong("t")))
                }
            } catch (e: Exception) {}
        }
    }

    private fun add(from: String, to: String, text: String, kind: String) {
        val ctx = app
        synchronized(events) {
            events.add(Ev(nextId++, from, to, text, kind, System.currentTimeMillis()))
            while (events.size > 300) events.removeAt(0)
            if (ctx != null) {
                val a = JSONArray()
                for (e in events.takeLast(120)) a.put(JSONObject().put("f", e.from).put("to", e.to).put("x", e.text.take(2000)).put("k", e.kind).put("t", e.t))
                prefs(ctx).edit().putString("events", a.toString()).apply()
            }
        }
    }

    fun clear(ctx: Context) {
        app = ctx.applicationContext
        stopAll(null)
        synchronized(events) { events.clear(); prefs(ctx).edit().remove("events").apply() }
        turns = 0
    }

    fun setConfig(ctx: Context, json: String) {
        val o = JSONObject(json)
        prefs(ctx).edit().putInt("max", o.optInt("max", 20)).putString("lead", o.optString("lead", "")).putBoolean("tools", o.optBoolean("tools", false)).apply()
    }

    fun state(ctx: Context, since: Int): String {
        app = ctx.applicationContext
        ensureLoaded(ctx)
        val ro = roster(ctx)
        val ag = JSONArray()
        for ((n, p) in ro) ag.put(JSONObject().put("name", n).put("model", p.model).put("status", status[n] ?: "idle"))
        val ev = JSONArray()
        synchronized(events) {
            for (e in events) if (e.id > since) ev.put(JSONObject().put("id", e.id).put("from", e.from).put("to", e.to).put("text", e.text).put("kind", e.kind).put("t", e.t))
        }
        val pr = prefs(ctx)
        return JSONObject().put("running", inflight.get() > 0).put("turns", turns).put("max", maxTurns(ctx))
            .put("lead", leadOf(ctx, ro)).put("leadPref", pr.getString("lead", "")).put("tools", toolsOn(ctx))
            .put("agents", ag).put("events", ev).toString()
    }

    fun stopAll(msg: String?) {
        gen++
        for (q in inbox.values) q.clear()
        try { if (app != null && toolsOn(app!!)) Agent.stop() } catch (e: Exception) {}
        for (k in status.keys) status[k] = "idle"
        if (msg != null) add("system", "room", msg, "system")
    }

    private fun compact(s: String) = s.replace(" ", "").lowercase()

    /** Called when you send something. target = "room" or an agent name. */
    fun post(ctx: Context, text: String, target: String) {
        app = ctx.applicationContext
        ensureLoaded(ctx)
        val t = text.trim()
        if (t.isEmpty()) return
        if (Regex("^(stop|cancel|ruk|ruko|ruk ja)\\b[\\s.!]*$", RegexOption.IGNORE_CASE).matches(t)) { stopAll("Stopped by you."); return }
        val ro = roster(ctx)
        if (ro.isEmpty()) { add("system", "room", "No providers are set up yet. Add some in AI > Providers.", "error"); return }
        val names = ro.map { it.first }
        val busy = inflight.get() > 0
        if (!busy) { turns = 0; gen++ }
        turns = 0
        val direct = names.firstOrNull { it == target }
        if (direct != null) {
            add("You", direct, t, "user")
            deliver(direct, "Sahil (private chat with you): $t")
            return
        }
        add("You", "room", t, "user")
        val mentioned = LinkedHashSet<String>()
        for (m in Regex("@([A-Za-z0-9_.\\-]+)").findAll(t)) {
            val w = m.groupValues[1].trimEnd('.', '-').lowercase()
            names.firstOrNull { compact(it) == w || compact(it).startsWith(w) && w.length >= 3 }?.let { mentioned.add(it) }
        }
        if (mentioned.isNotEmpty()) {
            for (n in mentioned) deliver(n, "Sahil (to you, in the team room): $t")
        } else {
            val lead = leadOf(ctx, ro)
            deliver(lead, if (busy) "Sahil says in between: $t" else "NEW TASK from Sahil: $t")
        }
    }

    private fun deliver(name: String, item: String) {
        inbox.getOrPut(name) { ConcurrentLinkedQueue() }.add(item)
        schedule(name)
    }

    private fun schedule(name: String) {
        val flag = scheduled.getOrPut(name) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        inflight.incrementAndGet()
        val g = gen
        pool.execute {
            try { runTurn(name, g) } catch (e: Throwable) { add(name, "room", "crashed: " + (e.message ?: e.javaClass.simpleName), "error") }
            finally {
                flag.set(false)
                status[name] = status[name]?.takeIf { it.startsWith("error") } ?: "idle"
                inflight.decrementAndGet()
                if (gen == g && inbox[name]?.isNotEmpty() == true) schedule(name)
            }
        }
    }

    private fun transcript(): String {
        val sb = StringBuilder()
        val list = synchronized(events) { events.takeLast(30).toList() }
        for (e in list) {
            if (e.kind == "error") continue
            val to = if (e.to == "room") "everyone" else e.to
            sb.append("[").append(e.from).append(" -> ").append(to).append("] ").append(e.text.take(700)).append("\n")
        }
        return sb.toString()
    }

    private fun systemFor(ctx: Context, name: String, p: Provider, ro: List<Pair<String, Provider>>, lead: String): String {
        val team = ro.joinToString("\n") { "- " + it.first + " (model " + it.second.model + (if (it.second.tagList().isNotEmpty()) ", strengths: " + it.second.tagList().joinToString("/") else "") + ")" + (if (it.first == lead) " [LEAD]" else "") }
        val tools = if (toolsOn(ctx)) "Nana is a phone assistant with real tools (read the screen, open apps, web, files). Give her a concrete job with a DO line." else "Nana's phone tools are turned OFF. Do not use DO."
        return "You are $name, one AI agent in a small team inside Sahil's phone app. Your model: ${p.model}.\n" +
            "Team:\n$team\n$tools\n" +
            "Work as a team. " + (if (name == lead) "You are the LEAD: read the task, decide who does what based on strengths, assign parts with TO lines (you may keep a part yourself), collect results, then finish with DONE. " else "Do the parts the lead or teammates give you, report back with TO or SAY, and ask a teammate when you need help. ") +
            "Decide yourselves, do not ask Sahil unless you are truly blocked. Match Sahil's language (he writes Hinglish). Be short.\n" +
            "Reply with one or more lines, each starting with exactly one of:\n" +
            "TO <Name>: a message to one teammate (assign work, ask, answer, review)\n" +
            "SAY: a short progress note or answer for Sahil\n" +
            "DO: a concrete phone job for Nana (she reports back to you)\n" +
            "DONE: the final answer for Sahil, only when the whole job is finished\n" +
            "A message may continue on the following lines. Never invent the result of a DO job; wait for Nana's report. Never type passwords, card numbers or OTPs. Do not repeat what others already said."
    }

    private fun runTurn(name: String, g: Int) {
        val ctx = app ?: return
        if (gen != g) { inbox[name]?.clear(); return }
        val q = inbox[name] ?: return
        val items = ArrayList<String>()
        while (true) { val x = q.poll() ?: break; items.add(x) }
        if (items.isEmpty()) return
        val ro = roster(ctx)
        val p = ro.firstOrNull { it.first == name }?.second ?: return
        if (turns >= maxTurns(ctx)) {
            add("system", "room", "Turn limit (" + maxTurns(ctx) + ") reached, so I paused. Send a message to continue.", "system")
            stopAll(null)
            return
        }
        turns++
        status[name] = "thinking"
        val lead = leadOf(ctx, ro)
        val user = "Team room so far:\n" + transcript() + "\nNew for you:\n" + items.joinToString("\n")
        val reply = try {
            Adapters.call(p.copy(maxTokens = maxOf(p.maxTokens, 900)), systemFor(ctx, name, p, ro, lead), listOf(Msg("user", user)))
        } catch (e: Exception) {
            val m = (e.message ?: e.toString()).take(200)
            status[name] = "error: $m"
            add(name, "room", "could not answer: $m", "error")
            if (name == lead) {
                val other = ro.firstOrNull { it.first != name }?.first
                if (other != null) {
                    prefs(ctx).edit().putString("lead", other).apply()
                    add("system", "room", "$name failed, $other is the lead now.", "system")
                    for (x in items) deliver(other, x)
                }
            } else {
                deliver(lead, "$name could not answer (" + m.take(80) + "). Please redistribute their part.")
            }
            return
        }
        if (gen != g) return
        handle(ctx, name, reply, g, ro)
    }

    private fun handle(ctx: Context, name: String, reply: String, g: Int, ro: List<Pair<String, Provider>>) {
        val names = ro.map { it.first }
        val dirs = ArrayList<Pair<String, String>>()
        for (line in reply.trim().split("\n")) {
            val m = Regex("^\\s*(TO|SAY|DO|DONE)\\b\\s*(?:<([^>]*)>)?\\s*:?\\s*(.*)$").find(line)
            if (m != null && line.trimStart().let { it.startsWith("TO") || it.startsWith("SAY") || it.startsWith("DO") }) {
                val k = m.groupValues[1]
                var rest = m.groupValues[3]
                val tgt = m.groupValues[2]
                if (k == "TO" && tgt.isNotEmpty()) rest = "$tgt: $rest"
                dirs.add(Pair(k, rest))
            } else if (dirs.isEmpty()) dirs.add(Pair("SAY", line.trim()))
            else dirs[dirs.size - 1] = Pair(dirs.last().first, dirs.last().second + "\n" + line)
        }
        for ((k, raw) in dirs) {
            if (gen != g) return
            val text = raw.trim()
            if (text.isEmpty()) continue
            when (k) {
                "TO" -> {
                    val i = text.indexOf(':')
                    val tn = if (i > 0) text.substring(0, i).trim().trim('<', '>') else ""
                    val target = names.firstOrNull { compact(it) == compact(tn) }
                    if (target == null || target == name) add(name, "you", text, "agent")
                    else {
                        val body = text.substring(i + 1).trim()
                        add(name, target, body, "agent")
                        deliver(target, "From $name: $body")
                    }
                }
                "SAY" -> add(name, "you", text, "agent")
                "DONE" -> {
                    add(name, "you", text, "final")
                    gen++
                    for (q in inbox.values) q.clear()
                }
                "DO" -> {
                    if (!toolsOn(ctx)) { add("system", name, "Phone tools are turned off for the team (Team > settings).", "system"); deliver(name, "Nana's tools are OFF. Do it without DO.") }
                    else {
                        add(name, "Nana", text, "task")
                        status[name] = "waiting for Nana"
                        val res = runNana(ctx, text)
                        if (gen != g) return
                        add("Nana", name, res, "agent")
                        deliver(name, "Nana reported: " + res.take(1500))
                    }
                }
            }
        }
    }

    private fun runNana(ctx: Context, task: String): String = try {
        Agent.run(ctx, ConfigStore.load(ctx), listOf(Msg("user", task)), false) { _ -> }.text
    } catch (e: Agent.Interrupted) {
        "(stopped)"
    } catch (e: Exception) {
        "Nana failed: " + (e.message ?: e.toString()).take(200)
    }
}
