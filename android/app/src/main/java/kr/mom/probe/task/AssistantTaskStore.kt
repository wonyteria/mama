package kr.mom.probe.task

import android.content.Context
import android.util.Base64
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.mom.probe.data.ProbeCrypto
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantTaskSource { USER_LOCAL, USER_CONFIRMED_NOTICE, AUTO_NOTICE, LEGACY }

enum class TaskActionKind(val label: String) {
    SUBMIT("제출·신청"),
    PREPARE("준비물 챙기기"),
}

data class TaskChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
)

/** One deduplicated action plan produced from a single notice revision. */
data class AutoTaskPlan(
    val actionKind: String,
    val text: String,
    val checklist: List<String> = emptyList(),
    val dueAt: Long? = null,
    val remindAt: Long? = null,
)

data class AssistantTask(
    val id: String,
    val text: String,
    val completed: Boolean,
    val createdAt: Long,
    val sourceNotificationId: String? = null,
    val sourceRevisionId: String? = null,
    val sourceKind: AssistantTaskSource = AssistantTaskSource.USER_LOCAL,
    val dueAt: Long? = null,
    val remindAt: Long? = null,
    val reminderAttempts: Int = 0,
    val suspended: Boolean = false,
    val reminderOccurrenceId: String? = null,
    val reminderOccurrenceAt: Long? = null,
    val activeAlarmOccurrenceId: String? = null,
    val activeAlarmScheduledAt: Long? = null,
    val activeAlarmNotificationId: Int? = null,
    val snoozeCount: Int = 0,
    val snoozeMinutes: Int = 0,
    val checklist: List<TaskChecklistItem> = emptyList(),
    val actionKind: String? = null,
    val excluded: Boolean = false,
    val completedAt: Long? = null,
    val checklistBeforeCompletion: List<TaskChecklistItem>? = null,
    val userEdited: Boolean = false,
)

sealed class TaskAlarmSnoozeResult {
    data class Scheduled(val task: AssistantTask, val nextAt: Long) : TaskAlarmSnoozeResult()
    object Stale : TaskAlarmSnoozeResult()
    object LimitReached : TaskAlarmSnoozeResult()
    object Failed : TaskAlarmSnoozeResult()
}

/** Entire payload is encrypted using the existing Android Keystore-backed local data key. */
class AssistantTaskStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("assistant_tasks", Context.MODE_PRIVATE)
    private val mutableTasks = MutableStateFlow<List<AssistantTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private var loaded = false

    private fun requireConsent() {
        val repository = ProbeRepository.get(app)
        val settings = repository.settings.value
        check(repository.isReady.value && repository.lastError.value == null && settings.consent &&
            settings.consentVersion == ProbeRules.CONSENT_VERSION && settings.onboardingDone) {
            "앱에서 처음 설정을 마쳐주세요."
        }
    }

    @Synchronized
    fun load() {
        requireConsent()
        val encoded = preferences.getString("encrypted", null)
        val result = if (encoded == null) emptyList() else {
            val array = JSONArray(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), "assistant_tasks"))
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val sourceNotificationId = item.optionalId("sourceNotificationId") ?: item.optionalId("sourceRecordId")
                AssistantTask(
                    item.getString("id"),
                    item.getString("text"),
                    item.getBoolean("completed"),
                    item.getLong("createdAt"),
                    sourceNotificationId,
                    item.optionalId("sourceRevisionId"),
                    runCatching { AssistantTaskSource.valueOf(item.optString("sourceKind")) }
                        .getOrDefault(if (sourceNotificationId != null) AssistantTaskSource.LEGACY else AssistantTaskSource.USER_LOCAL),
                    item.optionalLong("dueAt"),
                    item.optionalLong("remindAt"),
                    item.optInt("reminderAttempts", 0).coerceIn(0, 2),
                    item.optBoolean("suspended", false),
                    item.optionalId("reminderOccurrenceId"),
                    item.optionalLong("reminderOccurrenceAt"),
                    item.optionalId("activeAlarmOccurrenceId"),
                    item.optionalLong("activeAlarmScheduledAt"),
                    item.optionalInt("activeAlarmNotificationId"),
                    item.optInt("snoozeCount", 0).coerceIn(0, MAX_SNOOZES),
                    item.optInt("snoozeMinutes", 0).coerceIn(0, MAX_SNOOZE_MINUTES),
                    decodeChecklist(item.optJSONArray("checklist")),
                    item.optionalId("actionKind"),
                    item.optBoolean("excluded", false),
                    item.optionalLong("completedAt"),
                    if (item.has("checklistBeforeCompletion") && !item.isNull("checklistBeforeCompletion"))
                        decodeChecklist(item.optJSONArray("checklistBeforeCompletion")) else null,
                    item.optBoolean("userEdited", false),
                )
            }
        }
        mutableTasks.value = result
        loaded = true
        TaskReminderScheduler.sync(app, result)
    }

    @Synchronized
    fun add(text: String, sourceNotificationId: String? = null, dueAt: Long? = null, remindAt: Long? = null): Boolean {
        return addTask(text, sourceNotificationId, dueAt, remindAt) != null
    }

    @Synchronized
    fun addTask(
        text: String,
        sourceNotificationId: String? = null,
        dueAt: Long? = null,
        remindAt: Long? = null,
        sourceRevisionId: String? = null,
        sourceKind: AssistantTaskSource = if (sourceNotificationId == null) AssistantTaskSource.USER_LOCAL else AssistantTaskSource.USER_CONFIRMED_NOTICE,
        checklist: List<String> = emptyList(),
        actionKind: String? = null,
    ): AssistantTask? {
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_TEXT) { "할 일을 1~300자로 적어주세요." }
        require(dueAt == null || dueAt > 0L) { "기한을 다시 확인해주세요." }
        require(remindAt == null || remindAt > 0L) { "알림 시간을 다시 확인해주세요." }
        val current = mutableTasks.value
        if (sourceNotificationId != null) {
            val sameSource = current.filter {
                it.sourceNotificationId == sourceNotificationId && it.actionKind == actionKind
            }
            if (sameSource.any { it.sourceKind != AssistantTaskSource.AUTO_NOTICE || sourceKind != AssistantTaskSource.AUTO_NOTICE }) return null
            val existing = sameSource.firstOrNull { !it.completed && !it.suspended } ?: sameSource.firstOrNull { !it.suspended }
            if (existing != null) {
                if (existing.completed) {
                    val updated = current.map {
                        if (it.id == existing.id && sourceRevisionId != null) it.copy(sourceRevisionId = sourceRevisionId) else it
                    }
                    if (updated != current) save(updated)
                    return null
                }
                val updated = existing.copy(
                    text = if (existing.userEdited) existing.text else normalized,
                    sourceRevisionId = sourceRevisionId ?: existing.sourceRevisionId,
                    dueAt = dueAt,
                    remindAt = remindAt,
                    reminderOccurrenceId = remindAt?.let { newOccurrenceId() },
                    reminderOccurrenceAt = remindAt,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                    reminderAttempts = 0,
                    snoozeCount = 0,
                    snoozeMinutes = 0,
                    suspended = false,
                    checklist = mergeChecklist(existing.checklist, checklist),
                )
                if (updated == existing) return null
                save(listOf(updated) + current.filterNot { it.id == existing.id })
                return updated
            }
        }
        check(current.size < MAX_TASKS) { "할 일이 200개 모였어요. 끝낸 일은 완료 또는 제외해주세요." }
        val occurrenceId = remindAt?.let { newOccurrenceId() }
        val task = AssistantTask(
            UUID.randomUUID().toString(),
            normalized,
            false,
            System.currentTimeMillis(),
            sourceNotificationId,
            sourceRevisionId,
            sourceKind,
            dueAt,
            remindAt,
            0,
            false,
            occurrenceId,
            remindAt,
            checklist = checklist.map { TaskChecklistItem(newOccurrenceId(), it.trim().take(MAX_ITEM_TEXT)) }.filter { it.text.isNotEmpty() },
            actionKind = actionKind,
        )
        save(listOf(task) + current)
        return task
    }

    @Synchronized
    fun setCompleted(id: String, completed: Boolean) {
        val before = mutableTasks.value.firstOrNull { it.id == id }
        save(mutableTasks.value.map {
            if (it.id == id && completed) {
                it.copy(
                    completed = true,
                    completedAt = System.currentTimeMillis(),
                    checklistBeforeCompletion = it.checklist.ifEmpty { null } ?: it.checklist,
                    checklist = it.checklist.map { item -> item.copy(done = true) },
                    remindAt = null,
                    reminderOccurrenceId = null,
                    reminderOccurrenceAt = null,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                )
            } else if (it.id == id) {
                it.copy(
                    completed = false,
                    completedAt = null,
                    checklist = it.checklistBeforeCompletion ?: it.checklist,
                    checklistBeforeCompletion = null,
                )
            } else {
                it
            }
        })
        if (completed) {
            if (before != null) TaskReminderScheduler.cancel(app, before) else TaskReminderScheduler.cancel(app, id)
        }
    }

    /** Checking the final checklist item completes the parent; unchecking an item leaves the parent open. */
    @Synchronized
    fun setChecklistItem(id: String, itemId: String, done: Boolean) {
        val before = mutableTasks.value.firstOrNull { it.id == id } ?: return
        if (before.completed || before.suspended || before.excluded) return
        val updatedChecklist = before.checklist.map { if (it.id == itemId) it.copy(done = done) else it }
        if (updatedChecklist == before.checklist) return
        val allDone = updatedChecklist.isNotEmpty() && updatedChecklist.all { it.done }
        val updated = if (allDone) {
            before.copy(
                completed = true,
                completedAt = System.currentTimeMillis(),
                checklistBeforeCompletion = before.checklist,
                checklist = updatedChecklist,
                remindAt = null,
                reminderOccurrenceId = null,
                reminderOccurrenceAt = null,
                activeAlarmOccurrenceId = null,
                activeAlarmScheduledAt = null,
                activeAlarmNotificationId = null,
            )
        } else {
            before.copy(checklist = updatedChecklist)
        }
        save(mutableTasks.value.map { if (it.id == id) updated else it })
        if (allDone) TaskReminderScheduler.cancel(app, before)
    }

    /** Excluded tasks stay out of every list and count; restoring keeps the original reminder slot. */
    @Synchronized
    fun setExcluded(id: String, excluded: Boolean) {
        val before = mutableTasks.value.firstOrNull { it.id == id } ?: return
        if (before.excluded == excluded) return
        save(mutableTasks.value.map {
            if (it.id == id) {
                it.copy(
                    excluded = excluded,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                )
            } else {
                it
            }
        })
        if (excluded) TaskReminderScheduler.cancel(app, before)
    }

    @Synchronized
    fun editTask(id: String, text: String, dueAt: Long?) {
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_TEXT) { "할 일을 1~300자로 적어주세요." }
        require(dueAt == null || dueAt > 0L) { "기한을 다시 확인해주세요." }
        save(mutableTasks.value.map {
            if (it.id == id && !it.completed) it.copy(text = normalized, dueAt = dueAt, userEdited = true) else it
        })
    }

    /** User-driven snooze: parks the task until the chosen time without touching retry counters. */
    @Synchronized
    fun snoozeTask(id: String, remindAt: Long) {
        require(remindAt > System.currentTimeMillis()) { "알림 시간을 다시 확인해주세요." }
        val occurrenceId = newOccurrenceId()
        save(mutableTasks.value.map {
            if (it.id == id && !it.completed && !it.suspended) {
                it.copy(
                    remindAt = remindAt,
                    reminderOccurrenceId = occurrenceId,
                    reminderOccurrenceAt = remindAt,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                    reminderAttempts = 0,
                )
            } else {
                it
            }
        })
    }

    /**
     * Dismisses the currently ringing alarm without completing the task. When the task
     * still has a later reminder slot (e.g. morning-of for a due date), it is re-armed
     * so unfinished work keeps surfacing until it is checked off.
     */
    @Synchronized
    fun dismissAlarmOccurrence(id: String, occurrenceId: String, nextRemindAt: Long? = null): Boolean {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return false
        if (task.completed || task.suspended || task.excluded || task.activeAlarmOccurrenceId != occurrenceId) return false
        val nextOccurrenceId = nextRemindAt?.let { newOccurrenceId() }
        save(mutableTasks.value.map {
            if (it.id == id) {
                it.copy(
                    remindAt = nextRemindAt,
                    reminderOccurrenceId = nextOccurrenceId,
                    reminderOccurrenceAt = nextRemindAt,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                    reminderAttempts = 0,
                )
            } else {
                it
            }
        })
        return true
    }

    /**
     * Applies the current revision's action plans to the automatic tasks of one notice.
     * Each action kind is reconciled in place so a deadline change cancels the old
     * schedule and arms the new one, while completed tasks keep their completion.
     */
    @Synchronized
    fun applyAutomaticPlans(sourceNotificationId: String, sourceRevisionId: String, plans: List<AutoTaskPlan>): Boolean {
        if (!loaded) load()
        val current = mutableTasks.value
        var changed = false
        val next = current.toMutableList()
        val reconciledIds = mutableSetOf<String>()
        plans.forEach { plan ->
            val normalized = plan.text.trim().take(MAX_TEXT)
            if (normalized.isEmpty()) return@forEach
            val existing = next.firstOrNull {
                it.sourceNotificationId == sourceNotificationId && it.actionKind == plan.actionKind &&
                    it.sourceKind == AssistantTaskSource.AUTO_NOTICE && !it.suspended
            } ?: next.firstOrNull {
                it.sourceNotificationId == sourceNotificationId && it.actionKind == plan.actionKind &&
                    it.sourceKind == AssistantTaskSource.AUTO_NOTICE
            } ?: return@forEach run {
                val occurrenceId = plan.remindAt?.let { newOccurrenceId() }
                val created = AssistantTask(
                    UUID.randomUUID().toString(),
                    normalized,
                    false,
                    System.currentTimeMillis(),
                    sourceNotificationId,
                    sourceRevisionId,
                    AssistantTaskSource.AUTO_NOTICE,
                    plan.dueAt,
                    plan.remindAt,
                    reminderOccurrenceId = occurrenceId,
                    reminderOccurrenceAt = plan.remindAt,
                    checklist = plan.checklist.map { TaskChecklistItem(newOccurrenceId(), it.trim().take(MAX_ITEM_TEXT)) }
                        .filter { it.text.isNotEmpty() },
                    actionKind = plan.actionKind,
                )
                next.add(0, created)
                changed = true
            }
            reconciledIds += existing.id
            val updated = if (existing.completed) {
                existing.copy(sourceRevisionId = sourceRevisionId, suspended = false)
            } else {
                val reminderChanged = existing.remindAt != plan.remindAt
                existing.copy(
                    text = if (existing.userEdited) existing.text else normalized,
                    sourceRevisionId = sourceRevisionId,
                    dueAt = plan.dueAt,
                    remindAt = plan.remindAt,
                    reminderOccurrenceId = if (reminderChanged) plan.remindAt?.let { newOccurrenceId() } else existing.reminderOccurrenceId,
                    reminderOccurrenceAt = if (reminderChanged) plan.remindAt else existing.reminderOccurrenceAt,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                    reminderAttempts = 0,
                    suspended = false,
                    checklist = mergeChecklist(existing.checklist, plan.checklist),
                )
            }
            if (updated != existing) {
                next[next.indexOfFirst { it.id == existing.id }] = updated
                changed = true
            }
        }
        // Suspend automatic tasks whose action kind disappeared from the new revision.
        val final = next.map {
            if (it.sourceNotificationId == sourceNotificationId && it.sourceKind == AssistantTaskSource.AUTO_NOTICE &&
                !it.completed && it.id !in reconciledIds && !it.suspended) {
                changed = true
                it.copy(
                    suspended = true,
                    remindAt = null,
                    reminderOccurrenceId = null,
                    reminderOccurrenceAt = null,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                )
            } else {
                it
            }
        }
        if (!changed) return false
        save(final)
        return true
    }

    @Synchronized
    fun delete(id: String) {
        val before = mutableTasks.value.firstOrNull { it.id == id }
        if (before != null) TaskReminderScheduler.cancel(app, before) else TaskReminderScheduler.cancel(app, id)
        save(mutableTasks.value.filterNot { it.id == id })
    }


    @Synchronized
    fun suspendAutomaticSources(sourceNotificationIds: Set<String>): Boolean {
        if (sourceNotificationIds.isEmpty()) return false
        if (!loaded) load()
        val next = suspendAutomaticSources(mutableTasks.value, sourceNotificationIds)
        if (next == mutableTasks.value) return false
        mutableTasks.value.filter { before ->
            before.sourceKind == AssistantTaskSource.AUTO_NOTICE &&
                before.sourceNotificationId in sourceNotificationIds &&
                next.firstOrNull { it.id == before.id }?.remindAt == null
        }.forEach { TaskReminderScheduler.cancel(app, it) }
        save(next)
        return true
    }

    @Synchronized
    fun suspendAutomaticSource(sourceNotificationId: String, sourceRevisionId: String): Boolean {
        val next = reconcileAutomaticRevision(mutableTasks.value, sourceNotificationId, sourceRevisionId)
        if (next == mutableTasks.value) return false
        mutableTasks.value.filter { before ->
            before.sourceNotificationId == sourceNotificationId && before.sourceKind == AssistantTaskSource.AUTO_NOTICE &&
                next.firstOrNull { it.id == before.id }?.remindAt == null
        }.forEach { TaskReminderScheduler.cancel(app, it) }
        save(next)
        return true
    }

    /** Atomically makes an alarm one-shot before its private notification is posted. */
    @Synchronized
    internal fun consumeReminder(id: String, occurrenceId: String? = null, notificationId: Int? = null): AssistantTask? {
        val (next, fired) = consumeReminderFrom(mutableTasks.value, id, occurrenceId, notificationId)
        if (fired != null) save(next)
        return fired
    }

    @Synchronized
    internal fun rescheduleReminder(id: String, remindAt: Long) {
        require(remindAt > System.currentTimeMillis())
        val occurrenceId = newOccurrenceId()
        save(mutableTasks.value.map {
            if (it.id == id && !it.completed && !it.suspended) {
                it.copy(
                    remindAt = remindAt,
                    reminderOccurrenceId = occurrenceId,
                    reminderOccurrenceAt = remindAt,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                    reminderAttempts = (it.reminderAttempts + 1).coerceAtMost(2),
                )
            } else {
                it
            }
        })
    }

    @Synchronized
    fun snoozeAlarmOccurrence(id: String, occurrenceId: String, now: Long = System.currentTimeMillis(), minutes: Int = 10): TaskAlarmSnoozeResult {
        require(minutes in setOf(5, 10, 30))
        val original = mutableTasks.value
        val nextAt = now + minutes * 60_000L
        val nextOccurrenceId = newOccurrenceId()
        val (next, result) = snoozeAlarmOccurrenceFrom(original, id, occurrenceId, nextAt, minutes, nextOccurrenceId)
        if (result !is TaskAlarmSnoozeResult.Scheduled) return result
        save(next, sync = false)
        if (TaskReminderScheduler.trySchedule(app, result.task)) return result
        save(original, sync = false)
        return TaskAlarmSnoozeResult.Failed
    }

    @Synchronized
    fun completeAlarmOccurrence(id: String, occurrenceId: String): Boolean {
        val task = mutableTasks.value.firstOrNull { it.id == id }
        val (next, completed) = completeAlarmOccurrenceFrom(mutableTasks.value, id, occurrenceId)
        if (!completed) return false
        save(next)
        if (task != null) TaskReminderScheduler.cancel(app, task) else TaskReminderScheduler.cancel(app, id)
        return true
    }

    @Synchronized
    internal fun activeAlarmNotificationId(id: String, occurrenceId: String): Int? =
        mutableTasks.value.firstOrNull {
            it.id == id && !it.completed && !it.suspended && it.activeAlarmOccurrenceId == occurrenceId
        }?.activeAlarmNotificationId

    @Synchronized
    internal fun clearActiveAlarmAfterFailedNotification(id: String, occurrenceId: String) {
        val next = mutableTasks.value.map {
            if (it.id == id && it.activeAlarmOccurrenceId == occurrenceId) {
                it.copy(activeAlarmOccurrenceId = null, activeAlarmScheduledAt = null, activeAlarmNotificationId = null)
            } else {
                it
            }
        }
        if (next != mutableTasks.value) save(next)
    }

    private fun save(next: List<AssistantTask>, sync: Boolean = true) {
        requireConsent()
        check(loaded) { "저장된 부탁을 먼저 불러와주세요." }
        val array = JSONArray().apply { next.forEach { task -> put(JSONObject()
            .put("id", task.id).put("text", task.text).put("completed", task.completed).put("createdAt", task.createdAt)
            .put("sourceNotificationId", task.sourceNotificationId ?: JSONObject.NULL)
            .put("sourceRevisionId", task.sourceRevisionId ?: JSONObject.NULL).put("sourceKind", task.sourceKind.name)
            .put("dueAt", task.dueAt ?: JSONObject.NULL).put("remindAt", task.remindAt ?: JSONObject.NULL)
            .put("reminderAttempts", task.reminderAttempts).put("suspended", task.suspended)
            .put("reminderOccurrenceId", task.reminderOccurrenceId ?: JSONObject.NULL)
            .put("reminderOccurrenceAt", task.reminderOccurrenceAt ?: JSONObject.NULL)
            .put("activeAlarmOccurrenceId", task.activeAlarmOccurrenceId ?: JSONObject.NULL)
            .put("activeAlarmScheduledAt", task.activeAlarmScheduledAt ?: JSONObject.NULL)
            .put("activeAlarmNotificationId", task.activeAlarmNotificationId ?: JSONObject.NULL)
            .put("snoozeCount", task.snoozeCount).put("snoozeMinutes", task.snoozeMinutes)
            .put("checklist", encodeChecklist(task.checklist))
            .put("actionKind", task.actionKind ?: JSONObject.NULL)
            .put("excluded", task.excluded)
            .put("completedAt", task.completedAt ?: JSONObject.NULL)
            .put("checklistBeforeCompletion", task.checklistBeforeCompletion?.let { encodeChecklist(it) } ?: JSONObject.NULL)
            .put("userEdited", task.userEdited)) } }
        val ciphertext = Base64.encodeToString(ProbeCrypto().encrypt(array.toString(), "assistant_tasks"), Base64.NO_WRAP)
        if (!preferences.edit().putString("encrypted", ciphertext).commit()) throw IOException("부탁을 저장하지 못했어요. 다시 시도해주세요.")
        mutableTasks.value = next
        if (sync) TaskReminderScheduler.sync(app, next)
    }

    @Synchronized
    private fun clear() {
        // Close mutations before reset; callers must first close the main repository consent gate.
        loaded = false
        mutableTasks.value = emptyList()
        if (!preferences.edit().clear().commit()) throw IOException("부탁 기록을 삭제하지 못했어요.")
        TaskReminderScheduler.sync(app, emptyList())
    }

    companion object {
        const val MAX_TEXT = 300
        const val MAX_ITEM_TEXT = 80
        private const val MAX_TASKS = 200
        internal const val MAX_SNOOZES = 3
        internal const val MAX_SNOOZE_MINUTES = 60

        /** Keeps done state of items that survive a revision; matches by normalized text. */
        internal fun mergeChecklist(existing: List<TaskChecklistItem>, revised: List<String>): List<TaskChecklistItem> {
            if (revised.isEmpty()) return existing
            val remaining = existing.toMutableList()
            return revised.map { text ->
                val normalized = text.trim().take(MAX_ITEM_TEXT)
                if (normalized.isEmpty()) return@map null
                val match = remaining.indexOfFirst { it.text.equals(normalized, ignoreCase = true) }
                if (match >= 0) remaining.removeAt(match) else TaskChecklistItem(newOccurrenceId(), normalized)
            }.filterNotNull()
        }

        internal fun decodeChecklist(array: JSONArray?): List<TaskChecklistItem> {
            if (array == null) return emptyList()
            return List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@List null
                val text = item.optString("text").trim().take(MAX_ITEM_TEXT)
                if (text.isEmpty()) return@List null
                TaskChecklistItem(
                    item.optString("id").ifBlank { newOccurrenceId() },
                    text,
                    item.optBoolean("done", false),
                )
            }.filterNotNull()
        }

        private fun encodeChecklist(items: List<TaskChecklistItem>): JSONArray = JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().put("id", item.id).put("text", item.text).put("done", item.done))
            }
        }
        internal fun newOccurrenceId(): String = UUID.randomUUID().toString()
        internal fun expectedOccurrenceId(task: AssistantTask): String? =
            task.reminderOccurrenceId ?: task.remindAt?.let { "legacy:${task.id}:$it" }

        internal fun consumeReminderFrom(
            tasks: List<AssistantTask>,
            id: String,
            occurrenceId: String? = null,
            notificationId: Int? = null,
        ): Pair<List<AssistantTask>, AssistantTask?> {
            val fired = tasks.firstOrNull { it.id == id && !it.completed && !it.suspended && !it.excluded && it.remindAt != null } ?: return tasks to null
            val expected = expectedOccurrenceId(fired)
            if (occurrenceId != null && occurrenceId != expected) return tasks to null
            if (occurrenceId == null && notificationId == null) {
                val consumed = fired.copy(remindAt = null, reminderOccurrenceId = null, reminderOccurrenceAt = null)
                return tasks.map { if (it.id == id) consumed else it } to fired
            }
            val active = fired.copy(
                remindAt = null,
                reminderOccurrenceId = null,
                reminderOccurrenceAt = null,
                activeAlarmOccurrenceId = occurrenceId ?: expected,
                activeAlarmScheduledAt = fired.reminderOccurrenceAt ?: fired.remindAt,
                activeAlarmNotificationId = notificationId,
            )
            return tasks.map { if (it.id == id) active else it } to active
        }

        internal fun snoozeAlarmOccurrenceFrom(
            tasks: List<AssistantTask>,
            id: String,
            occurrenceId: String,
            nextAt: Long,
            minutes: Int,
            nextOccurrenceId: String,
        ): Pair<List<AssistantTask>, TaskAlarmSnoozeResult> {
            val task = tasks.firstOrNull {
                it.id == id && !it.completed && !it.suspended && !it.excluded && it.activeAlarmOccurrenceId == occurrenceId
            } ?: return tasks to TaskAlarmSnoozeResult.Stale
            if (task.snoozeCount >= MAX_SNOOZES || task.snoozeMinutes + minutes > MAX_SNOOZE_MINUTES) {
                return tasks to TaskAlarmSnoozeResult.LimitReached
            }
            val snoozed = task.copy(
                remindAt = nextAt,
                reminderOccurrenceId = nextOccurrenceId,
                reminderOccurrenceAt = nextAt,
                activeAlarmOccurrenceId = null,
                activeAlarmScheduledAt = null,
                activeAlarmNotificationId = null,
                reminderAttempts = 0,
                snoozeCount = task.snoozeCount + 1,
                snoozeMinutes = task.snoozeMinutes + minutes,
            )
            return tasks.map { if (it.id == id) snoozed else it } to TaskAlarmSnoozeResult.Scheduled(snoozed, nextAt)
        }

        internal fun completeAlarmOccurrenceFrom(tasks: List<AssistantTask>, id: String, occurrenceId: String): Pair<List<AssistantTask>, Boolean> {
            val task = tasks.firstOrNull {
                it.id == id && !it.completed && !it.suspended && !it.excluded && it.activeAlarmOccurrenceId == occurrenceId
            } ?: return tasks to false
            val completed = task.copy(
                completed = true,
                completedAt = System.currentTimeMillis(),
                checklistBeforeCompletion = task.checklist.ifEmpty { null } ?: task.checklist,
                checklist = task.checklist.map { it.copy(done = true) },
                remindAt = null,
                reminderOccurrenceId = null,
                reminderOccurrenceAt = null,
                activeAlarmOccurrenceId = null,
                activeAlarmScheduledAt = null,
                activeAlarmNotificationId = null,
            )
            return tasks.map { if (it.id == id) completed else it } to true
        }
        @Volatile private var instance: AssistantTaskStore? = null
        fun get(context: Context): AssistantTaskStore = instance ?: synchronized(this) {
            instance ?: AssistantTaskStore(context).also { instance = it }
        }
        fun reset(context: Context) = get(context).clear()
        internal fun reconcileAutomaticRevisionForTest(
            tasks: List<AssistantTask>,
            sourceNotificationId: String,
            sourceRevisionId: String,
        ): List<AssistantTask> = reconcileAutomaticRevision(tasks, sourceNotificationId, sourceRevisionId)

        internal fun suspendAutomaticSourcesForTest(
            tasks: List<AssistantTask>,
            sourceNotificationIds: Set<String>,
        ): List<AssistantTask> = suspendAutomaticSources(tasks, sourceNotificationIds)

        private fun suspendAutomaticSources(
            tasks: List<AssistantTask>,
            sourceNotificationIds: Set<String>,
        ): List<AssistantTask> = tasks.map { task ->
            if (task.sourceKind == AssistantTaskSource.AUTO_NOTICE &&
                task.sourceNotificationId in sourceNotificationIds &&
                !task.completed && !task.suspended
            ) {
                task.copy(
                    suspended = true,
                    remindAt = null,
                    reminderOccurrenceId = null,
                    reminderOccurrenceAt = null,
                    activeAlarmOccurrenceId = null,
                    activeAlarmScheduledAt = null,
                    activeAlarmNotificationId = null,
                )
            } else {
                task
            }
        }

        private fun reconcileAutomaticRevision(
            tasks: List<AssistantTask>,
            sourceNotificationId: String,
            sourceRevisionId: String,
        ): List<AssistantTask> = tasks.map {
            if (it.sourceNotificationId == sourceNotificationId && it.sourceKind == AssistantTaskSource.AUTO_NOTICE &&
                !it.completed && (it.sourceRevisionId == null || it.sourceRevisionId != sourceRevisionId || !it.suspended)
            ) it.copy(
                sourceRevisionId = sourceRevisionId,
                remindAt = null,
                reminderOccurrenceId = null,
                reminderOccurrenceAt = null,
                activeAlarmOccurrenceId = null,
                activeAlarmScheduledAt = null,
                activeAlarmNotificationId = null,
                reminderAttempts = 0,
                suspended = true,
            ) else it
        }
    }
}

private fun JSONObject.optionalLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

private fun JSONObject.optionalInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

internal fun JSONObject.optionalId(name: String): String? =
    if (!has(name) || isNull(name)) {
        null
    } else {
        optString(name).trim().takeUnless { it.isEmpty() || it == "null" }
    }
