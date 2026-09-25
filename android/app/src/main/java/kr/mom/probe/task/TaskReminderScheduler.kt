package kr.mom.probe.task

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kr.mom.probe.R
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.reminder.BriefingReminders
import kr.mom.probe.reminder.TaskAlarmActivity

/** Owns one immutable, explicit alarm per assistant task. */
object TaskReminderScheduler {
    private const val ACTION_FIRE = "kr.mom.probe.task.FIRE_REMINDER"
    private const val ACTION_SNOOZE = "kr.mom.probe.task.SNOOZE_REMINDER"
    private const val ACTION_STOP = "kr.mom.probe.task.STOP_REMINDER"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_SCHEDULED_AT = "scheduledAt"
    const val EXTRA_SNOOZE_MINUTES = "snoozeMinutes"
    private const val PREFS = "assistant-task-reminders"
    private const val SCHEDULED_IDS = "scheduledIds"
    const val CHANNEL = "mom-task-reminders"
    private const val ALARM_CHANNEL = "mom-assistant-alarm"

    fun canDeliver(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (BriefingReminders.alarmMode(context) && BriefingReminders.alarmPermissions(context)) {
            ensureAlarmChannel(manager)
            ALARM_CHANNEL
        } else {
            ensureChannel(manager)
            CHANNEL
        }
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,
            "모모의 부탁 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "직접 정한 시간 무렵에 챙길 일을 알려줘요"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
        })
    }

    private fun ensureAlarmChannel(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(
            ALARM_CHANNEL,
            "비서 알람",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "직접 설정한 시간에 알람 소리로 비서를 불러요"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        })
    }

    fun sync(context: Context, tasks: List<AssistantTask>) {
        val app = context.applicationContext
        val active = tasks.filter { !it.completed && !it.suspended && !it.excluded && it.remindAt != null }.associateBy { it.id }
        val preferences = scheduledPrefs(app)
        val previous = preferences.getStringSet(SCHEDULED_IDS, emptySet()).orEmpty().toSet()
        (previous - active.keys).forEach { cancel(app, it) }
        val scheduled = active.values.mapNotNull { task -> task.id.takeIf { schedule(app, task) } }.toSet()
        preferences.edit().putStringSet(SCHEDULED_IDS, scheduled).apply()
    }

    fun cancel(context: Context, taskId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, taskId))
        context.getSystemService(NotificationManager::class.java).cancel(legacyNotificationId(taskId))
        scheduledPrefs(context).edit().putStringSet(SCHEDULED_IDS, scheduledIds(context) - taskId).apply()
    }

    fun cancel(context: Context, task: AssistantTask) {
        cancel(context, task.id)
        task.activeAlarmNotificationId?.let { context.getSystemService(NotificationManager::class.java).cancel(it) }
    }

    /** Rehydrates encrypted tasks after boot, then recreates every outstanding alarm. */
    fun restore(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { restoreNow(app) }
    }

    internal suspend fun restoreNow(context: Context) {
        withTimeout(10_000) {
            ProbeRepository.get(context).isReady.first { it }
            val store = AssistantTaskStore.get(context)
            store.load()
            sync(context, store.tasks.value)
        }
    }

    internal fun notify(context: Context, task: AssistantTask): Boolean {
        val occurrenceId = task.activeAlarmOccurrenceId ?: return false
        val notificationId = task.activeAlarmNotificationId ?: notificationId(task.id, occurrenceId)
        val scheduledAt = task.activeAlarmScheduledAt ?: return false
        if (task.completed || task.suspended) return false
        if (!canDeliver(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringing = BriefingReminders.alarmMode(context) && BriefingReminders.alarmPermissions(context)
        val channel = if (ringing) {
            ensureAlarmChannel(manager)
            ALARM_CHANNEL
        } else {
            CHANNEL
        }
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        val open = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, TaskAlarmActivity::class.java)
                .setAction("kr.mom.probe.task.OPEN_REMINDER")
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra("ringing", ringing),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x51,
            alarmActionIntent(context, ACTION_STOP, task.id, occurrenceId, notificationId, scheduledAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            context,
            notificationId xor 0xA7,
            alarmActionIntent(context, ACTION_SNOOZE, task.id, occurrenceId, notificationId, scheduledAt)
                .putExtra(EXTRA_SNOOZE_MINUTES, 10),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("나는 엄마다")
            .setContentText("확인할 부탁이 있어요")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("모모가 부탁을 알려드려요")
            .setContentText(task.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "소리 끄기", stop)
            .addAction(0, "10분 뒤", snooze)
        if (ringing) notification.setFullScreenIntent(open, true).setTimeoutAfter(30_000)
        return try {
            val built = notification.build()
            if (ringing) built.flags = built.flags or Notification.FLAG_INSISTENT
            manager.notify(notificationId, built)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun trySchedule(context: Context, task: AssistantTask): Boolean {
        if (!schedule(context, task)) return false
        if (registerScheduled(context, task.id)) return true
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, task.id))
        return false
    }

    private fun schedule(context: Context, task: AssistantTask): Boolean {
        val remindAt = task.remindAt ?: return false
        val occurrenceId = AssistantTaskStore.expectedOccurrenceId(task) ?: return false
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, task.id, occurrenceId)
        manager.cancel(operation)
        return try {
            if (manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
            }
            true
        } catch (_: SecurityException) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private fun pending(context: Context, taskId: String, occurrenceId: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, TaskReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .also { if (occurrenceId != null) it.putExtra(EXTRA_OCCURRENCE_ID, occurrenceId) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduledPrefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun scheduledIds(context: Context): Set<String> = scheduledPrefs(context).getStringSet(SCHEDULED_IDS, emptySet()).orEmpty().toSet()

    private fun registerScheduled(context: Context, taskId: String): Boolean =
        scheduledPrefs(context).edit().putStringSet(SCHEDULED_IDS, scheduledIds(context) + taskId).commit()

    private fun alarmActionIntent(
        context: Context,
        action: String,
        taskId: String,
        occurrenceId: String,
        notificationId: Int,
        scheduledAt: Long,
    ): Intent = Intent(context, TaskReminderReceiver::class.java)
        .setAction(action)
        .putExtra(EXTRA_TASK_ID, taskId)
        .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        .putExtra(EXTRA_SCHEDULED_AT, scheduledAt)

    internal fun notificationId(taskId: String, occurrenceId: String): Int =
        27_100 + ((taskId + occurrenceId).hashCode() and Int.MAX_VALUE) % 1_000_000

    private fun legacyNotificationId(taskId: String): Int = taskId.hashCode() xor 0x4D4F4D4F

    private val seoul: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")

    /**
     * Recurring reminder slots for a due date: the previous evening, the morning of,
     * and one hour before. Returns the earliest slot still in the future.
     */
    internal fun nextReminderAfter(dueAt: Long, after: Long): Long? {
        val dueDay = java.time.Instant.ofEpochMilli(dueAt).atZone(seoul).toLocalDate()
        val slots = listOf(
            dueDay.minusDays(1).atTime(20, 0).atZone(seoul).toInstant().toEpochMilli(),
            dueDay.atTime(7, 0).atZone(seoul).toInstant().toEpochMilli(),
            dueAt - 3_600_000L,
        )
        return slots.filter { it > after + 60_000L }.minOrNull()
    }

    internal fun isFire(intent: Intent): Boolean = intent.action == ACTION_FIRE
    internal fun isSnooze(intent: Intent): Boolean = intent.action == ACTION_SNOOZE
    internal fun isStop(intent: Intent): Boolean = intent.action == ACTION_STOP
    internal fun taskId(intent: Intent): String? = intent.getStringExtra(EXTRA_TASK_ID)
    internal fun occurrenceId(intent: Intent): String? = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
    internal fun notificationId(intent: Intent): Int = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val result = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    TaskReminderScheduler.restoreNow(context.applicationContext)
                } catch (_: Exception) {
                    // A later app launch/save retries synchronization.
                } finally {
                    result.finish()
                }
            }
            return
        }
        if (!TaskReminderScheduler.isFire(intent) && !TaskReminderScheduler.isSnooze(intent) && !TaskReminderScheduler.isStop(intent)) return
        val taskId = TaskReminderScheduler.taskId(intent) ?: return
        val occurrenceId = TaskReminderScheduler.occurrenceId(intent)
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = ProbeRepository.get(context)
                withTimeout(7_000) { repository.isReady.first { it } }
                val store = AssistantTaskStore.get(context)
                store.load()
                if (TaskReminderScheduler.isStop(intent)) {
                    if (occurrenceId != null) {
                        store.activeAlarmNotificationId(taskId, occurrenceId)?.let {
                            context.getSystemService(NotificationManager::class.java).cancel(it)
                        }
                        // Dismissing the sound does not finish the task; re-arm the next
                        // reminder slot so unfinished work keeps surfacing.
                        val task = store.tasks.value.firstOrNull {
                            it.id == taskId && !it.completed && !it.suspended && !it.excluded &&
                                it.activeAlarmOccurrenceId == occurrenceId
                        }
                        val nextAt = task?.dueAt?.let { TaskReminderScheduler.nextReminderAfter(it, System.currentTimeMillis()) }
                        store.dismissAlarmOccurrence(taskId, occurrenceId, nextAt)
                    }
                    return@launch
                }
                if (TaskReminderScheduler.isSnooze(intent)) {
                    if (occurrenceId != null) {
                        val minutes = intent.getIntExtra(TaskReminderScheduler.EXTRA_SNOOZE_MINUTES, 10)
                        val snoozed = store.snoozeAlarmOccurrence(taskId, occurrenceId, minutes = minutes)
                        if (snoozed is TaskAlarmSnoozeResult.Scheduled) {
                            val notificationId = TaskReminderScheduler.notificationId(intent)
                            if (notificationId > 0) context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                        }
                    }
                    return@launch
                }
                val fireOccurrenceId = occurrenceId ?: return@launch
                val task = store.tasks.value.firstOrNull {
                    it.id == taskId && !it.completed && !it.suspended && !it.excluded && it.remindAt != null &&
                        AssistantTaskStore.expectedOccurrenceId(it) == fireOccurrenceId
                } ?: return@launch
                if (!TaskReminderScheduler.canDeliver(context)) {
                    if (task.reminderAttempts >= 2) store.consumeReminder(taskId, fireOccurrenceId)
                    else store.rescheduleReminder(taskId, System.currentTimeMillis() + 15 * 60_000L)
                    return@launch
                }
                val notificationId = TaskReminderScheduler.notificationId(taskId, fireOccurrenceId)
                store.consumeReminder(taskId, fireOccurrenceId, notificationId)?.let { fired ->
                    if (!TaskReminderScheduler.notify(context, fired)) {
                        if (fired.reminderAttempts < 2) store.rescheduleReminder(taskId, System.currentTimeMillis() + 15 * 60_000L)
                        else store.clearActiveAlarmAfterFailedNotification(taskId, fireOccurrenceId)
                    }
                }
            } catch (_: Exception) {
                // Do not expose stale task contents when local state cannot be verified.
            } finally {
                result.finish()
            }
        }
    }
}
