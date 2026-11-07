package com.corepack.pet.agent

/**
 * One group of things the pet can do: its tools, the lines that describe them to the model, and the switch
 * that turns them on. To add an ability, write a module and add it to the list in PetModules.
 */
interface ToolModule {
    /** The user's switch for this module, or null for the basics that are always on. */
    val capability: Capability?

    /** Lines for the system prompt, each ending in a newline. Empty if the tools explain themselves. */
    val promptHint: String

    /** Called only while the module is switched on, so it may open files or look up Android services. */
    fun tools(): List<Tool>
}

/** Holds the modules in order and hands out the tools and prompt lines of whichever are switched on. */
class ToolRegistry(private val modules: List<ToolModule>) {
    private fun active(caps: Set<Capability>): List<ToolModule> = modules.filter { m ->
        val c = m.capability
        c == null || c in caps
    }

    fun tools(caps: Set<Capability>): List<Tool> = active(caps).flatMap { it.tools() }

    fun promptHints(caps: Set<Capability>): String = active(caps).joinToString("") { it.promptHint }
}
