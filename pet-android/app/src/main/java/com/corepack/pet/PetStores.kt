package com.corepack.pet

import android.content.Context
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.NoteStore
import com.corepack.pet.agent.ReminderStore
import com.corepack.pet.agent.TaskStore
import java.io.File

/** The pet's small stores (reminders, tasks, notes) and the on/off switches for what it may do. */
object PetStores {
    private var reminders: ReminderStore? = null
    private var tasks: TaskStore? = null
    private var notes: NoteStore? = null

    private fun file(c: Context, name: String) = File(c.applicationContext.filesDir, name)

    @Synchronized
    fun reminders(c: Context): ReminderStore = reminders ?: ReminderStore(file(c, "reminders.tsv")).also { reminders = it }

    @Synchronized
    fun tasks(c: Context): TaskStore = tasks ?: TaskStore(file(c, "tasks.tsv")).also { tasks = it }

    @Synchronized
    fun notes(c: Context): NoteStore = notes ?: NoteStore(file(c, "notes.tsv")).also { notes = it }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("corepack_pet_caps", Context.MODE_PRIVATE)

    fun capabilities(c: Context): Set<Capability> = Capability.parse(prefs(c).getString("enabled", null))

    fun setCapability(c: Context, cap: Capability, on: Boolean) {
        val now = capabilities(c).toMutableSet()
        if (on) now.add(cap) else now.remove(cap)
        prefs(c).edit().putString("enabled", Capability.serialize(now)).apply()
    }
}
