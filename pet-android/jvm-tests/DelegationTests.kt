import com.corepack.pet.agent.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Tests for handing a task to another model (named engines), reading resources from the PC, and Stop reaching
// whatever is running. A small fake gateway runs inside the test.

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

val ENGINES = listOf(EngineInfo("fast", "Quick answers"), EngineInfo("deep", "Careful reasoning"))

/** Answers with a fixed text and remembers what it was shown. */
class AnswerModel(private val answer: String) : Model {
    val seen = CopyOnWriteArrayList<Pair<List<AgentMessage>, List<ToolSpec>>>()
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        seen.add(messages.toList() to tools.toList())
        return ModelTurn(answer, emptyList())
    }
}

class FailingModel(private val error: ModelException) : Model {
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn = throw error
}

/** Waits until it is cancelled, like a model that is still thinking when Stop is pressed. */
class BlockingModel : Model {
    val started = CountDownLatch(1)
    private val stop = CountDownLatch(1)
    @Volatile
    var cancelCalls = 0
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn {
        started.countDown()
        stop.await(10, TimeUnit.SECONDS)
        throw ModelException("cancelled", cancelled = true)
    }

    override fun cancel() {
        cancelCalls++
        stop.countDown()
    }
}

class BlockingTool : Tool, CancellableTool {
    override val spec: ToolSpec = ToolSpec("wait_here", "waits")
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile
    var cancelled = false
    override fun run(arguments: Map<String, Any?>): String {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        return if (cancelled) "stopped" else "finished"
    }

    override fun cancel() {
        cancelled = true
        release.countDown()
    }
}

class OneShotModel(private val turns: MutableList<ModelTurn>) : Model {
    override fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn = turns.removeAt(0)
}

/** A gateway in miniature: tool list, engines, resources and a chat that answers with fixed text. */
class Gw {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    @Volatile var toolsCode = 200
    @Volatile var toolsBody = """{"tools":[{"name":"fake__ping","server":"fake","description":"Say pong","needs_confirm":false,"parameters":{"type":"object","properties":{}}}],"errors":{}}"""
    @Volatile var enginesCode = 200
    @Volatile var enginesBody = """{"engines":[{"name":"fast","description":"Quick answers"},{"name":"deep","description":"Careful reasoning"}]}"""
    @Volatile var resourcesCode = 200
    @Volatile var resourcesBody = """{"resources":[{"server":"notes","uri":"file:///a.txt","name":"A","description":"First note","mime_type":"text/plain"},{"server":"notes","uri":"file:///b.txt","name":"B","description":"","mime_type":"text/plain"}],"errors":{}}"""
    @Volatile var readCode = 200
    @Volatile var readBody = """{"ok":true,"text":"contents of the note"}"""
    @Volatile var chatText = "specialist answer"
    val hits = CopyOnWriteArrayList<String>()
    val chatBodies = CopyOnWriteArrayList<String>()
    val readBodies = CopyOnWriteArrayList<String>()

    init {
        server.createContext("/v1") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun send(ex: HttpExchange, code: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            hits.add(ex.requestMethod + " " + path)
            val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
            when {
                ex.requestMethod == "GET" && path == "/v1/tools" -> send(ex, toolsCode, toolsBody)
                ex.requestMethod == "GET" && path == "/v1/engines" -> send(ex, enginesCode, enginesBody)
                ex.requestMethod == "GET" && path == "/v1/resources" -> send(ex, resourcesCode, resourcesBody)
                ex.requestMethod == "POST" && path == "/v1/resources/read" -> {
                    readBodies.add(body)
                    send(ex, readCode, readBody)
                }
                ex.requestMethod == "POST" && path == "/v1/chat/completions" -> {
                    chatBodies.add(body)
                    val chunk = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", chatText))))
                    send(ex, 200, "data: $chunk\n\ndata: [DONE]\n\n", "text/event-stream")
                }
                else -> send(ex, 404, """{"error":{"message":"Not found"}}""")
            }
        } finally {
            ex.close()
        }
    }
}

fun names(tools: List<Tool>): List<String> = tools.map { it.spec.name }

fun main() {
    // ================= handing a task to another model
    run {
        val tool = DelegateTool(ENGINES, { AnswerModel("x") })
        val schema = JSONObject(tool.spec.parametersJson)
        check("the tool has a clear name", tool.spec.name == "ask_specialist")
        check("the model is told what each engine is good for", tool.spec.description.contains("fast: Quick answers") && tool.spec.description.contains("deep: Careful reasoning"), tool.spec.description)
        val engineSchema = schema.getJSONObject("properties").getJSONObject("engine")
        check("the engine can only be one of the real names", (0 until engineSchema.getJSONArray("enum").length()).map { engineSchema.getJSONArray("enum").getString(it) } == listOf("fast", "deep"))
        check("both the engine and the task are required", schema.getJSONArray("required").length() == 2)
    }
    run {
        val specialist = AnswerModel("It is 42.")
        val asked = CopyOnWriteArrayList<String>()
        val tool = DelegateTool(ENGINES, { name -> asked.add(name); specialist })
        val answer = tool.run(mapOf("engine" to "deep", "task" to "What is six times seven?"))
        check("the answer comes back as the tool's result", answer == "It is 42.", answer)
        check("the right engine was used", asked.toList() == listOf("deep"))
        val (messages, tools) = specialist.seen[0]
        check("the specialist sees only the task, with no conversation", messages.map { it.role } == listOf("system", "user") && messages[1].content == "What is six times seven?", messages.toString())
        check("and it has no tools, so it cannot act or hand the task on", tools.isEmpty())
    }
    run {
        val asked = CopyOnWriteArrayList<String>()
        val tool = DelegateTool(ENGINES, { name -> asked.add(name); AnswerModel("x") })
        val unknown = tool.run(mapOf("engine" to "gpt-9", "task" to "hi"))
        check("an engine that does not exist is refused, with the real choices", unknown.startsWith("error:") && unknown.contains("fast, deep"), unknown)
        check("a task with nothing in it is refused", tool.run(mapOf("engine" to "fast", "task" to "  ")).startsWith("error:"))
        check("neither of those woke a model", asked.isEmpty())
    }
    run {
        val long = DelegateTool(ENGINES, { AnswerModel("z".repeat(5000)) }, maxChars = 100).run(mapOf("engine" to "fast", "task" to "t"))
        check("a long answer is cut and says so", long.startsWith("z".repeat(100)) && long.contains("4900 more characters"), long.takeLast(40))
        check("an empty answer is an error, not silence", DelegateTool(ENGINES, { AnswerModel("") }).run(mapOf("engine" to "fast", "task" to "t")).startsWith("error:"))
        check("a model that fails is reported", DelegateTool(ENGINES, { FailingModel(ModelException("boom")) }).run(mapOf("engine" to "fast", "task" to "t")) == "error: the specialist failed: boom")
        check("a cancelled model is reported as cancelled", DelegateTool(ENGINES, { FailingModel(ModelException("stop", cancelled = true)) }).run(mapOf("engine" to "fast", "task" to "t")) == "error: cancelled")
    }

    // ================= Stop
    run {
        val model = BlockingModel()
        val tool = DelegateTool(ENGINES, { model })
        var result = ""
        val t = Thread { result = tool.run(mapOf("engine" to "fast", "task" to "think hard")) }
        t.start()
        check("the specialist is working", model.started.await(5, TimeUnit.SECONDS))
        tool.cancel()
        t.join(5000)
        check("cancelling stops the specialist and the tool returns at once", !t.isAlive && result == "error: cancelled", result)
        check("the model itself was told to stop", model.cancelCalls == 1)
        check("cancelling when nothing is running does nothing", run { tool.cancel(); true })
    }
    run {
        val blocking = BlockingTool()
        val call = ToolCall("c1", "wait_here", emptyMap(), "{}")
        val s = AgentSession()
        s.arm(OneShotModel(mutableListOf(ModelTurn("", listOf(call))))) { listOf(blocking) }
        var reply: String? = null
        val t = Thread { reply = s.run("go", { "SYS" }, Quiet) }
        t.start()
        check("the turn reaches the tool", blocking.started.await(5, TimeUnit.SECONDS))
        s.cancel()
        t.join(5000)
        check("Stop reaches a tool that can be stopped, and the turn ends", !t.isAlive && blocking.cancelled && reply == "", "reply=$reply")
    }

    // ================= asking the gateway for an engine
    run {
        val g = Gw()
        val named = GatewayModel(g.baseUrl, "tok", 5000, "fast")
        val hooks = object : TurnHooks {
            override fun onGatewayEvent(name: String) {}
            override fun onText(delta: String) {}
        }
        val turn = named.turn(listOf(AgentMessage("user", "hi")), emptyList(), hooks)
        check("a named engine is asked for in the request", JSONObject(g.chatBodies[0]).optString("model") == "fast")
        check("and its answer is read as usual", turn.text == "specialist answer", turn.text)
        GatewayModel(g.baseUrl, "tok", 5000).turn(listOf(AgentMessage("user", "hi")), emptyList(), hooks)
        check("without a name the request carries no model at all", !JSONObject(g.chatBodies[1]).has("model"))
        g.stop()
    }
    run {
        val g = Gw()
        val tool = DelegateTool(ENGINES, { GatewayModel(g.baseUrl, "tok", 5000, it) })
        val answer = tool.run(mapOf("engine" to "fast", "task" to "Summarize: cats sleep a lot"))
        check("a task really travels to the gateway and back", answer == "specialist answer", answer)
        check("it asked the engine by name, and the specialist had no tools", JSONObject(g.chatBodies[0]).optString("model") == "fast" && !JSONObject(g.chatBodies[0]).has("tools"))
        g.stop()
    }

    // ================= what the pet is offered
    run {
        val g = Gw()
        val m = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, modelFor = { GatewayModel(g.baseUrl, "tok", 5000, it) })
        val n = names(m.tools())
        check("with engines and resources on the PC, the pet gets the server's tools and the three helpers", n == listOf("fake__ping", "ask_specialist", "list_resources", "read_resource"), n.toString())
        check("the engine list shown to the model comes from the gateway", m.tools().first { it.spec.name == "ask_specialist" }.spec.description.contains("deep: Careful reasoning"))
        g.stop()
    }
    run {
        val g = Gw()
        val noModel = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null })
        check("without a way to reach other models there is no delegation, but resources still work", names(noModel.tools()) == listOf("fake__ping", "list_resources", "read_resource"), names(noModel.tools()).toString())
        g.enginesBody = """{"engines":[]}"""
        g.resourcesBody = """{"resources":[],"errors":{}}"""
        val empty = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, modelFor = { GatewayModel(g.baseUrl, "tok", 5000, it) })
        check("with none of either on the PC, there are no helpers", names(empty.tools()) == listOf("fake__ping"))
        g.stop()
    }
    run {
        val g = Gw()
        g.enginesCode = 404
        g.resourcesCode = 404
        val m = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, modelFor = { GatewayModel(g.baseUrl, "tok", 5000, it) })
        check("an older gateway without these endpoints is fine", names(m.tools()) == listOf("fake__ping"))
        g.enginesCode = 500
        g.enginesBody = """{"error":{"message":"broken"}}"""
        val m2 = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, modelFor = { GatewayModel(g.baseUrl, "tok", 5000, it) })
        check("a failing helper list never takes the server's tools down with it", names(m2.tools()) == listOf("fake__ping"))
        g.stop()
    }
    run {
        val g = Gw()
        g.toolsCode = 500
        g.toolsBody = """{"error":{"message":"down"}}"""
        val m = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, modelFor = { GatewayModel(g.baseUrl, "tok", 5000, it) })
        check("if the tool list cannot be fetched, nothing else is tried", m.tools().isEmpty() && g.hits.none { it.endsWith("/engines") || it.endsWith("/resources") }, g.hits.toString())
        g.stop()
    }
    run {
        val g = Gw()
        var now = 1_000L
        val m = RemoteToolsModule(HttpToolGateway({ g.baseUrl }, { "tok" }), { null }, { now }, 30_000, { GatewayModel(g.baseUrl, "tok", 5000, it) })
        m.tools()
        m.tools()
        check("the helper lists are remembered like the tool list", g.hits.count { it == "GET /v1/engines" } == 1 && g.hits.count { it == "GET /v1/resources" } == 1)
        now += 31_000
        m.tools()
        check("and fetched again after the time limit", g.hits.count { it == "GET /v1/engines" } == 2)
        g.stop()
    }

    // ================= reading resources
    run {
        val g = Gw()
        val gateway = HttpToolGateway({ g.baseUrl }, { "tok" })
        val list = ListResourcesTool(gateway.resources())
        val out = list.run(emptyMap())
        check("the model sees each resource's server, uri and name", out.lines()[0] == "notes | file:///a.txt | A - First note" && out.lines()[1] == "notes | file:///b.txt | B", out)
        check("a long list is cut and says so", ListResourcesTool(gateway.resources(), max = 1).run(emptyMap()).endsWith("[1 more not shown]"))
        check("an empty list says there is nothing", ListResourcesTool(emptyList()).run(emptyMap()) == "There is nothing to read.")
        val read = ReadResourceTool(gateway)
        check("reading returns the text", read.run(mapOf("server" to "notes", "uri" to "file:///a.txt")) == "contents of the note")
        val sent = JSONObject(g.readBodies[0])
        check("and asks for exactly that server and uri", sent.getString("server") == "notes" && sent.getString("uri") == "file:///a.txt")
        check("a missing argument is explained", read.run(mapOf("server" to "notes")).startsWith("error:") && g.readBodies.size == 1)
        g.readCode = 404
        g.readBody = """{"error":{"message":"No resource"}}"""
        check("a resource that is not offered says so", read.run(mapOf("server" to "notes", "uri" to "file:///zzz")) == "error: that resource is not available")
        g.readCode = 200
        g.readBody = """{"ok":false,"text":"it broke"}"""
        check("an error from the server is passed on", read.run(mapOf("server" to "notes", "uri" to "file:///a.txt")) == "error: it broke")
        g.stop()
        check("an unreachable gateway is an error, not a crash", read.run(mapOf("server" to "notes", "uri" to "file:///a.txt")).startsWith("error:"))
    }

    println(if (failures == 0) "\nALL DELEGATION TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
