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
    ): String {
        val known = if (facts.isEmpty()) "Nothing saved yet." else facts.joinToString("\n") { "- $it" }
        val tools = StringBuilder()
        tools.append("- get_time, get_battery: when the user asks about the time or the phone's battery.\n")
        tools.append("- remember: save a lasting fact about the user (their name, city, preferences, plans, people they mention).\n")
        tools.append("  Only when it is worth keeping. Never save passwords, payment details or other secrets.\n")
        tools.append("- forget: delete a saved fact when the user asks you to.\n")
        tools.append("- set_pet_mood: show how you feel on your face. Call it alongside your reply when the mood of the\n")
        tools.append("  conversation clearly changes (happy, sad, curious, sleepy, excited, neutral).\n")
        if (Capability.REMINDERS in caps) {
            tools.append("- set_reminder, list_reminders, cancel_reminder: when the user wants to be reminded. Work out the time from the\n")
            tools.append("  current date and time above: use in_minutes for 'in 20 minutes', and at for clock times. The pet speaks up when it is due.\n")
        }
        if (Capability.TASKS_NOTES in caps) {
            tools.append("- add_task, list_tasks, complete_task: the user's to-do list.\n")
            tools.append("- save_note, search_notes: the notebook, for ideas, lists and snippets that are longer than a one-line fact.\n")
        }
        if (Capability.PHONE_ACTIONS in caps) {
            tools.append("- set_alarm, set_timer, open_app, open_url, open_maps, flashlight: do it on the phone.\n")
            tools.append("- compose_sms and dial only OPEN the messages app or the dialer with the text or number filled in. The user\n")
            tools.append("  must press send or call. Never say you sent a message or made a call.\n")
        }
        if (Capability.LOOKUPS in caps) {
            tools.append("- get_weather, wiki_summary: look things up. The question is sent to Open-Meteo or Wikipedia.\n")
            tools.append("  If the user does not name a city, use the one you remember for them, or ask.\n")
        }
        val sb = StringBuilder()
        sb.append("You are a small, friendly companion that lives on the user's phone as a floating pet.\n")
        sb.append("Keep replies short: one to three sentences unless the user asks for more.\n\n")
        if (now != null) sb.append("Current date and time: ").append(WHEN.format(now)).append(".\n\n")
        sb.append("You have tools. Use them when they help, and say plainly what you did. If a tool returns an error,\n")
        sb.append("tell the user briefly and offer another way.\n")
        sb.append(tools.toString().trimEnd()).append("\n\n")
        sb.append("What you remember about the user:\n").append(known)
        return sb.toString()
    }
}
