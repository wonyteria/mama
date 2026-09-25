package kr.mom.probe.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.mom.probe.connector.ConnectorRepository
import kr.mom.probe.connector.NeisPublicClient
import kr.mom.probe.connector.PublicSourceFetchers
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the production NEIS lane remains disabled until a secure proxy exists. */
@RunWith(AndroidJUnit4::class)
class NeisLiveSyncDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keylessProductionSyncStaysDisabledUntilProxyExists() = runBlocking {
        check(context.packageName.endsWith(".qa")) { "Source QA must never target the release app." }
        val repository = ProbeRepository.get(context)
        repository.isReady.first { it }
        assertTrue(repository.deleteAll())
        assertTrue(SourceSyncStateStore.get(context).reset())
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.deferSetup())

        val connectors = ConnectorRepository.get(context)
        assertTrue(connectors.markConnected(SourceIds.NEIS_PUBLIC, mapOf(
            "officeCode" to "J10",
            "schoolCode" to "TEST",
            "schoolName" to "성남정자초등학교",
            "level" to "ELEMENTARY",
        )))

        PublicSourceFetchers.registerDefaults()
        val disposition = SourceSyncRunner(context).run(SourceIds.NEIS_PUBLIC, SourceRunTrigger.MANUAL)
        val snapshot = SourceSyncStateStore.get(context).snapshot(SourceIds.NEIS_PUBLIC)

        assertFalse(NeisPublicClient.PRODUCTION_SYNC_ENABLED)
        assertEquals(SourceSyncRunDisposition.SUCCESS, disposition)
        assertNull("Disabled NEIS must not persist a misleading source status.", snapshot)
        val neisRecords = repository.records.value.filter { it.sourceMetadata?.sourceId == SourceIds.NEIS_PUBLIC }
        assertTrue("Disabled NEIS must not ingest records.", neisRecords.isEmpty())
        val scope = SourceScopeFactory.scopeFor(context, SourceIds.NEIS_PUBLIC, SourceRunTrigger.MANUAL)
        assertNull("Disabled NEIS must not create a production source scope.", scope)
    }
}
