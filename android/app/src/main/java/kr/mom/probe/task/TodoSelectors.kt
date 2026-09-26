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
        return open(tasks).filter { it.dueAt != null && (dateOf(it.dueAt).isBefore(today) || it.dueAt <= now) }
            .sortedBy { it.dueAt }
    }

    fun dueSoon(tasks: List<AssistantTask>, now: Long = System.currentTimeMillis(), daysAhead: Long = 1): List<AssistantTask> {
        val today = today(now)
        val end = today.plusDays(daysAhead)
        return open(tasks).filter { it.dueAt != null && it.dueAt > now && !dateOf(it.dueAt).isAfter(end) }
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

    /**
     * Home headline that never contradicts reality: an open task always counts,
     * collection off or no connected source says so, a failed or partial sync is
     * surfaced, real completion is named, and only a verified-empty state claims
     * there is nothing to do.
     */
    fun todayHeadline(
        tasks: List<AssistantTask>,
        configuredSources: Boolean,
        collectionEnabled: Boolean,
        sourceStatusMessage: String?,
    ): String {
        val open = openCount(tasks)
        return when {
            open > 0 -> "남은 할 일\n${open}개"
            !collectionEnabled -> "알림 모으기가\n꺼져 있어요"
            !configuredSources -> "연결된 곳이\n아직 없어요"
            sourceStatusMessage != null -> "연결 상태를\n확인해주세요"
            tasks.any { it.completed } -> "오늘 챙길 일을\n다 끝냈어요"
            else -> "확인된 할 일이\n아직 없어요"
        }
    }

    /** Empty-state copy matching the same states as [todayHeadline]. */
    fun todayEmptyMessage(
        tasks: List<AssistantTask>,
        configuredSources: Boolean,
        collectionEnabled: Boolean,
        sourceStatusMessage: String?,
    ): Pair<String, String> = when {
        !collectionEnabled ->
            "알림 모으기가 꺼져 있어요" to "설정에서 다시 켜면 골라둔 곳의 새 소식을 모아요."
        !configuredSources ->
            "연결된 곳이 아직 없어요" to "학교·학원 앱이나 홈페이지를 연결하면 새 소식이 여기 모여요."
        sourceStatusMessage != null ->
            "지금은 소식을 다 확인하지 못했어요" to "연결 상태가 좋아지면 남은 할 일이 여기 나타나요."
        tasks.any { it.completed } ->
            "남은 할 일이 없어요" to "오늘 챙길 일을 모두 마쳤어요."
        else ->
            "확인된 할 일이 아직 없어요" to "새 소식에서 확인할 일이 생기면 여기 모여요. 직접 추가할 수도 있어요."
    }

    fun today(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(seoul).toLocalDate()
    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(seoul).toLocalDate()
}
