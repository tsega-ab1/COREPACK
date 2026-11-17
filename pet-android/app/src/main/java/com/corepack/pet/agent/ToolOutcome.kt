package com.corepack.pet.agent

/**
 * Whether the result of a tool means it worked, and a short note the chat can show next to it. By convention a
 * tool that fails answers with a text that starts with "error:"; a tool the user turned down says so.
 */
data class ToolOutcome(val ok: Boolean, val note: String) {
    companion object {
        private const val ERROR = "error:"
        private const val REFUSED = "The user did not allow this"

        fun of(result: String): ToolOutcome {
            val text = result.trim()
            return when {
                text.startsWith(REFUSED, ignoreCase = true) -> ToolOutcome(false, "you did not allow it")
                text.startsWith(ERROR, ignoreCase = true) -> {
                    val reason = text.substring(ERROR.length).trim()
                    ToolOutcome(false, if (reason.contains("did not allow", ignoreCase = true)) "you did not allow it" else reason.take(120))
                }
                else -> ToolOutcome(true, "")
            }
        }
    }
}
