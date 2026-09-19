package kr.mom.probe.task

import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateActionPlannerTest {
    private val now = 1_789_300_000_000L

    @Test fun createsAutomaticPlanOnlyForExplicitDatedPreparation() {
        val plans = CandidateActionPlanner.plans(record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지"), now)

        assertTrue(plans.isNotEmpty())
        val plan = plans.single { it.actionKind == "prepare" }
        assertTrue(plan.checklist.contains("도시락"))
        assertTrue(plan.checklist.contains("물통"))
        assertTrue(plan.dueAt != null && plan.dueAt > now)
        if (plan.remindAt != null) assertTrue(plan.remindAt > now)
    }

    @Test fun splitsSubmissionAndPreparationIntoSeparatePlans() {
        val plans = CandidateActionPlanner.plans(
            record("체험학습 안내", "준비물: 도시락, 물통. 참가 신청서는 내일 오전 9시까지 제출"),
            now,
        )

        assertEquals(2, plans.size)
        assertEquals(setOf("submit", "prepare"), plans.map { it.actionKind }.toSet())
        val prepare = plans.single { it.actionKind == "prepare" }
        assertTrue(prepare.checklist.contains("도시락"))
    }

    @Test fun leavesAmbiguousUndatedNoticeForReview() {
        assertTrue(CandidateActionPlanner.plans(record("체험학습 안내", "준비물을 챙겨 주세요."), now).isEmpty())
    }

    @Test fun doesNotCreatePlanForOptionalSchoolProgramEvenWhenGradeMatches() {
        val notice = record("체험 프로그램 신청 안내", "대상 초등 5~6학년. 신청 2026-09-28 월 17:00 ~ 2026-10-02 금 09:00. 선착순 마감.")

        assertNull(CandidateActionPlanner.plan(notice, now, ChildNoticeProfile(5, SchoolLevel.ELEMENTARY)))
    }

    @Test fun createsPlanWhenCollapsedAndExpandedTextAreIdentical() {
        val body = "준비물: 물통. 내일 오전 9시까지"
        val plan = CandidateActionPlanner.plan(record("준비물 안내", body).copy(bigText = body), now)

        assertNotNull(plan)
    }

    @Test fun doesNotCreatePlanFromPartialOrAttachmentMissingContent() {
        assertNull(CandidateActionPlanner.plan(record("준비물 안내", "준비물: 물통. 내일 오전 9시까지").copy(truncated = true), now))
        assertNull(CandidateActionPlanner.plan(record("첨부 대상 확인", "첨부 대상 확인. 준비물: 물통. 내일 오전 9시까지"), now))
    }

    private fun record(title: String, text: String) = ProbeRecord(
        id = "id", packageName = "school.app", appLabel = "학교", postedAt = now, receivedAt = now,
        title = title, text = text, bigText = "", textLines = emptyList(), subText = null,
        summaryText = null, category = null, channelId = null, notificationId = 1,
        notificationKey = "key", isOngoing = false, isGroupSummary = false, rawHash = "hash",
    )
}
