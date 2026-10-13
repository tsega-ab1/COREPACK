package com.corepack.pet.agent

import java.io.File

/** Things the pet remembers about the user, one per line in a plain text file. */
class FactStore(
    private val file: File,
    private val maxFacts: Int = 60,
    private val maxLength: Int = 200,
) {
    private val facts = ArrayList<String>()

    init {
        if (file.exists()) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { facts.add(it) }
        }
    }

    @Synchronized
    fun all(): List<String> = facts.toList()

    /** Returns true if a new fact was saved. */
    @Synchronized
    fun add(raw: String): Boolean {
        val fact = raw.replace(Regex("\\s+"), " ").trim().take(maxLength)
        if (fact.isEmpty() || facts.any { it.equals(fact, ignoreCase = true) }) return false
        facts.add(fact)
        while (facts.size > maxFacts) facts.removeAt(0)
        save()
        return true
    }

    /** Removes every fact containing [query] (at least 3 characters). Returns how many were removed. */
    @Synchronized
    fun remove(query: String): Int {
        val q = query.trim().lowercase()
        if (q.length < 3) return 0
        val before = facts.size
        facts.removeAll { it.lowercase().contains(q) }
        if (facts.size != before) save()
        return before - facts.size
    }

    @Synchronized
    fun clear() {
        facts.clear()
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(facts.joinToString("\n"))
    }
}
