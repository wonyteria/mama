package kr.mom.probe

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.reminder.AssistantAlertNotifier
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceScopeFactory
import kr.mom.probe.sync.SourceSyncStateStore
import kr.mom.probe.sync.SourceSyncStatus
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.task.AutoActionCoordinator
import kr.mom.probe.task.ReconcileJournal
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
 * Durability regressions for the record-commit -> journal -> task-reconcile
 * contract (defect E). Runs only on an isolated emulator against the QA app.
 *
 * The journal writes before the record row commits, so a journal failure
 * persists nothing; a crash that leaves a marker without a record is a ghost
 * that replay drops; a crash after the commit leaves a marker replay resolves
 * exactly once. Tombstones, retired keys, and a withdrawn consent are never
 * resurrected.
 */
@RunWith(AndroidJUnit4::class)
class ReconcileDurabilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = ProbeRepository.get(context)
    private val store get() = AssistantTaskStore.get(context)

    @Before
    fun requireQaPackage() {
        check(context.packageName.endsWith(".qa")) {
            "ReconcileDurabilityDeviceTest must target the debug QA application id, not release user data."
        }
    }

    /**
     * A failed journal commit must abort the capture: no record row and no
     * pending marker may persist, and the caller sees the failure.
     */
    @Test
    fun journalCommitFailurePersistsNeitherRecordNorMarker() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "journalCommitFailurePersistsNeitherRecordNorMarker")
        seedRepository()
        val restore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        try {
            assumeTrue("listener access could not be granted", repository.hasNotificationAccess())
            assertTrue(repository.setCollectionEnabled(true))
            val previous = ReconcileJournal.committer
            ReconcileJournal.committer = { false }
            try {
                assertFalse(repository.capture(syntheticSbn(), repository.captureEpoch()))
            } finally {
                ReconcileJournal.committer = previous
            }
            assertTrue(repository.records.value.isEmpty())
            assertTrue(ReconcileJournal.pending(context).isEmpty())
            assertTrue(store.tasks.value.isEmpty())
        } finally {
            DeviceQaSafety.restoreQaListenerAccess(context, restore)
        }
    }

    /**
     * The record commits but the task save fails (the crash-between boundary).
     * The pending marker survives; replay creates the task exactly once, and a
     * second replay creates no duplicate.
     */
    @Test
    fun taskSaveFailureLeavesPendingThenReplayCreatesExactlyOnce() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "taskSaveFailureLeavesPendingThenReplayCreatesExactlyOnce")
        seedRepository()
        val restore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        var recordId: String? = null
        var taskId: String? = null
        try {
            assumeTrue("listener access could not be granted", repository.hasNotificationAccess())
            assertTrue(repository.setCollectionEnabled(true))
            AssistantTaskStore.saveInterceptor = { throw IOException("injected save failure") }
            val captured = try {
                repository.capture(syntheticSbn(), repository.captureEpoch())
            } finally {
                AssistantTaskStore.saveInterceptor = null
            }
            assertTrue(captured)

            val record = await(10_000) {
                repository.records.value.firstOrNull { it.packageName == SYNTHETIC_PACKAGE }
            }
            assertNotNull("record must persist even though the task save failed", record)
            recordId = record!!.id
            assertTrue("pending marker must survive the failed reconcile",
                ReconcileJournal.pending(context).isNotEmpty())
            assertTrue("no task may exist before replay",
                store.tasks.value.none { it.sourceRevisionId == record.id })

            AutoActionCoordinator.replayPending(context)
            store.load()
            val afterFirst = store.tasks.value.count { it.sourceRevisionId == record.id }
            assertTrue("replay must create the reconciled task(s)", afterFirst >= 1)
            taskId = store.tasks.value.first { it.sourceRevisionId == record.id }.id

            AutoActionCoordinator.replayPending(context)
            store.load()
            assertEquals("a second replay must not duplicate the task", afterFirst,
                store.tasks.value.count { it.sourceRevisionId == record.id })
            assertTrue(ReconcileJournal.pending(context).isEmpty())

            AssistantAlertNotifier.cancel(context, record)
        } finally {
            taskId?.let {
                store.load()
                store.tasks.value.filter { task -> task.sourceRevisionId == recordId }
                    .forEach { task -> store.delete(task.id) }
            }
            recordId?.let { assertTrue("record cleanup failed", repository.deleteRecord(it)) }
            DeviceQaSafety.restoreQaListenerAccess(context, restore)
        }
    }

    /**
     * A task the user deleted retires its group keys; a stale pending marker
     * for the same source must drop instead of resurrecting the task. A marker
     * whose record never committed is a ghost and drops the same way.
     */
    @Test
    fun retiredAndGhostMarkersNeverResurrect() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "retiredAndGhostMarkersNeverResurrect")
        seedRepository()
        val restore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        var recordId: String? = null
        try {
            assumeTrue("listener access could not be granted", repository.hasNotificationAccess())
            assertTrue(repository.setCollectionEnabled(true))
            assertTrue(repository.capture(syntheticSbn(), repository.captureEpoch()))
            val record = await(10_000) {
                repository.records.value.firstOrNull { it.packageName == SYNTHETIC_PACKAGE }
            }!!
            recordId = record.id
            val created = await(10_000) {
                store.load()
                store.tasks.value.filter { it.sourceRevisionId == record.id }.takeIf { it.isNotEmpty() }
            }
            assertNotNull(created)
            val groupKeys = created!!.flatMap { it.noticeGroupKeys + listOfNotNull(it.sourceNotificationId) }.toSet()

            // User deletes every task the notice produced: all its keys retire,
            // then stale journal entries pretend the source still needs work.
            created.forEach { store.delete(it.id) }
            assertTrue(ReconcileJournal.retiredKeys(context).isNotEmpty())
            groupKeys.forEach { ReconcileJournal.markPending(context, it) }
            // A ghost marker for a record that never committed.
            ReconcileJournal.markPending(context, "fp:ghost-without-record")

            AutoActionCoordinator.replayPending(context)
            store.load()
            assertTrue("deleted tasks must not be resurrected",
                store.tasks.value.none { it.sourceRevisionId == record.id })
            assertTrue("every stale/ghost marker must drop",
                ReconcileJournal.pending(context).isEmpty())

            AssistantAlertNotifier.cancel(context, record)
        } finally {
            recordId?.let { assertTrue("record cleanup failed", repository.deleteRecord(it)) }
            DeviceQaSafety.restoreQaListenerAccess(context, restore)
        }
    }

    /**
     * While consent is withdrawn the replay gate stays closed: pending markers
     * wait instead of recreating automation. Re-consenting reopens the gate
     * and the reconcile then completes once.
     */
    @Test
    fun withdrawnConsentKeepsReplayClosedUntilConsentReturns() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "withdrawnConsentKeepsReplayClosedUntilConsentReturns")
        seedRepository()
        val restore = DeviceQaSafety.grantQaListenerAccess(context, instrumentation, repository)
        var recordId: String? = null
        var taskId: String? = null
        try {
            assumeTrue("listener access could not be granted", repository.hasNotificationAccess())
            assertTrue(repository.setCollectionEnabled(true))
            AssistantTaskStore.saveInterceptor = { throw IOException("injected save failure") }
            assertTrue(try {
                repository.capture(syntheticSbn(), repository.captureEpoch())
            } finally {
                AssistantTaskStore.saveInterceptor = null
            })
            val record = await(10_000) {
                repository.records.value.firstOrNull { it.packageName == SYNTHETIC_PACKAGE }
            }!!
            recordId = record.id

            assertTrue(repository.beginReset())
            AutoActionCoordinator.replayPending(context)
            // The store refuses to load while consent is withdrawn; the live
            // task list was already empty after the injected save failure.
            assertTrue("replay must not recreate tasks while consent is withdrawn",
                store.tasks.value.isEmpty())
            assertTrue("pending markers wait for consent to return",
                ReconcileJournal.pending(context).isNotEmpty())

            assertTrue(repository.acceptConsent())
            assertTrue(repository.deferSetup())
            AutoActionCoordinator.replayPending(context)
            store.load()
            val recreated = store.tasks.value.count { it.sourceRevisionId == record.id }
            assertTrue("re-consent replays the reconcile", recreated >= 1)
            taskId = store.tasks.value.first { it.sourceRevisionId == record.id }.id
            AutoActionCoordinator.replayPending(context)
            store.load()
            assertEquals("the replayed reconcile does not duplicate",
                recreated, store.tasks.value.count { it.sourceRevisionId == record.id })

            AssistantAlertNotifier.cancel(context, record)
        } finally {
            runCatching { repository.acceptConsent(); repository.deferSetup() }
            taskId?.let {
                runCatching {
                    store.load()
                    store.tasks.value.filter { task -> task.sourceRevisionId == recordId }
                        .forEach { task -> store.delete(task.id) }
                }
            }
            recordId?.let { repository.deleteRecord(it) }
            DeviceQaSafety.restoreQaListenerAccess(context, restore)
        }
    }

    /**
     * A journal failure mid-ingest rolls the record batch back: the receipt
     * reports the storage error, no record persists, and the marker committed
     * for an earlier item becomes a ghost that replay drops.
     */
    @Test
    fun ingestJournalFailureRollsBackBatchAndGhostMarkersDrop() = runBlocking {
        DeviceQaSafety.requireDestructibleState(context, "ingestJournalFailureRollsBackBatchAndGhostMarkersDrop")
        seedRepository()
        val stateStore = SourceSyncStateStore.get(context)
        assertTrue(stateStore.reset())
        val scope = SourceScopeFactory.scopeFor(context, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
        assertNotNull(scope)
        val result = ingestResult(
            ingestNotice("dur-item-a", "rev-a"),
            ingestNotice("dur-item-b", "rev-b"),
        )

        val calls = AtomicInteger(0)
        val previous = ReconcileJournal.committer
        // First journal commit (item-a) succeeds; the second fails, rolling
        // the whole transaction back and leaving item-a's marker orphaned.
        ReconcileJournal.committer = { editor -> if (calls.incrementAndGet() <= 1) editor.commit() else false }
        val receipt = try {
            repository.ingestSource(scope!!, result)
        } finally {
            ReconcileJournal.committer = previous
        }

        assertEquals(SourceSyncStatus.ERROR, receipt.status)
        assertTrue(repository.records.value.isEmpty())
        assertTrue("the rolled-back item's marker survives as a ghost",
            ReconcileJournal.pending(context).isNotEmpty())

        AutoActionCoordinator.replayPending(context)
        assertTrue("ghost markers drop once replay sees no record",
            ReconcileJournal.pending(context).isEmpty())
        assertTrue(store.tasks.value.isEmpty())
    }

    private suspend fun seedRepository() {
        assertTrue(repository.deleteAll())
        AssistantTaskStore.reset(context)
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.saveSourceSelection(setOf(SYNTHETIC_PACKAGE)))
        assertTrue(repository.deferSetup())
    }

    @Suppress("DEPRECATION") // StatusBarNotification's public constructor is the only test seam.
    private fun syntheticSbn(): StatusBarNotification {
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "내구성 검증 공지")
            extras.putString(Notification.EXTRA_TEXT, "준비물: 실내화. 모레까지 제출해 주세요")
        }
        return StatusBarNotification(
            SYNTHETIC_PACKAGE, SYNTHETIC_PACKAGE, 77, "dur-tag", 30_077, 0,
            0, notification, android.os.Process.myUserHandle(), System.currentTimeMillis(),
        )
    }

    private fun ingestResult(vararg items: kr.mom.probe.sync.FetchedNotice) = SourceFetchResult(
        sourceId = items.first().sourceId,
        status = SourceSyncStatus.FETCHED,
        fetchedAt = System.currentTimeMillis(),
        items = items.toList(),
        coverage = SourceCoverageWindow(pageStart = 1, pageEnd = 1, complete = true),
    )

    private fun ingestNotice(itemId: String, revisionHash: String) =
        kr.mom.probe.sync.FetchedNotice(
            sourceId = SourceIds.SCHOOL_WEBSITE,
            itemId = itemId,
            revisionHash = revisionHash,
            firstSeenAt = System.currentTimeMillis(),
            publishedAt = System.currentTimeMillis(),
            title = "QA 학교 일정",
            body = "QA 검증용 초등 2학년 학교 일정입니다.",
            origin = kr.mom.probe.sync.SourceOrigin(
                "https://snjj-e.goesn.kr/snjj-e/qa/$itemId", "snjj-e.goesn.kr", "qa", itemId,
            ),
            contentState = kr.mom.probe.data.NoticeContentState.VERIFIED,
            obligation = kr.mom.probe.data.NoticeObligation.INFORMATIONAL,
            audienceFacts = listOf(kr.mom.probe.sync.SourceAudienceFact(
                applicability = kr.mom.probe.data.NoticeApplicability.APPLIES,
                schoolLevel = SchoolLevel.ELEMENTARY,
                gradeStart = 2,
                gradeEnd = 2,
                evidence = kr.mom.probe.sync.SourceEvidence("대상", "초등 2학년"),
            )),
            dateFacts = listOf(kr.mom.probe.sync.SourceDateFact(
                kr.mom.probe.data.NoticeDateRole.EVENT,
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
                evidence = kr.mom.probe.sync.SourceEvidence("일정", "내일"),
            )),
            evidence = listOf(kr.mom.probe.sync.SourceEvidence("qa", "synthetic test fixture")),
        )

    private suspend fun <T> await(timeoutMs: Long, probe: suspend () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            kotlinx.coroutines.delay(400)
        }
        return probe()
    }

    private companion object {
        const val SYNTHETIC_PACKAGE = "kr.mom.probe.qa.synthetic"
    }
}
