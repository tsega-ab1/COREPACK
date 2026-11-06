package com.corepack.pet.agent

/** Groups of things the pet may do. The user switches each on or off; the model only sees tools that are on. */
enum class Capability(val label: String, val blurb: String) {
    REMINDERS("Reminders", "Set reminders; the pet speaks up when they are due"),
    TASKS_NOTES("Tasks and notes", "A to-do list and a notebook kept on this phone"),
    PHONE_ACTIONS("Phone actions", "Open apps, set alarms and timers, maps, links, flashlight, draft texts (you press send or call)"),
    LOOKUPS("Weather and facts", "Looks up weather (Open-Meteo) and summaries (Wikipedia); the question is sent to those sites"),
    NUDGES("Nudges", "The pet speaks up by itself, for example when the battery is low");

    companion object {
        val defaults: Set<Capability> = entries.toSet()

        fun parse(stored: String?): Set<Capability> {
            if (stored == null) return defaults
            return stored.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.toSet()
        }

        fun serialize(set: Set<Capability>): String = set.joinToString(",") { it.name }
    }
}
