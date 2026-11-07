package com.corepack.pet.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Prompt {
    private val WHEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm (VV)", Locale.ENGLISH)

    fun system(
        facts: List<String>,
        now: ZonedDateTime? = null,
        caps: Set<Capability> = Capability.defaults,
        toolHints: String = BuiltinHints.forCaps(caps),
    ): String {
        val known = if (facts.isEmpty()) "Nothing saved yet." else facts.joinToString("\n") { "- $it" }
        val sb = StringBuilder()
        sb.append("You are a small, friendly companion that lives on the user's phone as a floating pet.\n")
        sb.append("Keep replies short: one to three sentences unless the user asks for more.\n\n")
        if (now != null) sb.append("Current date and time: ").append(WHEN.format(now)).append(".\n\n")
        sb.append("You have tools. Use them when they help, and say plainly what you did. If a tool returns an error,\n")
        sb.append("tell the user briefly and offer another way.\n")
        sb.append(toolHints.trimEnd()).append("\n\n")
        sb.append("What you remember about the user:\n").append(known)
        return sb.toString()
    }
}
