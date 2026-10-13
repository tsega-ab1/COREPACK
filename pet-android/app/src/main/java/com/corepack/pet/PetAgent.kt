package com.corepack.pet

import android.content.Context
import com.corepack.pet.agent.AgentLoop
import com.corepack.pet.agent.AgentMessage
import com.corepack.pet.agent.FactStore
import com.corepack.pet.agent.ForgetTool
import com.corepack.pet.agent.GatewayModel
import com.corepack.pet.agent.HistoryTrim
import com.corepack.pet.agent.ModelException
import com.corepack.pet.agent.MoodTool
import com.corepack.pet.agent.Prompt
import com.corepack.pet.agent.RememberTool
import com.corepack.pet.agent.TimeTool
import java.io.File

/** Runs the agent for the chat screen and keeps the pet's face in step with what is happening. */
object PetAgent {
    private const val MAX_MESSAGES = 40

    interface UiListener {
        fun onTool(name: String)
        fun onToken(text: String)
        fun onDone(text: String)
        fun onError(message: String, unreachable: Boolean)
    }

    private val history = ArrayList<AgentMessage>()
    private var facts: FactStore? = null

    /** True from the moment a message is sent until its reply (or failure) is delivered. */
    @Volatile
    var busy = false
        private set

    @Volatile
    private var model: GatewayModel? = null

    @Volatile
    private var loop: AgentLoop? = null

    @Synchronized
    fun facts(context: Context): FactStore =
        facts ?: FactStore(File(context.applicationContext.filesDir, "facts.txt")).also { facts = it }

    fun cancel() {
        loop?.cancelled = true
        model?.cancel()
    }

    fun send(context: Context, userText: String, ui: UiListener) {
        val app = context.applicationContext
        val store = facts(app)
        val gateway = GatewayModel(Prefs.baseUrl(app), Prefs.token(app))
        val tools = listOf(
            TimeTool(),
            BatteryTool(app),
            RememberTool(store),
            ForgetTool(store),
            MoodTool { PetBus.setMood(it) },
        )
        val agent = AgentLoop(gateway, tools)
        model = gateway
        loop = agent
        busy = true

        val listener = object : AgentLoop.Listener {
            private var talking = false

            override fun onGatewayEvent(name: String) {
                when (name) {
                    "queued" -> PetBus.set(Phase.QUEUED)
                    "upstream_start" -> PetBus.set(Phase.THINKING)
                    "throttle" -> PetBus.set(Phase.THROTTLED)
                }
            }

            override fun onText(delta: String) {
                if (!talking) {
                    talking = true
                    PetBus.set(Phase.TALKING)
                }
                ui.onToken(delta)
            }

            override fun onThinking() {
                talking = false
                PetBus.set(Phase.THINKING)
            }

            override fun onToolStart(name: String) {
                PetBus.set(Phase.TOOL)
                ui.onTool(name)
            }

            override fun onToolDone(name: String, result: String) = Unit
        }

        Thread {
            try {
                val reply = synchronized(history) {
                    history.add(AgentMessage("user", userText))
                    HistoryTrim.trim(history, MAX_MESSAGES)
                    agent.run(history, Prompt.system(store.all()), listener)
                }
                PetBus.setThenIdle(Phase.SETTLE, 1800L)
                busy = false
                ui.onDone(reply)
            } catch (e: ModelException) {
                synchronized(history) { dropUnfinishedTurn() }
                busy = false
                when {
                    e.cancelled -> {
                        PetBus.set(Phase.IDLE)
                        ui.onDone("")
                    }
                    e.unreachable -> {
                        PetBus.set(Phase.OFFLINE)
                        ui.onError("Can't reach the gateway. Is it running on the PC with the phone plugged in?", true)
                    }
                    else -> {
                        PetBus.setThenIdle(Phase.ERROR, 3000L)
                        ui.onError(e.message ?: "Something went wrong", false)
                    }
                }
            } catch (e: Exception) {
                synchronized(history) { dropUnfinishedTurn() }
                busy = false
                PetBus.setThenIdle(Phase.ERROR, 3000L)
                ui.onError(e.message ?: "Something went wrong", false)
            }
        }.start()
    }

    /** After a failure, remove the half-finished turn so the next message starts from a clean state. */
    private fun dropUnfinishedTurn() {
        val lastUser = history.indexOfLast { it.role == "user" }
        if (lastUser >= 0) {
            while (history.size > lastUser) history.removeAt(history.size - 1)
        }
    }
}
