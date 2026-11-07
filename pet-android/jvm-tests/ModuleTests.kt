import com.corepack.pet.agent.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Tests for the tool modules and the registry, plus a parity check: the prompt built from modules must be
// word for word what the old hand-written prompt builder produced, for every combination of switches.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

val ZONE: ZoneId = ZoneId.of("Africa/Addis_Ababa")
fun at(h: Int, m: Int = 0, day: Int = 12): ZonedDateTime = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZONE)

class FakeTool(name: String) : Tool {
    override val spec: ToolSpec = ToolSpec(name, "fake $name")
    override fun run(arguments: Map<String, Any?>): String = "ok"
}

class FakeModule(
    override val capability: Capability?,
    override val promptHint: String,
    private val names: List<String>,
) : ToolModule {
    var built = 0
    override fun tools(): List<Tool> {
        built++
        return names.map { FakeTool(it) }
    }
}

// ---- the old prompt builder, copied unchanged from before the refactor ----
object LegacyPrompt {
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


fun names(tools: List<Tool>): List<String> = tools.map { it.spec.name }

fun main() {
    // ================= registry
    run {
        val core = FakeModule(null, "core\n", listOf("get_time", "remember"))
        val rem = FakeModule(Capability.REMINDERS, "rem\n", listOf("set_reminder"))
        val notes = FakeModule(Capability.TASKS_NOTES, "notes\n", listOf("add_task", "save_note"))
        val reg = ToolRegistry(listOf(core, rem, notes))

        check("everything on: tools come out in module order", names(reg.tools(Capability.defaults)) == listOf("get_time", "remember", "set_reminder", "add_task", "save_note"), names(reg.tools(Capability.defaults)).toString())
        check("a module switched off gives no tools", names(reg.tools(setOf(Capability.TASKS_NOTES))) == listOf("get_time", "remember", "add_task", "save_note"))
        check("the always-on module stays with everything off", names(reg.tools(emptySet())) == listOf("get_time", "remember"))

        val rem2 = FakeModule(Capability.REMINDERS, "rem\n", listOf("set_reminder"))
        ToolRegistry(listOf(core, rem2)).tools(emptySet())
        check("a module that is off is never asked to build its tools", rem2.built == 0)

        check("prompt hints follow the same switches and order", reg.promptHints(Capability.defaults) == "core\nrem\nnotes\n" && reg.promptHints(setOf(Capability.TASKS_NOTES)) == "core\nnotes\n" && reg.promptHints(emptySet()) == "core\n")

        val extra = FakeModule(null, "- ping: say pong.\n", listOf("ping"))
        val withExtra = ToolRegistry(listOf(core, rem, notes, extra))
        check("adding a module is one more entry in the list", names(withExtra.tools(emptySet())).contains("ping") && withExtra.promptHints(emptySet()).contains("ping"))
    }

    // ================= prompt parity with the old builder
    run {
        val moduleList = listOf(
            FakeModule(null, BuiltinHints.CORE, emptyList()),
            FakeModule(Capability.REMINDERS, BuiltinHints.REMINDERS, emptyList()),
            FakeModule(Capability.TASKS_NOTES, BuiltinHints.TASKS_NOTES, emptyList()),
            FakeModule(Capability.PHONE_ACTIONS, BuiltinHints.PHONE_ACTIONS, emptyList()),
            FakeModule(Capability.LOOKUPS, BuiltinHints.LOOKUPS, emptyList()),
        )
        val reg = ToolRegistry(moduleList)
        val factSets = listOf(emptyList<String>(), listOf("Likes tea", "Lives in Addis Ababa"))
        val clocks = listOf<ZonedDateTime?>(null, at(15))
        var compared = 0
        var firstBad = ""
        for (mask in 0 until 32) {
            val caps = Capability.entries.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
            for (facts in factSets) for (now in clocks) {
                val old = LegacyPrompt.system(facts, now, caps)
                val viaDefault = Prompt.system(facts, now, caps)
                val viaRegistry = Prompt.system(facts, now, caps, reg.promptHints(caps))
                compared++
                if ((old != viaDefault || old != viaRegistry) && firstBad.isEmpty()) firstBad = "caps=$caps facts=${facts.size} clock=${now != null}"
            }
        }
        check("prompt is identical to the old one for all $compared combinations", firstBad.isEmpty(), firstBad)
        check("default arguments still give the full prompt", Prompt.system(emptyList()) == LegacyPrompt.system(emptyList()))

        val custom = Prompt.system(emptyList(), at(9), emptySet(), "- ping: say pong.\n")
        check("a prompt can carry a module's own lines instead", custom.contains("- ping: say pong.") && !custom.contains("get_time"))
        check("the hints sit between the intro and the facts", custom.indexOf("You have tools.") < custom.indexOf("- ping") && custom.indexOf("- ping") < custom.indexOf("What you remember"))
    }

    println(if (failures == 0) "\nALL MODULE TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
