package kr.mom.probe.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingPresentationTest {
    @Test fun agendaHeroDoesNotMarkHiddenNoticeRowsSeen() {
        assertEquals(0, initiallyVisibleBriefingNoticeCount(agendaCount = 1, noticeCount = 2))
    }

    @Test fun noticeHeroMarksOnlyTheSingleVisibleNoticeSeen() {
        assertEquals(1, initiallyVisibleBriefingNoticeCount(agendaCount = 0, noticeCount = 3))
        assertEquals(0, initiallyVisibleBriefingNoticeCount(agendaCount = 0, noticeCount = 0))
    }
}
