package kr.mom.probe.voice

import android.content.Context
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CalendarCreateCommand
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause
import kr.mom.probe.agent.SchedulePayload
import kr.mom.probe.calendar.CalendarGateway
import kr.mom.probe.calendar.CalendarSaveState
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot write orchestration for a confirmed capture batch. Owns no
 * parsing — the clauses come from LocalAgentEngine plus the parent's
 * preview edits; this only performs the writes the parent already saw
 * and confirmed.
 *
 * Durability contract: every durable step is journaled in
 * [VoiceCaptureStore] before the next begins. captureId + clause index is
 * the stable idempotency key on the actual task/calendar write — a retry
 * after a mid-batch failure resumes the journal and never writes a clause
 * twice. Any persistence failure returns [VoiceSaveResult.Failed] instead
 * of being swallowed, so the preview stays retryable until every required
 * write has committed.
 */
sealed interface VoiceSaveResult {
    object Saved : VoiceSaveResult
    /** `retryable` false means retrying cannot fix it (e.g. setup missing). */
    data class Failed(val message: String, val retryable: Boolean = true) : VoiceSaveResult
}

interface VoiceCaptureSaver {
    suspend fun save(batch: CaptureBatch, transcript: String): VoiceSaveResult
}

class LocalVoiceCaptureSaver(
    context: Context,
    private val journals: CaptureJournalSink? = null,
) : VoiceCaptureSaver {
    private val app = context.applicationContext

    private fun journalSink(): CaptureJournalSink =
        journals ?: VoiceCaptureStore.get(app)

    override suspend fun save(
        batch: CaptureBatch,
        transcript: String,
    ): VoiceSaveResult = withContext(Dispatchers.IO) {
        val store = AssistantTaskStore.get(app)
        try {
            store.load()
        } catch (error: Exception) {
            return@withContext VoiceSaveResult.Failed(
                error.message ?: "앱에서 처음 설정을 마쳐주세요.", retryable = false,
            )
        }
        val coordinator = CaptureWriteCoordinator(
            journals = journalSink(),
            taskWriter = CaptureClauseWriter { captureId, clause ->
                writeTask(store, captureId, clause)
            },
            calendarWriter = CaptureClauseWriter { captureId, clause ->
                writeCalendar(captureId, clause)
            },
            recordWriter = { record ->
                if (!VoiceCaptureStore.get(app).save(record)) {
                    throw CaptureWriteException("발화 기록을 저장하지 못했어요.", retryable = true)
                }
            },
        )
        try {
            coordinator.commit(batch, transcript)
            VoiceSaveResult.Saved
        } catch (error: CaptureWriteException) {
            VoiceSaveResult.Failed(error.message ?: "저장하지 못했어요.", error.retryable)
        } catch (error: Exception) {
            VoiceSaveResult.Failed(error.message ?: "저장하지 못했어요. 다시 시도해 주세요.", retryable = true)
        }
    }

    /**
     * Task write keyed by `voice:<captureId>:<clause>` in
     * sourceNotificationId — durable across process restarts, and the
     * store's same-source dedup makes the write idempotent even if the
     * journal mark never landed.
     */
    private fun writeTask(store: AssistantTaskStore, captureId: String, clause: CaptureClause): String? {
        // Defense in depth — the coordinator never routes MEMO here, and a
        // memo must never become a task row.
        require(clause.intent != AgentIntent.MEMO) { "메모는 할 일로 저장하지 않아요." }
        val key = "voice:$captureId:${clause.index}"
        store.tasks.value.firstOrNull { it.sourceNotificationId == key }?.let { return it.id }
        val spec = taskWriteSpecFor(clause)
        taskStoreAdd(store, key, spec)
        // addTask returns null when a retry hits the dedup branch — the
        // already-written task is still the durable outcome.
        return store.tasks.value.firstOrNull { it.sourceNotificationId == key }?.id
    }

    private fun taskStoreAdd(
        store: AssistantTaskStore,
        key: String,
        spec: TaskWriteSpec,
    ) {
        try {
            store.addTask(
                spec.text,
                sourceNotificationId = key,
                dueAt = spec.dueAt,
                remindAt = spec.remindAt,
                sourceKind = kr.mom.probe.task.AssistantTaskSource.USER_LOCAL,
                actionKind = "voice",
                evidenceText = spec.evidenceText,
            )
        } catch (error: Exception) {
            throw CaptureWriteException(
                error.message ?: "부탁으로 저장하지 못했어요.", retryable = true,
            )
        }
    }

    private fun writeCalendar(captureId: String, clause: CaptureClause): String? {
        val payload = calendarPayloadFor(clause)
            ?: throw CaptureWriteException("일정 내용을 다시 확인해 주세요.", retryable = false)
        val result = CalendarGateway(app).saveEvent(
            "$captureId:${clause.index}", clause.transcript, payload,
        )
        return when (result.state) {
            CalendarSaveState.SAVED -> result.record?.eventId?.toString()
            CalendarSaveState.NEEDS_PERMISSION,
            CalendarSaveState.NEEDS_DESTINATION,
            CalendarSaveState.STALE_DESTINATION,
            -> throw CaptureWriteException(result.message, retryable = false)
            CalendarSaveState.UNCERTAIN,
            CalendarSaveState.FAILED,
            -> throw CaptureWriteException(result.message, retryable = true)
        }
    }
}

/**
 * The task values the parent actually confirmed — tri-state date edit:
 * untouched follows the parsed proposal, an explicit blank clears BOTH
 * due and remind (a silent leftover alarm would violate what the parent
 * saw), a parsed edit wins. Pure and testable.
 */
internal data class TaskWriteSpec(
    val text: String,
    val dueAt: Long?,
    val remindAt: Long?,
    val evidenceText: String,
)

internal fun taskWriteSpecFor(clause: CaptureClause): TaskWriteSpec {
    val cleared = clause.dateInput?.isBlank() == true
    val text = (clause.action ?: clause.plan.reply.proposedTask ?: clause.transcript)
        .ifBlank { clause.transcript }
    return TaskWriteSpec(
        text = text,
        dueAt = if (cleared) null else clause.effectiveDueAt,
        remindAt = if (cleared) null else clause.remindAt ?: clause.plan.reply.proposedRemindAt,
        evidenceText = "음성 입력: ${clause.transcript.take(300)}",
    )
}

/**
 * The payload the parent actually confirmed: clause edits win over the
 * engine's proposal — action becomes the event title, dueAt becomes the
 * start (duration preserved), zone/end semantics come from the parse.
 * An explicitly cleared start returns null so the write fails closed —
 * the old parsed start is never silently reused.
 * Pure and testable; returns null when the clause has no calendar command
 * or no usable start.
 */
internal fun calendarPayloadFor(clause: CaptureClause): SchedulePayload? {
    val command = clause.plan.reply.scheduleCommand as? CalendarCreateCommand ?: return null
    val base = command.payload
    val title = (clause.action?.ifBlank { null } ?: clause.plan.reply.proposedTask
        ?: base.title).take(120)
    val start = when {
        clause.dateInput != null -> clause.dueAt ?: return null
        else -> clause.dueAt ?: base.startMillis
    }
    val duration = (base.endMillis - base.startMillis).coerceAtLeast(0L)
    return base.copy(
        title = title.ifBlank { base.title },
        startMillis = start,
        endMillis = start + duration,
    )
}
