package com.corepack.pet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.corepack.pet.agent.Reminder
import com.corepack.pet.agent.ReminderScheduler

/** Schedules reminders with the system alarm clock, which still fires in Doze and needs no special permission. */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun fire(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun schedule(r: Reminder) {
        val show = PendingIntent.getActivity(
            context, 7, Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(r.dueMs, show), fire(r.id))
    }

    override fun cancel(id: Long) {
        alarms.cancel(fire(id))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        Reminders.fire(context.applicationContext, intent.getLongExtra(EXTRA_ID, -1L))
    }

    companion object {
        const val ACTION_FIRE = "com.corepack.pet.REMINDER_FIRE"
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
    private const val CHANNEL = "reminders"

    /** A reminder is due: tell the chat, show a notification, and make the pet speak up. */
    fun fire(app: Context, id: Long) {
        val store = PetStores.reminders(app)
        val r = store.get(id) ?: return
        if (r.done) return
        store.markDone(id)
        val text = "Reminder: ${r.text}"
        PetAgent.injectAssistant(app, text)
        notify(app, "Reminder", r.text, id.toInt())
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
                notify(app, "Missed reminder", r.text, r.id.toInt())
            }
        }
    }

    private fun notify(app: Context, title: String, text: String, id: Int) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            app, 8, Intent(app, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(1000 + id, n)
    }
}
