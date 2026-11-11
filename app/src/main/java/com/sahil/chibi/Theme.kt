package com.sahil.chibi

import android.content.Context

/** App-wide accent + mode (set from the settings page, read by every native overlay). */
object Theme {
    @Volatile var ac = 0xFFFF9F6E.toInt()
    @Volatile var ac2 = 0xFFFFB892.toInt()
    @Volatile var onAc = 0xFF1A0D05.toInt()
    @Volatile var bg = 0xFF0E0F12.toInt()
    @Volatile var top = 0xFF17181D.toInt()
    @Volatile var panelBg = 0xFF0E0F12.toInt()
    @Volatile var bubbleBg = 0xFFFFF4EC.toInt()
    @Volatile var bubbleText = 0xFF1A1411.toInt()
    @Volatile var light = false
    @Volatile var version = 0
    @Volatile var accentId = "peach"
    @Volatile var mode = "dark"

    private val A = mapOf(
        "peach" to intArrayOf(0xFFFF9F6E.toInt(), 0xFFFFB892.toInt(), 0xFF1A0D05.toInt()),
        "rose" to intArrayOf(0xFFFF7A9C.toInt(), 0xFFFF9DB6.toInt(), 0xFF2A0A14.toInt()),
        "violet" to intArrayOf(0xFFA78BFA.toInt(), 0xFFC4B0FF.toInt(), 0xFF12082E.toInt()),
        "sky" to intArrayOf(0xFF5BB8FF.toInt(), 0xFF8FD0FF.toInt(), 0xFF04121F.toInt()),
        "gold" to intArrayOf(0xFFF5C451.toInt(), 0xFFFFD98A.toInt(), 0xFF241A02.toInt()),
        "mono" to intArrayOf(0xFFF2F3F5.toInt(), 0xFFFFFFFF.toInt(), 0xFF0E0F12.toInt())
    )

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    private fun set(a: String, m: String) {
        val id = if (A.containsKey(a)) a else "peach"
        val md = if (m == "black" || m == "light") m else "dark"
        if (id == accentId && md == mode && version > 0) return
        val c = A[id]!!
        ac = c[0]; ac2 = c[1]; onAc = c[2]
        light = md == "light"
        when (md) {
            "black" -> { bg = 0xFF000000.toInt(); top = 0xFF0A0A0C.toInt(); panelBg = 0xFF000000.toInt() }
            "light" -> { bg = 0xFFF1F2F6.toInt(); top = 0xFFE6E8EE.toInt(); panelBg = 0xFF0E0F12.toInt() }
            else -> { bg = 0xFF0E0F12.toInt(); top = 0xFF17181D.toInt(); panelBg = 0xFF0E0F12.toInt() }
        }
        bubbleBg = if (light) 0xFFFFFFFF.toInt() else 0xFFFFF4EC.toInt()
        bubbleText = if (light) 0xFF15171B.toInt() else 0xFF1A1411.toInt()
        accentId = id; mode = md
        version++
    }

    fun load(c: Context) {
        val p = c.applicationContext.getSharedPreferences("theme", Context.MODE_PRIVATE)
        set(p.getString("accent", "peach") ?: "peach", p.getString("mode", "dark") ?: "dark")
    }

    fun save(c: Context, accent: String, mode: String) {
        c.applicationContext.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putString("accent", accent).putString("mode", mode).apply()
        set(accent, mode)
    }

    fun json(c: Context): String { load(c); return "{\"accent\":\"" + accentId + "\",\"mode\":\"" + mode + "\"}" }
}
