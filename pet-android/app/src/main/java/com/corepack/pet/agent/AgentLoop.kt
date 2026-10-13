package com.corepack.pet.agent

/**
 * Runs one user turn: ask the model, run any tools it calls, feed the results back, repeat until it
 * answers in plain text. Pure Kotlin so it can be unit-tested.
 */
class AgentLoop(
    private val model: Model,
    private val tools: List<Tool>,
    private val maxRounds: Int = 5,
) {
    interface Listener : TurnHooks {
        fun onThinking()
        fun onToolStart(name: String)
        fun onToolDone(name: String, result: String)
    }

    @Volatile
    var cancelled = false

    /**
     * Appends everything that happens (assistant turns, tool calls, tool results) to [history]
     * and returns the final reply text. [system] is sent first on every round but never stored.
     */
    fun run(history: MutableList<AgentMessage>, system: String, listener: Listener): String {
        val specs = tools.map { it.spec }
        var rounds = 0
        while (true) {
            if (cancelled) return ""
            listener.onThinking()
            val toolsAllowed = rounds < maxRounds
            val messages = listOf(AgentMessage("system", system)) + history
            val turn = model.turn(messages, if (toolsAllowed) specs else emptyList(), listener)

            if (turn.toolCalls.isEmpty() || !toolsAllowed) {
                val text = turn.text.ifBlank { "(no reply)" }
                history.add(AgentMessage("assistant", text))
                return text
            }

            history.add(AgentMessage("assistant", turn.text.ifBlank { null }, turn.toolCalls))
            var needsFollowUp = false
            for (call in turn.toolCalls) {
                val tool = tools.firstOrNull { it.spec.name == call.name }
                listener.onToolStart(call.name)
                val result = when {
                    tool == null -> "error: unknown tool '${call.name}'"
                    else -> try {
                        tool.run(call.arguments)
                    } catch (e: Exception) {
                        "error: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (tool == null || tool.needsFollowUp) needsFollowUp = true
                listener.onToolDone(call.name, result)
                history.add(AgentMessage("tool", result, toolCallId = call.id))
            }
            rounds++
            if (!needsFollowUp && turn.text.isNotBlank()) return turn.text
        }
    }
}

/** Keeps the history from growing forever without ever cutting a tool call away from its result. */
object HistoryTrim {
    fun trim(history: MutableList<AgentMessage>, maxMessages: Int) {
        if (history.size <= maxMessages) return
        var cut = history.size - maxMessages
        while (cut < history.size && history[cut].role != "user") cut++
        if (cut >= history.size) return
        repeat(cut) { history.removeAt(0) }
    }
}
