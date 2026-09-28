package kr.mom.probe.agent

import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.task.AssistantTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture classification contract: quiet buckets, honest provenance labels,
 * and nothing saveable without an explicit parent confirm. The engine is
 * the only classifier — these tests pin the six intent types against real
 * utterance shapes.
 */
class CaptureClassificationTest {

    private val fixedNow = 1_789_300_000_000L
    private val engine = LocalAgentEngine({ fixedNow })

    private fun context(
        childName: String = "아이",
        notifications: List<ProbeRecord> = emptyList(),
        tasks: List<AssistantTask> = emptyList(),
        childProfile: ChildNoticeProfile = ChildNoticeProfile(),
    ) = LocalAgentContext(childName, notifications, tasks, childProfile)

    private fun record(title: String, text: String, postedAt: Long = fixedNow) = ProbeRecord(
        id = "id-$postedAt-$title", packageName = "pkg", appLabel = "학교",
        postedAt = postedAt, receivedAt = postedAt, title = title, text = text,
        bigText = "", textLines = emptyList(), subText = null, summaryText = null,
        category = null, channelId = null, notificationId = postedAt.toInt(),
        notificationKey = "key-$postedAt", isOngoing = false, isGroupSummary = false,
        rawHash = "hash-$postedAt",
    )

    @Test fun `obligation statement without command verb becomes a reviewable task candidate`() {
        val plan = engine.capture("금요일까지 체육복 사야 돼", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.SHOPPING, plan.intent)
        assertEquals(CaptureDisposition.REVIEW_LATER, plan.disposition)
        // "금요일" is beyond the parser's relative dates — honestly undated.
        assertNull(plan.reply.proposedDueAt)
        assertTrue(plan.labels.contains(CaptureLabels.USER_SPOKE))
        assertTrue(plan.labels.contains(CaptureLabels.NEEDS_DATE))
        assertTrue(plan.labels.contains(CaptureLabels.RULE_ESTIMATE))
        assertTrue(plan.reply.proposedTask!!.contains("체육복"))
    }

    @Test fun `explicit task command with a date lands in today bucket`() {
        val plan = engine.capture("내일 오후 3시 물통 챙겨줘", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.TASK, plan.intent)
        assertEquals(CaptureDisposition.KEEP_TODAY, plan.disposition)
        assertNotNull(plan.reply.proposedDueAt)
        assertFalse(plan.labels.contains(CaptureLabels.NEEDS_DATE))
        assertFalse(plan.labels.contains(CaptureLabels.RULE_ESTIMATE))
    }

    @Test fun `reminder command maps to reminder intent`() {
        val plan = engine.capture("내일 오후 3시 물통 알려줘", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.REMINDER, plan.intent)
        assertEquals(plan.reply.proposedDueAt, plan.reply.proposedRemindAt)
    }

    @Test fun `lookup question answers inline and never saves`() {
        val plan = engine.capture("이번 주 준비물 뭐야?", context(
            notifications = listOf(record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지")),
        ))

        assertFalse(plan.saveable)
        assertEquals(AgentIntent.QUESTION, plan.intent)
        assertNull(plan.disposition)
        assertTrue(plan.labels.contains(CaptureLabels.NO_SAVE))
        assertTrue(plan.reply.message.isNotBlank())
    }

    @Test fun `memo marker keeps it memo-only`() {
        val plan = engine.capture("그냥 생각만 적어둬", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.MEMO, plan.intent)
        assertEquals(CaptureDisposition.MEMO_ONLY, plan.disposition)
        assertNull(plan.reply.proposedDueAt)
        assertNull(plan.reply.proposedRemindAt)
    }

    @Test fun `plain declarative utterance is a memo not a task`() {
        val plan = engine.capture("아이가 요즘 숙제를 힘들어하는 것 같아", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.MEMO, plan.intent)
        assertEquals(CaptureDisposition.MEMO_ONLY, plan.disposition)
    }

    @Test fun `unsupported relative week is clarification not silent write`() {
        val plan = engine.capture("다음주 화요일 3시 상담 일정 잡아줘", context())

        assertFalse(plan.saveable)
        assertEquals(CaptureDisposition.NEEDS_CONFIRM, plan.disposition)
        assertTrue(plan.reply.scheduleCommand is ScheduleClarification)
    }

    @Test fun `calendar command stays a confirmable calendar write`() {
        val plan = engine.capture("2026년 10월 17일 오후 2시 치과 약속 캘린더에 저장해줘", context())

        assertTrue(plan.saveable)
        assertEquals(AgentIntent.CALENDAR, plan.intent)
        assertEquals(CaptureDisposition.KEEP_TODAY, plan.disposition)
        assertTrue(plan.reply.scheduleCommand is CalendarCreateCommand)
    }

    @Test fun `alarm request needs confirm and stays unsaved by default`() {
        val plan = engine.capture("내일 오전 7시 물티슈 알람 맞춰줘", context())

        assertFalse(plan.saveable)
        assertEquals(AgentIntent.REMINDER, plan.intent)
        assertEquals(CaptureDisposition.NEEDS_CONFIRM, plan.disposition)
        assertTrue(plan.reply.scheduleCommand is AlarmRequestCommand)
        assertTrue(plan.labels.contains(CaptureLabels.EXTERNAL_APP))
    }

    @Test fun `blank transcript is not saveable`() {
        val plan = engine.capture("   ", context())

        assertFalse(plan.saveable)
        assertEquals(CaptureDisposition.NEEDS_CONFIRM, plan.disposition)
    }

    @Test fun `wake names still strip and ordinary words survive`() {
        val plan = engine.capture("라비야 내일 물티슈 챙겨줘", context())
        assertEquals("내일 물티슈 챙기기", plan.reply.proposedTask)
        assertEquals("내일 물티슈 챙겨줘", plan.transcript)

        val preserved = engine.capture("모모랜드는 재밌었어", context())
        assertEquals(AgentIntent.MEMO, preserved.intent)
        assertTrue(preserved.transcript.contains("모모랜드"))
    }

    @Test fun `past dated obligation stays undated rather than inventing a date`() {
        val plan = engine.capture("어제까지 낼 서류 챙겨야 해", context())
        // resolveCommandDue only knows 오늘/내일/모레 — undated candidate.
        assertTrue(plan.saveable)
        assertNull(plan.reply.proposedDueAt)
        assertTrue(plan.labels.contains(CaptureLabels.NEEDS_DATE))
    }

    @Test fun `interrogative obligation stays a question path`() {
        val plan = engine.capture("내일 뭐 챙겨야 해?", context(
            tasks = listOf(AssistantTask("t1", "물통 챙기기", false, createdAt = 1)),
        ))
        // asksActionCandidates fires — an answer, not a save.
        assertFalse(plan.saveable)
        assertEquals(AgentIntent.QUESTION, plan.intent)
    }
}
