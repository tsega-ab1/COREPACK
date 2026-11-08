package com.corepack.pet

import android.content.Context
import com.corepack.pet.agent.AddTaskTool
import com.corepack.pet.agent.BuiltinHints
import com.corepack.pet.agent.CancelReminderTool
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.CompleteTaskTool
import com.corepack.pet.agent.FactStore
import com.corepack.pet.agent.HttpToolGateway
import com.corepack.pet.agent.ForgetTool
import com.corepack.pet.agent.ListRemindersTool
import com.corepack.pet.agent.ListTasksTool
import com.corepack.pet.agent.MoodTool
import com.corepack.pet.agent.RememberTool
import com.corepack.pet.agent.RemoteToolsModule
import com.corepack.pet.agent.SaveNoteTool
import com.corepack.pet.agent.SearchNotesTool
import com.corepack.pet.agent.SetReminderTool
import com.corepack.pet.agent.TimeTool
import com.corepack.pet.agent.Tool
import com.corepack.pet.agent.ToolModule
import com.corepack.pet.agent.UrlHttp
import com.corepack.pet.agent.WeatherTool
import com.corepack.pet.agent.WikiTool

/**
 * The built-in modules, in the order the model sees them. To add an ability: write a [ToolModule] below and
 * add it to the list in [builtin]. Nothing else needs to change.
 */
object PetModules {
    // One instance for the whole app, so the list of online tools is remembered between messages.
    private var remote: RemoteToolsModule? = null

    @Synchronized
    private fun remote(app: Context): RemoteToolsModule {
        val appContext = app.applicationContext
        return remote ?: RemoteToolsModule(
            HttpToolGateway({ Prefs.baseUrl(appContext) }, { Prefs.token(appContext) }),
            { PetAgent.toolConfirmer },
        ).also { remote = it }
    }

    fun builtin(app: Context, store: FactStore): List<ToolModule> = listOf(
        CoreModule(app, store),
        RemindersModule(app),
        TasksNotesModule(app),
        PhoneActionsModule(app),
        LookupsModule(),
        remote(app),
    )
}

/** The basics: always on. */
private class CoreModule(private val app: Context, private val store: FactStore) : ToolModule {
    override val capability: Capability? = null
    override val promptHint: String = BuiltinHints.CORE

    override fun tools(): List<Tool> = listOf(
        TimeTool(),
        BatteryTool(app),
        RememberTool(store),
        ForgetTool(store),
        MoodTool { PetBus.setMood(it) },
    )
}

private class RemindersModule(private val app: Context) : ToolModule {
    override val capability: Capability? = Capability.REMINDERS
    override val promptHint: String = BuiltinHints.REMINDERS

    override fun tools(): List<Tool> {
        val reminders = PetStores.reminders(app)
        val scheduler = AlarmReminderScheduler(app)
        return listOf(
            SetReminderTool(reminders, scheduler),
            ListRemindersTool(reminders),
            CancelReminderTool(reminders, scheduler),
        )
    }
}

private class TasksNotesModule(private val app: Context) : ToolModule {
    override val capability: Capability? = Capability.TASKS_NOTES
    override val promptHint: String = BuiltinHints.TASKS_NOTES

    override fun tools(): List<Tool> {
        val tasks = PetStores.tasks(app)
        val notes = PetStores.notes(app)
        return listOf(
            AddTaskTool(tasks),
            ListTasksTool(tasks),
            CompleteTaskTool(tasks),
            SaveNoteTool(notes),
            SearchNotesTool(notes),
        )
    }
}

private class PhoneActionsModule(private val app: Context) : ToolModule {
    override val capability: Capability? = Capability.PHONE_ACTIONS
    override val promptHint: String = BuiltinHints.PHONE_ACTIONS

    override fun tools(): List<Tool> = listOf(
        SetAlarmTool(app),
        SetTimerTool(app),
        OpenAppTool(app),
        OpenUrlTool(app),
        OpenMapsTool(app),
        ComposeSmsTool(app),
        DialTool(app),
        FlashlightTool(app),
    )
}

private class LookupsModule : ToolModule {
    override val capability: Capability? = Capability.LOOKUPS
    override val promptHint: String = BuiltinHints.LOOKUPS

    override fun tools(): List<Tool> {
        val http = UrlHttp()
        return listOf(WeatherTool(http), WikiTool(http))
    }
}
