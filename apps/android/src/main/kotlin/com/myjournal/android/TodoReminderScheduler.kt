package com.myjournal.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.myjournal.domain.TodoReminderPreferences
import com.myjournal.domain.nextTodoReminder
import com.myjournal.domain.todoReminderSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

internal const val OPEN_TODO = "com.myjournal.android.OPEN_TODO"
private const val REMIND_TODO = "com.myjournal.android.REMIND_TODO"
private const val CHANNEL = "todo-reminders"

class TodoReminderScheduler(
    private val context: Context,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    private val preferences = context.getSharedPreferences("todo-reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun read() = TodoReminderPreferences(
        enabled = preferences.getBoolean("enabled", false),
        morning = LocalTime.parse(preferences.getString("morning", "09:00") ?: "09:00"),
        evening = LocalTime.parse(preferences.getString("evening", "19:00") ?: "19:00"),
    )

    fun save(settings: TodoReminderPreferences) {
        preferences.edit().putBoolean("enabled", settings.enabled)
            .putString("morning", settings.morning.toString())
            .putString("evening", settings.evening.toString()).apply()
        reschedule()
    }

    fun reschedule() {
        val settings = read()
        val times = listOf(settings.morning, settings.evening)
        for (slot in times.indices) {
            if (settings.enabled && (slot == 0 || times[slot] != times[0])) {
                schedule(slot, times[slot])
            } else {
                alarms.cancel(intent(slot, 0))
                preferences.edit().remove("scheduled-$slot").apply()
            }
        }
    }

    /** Advance the slot first so process death after posting cannot replay the same alarm. */
    fun consume(slot: Int, trigger: Long): Boolean {
        val settings = read()
        if (slot !in 0..1 || !settings.enabled || trigger <= 0 || trigger > clock.now().toEpochMilliseconds() ||
            preferences.getLong("scheduled-$slot", 0) != trigger) return false
        val time = if (slot == 0) settings.morning else settings.evening
        schedule(slot, time)
        return true
    }

    private fun schedule(slot: Int, time: LocalTime) {
        val trigger = nextTodoReminder(clock.now(), zone(), time).toEpochMilliseconds()
        preferences.edit().putLong("scheduled-$slot", trigger).apply()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent(slot, trigger))
    }

    private fun intent(slot: Int, trigger: Long) = PendingIntent.getBroadcast(
        context, slot,
        Intent(context, TodoReminderReceiver::class.java).setAction(REMIND_TODO)
            .putExtra("slot", slot).putExtra("trigger", trigger),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun permitted(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    }
}

class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = TodoReminderScheduler(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> { scheduler.reschedule(); return }
            REMIND_TODO -> Unit
            else -> return
        }
        if (!scheduler.consume(intent.getIntExtra("slot", -1), intent.getLongExtra("trigger", 0)) ||
            !scheduler.permitted()) return

        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as JournalApplication
                val summary = todoReminderSummary(app.todos.incomplete(), today()) ?: return@launch
                if (!scheduler.read().enabled || !scheduler.permitted()) return@launch
                val manager = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, "To-do reminders", NotificationManager.IMPORTANCE_DEFAULT))
                }
                val open = PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java).setAction(OPEN_TODO)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                @Suppress("DEPRECATION")
                val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
                    else Notification.Builder(context)
                val noun = if (summary.pending == 1) "task" else "tasks"
                manager.notify(100, notification.setSmallIcon(R.drawable.ic_lifeapp_notification)
                    .setContentTitle("${summary.pending} pending $noun")
                    .setContentText(if (summary.overdue > 0) "Overdue: ${summary.overdue}. Open LifeApp to review." else "Open LifeApp to review.")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
            } catch (_: Exception) {
                android.util.Log.w("TodoReminder", "Unable to read pending tasks or post the reminder")
            } finally {
                result.finish()
            }
        }
    }
}
