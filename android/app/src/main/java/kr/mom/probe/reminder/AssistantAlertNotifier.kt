package kr.mom.probe.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import kr.mom.probe.MainActivity
import kr.mom.probe.R
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDecision
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRules
import java.time.ZoneId

/** Posts a private, review-first alert for deterministic action candidates. */
object AssistantAlertNotifier {
    private const val CHANNEL = "mom-action-candidates"
    private const val PREFS = "assistant-alerts"
    private const val ENABLED = "enabled"
    private const val ALERTED_PREFIX = "alerted:"
    private val whitespace = Regex("\\s+")

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(ENABLED, enabled).commit()

    fun reset(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()

    fun cancel(context: Context, record: ProbeRecord) {
        val stableNotificationId = ProbeRules.recordIdentity(record).hashCode()
        context.getSystemService(NotificationManager::class.java).cancel(stableNotificationId)
    }

    fun notify(context: Context, record: ProbeRecord): Boolean {
        if (!isEnabled(context)) return false
        val repository = kr.mom.probe.data.ProbeRepository.get(context)
        val sourceId = ProbeRules.recordIdentity(record)
        val completedSource = kr.mom.probe.task.AssistantTaskStore.get(context).tasks.value.any {
            it.sourceNotificationId == sourceId && it.completed
        }
        if (completedSource) {
            cancel(context, record)
            return false
        }
        val decision = NoticeDecisionEngine.decide(record, NoticeDecisionEngine.childProfile(repository.settings.value))
        val now = System.currentTimeMillis()
        if (!shouldNotify(decision, now, nextBriefingAt(context, now))) {
            cancel(context, record)
            return false
        }
        val fingerprint = alertFingerprint(decision) ?: return false
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(alertedKey(sourceId), null) == fingerprint) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,
            "모모의 긴급한 일",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "오늘 안에 놓치면 안 되는 준비물·제출·마감만 바로 알려줘요"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        val stableNotificationId = ProbeRules.recordIdentity(record).hashCode()
        val open = PendingIntent.getActivity(
            context,
            stableNotificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RECORD_ID, record.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val description = decision.action?.label ?: "확인할 일이 있어요"
        val privateNotification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("모모가 챙길 일을 찾았어요")
            .setContentText(listOfNotNull(record.title.ifBlank { description }, decision.action?.whenText).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$description. 눌러서 원문을 확인해주세요."))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("나는 엄마다")
                .setContentText("확인할 새 소식이 있어요")
                .build())
            .build()
        return try {
            manager.notify(stableNotificationId, privateNotification)
            preferences.edit().putString(alertedKey(sourceId), fingerprint).apply()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun shouldNotify(decision: NoticeDecision, now: Long, nextBriefingAt: Long?): Boolean {
        val dueAt = decision.action?.dueAt ?: return false
        if (decision.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)) return false
        if (!decision.isRequiredForChild()) return false
        if (dueAt <= now) return false
        return nextBriefingAt != null && dueAt <= nextBriefingAt
    }

    private fun nextBriefingAt(context: Context, now: Long): Long? = (0..2)
        .map { BriefingReminders.read(context, it) }
        .filter { it.enabled }
        .map { BriefingSchedule.nextFire(now, it.hour, it.minute, ZoneId.of("Asia/Seoul"), it.weekdaysOnly) }
        .minOrNull()

    internal fun alertFingerprintForTest(decision: NoticeDecision): String? = alertFingerprint(decision)
    internal fun recordAlertedForTest(context: Context, sourceId: String, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(alertedKey(sourceId), fingerprint).commit()
    }
    internal fun storedAlertFingerprintForTest(context: Context, sourceId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(alertedKey(sourceId), null)

    private fun alertFingerprint(decision: NoticeDecision): String? {
        val action = decision.action ?: return null
        return ProbeRules.digest(listOf(
            decision.source.notificationId,
            action.label.normalizeForFingerprint(),
            action.dueAt?.toString().orEmpty(),
        ).joinToString("|"))
    }

    private fun alertedKey(sourceId: String): String = "$ALERTED_PREFIX$sourceId"
    private fun String.normalizeForFingerprint(): String = trim().replace(whitespace, " ")
}
