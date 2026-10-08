package com.corepack.pet

/** In-memory chat history shared by the chat screen and the network thread. */
object ChatStore {
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

    fun forRequest(): List<Pair<String, String>> = synchronized(messages) {
        messages.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotEmpty() }
            .map { it.role to it.text }
    }
}
