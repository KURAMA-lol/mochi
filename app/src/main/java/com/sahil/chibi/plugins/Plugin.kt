package com.sahil.chibi.plugins

import android.content.Context
import com.sahil.chibi.ParamSpec
import org.json.JSONObject

/** One input box the Settings card shows before connecting (for example a bot token). secret = hide the text. */
class PluginField(val key: String, val label: String, val hint: String = "", val secret: Boolean = false)

/**
 * One tool a plugin gives Nana.
 * readOnly: only reads data. ask: for tools that send or create something, returns the text of the Allow card; null means no card.
 */
class PluginTool(
    val name: String,
    val desc: String,
    val params: List<ParamSpec>,
    val readOnly: Boolean,
    val ask: ((JSONObject) -> String)? = null,
    val run: (Context, JSONObject) -> String
)

/**
 * Every connected app implements this. To add a new app: write one file with an object that implements Plugin,
 * then add that object to the list in PluginManager.all. Nothing else in the app changes.
 */
interface Plugin {
    /** Short stable id, lowercase (telegram, gmail). */
    val id: String
    val name: String
    /** One emoji or letter shown on the card. */
    val icon: String
    val description: String
    /** Inputs the card shows before Connect. Empty for apps that connect through a browser sign-in. */
    val fields: List<PluginField>
    /** Hint shown under the card. */
    val help: String
    val tools: List<PluginTool>

    /** Keys of fields that already have a saved value, so the card can show Saved instead of asking again. */
    fun saved(ctx: Context): Set<String> = emptySet()

    fun isConnected(ctx: Context): Boolean
    /** Account or bot name shown next to Connected, or "". */
    fun account(ctx: Context): String

    /**
     * Starts the connection. input holds the values of fields. done(ok, message) is called once, on any thread:
     * right away for token plugins, later (after the browser sign-in) for Google plugins.
     */
    fun connect(ctx: Context, input: Map<String, String>, done: (Boolean, String) -> Unit)
    fun disconnect(ctx: Context)
}
