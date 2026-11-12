package com.sahil.chibi

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One tool Nana can run. Built-in tools are registered by Agent; others can be added or removed at runtime. */
class ToolDef(
    val name: String,
    val desc: String,
    val params: List<ParamSpec>,
    val action: Boolean,
    val readOnly: Boolean,
    val ui: Boolean,
    val screen: Boolean,
    val run: (Context, JSONObject) -> String
)

/** Registry of tools: register, look up, list and search. Thread-safe. */
object ToolRegistry {
    private val tools = ConcurrentHashMap<String, ToolDef>()

    fun register(t: ToolDef) { tools[t.name] = t }

    fun unregister(name: String): Boolean = tools.remove(name) != null

    fun get(name: String): ToolDef? = tools[name]

    fun names(): Set<String> = tools.keys.toSortedSet()

    fun all(): List<ToolDef> = tools.values.sortedBy { it.name }

    /** Tools whose name or description contains the query. */
    fun find(query: String): List<ToolDef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter { it.name.contains(q) || it.desc.lowercase().contains(q) }
    }
}
