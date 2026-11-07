package com.corepack.pet.agent

/**
 * Remembers what the chat was already shown while the current model round streamed, so the final reply is not
 * shown a second time. This happens when the model writes its reply and calls the mood tool in the same turn:
 * the text streams in first, and the agent loop then returns that same text as the reply once the tool has run.
 */
class StreamedText {
    private val round = StringBuilder()

    /** A new model round is starting. */
    fun newRound() {
        round.setLength(0)
    }

    fun add(delta: String) {
        round.append(delta)
    }

    /** What the chat still has to be told when the turn ends: nothing if the reply already streamed in. */
    fun leftToShow(reply: String): String =
        if (reply.isNotBlank() && reply.trim() == round.toString().trim()) "" else reply
}
