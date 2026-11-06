package com.corepack.pet

import android.os.Handler
import android.os.Looper
import com.corepack.pet.agent.PetMood
import java.util.concurrent.CopyOnWriteArrayList

/** One shared place that says what the pet is doing and feeling. Safe to call from any thread. */
object PetBus {
    /** A busy phase that never gets a follow-up falls back to idle after this long. */
    private const val BUSY_TIMEOUT_MS = 120_000L

    /** A mood set by the model fades back to neutral after this long. */
    private const val MOOD_TIMEOUT_MS = 300_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Phase) -> Unit>()
    private var stamp = 0
    private var moodStamp = 0

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var mood: PetMood = PetMood.NEUTRAL
        private set

    fun listen(l: (Phase) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (Phase) -> Unit) {
        listeners.remove(l)
    }

    fun set(p: Phase) = show(p, if (p == Phase.IDLE) 0L else BUSY_TIMEOUT_MS)

    fun setThenIdle(p: Phase, holdMs: Long) = show(p, holdMs)

    fun setMood(m: PetMood) {
        main.post {
            moodStamp++
            val mine = moodStamp
            mood = m
            if (m != PetMood.NEUTRAL) {
                main.postDelayed({ if (moodStamp == mine) mood = PetMood.NEUTRAL }, MOOD_TIMEOUT_MS)
            }
        }
    }

    private fun show(p: Phase, holdMs: Long) {
        main.post {
            stamp++
            val mine = stamp
            apply(p)
            if (holdMs > 0L) {
                main.postDelayed({ if (stamp == mine) apply(Phase.IDLE) }, holdMs)
            }
        }
    }

    private fun apply(p: Phase) {
        phase = p
        for (l in listeners) l(p)
    }
}
