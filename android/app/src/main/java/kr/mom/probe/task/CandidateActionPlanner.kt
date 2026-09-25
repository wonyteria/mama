package kr.mom.probe.task

import kr.mom.probe.data.NotificationCandidate
import kr.mom.probe.data.NotificationCandidateParser
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeSettings

data class CandidateActionPlan(
    val text: String,
    val sourceNotificationId: String,
    val dueAt: Long?,
    val remindAt: Long?,
    val actionKind: String? = null,
    val checklist: List<String> = emptyList(),
    val noticeGroupKeys: Set<String> = emptySet(),
    val evidenceText: String? = null,
    val sourceTitle: String? = null,
    val sourceLabel: String? = null,
    val sourceCapturedAt: Long? = null,
    val audienceLabel: String? = null,
)

/**
 * Automates only explicit, evidence-backed required actions. Submission and
 * preparation become separate tasks; preparation carries its own checklist.
 * Ambiguous notices remain review-only.
 */
object CandidateActionPlanner {
    private val submitEvidence = Regex("제출|회신|납부|신청|응답|서명|동의서|마감|기한")

    fun plans(
        record: ProbeRecord,
        now: Long = System.currentTimeMillis(),
        child: ChildNoticeProfile = ChildNoticeProfile(),
        institution: String = "",
    ): List<CandidateActionPlan> {
        val candidate = NotificationCandidateParser.parse(record, child) ?: return emptyList()
        val dueAt = candidate.dueAt
        if (dueAt != null && dueAt <= now + 5 * 60_000L) return emptyList()
        if (record.title.isBlank()) return emptyList()
        // Undated preparation requests without an explicit item list stay review-only.
        if (dueAt == null && candidate.kind == NotificationCandidate.Kind.PREPARE && candidate.items.isEmpty()) return emptyList()
        val sourceNotificationId = NoticeGrouping.groupId(record, institution)
        val groupKeys = NoticeGrouping.keys(record, institution)
        val remindAt = dueAt?.let { nextReminder(it, now) }
        val plans = mutableListOf<CandidateActionPlan>()
        val decision = NoticeDecisionEngine.decide(record, child)
        val evidenceText = decision.action?.evidence?.quote?.trim()?.takeIf(String::isNotEmpty)
            ?: record.bigText.ifBlank { record.text }.ifBlank { record.textLines.joinToString(" ") }
                .trim().take(AssistantTaskStore.MAX_EVIDENCE_TEXT).takeIf(String::isNotEmpty)
        val audienceLabel = child.grade?.let { grade ->
            "${child.schoolLevel.label} ${grade}학년"
        } ?: "자녀 대상"
        val noticeText = listOf(record.title, record.bigText, record.text, record.textLines.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(" ")
        if (candidate.kind != NotificationCandidate.Kind.PREPARE || submitEvidence.containsMatchIn(noticeText)) {
            plans += CandidateActionPlan(
                text = actionText(record, "제출·신청", candidate.dueText),
                sourceNotificationId = sourceNotificationId,
                dueAt = dueAt,
                remindAt = remindAt,
                actionKind = "submit",
                noticeGroupKeys = groupKeys,
                evidenceText = evidenceText,
                sourceTitle = record.title.ifBlank { "제목 없는 공지" },
                sourceLabel = record.appLabel.ifBlank { "학교·학원 소식" },
                sourceCapturedAt = record.receivedAt,
                audienceLabel = audienceLabel,
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
                noticeGroupKeys = groupKeys,
                evidenceText = evidenceText,
                sourceTitle = record.title.ifBlank { "제목 없는 공지" },
                sourceLabel = record.appLabel.ifBlank { "학교·학원 소식" },
                sourceCapturedAt = record.receivedAt,
                audienceLabel = audienceLabel,
            )
        }
        return plans
    }

    fun plan(
        record: ProbeRecord,
        now: Long = System.currentTimeMillis(),
        child: ChildNoticeProfile = ChildNoticeProfile(),
        institution: String = "",
    ): CandidateActionPlan? = plans(record, now, child, institution).firstOrNull()

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
        val institution = NoticeGrouping.institution(settings)
        val groupKeys = NoticeGrouping.keys(record, institution)
        val sourceNotificationId = NoticeGrouping.groupId(record, institution)
        runCatching {
            val store = AssistantTaskStore.get(context)
            store.load()
            if (decision.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id, groupKeys)
                return
            }
            if (decision.applicability == NoticeApplicability.INELIGIBLE ||
                decision.obligation in setOf(NoticeObligation.INFORMATIONAL, NoticeObligation.OPTIONAL_OPPORTUNITY)
            ) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id, groupKeys)
                return
            }
            val plans = CandidateActionPlanner.plans(record, child = child, institution = institution)
            if (plans.isEmpty()) {
                kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                store.suspendAutomaticSource(sourceNotificationId, record.id, groupKeys)
                return
            }
            store.applyAutomaticPlans(
                sourceNotificationId,
                record.id,
                noticeGroupKeys = groupKeys,
                plans = plans.map { plan ->
                    AutoTaskPlan(
                        actionKind = plan.actionKind ?: "submit",
                        text = plan.text,
                        checklist = plan.checklist,
                        dueAt = plan.dueAt,
                        remindAt = plan.remindAt,
                        evidenceText = plan.evidenceText,
                        sourceTitle = plan.sourceTitle,
                        sourceLabel = plan.sourceLabel,
                        sourceCapturedAt = plan.sourceCapturedAt,
                        audienceLabel = plan.audienceLabel,
                    )
                },
            )
        }.onFailure {
            android.util.Log.w("AutoActionCoordinator", "auto-action failed for ${record.id}", it)
        }
    }
}
