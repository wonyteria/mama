package kr.mom.probe.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AssistantTaskStoreTest {
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
