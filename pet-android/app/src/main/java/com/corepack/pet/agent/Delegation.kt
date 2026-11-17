package com.corepack.pet.agent

import org.json.JSONArray
import org.json.JSONObject

/** A named engine the gateway offers, with what it is good for. */
data class EngineInfo(val name: String, val description: String)

/** A tool that can be stopped from outside while it is running (the Stop button). */
interface CancellableTool {
    fun cancel()
}

/**
 * Lets the pet's model hand one self-contained task to another model and wait for the answer. The specialist gets
 * only the task text: no tools and no memory of the conversation. So it can only answer, never act, and it cannot
 * hand the task on again.
 */
class DelegateTool(
    private val engines: List<EngineInfo>,
    private val modelFor: (String) -> Model,
    private val maxChars: Int = 3000,
) : Tool, CancellableTool {
    override val spec: ToolSpec = ToolSpec(NAME, describe(engines), schema(engines))

    @Volatile
    private var loop: AgentLoop? = null

    @Volatile
    private var model: Model? = null

    override fun run(arguments: Map<String, Any?>): String {
        val engine = arguments["engine"]?.toString()?.trim().orEmpty()
        val task = arguments["task"]?.toString()?.trim().orEmpty()
        if (task.isEmpty()) return "error: say what the specialist should do"
        if (engines.none { it.name == engine }) {
            return "error: there is no engine called '$engine'. Choose one of: " + engines.joinToString(", ") { it.name }
        }
        val m = modelFor(engine)
        val l = AgentLoop(m, emptyList())
        model = m
        loop = l
        return try {
            val answer = l.run(arrayListOf(AgentMessage("user", task)), SYSTEM, Silent).trim()
            when {
                l.cancelled -> "error: cancelled"
                answer.isEmpty() || answer == NO_REPLY -> "error: the specialist gave no answer"
                answer.length > maxChars -> answer.take(maxChars) + "\n[cut: ${answer.length - maxChars} more characters]"
                else -> answer
            }
        } catch (e: ModelException) {
            if (e.cancelled) "error: cancelled" else "error: the specialist failed: ${e.message}"
        } finally {
            loop = null
            model = null
        }
    }

    override fun cancel() {
        loop?.cancelled = true
        model?.cancel()
    }

    private object Silent : AgentLoop.Listener {
        override fun onGatewayEvent(name: String) {}
        override fun onText(delta: String) {}
        override fun onThinking() {}
        override fun onToolStart(name: String) {}
        override fun onToolDone(name: String, result: String) {}
    }

    companion object {
        const val NAME = "ask_specialist"

        /** What the agent loop puts in place of an empty reply. */
        private const val NO_REPLY = "(no reply)"

        private const val SYSTEM = "You are a specialist model helping another assistant that is talking to a user. " +
            "Do the task you are given and answer directly and concisely. You cannot ask questions back: if something " +
            "is unclear, say what you assumed and carry on."

        private fun describe(engines: List<EngineInfo>): String =
            "Hand one self-contained task to another AI model and wait for its answer. Use it when another model suits " +
                "the job better, for example a quick one for a short summary or a careful one for hard reasoning. " +
                "The specialist cannot see this conversation and cannot use tools, so put everything it needs in the task. " +
                "Engines: " + engines.joinToString("; ") { if (it.description.isBlank()) it.name else "${it.name}: ${it.description}" }

        private fun schema(engines: List<EngineInfo>): String {
            val props = JSONObject()
                .put("engine", JSONObject().put("type", "string").put("enum", JSONArray(engines.map { it.name })).put("description", "Which engine to ask"))
                .put("task", JSONObject().put("type", "string").put("description", "The complete task, with all the information it needs"))
            return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(listOf("engine", "task"))).toString()
        }
    }
}
