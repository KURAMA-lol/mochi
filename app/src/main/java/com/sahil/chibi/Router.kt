package com.sahil.chibi

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class Stat {
    @Volatile var calls = 0
    @Volatile var fails = 0
    @Volatile var ewmaMs = 0.0
    @Volatile var consecFails = 0
    @Volatile var cooldownUntil = 0L
    @Volatile var lastError = ""
}

/**
 * The API hub brain.
 *  1. Rules (first match whose target has a healthy provider) pick a preferred pool.
 *  2. Strategy orders that pool: priority | round_robin | random | weighted | fastest.
 *  3. Remaining healthy providers follow as fallback, then cooled-down ones as last resort.
 *  4. Each provider gets retries; repeated failures trip a circuit breaker (cooldown).
 */
object Router {
    val stats = ConcurrentHashMap<String, Stat>()
    private val rr = AtomicInteger(0)

    fun statOf(id: String): Stat = stats.getOrPut(id) { Stat() }
    fun resetStats() = stats.clear()

    class Plan(val order: List<Provider>, val rule: String?)
    class Result(val text: String, val provider: Provider, val ms: Long, val trace: List<String>)

    fun plan(cfg: AppConfig, text: String, voice: Boolean, advance: Boolean): Plan {
        val now = System.currentTimeMillis()
        val usable = cfg.providers.filter { it.usable() }
        val pool = usable.filter { statOf(it.id).cooldownUntil <= now }
        val cooled = usable.filter { statOf(it.id).cooldownUntil > now }

        var matched: String? = null
        var preferred: List<Provider> = emptyList()
        for (r in cfg.router.rules) {
            if (!matches(r, text, voice)) continue
            val sel = pool.filter { targetMatch(r.target, it) }
            if (sel.isEmpty()) continue
            matched = "${r.kind} ${r.value} -> ${r.target}".replace("  ", " ")
            preferred = sel
            break
        }

        val strat = cfg.router.strategy
        val ordered = ArrayList<Provider>()
        ordered.addAll(order(preferred, strat, advance))
        val rest = pool.filter { p -> preferred.none { it.id == p.id } }
        if (preferred.isEmpty() || cfg.router.fallback) ordered.addAll(order(rest, strat, advance))
        if (cfg.router.fallback || ordered.isEmpty()) ordered.addAll(cooled.sortedBy { statOf(it.id).cooldownUntil })
        return Plan(ordered, matched)
    }

    private fun matches(r: Rule, text: String, voice: Boolean): Boolean = try {
        when (r.kind) {
            "regex" -> Regex(r.value, RegexOption.IGNORE_CASE).containsMatchIn(text)
            "longer_than" -> text.length > (r.value.trim().toIntOrNull() ?: Int.MAX_VALUE)
            "shorter_than" -> text.length < (r.value.trim().toIntOrNull() ?: 0)
            "voice" -> voice
            "always" -> true
            else -> false
        }
    } catch (e: Exception) { false }

    private fun targetMatch(target: String, p: Provider): Boolean {
        val t = target.trim()
        return when {
            t.startsWith("tag:") -> p.tagList().contains(t.removePrefix("tag:").trim().lowercase())
            t.startsWith("name:") -> p.name.equals(t.removePrefix("name:").trim(), ignoreCase = true)
            else -> p.name.equals(t, ignoreCase = true)
        }
    }

    private fun order(list: List<Provider>, strat: String, advance: Boolean): List<Provider> {
        if (list.size <= 1) return list
        return when (strat) {
            "round_robin" -> {
                val s = list.sortedWith(compareBy({ it.priority }, { it.name }))
                val n = if (advance) rr.getAndIncrement() else rr.get()
                val k = Math.floorMod(n, s.size)
                s.drop(k) + s.take(k)
            }
            "random" -> list.shuffled()
            "weighted" -> weighted(list)
            "fastest" -> list.sortedBy { statOf(it.id).ewmaMs } // untried (0ms) first so they get measured
            else -> list.sortedWith(compareBy({ it.priority }, { it.name }))
        }
    }

    private fun weighted(list: List<Provider>): List<Provider> {
        val rest = list.toMutableList()
        val out = ArrayList<Provider>()
        while (rest.isNotEmpty()) {
            val total = rest.sumOf { max(1, it.weight) }
            var r = Random.nextInt(total)
            var pick = rest.last()
            for (p in rest) {
                r -= max(1, p.weight)
                if (r < 0) { pick = p; break }
            }
            rest.remove(pick)
            out.add(pick)
        }
        return out
    }

    fun chat(cfg: AppConfig, system: String, msgs: List<Msg>, voice: Boolean): Result {
        val lastUser = msgs.lastOrNull { it.role == "user" }?.content ?: ""
        val plan = plan(cfg, lastUser, voice, true)
        if (plan.order.isEmpty()) {
            throw IllegalStateException("No providers set up yet. Open the Mochi app > Providers and add one with an API key.")
        }
        val trace = ArrayList<String>()
        val attempts = 1 + cfg.router.retries.coerceIn(0, 3)
        for (p in plan.order) {
            for (a in 1..attempts) {
                val t0 = System.currentTimeMillis()
                try {
                    val text = Adapters.call(p, system, msgs)
                    if (text.isBlank()) throw RuntimeException("empty reply")
                    val ms = System.currentTimeMillis() - t0
                    onSuccess(p, ms)
                    return Result(text.trim(), p, ms, trace)
                } catch (e: Exception) {
                    val code = (e as? Http.HttpError)?.code ?: 0
                    val msg = e.message ?: e.toString()
                    trace.add("${p.name}: ${msg.take(140)}")
                    onFail(p, msg, code, cfg)
                    if (code == 400 || code == 401 || code == 403 || code == 404 || code == 422 || code == 429) break
                    if (a < attempts) Thread.sleep(400L * a)
                }
            }
        }
        throw RuntimeException("All providers failed:\n" + trace.joinToString("\n"))
    }

    private fun onSuccess(p: Provider, ms: Long) {
        val s = statOf(p.id)
        synchronized(s) {
            s.calls++
            s.consecFails = 0
            s.cooldownUntil = 0
            s.ewmaMs = if (s.ewmaMs == 0.0) ms.toDouble() else s.ewmaMs * 0.7 + ms * 0.3
        }
    }

    private fun onFail(p: Provider, msg: String, code: Int, cfg: AppConfig) {
        val s = statOf(p.id)
        synchronized(s) {
            s.calls++
            s.fails++
            s.consecFails++
            s.lastError = msg.take(160)
            val hard = code == 401 || code == 403
            val rate = code == 429
            if (hard || rate || s.consecFails >= cfg.router.failThreshold) {
                val base = cfg.router.cooldownSec.coerceAtLeast(5) * 1000L
                s.cooldownUntil = System.currentTimeMillis() + if (hard) base * 5 else base
            }
        }
    }
}
