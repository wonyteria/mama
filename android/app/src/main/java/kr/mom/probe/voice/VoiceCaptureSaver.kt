package kr.mom.probe.voice

import android.content.Context
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CalendarCreateCommand
import kr.mom.probe.agent.CapturePlan
import kr.mom.probe.calendar.CalendarGateway
import kr.mom.probe.calendar.CalendarSaveState
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot write orchestration for a confirmed capture. Owns no parsing —
 * the plan comes from LocalAgentEngine; this only performs the write the
 * parent already saw and confirmed.
 */
sealed interface VoiceSaveResult {
    object Saved : VoiceSaveResult
    /** `retryable` false means retrying cannot fix it (e.g. setup missing). */
    data class Failed(val message: String, val retryable: Boolean = true) : VoiceSaveResult
}

interface VoiceCaptureSaver {
    suspend fun save(plan: CapturePlan, transcript: String, captureId: String): VoiceSaveResult
}

class LocalVoiceCaptureSaver(context: Context) : VoiceCaptureSaver {
    private val app = context.applicationContext

    override suspend fun save(
        plan: CapturePlan,
        transcript: String,
        captureId: String,
    ): VoiceSaveResult = withContext(Dispatchers.IO) {
        when (plan.intent) {
            AgentIntent.CALENDAR -> saveCalendar(plan, transcript, captureId)
            else -> saveTask(plan, transcript, captureId)
        }
    }

    private fun saveCalendar(plan: CapturePlan, transcript: String, captureId: String): VoiceSaveResult {
        val command = plan.reply.scheduleCommand as? CalendarCreateCommand
            ?: return VoiceSaveResult.Failed("일정 내용을 다시 확인해 주세요.", retryable = false)
        val result = CalendarGateway(app).saveEvent(captureId, transcript, command.payload)
        return when (result.state) {
            CalendarSaveState.SAVED -> {
                runCatching {
                    VoiceCaptureStore.get(app).save(
                        VoiceCaptureRecord(captureId, transcript, plan.intent.name, System.currentTimeMillis()),
                    )
                }
                VoiceSaveResult.Saved
            }
            CalendarSaveState.NEEDS_PERMISSION,
            CalendarSaveState.NEEDS_DESTINATION,
            CalendarSaveState.STALE_DESTINATION,
            -> VoiceSaveResult.Failed(result.message, retryable = false)
            CalendarSaveState.UNCERTAIN,
            CalendarSaveState.FAILED,
            -> VoiceSaveResult.Failed(result.message, retryable = true)
        }
    }

    private fun saveTask(plan: CapturePlan, transcript: String, captureId: String): VoiceSaveResult {
        val store = AssistantTaskStore.get(app)
        try {
            store.load()
        } catch (error: Exception) {
            return VoiceSaveResult.Failed(
                error.message ?: "앱에서 처음 설정을 마쳐주세요.", retryable = false,
            )
        }
        val task = runCatching {
            store.addTask(
                plan.reply.proposedTask ?: transcript,
                dueAt = plan.reply.proposedDueAt,
                remindAt = plan.reply.proposedRemindAt,
                sourceKind = kr.mom.probe.task.AssistantTaskSource.USER_LOCAL,
                evidenceText = "음성 입력: ${transcript.take(300)}",
            )
        }.getOrElse { error ->
            return VoiceSaveResult.Failed(
                error.message ?: "부탁으로 저장하지 못했어요.", retryable = true,
            )
        }
        runCatching {
            VoiceCaptureStore.get(app).save(
                VoiceCaptureRecord(
                    id = captureId,
                    rawTranscript = transcript,
                    intentType = plan.intent.name,
                    createdAt = System.currentTimeMillis(),
                    linkedTaskId = task?.id,
                ),
            )
        }
        return VoiceSaveResult.Saved
    }
}
