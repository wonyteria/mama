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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
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
        compose.onNodeWithText("아직 저장하거나 실행하지 않았어요.").assertIsDisplayed()
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
                            errorText = null,
                            permissionDenied = false,
                            needsFallbackConsent = false,
                            onMicTap = {}, onConsentContinue = {},
                            onConsentCancel = {}, onClose = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
