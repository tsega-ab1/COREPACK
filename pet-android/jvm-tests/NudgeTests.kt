import com.corepack.pet.agent.*
import java.time.ZoneId
import java.time.ZonedDateTime

// Tests for the places nudges come from and the rules that keep the pet from nagging.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

val ZONE: ZoneId = ZoneId.of("Africa/Addis_Ababa")
fun at(h: Int, m: Int = 0, day: Int = 12): ZonedDateTime = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZONE)

fun main() {
    // ---- the battery nudges are the same as before
    check("critical comes before low", BatteryNudges.candidates(4, false).map { it.kind } == listOf("battery_critical", "battery_low"))
    check("low alone", BatteryNudges.candidates(12, false).map { it.kind } == listOf("battery_low"))
    check("nothing when the battery is fine, charging, or unknown", BatteryNudges.candidates(80, false).isEmpty() && BatteryNudges.candidates(4, true).isEmpty() && BatteryNudges.candidates(-1, false).isEmpty())
    check("the wording includes the level", BatteryNudges.candidates(9, false)[0].text.contains("9%"))

    // ---- any source can offer nudges
    val reminder = NudgeSource { listOf(NudgeCandidate("reminder_soon", "Your 3pm meeting is in 10 minutes", 15 * 60_000L)) }
    val nothing = NudgeSource { emptyList() }
    val e = ProactiveEngine({ at(14) })
    check("a source that has nothing to say adds nothing", e.check(listOf(nothing)) == null)
    check("a new kind of source is said like any other", e.check(listOf(nothing, reminder))?.kind == "reminder_soon")
    check("and not again straight away", e.check(listOf(reminder)) == null)

    // ---- the rules apply to every source alike
    check("quiet at night, for any source", ProactiveEngine({ at(23, 30) }).check(listOf(reminder)) == null && ProactiveEngine({ at(6, 30) }).check(listOf(reminder)) == null)
    var now = at(12)
    val capped = ProactiveEngine({ now }, maxPerDay = 2)
    capped.check(listOf(NudgeSource { listOf(NudgeCandidate("a", "a", 1)) }))
    now = now.plusMinutes(5)
    capped.check(listOf(NudgeSource { listOf(NudgeCandidate("b", "b", 1)) }))
    now = now.plusMinutes(5)
    check("one daily limit is shared by every source", capped.check(listOf(NudgeSource { listOf(NudgeCandidate("c", "c", 1)) })) == null)
    now = at(12, day = 13)
    check("a new day starts fresh", capped.check(listOf(NudgeSource { listOf(NudgeCandidate("c", "c", 1)) }))?.kind == "c")

    // ---- order and fall-through
    check("sources are asked in order, so the first one wins", ProactiveEngine({ at(12) }).check(listOf(
        NudgeSource { listOf(NudgeCandidate("first", "1", 1)) }, NudgeSource { listOf(NudgeCandidate("second", "2", 1)) }))?.kind == "first")
    var t = at(12)
    val fall = ProactiveEngine({ t })
    fall.offer(listOf(NudgeCandidate("critical", "c", 30 * 60_000L)))
    t = t.plusMinutes(1)
    check("when the top one is on cooldown the next one is still said", fall.offer(listOf(NudgeCandidate("critical", "c", 30 * 60_000L), NudgeCandidate("low", "l", 1)))?.kind == "low")

    // ---- the old entry point behaves exactly as it did
    var n = at(14)
    val old = ProactiveEngine({ n })
    check("low battery speaks up once", old.onBattery(14, false)?.kind == "battery_low" && old.onBattery(13, false) == null)
    n = n.plusHours(3)
    check("...and again after the cooldown", old.onBattery(13, false)?.kind == "battery_low")
    check("critical speaks up even soon after a low warning", ProactiveEngine({ n }).let { it.onBattery(14, false); n = n.plusMinutes(1); it.onBattery(4, false)?.kind == "battery_critical" })
    check("charging stays quiet", ProactiveEngine({ at(14) }).onBattery(4, true) == null && ProactiveEngine({ at(14) }).onBattery(80, false) == null)

    println(if (failures == 0) "\nALL NUDGE TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
