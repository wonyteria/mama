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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CaptureDisposition
import kr.mom.probe.agent.CaptureLabels
import kr.mom.probe.agent.CapturePlan
import org.junit.After
import org.junit.Assert.assertEquals
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
    }
    override fun stop() = record("stop")
    override fun cancel() = record("cancel")
    override fun destroy() { record("destroy"); destroyedCount++ }
}

private class FakeFactory(
    var recognitionAvailable: Boolean = true,
    var onDeviceAvailable: Boolean = true,
    var throwOnOnDeviceCreate: Throwable? = null,
    var throwOnGenericCreate: Throwable? = null,
) : SpeechRecognizerFactory {
    val createdOnDevice = mutableListOf<FakeAdapter>()
    val createdGeneric = mutableListOf<FakeAdapter>()
    override fun isRecognitionAvailable() = recognitionAvailable
    override fun isOnDeviceAvailable() = onDeviceAvailable
    override fun createOnDevice(): RecognizerAdapter {
        throwOnOnDeviceCreate?.let { throw it }
        return FakeAdapter().also { createdOnDevice += it }
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

    private class FakeSaver(var result: VoiceSaveResult = VoiceSaveResult.Saved) : VoiceCaptureSaver {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun save(plan: CapturePlan, transcript: String, captureId: String): VoiceSaveResult {
            calls += Triple(plan.intent.name, transcript, captureId)
            return result
        }
    }

    private fun listenToResult(result: String) {
        val adapter = factory.createdOnDevice[0]
        compose.runOnIdle {
            adapter.listener!!.onReady()
            adapter.listener!!.onSpeechEnded()
            adapter.listener!!.onResult(result)
        }
        compose.waitForIdle()
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
        compose.runOnIdle {
            adapter.listener!!.onReady()
            adapter.listener!!.onPartial("체육복")
            adapter.listener!!.onSpeechEnded()
            adapter.listener!!.onResult("금요일까지 체육복 사야 돼")
        }
        compose.waitForIdle()
        compose.onNodeWithText("금요일까지 체육복 사야 돼").assertIsDisplayed()
        compose.onNodeWithText("지금은 아직 아무것도 쓰지 않았어요.", substring = true).assertIsDisplayed()
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
        VoiceQuickCaptureActivity.classifierOverride = { _, text -> taskPlan().copy(transcript = text) }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("내일 물티슈 챙겨줘")

        // Preview is honest: transcript + labels + not-yet-written copy.
        compose.onNodeWithTag("voice-result-card").assertIsDisplayed()
        compose.onNodeWithText("엄마가 직접 말함 · 할 일", substring = true).assertExists()
        compose.onNodeWithText("지금은 아직 아무것도 쓰지 않았어요.", substring = true).assertExists()
        assertEquals(0, saver.calls.size)

        compose.onNodeWithTag("voice-save").performClick()
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(1, saver.calls.size)
        compose.onNodeWithText("저장했어요.", substring = true).assertExists()
    }

    @Test fun `duplicate confirm taps submit exactly once`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("물통 챙겨줘")

        // The write path disappears the instant the save is claimed — there is
        // no second tap to double-submit.
        compose.onNodeWithTag("voice-save").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        assertEquals(1, saver.calls.size)
    }

    @Test fun `save failure keeps transcript and offers retry`() {
        val saver = FakeSaver(VoiceSaveResult.Failed("저장 실패", retryable = true))
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("물통 챙겨줘")

        compose.onNodeWithTag("voice-save").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.onNodeWithText("저장 실패").assertIsDisplayed()
        compose.onNodeWithText("물통 챙겨줘").assertIsDisplayed()

        saver.result = VoiceSaveResult.Saved
        compose.onNodeWithTag("voice-save").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(2, saver.calls.size)
        compose.onNodeWithText("저장했어요.", substring = true).assertExists()
    }

    @Test fun `question answers inline with no save button`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> questionPlan() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("이번 주 준비물 뭐야?")

        compose.onNodeWithText("저장된 알림에서 ‘도시락, 물통’을 찾았어요.").assertIsDisplayed()
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        assertEquals(0, saver.calls.size)
    }

    @Test fun `discard writes nothing and returns to the tap entry`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan() }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("물통 챙겨줘")

        compose.onNodeWithTag("voice-discard").performClick()
        compose.waitForIdle()
        assertEquals(0, saver.calls.size)
        compose.onNodeWithText("마이크를 눌러 말해주세요").assertIsDisplayed()
    }

    @Test fun `unclassifiable capture only writes through the memo opt-in`() {
        val saver = FakeSaver()
        launch()
        VoiceQuickCaptureActivity.saverOverride = saver
        VoiceQuickCaptureActivity.classifierOverride = { _, _ -> taskPlan(saveable = false) }
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tapMic()
        listenToResult("다음주 화요일 3시 상담")

        // No silent write; the only write path is the explicit memo opt-in.
        compose.onAllNodesWithTag("voice-save").assertCountEquals(0)
        assertEquals(0, saver.calls.size)
        compose.onNodeWithTag("voice-save-memo").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("voice-save").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals(1, saver.calls.size)
        assertEquals(AgentIntent.MEMO.name, saver.calls[0].first)
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

    private fun renderScreen(
        fontScale: Float = 1f,
        state: VoiceCaptureState = VoiceCaptureState.IDLE,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                kr.mom.probe.ui.MomTheme {
                    Surface(Modifier.fillMaxSize()) {
                        VoiceCaptureScreen(
                            state = state,
                            transcript = "",
                            plan = null,
                            errorText = null,
                            permissionDenied = false,
                            needsFallbackConsent = false,
                            onMicTap = {}, onConsentContinue = {},
                            onConsentCancel = {}, onClose = {},
                            onSave = {}, onSaveMemo = {}, onDiscard = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
