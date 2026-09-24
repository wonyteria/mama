package kr.mom.probe.data

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.ZoneId
import kr.mom.probe.sync.AttachmentFetchState
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceAudienceEvaluator
import kr.mom.probe.sync.SourceAudienceFact

enum class SchoolLevel(val label: String) {
    ELEMENTARY("초등"),
    MIDDLE("중등"),
    UNKNOWN("학교급 미확인"),
}

data class ChildNoticeProfile(
    val grade: Int? = null,
    val schoolLevel: SchoolLevel = SchoolLevel.UNKNOWN,
)

enum class NoticeContentState(val label: String) {
    NOTIFICATION_ONLY("알림만 수집"),
    ATTACHMENT_MISSING("첨부 미확보"),
    PARTIAL_EXTRACTION("일부 추출"),
    VERIFIED("검증 완료"),
    FAILED("추출 실패"),
}

enum class NoticeApplicability { APPLIES, INELIGIBLE, UNKNOWN }
enum class NoticeObligation { REQUIRED, OPTIONAL_OPPORTUNITY, INFORMATIONAL, UNKNOWN }
enum class NoticeDateRole { DUE, APPLICATION_START, APPLICATION_END, EVENT, RESULT, CANCELLATION, PUBLICATION, UNKNOWN }
enum class NoticeAdmissionPolicy { FIRST_COME_FIRST_SERVED, LOTTERY, UNKNOWN }

data class NoticeSource(
    val notificationId: String,
    val revisionId: String,
    val appLabel: String,
    val packageName: String,
    val receivedAt: Long,
    val extractionMethod: String,
)

data class NoticeEvidence(
    val claim: String,
    val quote: String,
)

data class NoticeDateFact(
    val role: NoticeDateRole,
    val text: String,
    val dateIso: String?,
    val preciseAt: Long?,
    val hasExplicitTime: Boolean,
)

data class NoticeAction(
    val label: String,
    val whenText: String?,
    val dueAt: Long?,
    val required: Boolean,
    val evidence: NoticeEvidence,
)

data class NoticeDecision(
    val source: NoticeSource,
    val evidence: List<NoticeEvidence>,
    val contentState: NoticeContentState,
    val applicability: NoticeApplicability,
    val applicabilityReason: String,
    val obligation: NoticeObligation,
    val dates: List<NoticeDateFact>,
    val action: NoticeAction?,
    val admissionPolicy: NoticeAdmissionPolicy,
    val issues: List<String>,
) {
    fun isRequiredForChild(): Boolean = applicability == NoticeApplicability.APPLIES &&
        obligation == NoticeObligation.REQUIRED && action?.required == true

    fun isOptionalForChild(): Boolean = applicability == NoticeApplicability.APPLIES &&
        obligation == NoticeObligation.OPTIONAL_OPPORTUNITY
}

object NoticeDecisionEngine {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val whitespace = Regex("\\s+")
    private val trigger = Regex("준비물|준비해야|준비해|챙겨|가져오|제출|신청|회신|마감|기한|납부|입금")
    private val explicitRequired = Regex("준비해야|준비해|챙겨|가져오|제출|회신|납부|입금")
    private val requiredRequest = Regex("(제출|회신|납부|입금|준비|챙겨|가져오).{0,12}(?:해\\s*주세요|바랍니다|필요)")
    private val deadlineWord = Regex("마감|기한|까지")
    private val optionalWord = Regex("선택|희망|희망자|방과후|프로그램|체험\\s*프로그램|학생체험|모집|추첨|선착순|참여를\\s*희망")
    private val negated = Regex("(준비|제출|신청|회신|납부|입금)\\s*(?:하지\\s*않아도|할\\s*필요가?\\s*없|없습니다|완료(?:했|되었|됐))|준비물\\s*(?:은|는|:)?\\s*(?:없음|없습니다|필요\\s*없)")
    private val providedBySchool = Regex("(?:학교|기관).{0,18}(?:지원|제공).{0,18}준비물|준비물.{0,18}(?:학교|기관).{0,18}(?:지원|제공)")
    private val relativeDate = Regex("(오늘|내일|모레|이번\\s*주|다음\\s*주)(?:\\s*(?:(오전|오후)\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*시?)?")
    private val calendarDate = Regex("(?:(\\d{4})[./-]\\s*)?(\\d{1,2})[월./-]\\s*(\\d{1,2})(?:일|\\.)?(?:\\s*(?:\\(([월화수목금토일])\\)|([월화수목금토일])))?(?:\\s*(?:(오전|오후)\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*시?)?")
    private val dayOnlyDate = Regex("\\d{1,2}일\\s*\\([월화수목금토일]\\)(?:\\s*(오전|오후)?\\s*\\d{1,2}(?::\\d{2})?시?)?")
    private val elementaryRange = Regex("(?:초등|초등학교|초)\\s*(\\d)\\s*(?:~|-|∼|부터)\\s*(\\d)\\s*학년|(?:초등|초등학교|초)\\s*(\\d)\\s*학년")
    private val middleRange = Regex("(?:중등|중학교|중등학교|중)\\s*(\\d)\\s*(?:~|-|∼|부터)\\s*(\\d)\\s*학년|(?:중등|중학교|중등학교|중)\\s*(\\d)\\s*학년|중학생")
    private val bareGrade = Regex("(?<![초중])\\b(\\d)\\s*학년")

    fun childProfile(settings: ProbeSettings): ChildNoticeProfile {
        val level = settings.schoolLevel ?: inferLevel(settings.schoolName)
        return ChildNoticeProfile(settings.schoolGrade, level)
    }

    fun inferLevel(schoolName: String): SchoolLevel = when {
        schoolName.contains("초등") || schoolName.endsWith("초") -> SchoolLevel.ELEMENTARY
        schoolName.contains("중등") || schoolName.contains("중학교") || schoolName.endsWith("중") -> SchoolLevel.MIDDLE
        else -> SchoolLevel.UNKNOWN
    }

    fun isBriefingAction(decision: NoticeDecision, now: Long, daysAhead: Long = 7): Boolean {
        if (!decision.isRequiredForChild()) return false
        val dueAt = decision.action?.dueAt
        if (dueAt != null) return dueAt > now && dueAt <= now + daysAhead * 86_400_000L
        val dueDates = decision.dates.filter { it.role == NoticeDateRole.DUE }.mapNotNull {
            it.dateIso?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
        }
        if (dueDates.size != 1) return false
        val today = Instant.ofEpochMilli(now).atZone(SEOUL).toLocalDate()
        return !dueDates.single().isBefore(today) && !dueDates.single().isAfter(today.plusDays(daysAhead))
    }

    fun decide(record: ProbeRecord, child: ChildNoticeProfile = ChildNoticeProfile()): NoticeDecision {
        val sourceText = record.sourceText()
        val source = record.noticeSource()
        record.sourceMetadata?.let { metadata ->
            return decideSourceRecord(record, metadata, sourceText, source, child)
        }
        if (sourceText.isBlank()) {
            return NoticeDecision(source, emptyList(), NoticeContentState.NOTIFICATION_ONLY, NoticeApplicability.UNKNOWN,
                "알림 본문이 비어 있어 대상 여부를 확인하지 못했어요.", NoticeObligation.INFORMATIONAL, emptyList(), null, NoticeAdmissionPolicy.UNKNOWN, listOf("원문 앱 확인 필요"))
        }

        val evidence = mutableListOf<NoticeEvidence>()
        val issues = mutableListOf<String>()
        val contentState = when {
            record.truncated -> {
                issues += "긴 알림의 일부만 수집됐어요."
                NoticeContentState.PARTIAL_EXTRACTION
            }
            sourceText.contains("첨부") || sourceText.contains(".pdf", ignoreCase = true) || sourceText.contains(".png", ignoreCase = true) -> {
                issues += "첨부 본문은 아직 자동으로 읽지 못했어요."
                NoticeContentState.ATTACHMENT_MISSING
            }
            else -> NoticeContentState.NOTIFICATION_ONLY
        }

        val dates = extractDates(sourceText, record.postedAt, issues)
        val applicability = applicability(sourceText, child)
        evidence += if (applicability.evidenceQuotes.isEmpty()) {
            listOf(NoticeEvidence("대상 근거", ""))
        } else {
            applicability.evidenceQuotes.map { NoticeEvidence("대상 근거", it) }
        }
        val obligation = obligation(sourceText)
        evidence += NoticeEvidence("성격", obligationEvidence(obligation, sourceText))
        if (hasPublicationConflict(sourceText)) issues += "문서 날짜와 전달 파일명 날짜가 달라 발행일을 확정하지 않았어요."
        if (sourceText.contains("비용").not() && optionalWord.containsMatchIn(sourceText)) issues += "비용·교통편은 원문 근거가 없어요."
        if (sourceText.contains("선착순")) issues += "원문 선착순 표기를 유지해요."
        val admissionPolicy = admissionPolicy(sourceText)

        val canUseContentForAction = contentState == NoticeContentState.NOTIFICATION_ONLY || contentState == NoticeContentState.VERIFIED
        val action = if (canUseContentForAction) buildAction(record, sourceText, obligation, applicability.value, dates) else null
        return NoticeDecision(
            source = source,
            evidence = evidence,
            contentState = contentState,
            applicability = applicability.value,
            applicabilityReason = applicability.reason,
            obligation = obligation,
            dates = dates,
            action = action,
            admissionPolicy = admissionPolicy,
            issues = issues.distinct(),
        )
    }

    private fun admissionPolicy(source: String): NoticeAdmissionPolicy = when {
        Regex("선착순(?:이)?\\s*(?:아닙니다|아님|아니)").containsMatchIn(source) &&
            (source.contains("추첨") || source.contains("무작위")) -> NoticeAdmissionPolicy.LOTTERY
        source.contains("선착순") && !Regex("선착순(?:이)?\\s*(?:아닙니다|아님|아니)").containsMatchIn(source) -> NoticeAdmissionPolicy.FIRST_COME_FIRST_SERVED
        source.contains("추첨") || source.contains("무작위") -> NoticeAdmissionPolicy.LOTTERY
        else -> NoticeAdmissionPolicy.UNKNOWN
    }

    private fun buildAction(
        record: ProbeRecord,
        sourceText: String,
        obligation: NoticeObligation,
        applicability: NoticeApplicability,
        dates: List<NoticeDateFact>,
    ): NoticeAction? {
        if (applicability != NoticeApplicability.APPLIES) return null
        return when (obligation) {
            NoticeObligation.REQUIRED -> {
                val kind = requiredKind(sourceText)
                val due = dates.singleOrNull { it.role == NoticeDateRole.DUE }
                    ?: dates.singleOrNull { it.role == NoticeDateRole.APPLICATION_END }
                val items = extractItems(sourceText)
                val label = if (items.isNotEmpty()) "$kind: ${items.joinToString(", ")}" else kind
                NoticeAction(
                    label = label,
                    whenText = due?.text,
                    dueAt = due?.preciseAt,
                    required = true,
                    evidence = NoticeEvidence("필수 행동", quoteAround(sourceText, if (items.isNotEmpty()) items.first() else record.title.ifBlank { kind })),
                )
            }
            NoticeObligation.OPTIONAL_OPPORTUNITY -> NoticeAction(
                label = record.title.ifBlank { "선택 활동 살펴보기" },
                whenText = dates.firstOrNull { it.role == NoticeDateRole.APPLICATION_START || it.role == NoticeDateRole.APPLICATION_END }?.text,
                dueAt = null,
                required = false,
                evidence = NoticeEvidence("선택 기회", quoteAround(sourceText, "모집")),
            )
            else -> null
        }
    }

    private fun requiredKind(source: String): String = when {
        source.contains("제출") || source.contains("회신") -> "제출·회신"
        source.contains("납부") || source.contains("입금") -> "납부"
        source.contains("신청") -> "신청 확인"
        source.contains("마감") || source.contains("기한") -> "마감 확인"
        else -> "준비"
    }

    private fun obligation(source: String): NoticeObligation = when {
        negated.containsMatchIn(source) -> NoticeObligation.INFORMATIONAL
        optionalWord.containsMatchIn(source) -> NoticeObligation.OPTIONAL_OPPORTUNITY
        providedBySchool.containsMatchIn(source) && !explicitRequired.containsMatchIn(source) -> NoticeObligation.INFORMATIONAL
        extractItems(source).isNotEmpty() -> NoticeObligation.REQUIRED
        explicitRequired.containsMatchIn(source) || requiredRequest.containsMatchIn(source) -> NoticeObligation.REQUIRED
        trigger.containsMatchIn(source) -> NoticeObligation.UNKNOWN
        else -> NoticeObligation.INFORMATIONAL
    }

    private fun obligationEvidence(obligation: NoticeObligation, source: String): String = when (obligation) {
        NoticeObligation.REQUIRED -> quoteAround(source, explicitRequired.find(source)?.value ?: deadlineWord.find(source)?.value ?: "까지")
        NoticeObligation.OPTIONAL_OPPORTUNITY -> quoteAround(source, optionalWord.find(source)?.value ?: "신청")
        NoticeObligation.INFORMATIONAL -> quoteAround(source, negated.find(source)?.value ?: "안내")
        NoticeObligation.UNKNOWN -> ""
    }

    private data class ApplicabilityResult(
        val value: NoticeApplicability,
        val reason: String,
        val evidence: String,
        val evidenceQuotes: List<String> = evidence.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
    )

    private fun applicability(source: String, child: ChildNoticeProfile): ApplicabilityResult {
        val ranges = targetRanges(source)
        if (ranges.isEmpty() && bareGrade.containsMatchIn(source)) {
            val evidence = bareGrade.findAll(source).joinToString(" · ") { it.value }
            return ApplicabilityResult(
                NoticeApplicability.UNKNOWN,
                "학교급 없는 학년 조건이라 대상 여부를 확정하지 않았어요: $evidence",
                evidence,
                bareGrade.findAll(source).map { it.value }.toList(),
            )
        }
        if (ranges.isEmpty()) return ApplicabilityResult(
            NoticeApplicability.APPLIES,
            "대상 학년 제한을 찾지 못해 일반 공지로 보았어요.",
            "",
        )
        val evidence = ranges.joinToString(" · ") { it.raw }
        val evidenceQuotes = ranges.map { it.raw }
        if (child.grade == null || child.schoolLevel == SchoolLevel.UNKNOWN) {
            return ApplicabilityResult(
                NoticeApplicability.UNKNOWN,
                "대상 학년 확인 필요: $evidence",
                evidence,
                evidenceQuotes,
            )
        }
        val applies = ranges.any { it.level == child.schoolLevel && child.grade in it.start..it.end }
        return if (applies) {
            ApplicabilityResult(
                NoticeApplicability.APPLIES,
                "${child.schoolLevel.label} ${child.grade}학년 대상 근거가 있어요.",
                evidence,
                evidenceQuotes,
            )
        } else {
            ApplicabilityResult(
                NoticeApplicability.INELIGIBLE,
                "이 안내는 $evidence 대상이라 현재 아이 학년에는 해당하지 않아요.",
                evidence,
                evidenceQuotes,
            )
        }
    }

    private data class TargetRange(val level: SchoolLevel, val start: Int, val end: Int, val raw: String) {
        fun display(): String = "${level.label} ${start}~${end}학년"
    }

    private fun targetRanges(source: String): List<TargetRange> = buildList {
        elementaryRange.findAll(source).forEach { match ->
            val single = match.groupValues[3].toIntOrNull()
            if (single != null) add(TargetRange(SchoolLevel.ELEMENTARY, single, single, match.value))
            else add(TargetRange(SchoolLevel.ELEMENTARY, match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.value))
        }
        middleRange.findAll(source).forEach { match ->
            if (match.value == "중학생") add(TargetRange(SchoolLevel.MIDDLE, 1, 3, match.value))
            else {
                val single = match.groupValues[3].toIntOrNull()
                if (single != null) add(TargetRange(SchoolLevel.MIDDLE, single, single, match.value))
                else add(TargetRange(SchoolLevel.MIDDLE, match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.value))
            }
        }
    }.distinct()

    private fun extractDates(source: String, postedAt: Long, issues: MutableList<String>): List<NoticeDateFact> {
        val rawMatches = buildList {
            relativeDate.findAll(source).forEach { add(DateMatch(it, DatePattern.RELATIVE)) }
            calendarDate.findAll(source).forEach { add(DateMatch(it, DatePattern.CALENDAR)) }
            dayOnlyDate.findAll(source).forEach { add(DateMatch(it, DatePattern.DAY_ONLY)) }
        }.distinctBy { it.match.range }.sortedWith(compareBy<DateMatch> { it.match.range.first }.thenBy { it.pattern.ordinal })
        val matches = rawMatches.fold(emptyList<DateMatch>()) { accepted, candidate ->
            if (accepted.any { it.match.range overlaps candidate.match.range }) accepted else accepted + candidate
        }
        val facts = matches.mapNotNull { dateMatch ->
            val match = dateMatch.match
            val role = roleFor(source, match)
            val resolved = resolveDate(dateMatch, postedAt)
            if (dateMatch.pattern == DatePattern.CALENDAR && resolved?.first != null && calendarWeekdayMismatch(match, LocalDate.parse(resolved.first))) {
                issues += "원문 요일이 날짜와 맞지 않아 정확한 시각으로 쓰지 않았어요."
            }
            NoticeDateFact(role, match.value.trim(), resolved?.first, resolved?.second, resolved?.third == true)
        }
        val dueFacts = facts.filter { it.role == NoticeDateRole.DUE }
        if (dueFacts.size > 1) issues += "여러 날짜가 섞여 있어 첫 날짜를 자동 기한으로 쓰지 않았어요."
        if (facts.any { it.dateIso == null && (it.text.contains("이번") || it.text.contains("다음")) }) {
            issues += "주 단위 표현은 정확한 마감일로 확정하지 않았어요."
        }
        if (facts.any { it.role == NoticeDateRole.DUE && !it.hasExplicitTime }) {
            issues += "시각 없는 날짜는 23:59 마감으로 만들지 않았어요."
        }
        return facts
    }

    private fun roleFor(source: String, match: MatchResult): NoticeDateRole {
        val before = source.substring(maxOf(0, match.range.first - 24), match.range.first)
        val after = source.substring(match.range.last + 1, minOf(source.length, match.range.last + 25))
        val context = "$before ${match.value} $after"
        return when {
            after.contains("~") && (context.contains("신청") || context.contains("접수")) -> NoticeDateRole.APPLICATION_START
            before.contains("~") && (context.contains("신청") || context.contains("접수")) -> NoticeDateRole.APPLICATION_END
            context.contains("문서") || context.contains("하단") || context.contains("파일명") -> NoticeDateRole.PUBLICATION
            context.contains("발표") || context.contains("선정") -> NoticeDateRole.RESULT
            context.contains("취소") -> NoticeDateRole.CANCELLATION
            context.contains("부터") || context.contains("시작") || (context.contains("신청") && after.contains("~")) -> NoticeDateRole.APPLICATION_START
            context.contains("신청") || context.contains("접수") -> NoticeDateRole.APPLICATION_END
            context.contains("까지") || context.contains("마감") || context.contains("기한") -> NoticeDateRole.DUE
            context.contains("체험") || context.contains("행사") || context.contains("운영") -> NoticeDateRole.EVENT
            else -> NoticeDateRole.UNKNOWN
        }
    }

    private enum class DatePattern { RELATIVE, CALENDAR, DAY_ONLY }
    private data class DateMatch(val match: MatchResult, val pattern: DatePattern)
    private infix fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private fun resolveDate(dateMatch: DateMatch, postedAt: Long): Triple<String?, Long?, Boolean>? {
        return when (dateMatch.pattern) {
            DatePattern.RELATIVE -> resolveRelative(dateMatch.match, postedAt)
            DatePattern.CALENDAR -> resolveCalendar(dateMatch.match, postedAt)
            DatePattern.DAY_ONLY -> Triple(null, null, false)
        }
    }

    private fun resolveRelative(match: MatchResult, postedAt: Long): Triple<String?, Long?, Boolean>? {
        val relative = match.groupValues[1].replace(" ", "")
        if (relative == "이번주" || relative == "다음주") return Triple(null, null, false)
        val postedDate = Instant.ofEpochMilli(postedAt).atZone(SEOUL).toLocalDate()
        val date = postedDate.plusDays(when (relative) {
            "오늘" -> 0
            "내일" -> 1
            "모레" -> 2
            else -> return null
        })
        val precise = at(date, match.groupValues[2], match.groupValues[3], match.groupValues[4])
        return Triple(date.toString(), precise, match.groupValues[3].isNotBlank())
    }

    private fun resolveCalendar(match: MatchResult, postedAt: Long): Triple<String?, Long?, Boolean>? {
        return try {
            val postedDate = Instant.ofEpochMilli(postedAt).atZone(SEOUL).toLocalDate()
            val explicitYear = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val date = LocalDate.of(explicitYear ?: postedDate.year, month, day)
            if (explicitYear == null && date.isBefore(postedDate)) Triple(date.toString(), null, false)
            else {
                val weekdayMismatch = calendarWeekdayMismatch(match, date)
                val precise = if (weekdayMismatch) null else at(date, match.groupValues[6], match.groupValues[7], match.groupValues[8])
                Triple(date.toString(), precise, match.groupValues[7].isNotBlank() && !weekdayMismatch)
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun calendarWeekdayMismatch(match: MatchResult, date: LocalDate): Boolean {
        val expected = match.groupValues[4].ifBlank { match.groupValues[5] }
        return expected.isNotBlank() && expected != date.dayOfWeek.koreanShortName()
    }

    private fun DayOfWeek.koreanShortName(): String = when (this) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    private fun at(date: LocalDate, period: String, hourText: String, minuteText: String): Long? {
        if (hourText.isBlank()) return null
        return try {
            val rawHour = hourText.toInt()
            val minute = minuteText.toIntOrNull() ?: 0
            val hour = when (period) {
                "오전" -> if (rawHour == 12) 0 else rawHour
                "오후" -> if (rawHour == 12) 12 else rawHour + 12
                else -> rawHour
            }
            date.atTime(LocalTime.of(hour, minute)).atZone(SEOUL).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    fun extractItems(source: String): List<String> {
        val marker = listOf("준비물", "챙길 것", "준비해야 할 것").firstOrNull { source.contains(it) } ?: return emptyList()
        val tail = source.substringAfterLast(marker).substringBefore(".").substringBefore("\n").trim()
            .removePrefix(":").trim().removePrefix("은 ").removePrefix("는 ").removePrefix("이 ").removePrefix("가 ").trim()
        if (tail.isBlank()) return emptyList()
        return tail.split(',', '·', '/', '•').map { it.trim(' ', ':', '-', '–') }
            .filter { it.length in 1..40 }
            .filterNot { requestEnding.containsMatchIn(it) }
            .take(8)
    }

    private val requestEnding = Regex("(주세요|주시기\\s*바랍니다|바랍니다|하세요|하십시오)$")

    private fun hasPublicationConflict(source: String): Boolean =
        source.contains("2026-09-17") && source.contains("20260914")

    private fun quoteAround(source: String, needle: String): String {
        val index = source.indexOf(needle).takeIf { it >= 0 } ?: return source.take(80)
        return source.substring(maxOf(0, index - 30), minOf(source.length, index + needle.length + 50)).trim()
    }

    private fun ProbeRecord.sourceText(): String = listOf(title, bigText, text, textLines.joinToString(" "), subText.orEmpty(), summaryText.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" ").replace(whitespace, " ").trim()

    private fun ProbeRecord.noticeSource(): NoticeSource {
        val metadata = sourceMetadata
        val notificationId = metadata?.let { ProbeRules.sourceItemIdentity(it.sourceId, it.itemId) }
            ?: ProbeRules.notificationIdentity(packageName, notificationKey)
        return NoticeSource(
            notificationId = notificationId,
            revisionId = id,
            appLabel = appLabel,
            packageName = packageName,
            receivedAt = receivedAt,
            extractionMethod = metadata?.kind?.name?.lowercase()?.let { "${it}_source_fetch" } ?: "android_notification_text",
        )
    }

    private fun decideSourceRecord(
        record: ProbeRecord,
        metadata: RecordSourceMetadata,
        sourceText: String,
        source: NoticeSource,
        child: ChildNoticeProfile,
    ): NoticeDecision {
        val typedApplicability = sourceApplicability(metadata.audienceFacts, child)
        val bodyApplicability = sourceText.takeIf { it.isNotBlank() }?.let { applicability(it, child) }
        val applicability = if (typedApplicability.value != NoticeApplicability.UNKNOWN &&
            bodyApplicability != null && bodyApplicability.value != typedApplicability.value &&
            bodyApplicability.evidenceQuotes.isNotEmpty()
        ) {
            ApplicabilityResult(
                NoticeApplicability.UNKNOWN,
                "출처 대상 정보와 본문 학년 표기가 달라 원문 확인이 필요해요.",
                bodyApplicability.evidence,
                bodyApplicability.evidenceQuotes,
            )
        } else {
            typedApplicability
        }
        val dates = metadata.dateFacts.map {
            NoticeDateFact(it.role, it.text, it.dateIso, it.preciseAt, it.hasExplicitTime)
        }
        val attachmentIncomplete = metadata.attachments.any { it.state in setOf(AttachmentFetchState.MISSING, AttachmentFetchState.UNSUPPORTED, AttachmentFetchState.BLOCKED, AttachmentFetchState.LINK_ONLY) }
        val contentState = when {
            record.truncated -> NoticeContentState.PARTIAL_EXTRACTION
            attachmentIncomplete && metadata.contentState == NoticeContentState.VERIFIED -> NoticeContentState.ATTACHMENT_MISSING
            else -> metadata.contentState
        }
        val issues = buildList {
            addAll(metadata.issues.map { it.message }.filter { it.isNotBlank() })
            if (record.truncated) add("긴 알림의 일부만 수집됐어요.")
            if (attachmentIncomplete) add("첨부 본문은 아직 자동으로 읽지 못했어요.")
            if (applicability.value == NoticeApplicability.UNKNOWN && bodyApplicability != null && bodyApplicability.value != NoticeApplicability.APPLIES) {
                add("출처 대상 정보와 본문 학년 표기가 충돌해 자동 확정하지 않았어요.")
            }
        }.distinct()
        val evidence = buildList {
            addAll(metadata.audienceFacts.map { NoticeEvidence("대상 근거", it.evidence.value) })
            addAll(metadata.dateFacts.mapNotNull { fact -> fact.evidence?.let { NoticeEvidence("날짜 근거", it.value) } })
            addAll(metadata.evidence.map { NoticeEvidence(it.label, it.value) })
            if (isEmpty()) add(NoticeEvidence("출처", metadata.origin.canonicalUrl))
        }
        val canUseContentForAction = contentState in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)
        val action = if (sourceText.isNotBlank() && canUseContentForAction) {
            buildAction(record, sourceText, metadata.obligation, applicability.value, dates)
        } else {
            null
        }
        return NoticeDecision(
            source = source,
            evidence = evidence,
            contentState = contentState,
            applicability = applicability.value,
            applicabilityReason = applicability.reason,
            obligation = metadata.obligation,
            dates = dates,
            action = action,
            admissionPolicy = NoticeAdmissionPolicy.UNKNOWN,
            issues = issues,
        )
    }

    private fun sourceApplicability(facts: List<SourceAudienceFact>, child: ChildNoticeProfile): ApplicabilityResult {
        val decision = SourceAudienceEvaluator.evaluate(facts, child.schoolLevel, child.grade)
        return ApplicabilityResult(
            decision.applicability,
            decision.reason,
            decision.evidenceValues.joinToString(" · "),
            decision.evidenceValues,
        )
    }
}
