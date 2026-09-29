package kr.mom.probe.voice

import android.content.Context
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CalendarCreateCommand
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause
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
        val key = "voice:$captureId:${clause.index}"
        store.tasks.value.firstOrNull { it.sourceNotificationId == key }?.let { return it.id }
        val text = clause.action ?: clause.plan.reply.proposedTask ?: clause.transcript
        taskStoreAdd(store, key, text, clause)
        // addTask returns null when a retry hits the dedup branch — the
        // already-written task is still the durable outcome.
        return store.tasks.value.firstOrNull { it.sourceNotificationId == key }?.id
    }

    private fun taskStoreAdd(
        store: AssistantTaskStore,
        key: String,
        text: String,
        clause: CaptureClause,
    ) {
        try {
            store.addTask(
                text.ifBlank { clause.transcript },
                sourceNotificationId = key,
                dueAt = clause.dueAt ?: clause.plan.reply.proposedDueAt,
                remindAt = clause.remindAt ?: clause.plan.reply.proposedRemindAt,
                sourceKind = kr.mom.probe.task.AssistantTaskSource.USER_LOCAL,
                actionKind = "voice",
                evidenceText = "음성 입력: ${clause.transcript.take(300)}",
            )
        } catch (error: Exception) {
            throw CaptureWriteException(
                error.message ?: "부탁으로 저장하지 못했어요.", retryable = true,
            )
        }
    }

    private fun writeCalendar(captureId: String, clause: CaptureClause): String? {
        val command = clause.plan.reply.scheduleCommand as? CalendarCreateCommand
            ?: throw CaptureWriteException("일정 내용을 다시 확인해 주세요.", retryable = false)
        val result = CalendarGateway(app).saveEvent(
            "$captureId:${clause.index}", clause.transcript, command.payload,
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
