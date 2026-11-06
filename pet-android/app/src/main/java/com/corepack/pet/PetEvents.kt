package com.corepack.pet

import java.util.concurrent.CopyOnWriteArrayList

/** Lets any part of the app make the pet say something. The overlay listens and shows a speech bubble. */
object PetEvents {
    fun interface Listener {
        fun onSay(text: String, urgent: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun remove(l: Listener) {
        listeners.remove(l)
    }

    /** [urgent] makes the pet look alert and keeps the bubble up longer. Safe from any thread. */
    fun say(text: String, urgent: Boolean = false) {
        for (l in listeners) runCatching { l.onSay(text, urgent) }
    }
}
