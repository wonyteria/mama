package kr.mom.probe.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AssistantTaskStoreTest {
    @Test fun newlyCreatedAutomaticPlanRemainsActiveAndScheduled() {
        var nextId = 0
        val (tasks, changed) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = emptyList(),
            sourceNotificationId = "group-1",
            sourceRevisionId = "revision-1",
            plans = listOf(AutoTaskPlan("submit", "동의서 제출", dueAt = 30_000L, remindAt = 20_000L)),
            noticeGroupKeys = setOf("fp:notice"),
            now = 10_000L,
            idProvider = { "generated-${nextId++}" },
        )

        assertTrue(changed)
        val created = tasks.single()
        assertFalse(created.suspended)
        assertEquals(20_000L, created.remindAt)
        assertEquals("generated-0", created.reminderOccurrenceId)
        assertEquals("generated-1", created.id)
        assertEquals(setOf("group-1", "fp:notice"), created.noticeGroupKeys)
    }

    @Test fun newerRevisionUpdatesOneTaskAndRetainsOriginalEvidence() {
        val original = AssistantTask(
            id = "task",
            text = "동의서 금요일 제출",
            completed = false,
            createdAt = 10L,
            sourceNotificationId = "group-1",
            sourceRevisionId = "revision-1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE,
            dueAt = 30_000L,
            actionKind = "submit",
            noticeGroupKeys = setOf("group-1", "fp:notice"),
            evidenceText = "동의서는 금요일까지 제출해 주세요.",
            originalEvidenceText = "동의서는 금요일까지 제출해 주세요.",
        )

        val (tasks, changed) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(original),
            sourceNotificationId = "group-1",
            sourceRevisionId = "revision-2",
            plans = listOf(
                AutoTaskPlan(
                    "submit",
                    "동의서 월요일 제출",
                    dueAt = 40_000L,
                    evidenceText = "동의서는 월요일까지 제출해 주세요.",
                    sourceTitle = "동의서 제출일 정정",
                    sourceLabel = "학교",
                    sourceCapturedAt = 20L,
                    audienceLabel = "초등 2학년",
                ),
            ),
            noticeGroupKeys = setOf("fp:notice"),
            now = 20L,
        )

        assertTrue(changed)
        val revised = tasks.single()
        assertEquals("revision-2", revised.sourceRevisionId)
        assertEquals("동의서 월요일 제출", revised.text)
        assertEquals("동의서는 금요일까지 제출해 주세요.", revised.originalEvidenceText)
        assertEquals("동의서는 월요일까지 제출해 주세요.", revised.evidenceText)
        assertTrue(revised.revisionSummary?.contains("기한") == true)
        assertTrue(revised.revisionSummary?.contains("근거 문구") == true)
    }

    @Test fun consumingReminderClearsItAndReturnsOriginalTaskOnce() {
        val reminder = AssistantTask("one", "물통 챙기기", false, 10L, remindAt = 20L)
        val other = AssistantTask("two", "회신하기", false, 11L, remindAt = 30L)

        val (afterFirst, fired) = AssistantTaskStore.consumeReminderFrom(listOf(reminder, other), "one")
        val (afterSecond, firedAgain) = AssistantTaskStore.consumeReminderFrom(afterFirst, "one")

        assertEquals(reminder, fired)
        assertNull(afterFirst.first { it.id == "one" }.remindAt)
        assertEquals(30L, afterFirst.first { it.id == "two" }.remindAt)
        assertNull(firedAgain)
        assertEquals(afterFirst, afterSecond)
    }

    @Test fun completedTaskReminderCannotBeConsumed() {
        val completed = AssistantTask("done", "준비 완료", true, 10L, remindAt = 20L)
        val (unchanged, fired) = AssistantTaskStore.consumeReminderFrom(listOf(completed), "done")
        assertEquals(listOf(completed), unchanged)
        assertNull(fired)
    }

    @Test fun automaticNoticeRevisionSuspendsPreviousAutomaticTaskOnly() {
        val old = AssistantTask("old", "금요일 제출", false, 10L, sourceNotificationId = "source", sourceRevisionId = "rev1", sourceKind = AssistantTaskSource.AUTO_NOTICE, dueAt = 100L, remindAt = 90L)
        val user = AssistantTask("user", "직접 확인한 제출", false, 11L, sourceNotificationId = "manual", sourceRevisionId = "rev1", sourceKind = AssistantTaskSource.USER_CONFIRMED_NOTICE)

        val reconciled = AssistantTaskStore.reconcileAutomaticRevisionForTest(
            listOf(old, user),
            sourceNotificationId = "source",
            sourceRevisionId = "rev2",
        )
        val suspended = reconciled.first { it.id == "old" }

        assertFalse(suspended.completed)
        assertTrue(suspended.suspended)
        assertEquals("rev2", suspended.sourceRevisionId)
        assertNull(suspended.remindAt)
        assertFalse(reconciled.first { it.id == "user" }.completed)
    }

    @Test fun suspendedAutomaticTaskDoesNotLookCompletedToNotifierProjection() {
        val suspended = AssistantTask(
            "old",
            "정정 전 제출",
            false,
            10L,
            sourceNotificationId = "source",
            sourceRevisionId = "rev2",
            sourceKind = AssistantTaskSource.AUTO_NOTICE,
            suspended = true,
        )

        val completedSource = listOf(suspended).any { it.sourceNotificationId == "source" && it.completed }

        assertFalse(completedSource)
    }

    @Test fun sourceResetSuspendsOnlyActiveAutomaticTasksAndClearsAlarmState() {
        val automatic = AssistantTask(
            "auto",
            "옛 학교 준비물",
            false,
            10L,
            sourceNotificationId = "source:school-website-snjj:item-1",
            sourceRevisionId = "rev1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE,
            dueAt = 100L,
            remindAt = 90L,
            reminderOccurrenceId = "reminder",
            reminderOccurrenceAt = 90L,
            activeAlarmOccurrenceId = "alarm",
            activeAlarmScheduledAt = 80L,
            activeAlarmNotificationId = 27_100,
        )
        val manual = automatic.copy(
            id = "manual",
            sourceKind = AssistantTaskSource.USER_CONFIRMED_NOTICE,
            sourceNotificationId = "source:school-website-snjj:item-1",
        )
        val unrelated = automatic.copy(
            id = "other",
            sourceNotificationId = "source:school-website-snjj:item-2",
        )

        val next = AssistantTaskStore.suspendAutomaticSourcesForTest(
            listOf(automatic, manual, unrelated),
            setOf("source:school-website-snjj:item-1"),
        )
        val suspended = next.first { it.id == "auto" }

        assertTrue(suspended.suspended)
        assertNull(suspended.remindAt)
        assertNull(suspended.reminderOccurrenceId)
        assertNull(suspended.reminderOccurrenceAt)
        assertNull(suspended.activeAlarmOccurrenceId)
        assertNull(suspended.activeAlarmScheduledAt)
        assertNull(suspended.activeAlarmNotificationId)
        assertFalse(next.first { it.id == "manual" }.suspended)
        assertFalse(next.first { it.id == "other" }.suspended)
    }

    @Test fun staleOccurrenceCannotSnoozeOrCompleteLatestAlarm() {
        val active = AssistantTask(
            "task",
            "물티슈 넣기",
            false,
            10L,
            activeAlarmOccurrenceId = "new",
            activeAlarmScheduledAt = 1_000L,
            activeAlarmNotificationId = 27_100,
        )

        val (afterSnooze, snooze) = AssistantTaskStore.snoozeAlarmOccurrenceFrom(
            listOf(active),
            id = "task",
            occurrenceId = "old",
            nextAt = 2_000L,
            minutes = 10,
            nextOccurrenceId = "next",
        )
        val (afterComplete, completed) = AssistantTaskStore.completeAlarmOccurrenceFrom(afterSnooze, "task", "old")

        assertEquals(TaskAlarmSnoozeResult.Stale, snooze)
        assertFalse(completed)
        assertEquals(listOf(active), afterComplete)
    }

    @Test fun snoozePersistsNewOccurrenceAndKeepsAutoRetryCounterSeparate() {
        val active = AssistantTask(
            "task",
            "물통 챙기기",
            false,
            10L,
            reminderAttempts = 2,
            activeAlarmOccurrenceId = "fire",
            activeAlarmScheduledAt = 1_000L,
            activeAlarmNotificationId = 27_100,
        )

        val (next, result) = AssistantTaskStore.snoozeAlarmOccurrenceFrom(
            listOf(active),
            id = "task",
            occurrenceId = "fire",
            nextAt = 20_000L,
            minutes = 10,
            nextOccurrenceId = "next",
        )
        val snoozed = next.single()

        assertTrue(result is TaskAlarmSnoozeResult.Scheduled)
        assertEquals(20_000L, snoozed.remindAt)
        assertEquals("next", snoozed.reminderOccurrenceId)
        assertNull(snoozed.activeAlarmOccurrenceId)
        assertEquals(0, snoozed.reminderAttempts)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(10, snoozed.snoozeMinutes)
    }

    @Test fun fourConsecutiveSnoozesStayScheduledAndSurviveReload() {
        var tasks = listOf(
            AssistantTask(
                "task",
                "물티슈 넣기",
                false,
                10L,
                activeAlarmOccurrenceId = "fire-0",
                activeAlarmScheduledAt = 1_000L,
                activeAlarmNotificationId = 27_100,
            ),
        )

        var lastScheduled: TaskAlarmSnoozeResult.Scheduled? = null
        repeat(4) { index ->
            val (next, result) = AssistantTaskStore.snoozeAlarmOccurrenceFrom(
                tasks,
                id = "task",
                occurrenceId = "fire-$index",
                nextAt = 20_000L + index * 600_000L,
                minutes = 10,
                nextOccurrenceId = "fire-${index + 1}",
            )
            tasks = next
            lastScheduled = result as? TaskAlarmSnoozeResult.Scheduled
            // Simulate the reminder firing again before the next snooze.
            if (index < 3) {
                val (consumed, fired) = AssistantTaskStore.consumeReminderFrom(
                    tasks, "task", "fire-${index + 1}", 27_100,
                )
                assertTrue(fired != null)
                tasks = consumed
            }
        }
        val snoozed = tasks.single()

        assertTrue(lastScheduled is TaskAlarmSnoozeResult.Scheduled)
        assertEquals(4, snoozed.snoozeCount)
        assertEquals(40, snoozed.snoozeMinutes)
        assertEquals(1_820_000L, snoozed.remindAt)
        assertEquals("fire-4", snoozed.reminderOccurrenceId)

        // Persist + reload (process restart): every field needed to resume the chain survives.
        val restored = decodeTask(encodeTask(snoozed))
        assertEquals(snoozed, restored)
        assertEquals(4, restored.snoozeCount)
        assertEquals(1_820_000L, restored.remindAt)
    }

    @Test fun disjointOfficialIdsWithSameFingerprintProduceSeparateTasks() {
        // Two official documents share institution/title/date/body fingerprint but carry
        // different ext/url ids. The shared fp anchor must not reconcile one onto the other.
        val docAKeys = setOf("fp:shared", "ext:doc-a", "url:doc-a", "nid-a")
        val (afterA, _) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = emptyList(),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-a1",
            plans = listOf(AutoTaskPlan("submit", "서류 제출 A", dueAt = 30_000L)),
            noticeGroupKeys = docAKeys,
            now = 10_000L,
            idProvider = { "id-a" },
        )
        assertEquals(1, afterA.size)

        val docBKeys = setOf("fp:shared", "ext:doc-b", "url:doc-b", "nid-b")
        val (afterB, changed) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = afterA,
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-b1",
            plans = listOf(AutoTaskPlan("submit", "서류 제출 B", dueAt = 40_000L)),
            noticeGroupKeys = docBKeys,
            now = 20_000L,
            idProvider = { "id-b" },
        )

        assertTrue(changed)
        assertEquals(2, afterB.size)
        assertEquals("서류 제출 A", afterB.first { it.id == "id-a" }.text)
        assertEquals("rev-a1", afterB.first { it.id == "id-a" }.sourceRevisionId)
        assertEquals("서류 제출 B", afterB.first { it.id == "id-b" }.text)
    }

    @Test fun completionOfOneOfficialNoticeDoesNotCarryToDisjointOfficialNotice() {
        val doneA = AssistantTask(
            "task-a", "서류 제출 A", completed = true, createdAt = 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-a1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a", "url:doc-a"),
        )
        val openB = doneA.copy(
            id = "task-b", text = "서류 제출 B", completed = false,
            sourceRevisionId = "rev-b1",
            noticeGroupKeys = setOf("fp:shared", "ext:doc-b", "url:doc-b"),
        )
        // A third revision of document B must update only task-b.
        val (after, _) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(doneA, openB),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-b2",
            plans = listOf(AutoTaskPlan("submit", "서류 제출 B 정정", dueAt = 50_000L)),
            noticeGroupKeys = setOf("fp:shared", "ext:doc-b", "url:doc-b"),
            now = 30_000L,
        )
        assertEquals("rev-b2", after.first { it.id == "task-b" }.sourceRevisionId)
        assertFalse(after.first { it.id == "task-b" }.completed)
        assertEquals("rev-a1", after.first { it.id == "task-a" }.sourceRevisionId)
        assertTrue(after.first { it.id == "task-a" }.completed)
    }

    @Test fun appCopyWithoutOfficialIdsStillMergesIntoOfficialTask() {
        // The notification lane carries no ext/url keys: a fingerprint-only match must
        // still bridge the app copy onto the official document's task.
        val official = AssistantTask(
            "task-a", "서류 제출", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-a1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a", "url:doc-a"),
        )
        val (after, changed) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(official),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-app-copy",
            plans = listOf(AutoTaskPlan("submit", "서류 제출 정정", dueAt = 50_000L)),
            noticeGroupKeys = setOf("fp:shared", "nid-app"),
            now = 20_000L,
        )
        assertEquals(1, after.size)
        assertEquals("rev-app-copy", after.single().sourceRevisionId)
        assertTrue(changed)
    }

    @Test fun suspendingOneOfficialNoticeLeavesDisjointOfficialTaskActive() {
        val taskA = AssistantTask(
            "task-a", "서류 제출 A", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-a1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = 30_000L, remindAt = 20_000L,
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a", "url:doc-a"),
        )
        val taskB = taskA.copy(
            id = "task-b", text = "서류 제출 B", sourceRevisionId = "rev-b1",
            noticeGroupKeys = setOf("fp:shared", "ext:doc-b", "url:doc-b"),
        )
        val suspended = AssistantTaskStore.reconcileAutomaticRevisionForTest(
            listOf(taskA, taskB),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-b2",
            noticeGroupKeys = setOf("fp:shared", "ext:doc-b", "url:doc-b"),
        )
        assertTrue(suspended.first { it.id == "task-b" }.suspended)
        assertFalse(suspended.first { it.id == "task-a" }.suspended)
        assertEquals(20_000L, suspended.first { it.id == "task-a" }.remindAt)
    }

    @Test fun unrelatedRevisionPreservesUserSnoozeAndCompletion() {
        // A user snoozed the automatic task four times; a body-only revision that does
        // not change the deadline must not reset the reminder or the snooze history.
        val snoozed = AssistantTask(
            "task-1", "서류 제출", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = 100_000L, remindAt = 90_000L,
            reminderOccurrenceId = "occ-snooze", reminderOccurrenceAt = 90_000L,
            snoozeCount = 4, snoozeMinutes = 240,
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a"),
            userEdited = true,
        )
        val (after, changed) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(snoozed),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-2",
            plans = listOf(AutoTaskPlan("submit", "서류 제출", dueAt = 100_000L, remindAt = null)),
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a"),
            now = 50_000L,
        )
        val updated = after.single()
        assertTrue(changed)
        assertEquals(90_000L, updated.remindAt)
        assertEquals("occ-snooze", updated.reminderOccurrenceId)
        assertEquals(4, updated.snoozeCount)
        assertTrue(updated.userEdited)
        assertFalse(updated.completed)
    }

    @Test fun undatedTaskKeepsUserSnoozeAcrossCrossLaneRevision() {
        val snoozed = AssistantTask(
            "task-1", "동의서 제출 안내", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = null, remindAt = 90_000L,
            reminderOccurrenceId = "occ-1", reminderOccurrenceAt = 90_000L,
            snoozeCount = 4, snoozeMinutes = 240,
            noticeGroupKeys = setOf("fp:shared"),
        )
        val (after, _) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(snoozed),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-web",
            plans = listOf(AutoTaskPlan("submit", "동의서 제출", dueAt = null, remindAt = null)),
            noticeGroupKeys = setOf("fp:shared", "ext:doc-a"),
            now = 50_000L,
        )
        assertEquals(90_000L, after.single().remindAt)
        assertEquals("occ-1", after.single().reminderOccurrenceId)
        assertEquals(4, after.single().snoozeCount)
    }

    @Test fun genuineDeadlineChangeRearmsAutomaticReminder() {
        val existing = AssistantTask(
            "task-1", "서류 제출", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = 100_000L, remindAt = 90_000L,
            reminderOccurrenceId = "occ-1", reminderOccurrenceAt = 90_000L,
            noticeGroupKeys = setOf("fp:shared"),
        )
        val (after, _) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(existing),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-2",
            plans = listOf(AutoTaskPlan("submit", "서류 제출", dueAt = 200_000L, remindAt = 180_000L)),
            noticeGroupKeys = setOf("fp:shared"),
            now = 50_000L,
        )
        val updated = after.single()
        assertEquals(200_000L, updated.dueAt)
        assertEquals(180_000L, updated.remindAt)
        assertTrue(updated.reminderOccurrenceId != "occ-1")
        assertEquals(0, updated.reminderAttempts)
        assertNotNull(updated.revisionSummary)
    }

    @Test fun uncertainRevisionMarksNeedsReviewWithoutHidingObligation() {
        val task = AssistantTask(
            "task-1", "서류 제출", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = 100_000L, remindAt = 90_000L,
            reminderOccurrenceId = "occ-1", reminderOccurrenceAt = 90_000L,
            evidenceText = "금요일까지 제출",
            originalEvidenceText = "금요일까지 제출",
            noticeGroupKeys = setOf("fp:shared"),
        )
        val updated = AssistantTaskStore.markNeedsReviewForTest(
            listOf(task), "fp:shared", "rev-2", setOf("fp:shared"),
        ).single()

        assertTrue(updated.needsReview)
        assertFalse(updated.suspended)
        assertEquals(100_000L, updated.dueAt)
        assertEquals(90_000L, updated.remindAt)
        assertEquals("occ-1", updated.reminderOccurrenceId)
        assertEquals("금요일까지 제출", updated.evidenceText)
        assertEquals("rev-2", updated.sourceRevisionId)
        assertNotNull(updated.revisionSummary)
    }

    @Test fun needsReviewFlagSurvivesStorageRoundTrip() {
        val task = AssistantTask("t", "x", false, 1L, needsReview = true)
        val decoded = decodeTask(encodeTask(task))
        assertTrue(decoded.needsReview)
    }

    @Test fun clearRevisionResolvesNeedsReview() {
        val flagged = AssistantTask(
            "task-1", "서류 제출", false, 10L,
            sourceNotificationId = "fp:shared", sourceRevisionId = "rev-2",
            sourceKind = AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
            dueAt = 100_000L, needsReview = true,
            noticeGroupKeys = setOf("fp:shared"),
        )
        val (after, _) = AssistantTaskStore.applyAutomaticPlansFrom(
            tasks = listOf(flagged),
            sourceNotificationId = "fp:shared",
            sourceRevisionId = "rev-3",
            plans = listOf(AutoTaskPlan("submit", "서류 제출", dueAt = 100_000L)),
            noticeGroupKeys = setOf("fp:shared"),
            now = 50_000L,
        )
        assertFalse(after.single().needsReview)
    }

    @Test fun taskGroupKeysRoundTripThroughStorage() {
        val task = AssistantTask(
            "task",
            "서류 제출",
            false,
            10L,
            sourceNotificationId = "group",
            sourceRevisionId = "rev1",
            sourceKind = AssistantTaskSource.AUTO_NOTICE,
            dueAt = null,
            noticeGroupKeys = setOf("nid-1", "ext:abc", "fp:def"),
            evidenceText = "서류를 제출해 주세요.",
            originalEvidenceText = "서류를 제출해 주세요.",
            sourceTitle = "서류 안내",
            sourceLabel = "학교",
            sourceCapturedAt = 9L,
            audienceLabel = "초등 2학년",
            revisionSummary = "기한 변경",
        )

        val restored = decodeTask(encodeTask(task))

        assertEquals(task, restored)
        assertEquals(setOf("nid-1", "ext:abc", "fp:def"), restored.noticeGroupKeys)
        assertNull(restored.dueAt)
    }

    @Test fun completingActiveOccurrenceFinishesOnlyThatTask() {
        val active = AssistantTask("one", "등교 가방", false, 10L, activeAlarmOccurrenceId = "fire", remindAt = 30L)
        val other = AssistantTask("two", "회신", false, 11L, activeAlarmOccurrenceId = "other", remindAt = 40L)

        val (next, completed) = AssistantTaskStore.completeAlarmOccurrenceFrom(listOf(active, other), "one", "fire")

        assertTrue(completed)
        assertTrue(next.first { it.id == "one" }.completed)
        assertNull(next.first { it.id == "one" }.remindAt)
        assertFalse(next.first { it.id == "two" }.completed)
        assertEquals(40L, next.first { it.id == "two" }.remindAt)
    }

    @Test fun nullableIdDecodeTreatsAndroidJsonNullAsAbsent() {
        val item = JSONObject()
            .put("activeAlarmOccurrenceId", JSONObject.NULL)
            .put("reminderOccurrenceId", JSONObject.NULL)
            .put("sourceNotificationId", "null")
            .put("text", "null")

        assertNull(item.optionalId("activeAlarmOccurrenceId"))
        assertNull(item.optionalId("reminderOccurrenceId"))
        assertNull(item.optionalId("sourceNotificationId"))
        assertEquals("null", item.getString("text"))
    }
}
