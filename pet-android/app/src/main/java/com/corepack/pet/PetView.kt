package com.corepack.pet

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.corepack.pet.look.PetMap
import com.corepack.pet.look.PetPlayer
import com.corepack.pet.look.PetRenderer
import com.corepack.pet.look.PetState

/**
 * The pet. What it does comes from [PetBus] (phase and mood); how it looks comes from the CorePack Pet
 * character in the `look` package. This view only keeps time and hands frames to the renderer.
 */
class PetView(context: Context) : View(context) {
    private val player = PetPlayer()
    private val gfx = AndroidGfx()
    private var greeted = false

    private val listener: (Phase) -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PetBus.listen(listener)
        if (!greeted) {
            greeted = true
            player.playOnce(PetState.GREETING, SystemClock.uptimeMillis(), GREETING_MS)
        }
        invalidate()
    }

    /** Waves hello again (used when the pet wakes up). */
    fun greet() {
        player.playOnce(PetState.GREETING, SystemClock.uptimeMillis(), GREETING_MS)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        PetBus.unlisten(listener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        player.setBase(PetMap.stateFor(PetBus.phase, PetBus.mood), now)
        gfx.begin(canvas)
        PetRenderer.draw(gfx, player.frame(now), width.toFloat(), height.toFloat())
        postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val FRAME_MS = 33L
        const val GREETING_MS = 2600L
    }
}
