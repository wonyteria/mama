package kr.mom.probe.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoSelectorsTest {
    private fun task(id: String, completed: Boolean = false, dueAt: Long? = null) =
        AssistantTask(id, "x", completed, 1L, dueAt = dueAt)

    @Test fun sameDayPastDeadlineCountsAsOverdueNotDueSoon() {
        val due = 100_000L
        val tasks = listOf(task("t1", dueAt = due))
        val now = due + 60_000L

        assertEquals(listOf("t1"), TodoSelectors.overdue(tasks, now).map { it.id })
        assertTrue(TodoSelectors.dueSoon(tasks, now).isEmpty())
    }

    @Test fun headlineCountsOpenTasksAboveEveryOtherState() {
        val tasks = listOf(task("t1", dueAt = 1L))
        val headline = TodoSelectors.todayHeadline(tasks, true, true, "sync error")

        assertEquals("남은 할 일\n1개", headline)
    }

    @Test fun headlineDistinguishesOffMissingFailedDoneAndEmpty() {
        val open = listOf<AssistantTask>()
        val done = listOf(task("t1", completed = true))

        assertEquals("알림 모으기가\n꺼져 있어요", TodoSelectors.todayHeadline(open, true, false, null))
        assertEquals("연결된 곳이\n아직 없어요", TodoSelectors.todayHeadline(open, false, true, null))
        assertEquals("연결 상태를\n확인해주세요", TodoSelectors.todayHeadline(open, true, true, "sync failed"))
        assertEquals("오늘 챙길 일을\n다 끝냈어요", TodoSelectors.todayHeadline(done, true, true, null))
        assertEquals("확인된 할 일이\n아직 없어요", TodoSelectors.todayHeadline(open, true, true, null))
    }

    @Test fun suspendedAndExcludedTasksDoNotCountAsDone() {
        val tasks = listOf(
            AssistantTask("t1", "x", false, 1L, suspended = true),
            AssistantTask("t2", "y", false, 1L, excluded = true),
        )

        // Neither suspended nor excluded tasks are open, but they are also not
        // completed work, so the verified-empty state applies.
        assertEquals("확인된 할 일이\n아직 없어요", TodoSelectors.todayHeadline(tasks, true, true, null))
    }
}
