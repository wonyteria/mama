package kr.mom.probe.task

import java.time.Instant
import java.time.ZoneId
import kr.mom.probe.data.NotificationCandidate
import kr.mom.probe.data.NotificationCandidateParser
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSettings

data class CandidateActionPlan(
    val text: String,
    val sourceNotificationId: String,
    val dueAt: Long,
    val remindAt: Long?,
)

/** Automates only explicit, dated actions; ambiguous notices remain review-only. */
object CandidateActionPlanner {
    fun plan(record: ProbeRecord, now: Long = System.currentTimeMillis(), child: ChildNoticeProfile = ChildNoticeProfile()): CandidateActionPlan? {
        val candidate = NotificationCandidateParser.parse(record, child) ?: return null
        val dueAt = candidate.dueAt ?: return null
        if (dueAt <= now + 5 * 60_000L) return null
        val explicitEnough = when (candidate.kind) {
            NotificationCandidate.Kind.PREPARE -> candidate.items.isNotEmpty()
            NotificationCandidate.Kind.SUBMIT, NotificationCandidate.Kind.DEADLINE -> record.title.isNotBlank()
        }
        if (!explicitEnough) return null
        return CandidateActionPlan(
            text = taskText(record, candidate),
            sourceNotificationId = ProbeRules.recordIdentity(record),
            dueAt = dueAt,
            remindAt = null,
        )
    }

    fun taskText(record: ProbeRecord, candidate: NotificationCandidate): String {
        val actionLabel = when (candidate.kind) {
            NotificationCandidate.Kind.PREPARE -> "준비"
            NotificationCandidate.Kind.SUBMIT -> "제출·신청"
            NotificationCandidate.Kind.DEADLINE -> "마감 확인"
        }
        val action = if (candidate.items.isEmpty()) actionLabel else "$actionLabel: ${candidate.items.joinToString(", ")}"
        return listOfNotNull(record.title.ifBlank { "알림 확인" }, action, candidate.dueText)
            .joinToString(" · ").take(AssistantTaskStore.MAX_TEXT)
    }

    private fun defaultReminder(dueAt: Long, now: Long): Long? {
        val due = Instant.ofEpochMilli(dueAt).atZone(SEOUL)
        val previousEvening = due.minusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
        val oneHourBefore = dueAt - 60 * 60_000L
        return when {
            previousEvening > now + 5 * 60_000L -> previousEvening
            oneHourBefore > now + 5 * 60_000L -> oneHourBefore
            else -> null
        }
    }

    private val SEOUL = ZoneId.of("Asia/Seoul")
}

object AutoActionCoordinator {
    fun handle(context: android.content.Context, record: ProbeRecord, settings: ProbeSettings) {
        val child = NoticeDecisionEngine.childProfile(settings)
        val decision = NoticeDecisionEngine.decide(record, child)
        val sourceNotificationId = ProbeRules.recordIdentity(record)
        runCatching {
            val store = AssistantTaskStore.get(context)
            store.load()
            if (decision.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id)
                return
            }
            if (decision.applicability == NoticeApplicability.INELIGIBLE ||
                decision.obligation in setOf(NoticeObligation.INFORMATIONAL, NoticeObligation.OPTIONAL_OPPORTUNITY)
            ) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id)
                return
            }
            val plan = CandidateActionPlanner.plan(record, child = child) ?: run {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id)
                return
            }
            val reminder = plan.remindAt?.takeIf { TaskReminderScheduler.canDeliver(context) }
            store.addTask(
                plan.text,
                plan.sourceNotificationId,
                plan.dueAt,
                reminder,
                sourceRevisionId = record.id,
                sourceKind = AssistantTaskSource.AUTO_NOTICE,
            )
        }
    }
}
