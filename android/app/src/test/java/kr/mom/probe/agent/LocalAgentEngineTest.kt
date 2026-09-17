package kr.mom.probe.agent

import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.reminder.ExternalAlarmHandler
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentEngineTest {
    @Test fun `external alarm prepare uses saved handler when explicit handler is absent`() {
        val saved = ExternalAlarmHandler("saved.pkg", "SavedActivity", "저장된 시계")
        val explicit = ExternalAlarmHandler("explicit.pkg", "ExplicitActivity", "직접 고른 시계")

        assertEquals(saved, resolveExternalAlarmHandlerForPrepare(null) { saved })
        assertEquals(explicit, resolveExternalAlarmHandlerForPrepare(explicit) { saved })
    }
    private val fixedNow = 1_789_300_000_000L
    private val engine = LocalAgentEngine({ fixedNow })

    @Test
    fun proposesTaskInsteadOfExecutingCommand() {
        val reply = engine.answer("모모야 내일 물티슈 챙겨줘", context())

        assertEquals("내일 물티슈 챙기기", reply.proposedTask)
        assertNull(reply.proposedDueAt)
        assertNull(reply.proposedRemindAt)
        assertTrue(reply.message.contains("저장할게요"))
    }

    @Test
    fun clearSaveCommandWithTimeDoesNotCreateReminderUnlessAsked() {
        val reply = engine.answer("내일 오후 3시 물통 챙겨줘", context())

        assertEquals("내일 오후 3시 물통 챙기기", reply.proposedTask)
        assertTrue(reply.proposedDueAt != null && reply.proposedDueAt!! > fixedNow)
        assertNull(reply.proposedRemindAt)
    }

    @Test
    fun explicitReminderCommandUsesRequestedTime() {
        val reply = engine.answer("내일 오후 3시 물통 알려줘", context())

        assertEquals("내일 오후 3시 물통", reply.proposedTask)
        assertEquals(reply.proposedDueAt, reply.proposedRemindAt)
    }

    @Test
    fun lookupStyleTellMeDoesNotPersistTask() {
        val reply = engine.answer("내일 준비물 알려줘", context(notifications = listOf(
            record("체험학습 준비물", "준비물: 물통. 내일 오전 9시까지"),
        )))

        assertNull(reply.proposedTask)
        assertTrue(reply.message.contains("물통"))
    }

    @Test
    fun negativeAndExplanationScheduleTextDoesNotMutate() {
        val alarm = engine.answer("내일 오전 7시 기상 알람 맞추지 마", context())
        assertNull(alarm.scheduleCommand)
        assertNull(alarm.proposedTask)

        val calendar = engine.answer("2026년 10월 17일 14:30 상담 일정 저장 방법 설명해줘", context())
        assertNull(calendar.scheduleCommand)
        assertNull(calendar.proposedTask)
    }

    @Test
    fun recentNotificationTellMeDoesNotPersistTask() {
        val reply = engine.answer("최근 알림 알려줘", context(notifications = listOf(
            record("예전 공지", "내용", postedAt = 1),
            record("새 공지", "내용", postedAt = 3),
        )))

        assertNull(reply.proposedTask)
        assertTrue(reply.message.indexOf("새 공지") < reply.message.indexOf("예전 공지"))
    }

    @Test
    fun stripsTaskListPrefixFromAddCommand() {
        val reply = engine.answer("부탁 목록에 예방접종 예약 추가해줘", context())

        assertEquals("예방접종 예약", reply.proposedTask)
    }

    @Test
    fun questionWithInterrogativeIsNotMistakenForCommand() {
        val reply = engine.answer("내일 뭐 챙겨줘?", context(notifications = listOf(
            record("체험학습 준비물", "준비물: 물통, 모자. 내일 오전 9시까지"),
        )))

        assertNull(reply.proposedTask)
        assertTrue(reply.message.contains("물통, 모자"))
    }

    @Test
    fun answersTomorrowPrepareQuestionFromParsedLocalNotification() {
        val reply = engine.answer("내일 준비물이 뭐야?", context(notifications = listOf(
            record("체험학습 준비물", "준비물: 도시락, 물통, 모자. 내일 오전 9시까지", appLabel = "학교앱"),
            record("다음 주 준비", "준비물: 색종이. 다음 주까지", appLabel = "학원앱", postedAt = 2),
        )))

        assertTrue(reply.message.contains("확인할 일 1개"))
        assertTrue(reply.message.contains("도시락, 물통, 모자"))
        assertTrue(reply.message.contains("09:00"))
        assertTrue(reply.message.contains("학교앱"))
    }

    @Test
    fun answersIneligibleOptionalProgramWithTargetReason() {
        val reply = engine.answer("토요미래산책 우리 아이 해당돼?", context(
            notifications = listOf(record("토요미래산책 신청 안내", "대상 초등 5~6학년, 중 1~3학년. 신청 2026-09-28 월 17:00 ~ 2026-10-02 금 09:00. 선착순 마감.")),
            childProfile = ChildNoticeProfile(grade = 2, schoolLevel = SchoolLevel.ELEMENTARY),
        ))

        assertTrue(reply.message.contains("해당하지 않아요"))
        assertTrue(reply.message.contains("초등 5~6학년"))
        assertNull(reply.proposedTask)
    }

    @Test
    fun combinesCandidatesAndPendingTasksWithoutCompletedTasks() {
        val reply = engine.answer("놓친 할 것 있어?", context(
            notifications = listOf(record("회신 안내", "신청서는 모레까지 회신해 주세요.")),
            tasks = listOf(
                task("우유 신청하기", completed = false, createdAt = 3),
                task("체육복 준비", completed = true, createdAt = 4),
            ),
        ))

        assertTrue(reply.message.contains("확인할 일 2개"))
        assertTrue(reply.message.contains("회신 안내"))
        assertTrue(reply.message.contains("우유 신청하기"))
        assertTrue(!reply.message.contains("체육복 준비"))
    }

    @Test
    fun reportsPendingTasksNewestFirst() {
        val reply = engine.answer("미완료 부탁 보여줘", context(tasks = listOf(
            task("먼저 만든 부탁", createdAt = 1),
            task("나중에 만든 부탁", createdAt = 2),
            task("완료된 부탁", completed = true, createdAt = 3),
        )))

        assertTrue(reply.message.contains("미완료 부탁 2개"))
        assertTrue(reply.message.indexOf("나중에 만든 부탁") < reply.message.indexOf("먼저 만든 부탁"))
        assertTrue(!reply.message.contains("완료된 부탁"))
    }

    @Test
    fun reportsRecentNotificationsNewestFirst() {
        val reply = engine.answer("최근 알림 알려줘", context(notifications = listOf(
            record("예전 공지", "내용", postedAt = 1),
            record("새 공지", "내용", postedAt = 3),
        )))

        assertTrue(reply.message.indexOf("새 공지") < reply.message.indexOf("예전 공지"))
    }

    @Test
    fun refusesUnsupportedGeneralKnowledgeWithoutPretending() {
        val reply = engine.answer("서울 날씨가 어때?", context(childName = "민서"))

        assertNull(reply.proposedTask)
        assertTrue(reply.message.contains("이 휴대폰에 저장된 민서 알림과 부탁"))
        assertTrue(reply.message.contains("질문만 답할 수 있어요"))
    }

    @Test
    fun emptyResultExplainsCollectionBoundary() {
        val reply = engine.answer("내일 제출할 것 있어?", context(childName = "민서"))

        assertTrue(reply.message.contains("찾지 못했어요"))
        assertTrue(reply.message.contains("아직 이 휴대폰에 수집되지 않았을 수 있어요"))
    }

    @Test
    fun groupsUpdatedRevisionsOfOneNotification() {
        val first = record("준비물 안내", "준비물: 물통. 내일까지")
        val updated = first.copy(id = "updated", postedAt = first.postedAt + 1, receivedAt = first.receivedAt + 1, text = "준비물: 물통, 모자. 내일까지")
        val reply = engine.answer("내일 준비물이 뭐야?", context(notifications = listOf(first, updated)))

        assertTrue(reply.message.contains("확인할 일 1개"))
        assertTrue(reply.message.contains("물통, 모자"))
    }

    @Test
    fun relativeWordIsResolvedAgainstQuestionDate() {
        val notice = record("준비물 안내", "준비물: 물통. 내일까지")
        val twoDaysLater = LocalAgentEngine({ fixedNow + 2 * 86_400_000L })
        val reply = twoDaysLater.answer("내일 준비물이 뭐야?", context(notifications = listOf(notice)))

        assertTrue(reply.message.contains("찾지 못했어요"))
    }

    @Test
    fun excludesTaskLinkedCandidate() {
        val linked = record("제출 안내", "신청서를 내일까지 제출해 주세요.")
        val linkedId = ProbeRules.notificationIdentity(linked.packageName, linked.notificationKey)
        val reply = engine.answer("내일 할 것 있어?", context(
            notifications = listOf(linked),
            tasks = listOf(task("내일 신청서 제출", sourceNotificationId = linkedId)),
        ))

        assertTrue(reply.message.contains("확인할 일 1개"))
        assertTrue(reply.message.contains("부탁: 내일 신청서 제출"))
    }

    @Test
    fun sourceLookupExplainsWhenOnlyPartOfTheSourcesWereChecked() {
        val reply = engine.answer(
            "최근 알림 보여줘",
            context(sourceStatusMessage = "일부 소식과 첨부만 확인했어요"),
        )

        assertTrue(reply.message.contains("수집 상태: 일부 소식과 첨부만 확인했어요"))
    }

    private fun context(
        childName: String = "아이",
        notifications: List<ProbeRecord> = emptyList(),
        tasks: List<AssistantTask> = emptyList(),
        childProfile: ChildNoticeProfile = ChildNoticeProfile(),
        sourceStatusMessage: String? = null,
    ) = LocalAgentContext(childName, notifications, tasks, childProfile, sourceStatusMessage = sourceStatusMessage)

    private fun record(
        title: String,
        text: String,
        appLabel: String = "학교",
        postedAt: Long = fixedNow,
    ) = ProbeRecord(
        id = "id-$postedAt-$title",
        packageName = "pkg",
        appLabel = appLabel,
        postedAt = postedAt,
        receivedAt = postedAt,
        title = title,
        text = text,
        bigText = "",
        textLines = emptyList(),
        subText = null,
        summaryText = null,
        category = null,
        channelId = null,
        notificationId = postedAt.toInt(),
        notificationKey = "key-$postedAt",
        isOngoing = false,
        isGroupSummary = false,
        rawHash = "hash-$postedAt",
    )

    private fun task(text: String, completed: Boolean = false, createdAt: Long = 1, sourceNotificationId: String? = null) = AssistantTask(
        id = "task-$createdAt-$text",
        text = text,
        completed = completed,
        createdAt = createdAt,
        sourceNotificationId = sourceNotificationId,
    )
}

