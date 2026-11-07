import com.corepack.pet.agent.*

// Tests for AgentSession: the conversation history, reminders injected between messages, cleanup after a
// failure, trimming and cancelling, all with a scripted fake model and no phone.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

/** Answers from a script, one entry per model round, and remembers every conversation it was shown. */
class ScriptModel(private val turns: MutableList<() -> ModelTurn>) : Model {
    val seen = ArrayList<List<AgentMessage>>()
    var cancelCalls = 0

    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        seen.add(messages.toList())
        return turns.removeAt(0)()
    }

    override fun cancel() {
        cancelCalls++
    }
}

fun say(text: String): () -> ModelTurn = { ModelTurn(text, emptyList()) }

object Quiet : AgentLoop.Listener {
    override fun onGatewayEvent(name: String) {}
    override fun onText(delta: String) {}
    override fun onThinking() {}
    override fun onToolStart(name: String) {}
    override fun onToolDone(name: String, result: String) {}
}

class EchoTool : Tool {
    override val spec: ToolSpec = ToolSpec("echo", "echoes")
    override fun run(arguments: Map<String, Any?>): String = "echoed"
}

/** Like a real engine: the text is streamed to the screen before the turn is returned. */
class StreamingModel(private val turns: MutableList<ModelTurn>) : Model {
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        val t = turns.removeAt(0)
        if (t.text.isNotEmpty()) hooks.onText(t.text)
        return t
    }
}

/** Does what PetAgent does: remembers the streamed text of the current round. */
class TrackingListener(val streamed: StreamedText) : AgentLoop.Listener {
    override fun onGatewayEvent(name: String) {}
    override fun onText(delta: String) { streamed.add(delta) }
    override fun onThinking() { streamed.newRound() }
    override fun onToolStart(name: String) {}
    override fun onToolDone(name: String, result: String) {}
}

/** Like the mood tool: it runs alongside the reply, so the loop does not ask the model again. */
class MoodLikeTool : Tool {
    override val spec: ToolSpec = ToolSpec("set_pet_mood", "mood")
    override val needsFollowUp: Boolean = false
    override fun run(arguments: Map<String, Any?>): String = "mood set"
}

fun script(vararg turns: () -> ModelTurn): ScriptModel = ScriptModel(turns.toMutableList())

fun roles(messages: List<AgentMessage>): List<String> = messages.map { it.role }

fun main() {
    // ================= a plain turn
    run {
        val m = script(say("Hi!"))
        val s = AgentSession()
        s.arm(m, emptyList())
        val reply = s.run("hello", { "SYS" }, Quiet)
        check("a plain turn returns the model's text", reply == "Hi!", reply)
        check("history holds the user message and the reply", roles(s.snapshot()) == listOf("user", "assistant") && s.snapshot()[0].content == "hello" && s.snapshot()[1].content == "Hi!")
        check("the system prompt is the first message the model sees", m.seen[0][0] == AgentMessage("system", "SYS"), m.seen[0].toString())
    }
    run {
        var reads = 0
        val s = AgentSession()
        s.arm(script(say("ok")), emptyList())
        s.run("x", { reads++; "SYS" }, Quiet)
        check("the system prompt is read once per message", reads == 1, "reads=$reads")
    }

    // ================= history carries over
    run {
        val s = AgentSession()
        s.arm(script(say("one")), emptyList())
        s.run("first", { "SYS" }, Quiet)
        val m2 = script(say("two"))
        s.arm(m2, emptyList())
        s.run("second", { "SYS" }, Quiet)
        check("the second message is sent with the whole conversation so far", roles(m2.seen[0]) == listOf("system", "user", "assistant", "user"), roles(m2.seen[0]).toString())
    }

    // ================= things the pet said by itself
    run {
        val s = AgentSession()
        s.arm(script(say("Hi!")), emptyList())
        s.run("hello", { "SYS" }, Quiet)
        s.inject("Reminder: stretch")
        val m2 = script(say("Done."))
        s.arm(m2, emptyList())
        s.run("thanks", { "SYS" }, Quiet)
        check("a reminder is folded into the last plain reply", m2.seen[0][2].content == "Hi!\n\nReminder: stretch", m2.seen[0][2].content ?: "null")
        check("and the roles still alternate", roles(m2.seen[0]) == listOf("system", "user", "assistant", "user"))
    }
    run {
        val s = AgentSession()
        s.inject("Good morning")
        s.arm(script(say("Hello")), emptyList())
        s.run("hi", { "SYS" }, Quiet)
        check("with no reply to fold into, it becomes its own assistant message", roles(s.snapshot()) == listOf("assistant", "user", "assistant") && s.snapshot()[0].content == "Good morning", roles(s.snapshot()).toString())
    }

    // ================= failure cleanup
    run {
        val s = AgentSession()
        s.arm(script(say("fine")), emptyList())
        s.run("one", { "SYS" }, Quiet)
        val before = s.snapshot()
        s.arm(ScriptModel(mutableListOf({ throw ModelException("boom") })), emptyList())
        var thrown: Exception? = null
        try {
            s.run("two", { "SYS" }, Quiet)
        } catch (e: Exception) {
            thrown = e
        }
        check("a failed turn throws the error on", thrown is ModelException && thrown.message == "boom", thrown.toString())
        check("and leaves the history as it was before the message", s.snapshot() == before, s.snapshot().toString())
    }
    run {
        val s = AgentSession()
        s.arm(ScriptModel(mutableListOf({ throw ModelException("x") })), emptyList())
        try {
            s.run("first ever", { "SYS" }, Quiet)
        } catch (e: ModelException) {
            // expected
        }
        check("a failure on the very first message leaves an empty history", s.snapshot().isEmpty(), s.snapshot().toString())
    }

    // ================= tools
    run {
        val call = ToolCall("c1", "echo", emptyMap(), "{}")
        val m = script({ ModelTurn("", listOf(call)) }, say("done"))
        val s = AgentSession()
        s.arm(m, listOf(EchoTool()))
        val reply = s.run("use it", { "SYS" }, Quiet)
        val snap = s.snapshot()
        check("a tool call round trip returns the final text", reply == "done", reply)
        check("history records the call, its result and the answer", roles(snap) == listOf("user", "assistant", "tool", "assistant") && snap[2].content == "echoed", roles(snap).toString())
    }

    // ================= trimming
    run {
        val s = AgentSession(4)
        var worst = 0
        for (i in 1..6) {
            s.arm(script(say("r$i")), emptyList())
            s.run("m$i", { "SYS" }, Quiet)
            worst = maxOf(worst, s.snapshot().size)
        }
        check("the history stays bounded", worst <= 5, "largest=$worst")
        check("and never starts in the middle of a turn", s.snapshot().first().role == "user", roles(s.snapshot()).toString())
    }

    // ================= cancelling
    run {
        val m = script(say("never"))
        val s = AgentSession()
        s.arm(m, emptyList())
        s.cancel()
        val reply = s.run("hello", { "SYS" }, Quiet)
        check("cancel reaches the model", m.cancelCalls == 1, "calls=${m.cancelCalls}")
        check("a cancelled turn asks the model nothing and returns an empty reply", reply == "" && m.seen.isEmpty(), "reply='$reply' asked=${m.seen.size}")
    }

    // ================= a reply must not be shown twice
    run {
        val mood = ToolCall("m1", "set_pet_mood", emptyMap(), "{}")
        val s = AgentSession()
        s.arm(StreamingModel(mutableListOf(ModelTurn("Great to hear!", listOf(mood)))), listOf(MoodLikeTool()))
        val streamed = StreamedText()
        val reply = s.run("I passed my exam", { "SYS" }, TrackingListener(streamed))
        check("a reply written alongside the mood tool is the turn's reply", reply == "Great to hear!", reply)
        check("and the chat is told nothing more, because it already saw that text", streamed.leftToShow(reply) == "", streamed.leftToShow(reply))
    }
    run {
        val echo = ToolCall("c1", "echo", emptyMap(), "{}")
        val s = AgentSession()
        s.arm(StreamingModel(mutableListOf(ModelTurn("Let me check.", listOf(echo)), ModelTurn("It is 3pm.", emptyList()))), listOf(EchoTool()))
        val streamed = StreamedText()
        val reply = s.run("time?", { "SYS" }, TrackingListener(streamed))
        check("after a tool that needs a follow-up the final answer is the second round", reply == "It is 3pm.", reply)
        check("it had streamed in, so nothing is added", streamed.leftToShow(reply) == "")
        check("the lead-in before the tool is not mistaken for the reply", streamed.leftToShow("Let me check.") == "Let me check.")
    }
    run {
        val streamed = StreamedText()
        streamed.newRound()
        check("text that never streamed still has to be shown", streamed.leftToShow("Hello") == "Hello")
        check("an empty reply stays empty", streamed.leftToShow("") == "")
        streamed.add("Hi there\n")
        check("trailing whitespace does not matter", streamed.leftToShow("Hi there") == "")
    }

    // ================= misuse
    run {
        var thrown = false
        try {
            AgentSession().run("x", { "SYS" }, Quiet)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        check("running before arming is refused with a clear error", thrown)
    }

    println(if (failures == 0) "\nALL SESSION TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
