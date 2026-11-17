package com.corepack.pet.agent

import org.json.JSONObject

/**
 * Something Android must allow before a module's tools can work. The module only lists it; the app checks and
 * explains it (see Requirements in the app). [blocksTools] false means the tools still work, only worse.
 */
enum class Requirement(val label: String, val why: String, val blocksTools: Boolean) {
    ACCESSIBILITY("Accessibility access", "Android only lets an app read the screen after you switch it on in Settings > Accessibility", true),
    EXACT_ALARMS("Exact alarms", "Without it a reminder can arrive a few minutes late", false),
}

/**
 * One group of things the pet can do: its tools, the lines that describe them to the model, the switch that turns
 * them on, and what Android has to allow first. To add an ability, write a module and add it to the list in PetModules.
 */
interface ToolModule {
    /** The user's switch for this module, or null for the basics that are always on. */
    val capability: Capability?

    /** Lines for the system prompt, each ending in a newline. Empty if the tools explain themselves. */
    val promptHint: String

    /** What Android has to allow for these tools. The Tools screen shows each one with a button to grant it. */
    val requirements: List<Requirement> get() = emptyList()

    /** Called only while the module is switched on and its requirements are met, so it may use Android services. */
    fun tools(): List<Tool>
}

/**
 * Runs a [Risk.ASK] tool only after the user says yes. With nobody to ask, or a no, the model gets an error it can
 * report. [source] is what the question says the tool belongs to.
 */
class GuardedTool(private val inner: Tool, private val source: String, private val confirmer: () -> ToolConfirmer?) : Tool {
    override val spec = inner.spec
    override val needsFollowUp: Boolean get() = inner.needsFollowUp

    override fun run(arguments: Map<String, Any?>): String {
        val ask = confirmer()
            ?: return "error: this needs the user's approval and there is no way to ask right now. Tell them to open the chat and try again."
        val allowed = try {
            ask.confirm(source, inner.spec.name, JSONObject(arguments).toString())
        } catch (e: Exception) {
            false
        }
        return if (allowed) inner.run(arguments) else "error: the user did not allow this"
    }
}

/** Holds the modules in order and hands out the tools and prompt lines of whichever are switched on and usable. */
class ToolRegistry(private val modules: List<ToolModule>, private val confirmer: () -> ToolConfirmer? = { null }) {
    private fun enabled(caps: Set<Capability>): List<ToolModule> = modules.filter { m ->
        val c = m.capability
        c == null || c in caps
    }

    private fun blockedBy(m: ToolModule, granted: (Requirement) -> Boolean): List<Requirement> =
        m.requirements.filter { it.blocksTools && !granted(it) }

    /** Tools of the modules that are on and have what they need. Tools that must ask first are wrapped to do so. */
    fun tools(caps: Set<Capability>, granted: (Requirement) -> Boolean = { true }): List<Tool> =
        enabled(caps).filter { blockedBy(it, granted).isEmpty() }.flatMap { m ->
            val source = m.capability?.label ?: "The pet"
            m.tools().map { if (it.risk == Risk.ASK) GuardedTool(it, source, confirmer) else it }
        }

    /**
     * The modules' own lines, plus one line for each module that is on but missing something, so the pet can tell the
     * user what to grant instead of saying it cannot do that.
     */
    fun promptHints(caps: Set<Capability>, granted: (Requirement) -> Boolean = { true }): String {
        val on = enabled(caps)
        val ready = on.filter { blockedBy(it, granted).isEmpty() }.joinToString("") { it.promptHint }
        val waiting = on.mapNotNull { m ->
            val missing = blockedBy(m, granted)
            if (missing.isEmpty()) null
            else "- ${m.capability?.label ?: "A tool"} is switched on but cannot be used yet: it needs ${missing.joinToString(", ") { it.label }}. " +
                "If the user asks for it, tell them to grant that on the Tools screen.\n"
        }.joinToString("")
        return ready + waiting
    }
}
