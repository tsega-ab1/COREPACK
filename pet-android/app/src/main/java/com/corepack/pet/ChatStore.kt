package com.corepack.pet

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Chat history shared by the chat screen and the network thread. Kept in memory and saved so it survives a restart. */
object ChatStore {
    private const val KEEP = 100
    private var file: File? = null

    class Msg(val role: String, text: String) {
        @Volatile
        var text: String = text
    }

    private val messages = ArrayList<Msg>()

    fun add(role: String, text: String): Msg {
        val m = Msg(role, text)
        synchronized(messages) { messages.add(m) }
        return m
    }

    fun remove(m: Msg) {
        synchronized(messages) { messages.remove(m) }
    }

    fun snapshot(): List<Msg> = synchronized(messages) { ArrayList(messages) }

    /** Starts saving to [f] and loads what was saved before. Only the first call does anything. */
    fun attach(f: File) {
        synchronized(messages) {
            if (file != null) return
            file = f
            if (messages.isNotEmpty()) return
            try {
                val saved = JSONArray(f.readText())
                for (i in 0 until saved.length()) {
                    val o = saved.getJSONObject(i)
                    messages.add(Msg(o.getString("role"), o.getString("text")))
                }
            } catch (e: Exception) {
                // nothing saved yet, or it could not be read: start with an empty chat
            }
        }
    }

    /** Writes the most recent messages to the file. Cheap enough to call after every turn. */
    @Synchronized
    fun save() {
        val f = file ?: return
        val out = JSONArray()
        for (m in snapshot().filter { it.role != "error" }.takeLast(KEEP)) {
            out.put(JSONObject().put("role", m.role).put("text", m.text))
        }
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(out.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(out.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            // the chat still works; it just will not be there after a restart
        }
    }

    /** Forgets the in-memory chat and stops saving, but leaves the saved file alone: what a restart does. */
    fun detach() {
        synchronized(messages) {
            messages.clear()
            file = null
        }
    }

    /** Forgets the whole conversation, here and on disk. */
    fun clear() {
        synchronized(messages) { messages.clear() }
        file?.delete()
    }

    fun forRequest(): List<Pair<String, String>> = synchronized(messages) {
        messages.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotEmpty() }
            .map { it.role to it.text }
    }
}
