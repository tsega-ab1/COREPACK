package com.corepack.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.ProactiveEngine
import com.corepack.pet.mochi.MochiRenderer
import kotlin.math.abs

/** Keeps the pet floating over other apps. Drag to move, tap to chat, long-press for the menu. */
class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.corepack.pet.STOP"
        const val ACTION_WAKE = "com.corepack.pet.WAKE"
        private const val CHANNEL = "pet_overlay"
        private const val CHANNEL_HIDDEN = "pet_hidden"
        private const val NOTIF_ID = 1
        private const val NOTIF_RESTORE = 2
        private const val SLEEP_MS = 60 * 60 * 1000L

        @Volatile
        var running = false
    }

    private var wm: WindowManager? = null
    private var pet: PetView? = null
    private var health: Thread? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var bubble: BubbleWindow? = null
    private var menu: PetMenu? = null
    private var sleeping = false
    private val wakeUp = Runnable { wake() }
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private val nudges = ProactiveEngine()

    /** Something wants the pet to speak: show a bubble, and look alert if it is urgent. */
    private val sayListener = PetEvents.Listener { text, urgent -> main.post { say(text, urgent) } }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Capability.NUDGES !in PetStores.capabilities(context)) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            nudges.onBattery(level * 100 / scale, charging)?.let { PetEvents.say(it.text, urgent = false) }
        }
    }

    private fun say(text: String, urgent: Boolean) {
        val manager = wm ?: return
        val lp = petParams ?: return
        if (sleeping) return
        if (urgent) PetBus.setThenIdle(Phase.ALERT, 15_000L)
        val b = bubble ?: BubbleWindow(this, manager).also { bubble = it }
        try {
            b.show(text, Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height), if (urgent) 15_000L else 8_000L) { openChat() }
        } catch (e: Exception) {
            // The overlay permission may have been withdrawn; the notification still reaches the user.
        }
    }

    private fun startListening() {
        if (listening) return
        listening = true
        PetEvents.add(sayListener)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(batteryReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WAKE && pet != null) {
            wake()
            return START_STICKY
        }
        startForegroundCompat()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pet == null) {
            addOverlay()
            startHealth()
            startListening()
            running = true
        } else if (sleeping) {
            wake()
        }
        return START_STICKY
    }

    private fun chatIntent(): Intent =
        Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val chat = PendingIntent.getActivity(this, 1, chatIntent(), flags)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (sleeping) "CorePack pet is resting" else "CorePack pet is running")
            .setContentText(if (sleeping) "Back in an hour, or tap Wake" else "Tap the pet, or this, to chat")
            .setContentIntent(chat)
            .setOngoing(true)
        if (sleeping) {
            val wakePi = PendingIntent.getService(
                this, 4, Intent(this, OverlayService::class.java).setAction(ACTION_WAKE), flags
            )
            val wakeIcon = Icon.createWithResource(this, android.R.drawable.ic_media_play)
            b.addAction(Notification.Action.Builder(wakeIcon, "Wake", wakePi).build())
        }
        b.addAction(Notification.Action.Builder(stopIcon, "Stop", stop).build())
        return b.build()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pet overlay", NotificationManager.IMPORTANCE_LOW)
        )
        nm.cancel(NOTIF_RESTORE)
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun addOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val dm = resources.displayMetrics
        // The window has the character's own proportions, so nothing is cropped or stretched.
        val width = (Prefs.petWidthDp(this) * dm.density).toInt()
        val height = (width / MochiRenderer.ASPECT).toInt()
        val lp = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val savedX = Prefs.petX(this)
        val savedY = Prefs.petY(this)
        lp.x = if (savedX >= 0) savedX else dm.widthPixels - width - (16 * dm.density).toInt()
        lp.y = if (savedY >= 0) savedY else (dm.heightPixels * 0.55f).toInt()

        petParams = lp
        val view = PetView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        var closedMenu = false
        val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu()
        }
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressed = false
                    // A tap on the pet while the menu is open only closes the menu.
                    closedMenu = menu?.isShowing == true
                    menu?.dismiss()
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        main.removeCallbacks(longPress)
                        menu?.dismiss()
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - width)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - height)
                        manager.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) Prefs.savePetPos(this, lp.x, lp.y) else if (!longPressed && !closedMenu) openChat()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
        manager.addView(view, lp)
        pet = view
    }

    private fun showMenu() {
        val manager = wm ?: return
        val lp = petParams ?: return
        bubble?.dismiss()
        val m = menu ?: PetMenu(this, manager).also { menu = it }
        try {
            m.show(
                listOf(
                    PetMenu.Item("Chat") { openChat() },
                    PetMenu.Item("Sleep for 1 hour") { sleepPet() },
                    PetMenu.Item("Open app") { openApp() },
                    PetMenu.Item("Remove pet", destructive = true) { removePet() },
                ),
                Rect(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height),
            )
        } catch (e: Exception) {
            // The overlay permission may have been withdrawn.
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Open the app from your launcher", Toast.LENGTH_SHORT).show()
        }
    }

    /** Hides the pet for an hour. The notification has a Wake button. */
    private fun sleepPet() {
        val v = pet ?: return
        if (sleeping) return
        sleeping = true
        bubble?.dismiss()
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
            // already removed
        }
        main.postDelayed(wakeUp, SLEEP_MS)
        refreshNotification()
        Toast.makeText(this, "Pet is resting for an hour. Wake it from the notification.", Toast.LENGTH_LONG).show()
    }

    private fun wake() {
        if (!sleeping) return
        main.removeCallbacks(wakeUp)
        sleeping = false
        val v = pet
        val lp = petParams
        if (v != null && lp != null) {
            try {
                wm?.addView(v, lp)
                v.greet()
            } catch (e: Exception) {
                // overlay permission withdrawn
            }
        }
        refreshNotification()
    }

    /** Stops the pet, and leaves a notification that brings it back with one tap. */
    private fun removePet() {
        menu?.dismissNow()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HIDDEN, "Pet removed", NotificationManager.IMPORTANCE_LOW)
        )
        val start = PendingIntent.getForegroundService(
            this, 3, Intent(this, OverlayService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            NOTIF_RESTORE,
            Notification.Builder(this, CHANNEL_HIDDEN)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pet removed")
                .setContentText("Tap to bring it back")
                .setContentIntent(start)
                .setAutoCancel(true)
                .build(),
        )
        Toast.makeText(this, "Pet removed. Bring it back from the notification.", Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openChat() {
        if (ChatActivity.visible) return
        try {
            startActivity(chatIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Open the chat from the notification", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startHealth() {
        health = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val ok = EngineClient.health(Prefs.baseUrl(this))
                val p = PetBus.phase
                if (ok && p == Phase.OFFLINE) PetBus.set(Phase.IDLE)
                if (!ok && (p == Phase.IDLE || p == Phase.OFFLINE)) PetBus.set(Phase.OFFLINE)
                try {
                    Thread.sleep(8000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun onDestroy() {
        health?.interrupt()
        if (listening) {
            listening = false
            PetEvents.remove(sayListener)
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                // not registered
            }
        }
        bubble?.dismiss()
        bubble = null
        menu?.dismissNow()
        menu = null
        main.removeCallbacks(wakeUp)
        pet?.let { v ->
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        pet = null
        running = false
        super.onDestroy()
    }
}
