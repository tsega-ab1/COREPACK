package com.corepack.pet.agent

/** One tool the model may call. [parametersJson] is a JSON-schema object as text, or "" for no arguments. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String = "")

/**
 * A tool call requested by the model.
 * [extraContentJson] must be sent back untouched: Gemini 3 / 2.5 put a "thought signature" there
 * and reject the next request if it is missing.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val argumentsJson: String,
    val extraContentJson: String? = null,
)

/** One message in the model's conversation. role is system, user, assistant or tool. */
data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** What the model produced in one round: some text and/or tool calls. */
data class ModelTurn(val text: String, val toolCalls: List<ToolCall>)

class ModelException(
    message: String,
    val unreachable: Boolean = false,
    val cancelled: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Live callbacks while the model is answering. */
interface TurnHooks {
    fun onGatewayEvent(name: String)
    fun onText(delta: String)
}

/** The brain: sends messages (and tool specs) and returns one round. Blocking. */
interface Model {
    fun turn(messages: List<AgentMessage>, tools: List<ToolSpec>, hooks: TurnHooks): ModelTurn
}

interface Tool {
    val spec: ToolSpec

    /** False for fire-and-forget tools (like setting the mood): no extra model round is needed after them. */
    val needsFollowUp: Boolean get() = true

    fun run(arguments: Map<String, Any?>): String
}

enum class PetMood {
    NEUTRAL, HAPPY, SAD, CURIOUS, SLEEPY, EXCITED;

    companion object {
        fun parse(text: String?): PetMood? =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}
