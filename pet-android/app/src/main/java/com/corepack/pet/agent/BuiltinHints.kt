package com.corepack.pet.agent

/**
 * The prompt lines that describe the built-in modules to the model. Each module points at one of these, so
 * the wording lives in one place. A module written later carries its own [ToolModule.promptHint] instead.
 */
object BuiltinHints {
    val CORE: String = lines(
        "- get_time, get_battery: when the user asks about the time or the phone's battery.",
        "- remember: save a lasting fact about the user (their name, city, preferences, plans, people they mention).",
        "  Only when it is worth keeping. Never save passwords, payment details or other secrets.",
        "- forget: delete a saved fact when the user asks you to.",
        "- set_pet_mood: show how you feel on your face. Call it alongside your reply when the mood of the",
        "  conversation clearly changes (happy, sad, curious, sleepy, excited, neutral).",
    )

    val REMINDERS: String = lines(
        "- set_reminder, list_reminders, cancel_reminder: when the user wants to be reminded. Work out the time from the",
        "  current date and time above: use in_minutes for 'in 20 minutes', and at for clock times. The pet speaks up when it is due.",
    )

    val TASKS_NOTES: String = lines(
        "- add_task, list_tasks, complete_task: the user's to-do list.",
        "- save_note, search_notes: the notebook, for ideas, lists and snippets that are longer than a one-line fact.",
    )

    val PHONE_ACTIONS: String = lines(
        "- set_alarm, set_timer, open_app, open_url, open_maps, flashlight: do it on the phone.",
        "- compose_sms and dial only OPEN the messages app or the dialer with the text or number filled in. The user",
        "  must press send or call. Never say you sent a message or made a call.",
    )

    val LOOKUPS: String = lines(
        "- get_weather, wiki_summary: look things up. The question is sent to Open-Meteo or Wikipedia.",
        "  If the user does not name a city, use the one you remember for them, or ask.",
    )

    private fun lines(vararg text: String): String = text.joinToString("") { it + "\n" }

    /** The hints for the built-in modules that are switched on, in prompt order. Used when no registry is given. */
    fun forCaps(caps: Set<Capability>): String {
        val sb = StringBuilder(CORE)
        if (Capability.REMINDERS in caps) sb.append(REMINDERS)
        if (Capability.TASKS_NOTES in caps) sb.append(TASKS_NOTES)
        if (Capability.PHONE_ACTIONS in caps) sb.append(PHONE_ACTIONS)
        if (Capability.LOOKUPS in caps) sb.append(LOOKUPS)
        return sb.toString()
    }
}
