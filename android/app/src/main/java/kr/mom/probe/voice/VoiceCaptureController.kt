package kr.mom.probe.voice

import android.speech.SpeechRecognizer

/**
 * Tap-driven voice capture state machine.
 *
 * The only entry point is [startCapture], invoked after the user taps the
 * mic and runtime permission is granted. There is no path that starts
 * listening on creation, resume, or recreation — and no hotword,
 * background listening, or audio-file persistence anywhere.
 *
 * Lifecycle contract: the owning Activity calls [release] exactly once
 * from onDestroy. Every recognizer adapter is destroyed at most once;
 * callbacks from a torn-down or superseded session are ignored.
 *
 * Transcript is memory-only: it is set from partial/final results and
 * cleared on a new capture. Persistence belongs to Stage 4 routing and
 * requires explicit user confirmation.
 */
enum class VoiceCaptureState { IDLE, LISTENING, THINKING, DONE, ERROR }

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

    /** True while the generic system RecognitionService needs explicit consent. */
    var needsFallbackConsent: Boolean = false
        private set

    private var adapter: RecognizerAdapter? = null
    private var session = 0
    private var released = false
    var onChanged: () -> Unit = {}

    /**
     * Mic-tap entry. On-device recognition is tried first; if it is
     * unavailable or fails to create, we surface the fallback disclosure
     * instead of silently creating the generic recognizer.
     */
    fun startCapture() {
        if (released || adapter != null) return
        transcript = ""
        errorText = null
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

    /** Second tap while listening ends capture early and moves to THINKING. */
    fun stopCapture() {
        val a = adapter ?: return
        if (state != VoiceCaptureState.LISTENING) return
        try {
            a.stop()
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
            state = VoiceCaptureState.LISTENING
            a.start()
            onChanged()
        } catch (t: Throwable) {
            teardown(a)
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
