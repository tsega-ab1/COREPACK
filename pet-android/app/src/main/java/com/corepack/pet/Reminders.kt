package com.corepack.pet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import com.corepack.pet.agent.Reminder
import com.corepack.pet.agent.ReminderScheduler
import com.corepack.pet.mochi.MochiState

/**
 * Schedules reminders with the system alarm clock, which fires on the minute even in Doze. From Android 12 that
 * needs the "Alarms & reminders" permission; without it, an approximate alarm is used so the reminder still comes.
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun fire(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun isExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun schedule(r: Reminder) {
        if (isExact()) {
            try {
                val show = PendingIntent.getActivity(
                    context, 7, Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(r.dueMs, show), fire(r.id))
                return
            } catch (e: SecurityException) {
                // The permission went away between the check and the call: use the approximate alarm below.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs, fire(r.id))
    }

    override fun cancel(id: Long) {
        alarms.cancel(fire(id))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        when (intent.action) {
            ACTION_FIRE -> Reminders.fire(app, id)
            ACTION_SNOOZE -> Reminders.snooze(app, id)
            ACTION_DONE -> Reminders.dismiss(app, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.corepack.pet.REMINDER_FIRE"
        const val ACTION_SNOOZE = "com.corepack.pet.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.corepack.pet.REMINDER_DONE"
        const val EXTRA_ID = "id"
    }
}

/** Alarms do not survive a reboot or an app update, so they are set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rescheduleAll(context.applicationContext)
        }
    }
}

object Reminders {
    private const val SNOOZE_MS = 10 * 60 * 1000L

    /** A reminder is due: tell the chat, show a notification, and make the pet speak up. */
    fun fire(app: Context, id: Long) {
        val store = PetStores.reminders(app)
        val r = store.get(id) ?: return
        if (r.done) return
        store.markDone(id)
        val text = "Reminder: ${r.text}"
        PetAgent.injectAssistant(app, text)
        notify(app, "Hey, it's time", r.text, id.toInt(), MochiState.HAPPY, actions = true)
        PetEvents.say(text, urgent = true)
    }

    fun rescheduleAll(app: Context) {
        val store = PetStores.reminders(app)
        val scheduler = AlarmReminderScheduler(app)
        val now = System.currentTimeMillis()
        for (r in store.active()) {
            if (r.dueMs > now) {
                scheduler.schedule(r)
            } else {
                store.markDone(r.id)
                notify(app, "You missed this one", r.text, r.id.toInt(), MochiState.SAD, actions = false)
            }
        }
    }

    /** Snooze: the same reminder again in ten minutes. */
    fun snooze(app: Context, id: Long) {
        val r = PetStores.reminders(app).get(id)
        cancelNotification(app, id)
        if (r == null) return
        val next = PetStores.reminders(app).add(System.currentTimeMillis() + SNOOZE_MS, r.text)
        if (next == null) {
            Toast.makeText(app, "Too many reminders waiting to snooze this one", Toast.LENGTH_LONG).show()
            return
        }
        AlarmReminderScheduler(app).schedule(next)
        Toast.makeText(app, "Snoozed for 10 minutes", Toast.LENGTH_SHORT).show()
    }

    /** Done: just clears the notification (the reminder was already marked done when it fired). */
    fun dismiss(app: Context, id: Long) = cancelNotification(app, id)

    private fun cancelNotification(app: Context, id: Long) {
        app.getSystemService(NotificationManager::class.java).cancel(1000 + id.toInt())
    }

    private fun action(app: Context, icon: Int, label: String, act: String, id: Int, code: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            app, code,
            Intent(app, ReminderReceiver::class.java).setAction(act).putExtra(ReminderReceiver.EXTRA_ID, id.toLong()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(app, icon), label, pi).build()
    }

    private fun notify(app: Context, title: String, text: String, id: Int, face: MochiState, actions: Boolean) {
        PetNotifications.ensureChannels(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            app, 8, Intent(app, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(app, PetNotifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setLargeIcon(PetNotifications.petFace(face))
            .setColor(0xFF7F5BDF.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (actions) {
            b.addAction(action(app, R.drawable.ic_action_snooze, "Snooze 10 min", ReminderReceiver.ACTION_SNOOZE, id, 100_000 + id * 2))
            b.addAction(action(app, R.drawable.ic_action_done, "Done", ReminderReceiver.ACTION_DONE, id, 100_001 + id * 2))
        }
        nm.notify(1000 + id, b.build())
    }
}
