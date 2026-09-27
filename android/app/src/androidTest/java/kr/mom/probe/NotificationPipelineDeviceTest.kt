package kr.mom.probe

import android.app.Notification
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
 * Runs only against the debug QA application id (`kr.mom.probe.qa`) and only
 * on an isolated emulator. The collection gate stays closed until the
 * synthetic allowlist is seeded and proven — listener access by itself can
 * never make capture accept a package outside `selectedPackages`, and that
 * invariant is asserted explicitly for the pre-existing-access case. The
 * original enabled-listeners setting is captured verbatim and restored in
 * `finally`; a mismatched restore fails the test. Teardown deletes only the
 * exact record and task this test created and cancels its posted notification
 * by the stored key, verifying the key is gone — no dump, no cancel-all, no
 * user notification is ever touched.
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
        assertAllowlistBeforeCollection(SYNTHETIC_PACKAGE)
        val listenerRestore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
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
            // grant state the device had before the run. Cleanup failures are
            // test failures, not best-effort hints.
            taskId?.let { store.delete(it) }
            recordId?.let { assertTrue("captured record was not removed", repository.deleteRecord(it)) }
            DeviceQaSafety.restoreQaListenerAccess(context, listenerRestore)
        }
    }

    /**
     * Proves the real collection invariant for a device where listener access
     * already exists (or is granted mid-test): with the collection gate closed
     * or the package outside the synthetic allowlist, `capture` rejects the
     * notification no matter what `enabled_notification_listeners` says.
     * Nothing is persisted and no real notification is read.
     */
    @Suppress("DEPRECATION")
    @Test
    fun nonAllowlistedSourceIsNeverCapturedWhileCollectionIsClosed() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "nonAllowlistedSourceIsNeverCapturedWhileCollectionIsClosed")
        seedRepository(setOf(SYNTHETIC_PACKAGE))
        val listenerRestore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        try {
            val realPackage = "com.example.real.school.app"
            val realPackageNotification = Notification().apply {
                extras.putString(Notification.EXTRA_TITLE, "학원 공지")
                extras.putString(Notification.EXTRA_TEXT, "이번 주 수업 시간이 변경됩니다")
            }
            val sbn = StatusBarNotification(
                realPackage, realPackage, 7, "real-tag", 20_007,
                0, 0, realPackageNotification, android.os.Process.myUserHandle(),
                System.currentTimeMillis(),
            )
            // capture() reports whether the action ran, not whether the notice
            // was stored — the gate rejection is proven through canCapture and
            // by the record never appearing.
            fun gateOpen() = ProbeRules.canCapture(
                repository.settings.value, realPackage, context.packageName,
                repository.hasNotificationAccess(), false, false,
            )
            fun realRecordStored() = repository.records.value.any { it.packageName == realPackage }

            // Collection gate closed: rejected whether or not access is live.
            assertFalse(gateOpen())
            repository.capture(sbn, repository.captureEpoch())
            assertFalse(realRecordStored())

            assumeTrue(
                "QA listener access could not be enabled from instrumentation shell",
                repository.hasNotificationAccess(),
            )
            // Access granted but collection still closed: still rejected.
            assertFalse(gateOpen())
            repository.capture(sbn, repository.captureEpoch())
            assertFalse(realRecordStored())
            // Collection armed: the package is outside the allowlist, still rejected.
            assertTrue(repository.setCollectionEnabled(true))
            assertFalse(gateOpen())
            repository.capture(sbn, repository.captureEpoch())
            assertFalse(realRecordStored())
        } finally {
            DeviceQaSafety.restoreQaListenerAccess(context, listenerRestore)
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
     * the captured record, then verifies that key is no longer active — the
     * tray is left clean and any residue fails the test.
     */
    @Test
    fun postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore")
        seedRepository(setOf("com.android.shell"))
        assertAllowlistBeforeCollection("com.android.shell")
        val listenerRestore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        var recordId: String? = null
        var notificationKey: String? = null
        var taskId: String? = null
        try {
            assumeTrue(
                "QA listener access could not be enabled from instrumentation shell",
                repository.hasNotificationAccess(),
            )
            assertTrue(repository.setCollectionEnabled(true))
            // The setting being enabled does not mean the service is bound;
            // posting before onListenerConnected delivers nothing.
            assumeTrue(
                "QA listener service did not bind within 30s of the grant",
                waitUntil(30_000) { ProbeNotificationListener.instance != null },
            )
            val postedAt = System.currentTimeMillis()
            // executeShellCommand splits arguments on whitespace without shell
            // quoting, so the synthetic content stays single-token.
            instrumentation.uiAutomation.executeShellCommand(
                "cmd notification post -t 회신안내 qa_pipeline 9월16일까지회신해주세요"
            ).close()

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

            // The stored notification key identifies this test's post exactly;
            // wait until the record row is visible through the repository so
            // the key is proven persisted, not read optimistically.
            notificationKey = await(10_000) {
                repository.records.value.firstOrNull { it.id == recordId }?.notificationKey
            }
            assertNotNull("captured record did not persist its notification key", notificationKey)

            val task = awaitTask("회신안내", timeoutMs = 30_000)
            assertNotNull("captured notification did not produce an automatic task", task)
            taskId = task!!.id
            assertEquals(AssistantTaskSource.AUTO_NOTICE, task.sourceKind)
            assertNotNull(task.evidenceText)
            assertTrue(task.noticeGroupKeys.isNotEmpty())
        } finally {
            taskId?.let { store.delete(it) }
            recordId?.let { assertTrue("captured record was not removed", repository.deleteRecord(it)) }
            notificationKey?.let { cancelSyntheticNotificationExactly(it) }
            DeviceQaSafety.restoreQaListenerAccess(context, listenerRestore)
        }
    }

    /**
     * Cancels the one notification this test posted, by its stored key, and
     * proves it left the tray. Awaits a bound listener instance first, then
     * requires the cancel call to succeed and the key to go inactive — a
     * leftover synthetic notification or a missing listener fails the test.
     */
    private fun cancelSyntheticNotificationExactly(key: String) {
        assertTrue(
            "QA listener never connected; cannot remove the posted synthetic notification",
            waitUntil(15_000) { ProbeNotificationListener.instance != null },
        )
        val listener = ProbeNotificationListener.instance
            ?: error("QA listener instance disappeared before cleanup")
        assertTrue("listener rejected the synthetic notification cancel", listener.cancelNotificationByKey(key))
        assertTrue(
            "synthetic notification is still active after cancel",
            waitUntilInactive(listener, key, 5_000),
        )
    }

    /**
     * Polls until a successful active-notification query reports [key] gone.
     * A failed query is inconclusive — it retries until timeout and then
     * reports false, so "the check kept erroring" fails the test instead of
     * passing as if the tray were clean.
     */
    private fun waitUntilInactive(listener: ProbeNotificationListener, key: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val stillActive = try { listener.isNotificationActive(key) } catch (_: Exception) { true }
            if (!stillActive) return true
            Thread.sleep(300)
        }
        return try { !listener.isNotificationActive(key) } catch (_: Exception) { false }
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
     * collection gate opens — otherwise the listener could observe real user
     * notifications during the window between grant and arming. The gate is
     * evaluated on an as-if-enabled settings copy because collection is still
     * off at this point by design.
     */
    private fun assertAllowlistBeforeCollection(syntheticPackage: String) {
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
