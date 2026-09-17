package kr.mom.probe.data

data class NotificationCandidate(
    val kind: Kind,
    val items: List<String>,
    val dueText: String?,
    val confidence: String = "확인 필요",
    val dueAt: Long? = null,
) {
    enum class Kind { PREPARE, SUBMIT, DEADLINE }
}

/** Small deterministic hint parser. It never creates an Action or schedules an alarm. */
object NotificationCandidateParser {
    fun parse(record: ProbeRecord, child: ChildNoticeProfile = ChildNoticeProfile()): NotificationCandidate? {
        val decision = NoticeDecisionEngine.decide(record, child)
        val action = decision.action ?: return null
        if (decision.applicability != NoticeApplicability.APPLIES) return null
        if (decision.obligation != NoticeObligation.REQUIRED || !action.required) return null
        val kind = when {
            !action.required -> NotificationCandidate.Kind.DEADLINE
            action.label.contains("준비") -> NotificationCandidate.Kind.PREPARE
            action.label.contains("마감") -> NotificationCandidate.Kind.DEADLINE
            else -> NotificationCandidate.Kind.SUBMIT
        }
        val items = NoticeDecisionEngine.extractItems(listOf(record.title, record.bigText, record.text, record.textLines.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(" "))
        return NotificationCandidate(
            kind = kind,
            items = items,
            dueText = action.whenText,
            dueAt = action.dueAt,
            confidence = decision.contentState.label,
        )
    }
}
