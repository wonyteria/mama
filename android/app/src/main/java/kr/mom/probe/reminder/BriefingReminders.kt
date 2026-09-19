package kr.mom.probe.reminder

import android.app.*
import android.content.*
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kr.mom.probe.R
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.task.AssistantTaskStore
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

data class BriefingTime(val enabled: Boolean, val hour: Int, val minute: Int, val weekdaysOnly: Boolean = false)

sealed class BriefingSnoozeResult {
    data class Scheduled(val nextAt: Long) : BriefingSnoozeResult()
    object Stale : BriefingSnoozeResult()
    object Disabled : BriefingSnoozeResult()
    object LimitReached : BriefingSnoozeResult()
    object Failed : BriefingSnoozeResult()
}

object BriefingReminders {
    private const val PREFS = "briefing-reminders"
    private const val CHANNEL = "mom-briefing"
    private const val ALARM_CHANNEL = "mom-assistant-alarm"
    const val DEMO = "demo"
    const val NOTIFICATION_ID = "notificationId"
    const val SCHEDULED_AT = "scheduledAt"
    const val OCCURRENCE_ID = "occurrenceId"
    const val GENERATION = "generation"
    private const val SEEN_IDS = "seenIds"
    private const val MAX_SNOOZES = 3
    private const val MAX_SNOOZE_MINUTES = 60
    private const val PENDING_GRACE_MS = 15 * 60_000L
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun read(context: Context, slot: Int): BriefingTime = prefs(context).let {
        BriefingTime(it.getBoolean("enabled$slot", false), it.getInt("hour$slot", when (slot) { 0 -> 7; 1 -> 20; else -> 12 }), it.getInt("minute$slot", if (slot == 1) 30 else 0), it.getBoolean("weekdays$slot", false))
    }
    fun enableDefaults(context: Context): Boolean {
        val preferences = prefs(context)
        if (preferences.getBoolean("defaultsOffered", false)) return true
        val editor = preferences.edit().putBoolean("defaultsOffered", true)
        if (!preferences.contains("enabled0")) editor.putBoolean("enabled0", true).putInt("hour0", 7).putInt("minute0", 0).putBoolean("weekdays0", false)
        if (!preferences.contains("enabled1")) editor.putBoolean("enabled1", true).putInt("hour1", 20).putInt("minute1", 30).putBoolean("weekdays1", false)
        val saved = editor.commit()
        if (saved) (0..2).forEach { schedule(context, it) }
        return saved
    }
    fun hasEnabledBriefing(context: Context): Boolean = (0..2).any { read(context, it).enabled }
    fun defaultsOffered(context: Context): Boolean = prefs(context).getBoolean("defaultsOffered", false)
    fun claimNotificationPrompt(context: Context): Boolean {
        val preferences = prefs(context)
        if (preferences.getBoolean("notificationPrompted", false)) return false
        return preferences.edit().putBoolean("notificationPrompted", true).commit()
    }
    fun save(context: Context, slot: Int, value: BriefingTime): Boolean {
        require(slot in 0..2 && value.hour in 0..23 && value.minute in 0..59)
        val previous = read(context, slot)
        if (!prefs(context).edit().putBoolean("defaultsOffered", true).putBoolean("configured$slot", true).putBoolean("enabled$slot", value.enabled).putInt("hour$slot", value.hour).putInt("minute$slot", value.minute).putBoolean("weekdays$slot", value.weekdaysOnly).commit()) {
            prefs(context).edit().putBoolean("enabled$slot", previous.enabled).putInt("hour$slot", previous.hour).putInt("minute$slot", previous.minute).putBoolean("weekdays$slot", previous.weekdaysOnly).commit()
            return false
        }
        schedule(context, slot)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, slot, delayed = true))
        if (!value.enabled) clearSlotAlarm(context, slot)
        return true
    }
    private fun pending(
        context: Context,
        slot: Int,
        snooze: Boolean = false,
        delayed: Boolean = false,
        occurrenceId: String? = null,
        scheduledAt: Long? = null,
        expectedGeneration: Long = generation(context),
    ) = PendingIntent.getBroadcast(
        context,
        (if (delayed) 810 else 710) + slot,
        Intent(context, BriefingReceiver::class.java)
            .setAction(if (snooze) "SNOOZE" else if (delayed) "SNOOZED_FIRE" else "FIRE")
            .putExtra("slot", slot)
            .putExtra(GENERATION, expectedGeneration)
            .also {
                if (occurrenceId != null) it.putExtra(OCCURRENCE_ID, occurrenceId)
                if (scheduledAt != null) it.putExtra(SCHEDULED_AT, scheduledAt)
            },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun generation(context: Context): Long = prefs(context).getLong("generation", 0)
    fun alarmMode(context: Context): Boolean = prefs(context).getBoolean("alarmMode", false)
    fun alarmPermissions(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() &&
        (Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
    fun saveAlarmMode(context: Context, enabled: Boolean): Boolean {
        if (enabled && !alarmPermissions(context)) return false
        val previous = alarmMode(context)
        if (!prefs(context).edit().putBoolean("alarmMode", enabled).commit()) {
            prefs(context).edit().putBoolean("alarmMode", previous).commit()
            return false
        }
        restore(context)
        return true
    }
    fun schedule(context: Context, slot: Int) {
        scheduleRegular(context, slot, clearActive = true, resetSnooze = true, now = System.currentTimeMillis())
    }

    internal fun scheduleNextRegular(context: Context, slot: Int) {
        scheduleRegular(context, slot, clearActive = false, resetSnooze = false, now = System.currentTimeMillis())
    }

    private fun scheduleRegular(context: Context, slot: Int, clearActive: Boolean, resetSnooze: Boolean, now: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, slot)
        manager.cancel(operation)
        val value = read(context, slot)
        if (value.enabled) {
            val nextAt = BriefingSchedule.nextFire(now, value.hour, value.minute, weekdaysOnly = value.weekdaysOnly)
            val occurrenceId = newOccurrenceId()
            if (saveScheduledOccurrence(context, slot, occurrenceId, nextAt, resetSnooze = resetSnooze, clearActive = clearActive)) {
                at(context, nextAt, pending(context, slot, occurrenceId = occurrenceId, scheduledAt = nextAt))
            }
        }
    }
    private fun at(context: Context, time: Long, operation: PendingIntent): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return try {
            if (alarmMode(context) && alarmPermissions(context)) {
                val show = PendingIntent.getActivity(context, 900, Intent(context, BriefingSettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), operation)
            } else if (manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
            true
        } catch (_: SecurityException) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
    fun restore(context: Context) { (0..2).forEach { restoreSlot(context, it) } }

    private fun restoreSlot(context: Context, slot: Int) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (slot !in 0..2 || !read(context, slot).enabled) {
            manager.cancel(pending(context, slot))
            manager.cancel(pending(context, slot, delayed = true))
            return
        }
        val now = System.currentTimeMillis()
        val currentGeneration = generation(context)
        val preferences = prefs(context)
        val snoozeOccurrence = preferences.getString("snoozeOccurrence$slot", null)
        val snoozeAt = preferences.getLong("snoozeAt$slot", 0L)
        if (snoozeOccurrence != null && snoozeAt > 0L && snoozeAt >= now - PENDING_GRACE_MS) {
            at(context, maxOf(snoozeAt, now + 1_000L), pending(context, slot, delayed = true, occurrenceId = snoozeOccurrence, scheduledAt = snoozeAt, expectedGeneration = currentGeneration))
        } else if (snoozeOccurrence != null || snoozeAt > 0L) {
            clearSnoozeOccurrence(context, slot)
            manager.cancel(pending(context, slot, delayed = true))
        }

        val regularOccurrence = preferences.getString("scheduledOccurrence$slot", null)
        val regularAt = preferences.getLong("scheduledAt$slot", 0L)
        if (regularOccurrence != null && regularAt > 0L && regularAt >= now - PENDING_GRACE_MS) {
            at(context, maxOf(regularAt, now + 1_000L), pending(context, slot, occurrenceId = regularOccurrence, scheduledAt = regularAt, expectedGeneration = currentGeneration))
        } else {
            scheduleRegular(context, slot, clearActive = false, resetSnooze = false, now = now)
        }
    }
    @Synchronized fun reset(context: Context): Boolean {
        val nextGeneration = generation(context) + 1
        val cleared = prefs(context).edit().clear().putLong("generation", nextGeneration).commit()
        val alarms = context.getSystemService(AlarmManager::class.java)
        (0..2).forEach {
            alarms.cancel(pending(context, it))
            alarms.cancel(pending(context, it, delayed = true))
            context.getSystemService(NotificationManager::class.java).cancel(710 + it)
            context.getSystemService(NotificationManager::class.java).cancel(1_710 + it)
        }
        return cleared
    }
    fun unseenCount(context: Context, repository: ProbeRepository): Int {
        return unseenRecords(context, repository).size
    }
    fun unseenCount(context: Context, repository: ProbeRepository, tasks: List<kr.mom.probe.task.AssistantTask>): Int {
        return unseenRecords(context, repository, tasks).size
    }
    fun unseenRecords(context: Context, repository: ProbeRepository): List<kr.mom.probe.data.ProbeRecord> {
        val sourceScopes = kr.mom.probe.sync.SourceScopeFactory.activeScopes(context, kr.mom.probe.sync.SourceRunTrigger.BRIEFING_STALE)
        val activeRecords = kr.mom.probe.sync.SourceRecordSelectors.activeRecords(repository.records.value, sourceScopes)
        val hasSourceRecords = activeRecords.any { it.sourceMetadata != null }
        if (!repository.settings.value.collectionEnabled && !hasSourceRecords) return emptyList()
        val child = kr.mom.probe.data.NoticeDecisionEngine.childProfile(repository.settings.value)
        val since = maxOf(prefs(context).getLong("seen", 0), System.currentTimeMillis() - ProbeRules.RETENTION_MS)
        val seenIds = prefs(context).getStringSet(SEEN_IDS, emptySet()).orEmpty()
        return activeRecords.filter {
            it.receivedAt > since && (it.sourceMetadata != null || (repository.settings.value.collectionEnabled && it.packageName in repository.settings.value.selectedPackages))
        }
            .sortedByDescending { it.receivedAt }
            .distinctBy { ProbeRules.recordIdentity(it) }
            .filter { it.id !in seenIds }
            .filter { kr.mom.probe.data.NoticeDecisionEngine.isBriefingAction(kr.mom.probe.data.NoticeDecisionEngine.decide(it, child), System.currentTimeMillis()) }
    }
    fun unseenRecords(context: Context, repository: ProbeRepository, tasks: List<kr.mom.probe.task.AssistantTask>): List<kr.mom.probe.data.ProbeRecord> {
        val linked = tasks.mapNotNull { it.sourceNotificationId }.toSet()
        return unseenRecords(context, repository).filter { ProbeRules.recordIdentity(it) !in linked }
    }
    fun briefingTasks(tasks: List<kr.mom.probe.task.AssistantTask>, now: Long = System.currentTimeMillis()): List<kr.mom.probe.task.AssistantTask> =
        kr.mom.probe.task.TodoSelectors.open(tasks)
            .sortedWith(
                compareBy<kr.mom.probe.task.AssistantTask> { it.dueAt == null }
                    .thenBy { it.dueAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAt },
            )
    fun briefingAgenda(
        context: Context,
        records: List<kr.mom.probe.data.ProbeRecord>,
        settings: ProbeSettings,
        slot: Int,
        now: Long = System.currentTimeMillis(),
    ): List<kr.mom.probe.sync.SourceAgendaItem> {
        val targetDate = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
            .plusDays(if (slot == 1) 1 else 0)
        val sourceScopes = kr.mom.probe.sync.SourceScopeFactory.activeScopes(context, kr.mom.probe.sync.SourceRunTrigger.BRIEFING_STALE)
        return kr.mom.probe.sync.SourceRecordSelectors.agenda(records, kr.mom.probe.data.NoticeDecisionEngine.childProfile(settings), sourceScopes, now = now, daysAhead = 7)
            .filter { it.dateIso == targetDate.toString() }
    }
    fun seenRevisionIdsFor(records: List<kr.mom.probe.data.ProbeRecord>): Set<String> =
        records.map { it.id }.toSet()
    fun seenTimestampFor(records: List<kr.mom.probe.data.ProbeRecord>): Long? = records.minOfOrNull { it.receivedAt }
    fun markSeenRecords(context: Context, records: List<kr.mom.probe.data.ProbeRecord>) {
        val revisionIds = seenRevisionIdsFor(records)
        if (revisionIds.isEmpty()) return
        val current = prefs(context).getStringSet(SEEN_IDS, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(SEEN_IDS, current + revisionIds).apply()
    }
    fun markSeen(context: Context, timestamp: Long) { prefs(context).edit().putLong("seen", timestamp).apply() }
    fun eligible(repository: ProbeRepository): Boolean = repository.isReady.value && repository.settings.value.let {
        it.consent && it.consentVersion == ProbeRules.CONSENT_VERSION && it.onboardingDone && repository.lastError.value == null
    }
    @Synchronized fun notify(
        context: Context,
        slot: Int,
        count: Int,
        demo: Boolean = false,
        expectedGeneration: Long = generation(context),
        taskCount: Int = 0,
        agendaCount: Int = 0,
        occurrenceId: String? = null,
        scheduledAt: Long? = null,
    ): Boolean {
        if (expectedGeneration != generation(context)) return false
        if (!demo && (!read(context, slot).enabled || !eligible(ProbeRepository.get(context)))) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "비서 브리핑", NotificationManager.IMPORTANCE_HIGH).apply { description = "정한 시간에 새로 모인 알림을 안내해요"; enableVibration(true) })
        val ringing = alarmMode(context) && alarmPermissions(context)
        if (ringing) manager.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "비서 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "직접 설정한 시간에 알람 소리로 비서를 불러요"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
        val channel = if (ringing) ALARM_CHANNEL else CHANNEL
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return false
        val notificationId = (if (demo) 1_710 else 710) + slot
        val activeOccurrenceId = occurrenceId ?: if (demo) null else activeOrScheduledOccurrence(context, slot)
        val activeScheduledAt = scheduledAt ?: if (demo) null else activeOrScheduledAt(context, slot)
        val notifyOccurrenceId: String?
        val notifyScheduledAt: Long?
        if (demo) {
            notifyOccurrenceId = null
            notifyScheduledAt = null
        } else {
            val nonDemoOccurrenceId = activeOccurrenceId ?: return false
            val nonDemoScheduledAt = activeScheduledAt ?: return false
            if (occurrenceId != null && (occurrenceId != this.activeOccurrenceId(context, slot) || nonDemoScheduledAt != this.activeScheduledAt(context, slot))) return false
            if (!saveActiveOccurrence(context, slot, nonDemoOccurrenceId, expectedGeneration, nonDemoScheduledAt, notificationId)) return false
            notifyOccurrenceId = nonDemoOccurrenceId
            notifyScheduledAt = nonDemoScheduledAt
        }
        val requestCode = 710 + slot + (if (demo) 1_000 else 0) + (if (ringing) 2_000 else 0)
        val open = PendingIntent.getActivity(context, requestCode, Intent(context, BriefingActivity::class.java)
            .setAction(if (demo) "kr.mom.probe.BRIEFING_DEMO" else if (ringing) "kr.mom.probe.BRIEFING_ALARM" else "kr.mom.probe.BRIEFING")
            .putExtra(DEMO, demo).putExtra("ringing", ringing).putExtra("slot", slot)
            .putExtra(NOTIFICATION_ID, notificationId)
            .putExtra(SCHEDULED_AT, notifyScheduledAt ?: System.currentTimeMillis())
            .also {
                if (notifyOccurrenceId != null) it.putExtra(OCCURRENCE_ID, notifyOccurrenceId)
                it.putExtra(GENERATION, expectedGeneration)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (demo) "비서 알림 미리보기" else "모모가 새 알림을 모아뒀어요")
            .setContentText(if (demo) "시험 알림이에요. 실제 기록은 만들지 않아요." else "일정 ${agendaCount}개 · 부탁 ${taskCount}개 · 새 알림 ${count}개")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "브리핑 열기", open)
        if (ringing) note.setFullScreenIntent(open, true).setTimeoutAfter(30_000)
        if (!demo) note.addAction(0, "10분 뒤", pending(context, slot, true, occurrenceId = notifyOccurrenceId, expectedGeneration = expectedGeneration))
        return try { manager.notify(notificationId, note.build()); true } catch (_: SecurityException) { false }
    }
    @Synchronized fun snoozeActive(context: Context, slot: Int, occurrenceId: String?, expectedGeneration: Long, minutes: Int = 10): BriefingSnoozeResult {
        require(minutes in setOf(5, 10, 30))
        if (slot !in 0..2 || expectedGeneration != generation(context) || !read(context, slot).enabled) return BriefingSnoozeResult.Disabled
        if (occurrenceId == null || occurrenceId != activeOccurrenceId(context, slot) || expectedGeneration != activeGeneration(context, slot)) return BriefingSnoozeResult.Stale
        val currentCount = prefs(context).getInt("snoozeCount$slot", 0).coerceIn(0, MAX_SNOOZES)
        val currentMinutes = prefs(context).getInt("snoozeMinutes$slot", 0).coerceIn(0, MAX_SNOOZE_MINUTES)
        if (currentCount >= MAX_SNOOZES || currentMinutes + minutes > MAX_SNOOZE_MINUTES) return BriefingSnoozeResult.LimitReached
        val previousScheduledAt = activeScheduledAt(context, slot)
        val previousNotificationId = activeNotificationId(context, slot)
        val nextAt = System.currentTimeMillis() + minutes * 60_000L
        val nextOccurrenceId = newOccurrenceId()
        val saved = prefs(context).edit()
            .putString("snoozeOccurrence$slot", nextOccurrenceId)
            .putLong("snoozeAt$slot", nextAt)
            .remove("activeOccurrence$slot")
            .remove("activeScheduledAt$slot")
            .remove("activeNotificationId$slot")
            .putInt("snoozeCount$slot", currentCount + 1)
            .putInt("snoozeMinutes$slot", currentMinutes + minutes)
            .commit()
        if (!saved) return BriefingSnoozeResult.Stale
        if (!at(context, nextAt, pending(context, slot, delayed = true, occurrenceId = nextOccurrenceId, scheduledAt = nextAt, expectedGeneration = expectedGeneration))) {
            prefs(context).edit()
                .remove("snoozeOccurrence$slot")
                .remove("snoozeAt$slot")
                .putString("activeOccurrence$slot", occurrenceId)
                .putLong("activeGeneration$slot", expectedGeneration)
                .also {
                    if (previousScheduledAt != null) it.putLong("activeScheduledAt$slot", previousScheduledAt) else it.remove("activeScheduledAt$slot")
                    if (previousNotificationId != null) it.putInt("activeNotificationId$slot", previousNotificationId) else it.remove("activeNotificationId$slot")
                }
                .putInt("snoozeCount$slot", currentCount)
                .putInt("snoozeMinutes$slot", currentMinutes)
                .commit()
            return BriefingSnoozeResult.Failed
        }
        previousNotificationId?.let { context.getSystemService(NotificationManager::class.java).cancel(it) }
        return BriefingSnoozeResult.Scheduled(nextAt)
    }

    fun stopActive(context: Context, slot: Int, occurrenceId: String?, expectedGeneration: Long, demo: Boolean = false, notificationId: Int? = null): Boolean {
        if (demo) {
            notificationId?.let { context.getSystemService(NotificationManager::class.java).cancel(it) }
            return true
        }
        if (slot !in 0..2 || occurrenceId == null || expectedGeneration != generation(context)) return false
        if (occurrenceId != activeOccurrenceId(context, slot) || expectedGeneration != activeGeneration(context, slot)) return false
        val ownNotificationId = activeNotificationId(context, slot) ?: return false
        context.getSystemService(NotificationManager::class.java).cancel(ownNotificationId)
        return true
    }

    fun snooze(context: Context, slot: Int, minutes: Int = 10): BriefingSnoozeResult =
        snoozeActive(context, slot, activeOccurrenceId(context, slot), activeGeneration(context, slot), minutes)

    private fun newOccurrenceId(): String = UUID.randomUUID().toString()

    private fun saveScheduledOccurrence(
        context: Context,
        slot: Int,
        occurrenceId: String,
        scheduledAt: Long,
        resetSnooze: Boolean,
        clearActive: Boolean,
    ): Boolean {
        val editor = prefs(context).edit()
            .putString("scheduledOccurrence$slot", occurrenceId)
            .putLong("scheduledAt$slot", scheduledAt)
        if (clearActive) {
            editor.remove("activeOccurrence$slot")
                .remove("activeGeneration$slot")
                .remove("activeScheduledAt$slot")
                .remove("activeNotificationId$slot")
                .remove("snoozeOccurrence$slot")
                .remove("snoozeAt$slot")
        }
        if (resetSnooze) editor.putInt("snoozeCount$slot", 0).putInt("snoozeMinutes$slot", 0)
        return editor.commit()
    }

    @Synchronized internal fun consumeScheduledFire(
        context: Context,
        slot: Int,
        occurrenceId: String?,
        scheduledAt: Long?,
        expectedGeneration: Long,
        notificationId: Int,
        delayed: Boolean,
    ): Long? {
        if (slot !in 0..2 || expectedGeneration != generation(context) || !read(context, slot).enabled || occurrenceId == null) return null
        val preferences = prefs(context)
        val occurrenceKey = if (delayed) "snoozeOccurrence$slot" else "scheduledOccurrence$slot"
        val timeKey = if (delayed) "snoozeAt$slot" else "scheduledAt$slot"
        val storedOccurrence = preferences.getString(occurrenceKey, null) ?: return null
        val storedAt = preferences.getLong(timeKey, 0L).takeIf { it > 0L } ?: return null
        if (occurrenceId != storedOccurrence) return null
        if (scheduledAt != null && scheduledAt != storedAt) return null
        val editor = preferences.edit()
            .putString("activeOccurrence$slot", occurrenceId)
            .putLong("activeGeneration$slot", expectedGeneration)
            .putLong("activeScheduledAt$slot", storedAt)
            .putInt("activeNotificationId$slot", notificationId)
            .remove(occurrenceKey)
            .remove(timeKey)
        if (!delayed) editor.putInt("snoozeCount$slot", 0).putInt("snoozeMinutes$slot", 0)
        return if (editor.commit()) storedAt else null
    }

    private fun clearSnoozeOccurrence(context: Context, slot: Int) {
        prefs(context).edit().remove("snoozeOccurrence$slot").remove("snoozeAt$slot").apply()
    }

    private fun saveActiveOccurrence(
        context: Context,
        slot: Int,
        occurrenceId: String,
        expectedGeneration: Long,
        scheduledAt: Long,
        notificationId: Int,
    ): Boolean = prefs(context).edit()
        .putString("activeOccurrence$slot", occurrenceId)
        .putLong("activeGeneration$slot", expectedGeneration)
        .putLong("activeScheduledAt$slot", scheduledAt)
        .putInt("activeNotificationId$slot", notificationId)
        .commit()

    private fun clearSlotAlarm(context: Context, slot: Int) {
        val preferences = prefs(context)
        val active = preferences.getInt("activeNotificationId$slot", 710 + slot)
        preferences.edit()
            .remove("scheduledOccurrence$slot")
            .remove("scheduledAt$slot")
            .remove("snoozeOccurrence$slot")
            .remove("snoozeAt$slot")
            .remove("activeOccurrence$slot")
            .remove("activeGeneration$slot")
            .remove("activeScheduledAt$slot")
            .remove("activeNotificationId$slot")
            .putInt("snoozeCount$slot", 0)
            .putInt("snoozeMinutes$slot", 0)
            .apply()
        context.getSystemService(NotificationManager::class.java).cancel(active)
        context.getSystemService(NotificationManager::class.java).cancel(710 + slot)
        context.getSystemService(NotificationManager::class.java).cancel(1_710 + slot)
    }

    private fun activeOrScheduledOccurrence(context: Context, slot: Int): String? =
        activeOccurrenceId(context, slot)
            ?: prefs(context).getString("snoozeOccurrence$slot", null)
            ?: prefs(context).getString("scheduledOccurrence$slot", null)

    private fun activeOrScheduledAt(context: Context, slot: Int): Long? =
        activeScheduledAt(context, slot)
            ?: prefs(context).getLong("snoozeAt$slot", 0L).takeIf { it > 0L }
            ?: prefs(context).getLong("scheduledAt$slot", 0L).takeIf { it > 0L }

    private fun activeOccurrenceId(context: Context, slot: Int): String? =
        prefs(context).getString("activeOccurrence$slot", null)

    private fun activeGeneration(context: Context, slot: Int): Long =
        prefs(context).getLong("activeGeneration$slot", generation(context))

    private fun activeScheduledAt(context: Context, slot: Int): Long? =
        prefs(context).getLong("activeScheduledAt$slot", 0L).takeIf { it > 0L }

    private fun activeNotificationId(context: Context, slot: Int): Int? =
        prefs(context).getInt("activeNotificationId$slot", -1).takeIf { it > 0 }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) { BriefingReminders.restore(context); return }
        val slot = intent.getIntExtra("slot", 0)
        val generation = intent.getLongExtra(BriefingReminders.GENERATION, -1)
        val occurrenceId = intent.getStringExtra(BriefingReminders.OCCURRENCE_ID)
        val scheduledAt = intent.getLongExtra(BriefingReminders.SCHEDULED_AT, 0L).takeIf { it > 0L }
        if (slot !in 0..2 || generation != BriefingReminders.generation(context) || !BriefingReminders.read(context, slot).enabled) return
        if (intent.action == "SNOOZE") {
            BriefingReminders.snoozeActive(context, slot, occurrenceId, generation)
            return
        }
        if (intent.action != "FIRE" && intent.action != "SNOOZED_FIRE") return
        val delayedFire = intent.action == "SNOOZED_FIRE"
        val fireScheduledAt = BriefingReminders.consumeScheduledFire(
            context,
            slot,
            occurrenceId,
            scheduledAt,
            generation,
            710 + slot,
            delayed = delayedFire,
        ) ?: return
        if (intent.action == "FIRE") BriefingReminders.scheduleNextRegular(context, slot)
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(7_000) {
                    val repository = ProbeRepository.get(context)
                    repository.isReady.first { it }
                    if (generation == BriefingReminders.generation(context) && BriefingReminders.eligible(repository) && BriefingReminders.read(context, slot).enabled) {
                        val taskStore = AssistantTaskStore.get(context)
                        kr.mom.probe.sync.SourceSyncScheduler.enqueueActive(context, kr.mom.probe.sync.SourceRunTrigger.BRIEFING_STALE)
                        withContext(Dispatchers.IO) { taskStore.load() }
                        val count = BriefingReminders.unseenCount(context, repository, taskStore.tasks.value)
                        val taskCount = BriefingReminders.briefingTasks(taskStore.tasks.value).size
                        val agendaCount = BriefingReminders.briefingAgenda(context, repository.records.value, repository.settings.value, slot).size
                        if (count > 0 || taskCount > 0 || agendaCount > 0) {
                            BriefingReminders.notify(
                                context,
                                slot,
                                count,
                                expectedGeneration = generation,
                                taskCount = taskCount,
                                agendaCount = agendaCount,
                                occurrenceId = occurrenceId,
                                scheduledAt = fireScheduledAt,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // The next scheduled briefing retries once the repository is available.
            } finally { result.finish() }
        }
    }
}
