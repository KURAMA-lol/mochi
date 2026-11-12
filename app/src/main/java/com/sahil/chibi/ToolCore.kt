package com.sahil.chibi

/** One argument of a tool. type is string, int or bool. enums lists the allowed values when given. */
class ParamSpec(val name: String, val type: String, val required: Boolean = false, val enums: List<String> = emptyList())

/** Result of one tool run. status: OK, ERROR, INVALID, OFF, BLOCKED, DENIED, REPEATED, SKIPPED. */
class ToolOutcome(val status: String, val text: String)

/** Pure helpers for tool calls: finding <tool> blocks, checking arguments, suggesting names. No Android types, so JVM tests can run them. */
object ToolCore {
    private val TAG = Regex("""<tool>\s*([\s\S]*?)\s*</tool>""")

    /** The body of every complete <tool>...</tool> block in the reply, in order. */
    fun tagBodies(text: String): List<String> = TAG.findAll(text).map { it.groupValues[1] }.toList()

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Closest known tool names to a mistyped one (at most 3). */
    fun suggest(name: String, known: Collection<String>): List<String> {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return emptyList()
        val limit = maxOf(2, n.length / 3)
        return known.map { it to levenshtein(n, it) }
            .filter { it.second <= limit }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
    }

    /** Checks the arguments against the tool's parameters. Returns a message for the model, or null when they are fine. */
    fun checkArgs(params: List<ParamSpec>, args: Map<String, Any?>): String? {
        for (p in params) {
            val v = args[p.name]
            val blank = v == null || (v is String && v.isBlank())
            if (blank) {
                if (p.required) return "Missing required argument \"" + p.name + "\" (" + p.type + "). Call the tool again with it filled in."
                continue
            }
            val ok = when (p.type) {
                "int" -> v is Number || (v is String && v.trim().toIntOrNull() != null)
                "bool" -> v is Boolean || (v is String && (v == "true" || v == "false"))
                else -> true
            }
            if (!ok) return "Argument \"" + p.name + "\" must be a " + p.type + "."
            if (p.enums.isNotEmpty() && p.enums.none { it.equals(v.toString(), ignoreCase = true) }) {
                return "Argument \"" + p.name + "\" must be one of: " + p.enums.joinToString(", ") + "."
            }
        }
        return null
    }
}
