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
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.reminder.AssistantAlertNotifier
import kr.mom.probe.service.ProbeNotificationListener
import kr.mom.probe.task.AssistantTaskSource
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end QA for the notification -> candidate analysis -> todo pipeline.
 *
 * Runs only against the debug QA application id (`kr.mom.probe.qa`). The
 * synthetic source allowlist is configured and verified BEFORE listener access
 * is enabled, so no real notification or user data is ever read. The original
 * enabled-listeners setting is captured first and restored verbatim in
 * `finally`. Teardown removes only the exact record, task, and notification
 * key this test created — no notification dump, no cancel-all, no user data.
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
        DeviceQaSafety.requireDestructibleState(context, "capturePipelineStoresEncryptedRecordAndCreatesEvidenceBackedTask")
        seedRepository(setOf(SYNTHETIC_PACKAGE))
        assertAllowlistBeforeListenerAccess(SYNTHETIC_PACKAGE)
        val listenerRestore = grantListenerAccessCapturingOriginal()
        var recordId: String? = null
        var taskId: String? = null
        try {
            assumeTrue(
                "QA listener access could not be enabled from instrumentation shell",
                repository.hasNotificationAccess(),
            )
            // Collection is armed only after the allowlist is proven and the
            // grant is live — the listener can never observe a notification it
            // was not explicitly configured to accept.
            assertTrue(repository.setCollectionEnabled(true))
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
            recordId = record!!.id

            val task = awaitTask("체험학습 준비물", timeoutMs = 10_000)
            assertNotNull("automatic task was not created from the captured notice", task)
            taskId = task!!.id
            assertEquals(AssistantTaskSource.AUTO_NOTICE, task.sourceKind)
            assertEquals(record.id, task.sourceRevisionId)
            assertTrue(task.noticeGroupKeys.isNotEmpty())
            assertTrue(task.checklist.any { it.text.contains("도시락") })
            assertNotNull(task.evidenceText)

            AssistantAlertNotifier.cancel(context, record)
        } finally {
            // Remove exactly what this test created, then restore the listener
            // grant state the device had before the run.
            taskId?.let { id -> runCatching { store.delete(id) } }
            recordId?.let { id -> runCatching { repository.deleteRecord(id) } }
            restoreListenerAccess(listenerRestore)
        }
    }

    /**
     * Posts a real notification through the OS as `com.android.shell` and waits
     * for the enabled QA listener to capture it end-to-end: listener callback
     * -> capture gate -> encrypted Room write -> coordinator -> task store.
     * Test code runs under the QA app's own uid, so it cannot post as a
     * foreign package; `cmd notification post` is the only way to deliver a
     * notification from a selected non-self package without a second app.
     *
     * `cmd notification post` offers no cancel command, so teardown cancels the
     * posted notification through the QA listener by the exact key stored on
     * the captured record — the tray is left clean.
     */
    @Test
    fun postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore")
        seedRepository(setOf("com.android.shell"))
        assertAllowlistBeforeListenerAccess("com.android.shell")
        val listenerRestore = grantListenerAccessCapturingOriginal()
        var recordId: String? = null
        var notificationKey: String? = null
        var taskId: String? = null
        try {
            assumeTrue(
                "QA listener access could not be enabled from instrumentation shell",
                repository.hasNotificationAccess(),
            )
            assertTrue(repository.setCollectionEnabled(true))
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
                recordId = persisted!!.id
            } finally {
                db.close()
            }

            // The stored notification key identifies this test's post exactly —
            // use it to remove the tray residue once assertions are done.
            notificationKey = repository.records.value.firstOrNull { it.id == recordId }?.notificationKey

            val task = awaitTask("회신안내", timeoutMs = 30_000)
            assertNotNull("captured notification did not produce an automatic task", task)
            taskId = task!!.id
            assertEquals(AssistantTaskSource.AUTO_NOTICE, task.sourceKind)
            assertNotNull(task.evidenceText)
            assertTrue(task.noticeGroupKeys.isNotEmpty())
        } finally {
            taskId?.let { id -> runCatching { store.delete(id) } }
            recordId?.let { id -> runCatching { repository.deleteRecord(id) } }
            notificationKey?.let { key ->
                ProbeNotificationListener.instance?.cancelNotificationByKey(key)
            }
            restoreListenerAccess(listenerRestore)
        }
    }

    private suspend fun seedRepository(packages: Set<String>) {
        assertTrue(repository.deleteAll())
        AssistantTaskStore.reset(context)
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.saveSourceSelection(packages))
        assertTrue(repository.deferSetup())
        // collectionEnabled stays off until the allowlist is verified and the
        // listener grant is live; the test arms it inside try{}.
    }

    /**
     * The synthetic allowlist must be in place and proven effective before the
     * listener is granted — otherwise the service could observe real user
     * notifications during the window between grant and seeding. The gate is
     * evaluated on an as-if-enabled settings copy because collection is still
     * off at this point by design.
     */
    private fun assertAllowlistBeforeListenerAccess(syntheticPackage: String) {
        val settings = repository.settings.value.copy(collectionEnabled = true)
        assertTrue(
            "synthetic package must be the only selected source",
            settings.selectedPackages == setOf(syntheticPackage),
        )
        assertTrue(
            "capture gate must accept the synthetic package",
            ProbeRules.canCapture(settings, syntheticPackage, context.packageName, true, false, false),
        )
        assertFalse(
            "capture gate must still reject packages outside the allowlist",
            ProbeRules.canCapture(settings, "com.example.real.school.app", context.packageName, true, false, false),
        )
    }

    /**
     * Enabled-listener setting captured before this test touched it; null means
     * the QA component was already enabled and nothing was modified.
     */
    private class ListenerRestore(val originalEnabled: String, val modified: Boolean)

    /**
     * Enables the QA listener through the instrumentation shell, first
     * capturing the device's existing enabled_notification_listeners value so
     * teardown can put back exactly what was there. Only the QA component is
     * granted; user listeners are never removed or reordered by hand.
     */
    private fun grantListenerAccessCapturingOriginal(): ListenerRestore {
        val original = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val component = ComponentName(context, ProbeNotificationListener::class.java)
            .flattenToString()
        if (original.split(':').any { it == component }) {
            return ListenerRestore(original, modified = false)
        }
        val automation = instrumentation.uiAutomation
        runCatching {
            automation.executeShellCommand("cmd notification allow_listener $component").close()
        }
        if (waitUntil(15_000) { repository.hasNotificationAccess() }) {
            return ListenerRestore(original, modified = true)
        }
        // Fallback: append the QA component without dropping existing listeners.
        val updated = (original.split(':') + component)
            .filter { it.isNotBlank() }
            .joinToString(":")
        runCatching {
            automation.executeShellCommand(
                "settings put secure enabled_notification_listeners $updated"
            ).close()
        }
        waitUntil(15_000) { repository.hasNotificationAccess() }
        return ListenerRestore(original, modified = true)
    }

    /**
     * Writes back the captured enabled-listener value verbatim. Only runs when
     * this test actually granted the component — a pre-existing grant is left
     * alone, and no user listener is ever removed.
     */
    private fun restoreListenerAccess(restore: ListenerRestore) {
        if (!restore.modified) return
        runCatching {
            instrumentation.uiAutomation.executeShellCommand(
                "settings put secure enabled_notification_listeners ${restore.originalEnabled}"
            ).close()
        }
        waitUntil(15_000) {
            Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty() == restore.originalEnabled
        }
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
