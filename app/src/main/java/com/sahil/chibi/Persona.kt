package com.sahil.chibi

import kotlin.random.Random

object Persona {
    val MOODS = setOf("happy", "excited", "shy", "sad", "angry", "sleepy", "smug", "neutral")

    fun system(cfg: AppConfig): String =
        cfg.persona.prompt
            .replace("{name}", cfg.persona.name)
            .replace("{user}", cfg.persona.userName)

    private val IDLE = listOf(
        "neutral" to "...sus.",
        "smug" to "Vent check. Nobody saw that.",
        "excited" to "Emergency meeting! ...just kidding. Hi!",
        "happy" to "Task complete: wandering around your phone.",
        "neutral" to "Who is the impostor? Not me. Probably.",
        "sleepy" to "Crewmates need sleep too, bro.",
        "happy" to "Padhai ho gayi kya? Thoda break le lo!",
        "smug" to "I saw you open that app again. Sus.",
        "shy" to "Bro... were you staring at me?",
        "neutral" to "Drink some water, crewmate.",
        "excited" to "Tap me! I'm definitely not the impostor!"
    )

    private val POKE = listOf(
        "angry" to "Hey! Stop poking the crewmate!",
        "smug" to "Why so sus? You're the one poking me.",
        "happy" to "Hehe, okay, one more is fine.",
        "excited" to "AAH! Emergency meeting!",
        "shy" to "B-bro... that tickles."
    )

    fun idleLine(): Pair<String, String> = IDLE[Random.nextInt(IDLE.size)]
    fun pokeLine(): Pair<String, String> = POKE[Random.nextInt(POKE.size)]
}
