import com.corepack.pet.ChatStore
import com.corepack.pet.agent.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

// What the pet does with its tools, with scripted models: chaining several actions, recovering when one fails,
// asking before sensitive ones, telling the user how each went, and remembering the conversation after a restart.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

object Quiet : AgentLoop.Listener {
    override fun onGatewayEvent(name: String) {}
    override fun onText(delta: String) {}
    override fun onThinking() {}
    override fun onToolStart(name: String) {}
    override fun onToolDone(name: String, result: String) {}
}

class ScriptModel(private val turns: MutableList<ModelTurn>) : Model {
    val seen = ArrayList<List<AgentMessage>>()
    val toolCounts = ArrayList<Int>()
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        seen.add(messages.toList())
        toolCounts.add(tools.size)
        return turns.removeAt(0)
    }
}

/** A tool that answers from a list, remembers how it was called, and writes its name to a shared log. */
class Stub(name: String, private val answers: MutableList<String> = mutableListOf(), private val log: MutableList<String>? = null) : Tool {
    override val spec: ToolSpec = ToolSpec(name, "stub $name")
    val calls = ArrayList<Map<String, Any?>>()
    override fun run(arguments: Map<String, Any?>): String {
        calls.add(arguments)
        log?.add(spec.name)
        return if (answers.isEmpty()) "ok" else answers.removeAt(0)
    }
}

class Boom : Tool {
    override val spec: ToolSpec = ToolSpec("explode", "always throws")
    override fun run(arguments: Map<String, Any?>): String = throw IllegalStateException("kaboom")
}

fun call(id: String, name: String, args: Map<String, Any?> = emptyMap()) = ToolCall(id, name, args, "{}")
fun turn(text: String = "", vararg calls: ToolCall) = ModelTurn(text, calls.toList())
fun ask(text: String) = arrayListOf(AgentMessage("user", text))

class Rec : ReplySink {
    val events = CopyOnWriteArrayList<String>()
    override fun onTool(name: String) { events.add("tool:$name") }
    override fun onToolResult(name: String, ok: Boolean, note: String) { events.add("result:$name:${if (ok) "ok" else "failed"}:$note") }
    override fun onToken(text: String) { events.add("tok:$text") }
    override fun onDone(text: String) { events.add("done") }
    override fun onError(message: String, unreachable: Boolean) { events.add("err") }
}

/** A screen written before tools reported how they did: it only knows the four original messages. */
class OldScreen : ReplySink {
    val events = CopyOnWriteArrayList<String>()
    override fun onTool(name: String) { events.add("tool:$name") }
    override fun onToken(text: String) { events.add("tok:$text") }
    override fun onDone(text: String) { events.add("done") }
    override fun onError(message: String, unreachable: Boolean) { events.add("err") }
}

/** A gateway whose tool call fails a few times before it works. */
class Flaky(private val failFirst: Int, private val failCode: Int = 502) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile
    var calls = 0

    @Volatile
    var okBody = """{"ok":true,"text":"fetched"}"""
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/tools/call") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        try {
            ex.requestBody.readBytes()
            calls++
            val failing = calls <= failFirst
            val code = if (failing) failCode else 200
            val body = if (failing) """{"error":{"message":"tool server restarting"}}""" else okBody
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } finally {
            ex.close()
        }
    }
}

fun remote(f: Flaky, needsConfirm: Boolean = false, confirmer: ToolConfirmer? = null): RemoteTool {
    val info = RemoteToolInfo("fake__fetch", "fake", "Fetch a page", "", needsConfirm)
    return RemoteTool(info, HttpToolGateway({ f.baseUrl }, { "tok" })) { confirmer }.also { it.retryDelayMs = 5 }
}

fun main() {
    // ================= several actions in a row
    run {
        val search = Stub("search", mutableListOf("found: jollof rice recipe"))
        val note = Stub("save_note", mutableListOf("saved"))
        val reminder = Stub("set_reminder", mutableListOf("reminder set"))
        val m = ScriptModel(mutableListOf(
            turn("", call("1", "search", mapOf("q" to "jollof rice"))),
            turn("", call("2", "save_note", mapOf("text" to "found: jollof rice recipe"))),
            turn("", call("3", "set_reminder", mapOf("text" to "cook jollof"))),
            turn("I found a recipe, saved it and set a reminder."),
        ))
        val reply = AgentLoop(m, listOf(search, note, reminder)).run(ask("find a rice recipe, save it and remind me to cook"), "S", Quiet)
        check("a task with three steps ends with one answer", reply == "I found a recipe, saved it and set a reminder.", reply)
        check("each tool ran once, in the order the model chose", listOf(search, note, reminder).all { it.calls.size == 1 })
        check("the result of one step reached the model before it chose the next", m.seen[1].any { it.role == "tool" && it.content == "found: jollof rice recipe" })
        check("and the model could use it in the next call", note.calls[0]["text"] == "found: jollof rice recipe")
    }
    run {
        val order = ArrayList<String>()
        val a = Stub("a", log = order)
        val b = Stub("b", log = order)
        val m = ScriptModel(mutableListOf(turn("", call("1", "a"), call("2", "b")), turn("both done")))
        val reply = AgentLoop(m, listOf(a, b)).run(ask("do both"), "S", Quiet)
        check("several calls in one turn all run, in order", order == listOf("a", "b") && reply == "both done", order.toString())
    }
    run {
        val step = Stub("step")
        val turns = (1..8).map { turn("", call("c$it", "step")) }.toMutableList()
        turns.add(turn("wrapping up", call("c9", "step")))
        val m = ScriptModel(turns)
        val reply = AgentLoop(m, listOf(step)).run(ask("keep going"), "S", Quiet)
        check("eight rounds of tools are allowed", step.calls.size == 8 && m.toolCounts.take(8).all { it == 1 }, step.calls.size.toString())
        check("after that the model is made to answer, with no tools left to call", m.toolCounts.last() == 0 && reply == "wrapping up", "tools=${m.toolCounts.last()} reply=$reply")
    }

    // ================= when something fails
    run {
        val search = Stub("web_search", mutableListOf("error: search is down"))
        val wiki = Stub("wiki_summary", mutableListOf("The Blue Nile is a river."))
        val m = ScriptModel(mutableListOf(
            turn("", call("1", "web_search", mapOf("query" to "Blue Nile"))),
            turn("", call("2", "wiki_summary", mapOf("topic" to "Blue Nile"))),
            turn("Search was down, so I used Wikipedia: the Blue Nile is a river."),
        ))
        val reply = AgentLoop(m, listOf(search, wiki)).run(ask("what is the Blue Nile?"), "S", Quiet)
        check("the model is shown that a tool failed", m.seen[1].any { it.role == "tool" && it.content == "error: search is down" })
        check("so it can try another way, which then works", wiki.calls.size == 1 && reply.contains("Wikipedia"), reply)
    }
    run {
        val m = ScriptModel(mutableListOf(turn("", call("1", "explode")), turn("That did not work.")))
        val reply = AgentLoop(m, listOf(Boom())).run(ask("go"), "S", Quiet)
        check("a tool that crashes becomes an error for the model, not a crash", m.seen[1].any { it.role == "tool" && it.content == "error: kaboom" } && reply == "That did not work.")
        val m2 = ScriptModel(mutableListOf(turn("", call("1", "no_such_tool")), turn("I cannot do that.")))
        AgentLoop(m2, emptyList()).run(ask("go"), "S", Quiet)
        val unknown = m2.seen[1].last { it.role == "tool" }.content ?: ""
        check("a tool that does not exist is reported to the model as one", unknown.startsWith("error:") && unknown.contains("no_such_tool"), unknown)
    }

    // ================= a read-only tool is tried once more when the gateway hiccups
    run {
        val f = Flaky(failFirst = 1)
        check("one hiccup is recovered from", remote(f).run(mapOf("url" to "x")) == "fetched" && f.calls == 2, "calls=${f.calls}")
        f.stop()
    }
    run {
        val f = Flaky(failFirst = 5)
        val out = remote(f).run(emptyMap())
        check("but only one retry, then the error is reported", out == "error: tool server restarting" && f.calls == 2, "$out calls=${f.calls}")
        f.stop()
    }
    run {
        val f = Flaky(failFirst = 1)
        val out = remote(f, needsConfirm = true, confirmer = ToolConfirmer { _, _, _ -> true }).run(emptyMap())
        check("a tool that changes things is never repeated by itself", out == "error: tool server restarting" && f.calls == 1, "$out calls=${f.calls}")
        f.stop()
    }
    run {
        val f = Flaky(failFirst = 0)
        f.okBody = """{"ok":false,"text":"bad url"}"""
        check("an error the tool itself reports is not retried", remote(f).run(emptyMap()) == "error: bad url" && f.calls == 1)
        f.stop()
        val gone = Flaky(failFirst = 3, failCode = 404)
        check("a tool that no longer exists is not retried", remote(gone).run(emptyMap()) == "error: that tool is no longer available" && gone.calls == 1)
        gone.stop()
    }

    // ================= telling the user how each action went
    run {
        check("a normal result counts as worked", ToolOutcome.of("Reminder #1 set for 15:20: stretch") == ToolOutcome(true, ""))
        check("an error is a failure, with the reason", ToolOutcome.of("error: search is down") == ToolOutcome(false, "search is down"))
        check("a refusal from the approval step says so", ToolOutcome.of("error: the user did not allow this") == ToolOutcome(false, "you did not allow it"))
        check("so does a refusal from an online tool", ToolOutcome.of("The user did not allow this, so it was not run. Do not try again unless they ask.") == ToolOutcome(false, "you did not allow it"))
        check("a long reason is cut short", ToolOutcome.of("error: " + "x".repeat(500)).note.length == 120)
        check("blank is not a failure", ToolOutcome.of("").ok)
    }
    run {
        val rec = Rec()
        val sink = PacedSink(rec, charsPerSecond = 100000, tickMs = 1)
        val listener = object : AgentLoop.Listener {
            override fun onGatewayEvent(name: String) {}
            override fun onText(delta: String) { sink.onToken(delta) }
            override fun onThinking() {}
            override fun onToolStart(name: String) { sink.onTool(name) }
            override fun onToolDone(name: String, result: String) {
                val o = ToolOutcome.of(result)
                sink.onToolResult(name, o.ok, o.note)
            }
        }
        val m = ScriptModel(mutableListOf(
            turn("", call("1", "web_search")), turn("", call("2", "wiki_summary")), turn("Used Wikipedia instead.")))
        val reply = AgentLoop(m, listOf(Stub("web_search", mutableListOf("error: search is down")), Stub("wiki_summary"))).run(ask("q"), "S", listener)
        sink.onDone(reply)
        Thread.sleep(300)
        check("the screen hears how each tool went, in order",
            rec.events.toList() == listOf("tool:web_search", "result:web_search:failed:search is down", "tool:wiki_summary", "result:wiki_summary:ok:", "done"), rec.events.toString())
    }
    run {
        val rec = Rec()
        val sink = PacedSink(rec, charsPerSecond = 100000, tickMs = 1)
        sink.cancel()
        sink.onTool("x")
        sink.onToolResult("x", true, "")
        sink.onDone("")
        Thread.sleep(200)
        check("after Stop, no more results are shown", rec.events.toList() == listOf("done"), rec.events.toString())
        val old = OldScreen()
        val s2 = PacedSink(old, charsPerSecond = 100000, tickMs = 1)
        s2.onTool("x")
        s2.onToolResult("x", false, "boom")
        s2.onToken("hi")
        s2.onDone("hi")
        Thread.sleep(200)
        check("a screen that predates this still works, and just does not hear the result", old.events.toList() == listOf("tool:x", "tok:hi", "done"), old.events.toString())
    }

    // ================= asking first
    run {
        val file = File.createTempFile("facts", ".txt")
        file.deleteOnExit()
        check("forgetting what the pet knows asks first", ForgetTool(FactStore(file)).risk == Risk.ASK)
        check("telling the time does not", TimeTool().risk == Risk.SAFE)
        val src = System.getenv("PET_SRC")
        if (src == null) {
            println("SKIP the tools that need Android are checked from their source when PET_SRC is set (run.sh sets it)")
        } else {
            for ((path, cls) in listOf("DeviceTools.kt" to "OpenUrlTool", "ScreenTools.kt" to "ReadScreenTool")) {
                val text = File(src, path).readText()
                val start = text.indexOf("class $cls")
                val next = text.indexOf("\nclass ", start + 5)
                val body = if (start < 0) "" else text.substring(start, if (next < 0) text.length else next)
                check("$cls asks first", body.contains("override val risk: Risk = Risk.ASK"), if (start < 0) "class not found" else "")
            }
        }
    }

    // ================= remembering the conversation
    run {
        val chat = listOf("assistant" to "Good morning", "user" to "hi", "tool" to "used get_time", "assistant" to "hello", "assistant" to "how can I help?",
            "error" to "oops", "user" to "  ", "user" to "what time is it?", "assistant" to "15:20", "user" to "thanks")
        val h = HistoryRestore.fromChat(chat)
        check("only what the user and the pet said is kept, joined where one side spoke twice",
            h.map { it.role to it.content } == listOf("user" to "hi", "assistant" to "hello\n\nhow can I help?", "user" to "what time is it?", "assistant" to "15:20"), h.toString())
        check("it starts with the user and does not end on a question nobody answered", h.first().role == "user" && h.last().role == "assistant")
        val long = (1..30).flatMap { listOf("user" to "q$it", "assistant" to "a$it") }
        val cut = HistoryRestore.fromChat(long, maxMessages = 6)
        check("only the latest are kept, still starting with the user", cut.size == 6 && cut.first().content == "q28" && cut.first().role == "user", cut.map { it.content }.toString())
        check("an empty chat restores nothing", HistoryRestore.fromChat(emptyList()).isEmpty() && HistoryRestore.fromChat(listOf("user" to "only me")).isEmpty())
    }
    run {
        val m = ScriptModel(mutableListOf(turn("Your name is Beka.")))
        val s = AgentSession()
        s.restore(listOf(AgentMessage("user", "my name is Beka"), AgentMessage("assistant", "Nice to meet you, Beka")))
        s.arm(m, emptyList())
        s.run("what is my name?", { "S" }, Quiet)
        check("after a restart the model still has the earlier conversation", m.seen[0].map { it.role } == listOf("system", "user", "assistant", "user") && m.seen[0][1].content == "my name is Beka")
        s.restore(listOf(AgentMessage("user", "other"), AgentMessage("assistant", "other")))
        check("restoring into a conversation that has started changes nothing", s.snapshot().size == 4)
    }
    run {
        val f = File.createTempFile("chat", ".json")
        f.delete()
        ChatStore.detach()
        ChatStore.attach(f)
        ChatStore.add("user", "hello")
        ChatStore.add("tool", "used get_time - worked")
        ChatStore.add("assistant", "hi there")
        ChatStore.add("error", "Can't reach the gateway")
        ChatStore.save()
        ChatStore.detach()
        check("the chat is empty right after a restart, until it is loaded", ChatStore.snapshot().isEmpty())
        ChatStore.attach(f)
        val back = ChatStore.snapshot().map { it.role to it.text }
        check("the saved chat comes back, without the error lines", back == listOf("user" to "hello", "tool" to "used get_time - worked", "assistant" to "hi there"), back.toString())
        check("and only the words the model should see go back to it", ChatStore.forRequest() == listOf("user" to "hello", "assistant" to "hi there"), ChatStore.forRequest().toString())
        ChatStore.attach(File.createTempFile("other", ".json"))
        check("attaching again does nothing", ChatStore.snapshot().size == 3)
        ChatStore.clear()
        check("clearing forgets it here and on disk", ChatStore.snapshot().isEmpty() && !f.exists())
        ChatStore.detach()

        val bad = File.createTempFile("bad", ".json")
        bad.writeText("this is not json {{{")
        ChatStore.attach(bad)
        check("a damaged file means an empty chat, not a crash", ChatStore.snapshot().isEmpty())
        ChatStore.detach()
        bad.delete()

        val many = File.createTempFile("many", ".json")
        many.delete()
        ChatStore.attach(many)
        for (i in 1..130) ChatStore.add("user", "m$i")
        ChatStore.save()
        ChatStore.detach()
        ChatStore.attach(many)
        check("only the most recent hundred are kept", ChatStore.snapshot().size == 100 && ChatStore.snapshot().first().text == "m31", ChatStore.snapshot().size.toString())
        ChatStore.detach()
        many.delete()
    }

    println(if (failures == 0) "\nALL BEHAVIOR TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
