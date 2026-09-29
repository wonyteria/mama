package kr.mom.probe.voice

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause
import kr.mom.probe.agent.CaptureDisposition
import kr.mom.probe.agent.CaptureLabels
import kr.mom.probe.agent.CapturePlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

private class FakeAdapter : RecognizerAdapter {
    val events = mutableListOf<String>()
    var listener: VoiceRecognizerCallback? = null
    var destroyedCount = 0
    var startThrows: Throwable? = null
    /** When set, stop() synchronously delivers a final result — the race C guards. */
    var syncResultOnStop: String? = null

    private fun record(name: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "$name called off main thread" }
        events += name
    }

    override fun setCallback(callback: VoiceRecognizerCallback) {
        record("setCallback"); this.listener = callback
    }
    override fun start() {
        record("start")
        checkNotNull(listener) { "listener must be installed before start" }
        startThrows?.let { throw it }
    }
    override fun stop() {
        record("stop")
        syncResultOnStop?.let { listener?.onResult(it) }
    }
    override fun cancel() = record("cancel")
    override fun destroy() { record("destroy"); destroyedCount++ }
}

private class FakeFactory(
    var recognitionAvailable: Boolean = true,
    var onDeviceAvailable: Boolean = true,
    var throwOnOnDeviceCreate: Throwable? = null,
    var throwOnGenericCreate: Throwable? = null,
) : SpeechRecognizerFactory {
    /** When set, every on-device adapter created afterwards throws on start. */
    var onDeviceStartThrows: Throwable? = null
    val createdOnDevice = mutableListOf<FakeAdapter>()
    val createdGeneric = mutableListOf<FakeAdapter>()
    override fun isRecognitionAvailable() = recognitionAvailable
    override fun isOnDeviceAvailable() = onDeviceAvailable
    override fun createOnDevice(): RecognizerAdapter {
        throwOnOnDeviceCreate?.let { throw it }
        return FakeAdapter().also { it.startThrows = onDeviceStartThrows; createdOnDevice += it }
    }
    override fun createGeneric(): RecognizerAdapter {
        throwOnGenericCreate?.let { throw it }
        return FakeAdapter().also { createdGeneric += it }
    }
}

/**
 * Flow tests driving the real VoiceQuickCaptureActivity with a fake
 * recognizer factory. No real mic, audio, or speech service is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ko-rKR-w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceQuickCaptureActivityTest {

    @get:Rule val compose = createAndroidComposeRule<VoiceQuickCaptureActivity>()

    private lateinit var factory: FakeFactory

    private fun launch(
        recognitionAvailable: Boolean = true,
        onDeviceAvailable: Boolean = true,
        throwOnOnDeviceCreate: Throwable? = null,
        throwOnGenericCreate: Throwable? = null,
    ): VoiceQuickCaptureActivity {
        factory = FakeFactory(
            recognitionAvailable, onDeviceAvailable, throwOnOnDeviceCreate, throwOnGenericCreate,
        )
        VoiceQuickCaptureActivity.factoryOverride = { factory }
        return compose.activity
    }

    private fun tapMic() {
        compose.onNodeWithTag("voice-mic").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    @After fun tearDown() {
        VoiceQuickCaptureActivity.factoryOverride = null
        VoiceQuickCaptureActivity.classifierOverride = null
        VoiceQuickCaptureActivity.saverOverride = null
    }

    private fun taskPlan(saveable: Boolean = true) = CapturePlan(
        transcript = "내일 물티슈 챙겨줘",
        reply = kr.mom.probe.agent.LocalAgentReply(
            message = "‘내일 물티슈 챙기기’를 이 기기 부탁 목록에 저장할게요.",
            proposedTask = "내일 물티슈 챙기기",
            intent = AgentIntent.TASK,
        ),
        intent = AgentIntent.TASK,
        disposition = if (saveable) CaptureDisposition.KEEP_TODAY else CaptureDisposition.NEEDS_CONFIRM,
        labels = listOf(CaptureLabels.USER_SPOKE, AgentIntent.TASK.label),
        saveable = saveable,
    )

    private fun questionPlan() = CapturePlan(
        transcript = "이번 주 준비물 뭐야?",
        reply = kr.mom.probe.agent.LocalAgentReply(
            message = "저장된 알림에서 ‘도시락, 물통’을 찾았어요.",
            intent = AgentIntent.QUESTION,
        ),
        intent = AgentIntent.QUESTION,
        disposition = null,
        labels = listOf(CaptureLabels.USER_SPOKE, AgentIntent.QUESTION.label, CaptureLabels.NO_SAVE),
        saveable = false,
    )

    /** Single-clause batch wrapping one classified plan. */
    private fun CapturePlan.toBatch(text: String = transcript) = CaptureBatch(
        captureId = "test-capture",
        clauses = listOf(CaptureClause(0, text, this)),
    )

    private class FakeSaver(var result: VoiceSaveResult = VoiceSaveResult.Saved) : VoiceCaptureSaver {
        val calls = mutableListOf<CaptureBatch>()
        val transcripts = mutableListOf<String>()
        override suspend fun save(batch: CaptureBatch, transcript: String): VoiceSaveResult {
            calls += batch
            transcripts += transcript
            return result
        }
    }

    /**
     * Delivers a final result WITHOUT waiting for compose idle afterwards —
     * the DONE tree hosts editable text fields, which never idle under
     * Robolectric's paused looper in a real-activity composition (visual
     * assertions on that tree live in VoiceCaptureScreenLayoutTest).
     */
    private fun listenToResultNoIdle(result: String) {
        val adapter = factory.createdOnDevice.last()
        compose.runOnIdle {
            adapter.listener!!.onReady()
            adapter.listener!!.onSpeechEnded()
            adapter.listener!!.onResult(result)
        }
    }

    @Test fun `cold create never listens and shows the tap entry point`() {
        launch()
        assertEquals(0, factory.createdOnDevice.size + factory.createdGeneric.size)
        compose.onNodeWithTag("voice-mic").assertIsDisplayed()
        compose.onNodeWithText("마이크를 눌러 말해주세요").assertIsDisplayed()
    }

    @Test fun `mic tap without permission asks for it and never touches recognizer`() {
        launch()
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        // Robolectric records the request but does not dispatch the result;
        // drive it through the same handler the launcher callback calls.
        compose.runOnUiThread { compose.activity.onPermissionResult(false) }
        compose.waitForIdle()
        compose.onNodeWithText("마이크 권한이 필요해요").assertIsDisplayed()
        compose.onNodeWithTag("voice-permission-retry").assertIsDisplayed()
        assertEquals(0, factory.createdOnDevice.size + factory.createdGeneric.size)
        // Retry re-requests; still denied → still no recognizer created.
        compose.onNodeWithTag("voice-permission-retry").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onPermissionResult(false) }
        compose.waitForIdle()
        assertEquals(0, factory.createdOnDevice.size + factory.createdGeneric.size)
        // User then grants in the system dialog → capture starts.
        compose.runOnUiThread { compose.activity.onPermissionResult(true) }
        compose.waitForIdle()
        assertEquals(1, factory.createdOnDevice.size)
    }

    @Test fun `granted tap prefers on-device and installs listener before start`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        assertEquals(1, factory.createdOnDevice.size)
        assertEquals(0, factory.createdGeneric.size)
        val adapter = factory.createdOnDevice[0]
        assertEquals(listOf("setCallback", "start"), adapter.events)
        compose.onNodeWithTag("voice-headline").assert(hasText("듣고 있어요"))
    }

    @Test fun `full session reaches DONE with memory-only transcript`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        val adapter = factory.createdOnDevice[0]
        // The DONE tree renders editable text fields, which never idle under
        // Robolectric's paused looper with a real-activity composition — assert
        // the state machine directly; rendering is covered by layout tests.
        compose.runOnIdle {
            adapter.listener!!.onReady()
            adapter.listener!!.onPartial("체육복")
            adapter.listener!!.onSpeechEnded()
            adapter.listener!!.onResult("금요일까지 체육복 사야 돼")
        }
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertEquals("금요일까지 체육복 사야 돼", c.transcript)
        assertEquals(listOf("setCallback", "start", "destroy"), adapter.events)
    }

    @Test fun `on-device unavailable requires disclosure before generic recognizer`() {
        launch(onDeviceAvailable = false)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        assertEquals(0, factory.createdGeneric.size)
        compose.onNodeWithText("계속하기").assertIsDisplayed()
        compose.onNodeWithText("취소").assertIsDisplayed()
        // Cancel: back to idle, still no generic recognizer.
        compose.onNodeWithTag("voice-consent-cancel").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(0, factory.createdGeneric.size)
        compose.onNodeWithText("마이크를 눌러 말해주세요").assertIsDisplayed()
        // Tap again → disclosure again → Continue creates generic.
        tapMic()
        compose.onNodeWithTag("voice-consent-continue").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(1, factory.createdGeneric.size)
        assertEquals(listOf("setCallback", "start"), factory.createdGeneric[0].events)
    }

    @Test fun `on-device creation failure falls back only through disclosure`() {
        launch(throwOnOnDeviceCreate = IllegalStateException("no on-device"))
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        assertEquals(0, factory.createdGeneric.size)
        compose.onNodeWithText("계속하기").assertIsDisplayed()
    }

    @Test fun `duplicate taps never create a second recognizer`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        tapMic()
        assertEquals(1, factory.createdOnDevice.size)
        assertEquals(listOf("setCallback", "start", "stop"), factory.createdOnDevice[0].events)
    }

    @Test fun `device without any recognition lands in ERROR with retry`() {
        launch(recognitionAvailable = false, onDeviceAvailable = false)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        assertEquals(0, factory.createdOnDevice.size + factory.createdGeneric.size)
        compose.onNodeWithText("이 기기에서는 음성 인식을 지원하지 않아요.").assertIsDisplayed()
        compose.onNodeWithTag("voice-mic").assertIsDisplayed()
    }

    @Test fun `generic failure lands in ERROR with retry`() {
        launch(onDeviceAvailable = false, throwOnGenericCreate = IllegalStateException("no generic"))
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        compose.onNodeWithTag("voice-consent-continue").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.onNodeWithText("음성 인식을 시작하지 못했어요. 다시 시도해 주세요.").assertIsDisplayed()
        // Retry is still possible — the mic button remains the entry point.
        compose.onNodeWithTag("voice-mic").assertIsDisplayed()
    }

    @Test fun `empty speech result lands in a retryable error`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        compose.runOnIdle {
            factory.createdOnDevice[0].listener!!.onReady()
            factory.createdOnDevice[0].listener!!.onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
        }
        compose.waitForIdle()
        compose.onNodeWithText("알아듣지 못했어요. 다시 말해주세요.").assertIsDisplayed()
        compose.onNodeWithTag("voice-mic").assertIsEnabled()
    }

    @Test fun `cancelled recognition lands in error and nothing is written`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        compose.runOnIdle {
            factory.createdOnDevice[0].listener!!.onError(android.speech.SpeechRecognizer.ERROR_CLIENT)
        }
        compose.waitForIdle()
        compose.onNodeWithText("음성 인식이 중단됐어요. 다시 시도해 주세요.").assertIsDisplayed()
        assertEquals(0, saver.calls.size)
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
    }

    @Test fun `stop capture lands in THINKING even when the recognizer never calls back`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        compose.onNodeWithTag("voice-headline").assert(hasText("듣고 있어요"))

        // Second tap ends the capture — and no callback ever arrives.
        tapMic()
        compose.onNodeWithTag("voice-headline").assert(hasText("정리하고 있어요"))
        val adapter = factory.createdOnDevice[0]
        assertEquals(listOf("setCallback", "start", "stop"), adapter.events)
        compose.onNodeWithTag("voice-mic").assertIsNotEnabled()
    }

    @Test fun `adapter start failure restores idle so a cancelled disclosure still retries`() {
        launch()
        factory.onDeviceStartThrows = RuntimeException("start failed")
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()

        // Disclosure is up, and the session is back at IDLE — not stuck in LISTENING.
        compose.onNodeWithTag("voice-headline").assert(hasText("마이크를 눌러 말해주세요"))
        compose.onNodeWithTag("voice-consent-cancel").performClick()
        compose.waitForIdle()

        // A later tap retries cleanly: fix the adapter, tap again, LISTENING.
        factory.onDeviceStartThrows = null
        tapMic()
        compose.onNodeWithTag("voice-headline").assert(hasText("듣고 있어요"))
        assertEquals(listOf("setCallback", "start"), factory.createdOnDevice[1].events)
        assertEquals(listOf("setCallback", "start", "destroy"), factory.createdOnDevice[0].events)
    }

    @Test fun `recreation destroys the session once and does not re-listen`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        val adapter = factory.createdOnDevice[0]
        compose.runOnIdle { compose.activity.recreate() }
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(1, adapter.destroyedCount)
        assertEquals(listOf("setCallback", "start", "cancel", "destroy"), adapter.events)
        // The recreated activity waits for a new tap — no auto re-listen.
        assertEquals(1, factory.createdOnDevice.size)
        compose.onNodeWithText("마이크를 눌러 말해주세요").assertIsDisplayed()
    }

    @Test fun `stale callbacks cannot mutate a released session`() {
        launch()
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        val adapter = factory.createdOnDevice[0]
        val stale = adapter.listener!!
        compose.runOnIdle { compose.activity.recreate() }
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.runOnIdle {
            stale.onPartial("stale")
            stale.onResult("stale transcript")
        }
        compose.waitForIdle()
        // The new session stayed at its own idle entry — stale text never surfaces.
        compose.onNodeWithText("마이크를 눌러 말해주세요").assertIsDisplayed()
    }

    @Test fun `done preview shows provenance labels and writes only on explicit confirm`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, text -> taskPlan().toBatch(text) }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("내일 물티슈 챙겨줘")

        val c = compose.activity.captureController!!
        // Preview holds the batch; nothing is written before confirmation.
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertTrue(c.batch!!.saveable)
        assertTrue(c.batch!!.clauses.first().plan.labels.contains(CaptureLabels.USER_SPOKE))
        assertEquals(0, saver.calls.size)

        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(1, saver.calls.size)
        assertEquals("내일 물티슈 챙겨줘", saver.transcripts.first())
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `duplicate confirm taps submit exactly once`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("물통 챙겨줘")

        // The write path disappears the instant the save is claimed — there is
        // no second tap to double-submit.
        // The claim is taken synchronously — a second save request is refused.
        compose.runOnUiThread { compose.activity.onSave() }
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(1, saver.calls.size)
        assertEquals(VoiceCaptureState.SAVED, compose.activity.captureController!!.state)
    }

    @Test fun `save failure keeps transcript and offers retry`() {
        val saver = FakeSaver(VoiceSaveResult.Failed("저장 실패", retryable = true))
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("물통 챙겨줘")

        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertEquals("저장 실패", c.errorText)
        // Edits are preserved across the failed write.
        compose.runOnUiThread { c.setClauseTranscript(0, "수정된 물통") }
        assertEquals("수정된 물통", c.batch!!.clauses.first().transcript)

        saver.result = VoiceSaveResult.Saved
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(2, saver.calls.size)
        assertEquals("수정된 물통", saver.calls.last().clauses.first().transcript)
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `non-retryable save failure preserves edits but withdraws the retry`() {
        val saver = FakeSaver(VoiceSaveResult.Failed("앱에서 처음 설정을 마쳐주세요.", retryable = false))
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("물통 챙겨줘")

        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        // The failure is surfaced honestly — retryable=false reaches the UI
        // so no false retry is offered, but nothing was lost.
        assertFalse(c.saveRetryable)
        assertEquals("앱에서 처음 설정을 마쳐주세요.", c.errorText)
        assertTrue(c.batch!!.clauses.first().transcript.isNotBlank())
        // The claim is released — a fixed environment may save again.
        compose.runOnUiThread {
            saver.result = VoiceSaveResult.Saved
            compose.activity.onSave()
        }
        ShadowLooper.idleMainLooper()
        assertEquals(2, saver.calls.size)
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `memo-only batch writes the record without ever creating a task`() {
        // The explicit classification-failure memo path: convertToMemo makes a
        // MEMO/MEMO_ONLY clause — the saver must still run, but the durable
        // boundary is what decides nothing reaches the task store. The
        // coordinator-level proof lives in CaptureWriteCoordinatorTest; here
        // we pin that the UI save path completes with a memo clause intact.
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> throw RuntimeException("offline") }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("그냥 기록해두고 싶은 생각")

        val c = compose.activity.captureController!!
        assertTrue(c.classificationFailed)
        compose.runOnUiThread { c.convertToMemo() }
        val memo = c.batch!!.clauses.first()
        assertEquals(AgentIntent.MEMO, memo.intent)
        assertEquals(CaptureDisposition.MEMO_ONLY, memo.disposition)
        assertTrue(c.batch!!.saveable)

        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(1, saver.calls.size)
        assertEquals(AgentIntent.MEMO, saver.calls.last().clauses.first().intent)
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `non-retryable failure does not poison the next fresh capture`() {
        val saver = FakeSaver(VoiceSaveResult.Failed("앱에서 처음 설정을 마쳐주세요.", retryable = false))
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("첫 번째 부탁")

        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        val c = compose.activity.captureController!!
        assertFalse(c.saveRetryable)

        // A brand-new mic capture is a new session — the previous failure's
        // non-retryable flag must not hide the new saveable preview's save.
        saver.result = VoiceSaveResult.Saved
        compose.runOnUiThread { compose.activity.onMicTap() }
        ShadowLooper.idleMainLooper()
        listenToResultNoIdle("두 번째 부탁")

        assertEquals(VoiceCaptureState.DONE, c.state)
        assertTrue(c.saveRetryable)
        assertTrue(c.batch!!.saveable)
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        // Both captures reached the saver — the second carried the fresh
        // session's transcript, and its save action worked.
        assertEquals(2, saver.calls.size)
        assertEquals("두 번째 부탁", saver.transcripts.last())
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `question answers inline with no save button`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> questionPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("이번 주 준비물 뭐야?")

        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertEquals(AgentIntent.QUESTION, c.batch!!.clauses.first().intent)
        assertEquals("저장된 알림에서 ‘도시락, 물통’을 찾았어요.",
            c.batch!!.clauses.first().plan.reply.message)
        assertTrue(!c.batch!!.saveable)
        // Questions can never be written.
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(0, saver.calls.size)
    }

    @Test fun `discard writes nothing and returns to the tap entry`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan().toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("물통 챙겨줘")

        compose.runOnUiThread { compose.activity.captureController!!.discard() }
        assertEquals(VoiceCaptureState.IDLE, compose.activity.captureController!!.state)
        assertEquals(0, saver.calls.size)
    }

    @Test fun `unresolved clause only writes through the explicit memo resolution`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan(saveable = false).toBatch() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("다음주 화요일 3시 상담")

        // No silent write; the clause must be explicitly resolved first.
        val c = compose.activity.captureController!!
        assertTrue(c.batch!!.unresolved.isNotEmpty())
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(0, saver.calls.size)
        // Explicitly choosing 메모로 둘래요 resolves the clause; save then works.
        compose.runOnUiThread { c.resolveClause(0, CaptureDisposition.MEMO_ONLY) }
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(1, saver.calls.size)
        assertEquals(AgentIntent.MEMO, saver.calls[0].kept.first().intent)
    }

    @Test fun `stop with a synchronous final result lands in DONE not THINKING`() {
        launch()
        VoiceQuickCaptureActivity.classifierOverride = { _, text -> taskPlan().toBatch(text) }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        val adapter = factory.createdOnDevice[0]
        adapter.syncResultOnStop = "내일 물티슈 챙겨줘"

        // Second tap ends capture — stop() synchronously delivers the result.
        compose.runOnUiThread { compose.activity.onMicTap() }
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertEquals("내일 물티슈 챙겨줘", c.transcript)
        assertTrue(c.batch != null)
        assertEquals(listOf("setCallback", "start", "stop", "destroy"), adapter.events)
    }

    @Test fun `classifier failure is an explicit non-saveable review state`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> error("classifier blew up") }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("내일 물티슈 챙겨줘")

        // The transcript stays visible but nothing is saveable silently —
        // only explicit retry or memo conversion unlocks a write.
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertTrue(c.classificationFailed)
        assertEquals("내일 물티슈 챙겨줘", c.transcript)
        assertTrue(c.batch == null || !c.batch!!.saveable)
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(0, saver.calls.size)

        // Retry with a healthy classifier lands in the editable preview.
        VoiceQuickCaptureActivity.classifierOverride = { _, text -> taskPlan().toBatch(text) }
        compose.runOnUiThread { c.retryClassification() }
        assertTrue(!c.classificationFailed)
        assertTrue(c.batch!!.saveable)
    }

    @Test fun `multi-clause preview edits and drops before a single batch save`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ ->
            CaptureBatch(
                captureId = "multi-1",
                clauses = listOf(
                    CaptureClause(0, "내일 물티슈 챙겨줘", taskPlan()),
                    CaptureClause(1, "금요일 체육복 사야 돼", taskPlan()),
                ),
            )
        }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResultNoIdle("내일 물티슈 챙겨줘. 금요일 체육복 사야 돼")

        // Ordered editable preview — zero writes before confirmation.
        val c = compose.activity.captureController!!
        assertEquals(VoiceCaptureState.DONE, c.state)
        assertEquals(2, c.batch!!.clauses.size)
        assertEquals("내일 물티슈 챙겨줘", c.batch!!.clauses[0].transcript)
        assertEquals("금요일 체육복 사야 돼", c.batch!!.clauses[1].transcript)
        assertEquals(0, saver.calls.size)

        // Drop the second clause — only clause 0 is written.
        compose.runOnUiThread { c.dropClause(1) }
        compose.runOnUiThread { compose.activity.onSave() }
        ShadowLooper.idleMainLooper()
        assertEquals(1, saver.calls.size)
        assertEquals(1, saver.calls[0].kept.size)
        assertEquals(0, saver.calls[0].kept.first().index)
        assertEquals(VoiceCaptureState.SAVED, c.state)
    }

    @Test fun `manifest declares RECORD_AUDIO, recognition query, and non-exported activity`() {
        val manifest = File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml")
            .readText()
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.speech.RecognitionService"))
        val activityLine = manifest.lineSequence()
            .first { it.contains("VoiceQuickCaptureActivity") }
        assertTrue(activityLine.contains("android:exported=\"false\""))
        // No unrelated permission drift: the permission list stays exactly this.
        val permissions = Regex("""<uses-permission android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.INTERNET",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR",
                "com.android.alarm.permission.SET_ALARM",
                "android.permission.RECORD_AUDIO",
            ),
            permissions,
        )
        assertTrue(!manifest.contains("USE_FULL_SCREEN_INTENT"))
    }
}

/**
 * Layout/accessibility tests on a bare ComponentActivity so setContent is
 * legal — 360dp portrait, 200% font scale, landscape reachability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ko-rKR-w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceCaptureScreenLayoutTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `mic button keeps a 48dp target at 200 percent font scale`() {
        renderScreen(fontScale = 2f)
        compose.onNodeWithTag("voice-mic").assertHeightIsAtLeast(48.dp)
    }

    @Config(qualifiers = "ko-rKR-land-xhdpi")
    @Test fun `landscape keeps the mic and close controls reachable`() {
        renderScreen()
        compose.onNodeWithTag("voice-mic").assertExists()
        compose.onNodeWithText("닫기").assertExists()
    }

    @Test fun `thinking state disables the mic until results arrive`() {
        renderScreen(state = VoiceCaptureState.THINKING)
        compose.onNodeWithTag("voice-mic").assertIsNotEnabled()
    }

    private fun planOf(
        text: String,
        intent: AgentIntent = AgentIntent.TASK,
        disposition: CaptureDisposition? = CaptureDisposition.KEEP_TODAY,
        saveable: Boolean = true,
        dueAt: Long? = null,
        task: String? = null,
        message: String = "",
    ) = CapturePlan(
        transcript = text,
        reply = kr.mom.probe.agent.LocalAgentReply(
            message = message, proposedTask = task ?: text,
            intent = intent, proposedDueAt = dueAt,
        ),
        intent = intent, disposition = disposition,
        labels = listOf(CaptureLabels.USER_SPOKE, intent.label),
        saveable = saveable,
    )

    private fun batchOf(vararg clauses: CaptureClause) =
        CaptureBatch(captureId = "layout-test", clauses = clauses.toList())

    /**
     * A REAL controller driven through the fake recognizer into DONE, with
     * the composable's edit callbacks bound to it — every assertion reads
     * controller state, so UI↔controller wiring is fully exercised.
     */
    private class DrivenScreen(
        val controller: VoiceCaptureController,
        var adapter: FakeAdapter,
        var saveTaps: Int = 0,
        var memoTaps: Int = 0,
        var discardTaps: Int = 0,
        var retryTaps: Int = 0,
    )

    private fun driveToDone(batch: CaptureBatch, fontScale: Float = 1f): DrivenScreen {
        val factory = FakeFactory()
        val controller = VoiceCaptureController { factory }
        controller.classifier = { batch }
        val driven = DrivenScreen(controller, FakeAdapter())
        lateinit var renderedBatch: androidx.compose.runtime.MutableState<CaptureBatch?>
        lateinit var renderedState: androidx.compose.runtime.MutableState<VoiceCaptureState>
        compose.setContent {
            renderedState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(controller.state)
            }
            renderedBatch = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(controller.batch)
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                kr.mom.probe.ui.MomTheme {
                    Surface(Modifier.fillMaxSize()) {
                        VoiceCaptureScreen(
                            state = renderedState.value,
                            transcript = controller.transcript,
                            batch = renderedBatch.value,
                            classificationFailed = controller.classificationFailed,
                            errorText = controller.errorText,
                            permissionDenied = false,
                            needsFallbackConsent = false,
                            onMicTap = {},
                            onConsentContinue = {}, onConsentCancel = {},
                            onClose = {},
                            onSave = { driven.saveTaps++ },
                            onSaveMemo = { controller.convertToMemo(); driven.memoTaps++ },
                            onDiscard = { controller.discard(); driven.discardTaps++ },
                            onRetryClassification = { controller.retryClassification(); driven.retryTaps++ },
                            onClauseTranscript = controller::setClauseTranscript,
                            onClauseAction = controller::setClauseAction,
                            onClauseDate = controller::setClauseDate,
                            onClauseResolve = controller::resolveClause,
                            onClauseDrop = controller::dropClause,
                            onClauseKeep = controller::keepClause,
                        )
                    }
                }
            }
        }
        controller.onChanged = {
            renderedState.value = controller.state
            renderedBatch.value = controller.batch
        }
        controller.startCapture()
        val adapter = factory.createdOnDevice.first()
        adapter.listener!!.onResult("캡처")
        driven.adapter = adapter
        compose.waitForIdle()
        assertEquals(VoiceCaptureState.DONE, controller.state)
        return driven
    }

    @Test fun `ordered clauses render as separate editable vertical cards`() {
        driveToDone(
            batchOf(
                CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
                CaptureClause(1, "금요일 체육복 사야 돼", planOf("금요일 체육복 사야 돼", intent = AgentIntent.SHOPPING)),
                CaptureClause(2, "그냥 메모", planOf("그냥 메모", intent = AgentIntent.MEMO,
                    disposition = CaptureDisposition.MEMO_ONLY)),
            ),
        )
        compose.onNodeWithTag("voice-clause-0").assertIsDisplayed()
        compose.onNodeWithTag("voice-clause-1").assertIsDisplayed()
        compose.onNodeWithTag("voice-clause-2").performScrollTo().assertIsDisplayed()
        // Provenance labels stay visible; batch confirm requires all resolved.
        compose.onAllNodesWithText("엄마가 직접 말함", substring = true).assertCountEquals(3)
        compose.onNodeWithText("지금은 아직 아무것도 쓰지 않았어요.", substring = true).assertExists()
        compose.onNodeWithTag("voice-save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice-discard").performScrollTo().assertIsDisplayed()
    }

    @Test fun `editing transcript action and date fields updates the controller`() {
        val driven = driveToDone(batchOf(
            CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
        ))
        compose.onNodeWithTag("clause-text-0")
            .performTextReplacement("모레 물티슈 챙겨줘")
        compose.onNodeWithTag("clause-action-0")
            .performTextReplacement("물티슈 두 팩")
        compose.onNodeWithTag("clause-date-0")
            .performTextReplacement("모레 오전 9시")
        assertEquals("모레 물티슈 챙겨줘", driven.controller.batch!!.clauses[0].transcript)
        assertEquals("물티슈 두 팩", driven.controller.batch!!.clauses[0].action)
        assertEquals("모레 오전 9시", driven.controller.batch!!.clauses[0].dateInput)
        assertTrue(driven.controller.batch!!.clauses[0].editedByUser)
    }

    @Test fun `unparseable date input blocks the batch save`() {
        val driven = driveToDone(batchOf(
            CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
        ))
        compose.onNodeWithTag("clause-date-0")
            .performTextReplacement("이상한 날짜")
        compose.waitForIdle()
        assertTrue(driven.controller.batch!!.clauses[0].dateParseFailed)
        assertTrue(driven.controller.batch!!.unresolved.isNotEmpty())
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        // Clearing the field removes the block.
        compose.onNodeWithTag("clause-date-0").performTextReplacement("")
        compose.waitForIdle()
        compose.onNodeWithTag("voice-save").assertIsDisplayed()
    }

    @Test fun `programmatic null times clear the date without restoring the proposal`() {
        // The picker seam: setClauseTimes(i, null, null) must behave like a
        // typed blank — the cleared date persists and never falls back to
        // the parsed proposal.
        val proposed = 1_800_000_000_000L
        val driven = driveToDone(batchOf(
            CaptureClause(0, "내일 물티슈 챙겨줘",
                planOf("내일 물티슈 챙겨줘", dueAt = proposed)),
        ))
        driven.controller.setClauseTimes(0, null, null)
        val clause = driven.controller.batch!!.clauses[0]
        assertTrue(clause.dateEdited)
        assertEquals(null, clause.dueAt)
        assertEquals(null, clause.effectiveDueAt)
        // The preview shows what will actually be written: blank, not the
        // stale proposed date the parent just removed.
        compose.waitForIdle()
        compose.onNodeWithTag("clause-date-0").assertTextEquals("")
        // Picked times mark the edit the same way typed text does.
        driven.controller.setClauseTimes(0, 1_900_000_000_000L, null)
        assertEquals(1_900_000_000_000L, driven.controller.batch!!.clauses[0].effectiveDueAt)
        compose.waitForIdle()
        compose.onNodeWithTag("clause-date-0").assertTextEquals(
            java.time.Instant.ofEpochMilli(1_900_000_000_000L)
                .atZone(java.time.ZoneId.of("Asia/Seoul")).let {
                    "${it.monthValue}월 ${it.dayOfMonth}일 %02d:%02d".format(it.hour, it.minute)
                },
        )
    }

    @Test fun `drop hides a clause from the batch and keep restores it`() {
        val driven = driveToDone(
            batchOf(
                CaptureClause(0, "첫 번째", planOf("첫 번째")),
                CaptureClause(1, "두 번째", planOf("두 번째")),
            ),
        )
        compose.onNodeWithTag("clause-drop-1").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(driven.controller.batch!!.clauses[1].dropped)
        assertEquals(listOf(0), driven.controller.batch!!.kept.map { it.index })
        compose.onNodeWithText("이 부분은 저장하지 않아요.").assertIsDisplayed()
        compose.onNodeWithTag("clause-keep-1").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(0, 1), driven.controller.batch!!.kept.map { it.index })
    }

    @Test fun `needs-review clause blocks save until explicitly resolved`() {
        val driven = driveToDone(batchOf(
            CaptureClause(0, "모르겠는데 뭔가 있었어", planOf(
                "모르겠는데 뭔가 있었어", disposition = CaptureDisposition.NEEDS_CONFIRM,
                saveable = false,
            )),
        ))
        assertTrue(driven.controller.batch!!.unresolved.isNotEmpty())
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        compose.onNodeWithTag("clause-memo-0").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(driven.controller.batch!!.unresolved.isEmpty())
        assertEquals(AgentIntent.MEMO, driven.controller.batch!!.clauses[0].intent)
        compose.onNodeWithTag("voice-save").performScrollTo().assertIsDisplayed()
    }

    @Test fun `question clause shows its answer and never offers a write`() {
        driveToDone(batchOf(
            CaptureClause(0, "이번 주 준비물 뭐야?", planOf(
                "이번 주 준비물 뭐야?", intent = AgentIntent.QUESTION,
                disposition = null, saveable = false,
                message = "도시락과 물통이 등록되어 있어요.",
            )),
        ))
        compose.onNodeWithText("도시락과 물통이 등록되어 있어요.").assertIsDisplayed()
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
    }

    @Test fun `save and discard fire exactly once per tap`() {
        val driven = driveToDone(batchOf(
            CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
        ))
        compose.onNodeWithTag("voice-save").performScrollTo().performClick()
        assertEquals(1, driven.saveTaps)
        compose.onNodeWithTag("voice-discard").performScrollTo().performClick()
        assertEquals(1, driven.discardTaps)
        assertEquals(VoiceCaptureState.IDLE, driven.controller.state)
    }

    @Test fun `classification failure offers only retry or explicit memo`() {
        var retryTaps = 0
        var memoTaps = 0
        renderScreen(
            state = VoiceCaptureState.DONE, batch = null, classificationFailed = true,
            onRetryClassification = { retryTaps++ }, onSaveMemo = { memoTaps++ },
        )
        compose.onNodeWithTag("voice-retry-classify").assertIsDisplayed()
        compose.onNodeWithTag("voice-save-memo").assertIsDisplayed()
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        compose.onNodeWithTag("voice-retry-classify").performClick()
        compose.onNodeWithTag("voice-save-memo").performClick()
        assertEquals(1, retryTaps)
        assertEquals(1, memoTaps)
    }

    @Test fun `editable clause cards stay reachable at 200 percent font scale`() {
        driveToDone(
            batchOf(
                CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
                CaptureClause(1, "금요일 체육복 사야 돼", planOf("금요일 체육복 사야 돼")),
            ),
            fontScale = 2f,
        )
        compose.onNodeWithTag("clause-text-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("clause-drop-0").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("clause-memo-0").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("voice-save").performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test fun `non-retryable failure hides the save retry and explains recovery`() {
        renderScreen(
            state = VoiceCaptureState.DONE,
            batch = batchOf(
                CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
            ),
            saveRetryable = false,
        )
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        compose.onNodeWithTag("voice-save-blocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("앱에서 처음 설정을 마쳐주세요.").performScrollTo().assertExists()
        compose.onNodeWithTag("voice-discard").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = "ko-rKR-land-xhdpi")
    @Test fun `landscape keeps every clause control reachable`() {
        driveToDone(batchOf(
            CaptureClause(0, "내일 물티슈 챙겨줘", planOf("내일 물티슈 챙겨줘")),
        ))
        compose.onNodeWithTag("voice-clause-0").performScrollTo().assertExists()
        compose.onNodeWithTag("clause-drop-0").performScrollTo().assertExists()
        compose.onNodeWithTag("voice-save").performScrollTo().assertExists()
        compose.onNodeWithText("닫기").performScrollTo().assertExists()
    }

    private fun renderScreen(
        fontScale: Float = 1f,
        state: VoiceCaptureState = VoiceCaptureState.IDLE,
        batch: CaptureBatch? = null,
        classificationFailed: Boolean = false,
        saveRetryable: Boolean = true,
        onRetryClassification: () -> Unit = {},
        onSaveMemo: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                kr.mom.probe.ui.MomTheme {
                    Surface(Modifier.fillMaxSize()) {
                        VoiceCaptureScreen(
                            state = state,
                            transcript = "",
                            batch = batch,
                            classificationFailed = classificationFailed,
                            errorText = if (!saveRetryable) "앱에서 처음 설정을 마쳐주세요." else null,
                            saveRetryable = saveRetryable,
                            permissionDenied = false,
                            needsFallbackConsent = false,
                            onMicTap = {}, onConsentContinue = {},
                            onConsentCancel = {}, onClose = {},
                            onSave = {}, onSaveMemo = onSaveMemo, onDiscard = {},
                            onRetryClassification = onRetryClassification,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
