package kr.mom.probe.reminder

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingScheduleTest {
    @Test fun schedulesLaterTodayInLocalZone() {
        val now = Instant.parse("2026-09-13T00:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-09-13T11:30:00Z").toEpochMilli(), BriefingSchedule.nextFire(now, 20, 30, ZoneId.of("Asia/Seoul")))
    }
    @Test fun exactCurrentMinuteMovesToTomorrow() {
        val now = Instant.parse("2026-09-13T11:30:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-09-14T11:30:00Z").toEpochMilli(), BriefingSchedule.nextFire(now, 20, 30, ZoneId.of("Asia/Seoul")))
    }
    @Test fun springForwardUsesNextValidLocalTime() {
        val now = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), BriefingSchedule.nextFire(now, 2, 30, ZoneId.of("America/New_York")))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidTime() {
        BriefingSchedule.nextFire(0, 24, 0)
    }
    @Test fun weekdayScheduleSkipsWeekend() {
        val now = Instant.parse("2026-09-11T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-09-13T22:00:00Z").toEpochMilli(), BriefingSchedule.nextFire(now, 7, 0, ZoneId.of("Asia/Seoul"), weekdaysOnly = true))
    }
}
