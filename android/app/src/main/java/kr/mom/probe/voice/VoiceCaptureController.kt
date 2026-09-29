package kr.mom.probe.voice

import android.speech.SpeechRecognizer
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause
import kr.mom.probe.agent.CaptureDisposition
import kr.mom.probe.agent.CaptureLabels
import kr.mom.probe.agent.CapturePlan
import kr.mom.probe.agent.LocalAgentReply

/**
 * Tap-driven voice capture state machine.
 *
 * The only entry point is [startCapture], invoked after the user taps the
 * mic and runtime permission is granted. There is no path that starts
 * listening on creation, resume, or recreation — and no hotword,
 * background listening, or audio-file persistence anywhere.
 *
 * DONE holds a classified [CapturePlan] as a memory-only preview. Writes
 * happen only through [requestSave] → the caller performs the save and
 * reports back via [finishSave]; a failed save returns to DONE with the
 * transcript intact and a retry path. [discard] is the explicit
 * 내려놓기 path — nothing is ever written for it.
 *
 * Lifecycle contract: the owning Activity calls [release] exactly once
 * from onDestroy. Every recognizer adapter is destroyed at most once;
 * callbacks from a torn-down or superseded session are ignored.
 */
enum class VoiceCaptureState { IDLE, LISTENING, THINKING, DONE, ERROR, SAVING, SAVED }

class VoiceCaptureController(
    factoryProvider: () -> SpeechRecognizerFactory,
) {
    /**
     * Resolved on the first capture so tests can install a fake factory
     * even when the owning Activity was created before the test body ran
     * (ActivityScenarioRule launches during the rule's before()).
     */
    private val factory: SpeechRecognizerFactory by lazy(factoryProvider)

    var state: VoiceCaptureState = VoiceCaptureState.IDLE
        private set
    var transcript: String = ""
        private set
    var errorText: String? = null
        private set
    /** False after a non-retryable save failure — the UI must not offer the same save again. */
    var saveRetryable: Boolean = true
        private set
    /**
     * Ordered editable clause batch produced when the final transcript
     * arrives. Writes happen only through [requestSave] and only when
     * every kept clause is resolved.
     */
    var batch: CaptureBatch? = null
        private set

    /**
     * True when the classifier itself threw — an explicit non-saveable
     * review state, never silently collapsed into a memo offer. The parent
     * may retry classification or explicitly choose 메모로 저장.
     */
    var classificationFailed: Boolean = false
        private set

    /** True while the generic system RecognitionService needs explicit consent. */
    var needsFallbackConsent: Boolean = false
        private set

    /**
     * Classification seam — set by the owning component to the single
     * LocalAgentEngine.captureBatch call. Returning null (or throwing)
     * leaves the transcript visible in the explicit review state.
     */
    var classifier: (String) -> CaptureBatch? = { null }

    /**
     * Idempotency key for the capture in flight — the owner assigns it per
     * mic tap and the saver writes it into durable stores. A batch produced
     * without a classifier (e.g. explicit memo conversion) falls back to it.
     */
    var captureId: String = ""

    private var adapter: RecognizerAdapter? = null
    private var session = 0
    private var released = false
    /** One in-flight or completed write per capture — duplicate-submit guard. */
    private var saveClaimed = false
    var onChanged: () -> Unit = {}

    /**
     * Mic-tap entry. On-device recognition is tried first; if it is
     * unavailable or fails to create, we surface the fallback disclosure
     * instead of silently creating the generic recognizer.
     */
    fun startCapture() {
        if (released || adapter != null || state == VoiceCaptureState.SAVING) return
        transcript = ""
        errorText = null
        batch = null
        classificationFailed = false
        saveClaimed = false
        needsFallbackConsent = false
        if (!factory.isRecognitionAvailable()) {
            fail("이 기기에서는 음성 인식을 지원하지 않아요.")
            return
        }
        if (factory.isOnDeviceAvailable()) {
            try {
                begin(factory.createOnDevice())
                return
            } catch (t: Throwable) {
                // Fall through to the disclosed generic path.
            }
        }
        needsFallbackConsent = true
        onChanged()
    }

    /**
     * Second tap while listening ends capture early. The state moves to
     * THINKING immediately — a recognizer that never calls back must not
     * leave the session stuck in LISTENING.
     */
    fun stopCapture() {
        val a = adapter ?: return
        if (state != VoiceCaptureState.LISTENING) return
        try {
            a.stop()
            // stop() may have synchronously delivered a final result or
            // error — those paths already tore the session down and moved
            // to DONE/ERROR. Only an untouched live session needs the
            // explicit THINKING marker so it cannot stall in LISTENING.
            if (adapter === a && state == VoiceCaptureState.LISTENING) {
                state = VoiceCaptureState.THINKING
                onChanged()
            }
        } catch (t: Throwable) {
            teardown(a)
            fail("음성 인식이 중단됐어요. 다시 시도해 주세요.")
        }
    }

    /** Disclosure result: only Continue may create the generic recognizer. */
    fun onFallbackConsent(accepted: Boolean) {
        if (released || !needsFallbackConsent) return
        needsFallbackConsent = false
        if (!accepted) {
            onChanged()
            return
        }
        try {
            begin(factory.createGeneric())
        } catch (t: Throwable) {
            fail("음성 인식을 시작하지 못했어요. 다시 시도해 주세요.")
        }
    }

    // --- Editable clause preview -------------------------------------

    private fun editClause(index: Int, transform: (CaptureClause) -> CaptureClause) {
        if (state != VoiceCaptureState.DONE) return
        val current = batch ?: return
        batch = current.updateClause(index) { transform(it).copy(editedByUser = true) }
        onChanged()
    }

    /** Parent rewrites the clause text — used verbatim as the memo/task source. */
    fun setClauseTranscript(index: Int, text: String) =
        editClause(index) { it.copy(transcript = text.take(MAX_CLAUSE_TEXT)) }

    /** Parent rewrites the stored action text for this clause. */
    fun setClauseAction(index: Int, action: String) =
        editClause(index) { it.copy(action = action.take(MAX_CLAUSE_TEXT).ifBlank { null }) }

    /** Parent adjusts the parsed due/remind times (null clears them). */
    fun setClauseTimes(index: Int, dueAt: Long?, remindAt: Long?) =
        editClause(index) { it.copy(dueAt = dueAt, remindAt = remindAt, dateParseFailed = false) }

    /**
     * Parent edits the due date/time as text — ISO, `M월 d일`, or
     * 오늘/내일/모레 + optional 시/분. Blank clears the date; unparseable
     * input flags the clause so it cannot be written until fixed.
     */
    fun setClauseDate(index: Int, rawInput: String) =
        editClause(index) { clause ->
            val raw = rawInput.trim()
            val parsed = if (raw.isEmpty()) null else
                kr.mom.probe.agent.LocalAgentEngine.parseEditableDateTime(raw)
            if (raw.isNotEmpty() && parsed == null) {
                clause.copy(dateInput = rawInput, dateParseFailed = true)
            } else {
                clause.copy(dueAt = parsed, dateInput = rawInput, dateParseFailed = false)
            }
        }

    /**
     * Explicit per-clause resolution — the only way a NEEDS_CONFIRM or
     * non-saveable clause becomes writable. Choosing a real disposition
     * marks the clause resolved; dropping removes it from the batch.
     */
    fun resolveClause(index: Int, disposition: CaptureDisposition) {
        if (disposition == CaptureDisposition.NEEDS_CONFIRM) return
        editClause(index) { clause ->
            val intent = when (disposition) {
                CaptureDisposition.MEMO_ONLY -> AgentIntent.MEMO
                else -> if (clause.intent == AgentIntent.QUESTION) AgentIntent.TASK else clause.intent
            }
            clause.copy(
                dispositionOverride = disposition,
                intentOverride = intent,
                saveableOverride = true,
                resolved = true,
            )
        }
    }

    /** Drop/keep control — a dropped clause is never written. */
    fun dropClause(index: Int) = editClause(index) { it.copy(dropped = true) }
    fun keepClause(index: Int) = editClause(index) { it.copy(dropped = false) }

    /**
     * Re-runs the classifier on the kept transcript after a classification
     * failure — the explicit retry path out of the review state.
     */
    fun retryClassification() {
        if (state != VoiceCaptureState.DONE || !classificationFailed) return
        classifyNow()
    }

    private fun classifyNow() {
        val result = runCatching { classifier(transcript) }
        result.onSuccess { value ->
            classificationFailed = value == null
            batch = value
        }.onFailure {
            classificationFailed = true
            batch = null
        }
        onChanged()
    }

    /**
     * Explicit save request from the preview card. Fires at most once per
     * capture — the caller reports the outcome through [finishSave].
     */
    fun requestSave(): Boolean {
        if (released || state != VoiceCaptureState.DONE || saveClaimed) return false
        if (batch?.saveable != true) return false
        saveClaimed = true
        saveRetryable = true
        errorText = null
        state = VoiceCaptureState.SAVING
        onChanged()
        return true
    }

    /**
     * Caller reports the write result; failure keeps the transcript for
     * retry. `retryable` is preserved to the UI: a retryable failure offers
     * the same save again, a non-retryable one (setup, permission,
     * destination) hides the save button so no false retry is promised.
     */
    fun finishSave(success: Boolean, message: String? = null, retryable: Boolean = true) {
        if (state != VoiceCaptureState.SAVING) return
        if (success) {
            state = VoiceCaptureState.SAVED
            errorText = null
            saveRetryable = true
        } else {
            saveClaimed = false
            saveRetryable = retryable
            state = VoiceCaptureState.DONE
            errorText = message ?: "저장하지 못했어요. 다시 시도해 주세요."
        }
        onChanged()
    }

    /**
     * Lets the parent explicitly keep an unclassifiable capture as a memo —
     * still user-initiated, and now the explicit choice offered alongside
     * the classification-failure retry state.
     */
    fun convertToMemo() {
        if (state != VoiceCaptureState.DONE) return
        if (batch?.saveable == true) return
        val memoPlan = CapturePlan(
            transcript = transcript,
            reply = LocalAgentReply(
                message = "",
                proposedTask = transcript,
                intent = AgentIntent.MEMO,
            ),
            intent = AgentIntent.MEMO,
            disposition = CaptureDisposition.MEMO_ONLY,
            labels = listOf(CaptureLabels.USER_SPOKE, AgentIntent.MEMO.label),
            saveable = true,
        )
        batch = CaptureBatch(
            captureId = batch?.captureId ?: captureId,
            clauses = listOf(CaptureClause(0, transcript, memoPlan, resolved = true)),
        )
        classificationFailed = false
        saveRetryable = true
        onChanged()
    }

    /** Explicit 내려놓기 — the preview is dropped and nothing is written. */
    fun discard() {
        if (released) return
        transcript = ""
        batch = null
        classificationFailed = false
        errorText = null
        saveClaimed = false
        saveRetryable = true
        needsFallbackConsent = false
        state = VoiceCaptureState.IDLE
        onChanged()
    }

    /** Cancel/timeout from the user (e.g. 화면 닫기 중 청취 취소). */
    fun cancelCapture() {
        val a = adapter ?: return
        adapter = null
        try { a.cancel() } catch (t: Throwable) { }
        try { a.destroy() } catch (t: Throwable) { }
        state = VoiceCaptureState.IDLE
        transcript = ""
        onChanged()
    }

    /** Called exactly once from Activity.onDestroy. Idempotent. */
    fun release() {
        if (released) return
        released = true
        val a = adapter
        adapter = null
        if (a != null) {
            try { a.cancel() } catch (t: Throwable) { }
            try { a.destroy() } catch (t: Throwable) { }
        }
    }

    private fun begin(a: RecognizerAdapter) {
        val active = ++session
        adapter = a
        try {
            a.setCallback(object : VoiceRecognizerCallback {
                private fun live() = !released && session == active && adapter === a

                override fun onReady() {
                    if (live()) { state = VoiceCaptureState.LISTENING; onChanged() }
                }
                override fun onSpeechEnded() {
                    if (live()) { state = VoiceCaptureState.THINKING; onChanged() }
                }
                override fun onPartial(text: String) {
                    if (live()) { transcript = text; onChanged() }
                }
                override fun onResult(text: String) {
                    if (!live()) return
                    if (text.isNotBlank()) transcript = text
                    teardown(a)
                    if (transcript.isBlank()) {
                        fail("알아듣지 못했어요. 다시 말해주세요.")
                    } else {
                        classifyNow()
                        state = VoiceCaptureState.DONE
                        onChanged()
                    }
                }
                override fun onError(error: Int) {
                    if (!live()) return
                    teardown(a)
                    fail(
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ) "알아듣지 못했어요. 다시 말해주세요."
                        else "음성 인식이 중단됐어요. 다시 시도해 주세요.",
                    )
                }
            })
            a.start()
            // A callback may have already run (or torn the session down)
            // inside start() — only mark LISTENING if it is still ours.
            if (adapter === a) {
                state = VoiceCaptureState.LISTENING
                onChanged()
            }
        } catch (t: Throwable) {
            // Restore IDLE so a failed start leaves a clean retry path —
            // callers decide between disclosure and an error state.
            teardown(a)
            state = VoiceCaptureState.IDLE
            throw t
        }
    }

    private fun teardown(a: RecognizerAdapter) {
        if (adapter === a) adapter = null
        try { a.destroy() } catch (t: Throwable) { }
    }

    private fun fail(message: String) {
        state = VoiceCaptureState.ERROR
        errorText = message
        onChanged()
    }

    companion object {
        private const val MAX_CLAUSE_TEXT = 300
    }
}
