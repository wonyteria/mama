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
import kr.mom.probe.data.ProbeRules
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
        // A required action stays required when the notice arrives at or after its
        // deadline; the task lands overdue instead of being dropped. nextReminder
        // returns null once every slot passed, so no past alarm is ever armed.
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
    /** What the coordinator will do with a record — shared by handle and tests. */
    internal enum class Route { APPLY, SUSPEND, REVIEW }

    internal data class Routing(
        val route: Route,
        val decision: kr.mom.probe.data.NoticeDecision,
        val plans: List<CandidateActionPlan>,
        val groupKeys: Set<String>,
        val sourceNotificationId: String,
    )

    /**
     * The context-free half of [handle]: decide + group + plan, then pick the
     * route. Tests drive this with a synthetic record to exercise the exact
     * promotion/suspension gate the live capture path applies.
     */
    internal fun routeFor(
        record: ProbeRecord,
        child: ChildNoticeProfile,
        institution: String,
        now: Long = System.currentTimeMillis(),
    ): Routing {
        val decision = NoticeDecisionEngine.decide(record, child)
        val groupKeys = NoticeGrouping.keys(record, institution)
        val sourceNotificationId = NoticeGrouping.groupId(record, institution)
        if (decision.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)) {
            return Routing(Route.REVIEW, decision, emptyList(), groupKeys, sourceNotificationId)
        }
        if (decision.applicability == NoticeApplicability.INELIGIBLE ||
            decision.obligation in setOf(NoticeObligation.INFORMATIONAL, NoticeObligation.OPTIONAL_OPPORTUNITY)
        ) {
            return Routing(Route.SUSPEND, decision, emptyList(), groupKeys, sourceNotificationId)
        }
        val plans = CandidateActionPlanner.plans(record, now = now, child = child, institution = institution)
        if (plans.isEmpty()) {
            val cancels = decision.dates.any { it.role == kr.mom.probe.data.NoticeDateRole.CANCELLATION }
            return Routing(if (cancels) Route.SUSPEND else Route.REVIEW, decision, plans, groupKeys, sourceNotificationId)
        }
        return Routing(Route.APPLY, decision, plans, groupKeys, sourceNotificationId)
    }

    fun handle(context: android.content.Context, record: ProbeRecord, settings: ProbeSettings) {
        val child = NoticeDecisionEngine.childProfile(settings)
        val institution = NoticeGrouping.institution(settings)
        val routing = routeFor(record, child, institution)
        runCatching {
            val store = AssistantTaskStore.get(context)
            store.load()
            // The record is already committed; journal the source until the task
            // reconcile below finishes so a crash or failed save replays later.
            ReconcileJournal.markPending(context, routing.sourceNotificationId)
            when (routing.route) {
                Route.REVIEW -> {
                    // Ambiguous or incomplete content must not hide a known obligation:
                    // keep the last trusted task state and flag it for user review.
                    store.markAutomaticSourcesNeedReview(
                        routing.sourceNotificationId, record.id, routing.groupKeys,
                    )
                }
                Route.SUSPEND -> {
                    kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, record)
                    store.suspendAutomaticSource(
                        routing.sourceNotificationId, record.id, routing.groupKeys,
                    )
                }
                Route.APPLY -> store.applyAutomaticPlans(
                    routing.sourceNotificationId,
                    record.id,
                    noticeGroupKeys = routing.groupKeys,
                    plans = routing.plans.map { plan ->
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
            }
            ReconcileJournal.clearPending(context, routing.sourceNotificationId)
        }.onFailure {
            android.util.Log.w("AutoActionCoordinator", "auto-action failed for ${record.id}", it)
        }
    }

    internal enum class PendingAction { REPLAY, DROP }

    /**
     * Decides, for each journaled source id, whether a stored record still needs
     * task reconciliation. Retired keys belong to tasks the user deleted, and a
     * missing record means the source itself is gone: both drop the entry.
     */
    internal fun pendingActions(
        records: List<ProbeRecord>,
        pending: Set<String>,
        retired: Set<String>,
        institution: String,
    ): List<Pair<String, PendingAction>> = pending.map { pendingId ->
        val record = records.firstOrNull { record ->
            val keys = NoticeGrouping.keys(record, institution)
            pendingId in keys || NoticeGrouping.matches(keys, setOf(pendingId))
        }
        when {
            record == null -> pendingId to PendingAction.DROP
            (NoticeGrouping.keys(record, institution) + pendingId).any { it in retired } ->
                pendingId to PendingAction.DROP
            else -> pendingId to PendingAction.REPLAY
        }
    }

    /**
     * Replays journaled reconciliations; each replayed handle() clears its
     * entry on success. While consent or onboarding is withdrawn the replay
     * stays closed: automation is never recreated from stale markers, and the
     * markers simply wait until consent returns or the records are wiped.
     * A record tombstoned between capture and replay is dropped the same way
     * a missing record is.
     */
    suspend fun replayPending(context: android.content.Context) {
        val pending = ReconcileJournal.pending(context)
        if (pending.isEmpty()) return
        val repository = runCatching { kr.mom.probe.data.ProbeRepository.get(context) }.getOrNull() ?: return
        // Replay decides against the committed database, not the cached flow:
        // a marker whose row exists but whose snapshot has not published yet is
        // still reconciled, and when the DB cannot be read the markers simply
        // wait instead of being dropped as ghosts.
        val committed = runCatching { repository.committedRecordsSnapshot() }.getOrNull() ?: return
        val settings = repository.settings.value
        if (!settings.consent || settings.consentVersion != ProbeRules.CONSENT_VERSION || !settings.onboardingDone) return
        val institution = NoticeGrouping.institution(settings)
        val retired = ReconcileJournal.retiredKeys(context)
        val actions = pendingActions(committed, pending, retired, institution)
        actions.forEach { (pendingId, action) ->
            when (action) {
                PendingAction.DROP -> ReconcileJournal.clearPending(context, pendingId)
                PendingAction.REPLAY -> {
                    val record = committed.firstOrNull { record ->
                        val keys = NoticeGrouping.keys(record, institution)
                        pendingId in keys || NoticeGrouping.matches(keys, setOf(pendingId))
                    }
                    when {
                        record == null -> ReconcileJournal.clearPending(context, pendingId)
                        repository.isSuppressed(record.id) ->
                            ReconcileJournal.clearPending(context, pendingId)
                        else -> handle(context, record, settings)
                    }
                }
            }
        }
    }
}
