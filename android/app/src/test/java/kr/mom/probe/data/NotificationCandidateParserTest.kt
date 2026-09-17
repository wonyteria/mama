package kr.mom.probe.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCandidateParserTest {
    private fun record(title: String, text: String, postedAt: Long = 1) = ProbeRecord(
        id = "id", packageName = "pkg", appLabel = "학교", postedAt = postedAt, receivedAt = postedAt,
        title = title, text = text, bigText = "", textLines = emptyList(), subText = null,
        summaryText = null, category = null, channelId = null, notificationId = 1,
        notificationKey = "key", isOngoing = false, isGroupSummary = false, rawHash = "hash",
    )

    @Test fun findsPrepareItemsAndDueHint() {
        val candidate = NotificationCandidateParser.parse(record("체험학습 준비물", "준비물: 도시락, 물통, 모자. 내일 오전 9시까지"))
        assertNotNull(candidate)
        assertEquals(listOf("도시락", "물통", "모자"), candidate!!.items)
        assertEquals("내일 오전 9시", candidate.dueText)
    }

    @Test fun doesNotInventCandidateForInformationalText() {
        assertNull(NotificationCandidateParser.parse(record("학교 안내", "다음 주 체험학습이 진행됩니다.")))
    }

    @Test fun findsCalendarDateAndReplyAction() {
        val candidate = NotificationCandidateParser.parse(record("가정통신문", "9월 16일(수)까지 회신해 주세요."))
        assertNotNull(candidate)
        assertEquals(NotificationCandidate.Kind.SUBMIT, candidate!!.kind)
        assertEquals("9월 16일(수)", candidate.dueText)
    }

    @Test fun weekdayMismatchKeepsSourceTextButDoesNotCreatePreciseDeadline() {
        val postedAt = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()
        val decision = NoticeDecisionEngine.decide(record("회신 안내", "2026.9.16.(화) 09:00까지 회신해 주세요.", postedAt))

        val due = decision.dates.single { it.role == NoticeDateRole.DUE }
        assertEquals("2026.9.16.(화) 09:00", due.text)
        assertEquals("2026-09-16", due.dateIso)
        assertNull(due.preciseAt)
        assertFalse(due.hasExplicitTime)
        assertTrue(decision.issues.any { it.contains("요일") })
    }

    @Test fun ignoresExplicitlyCompletedOrUnneededAction() {
        assertNull(NotificationCandidateParser.parse(record("제출 안내", "동의서는 제출하지 않아도 됩니다.")))
        assertNull(NotificationCandidateParser.parse(record("납부 안내", "참가비 납부 완료했습니다.")))
        assertNull(NotificationCandidateParser.parse(record("준비물 안내", "이번 활동은 준비물 없음입니다.")))
        assertNull(NotificationCandidateParser.parse(record("학습준비물", "학습준비물은 학교에서 지원합니다.")))
    }

    @Test fun stripsKoreanTopicParticleFromItems() {
        val candidate = NotificationCandidateParser.parse(record("체험 안내", "준비물은 도시락, 물통."))
        assertEquals(listOf("도시락", "물통"), candidate!!.items)
    }

    @Test fun resolvesRelativeDatesAndKoreanClockInSeoul() {
        val postedAt = Instant.parse("2026-09-13T11:00:00Z").toEpochMilli() // 20:00 KST
        val tomorrow = NotificationCandidateParser.parse(record("제출 안내", "내일 오전 9시까지 제출", postedAt))
        val dayAfter = NotificationCandidateParser.parse(record("준비 안내", "모레 오후 3:30시까지 준비해 주세요", postedAt))
        val todayWithoutTime = NotificationCandidateParser.parse(record("회신 안내", "오늘까지 회신", postedAt))

        assertEquals(Instant.parse("2026-09-14T00:00:00Z").toEpochMilli(), tomorrow!!.dueAt)
        assertEquals(Instant.parse("2026-09-15T06:30:00Z").toEpochMilli(), dayAfter!!.dueAt)
        assertNull(todayWithoutTime!!.dueAt)
    }

    @Test fun resolvesKoreanAndSlashCalendarDates() {
        val postedAt = Instant.parse("2026-09-13T11:00:00Z").toEpochMilli()
        val korean = NotificationCandidateParser.parse(record("회신 안내", "9월 16일까지 회신", postedAt))
        val slash = NotificationCandidateParser.parse(record("신청서 제출", "9/16 오후 1:05시까지 신청서를 제출해 주세요", postedAt))

        assertNull(korean!!.dueAt)
        assertEquals(Instant.parse("2026-09-16T04:05:00Z").toEpochMilli(), slash!!.dueAt)
    }

    @Test fun doesNotRollMonthAndDayIntoNextYearWhenAlreadyPast() {
        val postedAt = Instant.parse("2026-12-20T00:00:00Z").toEpochMilli()
        val candidate = NotificationCandidateParser.parse(record("제출 안내", "1월 5일까지 제출", postedAt))
        assertNull(candidate!!.dueAt)
        assertEquals("1월 5일", candidate.dueText)
    }

    @Test fun keepsAmbiguousWeekWithoutInventingAbsoluteDeadline() {
        val postedAt = Instant.parse("2026-09-13T11:00:00Z").toEpochMilli()
        assertNull(NotificationCandidateParser.parse(record("준비 안내", "이번 주까지 준비해 주세요", postedAt))!!.dueAt)
        assertNull(NotificationCandidateParser.parse(record("제출 안내", "다음주까지 제출해 주세요", postedAt))!!.dueAt)
    }

    @Test fun doesNotTreatOperatingHoursAsRequiredAction() {
        val candidate = NotificationCandidateParser.parse(record("도서관 안내", "도서관은 오늘 오후 5시까지 운영합니다.", postedAt = Instant.parse("2026-09-13T00:00:00Z").toEpochMilli()))

        assertNull(candidate)
    }

    @Test fun ambiguousBareGradeDoesNotMasqueradeAsApplicable() {
        val record = record("3학년 준비물", "3학년 준비물: 색종이. 내일 오전 9시까지")
        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile(grade = 3, schoolLevel = SchoolLevel.ELEMENTARY))

        assertEquals(NoticeApplicability.UNKNOWN, decision.applicability)
        assertFalse(decision.isRequiredForChild())
    }
}
