import com.corepack.pet.agent.*
import org.json.JSONArray
import java.io.File
import java.net.URL

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

class Rec : AgentLoop.Listener {
    val events = ArrayList<String>(); val text = StringBuilder(); val tools = ArrayList<String>()
    override fun onGatewayEvent(name: String) { events.add(name) }
    override fun onText(delta: String) { text.append(delta) }
    override fun onThinking() {}
    override fun onToolStart(name: String) { tools.add(name) }
    override fun onToolDone(name: String, result: String) {}
}

fun upstreamLog(): JSONArray = JSONArray(URL("http://127.0.0.1:9921/_log").readText())

fun main() {
    val token = System.getenv("GW_TOKEN")!!
    val url = "http://127.0.0.1:8788/v1"
    val facts = FactStore(File.createTempFile("facts", ".txt").also { it.delete() })
    var mood: PetMood? = null
    val tools = listOf(TimeTool(), RememberTool(facts), ForgetTool(facts), MoodTool { mood = it })

    fun run(userText: String): Pair<String, Rec> {
        val loop = AgentLoop(GatewayModel(url, token), tools)
        val rec = Rec()
        val out = loop.run(ArrayList(listOf(AgentMessage("user", userText))), Prompt.system(facts.all()), rec)
        return out to rec
    }

    // 1) OpenAI-style fragmented call with index; signature on the call; needs a follow-up round
    var before = upstreamLog().length()
    val (a, recA) = run("please remember that I like jasmine tea")
    var log = upstreamLog()
    check("remember: fragments were joined and the tool ran", facts.all() == listOf("likes jasmine tea"), facts.all().toString())
    check("remember: two upstream requests (call, then follow-up)", log.length() - before == 2, "${log.length() - before}")
    val second = log.getJSONObject(log.length() - 1)
    val asst = second.getJSONArray("messages").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.last { it.optString("role") == "assistant" } }
    val sigA = asst.getJSONArray("tool_calls").getJSONObject(0).optJSONObject("extra_content")?.optJSONObject("google")?.optString("thought_signature")
    check("remember: thought signature echoed back to Gemini", sigA == "SIG_A", sigA.toString())
    check("remember: final answer streamed to the user", a == "Result: saved" && recA.text.toString() == "Result: saved" && recA.tools == listOf("remember"), "$a | ${recA.text} | ${recA.tools}")
    check("system prompt reached the model with saved facts only after saving", log.getJSONObject(log.length() - 2).getJSONArray("messages").getJSONObject(0).getString("content").contains("Nothing saved yet."))

    // 2) text + fire-and-forget tool; signature on the TEXT delta; no follow-up
    before = upstreamLog().length()
    val (b, recB) = run("I'm so happy today")
    log = upstreamLog()
    check("mood: tool ran and the pet mood changed", mood == PetMood.HAPPY)
    check("mood: only one upstream request", log.length() - before == 1, "${log.length() - before}")
    check("mood: reply text is the streamed text", b == "That's wonderful! " && recB.text.toString() == b, b)

    // 3) no index, empty arguments, signature on the call
    before = upstreamLog().length()
    val (c, _) = run("what time is it")
    log = upstreamLog()
    val reqC1 = log.getJSONObject(before)
    val getTime = (0 until reqC1.getJSONArray("tools").length()).map { reqC1.getJSONArray("tools").getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "get_time" }
    check("time: no-argument tool is sent without a parameters schema", !getTime.has("parameters"))
    check("time: empty arguments handled and result passed back", c.startsWith("Result: ") && c.contains("20"), c)
    check("time: signature echoed", log.getJSONObject(log.length() - 1).getJSONArray("messages").let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }.last { it.optString("role") == "assistant" }
    }.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature") == "SIG_C")

    // 4) plain chat
    val (d, recD) = run("hello")
    check("plain chat streams text", d == "Hello there." && recD.text.toString() == d)

    // 5) the fake really does enforce signatures: a call without one must fail the follow-up with a clear error
    val msg = try { run("nosig please"); "no error" } catch (e: ModelException) { e.message ?: "" }
    check("missing signature is rejected by Gemini and surfaced as an error", msg.contains("thought_signature"), msg)

    // 6) transport errors
    val m1 = try { AgentLoop(GatewayModel(url, "wrong-token"), tools).run(ArrayList(listOf(AgentMessage("user", "x"))), "S", Rec()); "no error" } catch (e: ModelException) { e.message ?: "" }
    check("wrong token gives a helpful message", m1.contains("rejected the token"), m1)
    val unreachable = try { AgentLoop(GatewayModel("http://127.0.0.1:1/v1", token), tools).run(ArrayList(listOf(AgentMessage("user", "x"))), "S", Rec()); false } catch (e: ModelException) { e.unreachable }
    check("gateway down is flagged unreachable", unreachable)

    // 7) cancel mid-flight
    val gm = GatewayModel(url, token)
    gm.cancel()
    val cancelled = try { gm.turn(listOf(AgentMessage("user", "x")), emptyList(), object : TurnHooks { override fun onGatewayEvent(name: String) {}; override fun onText(delta: String) {} }); false } catch (e: ModelException) { e.cancelled }
    check("cancelled model refuses to start", cancelled)

    println(if (failures == 0) "\nALL END-TO-END CHECKS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
