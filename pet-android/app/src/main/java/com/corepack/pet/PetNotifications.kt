package com.corepack.pet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.corepack.pet.mochi.MochiAnimator
import com.corepack.pet.mochi.MochiRenderer
import com.corepack.pet.mochi.MochiState

/** Notification channels (so each kind can be muted on its own) and the pet's face as a notification picture. */
object PetNotifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_OVERLAY = "pet_overlay"
    const val CHANNEL_HIDDEN = "pet_hidden"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "When something you asked the pet to remind you of is due" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERLAY, "Pet overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the pet floats over your apps" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HIDDEN, "Pet removed", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Lets you bring the pet back after you remove it" }
        )
    }

    /** The pet in [state], drawn by the same code as the floating pet, as a square picture. */
    fun petFace(state: MochiState, px: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val gfx = AndroidGfx()
        gfx.begin(Canvas(bmp))
        MochiRenderer.draw(gfx, MochiAnimator.pose(state, 1f, 0.3f), px.toFloat(), px.toFloat())
        return bmp
    }
}
