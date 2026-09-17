package kr.mom.probe

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kr.mom.probe.agent.AgentActivity
import kr.mom.probe.calendar.CalendarAppPreferences
import kr.mom.probe.calendar.CalendarCommandStore
import kr.mom.probe.calendar.CalendarPreferences
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.reminder.ExternalAlarmGateway
import kr.mom.probe.reminder.TaskAlarmActivity
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.task.TaskReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Real-device QA harness for the local AgentActivity command lane.
 *
 * These tests run only against the debug application id (`kr.mom.probe.qa`). They create a
 * synthetic local consent/profile with deferred setup, avoid CalendarProvider writes, and stop
 * before launching any external alarm/calendar handler. Screenshots are saved for manual design QA.
 */
@RunWith(AndroidJUnit4::class)
class DeviceAgentSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val failureScreenshots = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { captureScreenshot("failure-${description.methodName}") }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<*>? = null

    @After
    fun closeActivity() {
        scenario?.close()
        scenario = null
    }


    @Test
    fun taskAlarmSnoozeKeepsTaskIncompleteAndSchedulesNextOccurrence() {
        initializeQaRepository()
        val store = AssistantTaskStore.get(context)
        store.load()
        val original = requireNotNull(store.addTask("QA 체육복 챙기기", remindAt = System.currentTimeMillis() + 60_000L)) {
            "QA task was not created."
        }
        val occurrence = requireNotNull(original.reminderOccurrenceId) { "QA task reminder occurrence was missing." }
        val notificationId = 271_555
        val active = requireNotNull(store.consumeReminder(original.id, occurrence, notificationId)) {
            "QA task could not be moved into active alarm state."
        }
        val scheduledAt = requireNotNull(active.activeAlarmScheduledAt) { "Active QA alarm lost its scheduled time." }
        val beforeSnooze = System.currentTimeMillis()
        scenario = ActivityScenario.launch<TaskAlarmActivity>(
            Intent(context, TaskAlarmActivity::class.java)
                .putExtra(TaskReminderScheduler.EXTRA_TASK_ID, original.id)
                .putExtra(TaskReminderScheduler.EXTRA_OCCURRENCE_ID, occurrence)
                .putExtra(TaskReminderScheduler.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(TaskReminderScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra("ringing", false),
        )

        waitForText("10분 뒤 다시 알림")
        compose.onNodeWithText("10분 뒤 다시 알림").performClick()

        waitForText("다시 알림을 예약했어요")
        captureScreenshot("05-task-alarm-snoozed")
        store.load()
        val snoozed = store.tasks.value.first { it.id == original.id }
        assertFalse(snoozed.completed)
        assertNull(snoozed.activeAlarmOccurrenceId)
        assertNull(snoozed.activeAlarmScheduledAt)
        assertNotNull(snoozed.remindAt)
        assertNotEquals(occurrence, snoozed.reminderOccurrenceId)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(10, snoozed.snoozeMinutes)
        assertTrue(requireNotNull(snoozed.remindAt) >= beforeSnooze + 9 * 60_000L)
        store.delete(original.id)
    }

    @Test
    fun localTaskCommandSavesAndUndoesWithScreenshots() {
        initializeQaRepository()
        launchAgent()

        sendToAgent("물티슈 챙겨줘")

        waitForText("부탁 목록에 저장했어요")
        compose.onNodeWithText("방금 저장한 부탁").assertIsDisplayed()
        captureScreenshot("01-local-task-saved")

        compose.onNodeWithText("취소").performClick()

        waitForText("방금 저장한 부탁을 취소했어요")
        captureScreenshot("02-local-task-undone")
    }

    @Test
    fun ambiguousCalendarCommandAsksForAmPmWithoutExternalInsertPath() {
        initializeQaRepository()
        launchAgent()

        sendToAgent("내일 7시 상담 일정 저장해줘")

        waitForText("오전인지 오후인지 알려주세요")
        compose.onNodeWithText("시간 확인").assertIsDisplayed()
        compose.onNodeWithText("오전").assertIsDisplayed()
        compose.onNodeWithText("오후").assertIsDisplayed()
        compose.onNodeWithText("저장할 캘린더").assertDoesNotExist()
        compose.onNodeWithText("입력 화면을 열 앱").assertDoesNotExist()
        captureScreenshot("03-calendar-ambiguous-clarification")
    }

    @Test
    fun datedAlarmCommandShowsSafeFallbackBeforeExternalDispatch() {
        initializeQaRepository()
        val handlers = ExternalAlarmGateway(context).handlers()
        if (handlers.isNotEmpty()) {
            assertTrue(ExternalAlarmGateway(context).saveSelectedHandler(handlers.first()))
        }
        launchAgent()

        sendToAgent("모레 오전 7시 알람 맞춰줘")

        if (handlers.isEmpty()) {
            waitForText("시계 앱을 찾지 못했어요")
            captureScreenshot("04-alarm-no-set-alarm-handler")
        } else {
            waitForText("날짜 있는 알림")
            compose.onNodeWithText("모모에서 알리기").assertIsDisplayed()
            compose.onNodeWithText("캘린더에 저장").assertIsDisplayed()
            compose.onNodeWithText("시계 앱 알람 설정 화면을 열었어요").assertDoesNotExist()
            captureScreenshot("04-alarm-dated-fallback")
        }
    }

    private fun initializeQaRepository() = runBlocking {
        check(context.packageName.endsWith(".qa")) {
            "DeviceAgentSmokeTest must target the debug QA application id, not release user data."
        }
        val repository = ProbeRepository.get(context)
        repository.deleteAll()
        CalendarCommandStore.reset(context)
        CalendarPreferences.reset(context)
        CalendarAppPreferences.reset(context)
        ExternalAlarmGateway.reset(context)
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA 아이"))
        assertTrue(repository.saveSourceSelection(setOf("kr.mom.synthetic.school")))
        assertTrue(repository.deferSetup())
        AssistantTaskStore.reset(context)
    }

    private fun launchAgent() {
        scenario = ActivityScenario.launch(AgentActivity::class.java)
        waitForText("모모에게 묻기")
    }

    private fun sendToAgent(text: String) {
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithText("모모에게 보내기").performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onFirstDisplayedNodeWithText(text).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onFirstDisplayedNodeWithText(text: String) =
        onAllNodesWithText(text, substring = true).also { nodes ->
            waitUntil(timeoutMillis = 10_000) { nodes.fetchSemanticsNodes().isNotEmpty() }
        }[0]
    private fun captureScreenshot(name: String): File {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = context.getExternalFilesDir("device-smoke") ?: File(context.filesDir, "device-smoke")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        assertTrue("Screenshot was not written: ${file.absolutePath}", file.length() > 0L)
        return file
    }
}







