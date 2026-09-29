package kr.mom.probe.agent

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.LocalTime
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NotificationCandidate
import kr.mom.probe.data.NotificationCandidateParser
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeDecision
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.reminder.ExternalAlarmHandler
import kr.mom.probe.sync.SourceRecordSelectors
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.task.AssistantTask

data class LocalAgentContext(
    val childName: String,
    val notifications: List<ProbeRecord>,
    val tasks: List<AssistantTask>,
    val childProfile: ChildNoticeProfile = ChildNoticeProfile(),
    val sourceScopes: List<SourceScope> = emptyList(),
    val sourceStatusMessage: String? = null,
    val institution: String = "",
)

data class LocalAgentReply(
    val message: String,
    /** A local, reversible task save request. The UI may save it and offer undo. */
    val proposedTask: String? = null,
    val proposedDueAt: Long? = null,
    val proposedRemindAt: Long? = null,
    val scheduleCommand: ScheduleCommand? = null,
    /** Typed classification. Null for legacy chat-style answers; capture sets it. */
    val intent: AgentIntent? = null,
)

/**
 * Deterministic, on-device assistant for the family's locally stored information.
 * It intentionally does not answer from general knowledge or imply that it searched
 * notifications which are not present in [LocalAgentContext].
 */
class LocalAgentEngine(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val defaultEventDurationMinutes: Int = ScheduleCommandParser.DEFAULT_DURATION_MINUTES,
) {
    private val scheduleParser = ScheduleCommandParser(nowMillis, zoneId, defaultEventDurationMinutes)

    fun answer(question: String, context: LocalAgentContext): LocalAgentReply {
        val cleanQuestion = question.trim().replace(whitespace, " ")
        if (cleanQuestion.isBlank()) {
            return LocalAgentReply("궁금한 일이나 챙길 일을 적어주세요.")
        }

        when (val schedule = scheduleParser.parse(cleanQuestion)) {
            is CalendarCreateCommand -> return LocalAgentReply(
                message = "일정 내용을 확인했어요. 저장할 캘린더를 확인한 뒤 이 기기 CalendarProvider에 저장할게요.",
                scheduleCommand = schedule,
            )
            is AlarmRequestCommand -> return LocalAgentReply(
                message = "알람 요청을 확인했어요. 표준 시계 앱이 이 날짜 요청을 받을 수 있는지 확인할게요.",
                scheduleCommand = schedule,
            )
            is ScheduleClarification -> return LocalAgentReply(schedule.message, scheduleCommand = schedule)
            is ScheduleNoMutation -> Unit
        }

        proposedTask(cleanQuestion)?.let { task ->
            return LocalAgentReply(
                message = "‘${task.text}’${task.dueAt?.let { " (${absoluteDate(it)})" }.orEmpty()}를 이 기기 부탁 목록에 저장할게요.",
                proposedTask = task.text,
                proposedDueAt = task.dueAt,
                proposedRemindAt = task.remindAt,
            )
        }

        val pendingTasks = context.tasks.filter { !it.completed && !it.suspended }.sortedByDescending { it.createdAt }
        val linkedKeySets = context.tasks.map { it.noticeGroupKeys + listOfNotNull(it.sourceNotificationId) }
            .filter { it.isNotEmpty() }
        val policyRecords = SourceRecordSelectors.activeRecords(context.notifications, context.sourceScopes, now = nowMillis())
        val groupIds = NoticeGrouping.groupIds(policyRecords, context.institution)
        val notices = policyRecords.sortedByDescending { maxOf(it.postedAt, it.receivedAt) }
            .distinctBy { groupIds.getValue(it.id) }
        val decisions = notices.map { it to NoticeDecisionEngine.decide(it, context.childProfile) }
        val candidates = notices.mapNotNull { record ->
            val recordKeys = NoticeGrouping.keys(record, context.institution)
            if (linkedKeySets.any { NoticeGrouping.matches(recordKeys, it) }) return@mapNotNull null
            val decision = NoticeDecisionEngine.decide(record, context.childProfile)
            NotificationCandidateParser.parse(record, context.childProfile)?.let { CandidateNotice(record, it, decision) }
        }
        val agenda = SourceRecordSelectors.agenda(context.notifications, context.childProfile, context.sourceScopes, now = nowMillis(), daysAhead = 7, institution = context.institution)
        val name = context.childName.trim().ifBlank { "아이" }

        val reply = when {
            asksCapabilities(cleanQuestion) -> LocalAgentReply(
                "저는 이 휴대폰에 모인 $name 알림에서 준비물·제출·마감을 찾아 답하고, 미완료 부탁을 확인해드려요. " +
                    "‘물티슈 챙겨줘’처럼 명확한 부탁은 이 기기에 암호화해 저장하고, 방금 저장한 것은 바로 취소할 수 있어요. " +
                    "캘린더 일정은 엄마가 고른 캘린더 계정에만 저장을 시도해요."
            )
            asksPendingTasks(cleanQuestion) -> LocalAgentReply(tasksAnswer(pendingTasks))
            asksRecentNotifications(cleanQuestion) -> LocalAgentReply(notificationsAnswer(notices))
            asksAgenda(cleanQuestion) -> LocalAgentReply(agendaAnswer(cleanQuestion, agenda, name))
            asksApplicability(cleanQuestion) -> LocalAgentReply(applicabilityAnswer(cleanQuestion, decisions, name))
            asksActionCandidates(cleanQuestion) -> LocalAgentReply(
                candidateAnswer(cleanQuestion, candidates, pendingTasks, name),
            )
            else -> LocalAgentReply(
                "지금은 이 휴대폰에 저장된 $name 알림과 부탁에 관한 질문만 답할 수 있어요. " +
                    "예를 들어 ‘내일 뭐 챙겨야 해?’, ‘제출할 것 있어?’, ‘미완료 부탁 보여줘’라고 물어보세요."
            )
        }
        val shouldExplainSourceState = asksRecentNotifications(cleanQuestion) || asksAgenda(cleanQuestion) ||
            asksActionCandidates(cleanQuestion) || asksCapabilities(cleanQuestion)
        return if (shouldExplainSourceState && !context.sourceStatusMessage.isNullOrBlank()) {
            reply.copy(message = "${reply.message}\n수집 상태: ${context.sourceStatusMessage}")
        } else {
            reply
        }
    }

    /**
     * Classifies a spoken/typed capture into a typed [CapturePlan]. This is
     * the only classification engine — callers assemble context, show the
     * preview, and may write only when [CapturePlan.saveable] and after an
     * explicit user confirm. Nothing here persists or schedules anything.
     *
     * Dispositions follow the quiet-capture contract: dated action →
     * KEEP_TODAY, undated action → REVIEW_LATER, plain note → MEMO_ONLY,
     * clarification/alarm/unparseable → NEEDS_CONFIRM, question → answered
     * inline and never stored.
     */
    fun capture(rawText: String, context: LocalAgentContext): CapturePlan {
        val text = AgentIdentity.stripWakeName(rawText).trim().replace(whitespace, " ")
        if (text.isBlank()) {
            return CapturePlan(
                transcript = rawText.trim(),
                reply = LocalAgentReply("무슨 말인지 듣지 못했어요. 다시 말해주세요."),
                intent = AgentIntent.MEMO,
                disposition = CaptureDisposition.NEEDS_CONFIRM,
                labels = listOf(CaptureLabels.USER_SPOKE, CaptureLabels.NO_SAVE),
                saveable = false,
            )
        }

        val reply = answer(text, context)
        when (reply.scheduleCommand) {
            is CalendarCreateCommand -> return plan(
                text, reply.copy(intent = AgentIntent.CALENDAR), AgentIntent.CALENDAR,
                CaptureDisposition.KEEP_TODAY,
                listOf(CaptureLabels.USER_SPOKE, AgentIntent.CALENDAR.label),
                saveable = true,
            )
            is AlarmRequestCommand -> return plan(
                text, reply.copy(intent = AgentIntent.REMINDER), AgentIntent.REMINDER,
                CaptureDisposition.NEEDS_CONFIRM,
                listOf(CaptureLabels.USER_SPOKE, CaptureLabels.EXTERNAL_APP, CaptureLabels.NO_SAVE),
                saveable = false,
            )
            is ScheduleClarification -> return plan(
                text, reply.copy(intent = AgentIntent.MEMO), AgentIntent.MEMO,
                CaptureDisposition.NEEDS_CONFIRM,
                listOf(CaptureLabels.USER_SPOKE, CaptureDisposition.NEEDS_CONFIRM.label),
                saveable = false,
            )
            else -> Unit
        }

        reply.proposedTask?.let { taskText ->
            val intent = when {
                reply.proposedRemindAt != null -> AgentIntent.REMINDER
                shoppingMarker.containsMatchIn(taskText) -> AgentIntent.SHOPPING
                else -> AgentIntent.TASK
            }
            return plan(
                text, reply.copy(intent = intent), intent,
                if (reply.proposedDueAt != null) CaptureDisposition.KEEP_TODAY else CaptureDisposition.REVIEW_LATER,
                listOfNotNull(
                    CaptureLabels.USER_SPOKE,
                    intent.label,
                    if (reply.proposedDueAt == null) CaptureLabels.NEEDS_DATE else null,
                ),
                saveable = true,
            )
        }

        obligationProposal(text)?.let { proposed ->
            val intent = if (shoppingMarker.containsMatchIn(proposed.text)) AgentIntent.SHOPPING else AgentIntent.TASK
            return plan(
                text,
                LocalAgentReply(
                    message = "‘${proposed.text}’${proposed.dueAt?.let { " (${absoluteDate(it)})" }.orEmpty()}를 이 기기 부탁 목록에 저장할게요.",
                    proposedTask = proposed.text,
                    proposedDueAt = proposed.dueAt,
                    proposedRemindAt = proposed.remindAt,
                    intent = intent,
                ),
                intent,
                if (proposed.dueAt != null) CaptureDisposition.KEEP_TODAY else CaptureDisposition.REVIEW_LATER,
                listOfNotNull(
                    CaptureLabels.USER_SPOKE,
                    intent.label,
                    if (proposed.dueAt == null) CaptureLabels.NEEDS_DATE else null,
                    CaptureLabels.RULE_ESTIMATE,
                ),
                saveable = true,
            )
        }

        if (looksLikeQuestion(text)) {
            return plan(
                text, reply.copy(intent = AgentIntent.QUESTION), AgentIntent.QUESTION,
                disposition = null,
                labels = listOf(CaptureLabels.USER_SPOKE, AgentIntent.QUESTION.label, CaptureLabels.NO_SAVE),
                saveable = false,
            )
        }

        return plan(
            text,
            reply.copy(
                message = "‘$text’를 메모로 남길게요. 할 일이나 알림은 만들지 않아요.",
                intent = AgentIntent.MEMO,
            ),
            AgentIntent.MEMO, CaptureDisposition.MEMO_ONLY,
            listOf(CaptureLabels.USER_SPOKE, AgentIntent.MEMO.label),
            saveable = true,
        )
    }

    /**
     * Splits a brain-dump utterance into ordered clauses and classifies each
     * one independently. Splitting is deliberately conservative — only real
     * sentence boundaries (`.`, `!`, `?`, `…`, newline), comma-separated
     * joins, and standalone conjunction tokens (그리고/또한/그 다음/또) —
     * so a clause never invents child, date, time, context, or action that
     * was not in the spoken words.
     */
    fun captureBatch(rawText: String, context: LocalAgentContext, captureId: String): CaptureBatch {
        val clauses = splitClauses(rawText)
        return CaptureBatch(
            captureId = captureId,
            clauses = clauses.mapIndexed { index, clauseText ->
                CaptureClause(index = index, transcript = clauseText, plan = capture(clauseText, context))
            },
        )
    }

    /**
     * Deterministic conservative clause splitter. Splits on sentence-ending
     * punctuation and newlines, on commas that join clauses, and on
     * standalone conjunction words at a word boundary. Never merges,
     * reorders, or rewrites the spoken text — a fragment stays a fragment
     * and lands in memo/review rather than being turned into an action.
     */
    internal fun splitClauses(text: String): List<String> {
        // Hard boundaries first: sentence-ending punctuation and line breaks.
        // Then soft joins: a comma, or a standalone conjunction token at a
        // word boundary ("그리고", "또한", "그 다음", or bare "또" between
        // spaces — never inside a word like 또박또박).
        return text.split(hardBoundary)
            .flatMap { it.split(softJoin) }
            .map { it.trim().trim(',', '，', '、') }
            .filter { it.isNotBlank() }
    }

    private fun plan(
        transcript: String,
        reply: LocalAgentReply,
        intent: AgentIntent,
        disposition: CaptureDisposition?,
        labels: List<String>,
        saveable: Boolean,
    ): CapturePlan = CapturePlan(
        transcript = transcript,
        reply = reply, intent = intent, disposition = disposition,
        labels = labels, saveable = saveable,
    )

    /**
     * Obligation statements without a command verb — "금요일까지 체육복 사야 돼"
     * — still become task *candidates*, but carry the rule-estimate label so
     * the preview is honest about how the interpretation was derived.
     */
    private fun obligationProposal(text: String): ProposedTask? {
        if (interrogative.containsMatchIn(text)) return null
        if (!obligationMarker.containsMatchIn(text)) return null
        val normalized = text.take(MAX_PROPOSED_TASK_LENGTH).trim()
        if (normalized.isBlank()) return null
        val dueAt = resolveCommandDue(normalized)
        return ProposedTask(normalized, dueAt, null)
    }

    private fun looksLikeQuestion(text: String): Boolean =
        asksCapabilities(text) || asksPendingTasks(text) || asksRecentNotifications(text) ||
            asksAgenda(text) || asksApplicability(text) || asksActionCandidates(text) ||
            interrogative.containsMatchIn(text) || questionTail.containsMatchIn(text)

    private fun proposedTask(question: String): ProposedTask? {
        val match = taskCommand.matchEntire(question) ?: return null
        var subject = match.groupValues[1].trim(' ', ',', '.', '!', '?', '~')
            .let { AgentIdentity.stripWakeName(it) }
        subject = subject.replace(taskListPrefix, "").trim()
        if (subject.isBlank() || interrogative.containsMatchIn(subject)) return null

        val command = match.groupValues[2].replace(" ", "")
        if (command == "알려줘") {
            val requestedTime = relativeCommandDate.find(subject)?.groupValues?.getOrNull(3)?.isNotBlank() == true
            val looksLikeLookup = prepareQuestion.containsMatchIn(subject) || submitQuestion.containsMatchIn(subject) ||
                deadlineQuestion.containsMatchIn(subject) || recentNotificationQuestion.containsMatchIn(subject) ||
                taskQuestion.containsMatchIn(subject)
            if (!requestedTime || looksLikeLookup) return null
        }
        val task = when (command) {
            "챙겨줘" -> if (subject.endsWith("챙기기")) subject else "$subject 챙기기"
            else -> subject
        }
        val text = task.take(MAX_PROPOSED_TASK_LENGTH).trim().ifBlank { return null }
        val dueAt = resolveCommandDue(text)
        val reminder = dueAt?.takeIf { command == "알려줘" }
        return ProposedTask(text, dueAt, reminder)
    }

    private fun resolveCommandDue(text: String): Long? {
        val match = relativeCommandDate.find(text) ?: return null
        val base = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        val date = base.plusDays(when (match.groupValues[1]) { "오늘" -> 0; "내일" -> 1; "모레" -> 2; else -> return null })
        val rawHour = match.groupValues[3].toIntOrNull()
        if (rawHour == null) return null
        val time = run {
            val minute = match.groupValues[4].toIntOrNull() ?: 0
            val hour = when (match.groupValues[2]) {
                "오전" -> if (rawHour == 12) 0 else rawHour
                "오후" -> if (rawHour == 12) 12 else rawHour + 12
                else -> rawHour
            }
            runCatching { LocalTime.of(hour, minute) }.getOrNull() ?: return null
        }
        return date.atTime(time).atZone(zoneId).toInstant().toEpochMilli().takeIf { it > nowMillis() }
    }

    private fun absoluteDate(value: Long): String = Instant.ofEpochMilli(value).atZone(zoneId).let {
        "${it.monthValue}월 ${it.dayOfMonth}일 %02d:%02d".format(it.hour, it.minute)
    }

    private fun tasksAnswer(tasks: List<AssistantTask>): String {
        if (tasks.isEmpty()) return "미완료 부탁은 없어요. 새로 기억할 일이 있으면 ‘○○ 기억해줘’라고 말해주세요."
        val lines = tasks.take(MAX_RESULTS).mapIndexed { index, task -> "${index + 1}. ${task.text}" }
        val suffix = if (tasks.size > MAX_RESULTS) "\n그 밖에 ${tasks.size - MAX_RESULTS}개가 더 있어요." else ""
        return "미완료 부탁 ${tasks.size}개예요.\n${lines.joinToString("\n")}$suffix"
    }

    private fun notificationsAnswer(notifications: List<ProbeRecord>): String {
        if (notifications.isEmpty()) return "이 휴대폰에 저장된 알림이 아직 없어요. 연결한 앱에서 새 알림을 받으면 여기서 함께 살펴볼 수 있어요."
        val lines = notifications.take(MAX_RESULTS).map { record ->
            val title = record.title.ifBlank { record.primaryText().take(60).ifBlank { "제목 없는 알림" } }
            "• ${record.appLabel}: $title"
        }
        val suffix = if (notifications.size > MAX_RESULTS) "\n최근 ${MAX_RESULTS}개만 보여드렸어요." else ""
        return "최근 알림이에요.\n${lines.joinToString("\n")}$suffix"
    }

    private fun candidateAnswer(
        question: String,
        candidates: List<CandidateNotice>,
        pendingTasks: List<AssistantTask>,
        childName: String,
    ): String {
        val dueWord = dueWords.firstOrNull { question.contains(it) }
        val kinds = when {
            prepareQuestion.containsMatchIn(question) -> setOf(NotificationCandidate.Kind.PREPARE)
            submitQuestion.containsMatchIn(question) -> setOf(NotificationCandidate.Kind.SUBMIT)
            deadlineQuestion.containsMatchIn(question) -> setOf(
                NotificationCandidate.Kind.SUBMIT,
                NotificationCandidate.Kind.DEADLINE,
            )
            else -> NotificationCandidate.Kind.entries.toSet()
        }
        val matchingCandidates = candidates.filter { candidate ->
            candidate.decision.isRequiredForChild() &&
            candidate.candidate.kind in kinds && matchesDue(candidate.decision, dueWord)
        }
        val matchingTasks = pendingTasks.filter { task ->
            matchesDue(task.dueAt, task.text, dueWord) && when {
                prepareQuestion.containsMatchIn(question) -> task.text.containsAny("준비", "챙", "가져")
                submitQuestion.containsMatchIn(question) -> task.text.containsAny("제출", "신청", "회신", "납부", "입금")
                deadlineQuestion.containsMatchIn(question) -> task.text.containsAny("마감", "기한", "까지", "제출", "신청")
                else -> true
            }
        }

        if (matchingCandidates.isEmpty() && matchingTasks.isEmpty()) {
            val range = dueWord?.let { "$it " }.orEmpty()
            return "저장된 $childName 알림과 미완료 부탁에서는 ${range}해당하는 일을 찾지 못했어요. 원문 앱에 새 공지가 있다면 아직 이 휴대폰에 수집되지 않았을 수 있어요."
        }

        val lines = buildList {
            matchingCandidates.take(MAX_RESULTS).forEach { notice -> add(notice.displayLine()) }
            matchingTasks.take((MAX_RESULTS - size).coerceAtLeast(0)).forEach { task -> add("• 부탁: ${task.text}") }
        }
        val total = matchingCandidates.size + matchingTasks.size
        val suffix = if (total > lines.size) "\n그 밖에 ${total - lines.size}개가 더 있어요." else ""
        return "확인할 일 ${total}개를 찾았어요.\n${lines.joinToString("\n")}$suffix"
    }

    private fun applicabilityAnswer(
        question: String,
        decisions: List<Pair<ProbeRecord, NoticeDecision>>,
        childName: String,
    ): String {
        val matched = decisions.firstOrNull { (record, decision) ->
            val text = "${record.title} ${record.text} ${record.bigText} ${record.textLines.joinToString(" ")}"
            question.split(" ").filter { it.length >= 2 }.any { token -> text.contains(token) } ||
                decision.obligation == NoticeObligation.OPTIONAL_OPPORTUNITY
        } ?: return "저장된 $childName 알림에서는 해당 대상을 판단할 공지를 찾지 못했어요."
        val (record, decision) = matched
        return when (decision.applicability) {
            NoticeApplicability.INELIGIBLE -> "${record.title.ifBlank { "이 안내" }}는 ${decision.applicabilityReason}"
            NoticeApplicability.UNKNOWN -> "${record.title.ifBlank { "이 안내" }}는 ${decision.applicabilityReason}"
            NoticeApplicability.APPLIES -> "${record.title.ifBlank { "이 안내" }}는 ${decision.applicabilityReason}"
        }
    }

    private fun agendaAnswer(
        question: String,
        agenda: List<kr.mom.probe.sync.SourceAgendaItem>,
        childName: String,
    ): String {
        val today = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        val filtered = when {
            question.contains("오늘") -> agenda.filter { it.dateIso == today.toString() }
            question.contains("내일") -> agenda.filter { it.dateIso == today.plusDays(1).toString() }
            question.contains("이번 주") -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = start.plusDays(6)
                agenda.filter {
                    val date = runCatching { LocalDate.parse(it.dateIso) }.getOrNull()
                    date != null && !date.isBefore(start) && !date.isAfter(end)
                }
            }
            else -> agenda
        }
        if (filtered.isEmpty()) return "저장된 $childName 소식에서는 해당하는 학교 일정을 찾지 못했어요. 아직 조회하지 못했거나, 출처가 일부만 확인됐을 수 있어요."
        val lines = filtered.take(MAX_RESULTS).map { item -> "• ${displayDate(item.dateIso)} ${item.title} (${item.sourceLabel})" }
        val suffix = if (filtered.size > MAX_RESULTS) "\n그 밖에 ${filtered.size - MAX_RESULTS}개가 더 있어요." else ""
        return "저장된 학교 일정 ${filtered.size}개예요.\n${lines.joinToString("\n")}$suffix"
    }

    private fun displayDate(dateIso: String): String =
        runCatching { LocalDate.parse(dateIso) }.getOrNull()?.let { "${it.monthValue}월 ${it.dayOfMonth}일" } ?: dateIso

    private fun CandidateNotice.displayLine(): String {
        val type = when (candidate.kind) {
            NotificationCandidate.Kind.PREPARE -> "준비"
            NotificationCandidate.Kind.SUBMIT -> "제출"
            NotificationCandidate.Kind.DEADLINE -> "마감"
        }
        val detail = candidate.items.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: record.title.ifBlank { record.primaryText().take(70).ifBlank { "내용 확인 필요" } }
        val due = candidate.dueAt?.let {
            val dateTime = Instant.ofEpochMilli(it).atZone(zoneId)
            val prefix = if (it < nowMillis()) "지난 기한 " else ""
            " · $prefix${dateTime.monthValue}월 ${dateTime.dayOfMonth}일 %02d:%02d".format(dateTime.hour, dateTime.minute)
        } ?: candidate.dueText?.let { " · 원문 $it" }.orEmpty()
        return "• $type: $detail$due (${record.appLabel})"
    }

    private fun matchesDue(dueAt: Long?, rawText: String?, dueWord: String?): Boolean {
        if (dueWord == null) return true
        if (dueAt == null) return rawText?.contains(dueWord) == true
        val today = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        val dueDate = Instant.ofEpochMilli(dueAt).atZone(zoneId).toLocalDate()
        return when (dueWord) {
            "오늘" -> dueDate == today
            "내일" -> dueDate == today.plusDays(1)
            "모레" -> dueDate == today.plusDays(2)
            "이번 주" -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                !dueDate.isBefore(start) && !dueDate.isAfter(start.plusDays(6))
            }
            "다음 주" -> {
                val start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                !dueDate.isBefore(start) && !dueDate.isAfter(start.plusDays(6))
            }
            else -> false
        }
    }

    private fun matchesDue(decision: NoticeDecision, dueWord: String?): Boolean {
        if (dueWord == null) return true
        val dueFacts = decision.dates.filter { it.role == kr.mom.probe.data.NoticeDateRole.DUE }
        if (dueFacts.size != 1) return false
        val dueDate = dueFacts.single().dateIso
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (dueDate == null) return matchesDue(decision.action?.dueAt, decision.action?.whenText, dueWord)
        val today = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
        return when (dueWord) {
            "오늘" -> dueDate == today
            "내일" -> dueDate == today.plusDays(1)
            "모레" -> dueDate == today.plusDays(2)
            "이번 주" -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                !dueDate.isBefore(start) && !dueDate.isAfter(start.plusDays(6))
            }
            "다음 주" -> {
                val start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                !dueDate.isBefore(start) && !dueDate.isAfter(start.plusDays(6))
            }
            else -> false
        }
    }

    private fun ProbeRecord.primaryText(): String = sequenceOf(bigText, text, textLines.joinToString(" "))
        .firstOrNull { it.isNotBlank() }.orEmpty().replace(whitespace, " ").trim()

    private fun String.containsAny(vararg values: String) = values.any(::contains)

    private fun asksCapabilities(question: String) = capabilityQuestion.containsMatchIn(question)
    private fun asksPendingTasks(question: String) = taskQuestion.containsMatchIn(question)
    private fun asksRecentNotifications(question: String) = recentNotificationQuestion.containsMatchIn(question)
    private fun asksAgenda(question: String) = agendaQuestion.containsMatchIn(question) ||
        ((question.contains("오늘") || question.contains("내일") || question.contains("이번 주")) && !actionQuestion.containsMatchIn(question))
    private fun asksActionCandidates(question: String) = actionQuestion.containsMatchIn(question)
    private fun asksApplicability(question: String) = applicabilityQuestion.containsMatchIn(question)

    private data class CandidateNotice(
        val record: ProbeRecord,
        val candidate: NotificationCandidate,
        val decision: NoticeDecision,
    )
    private data class ProposedTask(val text: String, val dueAt: Long?, val remindAt: Long?)

    companion object {
        private const val MAX_RESULTS = 5
        private const val MAX_PROPOSED_TASK_LENGTH = 280
        private val whitespace = Regex("\\s+")
        private val hardBoundary = Regex("[.!?…。\\n]+")
        private val softJoin = Regex(
            "[,，、]|(?:^|\\s)(?:그리고|또한|그\\s*다음(?:에)?|또)\\s+",
        )
        private val taskCommand = Regex("^(.+?)\\s*(챙겨\\s*줘|기억해\\s*줘|추가해\\s*줘|알려\\s*줘)[.!?~\\s]*$")
        private val relativeCommandDate = Regex("(오늘|내일|모레)(?:\\s*(오전|오후)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*시)?")
        private val taskListPrefix = Regex("^(?:부탁(?:\\s*목록)?|할\\s*일|준비물)(?:에|으로)?\\s+")
        private val interrogative = Regex("(?:뭐|무엇|어떤|언제|어디|누구|왜|어떻게)(?:를|을|가|이|야|지|죠|요)?(?:\\s|$)")
        private val capabilityQuestion = Regex("뭘?\\s*할\\s*수|무엇을\\s*할\\s*수|도와줄\\s*수|사용법|어떻게\\s*써")
        private val obligationMarker = Regex(
            "해야\\s*(?:돼|해|지|함)|사야\\s*(?:돼|해)|챙겨야|내야\\s*(?:돼|해)|납부해야|제출해야|신청해야|준비해야|가져가야|알아봐야|확인해야|해야\\s*할\\s*(?:거|것)",
        )
        private val shoppingMarker = Regex("사야\\s*(?:돼|해)|사\\s*줘|사다|구매|장보|주문|사러\\s*가")
        private val questionTail = Regex("""[?？]\s*$|(?:뭐야|뭐냐|뭐지|있어\??|있니|없니|누구야|언제야|어디야)\s*[?？]?\s*$""")
        private val taskQuestion = Regex("부탁|할\\s*일|해야\\s*할\\s*일|기억한|기억해\\s*둔")
        private val recentNotificationQuestion = Regex("최근|새(?:로운)?\\s*알림|무슨\\s*알림|받은\\s*알림|공지\\s*(?:보여|알려)")
        private val agendaQuestion = Regex("일정|행사|학사")
        private val actionQuestion = Regex("준비물|준비해야|챙겨|가져가|제출|신청|회신|마감|기한|납부|입금|놓친|해야\\s*해|할\\s*거|할\\s*것")
        private val applicabilityQuestion = Regex("대상|해당|가능|체험|모집|토요|미래산책")
        private val prepareQuestion = Regex("준비물|준비해야|챙겨|가져가")
        private val submitQuestion = Regex("제출|신청|회신|납부|입금")
        private val deadlineQuestion = Regex("마감|기한|언제까지")
        private val dueWords = listOf("오늘", "내일", "모레", "이번 주", "다음 주")

        private val isoDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?$""")
        private val koreanDate = Regex("""^(\d{1,2})월\s*(\d{1,2})일(?:\s*(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?)?$""")
        private val relativeDay = Regex("""^(오늘|내일|모레)(?:\s*(오전|오후)?\s*(\d{1,2})(?::(\d{1,2}))?\s*시(?:\s*(\d{1,2})분)?)?$""")

        /**
         * Parses a user-edited date/time field: `yyyy-MM-dd [HH:mm]`,
         * `M월 d일 [오전/오후 h시[mm분]]`, or `오늘/내일/모레 [오전/오후 h시[mm분]]`.
         * Returns null on blank or unparseable input — never guesses.
         */
        fun parseEditableDateTime(
            input: String,
            nowMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
        ): Long? {
            val text = input.trim().replace(whitespace, " ")
            if (text.isEmpty()) return null
            fun atTime(year: Int, month: Int, day: Int, meridiem: String?, hour: Int?, minute: Int?): Long? {
                val h = when {
                    hour == null -> 9 // undated-time default: morning, shown as date-only
                    meridiem == "오전" -> if (hour == 12) 0 else hour
                    meridiem == "오후" -> if (hour == 12) 12 else hour + 12
                    else -> hour
                }
                return runCatching {
                    LocalDate.of(year, month, day).atTime(LocalTime.of(h, minute ?: 0))
                        .atZone(zoneId).toInstant().toEpochMilli()
                }.getOrNull()
            }
            isoDate.matchEntire(text)?.let { m ->
                return atTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    null, m.groupValues[4].toIntOrNull(), m.groupValues[5].toIntOrNull())
            }
            koreanDate.matchEntire(text)?.let { m ->
                val year = Instant.ofEpochMilli(nowMillis).atZone(zoneId).year
                return atTime(year, m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                    m.groupValues[3].ifBlank { null }, m.groupValues[4].toIntOrNull(), m.groupValues[5].toIntOrNull())
            }
            relativeDay.matchEntire(text)?.let { m ->
                val base = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
                val date = base.plusDays(when (m.groupValues[1]) { "오늘" -> 0; "내일" -> 1; else -> 2 })
                return atTime(date.year, date.monthValue, date.dayOfMonth,
                    m.groupValues[2].ifBlank { null }, m.groupValues[3].toIntOrNull(), m.groupValues[5].toIntOrNull()
                        ?: m.groupValues[4].toIntOrNull())
            }
            return null
        }
    }
}

internal fun resolveExternalAlarmHandlerForPrepare(
    explicit: ExternalAlarmHandler?,
    selectedHandler: () -> ExternalAlarmHandler?,
): ExternalAlarmHandler? = explicit ?: selectedHandler()
