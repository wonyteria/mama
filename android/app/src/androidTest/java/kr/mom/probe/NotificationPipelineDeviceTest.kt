package kr.mom.probe

import android.app.Notification
import android.content.ComponentName
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.mom.probe.data.ProbeDatabase
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.reminder.AssistantAlertNotifier
import kr.mom.probe.service.ProbeNotificationListener
import kr.mom.probe.task.AssistantTaskSource
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end QA for the notification -> candidate analysis -> todo pipeline.
 *
 * Runs only against the debug QA application id (`kr.mom.probe.qa`). Synthetic
 * notifications come from a fake package name or the instrumentation test
 * package itself; real user notifications and release app data are never read.
 * The listener is enabled through the instrumentation shell, so no manual
 * setup is needed, but the test honestly skips if the OS refuses the grant.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPipelineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = ProbeRepository.get(context)
    private val store get() = AssistantTaskStore.get(context)

    @Before
    fun requireQaPackage() {
        check(context.packageName.endsWith(".qa")) {
            "NotificationPipelineDeviceTest must target the debug QA application id, not release user data."
        }
    }

    /**
     * Drives ProbeRepository.capture directly with a synthetic
     * StatusBarNotification: consent -> selected-package gate -> field
     * extraction -> encrypted Room write -> CandidateActionPlanner ->
     * AssistantTaskStore. This is the production capture body the listener
     * calls, minus OS delivery.
     */
    @Suppress("DEPRECATION") // StatusBarNotification's public constructor is the only test seam.
    @Test
    fun capturePipelineStoresEncryptedRecordAndCreatesEvidenceBackedTask() = runBlocking {
        grantListenerAccess()
        assumeTrue(
            "QA listener access could not be enabled from instrumentation shell",
            repository.hasNotificationAccess(),
        )
        seedRepository(setOf(SYNTHETIC_PACKAGE))

        val postedAt = System.currentTimeMillis()
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "체험학습 준비물")
            extras.putString(Notification.EXTRA_TEXT, "준비물: 도시락, 물통. 내일 오전 9시까지")
        }
        val sbn = StatusBarNotification(
            SYNTHETIC_PACKAGE, SYNTHETIC_PACKAGE, 402, "qa-tag", 10_042, 0,
            0, notification, android.os.Process.myUserHandle(), postedAt,
        )

        assertTrue(repository.capture(sbn, repository.captureEpoch()))

        val record = await(10_000) {
            repository.records.value.firstOrNull {
                it.packageName == SYNTHETIC_PACKAGE && it.title == "체험학습 준비물"
            }
        }
        assertNotNull("captured record was not persisted", record)

        val task = awaitTask("체험학습 준비물", timeoutMs = 10_000)
        assertNotNull("automatic task was not created from the captured notice", task)
        task!!
        assertEquals(AssistantTaskSource.AUTO_NOTICE, task.sourceKind)
        assertEquals(record!!.id, task.sourceRevisionId)
        assertTrue(task.noticeGroupKeys.isNotEmpty())
        assertTrue(task.checklist.any { it.text.contains("도시락") })
        assertNotNull(task.evidenceText)

        AssistantAlertNotifier.cancel(context, record)
    }

    /**
     * Posts a real notification through the OS as `com.android.shell` and waits
     * for the enabled QA listener to capture it end-to-end: listener callback
     * -> capture gate -> encrypted Room write -> coordinator -> task store.
     * Test code runs under the QA app's own uid, so it cannot post as a
     * foreign package; `cmd notification post` is the only way to deliver a
     * notification from a selected non-self package without a second app.
     *
     * `cmd notification post` offers no cancel command, so the posted
     * notification may remain in the tray after the test (safe to swipe away).
     * The record and the task are read back from shared storage because the
     * listener works in the app process.
     */
    @Test
    fun postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore() = runBlocking {
        grantListenerAccess()
        assumeTrue(
            "QA listener access could not be enabled from instrumentation shell",
            repository.hasNotificationAccess(),
        )
        seedRepository(setOf("com.android.shell"))

        val postedAt = System.currentTimeMillis()
        // executeShellCommand splits arguments on whitespace without shell
        // quoting, so the synthetic content stays single-token.
        runCatching {
            instrumentation.uiAutomation.executeShellCommand(
                "cmd notification post -t 회신안내 qa_pipeline 9월16일까지회신해주세요"
            ).close()
        }

        val db = Room.databaseBuilder(context, ProbeDatabase::class.java, "mom-probe.db").build()
        try {
            val persisted = await(45_000) {
                runCatching {
                    db.dao().currentRecords().firstOrNull { it.receivedAt >= postedAt }
                }.getOrNull()
            }
            assertNotNull("listener did not persist the posted synthetic notification", persisted)
        } finally {
            db.close()
        }

        val task = awaitTask("회신안내", timeoutMs = 30_000)
        assertNotNull("captured notification did not produce an automatic task", task)
        task!!
        assertEquals(AssistantTaskSource.AUTO_NOTICE, task.sourceKind)
        assertNotNull(task.evidenceText)
        assertTrue(task.noticeGroupKeys.isNotEmpty())
    }

    private suspend fun seedRepository(packages: Set<String>) {
        assertTrue(repository.deleteAll())
        AssistantTaskStore.reset(context)
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.saveSourceSelection(packages))
        assertTrue(repository.deferSetup())
        assertTrue(repository.setCollectionEnabled(true))
    }

    /**
     * Enables the QA listener through the instrumentation shell. Only the QA
     * component is granted; existing user listeners are kept. We never revoke
     * on teardown because other tests also rely on the grant and the capture
     * gate only reads explicitly selected synthetic packages.
     */
    private fun grantListenerAccess() {
        val component = ComponentName(context, ProbeNotificationListener::class.java)
            .flattenToString()
        val automation = instrumentation.uiAutomation
        runCatching {
            automation.executeShellCommand("cmd notification allow_listener $component").close()
        }
        if (waitUntil(15_000) { repository.hasNotificationAccess() }) return

        // Fallback: append the QA component to enabled_notification_listeners
        // without dropping listeners the user already enabled.
        val current = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        if (current.split(':').none { it == component }) {
            val updated = (current.split(':') + component)
                .filter { it.isNotBlank() }
                .joinToString(":")
            runCatching {
                automation.executeShellCommand(
                    "settings put secure enabled_notification_listeners $updated"
                ).close()
            }
        }
        waitUntil(15_000) { repository.hasNotificationAccess() }
    }

    private suspend fun awaitTask(sourceTitle: String, timeoutMs: Long) = await(timeoutMs) {
        store.load()
        store.tasks.value.firstOrNull { it.sourceTitle == sourceTitle }
    }

    private suspend fun <T> await(timeoutMs: Long, probe: suspend () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            kotlinx.coroutines.delay(400)
        }
        return probe()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(400)
        }
        return condition()
    }

    private companion object {
        const val SYNTHETIC_PACKAGE = "kr.mom.probe.qa.synthetic"
    }
}
