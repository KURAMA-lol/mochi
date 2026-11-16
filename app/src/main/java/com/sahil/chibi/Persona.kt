package com.sahil.chibi

import kotlin.random.Random

object Persona {
    val MOODS = setOf("happy", "excited", "shy", "sad", "angry", "sleepy", "smug", "neutral")

    fun system(cfg: AppConfig): String =
        cfg.persona.prompt
            .replace("{name}", cfg.persona.name)
            .replace("{user}", cfg.persona.userName)
            .let { if (Voice.cuesOn) it + "\n\nYour replies are spoken aloud. Now and then (not in every message) you may include a natural reaction such as haha, hehe, *giggles*, *sighs* or *coughs*; the app plays these as real sounds." else it }

    private val IDLE = listOf(
        "neutral" to "Hmm... what are you up to?",
        "happy" to "I'm right here if you need me~",
        "excited" to "Ooh, let's do something fun!",
        "happy" to "Padhai ho gayi kya? Thoda break le lo!",
        "sleepy" to "Aaaah... a little sleepy today.",
        "smug" to "I saw you open that app again~",
        "shy" to "Eh? Were you looking at me?",
        "neutral" to "Drink some water, okay?",
        "excited" to "Tap me! Let's chat!",
        "happy" to "Nana is on duty~"
    )

    private val POKE = listOf(
        "angry" to "Hey! Stop poking me!",
        "smug" to "Hehe, you keep poking me, huh?",
        "happy" to "Okay okay, one more is fine.",
        "excited" to "Kyaa! That surprised me!",
        "shy" to "N-no... that tickles."
    )

    fun idleLine(): Pair<String, String> = IDLE[Random.nextInt(IDLE.size)]
    fun pokeLine(): Pair<String, String> = POKE[Random.nextInt(POKE.size)]
}
