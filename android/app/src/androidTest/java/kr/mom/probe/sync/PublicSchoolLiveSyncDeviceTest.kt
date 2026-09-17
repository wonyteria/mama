package kr.mom.probe.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kr.mom.probe.connector.PublicSourceFetchers
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production no-push school source against the public school website. */
@RunWith(AndroidJUnit4::class)
class PublicSchoolLiveSyncDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun productionWorkerFetchesStoresAndExcludesEveryExplicitlyIneligibleNoticeForGradeTwo() = runBlocking {
        check(context.packageName.endsWith(".qa")) { "Live source QA must never target the release app." }
        val repository = ProbeRepository.get(context)
        repository.isReady.first { it }
        assertTrue(repository.deleteAll())
        assertTrue(SourceSyncStateStore.get(context).reset())
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.deferSetup())
        assertFalse(repository.settings.value.collectionEnabled)

        PublicSourceFetchers.registerDefaults()
        SourceSyncScheduler.cancel(context, SourceIds.SCHOOL_WEBSITE)
        SourceSyncScheduler.enqueue(context, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
        val snapshot = withTimeout(120_000L) {
            SourceSyncStateStore.get(context).snapshotsFlow.first { snapshots ->
                snapshots[SourceIds.SCHOOL_WEBSITE]?.status?.let {
                    it != SourceSyncStatus.NEVER && it != SourceSyncStatus.RUNNING
                } == true
            }[SourceIds.SCHOOL_WEBSITE]
        }
        val sourceRecords = withTimeout(30_000L) {
            repository.records.first { records ->
                records.any { it.sourceMetadata?.sourceId == SourceIds.SCHOOL_WEBSITE }
            }
        }.filter { it.sourceMetadata?.sourceId == SourceIds.SCHOOL_WEBSITE }

        assertNotNull("The live worker did not persist a source status.", snapshot)
        assertTrue(snapshot?.status in setOf(SourceSyncStatus.FETCHED, SourceSyncStatus.PARTIAL))
        assertTrue("The live public source returned no stored records.", sourceRecords.isNotEmpty())
        val scope = SourceScopeFactory.scopeFor(context, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
        assertNotNull(scope)
        val active = SourceRecordSelectors.activeRecords(sourceRecords, listOf(scope!!))
        assertFalse(active.any { record ->
            SourceAudienceEvaluator.evaluate(
                record.sourceMetadata!!.audienceFacts,
                SchoolLevel.ELEMENTARY,
                2,
            ).applicability == NoticeApplicability.INELIGIBLE
        })
        val child = NoticeDecisionEngine.childProfile(repository.settings.value)
        val agenda = SourceRecordSelectors.agenda(sourceRecords, child, listOf(scope))
        assertFalse(agenda.any { item ->
            SourceAudienceEvaluator.evaluate(
                item.record.sourceMetadata!!.audienceFacts,
                SchoolLevel.ELEMENTARY,
                2,
            ).applicability == NoticeApplicability.INELIGIBLE
        })
        if (snapshot!!.status == SourceSyncStatus.PARTIAL) assertNull(snapshot.lastCompleteAt)
        val periodic = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("source-sync-periodic-${SourceIds.SCHOOL_WEBSITE}")
            .get(10, TimeUnit.SECONDS)
        assertTrue("The six-hour school source work was not registered.", periodic.isNotEmpty())
    }
}
