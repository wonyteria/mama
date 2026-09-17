package kr.mom.probe.agent

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCommandParserTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDateTime.of(2026, 9, 15, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val parser = ScheduleCommandParser({ now }, zone, defaultDurationMinutes = 60)

    @Test
    fun parsesExplicitCalendarCreate() {
        val result = parser.parse("2026년 10월 17일 오후 2시 치과 약속 캘린더에 저장해줘")

        assertTrue(result is CalendarCreateCommand)
        val payload = (result as CalendarCreateCommand).payload
        assertEquals("치과", payload.title)
        assertEquals(millis(2026, 10, 17, 14, 0), payload.startMillis)
        assertEquals(millis(2026, 10, 17, 15, 0), payload.endMillis)
        assertEquals(false, payload.explicitEnd)
    }

    @Test
    fun supportsRelativeAlarmRequest() {
        val result = parser.parse("내일 오전 7시 물티슈 알람 맞춰줘")

        assertTrue(result is AlarmRequestCommand)
        val payload = (result as AlarmRequestCommand).payload
        assertEquals("물티슈", payload.title)
        assertEquals(millis(2026, 9, 16, 7, 0), payload.startMillis)
    }

    @Test
    fun alarmIsPointInTimeAndAllowsDefaultLabel() {
        val late = parser.parse("오늘 23:30 알람 맞춰줘")
        assertTrue(late is AlarmRequestCommand)
        assertEquals(millis(2026, 9, 15, 23, 30), (late as AlarmRequestCommand).payload.startMillis)
        assertEquals("알람", late.payload.title)

        val generic = parser.parse("내일 오전 7시 알람 맞춰줘")
        assertTrue(generic is AlarmRequestCommand)
        assertEquals("알람", (generic as AlarmRequestCommand).payload.title)
    }

    @Test
    fun refusesBareOneToTwelveHour() {
        val result = parser.parse("10월 17일 3시 상담 일정 추가")

        assertTrue(result is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.AM_PM, (result as ScheduleClarification).kind)
        assertTrue(result.amText!!.contains("오전 3시"))
        assertTrue(result.pmText!!.contains("오후 3시"))
    }

    @Test
    fun acceptsUnambiguousTwentyFourHour() {
        val result = parser.parse("10월 17일 14:30 상담 일정 추가")

        assertTrue(result is CalendarCreateCommand)
        val payload = (result as CalendarCreateCommand).payload
        assertEquals(millis(2026, 10, 17, 14, 30), payload.startMillis)
    }

    @Test
    fun startMinuteIsNotDuration() {
        val result = parser.parse("10월 17일 오후 2시 30분 상담 일정 추가")

        assertTrue(result is CalendarCreateCommand)
        val payload = (result as CalendarCreateCommand).payload
        assertEquals("상담", payload.title)
        assertEquals(millis(2026, 10, 17, 14, 30), payload.startMillis)
        assertEquals(millis(2026, 10, 17, 15, 30), payload.endMillis)
        assertEquals(false, payload.explicitEnd)
    }

    @Test
    fun refusesMultipleAlternativeTimesAndInvalidAmPmRawHour() {
        val multiple = parser.parse("10월 17일 14:00 또는 16:00 상담 일정 추가")
        assertTrue(multiple is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.TIME, (multiple as ScheduleClarification).kind)

        val rangePlusExtra = parser.parse("10월 17일 14:00~16:00 또는 17:00 상담 일정 추가")
        assertTrue(rangePlusExtra is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.TIME, (rangePlusExtra as ScheduleClarification).kind)

        val invalidMorning = parser.parse("10월 17일 오전 13시 상담 일정 추가")
        assertTrue(invalidMorning is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.TIME, (invalidMorning as ScheduleClarification).kind)

        val invalidAfternoon = parser.parse("10월 17일 오후 0시 상담 일정 추가")
        assertTrue(invalidAfternoon is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.TIME, (invalidAfternoon as ScheduleClarification).kind)

        val invalidEndMarker = parser.parse("10월 17일 오전 11시부터 오전 13시까지 상담 일정 추가")
        assertTrue(invalidEndMarker is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.END_TIME, (invalidEndMarker as ScheduleClarification).kind)

        val alarmWithDuration = parser.parse("내일 오전 7시 2시간 동안 알람 맞춰줘")
        assertTrue(alarmWithDuration is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.UNSUPPORTED, (alarmWithDuration as ScheduleClarification).kind)
    }

    @Test
    fun doesNotRollPastMonthDayToNextYear() {
        val result = parser.parse("9월 1일 14:30 상담 일정 추가")

        assertTrue(result is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.DATE, (result as ScheduleClarification).kind)
    }

    @Test
    fun refusesInvalidAndMultipleDates() {
        assertTrue(parser.parse("2월 30일 14:30 상담 일정 추가") is ScheduleClarification)
        val multiple = parser.parse("10월 17일 11월 1일 14:30 상담 일정 추가")
        assertTrue(multiple is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.DATE, (multiple as ScheduleClarification).kind)
    }

    @Test
    fun queryNegativeAndQuotedTextAreNotMutations() {
        assertTrue(parser.parse("10월 17일 14:30 약속 있어?") is ScheduleNoMutation)
        assertTrue(parser.parse("10월 17일 14:30 치과 일정 저장한 거 확인해줘") is ScheduleNoMutation)
        assertTrue(parser.parse("10월 17일 14:30 치과 일정 저장했어") is ScheduleNoMutation)
        assertTrue(parser.parse("10월 17일 14:30 상담 일정 저장하지 마") is ScheduleNoMutation)
        assertTrue(parser.parse("내일 오전 7시 기상 알람 맞추지 마") is ScheduleNoMutation)
        assertTrue(parser.parse("10월 17일 14:30 상담 일정 저장 방법 설명해줘") is ScheduleNoMutation)
        assertTrue(parser.parse("‘10월 17일 14:30 상담 일정 추가해줘’라는 문장 설명해줘") is ScheduleNoMutation)
    }

    @Test
    fun honorsExplicitDurationAndRefusesMidnightDefaultRollover() {
        val duration = parser.parse("10월 17일 오후 2시 상담 2시간 일정 추가")
        assertTrue(duration is CalendarCreateCommand)
        assertEquals(millis(2026, 10, 17, 16, 0), (duration as CalendarCreateCommand).payload.endMillis)

        val range = parser.parse("10월 17일 14:00~16:00 상담 일정 추가")
        assertTrue(range.toString(), range is CalendarCreateCommand)
        val rangePayload = (range as CalendarCreateCommand).payload
        assertEquals("상담", rangePayload.title)
        assertEquals(millis(2026, 10, 17, 16, 0), rangePayload.endMillis)

        val rollover = parser.parse("10월 17일 23:30 상담 일정 추가")
        assertTrue(rollover is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.END_TIME, (rollover as ScheduleClarification).kind)
    }

    @Test
    fun refusesOverflowingDuration() {
        val result = parser.parse("10월 17일 14:30 상담 999999999999999999999분 일정 추가")

        assertTrue(result is ScheduleClarification)
        assertEquals(ScheduleClarificationKind.END_TIME, (result as ScheduleClarification).kind)
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}



