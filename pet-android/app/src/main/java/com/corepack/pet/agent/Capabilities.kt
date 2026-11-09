package com.corepack.pet.agent

/** Groups of things the pet may do. The user switches each on or off; the model only sees tools that are on. */
enum class Capability(val label: String, val blurb: String) {
    REMINDERS("Reminders", "Set reminders; the pet speaks up when they are due"),
    TASKS_NOTES("Tasks and notes", "A to-do list and a notebook kept on this phone"),
    PHONE_ACTIONS("Phone actions", "Open apps, set alarms and timers, maps, links, flashlight, draft texts (you press send or call)"),
    LOOKUPS("Weather and facts", "Looks up weather (Open-Meteo) and summaries (Wikipedia); the question is sent to those sites"),
    NUDGES("Nudges", "The pet speaks up by itself, for example when the battery is low"),
    SCREEN("Screen eye", "Lets the pet read what is on your screen when you ask. Read-only; needs the Accessibility switch in Android Settings"),
    REMOTE_TOOLS("Online tools", "Tools that run on your PC through the gateway, such as fetching web pages. What they return comes from outside, so the pet treats it with suspicion");

    companion object {
        /** What a new install starts with. Online tools stay off until you switch them on, even after allowing tools on the PC. */
        val defaults: Set<Capability> = entries.toSet() - REMOTE_TOOLS - SCREEN

        fun parse(stored: String?): Set<Capability> {
            if (stored == null) return defaults
            return stored.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.toSet()
        }

        fun serialize(set: Set<Capability>): String = set.joinToString(",") { it.name }
    }
}
