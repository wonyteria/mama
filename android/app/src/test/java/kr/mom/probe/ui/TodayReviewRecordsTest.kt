package kr.mom.probe.ui

import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.task.AssistantTaskSource
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayReviewRecordsTest {
    @Test fun canonicalTodoLinkRemovesNoticeFromReviewQueue() {
        val record = ProbeRecord(
            id = "revision-1",
            packageName = "school.app",
            appLabel = "학교",
            postedAt = 1_790_812_800_000L,
            receivedAt = 1_790_812_800_001L,
            title = "동의서 제출 안내",
            text = "동의서를 제출해 주세요.",
            bigText = "동의서를 제출해 주세요. 보호자 서명이 필요합니다.",
            textLines = emptyList(),
            subText = null,
            summaryText = null,
            category = null,
            channelId = null,
            notificationId = 1,
            notificationKey = "notice-key",
            isOngoing = false,
            isGroupSummary = false,
            rawHash = "hash",
        )
        val institution = "school:성남정자초등학교"
        val groupKeys = NoticeGrouping.keys(record, institution)
        val task = AssistantTask(
            id = "task-1",
            text = "동의서 제출",
            completed = false,
            createdAt = record.receivedAt,
            sourceNotificationId = NoticeGrouping.groupId(record, institution),
            sourceRevisionId = record.id,
            sourceKind = AssistantTaskSource.AUTO_NOTICE,
            actionKind = "submit",
            noticeGroupKeys = groupKeys,
        )

        val review = todayReviewRecords(listOf(record), listOf(task), ChildNoticeProfile(), institution)

        assertTrue(review.isEmpty())
    }
}
