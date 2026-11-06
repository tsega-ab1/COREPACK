package com.corepack.pet

import android.content.Context
import com.corepack.pet.agent.AgentLoop
import com.corepack.pet.agent.AddTaskTool
import com.corepack.pet.agent.AgentMessage
import com.corepack.pet.agent.CancelReminderTool
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.CompleteTaskTool
import com.corepack.pet.agent.FactStore
import com.corepack.pet.agent.ForgetTool
import com.corepack.pet.agent.GatewayModel
import com.corepack.pet.agent.HistoryTrim
import com.corepack.pet.agent.ListRemindersTool
import com.corepack.pet.agent.ListTasksTool
import com.corepack.pet.agent.ModelException
import com.corepack.pet.agent.MoodTool
import com.corepack.pet.agent.Prompt
import com.corepack.pet.agent.RememberTool
import com.corepack.pet.agent.SaveNoteTool
import com.corepack.pet.agent.SearchNotesTool
import com.corepack.pet.agent.SetReminderTool
import com.corepack.pet.agent.TimeTool
import com.corepack.pet.agent.Tool
import com.corepack.pet.agent.UrlHttp
import com.corepack.pet.agent.WeatherTool
import com.corepack.pet.agent.WikiTool
import java.io.File
import java.time.ZonedDateTime

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

    /** The tools the model gets: the basics always, the rest only if the user has switched that group on. */
    private fun buildTools(app: Context, store: FactStore, caps: Set<Capability>): List<Tool> {
        val tools = ArrayList<Tool>()
        tools += TimeTool()
        tools += BatteryTool(app)
        tools += RememberTool(store)
        tools += ForgetTool(store)
        tools += MoodTool { PetBus.setMood(it) }
        if (Capability.REMINDERS in caps) {
            val reminders = PetStores.reminders(app)
            val scheduler = AlarmReminderScheduler(app)
            tools += SetReminderTool(reminders, scheduler)
            tools += ListRemindersTool(reminders)
            tools += CancelReminderTool(reminders, scheduler)
        }
        if (Capability.TASKS_NOTES in caps) {
            val tasks = PetStores.tasks(app)
            val notes = PetStores.notes(app)
            tools += AddTaskTool(tasks)
            tools += ListTasksTool(tasks)
            tools += CompleteTaskTool(tasks)
            tools += SaveNoteTool(notes)
            tools += SearchNotesTool(notes)
        }
        if (Capability.PHONE_ACTIONS in caps) {
            tools += listOf(
                SetAlarmTool(app), SetTimerTool(app), OpenAppTool(app), OpenUrlTool(app),
                OpenMapsTool(app), ComposeSmsTool(app), DialTool(app), FlashlightTool(app),
            )
        }
        if (Capability.LOOKUPS in caps) {
            val http = UrlHttp()
            tools += WeatherTool(http)
            tools += WikiTool(http)
        }
        return tools
    }

    private val pendingInjected = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * Adds something the pet said by itself (a reminder, a nudge) to the chat and, at the start of the next
     * message, to the model's memory of the conversation, so the user can answer it ("snooze 10 minutes").
     * Never blocks: it may be called while a reply is still streaming.
     */
    fun injectAssistant(context: Context, text: String) {
        ChatStore.add("assistant", text)
        pendingInjected.add(text)
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

    fun cancel() {
        loop?.cancelled = true
        model?.cancel()
    }

    fun send(context: Context, userText: String, ui: UiListener) {
        val app = context.applicationContext
        val store = facts(app)
        val gateway = GatewayModel(Prefs.baseUrl(app), Prefs.token(app))
        val caps = PetStores.capabilities(app)
        val tools = buildTools(app, store, caps)
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
                    applyInjected()
                    history.add(AgentMessage("user", userText))
                    HistoryTrim.trim(history, MAX_MESSAGES)
                    agent.run(history, Prompt.system(store.all(), ZonedDateTime.now(), caps), listener)
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
