package com.corepack.pet.agent

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One conversation with the pet: the message history, things the pet said on its own (reminders) that are
 * waiting to join it, and the turn that is running. Pure Kotlin, so it can be tested without a phone.
 * The Android side (PetAgent) chooses the model and tools for each message and shows the result.
 */
class AgentSession(private val maxMessages: Int = 40) {
    private val history = ArrayList<AgentMessage>()
    private val pendingInjected = ConcurrentLinkedQueue<String>()

    @Volatile
    private var loop: AgentLoop? = null

    @Volatile
    private var model: Model? = null

    @Volatile
    private var toolsProvider: (() -> List<Tool>)? = null

    @Volatile
    private var cancelRequested = false

    /** Something the pet said by itself. It joins the history at the start of the next message. Never blocks. */
    fun inject(text: String) {
        pendingInjected.add(text)
    }

    /** A copy of the history, for tests and for showing it. */
    fun snapshot(): List<AgentMessage> = synchronized(history) { history.toList() }

    /**
     * Gets a turn ready and remembers which model and loop are in use, so [cancel] can stop them. Call it on
     * the thread that handles the tap, before [run] starts on another thread.
     */
    fun arm(model: Model, tools: List<Tool>) {
        arm(model) { tools }
    }

    /**
     * Like [arm], but the tools are looked up when [run] starts, on the thread that runs the turn. That is where
     * tools that fetch their list over the network belong; the thread that handles the tap must not wait for a network.
     */
    fun arm(model: Model, tools: () -> List<Tool>) {
        this.model = model
        this.toolsProvider = tools
        this.loop = null
        this.cancelRequested = false
    }

    /**
     * Runs one user message to its final reply. Blocks. [system] is read once the history is locked, so it
     * sees the latest saved facts. If anything fails, the half-finished turn is removed from the history and
     * the exception is thrown on.
     */
    fun run(userText: String, system: () -> String, listener: AgentLoop.Listener): String {
        val provider = toolsProvider
        val armed = model
        if (provider == null || armed == null) throw IllegalStateException("arm() must be called before run()")
        val agent = AgentLoop(armed, provider())
        loop = agent
        if (cancelRequested) agent.cancelled = true     // Stop was pressed before the loop existed
        try {
            return synchronized(history) {
                applyInjected()
                history.add(AgentMessage("user", userText))
                HistoryTrim.trim(history, maxMessages)
                agent.run(history, system(), listener)
            }
        } catch (e: Exception) {
            synchronized(history) { dropUnfinishedTurn() }
            throw e
        }
    }

    fun cancel() {
        cancelRequested = true
        loop?.cancelled = true
        model?.cancel()
    }

    /** Call with the history lock held. Keeps user/assistant alternation by folding into the last plain reply. */
    private fun applyInjected() {
        while (true) {
            val text = pendingInjected.poll() ?: return
            val last = history.lastOrNull()
            if (last != null && last.role == "assistant" && last.toolCalls.isEmpty()) {
                history[history.size - 1] = last.copy(content = (last.content.orEmpty() + "\n\n" + text).trim())
            } else {
                history.add(AgentMessage("assistant", text))
            }
        }
    }

    /** After a failure, remove the half-finished turn so the next message starts from a clean state. */
    private fun dropUnfinishedTurn() {
        val lastUser = history.indexOfLast { it.role == "user" }
        if (lastUser >= 0) {
            while (history.size > lastUser) history.removeAt(history.size - 1)
        }
    }
}
