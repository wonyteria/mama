package kr.mom.probe.task

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
    val dueAt: Long?,
    val remindAt: Long?,
    val actionKind: String? = null,
    val checklist: List<String> = emptyList(),
)

/**
 * Automates only explicit, evidence-backed required actions. Submission and
 * preparation become separate tasks; preparation carries its own checklist.
 * Ambiguous notices remain review-only.
 */
object CandidateActionPlanner {
    private val submitEvidence = Regex("제출|회신|납부|신청|응답|서명|동의서|마감|기한")

    fun plans(record: ProbeRecord, now: Long = System.currentTimeMillis(), child: ChildNoticeProfile = ChildNoticeProfile()): List<CandidateActionPlan> {
        val candidate = NotificationCandidateParser.parse(record, child) ?: return emptyList()
        val dueAt = candidate.dueAt ?: return emptyList()
        if (dueAt <= now + 5 * 60_000L) return emptyList()
        if (record.title.isBlank()) return emptyList()
        val sourceNotificationId = ProbeRules.recordIdentity(record)
        val remindAt = dueAt?.let { nextReminder(it, now) }
        val plans = mutableListOf<CandidateActionPlan>()
        val noticeText = listOf(record.title, record.bigText, record.text, record.textLines.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(" ")
        if (candidate.kind != NotificationCandidate.Kind.PREPARE || submitEvidence.containsMatchIn(noticeText)) {
            plans += CandidateActionPlan(
                text = actionText(record, "제출·신청", candidate.dueText),
                sourceNotificationId = sourceNotificationId,
                dueAt = dueAt,
                remindAt = remindAt,
                actionKind = "submit",
            )
        }
        if (candidate.kind == NotificationCandidate.Kind.PREPARE || candidate.items.isNotEmpty()) {
            plans += CandidateActionPlan(
                text = actionText(record, "준비물 챙기기", candidate.dueText),
                sourceNotificationId = sourceNotificationId,
                dueAt = dueAt,
                remindAt = remindAt,
                actionKind = "prepare",
                checklist = candidate.items,
            )
        }
        return plans
    }

    fun plan(record: ProbeRecord, now: Long = System.currentTimeMillis(), child: ChildNoticeProfile = ChildNoticeProfile()): CandidateActionPlan? =
        plans(record, now, child).firstOrNull()

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

    private fun actionText(record: ProbeRecord, actionLabel: String, dueText: String?): String =
        listOfNotNull(record.title.ifBlank { "알림 확인" }, actionLabel, dueText)
            .joinToString(" · ").take(AssistantTaskStore.MAX_TEXT)

    internal fun nextReminder(dueAt: Long, now: Long): Long? =
        TaskReminderScheduler.nextReminderAfter(dueAt, now + 4 * 60_000L)
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
            val plans = CandidateActionPlanner.plans(record, child = child)
            if (plans.isEmpty()) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id)
                return
            }
            store.applyAutomaticPlans(
                sourceNotificationId,
                record.id,
                plans.map { plan ->
                    AutoTaskPlan(
                        actionKind = plan.actionKind ?: "submit",
                        text = plan.text,
                        checklist = plan.checklist,
                        dueAt = plan.dueAt,
                        remindAt = plan.remindAt,
                    )
                },
            )
        }.onFailure {
            android.util.Log.w("AutoActionCoordinator", "auto-action failed for ${record.id}", it)
        }
    }
}
