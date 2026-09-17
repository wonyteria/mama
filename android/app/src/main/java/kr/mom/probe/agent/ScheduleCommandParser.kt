package kr.mom.probe.agent

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleCommandParser(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
    private val defaultDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
) {
    fun parse(input: String): ScheduleCommand {
        val text = input.trim().replace(whitespace, " ")
        if (text.isBlank()) return ScheduleNoMutation(input, "empty")
        if (isNegative(text) || isQuotedMeta(text) || isLookup(text)) return ScheduleNoMutation(text, "not a create command")

        val target = detectTarget(text) ?: return ScheduleNoMutation(text, "unsupported target")
        val date = extractDate(text) ?: return ScheduleClarification(text, ScheduleClarificationKind.DATE, "날짜를 연도/월일, 월일, 오늘/내일/모레 중 하나로 알려주세요.")
        if (date.count > 1) return ScheduleClarification(text, ScheduleClarificationKind.DATE, "날짜가 여러 개라 한 번에 저장하지 않았어요. 하나의 날짜만 다시 알려주세요.")
        if (date.date.isBefore(today())) return ScheduleClarification(text, ScheduleClarificationKind.DATE, "이미 지난 날짜예요. 다음 해로 넘기지 않고 정확한 날짜를 다시 확인할게요.")

        val time = extractTime(text)
        if (time == null) return ScheduleClarification(text, ScheduleClarificationKind.TIME, "시작 시간을 함께 알려주세요.")
        if (time.invalid) return ScheduleClarification(text, ScheduleClarificationKind.TIME, "시간을 다시 확인해주세요. 오전/오후 시간은 1~12시로 적어주세요.")
        if (time.ambiguous) {
            return ScheduleClarification(
                rawText = text,
                kind = ScheduleClarificationKind.AM_PM,
                message = "오전인지 오후인지 알려주세요.",
                amText = replaceAmbiguousDateTime(text, date, time, "오전 ${time.displayHour}시${time.minuteText()}"),
                pmText = replaceAmbiguousDateTime(text, date, time, "오후 ${time.displayHour}시${time.minuteText()}"),
            )
        }
        val start = LocalDateTime.of(date.date, time.value).atZone(zoneId).toInstant().toEpochMilli()
        if (start <= nowMillis()) return ScheduleClarification(text, ScheduleClarificationKind.DATE, "이미 지난 시각이에요. 새 날짜와 시간을 다시 알려주세요.")

        if (target == ScheduleTarget.EXTERNAL_ALARM) {
            if (hasUnsupportedExtraTimes(text, listOf(time.range)) || hasDurationOutsideConsumedRanges(text, date.ranges + listOf(time.range))) {
                return ScheduleClarification(text, ScheduleClarificationKind.UNSUPPORTED, "알람은 0.8에서 하나의 날짜와 시작 시각만 받을 수 있어요. 한 시각만 다시 알려주세요.")
            }
            val title = extractTitle(text, target, date.ranges, time.range, emptyList()).ifBlank { "알람" }
            return AlarmRequestCommand(
                text,
                SchedulePayload(
                    title = title.take(MAX_TITLE_LENGTH),
                    startMillis = start,
                    endMillis = start + ALARM_PLACEHOLDER_DURATION_MILLIS,
                    zoneId = zoneId.id,
                    explicitEnd = true,
                    defaultDurationMinutes = 0,
                ),
            )
        }

        val explicitEnd = extractEnd(text, time, date.ranges)
        if (hasUnsupportedExtraTimes(text, listOf(time.range) + explicitEnd?.ranges.orEmpty())) {
            return ScheduleClarification(text, ScheduleClarificationKind.TIME, "시간이 여러 개라 한 번에 저장하지 않았어요. 하나의 시작 시간과 종료 시간만 다시 알려주세요.")
        }
        if (explicitEnd?.invalid == true) return ScheduleClarification(text, ScheduleClarificationKind.END_TIME, explicitEnd.message)
        val endMillis = explicitEnd?.endMillis(date.date, start) ?: start + Duration.ofMinutes(defaultDurationMinutes.toLong()).toMillis()
        if (explicitEnd == null && !LocalDateTime.ofInstant(Instant.ofEpochMilli(endMillis), zoneId).toLocalDate().isEqual(date.date)) {
            return ScheduleClarification(text, ScheduleClarificationKind.END_TIME, "기본 1시간으로 잡으면 다음 날에 끝나요. 끝나는 날짜와 시간을 확인해주세요.")
        }
        if (endMillis <= start) return ScheduleClarification(text, ScheduleClarificationKind.END_TIME, "끝나는 시간이 시작보다 빠르거나 같아요. 시작~종료를 다시 알려주세요.")
        if (endMillis - start > MAX_DURATION_MILLIS) return ScheduleClarification(text, ScheduleClarificationKind.END_TIME, "0.8에서는 24시간 이내의 단일 일정만 저장할 수 있어요.")

        val title = extractTitle(text, target, date.ranges, time.range, explicitEnd?.ranges.orEmpty())
        if (title.isBlank()) return ScheduleClarification(text, ScheduleClarificationKind.TITLE, "일정 제목이나 알람 이름을 함께 알려주세요.")

        val payload = SchedulePayload(
            title = title.take(MAX_TITLE_LENGTH),
            startMillis = start,
            endMillis = endMillis,
            zoneId = zoneId.id,
            explicitEnd = explicitEnd != null,
            defaultDurationMinutes = defaultDurationMinutes,
        )
        return CalendarCreateCommand(text, payload)
    }

    private fun detectTarget(text: String): ScheduleTarget? {
        return when {
            alarmImperative.containsMatchIn(text) -> ScheduleTarget.EXTERNAL_ALARM
            calendarImperative.containsMatchIn(text) -> ScheduleTarget.CALENDAR
            else -> null
        }
    }

    private fun extractDate(text: String): DateMatch? {
        val ranges = mutableListOf<IntRange>()
        val matches = mutableListOf<LocalDate>()
        yearMonthDay.findAll(text).forEach { match ->
            parseDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())?.let {
                matches += it
                ranges += match.range
            } ?: return DateMatch(LocalDate.MIN, 1, listOf(match.range))
        }
        val covered = ranges.toList()
        monthDay.findAll(text).forEach { match ->
            if (covered.any { match.range.first >= it.first && match.range.last <= it.last }) return@forEach
            val year = today().year
            parseDate(year, match.groupValues[1].toInt(), match.groupValues[2].toInt())?.let {
                matches += it
                ranges += match.range
            } ?: return DateMatch(LocalDate.MIN, 1, listOf(match.range))
        }
        relativeDate.findAll(text).forEach { match ->
            val offset = when (match.value) {
                "오늘" -> 0L
                "내일" -> 1L
                "모레" -> 2L
                else -> return@forEach
            }
            matches += today().plusDays(offset)
            ranges += match.range
        }
        if (matches.isEmpty()) return null
        val distinct = matches.distinct()
        if (distinct.any { it == LocalDate.MIN }) return DateMatch(LocalDate.MIN, 1, ranges)
        return DateMatch(distinct.first(), distinct.size, ranges)
    }

    private fun parseDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun extractTime(text: String): TimeMatch? {
        koreanTime.findAll(text).forEach { match ->
            val marker = match.groupValues[1].ifBlank { null }
            val hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].ifBlank { "0" }.toInt()
            if (minute !in 0..59) return TimeMatch(LocalTime.MIDNIGHT, match.range, ambiguous = false, displayHour = hour, minute = minute, invalid = true)
            if (marker != null && hour !in 1..12) return TimeMatch(LocalTime.MIDNIGHT, match.range, ambiguous = false, displayHour = hour, minute = minute, invalid = true)
            if (marker == null && hour in 1..12) return TimeMatch(LocalTime.MIDNIGHT, match.range, true, hour, minute)
            val resolvedHour = when (marker) {
                "오전" -> if (hour == 12) 0 else hour
                "오후" -> if (hour == 12) 12 else hour + 12
                else -> hour
            }
            if (resolvedHour !in 0..23) return null
            return TimeMatch(LocalTime.of(resolvedHour, minute), match.range, false, hour, minute)
        }
        clockTime.find(text)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) return TimeMatch(LocalTime.MIDNIGHT, match.range, ambiguous = false, displayHour = hour, minute = minute, invalid = true)
            return TimeMatch(LocalTime.of(hour, minute), match.range, ambiguous = false, displayHour = hour, minute, invalid = false)
        }
        return null
    }

    private fun extractEnd(text: String, startTime: TimeMatch, dateRanges: List<IntRange>): EndMatch? {
        endTime.find(text)?.let { match ->
            val parsed = parseEndClock(match) ?: return EndMatch(null, listOf(match.range), true, "끝나는 시간을 다시 확인해주세요.")
            return EndMatch({ start ->
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zoneId).toLocalDate()
                LocalDateTime.of(date, parsed).atZone(zoneId).toInstant().toEpochMilli()
            }, listOf(match.range), invalid = false)
        }
        rangeClock.find(text)?.let { match ->
            if (startTime.range.first !in match.range) return null
            val parsed = parseRangeEnd(match) ?: return EndMatch(null, listOf(match.range), true, "끝나는 시간을 다시 확인해주세요.")
            return EndMatch({ start ->
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zoneId).toLocalDate()
                LocalDateTime.of(date, parsed).atZone(zoneId).toInstant().toEpochMilli()
            }, listOf(match.range), invalid = false)
        }
        val clockRanges = buildList {
            addAll(dateRanges)
            add(startTime.range)
            koreanTime.findAll(text).forEach { match -> if (match.range != startTime.range) add(match.range) }
            clockTime.findAll(text).forEach { match -> if (none { overlap(it, match.range) }) add(match.range) }
        }
        val durationSource = maskRanges(text, clockRanges)
        duration.find(durationSource)?.let { match ->
            val hours = match.groupValues[1].ifBlank { match.groupValues[3].ifBlank { "0" } }.toLongOrNull()
                ?: return EndMatch(null, listOf(match.range), true, "기간을 다시 확인해주세요.")
            val minutes = match.groupValues[2].ifBlank { "0" }.toLongOrNull()
                ?: return EndMatch(null, listOf(match.range), true, "기간을 다시 확인해주세요.")
            if (hours == 0L && minutes == 0L) return EndMatch(null, listOf(match.range), true, "기간을 다시 확인해주세요.")
            if (hours > 24L || minutes > 24L * 60L || hours * 60L + minutes > 24L * 60L) {
                return EndMatch(null, listOf(match.range), true, "0.8에서는 24시간 이내의 단일 일정만 저장할 수 있어요.")
            }
            val millis = Duration.ofHours(hours).plusMinutes(minutes).toMillis()
            return EndMatch({ start -> start + millis }, listOf(match.range), invalid = false)
        }
        return null
    }

    private fun hasUnsupportedExtraTimes(text: String, consumedTimeRanges: List<IntRange>): Boolean {
        val ranges = mutableListOf<IntRange>()
        koreanTime.findAll(text).forEach { match -> ranges += match.range }
        clockTime.findAll(text).forEach { match ->
            if (ranges.none { overlap(it, match.range) }) ranges += match.range
        }
        return ranges.any { timeRange -> consumedTimeRanges.none { consumed -> overlap(consumed, timeRange) } }
    }

    private fun hasDurationOutsideConsumedRanges(text: String, ranges: List<IntRange>): Boolean =
        duration.containsMatchIn(maskRanges(text, ranges))

    private fun maskRanges(text: String, ranges: List<IntRange>): String {
        val chars = text.toCharArray()
        ranges.forEach { range ->
            val start = range.first.coerceAtLeast(0)
            val end = range.last.coerceAtMost(chars.lastIndex)
            for (index in start..end) chars[index] = ' '
        }
        return String(chars)
    }

    private fun overlap(left: IntRange, right: IntRange): Boolean =
        left.first <= right.last && right.first <= left.last

    private fun parseEndClock(match: MatchResult): LocalTime? {
        val marker = match.groupValues[1].ifBlank { null }
        val hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].ifBlank { match.groupValues[4].ifBlank { "0" } }.toInt()
        if (minute !in 0..59) return null
        if (marker != null && hour !in 1..12) return null
        if (marker == null && hour in 1..12) return null
        val resolved = when (marker) {
            "오전" -> if (hour == 12) 0 else hour
            "오후" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        return if (resolved in 0..23) LocalTime.of(resolved, minute) else null
    }

    private fun parseRangeEnd(match: MatchResult): LocalTime? {
        val marker = match.groupValues[5].ifBlank { null }
        val hour = match.groupValues[6].toInt()
        val minute = match.groupValues[7].ifBlank { match.groupValues[8].ifBlank { "0" } }.toInt()
        if (minute !in 0..59) return null
        if (marker != null && hour !in 1..12) return null
        if (marker == null && hour in 1..12) return null
        val resolved = when (marker) {
            "오전" -> if (hour == 12) 0 else hour
            "오후" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        return if (resolved in 0..23) LocalTime.of(resolved, minute) else null
    }

    private fun extractTitle(
        original: String,
        target: ScheduleTarget,
        dateRanges: List<IntRange>,
        timeRange: IntRange,
        endRanges: List<IntRange>,
    ): String {
        var text = maskRanges(original, unionRanges(dateRanges + listOf(timeRange) + endRanges))
        val replacements = when (target) {
            ScheduleTarget.CALENDAR -> calendarRemovals
            ScheduleTarget.EXTERNAL_ALARM -> alarmRemovals
        }
        replacements.forEach { text = text.replace(it, " ") }
        return text.replace(punctuation, " ").replace(whitespace, " ").trim()
    }

    private fun unionRanges(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.filter { it.first <= it.last }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var current = sorted.first()
        sorted.drop(1).forEach { range ->
            if (range.first <= current.last + 1) {
                current = current.first..maxOf(current.last, range.last)
            } else {
                result += current
                current = range
            }
        }
        result += current
        return result
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()
    private fun replaceAmbiguousDateTime(text: String, date: DateMatch, time: TimeMatch, replacementTime: String): String {
        val dateRange = date.ranges.firstOrNull() ?: return text.replaceRange(time.range, replacementTime)
        val explicit = "${date.date.year}년 ${date.date.monthValue}월 ${date.date.dayOfMonth}일"
        return listOf(dateRange to explicit, time.range to replacementTime)
            .sortedByDescending { it.first.first }
            .fold(text) { current, (range, replacement) -> current.replaceRange(range, replacement) }
    }
    private fun isNegative(text: String) = negative.containsMatchIn(text)
    private fun isQuotedMeta(text: String) = quotedMeta.containsMatchIn(text)
    private fun isLookup(text: String): Boolean {
        if (text.contains("?")) return true
        if (explanation.containsMatchIn(text)) return true
        if (lookupEnd.containsMatchIn(text)) return true
        if (queryWords.any { text.contains(it) } && !createVerbs.any { text.contains(it) }) return true
        return false
    }

    private data class DateMatch(val date: LocalDate, val count: Int, val ranges: List<IntRange>)
    private data class TimeMatch(
        val value: LocalTime,
        val range: IntRange,
        val ambiguous: Boolean,
        val displayHour: Int,
        val minute: Int,
        val invalid: Boolean = false,
    ) {
        fun minuteText(): String = if (minute == 0) "" else " ${minute}분"
    }

    private data class EndMatch(
        val resolver: ((Long) -> Long)?,
        val ranges: List<IntRange>,
        val invalid: Boolean,
        val message: String = "",
    ) {
        fun endMillis(date: LocalDate, start: Long): Long = resolver?.invoke(start)
            ?: date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 60
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_DURATION_MILLIS = 24L * 60 * 60 * 1000
        private const val ALARM_PLACEHOLDER_DURATION_MILLIS = 60_000L
        private val whitespace = Regex("\\s+")
        private val punctuation = Regex("[,，.!?~。]+")
        private val yearMonthDay = Regex("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
        private val monthDay = Regex("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
        private val relativeDate = Regex("오늘|내일|모레")
        private val koreanTime = Regex("(오전|오후)?\\s*(\\d{1,2})\\s*시(?!간)(?:\\s*(\\d{1,2})\\s*분)?")
        private val clockTime = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
        private val duration = Regex("(?:([1-9]\\d*)\\s*시간)?\\s*(?:([1-9]\\d*)\\s*분)\\s*(?:동안|짜리)?|([1-9]\\d*)\\s*시간\\s*(?:동안|짜리)?")
        private val endTime = Regex("(?:부터|시작해서).{0,12}?(오전|오후)?\\s*(\\d{1,2})(?::(\\d{2})|\\s*시(?:\\s*(\\d{1,2})\\s*분)?)\\s*(?:까지|종료|끝)")
        private val rangeClock = Regex("(오전|오후)?\\s*(\\d{1,2})(?::(\\d{2})|\\s*시(?:\\s*(\\d{1,2})\\s*분)?)\\s*(?:~|-|부터)\\s*(오전|오후)?\\s*(\\d{1,2})(?::(\\d{2})|\\s*시(?:\\s*(\\d{1,2})\\s*분)?)")
        private val createVerbs = listOf("저장", "추가", "등록", "넣어", "넣기")
        private val imperativeSuffix = "\\s*(?:해\\s*줘|해줘|해\\s*주세요|해주세요|해|줘|주세요)?[.!~\\s]*$"
        private val calendarImperative = Regex("캘린더(?:에)?\\s*(?:저장|추가|등록|넣어|넣기)$imperativeSuffix|(?:일정|약속)(?:으로|에)?\\s*(?:저장|추가|등록|넣어|넣기)$imperativeSuffix")
        private val alarmImperative = Regex("알람\\s*(?:맞춰|맞추어|맞추|설정|켜|등록|추가)$imperativeSuffix")
        private val negative = Regex("(?:저장|등록|추가|맞추|맞춰|설정|켜|넣)\\s*(?:하)?\\s*지\\s*마|취소|삭제|지우")
        private val quotedMeta = Regex("[‘'\"“][^’'\"”]*(일정|캘린더|알람)[^’'\"”]*(저장|추가|등록|맞춰)[^’'\"”]*[’'\"”].*(라는|문장|설명|뜻)")
        private val explanation = Regex("방법|설명|뜻|어떻게")
        private val lookupEnd = Regex("(있어|있나요|맞췄어|저장됐|알려줘|보여줘|뭐야|언제야)[요?!.\\s]*$")
        private val queryWords = listOf("뭐", "무엇", "언제", "어디", "확인", "조회", "보여")
        private val calendarRemovals = listOf("캘린더에", "캘린더", "일정으로", "일정에", "일정", "약속으로", "약속", "저장해줘", "저장해 줘", "저장", "추가해줘", "추가해 줘", "추가", "등록해줘", "등록해 줘", "등록", "넣어줘", "넣어 줘", "넣어")
        private val alarmRemovals = listOf("알람", "맞춰줘", "맞춰 줘", "맞춰", "맞추", "설정해줘", "설정해 줘", "설정", "켜줘", "켜 줘", "켜", "등록해줘", "등록", "추가해줘", "추가")
    }
}

