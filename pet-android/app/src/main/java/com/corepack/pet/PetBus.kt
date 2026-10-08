package com.corepack.pet

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { IDLE, QUEUED, THINKING, TALKING, SETTLE, ERROR, OFFLINE, THROTTLED }

/** One shared place that says what the pet should be doing. Safe to call from any thread. */
object PetBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Phase) -> Unit>()

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    fun listen(l: (Phase) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (Phase) -> Unit) {
        listeners.remove(l)
    }

    fun set(p: Phase) {
        main.post {
            phase = p
            for (l in listeners) l(p)
        }
    }

    fun setThenIdle(p: Phase, ms: Long) {
        set(p)
        main.postDelayed({ if (phase == p) set(Phase.IDLE) }, ms)
    }
}
