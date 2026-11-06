package com.corepack.pet.agent

import java.io.File

/** A tiny tab-separated line format for the pet's small stores. Tabs and newlines inside fields are escaped. */
internal object Lines {
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n")

    fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun read(file: File): List<List<String>> =
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }.map { l -> l.split('\t').map { unesc(it) } }

    fun write(file: File, rows: List<List<String>>) {
        file.parentFile?.mkdirs()
        file.writeText(rows.joinToString("\n") { r -> r.joinToString("\t") { esc(it) } })
    }
}

/** The result of looking something up by id ("#3", "3") or by a part of its text. */
sealed class Match<out T> {
    class One<T>(val item: T) : Match<T>()
    class Many<T>(val items: List<T>) : Match<T>()
    object None : Match<Nothing>()
}

private fun idOf(query: String): Long? = query.trim().removePrefix("#").toLongOrNull()

// ------------------------------------------------------------------ reminders

data class Reminder(val id: Long, val dueMs: Long, val text: String, val done: Boolean)

class ReminderStore(private val file: File, private val maxActive: Int = 50) {
    private val items = ArrayList<Reminder>()

    init {
        for (r in Lines.read(file)) {
            val id = r.getOrNull(0)?.toLongOrNull()
            val due = r.getOrNull(1)?.toLongOrNull()
            if (id != null && due != null && r.size >= 4) items.add(Reminder(id, due, r[2], r[3] == "1"))
        }
    }

    @Synchronized
    fun active(): List<Reminder> = items.filter { !it.done }.sortedBy { it.dueMs }

    @Synchronized
    fun get(id: Long): Reminder? = items.firstOrNull { it.id == id }

    /** Returns null when too many reminders are already waiting. */
    @Synchronized
    fun add(dueMs: Long, text: String): Reminder? {
        if (active().size >= maxActive) return null
        val r = Reminder((items.maxOfOrNull { it.id } ?: 0L) + 1, dueMs, text.trim().take(300), false)
        items.add(r)
        save()
        return r
    }

    @Synchronized
    fun markDone(id: Long): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || items[i].done) return false
        items[i] = items[i].copy(done = true)
        val finished = items.filter { it.done }
        if (finished.size > 100) items.removeAll(finished.sortedBy { it.dueMs }.take(finished.size - 100).toSet())
        save()
        return true
    }

    /** Cancels active reminders matching an id or at least 3 characters of text. Returns what was cancelled. */
    @Synchronized
    fun cancel(query: String): List<Reminder> {
        val q = query.trim()
        val hits = idOf(q)?.let { id -> active().filter { it.id == id } }
            ?: if (q.length >= 3) active().filter { it.text.contains(q, ignoreCase = true) } else emptyList()
        hits.forEach { markDone(it.id) }
        return hits
    }

    private fun save() = Lines.write(file, items.map { listOf(it.id.toString(), it.dueMs.toString(), it.text, if (it.done) "1" else "0") })
}

// ------------------------------------------------------------------ tasks

data class Task(val id: Long, val text: String, val due: String, val done: Boolean)

class TaskStore(private val file: File, private val maxOpen: Int = 200) {
    private val items = ArrayList<Task>()

    init {
        for (r in Lines.read(file)) {
            val id = r.getOrNull(0)?.toLongOrNull()
            if (id != null && r.size >= 4) items.add(Task(id, r[1], r[2], r[3] == "1"))
        }
    }

    @Synchronized
    fun list(includeDone: Boolean): List<Task> = items.filter { includeDone || !it.done }

    @Synchronized
    fun add(text: String, due: String): Task? {
        if (items.count { !it.done } >= maxOpen) return null
        val t = Task((items.maxOfOrNull { it.id } ?: 0L) + 1, text.trim().take(300), due.trim().take(60), false)
        items.add(t)
        save()
        return t
    }

    @Synchronized
    fun find(query: String): Match<Task> {
        val q = query.trim()
        val open = items.filter { !it.done }
        idOf(q)?.let { id -> open.firstOrNull { it.id == id }?.let { return Match.One(it) } }
        if (q.length < 3) return Match.None
        val hits = open.filter { it.text.contains(q, ignoreCase = true) }
        return when (hits.size) { 0 -> Match.None; 1 -> Match.One(hits[0]); else -> Match.Many(hits) }
    }

    @Synchronized
    fun complete(id: Long): Boolean {
        val i = items.indexOfFirst { it.id == id && !it.done }
        if (i < 0) return false
        items[i] = items[i].copy(done = true)
        val finished = items.filter { it.done }
        if (finished.size > 200) items.removeAll(finished.take(finished.size - 200).toSet())
        save()
        return true
    }

    private fun save() = Lines.write(file, items.map { listOf(it.id.toString(), it.text, it.due, if (it.done) "1" else "0") })
}

// ------------------------------------------------------------------ notes

data class Note(val id: Long, val createdMs: Long, val title: String, val text: String)

class NoteStore(private val file: File, private val maxNotes: Int = 300) {
    private val items = ArrayList<Note>()

    init {
        for (r in Lines.read(file)) {
            val id = r.getOrNull(0)?.toLongOrNull()
            val at = r.getOrNull(1)?.toLongOrNull()
            if (id != null && at != null && r.size >= 4) items.add(Note(id, at, r[2], r[3]))
        }
    }

    @Synchronized
    fun add(title: String, text: String, nowMs: Long): Note {
        val n = Note((items.maxOfOrNull { it.id } ?: 0L) + 1, nowMs, title.trim().take(80), text.trim().take(2000))
        items.add(n)
        while (items.size > maxNotes) items.removeAt(0)
        save()
        return n
    }

    @Synchronized
    fun recent(limit: Int): List<Note> = items.sortedByDescending { it.createdMs }.take(limit)

    /** Notes containing any of the words of [query], best matches first; blank query returns the newest. */
    @Synchronized
    fun search(query: String, limit: Int = 5): List<Note> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return recent(limit)
        return items.map { n ->
            val hay = (n.title + " " + n.text).lowercase()
            n to words.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.createdMs })
            .take(limit).map { it.first }
    }

    private fun save() = Lines.write(file, items.map { listOf(it.id.toString(), it.createdMs.toString(), it.title, it.text) })
}
