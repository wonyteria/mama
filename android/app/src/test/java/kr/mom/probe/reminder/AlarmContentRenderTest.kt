package kr.mom.probe.reminder

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import java.io.File
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.ui.MomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "ko-rKR-w412dp-h892dp-xhdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmContentRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unlockedTaskAlarmShowsOneActionAndSpecificCompletion() {
        var completeTaps = 0
        render {
            AlarmContent(
                state = taskAlarmState(
                    unlocked = true,
                    allowed = true,
                    loading = false,
                    taskError = false,
                    task = AssistantTask(
                        id = "task",
                        text = "등교 가방에 물티슈 넣기",
                        completed = false,
                        createdAt = 1L,
                        dueAt = 1_800_000L,
                        activeAlarmOccurrenceId = "fire",
                        activeAlarmScheduledAt = 1_234_000L,
                        activeAlarmNotificationId = 27_100,
                    ),
                    scheduledAt = 1_234_000L,
                    speechReady = true,
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
                onComplete = { completeTaps++ },
                onSpeak = {},
            )
        }

        compose.onNodeWithTag("alarm-time").assertIsDisplayed()
        compose.onNodeWithText("예약 시각").assertIsDisplayed()
        compose.onNodeWithText("등교 가방에 물티슈 넣기").assertIsDisplayed()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-complete").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, completeTaps) }
        screenshot("alarm-task-unlocked")
    }

    @Test
    fun lockedTaskAlarmHidesPrivateTaskContent() {
        render {
            AlarmContent(
                state = AlarmContentState(
                    title = "민서 학교 약속",
                    dateText = "9월 20일 일요일",
                    scheduledTimeText = "07:00",
                    actionTitle = "민서 등교 가방에 물티슈 넣기",
                    actionSummary = "학교 약속 3개",
                    detailLines = listOf("민서 등교 가방에 물티슈 넣기"),
                    locked = true,
                    showComplete = true,
                    showSpeak = true,
                    statusText = "약속 내용을 불러왔어요",
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
                onUnlock = {},
            )
        }

        compose.onNodeWithText("잠금을 풀고 확인하세요").assertIsDisplayed()
        compose.onNodeWithText("민서", substring = true).assertDoesNotExist()
        compose.onNodeWithText("학교", substring = true).assertDoesNotExist()
        compose.onNodeWithText("약속", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-complete").assertDoesNotExist()
        compose.onNodeWithTag("alarm-speak").assertDoesNotExist()
        screenshot("alarm-task-locked")
    }

    @Test
    fun briefingAlarmOmitsCompletionButKeepsDetailsSecondary() {
        render {
            AlarmContent(
                state = AlarmContentState(
                    title = "모모의 브리핑",
                    dateText = "9월 20일 일요일",
                    scheduledTimeText = "20:30",
                    actionTitle = "체험학습 준비물 · 내일 오전 9시",
                    actionSummary = "곧 챙길 일 1개 · 다른 일 2개",
                    detailLines = listOf("곧 챙길 내용을 한 번에 정리했어요.", "부탁: 물통 챙기기"),
                    showComplete = false,
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
            )
        }

        compose.onNodeWithTag("alarm-complete").assertDoesNotExist()
        compose.onNodeWithTag("alarm-details").assertIsDisplayed().performClick()
        compose.onNodeWithText("부탁: 물통 챙기기").assertIsDisplayed()
        screenshot("alarm-briefing")
    }

    @Test
    fun demoBriefingKeepsPreviewSeparateFromCompletion() {
        render {
            AlarmContent(
                state = AlarmContentState(
                    title = "비서 알림 미리보기",
                    dateText = "9월 20일 일요일",
                    scheduledTimeText = "07:00",
                    actionTitle = "비서 알림 미리보기",
                    actionSummary = "정한 시간에 새로 모인 알림을 알려드릴게요.",
                    snoozeEnabled = false,
                    showComplete = false,
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
            )
        }

        compose.onNodeWithText("비서 알림 미리보기").assertIsDisplayed()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsNotEnabled()
        compose.onNodeWithTag("alarm-complete").assertDoesNotExist()
        screenshot("alarm-demo")
    }

    @Test
    @Config(qualifiers = "ko-rKR-w320dp-h640dp-xhdpi")
    fun primaryControlsRemainReachableAtSmallWidthAndDoubleText() {
        render(fontScale = 2f) {
            AlarmContent(
                state = AlarmContentState(
                    title = "모모의 부탁",
                    dateText = "9월 20일 일요일",
                    scheduledTimeText = "07:00",
                    actionTitle = "등교 가방에 물티슈 넣기",
                    actionSummary = "곧 챙길 일 1개",
                    showComplete = true,
                    completeEnabled = true,
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
                onComplete = {},
            )
        }

        compose.onNodeWithTag("alarm-time").assertIsDisplayed()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-complete").assertIsDisplayed().assertIsEnabled()
        screenshot("alarm-small-2x")
    }

    @Test
    fun staleTaskAlarmStateCannotCompleteOrSnooze() {
        render {
            AlarmContent(
                state = taskAlarmState(
                    unlocked = true,
                    allowed = true,
                    loading = false,
                    taskError = false,
                    task = null,
                    scheduledAt = 1_234_000L,
                    speechReady = false,
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
            )
        }

        compose.onNodeWithText("이미 지난 알림이에요").assertIsDisplayed()
        compose.onNodeWithTag("alarm-snooze").assertIsNotEnabled()
        compose.onNodeWithTag("alarm-complete").assertDoesNotExist()
    }

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MomTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        val file = File(directory, "$name.png")
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue("Activity must have a laid-out viewport", decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        assertTrue("Screenshot must have a non-empty viewport", bitmap.width > 0 && bitmap.height > 0)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Activity capture must contain drawn UI", pixels.any { it != pixels[0] })
        file.outputStream().use { output ->
            assertTrue("PNG encoder failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue("PNG was not written: ${file.absolutePath}", file.length() > 0)
    }
}

