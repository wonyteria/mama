package kr.mom.probe.reminder

import java.time.LocalDateTime
import java.time.ZoneId
import kr.mom.probe.agent.SchedulePayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAlarmGatewayTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = millis(2026, 9, 15, 18, 0)

    @Test
    fun acceptsOnlyTheNextMatchingWallClockOccurrence() {
        val tomorrowSeven = payload(millis(2026, 9, 16, 7, 0))
        val nextMonthSeven = payload(millis(2026, 10, 17, 7, 0))

        assertTrue(ExternalAlarmGateway.isCompatibleWithStandardSetAlarm(tomorrowSeven, now, zone))
        assertFalse(ExternalAlarmGateway.isCompatibleWithStandardSetAlarm(nextMonthSeven, now, zone))
    }

    private fun payload(start: Long) = SchedulePayload("물티슈", start, start + 60_000L, zone.id, false, 60)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
