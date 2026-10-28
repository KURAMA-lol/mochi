package com.sahil.chibi

import kotlin.random.Random

object Persona {
    val MOODS = setOf("happy", "excited", "shy", "sad", "angry", "sleepy", "smug", "neutral")

    fun system(cfg: AppConfig): String =
        cfg.persona.prompt
            .replace("{name}", cfg.persona.name)
            .replace("{user}", cfg.persona.userName)

    private val IDLE = listOf(
        "happy" to "Hehe, just wandering around your phone~",
        "shy" to "U-um... were you looking at me?",
        "smug" to "You've been scrolling for a while, you know.",
        "neutral" to "Drink some water, okay?",
        "sleepy" to "*yawn* ...is it study time yet?",
        "excited" to "Ooh! Tap me, tap me!",
        "happy" to "Padhai ho gayi kya? Thoda break le lo!",
        "smug" to "I could answer that faster than your WiFi.",
        "shy" to "D-don't stare, I'm just walking!",
        "happy" to "I found a pixel. It's mine now."
    )

    private val POKE = listOf(
        "shy" to "H-hey! Don't pat me like that...!",
        "angry" to "Hmph! I'm not a toy!",
        "happy" to "Hehe... okay, one more pat is fine.",
        "excited" to "Eep! You startled me!"
    )

    fun idleLine(): Pair<String, String> = IDLE[Random.nextInt(IDLE.size)]
    fun pokeLine(): Pair<String, String> = POKE[Random.nextInt(POKE.size)]
}
