package com.sahil.chibi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCoreTest {
    @Test fun findsEveryToolBlock() {
        val t = "Sure. <tool>{\"name\":\"a\"}</tool> and then <tool>\n{\"name\":\"b\"}\n</tool>"
        assertEquals(listOf("{\"name\":\"a\"}", "{\"name\":\"b\"}"), ToolCore.tagBodies(t))
    }

    @Test fun noBlocksMeansEmpty() {
        assertTrue(ToolCore.tagBodies("just chatting").isEmpty())
    }

    @Test fun levenshteinBasics() {
        assertEquals(0, ToolCore.levenshtein("abc", "abc"))
        assertEquals(1, ToolCore.levenshtein("screen_tap", "screen_tp"))
        assertEquals(3, ToolCore.levenshtein("", "abc"))
    }

    @Test fun suggestsNearNames() {
        val known = listOf("launch_app", "screen_tap_text", "web_search", "device_status")
        assertEquals(listOf("launch_app"), ToolCore.suggest("lanch_app", known))
        assertTrue(ToolCore.suggest("zzzzzzzz", known).isEmpty())
    }

    @Test fun missingRequiredArgumentIsReported() {
        val p = listOf(ParamSpec("name", "string", required = true))
        val msg = ToolCore.checkArgs(p, emptyMap())
        assertTrue(msg!!.contains("\"name\""))
        assertNull(ToolCore.checkArgs(p, mapOf("name" to "YouTube")))
    }

    @Test fun blankRequiredStringCountsAsMissing() {
        val p = listOf(ParamSpec("text", "string", required = true))
        assertTrue(ToolCore.checkArgs(p, mapOf("text" to "   ")) != null)
    }

    @Test fun intAndBoolAcceptNumericAndTextForms() {
        val p = listOf(ParamSpec("seconds", "int"), ParamSpec("on", "bool"))
        assertNull(ToolCore.checkArgs(p, mapOf("seconds" to 30, "on" to true)))
        assertNull(ToolCore.checkArgs(p, mapOf("seconds" to "30", "on" to "false")))
        assertTrue(ToolCore.checkArgs(p, mapOf("seconds" to "soon")) != null)
    }

    @Test fun enumValuesAreChecked() {
        val p = listOf(ParamSpec("direction", "string", enums = listOf("up", "down")))
        assertNull(ToolCore.checkArgs(p, mapOf("direction" to "UP")))
        assertTrue(ToolCore.checkArgs(p, mapOf("direction" to "left")) != null)
    }
}
