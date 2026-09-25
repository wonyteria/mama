package kr.mom.probe.task

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single source of truth for task visibility. Home, the Todo list, briefings and the
 * widget all count the same scope: open = not completed, not suspended, not excluded.
 */
object TodoSelectors {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun open(tasks: List<AssistantTask>): List<AssistantTask> =
        tasks.filter { !it.completed && !it.suspended && !it.excluded }

    fun openCount(tasks: List<AssistantTask>): Int = open(tasks).size

    fun overdue(tasks: List<AssistantTask>, now: Long = System.currentTimeMillis()): List<AssistantTask> {
        val today = today(now)
        return open(tasks).filter { it.dueAt != null && dateOf(it.dueAt).isBefore(today) }
            .sortedBy { it.dueAt }
    }

    fun dueSoon(tasks: List<AssistantTask>, now: Long = System.currentTimeMillis(), daysAhead: Long = 1): List<AssistantTask> {
        val today = today(now)
        val end = today.plusDays(daysAhead)
        return open(tasks).filter { it.dueAt != null && !dateOf(it.dueAt).isBefore(today) && !dateOf(it.dueAt).isAfter(end) }
            .sortedBy { it.dueAt }
    }

    fun later(tasks: List<AssistantTask>, now: Long = System.currentTimeMillis(), daysAhead: Long = 1): List<AssistantTask> {
        val end = today(now).plusDays(daysAhead)
        return open(tasks).filter { it.dueAt != null && dateOf(it.dueAt).isAfter(end) }
            .sortedBy { it.dueAt }
    }

    fun undated(tasks: List<AssistantTask>): List<AssistantTask> =
        open(tasks).filter { it.dueAt == null }.sortedByDescending { it.createdAt }

    fun completed(tasks: List<AssistantTask>): List<AssistantTask> =
        tasks.filter { it.completed && !it.suspended }.sortedByDescending { it.completedAt ?: it.createdAt }

    fun excluded(tasks: List<AssistantTask>): List<AssistantTask> =
        tasks.filter { it.excluded && !it.completed }.sortedByDescending { it.createdAt }

    fun linkedTo(tasks: List<AssistantTask>, noticeGroupKeys: Set<String>): List<AssistantTask> =
        if (noticeGroupKeys.isEmpty()) emptyList()
        else tasks.filter { task ->
            !task.suspended && kr.mom.probe.data.NoticeGrouping.matches(
                noticeGroupKeys,
                task.noticeGroupKeys + listOfNotNull(task.sourceNotificationId),
            )
        }

    fun checklistProgress(task: AssistantTask): Pair<Int, Int> =
        task.checklist.count { it.done } to task.checklist.size

    fun progressText(task: AssistantTask): String? {
        if (task.checklist.isEmpty()) return null
        val (done, total) = checklistProgress(task)
        return "$done/$total 준비"
    }

    fun dueLabel(task: AssistantTask, now: Long = System.currentTimeMillis()): String? {
        val dueAt = task.dueAt ?: return null
        val due = dateOf(dueAt)
        val today = today(now)
        return when {
            due.isBefore(today) -> "기한 지남"
            due == today -> "오늘"
            due == today.plusDays(1) -> "내일"
            else -> null
        }
    }

    fun today(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(seoul).toLocalDate()
    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(seoul).toLocalDate()
}
