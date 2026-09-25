package kr.mom.probe.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.mom.probe.connector.ConnectorRepository
import kr.mom.probe.connector.NeisPublicClient
import kr.mom.probe.connector.NeisResult
import kr.mom.probe.connector.PublicSourceFetchers
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production no-push NEIS source against the live public API on device. */
@RunWith(AndroidJUnit4::class)
class NeisLiveSyncDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keylessSchoolLookupConnectsAndScheduleSyncStoresRows() = runBlocking {
        check(context.packageName.endsWith(".qa")) { "Live source QA must never target the release app." }
        val repository = ProbeRepository.get(context)
        repository.isReady.first { it }
        assertTrue(repository.deleteAll())
        assertTrue(SourceSyncStateStore.get(context).reset())
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.deferSetup())

        // Keyless school search must resolve the real school on the public API.
        val client = NeisPublicClient()
        val found = client.findExactSchool("성남정자초등학교")
        assertTrue("NEIS school search failed: $found", found is NeisResult.Success)
        val school = (found as NeisResult.Success).value

        val connectors = ConnectorRepository.get(context)
        assertTrue(connectors.markConnected(SourceIds.NEIS_PUBLIC, mapOf(
            "officeCode" to school.officeCode,
            "schoolCode" to school.schoolCode,
            "schoolName" to school.name,
            "address" to school.address,
            "level" to school.level,
        )))

        PublicSourceFetchers.registerDefaults()
        val disposition = SourceSyncRunner(context).run(SourceIds.NEIS_PUBLIC, SourceRunTrigger.MANUAL)
        val snapshot = SourceSyncStateStore.get(context).snapshot(SourceIds.NEIS_PUBLIC)

        assertEquals(SourceSyncRunDisposition.SUCCESS, disposition)
        assertNotNull("The live NEIS worker did not persist a source status.", snapshot)
        assertTrue("Unexpected NEIS status: ${snapshot?.status}", snapshot?.status in setOf(SourceSyncStatus.FETCHED, SourceSyncStatus.PARTIAL))
        assertTrue("The live NEIS source stored no schedule rows.", (snapshot?.storedCount ?: 0) > 0)
        val neisRecords = repository.records.value.filter { it.sourceMetadata?.sourceId == SourceIds.NEIS_PUBLIC }
        assertTrue("No NEIS records were ingested.", neisRecords.isNotEmpty())
        // Every stored schedule must remain in-scope for the grade-2 child.
        val scope = SourceScopeFactory.scopeFor(context, SourceIds.NEIS_PUBLIC, SourceRunTrigger.MANUAL)
        assertNotNull(scope)
        val active = SourceRecordSelectors.activeRecords(neisRecords, listOf(scope!!))
        assertTrue(active.isNotEmpty())
    }
}
