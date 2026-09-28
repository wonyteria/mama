package kr.mom.probe.task

import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end synthetic chain: app/web record -> decision+plans ->
 * applyAutomaticPlansFrom -> stored task. Pins the dedup contract —
 * the same official document can never produce a second task, and a
 * same-title different official document can never collapse into one.
 */
class NoticeCaptureChainTest {
    private val now = 1_789_300_000_000L

    private fun record(
        title: String,
        text: String,
        itemId: String? = null,
        notificationKey: String = "key-${title}",
        postedAt: Long = now,
    ) = ProbeRecord(
        id = "id-$title-$itemId-$postedAt",
        packageName = "kr.test.school", appLabel = "학교",
        postedAt = postedAt, receivedAt = postedAt,
        title = title, text = text, bigText = "", textLines = emptyList(),
        subText = null, summaryText = null, category = null, channelId = null,
        notificationId = 1, notificationKey = notificationKey,
        isOngoing = false, isGroupSummary = false, rawHash = "h-$itemId",
        sourceMetadata = itemId?.let { id ->
            RecordSourceMetadata(
                kind = SourceKind.SCHOOL_WEBSITE, sourceId = "school-board",
                itemId = id, revisionHash = "rev-$id",
                origin = SourceOrigin(
                    canonicalUrl = "https://school.example/notice/$id",
                    host = "school.example", rawId = id,
                ),
                contentState = NoticeContentState.VERIFIED,
                obligation = NoticeObligation.REQUIRED,
                firstSeenAt = postedAt, lastFetchedAt = postedAt,
            )
        },
    )

    private fun apply(record: ProbeRecord, tasks: List<AssistantTask>): Pair<List<AssistantTask>, Boolean> {
        val plans = CandidateActionPlanner.plans(record, now = now, child = ChildNoticeProfile())
        return AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = tasks,
            sourceNotificationId = plans.firstOrNull()?.sourceNotificationId
                ?: kr.mom.probe.data.NoticeGrouping.groupId(record, ""),
            sourceRevisionId = record.id,
            plans = plans.map {
                AutoTaskPlan(
                    actionKind = it.actionKind ?: "submit",
                    text = it.text, checklist = it.checklist,
                    dueAt = it.dueAt, remindAt = it.remindAt,
                    evidenceText = it.evidenceText, sourceTitle = it.sourceTitle,
                    sourceLabel = it.sourceLabel, sourceCapturedAt = it.sourceCapturedAt,
                    audienceLabel = it.audienceLabel,
                )
            },
            noticeGroupKeys = plans.firstOrNull()?.noticeGroupKeys
                ?: kr.mom.probe.data.NoticeGrouping.keys(record, ""),
            now = now,
            idProvider = { "gen-${tasks.size}-${plans.size}" },
        )
    }

    @Test fun `required dated notice chains into a stored task with evidence`() {
        val notice = record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지", itemId = "42")
        val (tasks, changed) = apply(notice, emptyList())

        assertTrue(changed)
        assertTrue(tasks.isNotEmpty())
        val task = tasks.first()
        assertTrue(task.noticeGroupKeys.isNotEmpty())
        assertNotNull(task.evidenceText)
        assertEquals("학교", task.sourceLabel)
    }

    @Test fun `re-ingesting the same official document never duplicates the task`() {
        val appCopy = record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            itemId = "42", notificationKey = "app-1")
        val webCopy = record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            itemId = "42", notificationKey = "web-1")

        val (afterApp, _) = apply(appCopy, emptyList())
        val (afterWeb, _) = apply(webCopy, afterApp)

        // Same official item id → shared strong keys → same group → no dup.
        assertEquals(afterApp.size, afterWeb.size)
    }

    @Test fun `same title on different official documents stays separate`() {
        val first = record("체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            itemId = "42", notificationKey = "n-42")
        val second = record("체험학습 준비물", "준비물: 모자, 간식. 내일 오후 2시까지",
            itemId = "77", notificationKey = "n-77")

        val plansTwo = CandidateActionPlanner.plans(second, now = now)
        assertTrue(plansTwo.isNotEmpty())
        assertTrue(kr.mom.probe.data.NoticeGrouping.keys(first, "")
            .intersect(kr.mom.probe.data.NoticeGrouping.keys(second, "")).isEmpty())

        val (afterFirst, _) = apply(first, emptyList())
        val (afterSecond, _) = apply(second, afterFirst)

        assertTrue(afterSecond.size > afterFirst.size)
    }

    @Test fun `informational notice produces no automatic task`() {
        val notice = record("학교 소식", "다음 주 화요일은 개교기념일로 쉬는 날입니다.", itemId = "9")
        assertTrue(CandidateActionPlanner.plans(notice, now = now).isEmpty())
        val (tasks, _) = apply(notice, emptyList())
        assertTrue(tasks.isEmpty())
    }

    @Test fun `completion survives a resync of the same source`() {
        val notice = record("체험학습 준비물", "준비물: 도시락. 내일 오전 9시까지", itemId = "42")
        val (afterApply, _) = apply(notice, emptyList())
        val done = afterApply.map { it.copy(completed = true, completedAt = now) }
        val (afterResync, _) = apply(notice.copy(id = "rev-2"), done)

        assertTrue(afterResync.all { it.completed })
        assertEquals(done.size, afterResync.size)
    }
}
