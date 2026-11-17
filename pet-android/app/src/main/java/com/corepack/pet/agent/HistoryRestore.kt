package com.corepack.pet.agent

/** Rebuilds what the model should remember from a chat that was saved before the app was restarted. */
object HistoryRestore {
    /**
     * [chat] is (role, text) pairs, oldest first. Only the user's and the pet's own words are kept, runs of the same
     * speaker are joined, the history starts with the user and does not end with a question that was never answered
     * (the next message follows it), and only the most recent [maxMessages] are kept.
     */
    fun fromChat(chat: List<Pair<String, String>>, maxMessages: Int = 20): List<AgentMessage> {
        val merged = ArrayList<AgentMessage>()
        for ((role, text) in chat) {
            if ((role != "user" && role != "assistant") || text.isBlank()) continue
            val last = merged.lastOrNull()
            if (last != null && last.role == role) {
                merged[merged.size - 1] = last.copy(content = (last.content.orEmpty() + "\n\n" + text).trim())
            } else {
                merged.add(AgentMessage(role, text.trim()))
            }
        }
        while (merged.isNotEmpty() && merged.last().role == "user") merged.removeAt(merged.size - 1)
        var kept: List<AgentMessage> = if (merged.size > maxMessages) merged.subList(merged.size - maxMessages, merged.size) else merged
        while (kept.isNotEmpty() && kept.first().role != "user") kept = kept.drop(1)
        return kept.toList()
    }
}
