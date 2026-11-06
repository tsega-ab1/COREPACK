package com.corepack.pet.agent

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schedules a reminder with the system. The phone implementation uses the alarm clock; tests use a fake. */
interface ReminderScheduler {
    fun schedule(r: Reminder)
    fun cancel(id: Long)
}

internal fun numberArg(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

internal fun boolArg(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> when (v.trim().lowercase()) { "true", "yes", "1", "on" -> true; "false", "no", "0", "off" -> false; else -> null }
    else -> null
}

internal fun stringArg(args: Map<String, Any?>, key: String): String = (args[key] as? String)?.trim().orEmpty()

object TimeParse {
    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    /** Reads "2026-10-12T15:30" (or with a space, seconds, or an offset). Plain local times use [zone]. */
    fun parse(text: String, zone: ZoneId): ZonedDateTime? {
        val t = text.trim().replace(' ', 'T')
        return try {
            ZonedDateTime.parse(t)
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(t).atZone(zone)
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun format(ms: Long, zone: ZoneId): String = WHEN.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
}

// ------------------------------------------------------------------ reminders

class SetReminderTool(
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Tool {
    override val spec = ToolSpec(
        "set_reminder",
        "Remind the user of something at a time. The pet will speak up and show a notification when it is due.",
        """{"type":"object","properties":{"text":{"type":"string","description":"What to remind the user about."},"in_minutes":{"type":"number","description":"Minutes from now. Use for relative times like 'in 20 minutes'."},"at":{"type":"string","description":"Local date and time like 2026-10-12T15:30. Use for clock times."}},"required":["text"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val text = stringArg(arguments, "text")
        if (text.isEmpty()) return "error: missing 'text'"
        val now = clock()
        val minutes = numberArg(arguments["in_minutes"])
        val due: ZonedDateTime = when {
            minutes != null -> {
                if (minutes < 1 || minutes > 525_600) return "error: in_minutes must be between 1 and 525600"
                now.plusSeconds((minutes * 60).toLong())
            }
            arguments["at"] is String -> TimeParse.parse(arguments["at"] as String, now.zone)
                ?: return "error: couldn't read the time '${arguments["at"]}'; use a form like 2026-10-12T15:30"
            else -> return "error: give in_minutes or at"
        }
        if (due.toInstant().toEpochMilli() < now.toInstant().toEpochMilli() + 5_000) return "error: that time has already passed"
        if (due.isAfter(now.plusYears(1))) return "error: too far ahead (one year at most)"
        val r = store.add(due.toInstant().toEpochMilli(), text) ?: return "error: too many waiting reminders (50 at most)"
        scheduler.schedule(r)
        return "Reminder #${r.id} set for ${TimeParse.format(r.dueMs, now.zone)}: ${r.text}"
    }
}

class ListRemindersTool(private val store: ReminderStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val spec = ToolSpec("list_reminders", "List the reminders that are still waiting.")

    override fun run(arguments: Map<String, Any?>): String {
        val all = store.active()
        if (all.isEmpty()) return "No reminders waiting."
        return all.joinToString("\n") { "#${it.id} ${TimeParse.format(it.dueMs, zone())} - ${it.text}" }
    }
}

class CancelReminderTool(private val store: ReminderStore, private val scheduler: ReminderScheduler) : Tool {
    override val spec = ToolSpec(
        "cancel_reminder",
        "Cancel a waiting reminder by its number (like 3) or by part of its text.",
        """{"type":"object","properties":{"which":{"type":"string","description":"The reminder number or part of its text."}},"required":["which"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val which = stringArg(arguments, "which")
        if (which.isEmpty()) return "error: missing 'which'"
        val hit = store.cancel(which)
        hit.forEach { scheduler.cancel(it.id) }
        return if (hit.isEmpty()) "No waiting reminder matched." else "Cancelled: " + hit.joinToString("; ") { "#${it.id} ${it.text}" }
    }
}

// ------------------------------------------------------------------ tasks

class AddTaskTool(private val store: TaskStore) : Tool {
    override val spec = ToolSpec(
        "add_task",
        "Add an item to the user's to-do list.",
        """{"type":"object","properties":{"text":{"type":"string","description":"The task."},"due":{"type":"string","description":"Optional due date or time in plain words, like 'tomorrow' or 'Friday'."}},"required":["text"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val text = stringArg(arguments, "text")
        if (text.isEmpty()) return "error: missing 'text'"
        val t = store.add(text, stringArg(arguments, "due")) ?: return "error: the to-do list is full"
        return "Task #${t.id} added."
    }
}

class ListTasksTool(private val store: TaskStore) : Tool {
    override val spec = ToolSpec(
        "list_tasks",
        "Show the user's to-do list.",
        """{"type":"object","properties":{"include_done":{"type":"boolean","description":"Also show finished tasks."}}}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val all = store.list(boolArg(arguments["include_done"]) ?: false)
        if (all.isEmpty()) return "The to-do list is empty."
        return all.joinToString("\n") { "#${it.id} [${if (it.done) "x" else " "}] ${it.text}" + if (it.due.isNotEmpty()) " (due: ${it.due})" else "" }
    }
}

class CompleteTaskTool(private val store: TaskStore) : Tool {
    override val spec = ToolSpec(
        "complete_task",
        "Tick off a task by its number (like 2) or by part of its text.",
        """{"type":"object","properties":{"which":{"type":"string","description":"The task number or part of its text."}},"required":["which"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val which = stringArg(arguments, "which")
        if (which.isEmpty()) return "error: missing 'which'"
        return when (val m = store.find(which)) {
            is Match.One -> { store.complete(m.item.id); "Done: #${m.item.id} ${m.item.text}" }
            is Match.Many -> "More than one task matches; which one? " + m.items.joinToString("; ") { "#${it.id} ${it.text}" }
            Match.None -> "No open task matched."
        }
    }
}

// ------------------------------------------------------------------ notes

class SaveNoteTool(private val store: NoteStore, private val now: () -> Long = { System.currentTimeMillis() }) : Tool {
    override val spec = ToolSpec(
        "save_note",
        "Save a note in the user's notebook: an idea, a list, a snippet, something longer than a one-line fact.",
        """{"type":"object","properties":{"text":{"type":"string","description":"The note."},"title":{"type":"string","description":"A short title."}},"required":["text"]}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val text = stringArg(arguments, "text")
        if (text.isEmpty()) return "error: missing 'text'"
        val title = stringArg(arguments, "title").ifEmpty { text.lineSequence().first().take(40) }
        val n = store.add(title, text, now())
        return "Note #${n.id} saved: ${n.title}"
    }
}

class SearchNotesTool(private val store: NoteStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val spec = ToolSpec(
        "search_notes",
        "Search the user's notebook. With no query, returns the newest notes.",
        """{"type":"object","properties":{"query":{"type":"string","description":"Words to look for."}}}""",
    )

    override fun run(arguments: Map<String, Any?>): String {
        val hits = store.search(stringArg(arguments, "query"))
        if (hits.isEmpty()) return "No notes matched."
        return hits.joinToString("\n---\n") { "#${it.id} ${it.title} (${TimeParse.format(it.createdMs, zone())})\n${it.text.take(600)}" }
    }
}

/** Public wrapper so tools outside this package can read booleans the model may send as text. */
fun boolArgPublic(v: Any?): Boolean? = boolArg(v)
