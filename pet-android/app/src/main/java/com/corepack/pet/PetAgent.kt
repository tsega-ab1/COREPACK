package com.corepack.pet

import android.content.Context
import com.corepack.pet.agent.AgentLoop
import com.corepack.pet.agent.AgentSession
import com.corepack.pet.agent.FactStore
import com.corepack.pet.agent.GatewayModel
import com.corepack.pet.agent.HistoryRestore
import com.corepack.pet.agent.Model
import com.corepack.pet.agent.ModelException
import com.corepack.pet.agent.PacedSink
import com.corepack.pet.agent.Prompt
import com.corepack.pet.agent.ReplySink
import com.corepack.pet.agent.ToolConfirmer
import com.corepack.pet.agent.ToolOutcome
import com.corepack.pet.agent.StreamedText
import com.corepack.pet.agent.ToolModule
import com.corepack.pet.agent.Requirement
import com.corepack.pet.agent.ToolRegistry
import java.io.File
import java.time.ZonedDateTime

/**
 * Runs the agent for the chat screen and keeps the pet's face in step with what is happening.
 * The conversation itself lives in [AgentSession]; this object adds the Android side: settings, the pet's
 * face, the chat screen and the threads.
 */
object PetAgent {
    private const val MAX_MESSAGES = 40

    /** How fast a reply is shown in the chat. The engine can send a whole story in a second; this keeps it readable. */
    private const val REVEAL_CHARS_PER_SECOND = 240

    interface UiListener : ReplySink

    /**
     * How the model and the modules are made for each message. Replace these to plug in another model or
     * more modules; nothing else in this file needs to change.
     */
    @Volatile
    var modelFactory: (Context) -> Model = { app -> GatewayModel(Prefs.baseUrl(app), Prefs.token(app), userKey = Prefs.apiKey(app), userProvider = Prefs.provider(app), userModel = Prefs.model(app)) }

    @Volatile
    var moduleFactory: (Context, FactStore) -> List<ToolModule> = { app, store -> PetModules.builtin(app, store) }

    /**
     * Asks the user before an online tool that is not read-only runs. Until the screen sets one, such tools are
     * not offered to the model at all. It is called on a background thread and may block until the user answers.
     */
    @Volatile
    var toolConfirmer: ToolConfirmer? = null

    private val session = AgentSession(MAX_MESSAGES)
    private var facts: FactStore? = null

    @Volatile
    private var pacer: PacedSink? = null

    /** True from the moment a message is sent until its reply (or failure) has been fully delivered to the chat. */
    @Volatile
    var busy = false
        private set

    @Synchronized
    fun facts(context: Context): FactStore =
        facts ?: FactStore(File(context.applicationContext.filesDir, "facts.txt")).also { facts = it }

    /**
     * Adds something the pet said by itself (a reminder, a nudge) to the chat and, at the start of the next
     * message, to the model's memory of the conversation, so the user can answer it ("snooze 10 minutes").
     * Never blocks: it may be called while a reply is still streaming.
     */
    fun injectAssistant(context: Context, text: String) {
        ChatStore.attach(chatFile(context.applicationContext))
        ChatStore.add("assistant", text)
        ChatStore.save()
        // Before the model's memory has been rebuilt from the chat, the chat itself already holds this message.
        if (restored) session.inject(text)
    }

    private fun chatFile(app: Context) = File(app.filesDir, "chat.json")

    @Volatile
    private var restored = false

    /** Stops the model, and stops showing any text that has not appeared yet. */
    fun cancel() {
        session.cancel()
        pacer?.cancel()
    }

    fun send(context: Context, userText: String, ui: UiListener) {
        val app = context.applicationContext
        ChatStore.attach(chatFile(app))
        if (!restored) {
            // First message since the app started: give the model back the conversation the chat screen still shows.
            restored = true
            val chat = ChatStore.forRequest()
            val earlier = if (chat.lastOrNull()?.first == "user") chat.dropLast(1) else chat   // the last one is this message
            session.restore(HistoryRestore.fromChat(earlier))
        }
        ChatStore.save()
        val store = facts(app)
        val caps = PetStores.capabilities(app)
        val registry = ToolRegistry(moduleFactory(app, store)) { toolConfirmer }
        val granted = { r: Requirement -> Requirements.isGranted(app, r) }
        session.arm(modelFactory(app)) { registry.tools(caps, granted) }
        busy = true

        // Everything the chat hears goes through the pacer; it clears [busy] just before the final event.
        val out = PacedSink(ui, charsPerSecond = REVEAL_CHARS_PER_SECOND, beforeEnd = {
            busy = false
            ChatStore.save()
        })
        pacer = out

        // The reply is shown as it streams in, so the chat is only handed text at the end if none of it streamed.
        val streamed = StreamedText()

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
                streamed.add(delta)
                out.onToken(delta)
            }

            override fun onThinking() {
                talking = false
                streamed.newRound()
                PetBus.set(Phase.THINKING)
            }

            override fun onToolStart(name: String) {
                PetBus.set(Phase.TOOL)
                out.onTool(name)
            }

            override fun onToolDone(name: String, result: String) {
                val outcome = ToolOutcome.of(result)
                out.onToolResult(name, outcome.ok, outcome.note)
            }
        }

        Thread {
            try {
                val reply = session.run(
                    userText,
                    { Prompt.system(store.all(), ZonedDateTime.now(), caps, registry.promptHints(caps, granted)) },
                    listener,
                )
                PetBus.setThenIdle(Phase.SETTLE, 1800L)
                out.onDone(streamed.leftToShow(reply))
            } catch (e: ModelException) {
                when {
                    e.cancelled -> {
                        PetBus.set(Phase.IDLE)
                        out.onDone("")
                    }
                    e.unreachable -> {
                        PetBus.set(Phase.OFFLINE)
                        out.onError("Can't reach the gateway. Is it running on the PC with the phone plugged in?", true)
                    }
                    else -> {
                        PetBus.setThenIdle(Phase.ERROR, 3000L)
                        out.onError(e.message ?: "Something went wrong", false)
                    }
                }
            } catch (e: Exception) {
                PetBus.setThenIdle(Phase.ERROR, 3000L)
                out.onError(e.message ?: "Something went wrong", false)
            }
        }.start()
    }
}
