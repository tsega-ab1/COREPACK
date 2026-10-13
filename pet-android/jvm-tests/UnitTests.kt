import com.corepack.pet.agent.*
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

fun call(id: String, name: String, args: Map<String, Any?> = emptyMap(), extra: String? = null) =
    ToolCall(id, name, args, "{}", extra)

/** Scripted model: returns the queued turns in order and records what it was asked. */
class Script(private val turns: MutableList<ModelTurn>) : Model {
    val seenTools = ArrayList<Int>()
    val seenFirstRole = ArrayList<String>()
    val seenHistoryRoles = ArrayList<List<String>>()
    var calls = 0
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        calls++
        seenTools.add(tools.size)
        seenFirstRole.add(messages.first().role)
        seenHistoryRoles.add(messages.drop(1).map { it.role })
        val t = turns.removeAt(0)
        if (t.text.isNotEmpty()) hooks.onText(t.text)
        return t
    }
}

class Rec : AgentLoop.Listener {
    val log = ArrayList<String>()
    override fun onGatewayEvent(name: String) { log.add("ev:$name") }
    override fun onText(delta: String) { log.add("text") }
    override fun onThinking() { log.add("thinking") }
    override fun onToolStart(name: String) { log.add("start:$name") }
    override fun onToolDone(name: String, result: String) { log.add("done:$name=$result") }
}

class Echo(name: String, private val followUp: Boolean = true, private val fail: Boolean = false) : Tool {
    override val spec = ToolSpec(name, "test")
    override val needsFollowUp = followUp
    var runs = 0
    override fun run(arguments: Map<String, Any?>): String {
        runs++
        if (fail) throw IllegalStateException("boom")
        return "ok-$runs"
    }
}

fun main() {
    // ---- AgentLoop
    run {
        val m = Script(mutableListOf(ModelTurn("hello", emptyList())))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "hi")))
        val out = AgentLoop(m, listOf(Echo("t"))).run(h, "SYS", Rec())
        check("plain answer returned and stored", out == "hello" && h.map { it.role } == listOf("user", "assistant"), "$out ${h.map { it.role }}")
        check("system prompt is first for the model but not stored", m.seenFirstRole == listOf("system") && h.none { it.role == "system" })
    }
    run {
        val tool = Echo("get_time")
        val m = Script(mutableListOf(ModelTurn("", listOf(call("c1", "get_time"))), ModelTurn("It is noon", emptyList())))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "time?")))
        val rec = Rec()
        val out = AgentLoop(m, listOf(tool)).run(h, "S", rec)
        check("tool round: answer after tool", out == "It is noon" && tool.runs == 1 && m.calls == 2)
        check("tool round: history roles", h.map { it.role } == listOf("user", "assistant", "tool", "assistant"), h.map { it.role }.toString())
        check("tool message carries the call id", h[2].toolCallId == "c1" && h[2].content == "ok-1")
        check("assistant tool-call message keeps the calls", h[1].toolCalls.size == 1 && h[1].content == null)
        check("listener order", rec.log.filter { !it.startsWith("text") } == listOf("thinking", "start:get_time", "done:get_time=ok-1", "thinking"), rec.log.toString())
    }
    run {
        val mood = Echo("set_pet_mood", followUp = false)
        val m = Script(mutableListOf(ModelTurn("Yay!", listOf(call("m1", "set_pet_mood")))))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "I won")))
        val out = AgentLoop(m, listOf(mood)).run(h, "S", Rec())
        check("fire-and-forget with text ends in one model call", out == "Yay!" && m.calls == 1 && mood.runs == 1)
        check("fire-and-forget leaves a consistent history", h.map { it.role } == listOf("user", "assistant", "tool"), h.map { it.role }.toString())
    }
    run {
        val mood = Echo("set_pet_mood", followUp = false)
        val m = Script(mutableListOf(ModelTurn("", listOf(call("m1", "set_pet_mood"))), ModelTurn("Fine.", emptyList())))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "x")))
        val out = AgentLoop(m, listOf(mood)).run(h, "S", Rec())
        check("fire-and-forget WITHOUT text still gets a reply", out == "Fine." && m.calls == 2)
    }
    run {
        val m = Script(mutableListOf(ModelTurn("", listOf(call("u1", "nope"))), ModelTurn("ok", emptyList())))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "x")))
        val out = AgentLoop(m, listOf(Echo("t"))).run(h, "S", Rec())
        check("unknown tool becomes an error result and the loop continues", out == "ok" && h[2].content!!.startsWith("error: unknown tool"), h.getOrNull(2)?.content ?: "")
    }
    run {
        val m = Script(mutableListOf(ModelTurn("", listOf(call("f1", "bad"))), ModelTurn("recovered", emptyList())))
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "x")))
        val out = AgentLoop(m, listOf(Echo("bad", fail = true))).run(h, "S", Rec())
        check("a throwing tool is reported to the model, not the user", out == "recovered" && h[2].content == "error: boom", h.getOrNull(2)?.content ?: "")
    }
    run {
        val turns = MutableList(10) { ModelTurn("", listOf(call("l$it", "t"))) }
        val m = Script(turns)
        val h = ArrayList<AgentMessage>(listOf(AgentMessage("user", "x")))
        val out = AgentLoop(m, listOf(Echo("t")), maxRounds = 3).run(h, "S", Rec())
        check("round cap: last model call gets no tools and the loop ends", m.seenTools.last() == 0 && m.calls == 4 && out == "(no reply)", "tools=${m.seenTools} calls=${m.calls} out=$out")
    }
    run {
        val m = Script(mutableListOf(ModelTurn("never", emptyList())))
        val loop = AgentLoop(m, emptyList()); loop.cancelled = true
        val out = loop.run(ArrayList(listOf(AgentMessage("user", "x"))), "S", Rec())
        check("cancelled loop never calls the model", out == "" && m.calls == 0)
    }

    // ---- HistoryTrim
    run {
        val h = arrayListOf(
            AgentMessage("user", "1"), AgentMessage("assistant", null, listOf(call("a", "t"))), AgentMessage("tool", "r", toolCallId = "a"),
            AgentMessage("assistant", "x"), AgentMessage("user", "2"), AgentMessage("assistant", "y"),
        )
        HistoryTrim.trim(h, 3)
        check("trim cuts at a user message, never inside a tool pair", h.map { it.role } == listOf("user", "assistant"), h.map { it.role }.toString())
        val g = arrayListOf(AgentMessage("user", "1"), AgentMessage("assistant", "a"))
        HistoryTrim.trim(g, 5)
        check("trim does nothing when short", g.size == 2)
        val k = arrayListOf(AgentMessage("user", "1"), AgentMessage("assistant", "a"), AgentMessage("assistant", "b"), AgentMessage("assistant", "c"))
        HistoryTrim.trim(k, 2)
        check("trim keeps everything if there is no safe cut point", k.size == 4)
    }

    // ---- FactStore
    run {
        val f = File.createTempFile("facts", ".txt"); f.delete()
        val s = FactStore(f, maxFacts = 3, maxLength = 20)
        check("add saves", s.add("Likes jasmine tea") && s.all() == listOf("Likes jasmine tea"))
        check("duplicate (any case) is rejected", !s.add("likes JASMINE tea") && s.all().size == 1)
        check("blank is rejected", !s.add("   \n "))
        check("whitespace is normalised and length capped", s.add("A   very\nlong     fact that goes on and on") && s.all().last() == "A very long fact tha")
        s.add("third"); s.add("fourth")
        check("cap drops the oldest", s.all().size == 3 && s.all().first() != "Likes jasmine tea", s.all().toString())
        check("persisted to disk and reloaded", FactStore(f, 3, 20).all() == s.all())
        check("remove needs 3+ characters", s.remove("th") == 0)
        check("remove by substring", s.remove("FOURTH") == 1 && s.all().none { it == "fourth" })
        s.clear()
        check("clear empties memory and file", s.all().isEmpty() && FactStore(f).all().isEmpty())
        f.delete()
    }

    // ---- Tools / Prompt / Mood
    run {
        val f = File.createTempFile("facts", ".txt"); f.delete()
        val s = FactStore(f)
        val r = RememberTool(s)
        check("remember tool saves", r.run(mapOf("fact" to "Name is Tsega")) == "saved" && s.all() == listOf("Name is Tsega"))
        check("remember tool reports duplicates", r.run(mapOf("fact" to "name is tsega")).startsWith("already"))
        check("remember tool rejects missing argument", r.run(emptyMap()).startsWith("error"))
        check("forget tool", ForgetTool(s).run(mapOf("text" to "tsega")) == "removed 1" && ForgetTool(s).run(mapOf("text" to "zzz")) == "nothing matched")
        var seen: PetMood? = null
        val m = MoodTool { seen = it }
        check("mood tool sets mood and is fire-and-forget", m.run(mapOf("mood" to "HAPPY")) == "ok" && seen == PetMood.HAPPY && !m.needsFollowUp)
        check("mood tool rejects unknown moods", m.run(mapOf("mood" to "furious")).startsWith("error"))
        val clock = ZonedDateTime.of(2026, 10, 10, 13, 7, 0, 0, ZoneId.of("Africa/Addis_Ababa"))
        check("time tool format", TimeTool { clock }.run(emptyMap()) == "Saturday, 10 October 2026, 13:07 (Africa/Addis_Ababa)", TimeTool { clock }.run(emptyMap()))
        check("no-argument tools have no schema", TimeTool().spec.parametersJson.isEmpty())
        check("prompt lists facts", Prompt.system(listOf("Likes tea", "Has a cat")).contains("- Likes tea\n") || Prompt.system(listOf("Likes tea", "Has a cat")).contains("- Likes tea"))
        check("prompt says when nothing is saved", Prompt.system(emptyList()).contains("Nothing saved yet."))
        check("PetMood.parse", PetMood.parse(" Sleepy ") == PetMood.SLEEPY && PetMood.parse("x") == null && PetMood.parse(null) == null)
        f.delete()
    }

    println(if (failures == 0) "\nALL UNIT TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
