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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.ProactiveEngine
import com.corepack.pet.mochi.MochiRenderer
import kotlin.math.abs

/** Keeps the pet floating over other apps. Drag to move, tap to chat. */
class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.corepack.pet.STOP"
        private const val CHANNEL = "pet_overlay"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private var wm: WindowManager? = null
    private var pet: PetView? = null
    private var health: Thread? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var bubble: BubbleWindow? = null
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
        }
        return START_STICKY
    }

    private fun chatIntent(): Intent =
        Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pet overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val chat = PendingIntent.getActivity(this, 1, chatIntent(), flags)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CorePack pet is running")
            .setContentText("Tap the pet, or this, to chat")
            .setContentIntent(chat)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(stopIcon, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
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
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - width)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - height)
                        manager.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.savePetPos(this, lp.x, lp.y) else openChat()
                    true
                }
                else -> false
            }
        }
        manager.addView(view, lp)
        pet = view
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
