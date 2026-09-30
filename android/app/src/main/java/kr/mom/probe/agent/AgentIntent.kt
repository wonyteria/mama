package kr.mom.probe.agent

/**
 * Typed intent for a captured utterance. Classification is owned solely by
 * [LocalAgentEngine]; routers/UI must never implement a second parser.
 *
 * MAMA adaptation of quiet capture buckets (inspired by capture-first
 * assistants): not every utterance becomes a task — a plan declares what
 * it is, where it would land, and whether anything may be written at all.
 */
enum class AgentIntent(val label: String) {
    /** Saveable action on the family task list after explicit confirm. */
    TASK("할 일"),
    /** Task with a reminder slot. */
    REMINDER("알림이 있는 할 일"),
    /** Calendar event — saved via CalendarGateway after confirm only. */
    CALENDAR("캘린더 일정"),
    /** Shopping — same store as TASK, display type differs. */
    SHOPPING("살 것"),
    /** Plain memory-only note; no date, no alarm. */
    MEMO("메모"),
    /** Lookup question — answered from local data, never stored. */
    QUESTION("질문"),
}

/** User-facing bucket for a capture — never auto-confirms anything. */
enum class CaptureDisposition(val label: String) {
    /** Dated or urgent action candidates. */
    KEEP_TODAY("오늘 챙길 일"),
    /** Undated or non-urgent items parked for later review. */
    REVIEW_LATER("나중에 확인"),
    /** Memo-only capture. */
    MEMO_ONLY("메모만"),
    /**
     * Ambiguous, clarification-required, or externally-executed requests
     * (e.g. alarm). Nothing may be written until the parent decides.
     */
    NEEDS_CONFIRM("엄마 확인 필요"),
}

/** Provenance/confidence labels shown on the capture preview card. */
object CaptureLabels {
    const val USER_SPOKE = "엄마가 직접 말함"
    const val NEEDS_DATE = "날짜 확인 필요"
    const val RULE_ESTIMATE = "규칙 추정"
    const val NO_SAVE = "저장하지 않음"
    const val EXTERNAL_APP = "외부 시계 앱 실행"
}

/**
 * Result of [LocalAgentEngine.capture]: the classified reply plus the
 * user-facing disposition and write permission. `saveable` is the single
 * gate — UI must not persist anything unless it is true.
 */
data class CapturePlan(
    val transcript: String,
    val reply: LocalAgentReply,
    val intent: AgentIntent,
    val disposition: CaptureDisposition?,
    val labels: List<String>,
    val saveable: Boolean,
)

/**
 * One editable clause inside a captured brain-dump. The engine produces
 * the initial classification; the parent may rewrite the transcript,
 * action, due/remind time, and disposition before confirming — or drop
 * the clause entirely. A clause is writable only when it is kept, its
 * plan is saveable, and it no longer sits in NEEDS_CONFIRM.
 */
data class CaptureClause(
    val index: Int,
    val transcript: String,
    val plan: CapturePlan,
    /** Editable action text; defaults to the plan's proposed task. */
    val action: String? = null,
    /** Editable due time; defaults to the plan's proposal. */
    val dueAt: Long? = null,
    /** Raw text of the parent's due-date edit; null for picker edits. */
    val dateInput: String? = null,
    /**
     * The parent deliberately changed the date — typed text or picked
     * times. This is the single source of truth for "touched": untouched
     * falls back to the parsed proposal, edited never does.
     */
    val dateEdited: Boolean = false,
    /**
     * The parent's edit named a day but no hour — "시간 없음". The chosen
     * day is preserved as an ISO date (`YYYY-MM-DD`); `dueAt`/`remindAt`
     * stay null because a bare date is not a time.
     */
    val dueDateIso: String? = null,
    /** Editable reminder time; defaults to the plan's proposal. */
    val remindAt: Long? = null,
    /** Editable disposition override; null follows the plan. */
    val dispositionOverride: CaptureDisposition? = null,
    /** Editable write-permission override set only by explicit resolution. */
    val saveableOverride: Boolean? = null,
    /** Editable intent override set only by explicit resolution. */
    val intentOverride: AgentIntent? = null,
    val dropped: Boolean = false,
    /** True once the parent explicitly resolved a NEEDS_CONFIRM clause. */
    val resolved: Boolean = false,
    /** A typed date/time edit could not be parsed — blocks writing. */
    val dateParseFailed: Boolean = false,
    /** Edits applied after the engine run — provenance stays honest. */
    val editedByUser: Boolean = false,
) {
    val disposition: CaptureDisposition? get() = dispositionOverride ?: plan.disposition
    val intent: AgentIntent get() = intentOverride ?: plan.intent

    /**
     * The due time the parent confirmed — tri-state on [dateEdited]:
     * untouched follows the parsed proposal, an explicit clear stays
     * null, a parsed edit wins. Writers must use this, never
     * `dueAt ?: plan.reply.proposedDueAt`.
     */
    val effectiveDueAt: Long?
        get() = if (!dateEdited) dueAt ?: plan.reply.proposedDueAt else dueAt

    /**
     * The reminder time the parent confirmed — the same tri-state. On an
     * explicit date edit a stale proposed reminder is never reused: an
     * explicitly cleared or date-only date has no `dueAt`, so the reminder
     * is null too — a bare date cannot schedule an alarm. A timed edit on
     * a REMINDER clause ("알려줘") follows the edited time since reminding
     * is the whole intent. Untouched follows the proposal.
     */
    val effectiveRemindAt: Long?
        get() = when {
            dateEdited && dueAt == null -> null
            dateEdited -> remindAt ?: (if (intent == AgentIntent.REMINDER) dueAt else null)
            else -> remindAt ?: plan.reply.proposedRemindAt
        }

    /**
     * A calendar clause whose start the parent explicitly cleared or left
     * as a bare date — an event cannot exist without a start time, so the
     * clause stays non-writable until the parent enters a timed date or
     * drops it. Never silently reused or invented.
     */
    val calendarMissingStart: Boolean
        get() = intent == AgentIntent.CALENDAR && dateEdited && effectiveDueAt == null

    /** Writable = kept + saveable + resolved out of the confirm bucket. */
    val writable: Boolean
        get() = !dropped && !dateParseFailed && !calendarMissingStart &&
            (saveableOverride ?: plan.saveable) &&
            disposition != null && disposition != CaptureDisposition.NEEDS_CONFIRM
    /** Kept but not yet writable — blocks batch confirmation. */
    val needsReview: Boolean get() = !dropped && !writable
}

/**
 * Ordered result of a multi-clause capture: the engine splits a brain-dump
 * into clauses conservatively — it never invents child, date, time, or
 * action — and the batch may be written only after an explicit batch
 * confirmation once every kept clause is writable.
 */
data class CaptureBatch(
    val captureId: String,
    val clauses: List<CaptureClause>,
) {
    val kept: List<CaptureClause> get() = clauses.filter { !it.dropped }
    val unresolved: List<CaptureClause> get() = clauses.filter { it.needsReview }
    /** The single gate for any persistence: every kept clause resolved. */
    val saveable: Boolean get() = kept.isNotEmpty() && unresolved.isEmpty()

    fun updateClause(index: Int, transform: (CaptureClause) -> CaptureClause): CaptureBatch =
        copy(clauses = clauses.map { if (it.index == index) transform(it) else it })
}
