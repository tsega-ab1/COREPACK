package com.corepack.pet.agent

import java.time.ZonedDateTime

/** Something the pet decides to say on its own. */
data class Nudge(val kind: String, val text: String)

/** One reason for the pet to speak up, and how long to wait before saying the same kind of thing again. */
data class NudgeCandidate(val kind: String, val text: String, val cooldownMs: Long)

/** Somewhere nudges come from (the battery, a reminder coming up, ...). The engine decides which ones may be said. */
fun interface NudgeSource {
    fun candidates(): List<NudgeCandidate>
}

/** The nudges about the battery, most urgent first. */
object BatteryNudges {
    fun candidates(percent: Int, charging: Boolean): List<NudgeCandidate> {
        if (charging || percent < 0) return emptyList()
        val out = ArrayList<NudgeCandidate>()
        if (percent <= 5) out.add(NudgeCandidate("battery_critical", "Only $percent% battery left. Please plug in soon!", 30 * 60_000L))
        if (percent <= 15) out.add(NudgeCandidate("battery_low", "Your battery is at $percent%. Time to plug in?", 2 * 3_600_000L))
        return out
    }
}

/**
 * Decides when the pet may speak up unprompted. It stays quiet at night, at most [maxPerDay] times a day,
 * and never repeats the same kind of nudge too soon. Pure: the caller feeds it facts and a clock.
 */
class ProactiveEngine(
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val maxPerDay: Int = 4,
) {
    private val lastAt = HashMap<String, Long>()
    private var dayKey = -1
    private var sentToday = 0

    private fun allowed(kind: String, cooldownMs: Long): Boolean {
        val now = clock()
        val hour = now.hour
        if (hour >= 22 || hour < 7) return false
        val day = now.year * 1000 + now.dayOfYear
        if (day != dayKey) { dayKey = day; sentToday = 0 }
        if (sentToday >= maxPerDay) return false
        val last = lastAt[kind]
        return last == null || now.toInstant().toEpochMilli() - last >= cooldownMs
    }

    private fun sent(kind: String): Boolean {
        lastAt[kind] = clock().toInstant().toEpochMilli()
        sentToday++
        return true
    }

    /**
     * Says the first of these that is allowed: not at night, not over the daily limit, and not the same kind of thing
     * as one said recently. Returns null if none may be said now.
     */
    fun offer(candidates: List<NudgeCandidate>): Nudge? {
        for (c in candidates) {
            if (allowed(c.kind, c.cooldownMs) && sent(c.kind)) return Nudge(c.kind, c.text)
        }
        return null
    }

    /** Asks every source and says the first allowed nudge. Sources are asked in order, most important first. */
    fun check(sources: List<NudgeSource>): Nudge? = offer(sources.flatMap { it.candidates() })

    /** Call whenever the battery level changes. */
    fun onBattery(percent: Int, charging: Boolean): Nudge? = offer(BatteryNudges.candidates(percent, charging))
}
