package kr.mom.probe.reminder

import java.time.Instant
import java.time.ZoneId

object BriefingSchedule {
    fun nextFire(now: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault(), weekdaysOnly: Boolean = false): Long {
        require(hour in 0..23 && minute in 0..59)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var next = current.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!next.isAfter(current)) next = current.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        while (weekdaysOnly && next.dayOfWeek.value > 5) next = next.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
