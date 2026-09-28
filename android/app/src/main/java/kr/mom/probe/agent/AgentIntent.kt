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
