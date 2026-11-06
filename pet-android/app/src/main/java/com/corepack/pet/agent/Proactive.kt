package com.corepack.pet.agent

import java.time.ZonedDateTime

/** Something the pet decides to say on its own. */
data class Nudge(val kind: String, val text: String)

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

    /** Call whenever the battery level changes. */
    fun onBattery(percent: Int, charging: Boolean): Nudge? {
        if (charging || percent < 0) return null
        if (percent <= 5 && allowed("battery_critical", 30 * 60_000L) && sent("battery_critical")) {
            return Nudge("battery_critical", "Only $percent% battery left. Please plug in soon!")
        }
        if (percent <= 15 && allowed("battery_low", 2 * 3_600_000L) && sent("battery_low")) {
            return Nudge("battery_low", "Your battery is at $percent%. Time to plug in?")
        }
        return null
    }
}
