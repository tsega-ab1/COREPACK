import com.corepack.pet.agent.*
import org.json.JSONObject

// The rules every tool module lives by: what Android must allow first, which tools must ask before they run,
// and what the screen eye keeps private.

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "   -> $detail" else ""))
    if (!ok) failures++
}

class Echo(val name: String, override val risk: Risk = Risk.SAFE) : Tool {
    override val spec = ToolSpec(name, "echo $name")
    val ran = ArrayList<Map<String, Any?>>()
    override fun run(arguments: Map<String, Any?>): String { ran.add(arguments); return "ran $name" }
}

class Mod(
    override val capability: Capability?,
    override val promptHint: String,
    override val requirements: List<Requirement> = emptyList(),
    val toolList: List<Tool> = emptyList(),
) : ToolModule {
    var asked = 0
    override fun tools(): List<Tool> { asked++; return toolList }
}

fun main() {
    // ---- capabilities describe themselves
    check("every capability has a label, a blurb and a colour family", Capability.entries.all { it.label.isNotBlank() && it.blurb.isNotBlank() && it.hue in Hue.entries })
    check("the risky ones start switched off", Capability.SCREEN !in Capability.defaults && Capability.REMOTE_TOOLS !in Capability.defaults)

    // ---- requirements
    val screen = Mod(Capability.SCREEN, "- read_screen\n", listOf(Requirement.ACCESSIBILITY), listOf(Echo("read_screen")))
    val reminders = Mod(Capability.REMINDERS, "- set_reminder\n", listOf(Requirement.EXACT_ALARMS), listOf(Echo("set_reminder")))
    val basics = Mod(null, "- time\n", emptyList(), listOf(Echo("get_time")))
    val reg = ToolRegistry(listOf(basics, reminders, screen))
    val all = setOf(Capability.REMINDERS, Capability.SCREEN)
    fun names(t: List<Tool>) = t.map { it.spec.name }
    check("with nothing special granted, a blocking requirement hides that module's tools", names(reg.tools(all) { false }) == listOf("get_time", "set_reminder"))
    check("a requirement that does not block leaves the tools in place", "set_reminder" in names(reg.tools(all) { false }))
    check("once granted, the tools appear", names(reg.tools(all) { true }) == listOf("get_time", "set_reminder", "read_screen"))
    check("the default (nothing to check) offers everything that is on", names(reg.tools(all)) == listOf("get_time", "set_reminder", "read_screen"))
    check("a blocked module is not even asked for its tools", run { screen.asked = 0; reg.tools(all) { false }; screen.asked == 0 })
    check("a module that is switched off stays off whatever is granted", names(reg.tools(setOf(Capability.REMINDERS)) { true }) == listOf("get_time", "set_reminder"))
    val hints = reg.promptHints(all) { false }
    check("a module that is on but missing something gets one explaining line", hints.contains("Screen eye is switched on but cannot be used yet") && hints.contains("Accessibility access") && hints.contains("Tools screen") && !hints.contains("- read_screen"), hints)
    check("a module that is off adds nothing", !reg.promptHints(setOf(Capability.REMINDERS)) { false }.contains("Screen eye"))
    check("when everything is granted the hints are exactly the modules' own lines", reg.promptHints(all) { true } == "- time\n- set_reminder\n- read_screen\n")
    check("the default is exactly the modules' own lines too", reg.promptHints(all) == "- time\n- set_reminder\n- read_screen\n")

    // ---- tools that must ask first
    val risky = Echo("tap_screen", Risk.ASK)
    val safe = Echo("read_screen")
    val actions = Mod(Capability.SCREEN, "", emptyList(), listOf(safe, risky))
    class Recorder(val answer: Boolean) : ToolConfirmer {
        val asked = ArrayList<Triple<String, String, String>>()
        override fun confirm(server: String, tool: String, arguments: String): Boolean { asked.add(Triple(server, tool, arguments)); return answer }
    }
    val yes = Recorder(true)
    val tools = ToolRegistry(listOf(actions)) { yes }.tools(setOf(Capability.SCREEN))
    val tap = tools.first { it.spec.name == "tap_screen" }
    check("only the risky tool is wrapped; a safe one is handed over as it is", tools.first { it.spec.name == "read_screen" } === safe && tap is GuardedTool)
    check("the wrapped tool keeps its spec", tap.spec == risky.spec)
    check("approved: the tool runs, and the question named the module, the tool and the arguments",
        tap.run(mapOf("x" to 10, "y" to 20)) == "ran tap_screen" && yes.asked.single().first == "Screen eye" && yes.asked.single().second == "tap_screen" && JSONObject(yes.asked.single().third).getInt("x") == 10)
    val no = Recorder(false)
    val denied = ToolRegistry(listOf(actions)) { no }.tools(setOf(Capability.SCREEN)).first { it.spec.name == "tap_screen" }
    val before = risky.ran.size
    check("refused: the tool does not run and the model is told why", denied.run(emptyMap()) == "error: the user did not allow this" && risky.ran.size == before)
    val nobody = ToolRegistry(listOf(actions)).tools(setOf(Capability.SCREEN)).first { it.spec.name == "tap_screen" }
    check("nobody to ask: it does not run, and says to open the chat", nobody.run(emptyMap()).let { it.startsWith("error:") && it.contains("open the chat") } && risky.ran.size == before)
    val broken = ToolRegistry(listOf(actions)) { ToolConfirmer { _, _, _ -> throw IllegalStateException("dialog died") } }.tools(setOf(Capability.SCREEN)).first { it.spec.name == "tap_screen" }
    check("a question that fails counts as no", broken.run(emptyMap()) == "error: the user did not allow this" && risky.ran.size == before)
    check("the guard passes the follow-up setting through", GuardedTool(object : Tool { override val spec = ToolSpec("q", "q"); override val needsFollowUp = false; override fun run(arguments: Map<String, Any?>) = "" }, "s") { null }.needsFollowUp == false)

    // ---- what the screen eye keeps private
    check("banking, wallet, password and authenticator apps are blocked", listOf("com.mybank.mobile", "com.google.android.apps.walletnfcrel", "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.google.android.apps.authenticator2", "com.paypal.android.p2pmobile").all { ScreenGuard.blockedReason(it) != null })
    check("ordinary apps are not", listOf("com.google.android.apps.maps", "com.android.chrome", "org.telegram.messenger", "com.whatsapp", "com.android.settings", null).all { ScreenGuard.blockedReason(it) == null })
    check("the user's own list blocks exact packages and prefixes", ScreenGuard.blockedReason("et.example.birrapp", listOf("et.example.birrapp")) != null && ScreenGuard.blockedReason("et.example.pay.x", listOf("et.example.")) != null && ScreenGuard.blockedReason("et.exampleother", listOf("et.example.")) == null)
    check("a blank entry in that list blocks nothing", ScreenGuard.blockedReason("com.whatsapp", listOf("", "  ")) == null)
    check("a real card number is hidden", ScreenGuard.redact("Card 4111 1111 1111 1111 expires 12/29") == "Card (card number hidden) expires 12/29")
    check("a long number that is not a card stays", ScreenGuard.redact("Tracking 4111 1111 1111 1112") == "Tracking 4111 1111 1111 1112")
    check("a phone number and a price stay", ScreenGuard.redact("Call +251 911 234 567 or pay 1,299.00 birr") == "Call +251 911 234 567 or pay 1,299.00 birr")
    check("a code in text about codes is hidden", ScreenGuard.redact("Your verification code is 483920. Do not share it.") == "Your verification code is (hidden). Do not share it.")
    check("a short number in ordinary text stays", ScreenGuard.redact("Order 12345678 has shipped") == "Order 12345678 has shipped")
    check("a PIN is hidden too", ScreenGuard.redact("Enter your PIN 4821 to continue") == "Enter your PIN (hidden) to continue")

    println(if (failures == 0) "\nALL TOOL ARCHITECTURE TESTS PASSED" else "\n$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
