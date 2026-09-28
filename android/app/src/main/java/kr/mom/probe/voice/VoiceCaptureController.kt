package kr.mom.probe.voice

import android.speech.SpeechRecognizer
import kr.mom.probe.agent.CapturePlan

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
    /** Classified preview produced when the final transcript arrives. */
    var plan: CapturePlan? = null
        private set

    /** True while the generic system RecognitionService needs explicit consent. */
    var needsFallbackConsent: Boolean = false
        private set

    /**
     * Classification seam — set by the owning component to the single
     * LocalAgentEngine.capture call. Returning null leaves the transcript
     * visible without an interpretation.
     */
    var classifier: (String) -> CapturePlan? = { null }

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
        plan = null
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
            state = VoiceCaptureState.THINKING
            onChanged()
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

    /**
     * Explicit save request from the preview card. Fires at most once per
     * capture — the caller reports the outcome through [finishSave].
     */
    fun requestSave(): Boolean {
        if (released || state != VoiceCaptureState.DONE || saveClaimed) return false
        if (plan?.saveable != true) return false
        saveClaimed = true
        errorText = null
        state = VoiceCaptureState.SAVING
        onChanged()
        return true
    }

    /** Caller reports the write result; failure keeps the transcript for retry. */
    fun finishSave(success: Boolean, message: String? = null) {
        if (state != VoiceCaptureState.SAVING) return
        if (success) {
            state = VoiceCaptureState.SAVED
            errorText = null
        } else {
            saveClaimed = false
            state = VoiceCaptureState.DONE
            errorText = message ?: "저장하지 못했어요. 다시 시도해 주세요."
        }
        onChanged()
    }

    /**
     * Lets the parent explicitly keep an unclassifiable capture as a memo —
     * the only write path out of NEEDS_CONFIRM, and still user-initiated.
     */
    fun convertToMemo() {
        if (state != VoiceCaptureState.DONE) return
        val current = plan
        if (current?.saveable == true) return
        val base = current ?: CapturePlan(
            transcript = transcript,
            reply = kr.mom.probe.agent.LocalAgentReply(""),
            intent = kr.mom.probe.agent.AgentIntent.MEMO,
            disposition = null,
            labels = emptyList(),
            saveable = false,
        )
        plan = base.copy(
            intent = kr.mom.probe.agent.AgentIntent.MEMO,
            disposition = kr.mom.probe.agent.CaptureDisposition.MEMO_ONLY,
            reply = base.reply.copy(
                proposedTask = base.transcript.ifBlank { transcript },
                proposedDueAt = null,
                proposedRemindAt = null,
                scheduleCommand = null,
                intent = kr.mom.probe.agent.AgentIntent.MEMO,
            ),
            labels = (base.labels - kr.mom.probe.agent.CaptureLabels.NO_SAVE) +
                kr.mom.probe.agent.AgentIntent.MEMO.label,
            saveable = true,
        )
        onChanged()
    }

    /** Explicit 내려놓기 — the preview is dropped and nothing is written. */
    fun discard() {
        if (released) return
        transcript = ""
        plan = null
        errorText = null
        saveClaimed = false
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
                        plan = runCatching { classifier(transcript) }.getOrNull()
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
}
