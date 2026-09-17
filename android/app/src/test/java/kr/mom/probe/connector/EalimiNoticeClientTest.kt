package kr.mom.probe.connector

import kotlinx.coroutines.runBlocking
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.CanonicalSchoolScope
import kr.mom.probe.sync.ChildSourceScope
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EalimiNoticeClientTest {
    @Test fun rendererLoginPageStopsAsAuthRequired() = runBlocking {
        val client = EalimiNoticeClient(FakeRenderer(EalimiRenderedPage.Success(
            "https://www.ealimi.com/Member/SignIn",
            JSONObject().put("contractVersion", EalimiDomReader.CONTRACT_VERSION)
                .put("url", "https://www.ealimi.com/Member/SignIn")
                .put("loginForm", true)
                .toString(),
        )), clock = { 20L })

        val result = client.fetch(scope(), null)

        assertEquals(SourceSyncStatus.AUTH_REQUIRED, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test fun blockedNavigationDoesNotBecomeAuthenticated() = runBlocking {
        val client = EalimiNoticeClient(FakeRenderer(EalimiRenderedPage.BlockedNavigation("https://example.com/")), clock = { 20L })

        val result = client.fetch(scope(), null)

        assertEquals(SourceSyncStatus.ERROR, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test fun invalidScopeFailsBeforeRendering() = runBlocking {
        val renderer = FakeRenderer(EalimiRenderedPage.Timeout)
        val client = EalimiNoticeClient(renderer, clock = { 20L })

        val result = client.fetch(scope().copy(kind = SourceKind.SCHOOL_WEBSITE), null)

        assertEquals(SourceSyncStatus.ERROR, result.status)
        assertEquals(0, renderer.calls)
    }

    private class FakeRenderer(private val result: EalimiRenderedPage) : EalimiPageRenderer {
        var calls: Int = 0
            private set

        override suspend fun render(definition: SiteDefinition, url: String, script: String, timeoutMs: Long): EalimiRenderedPage {
            calls += 1
            assertEquals(SourceIds.EALIMI_WEB, definition.id)
            assertTrue(script.contains(EalimiDomReader.CONTRACT_VERSION))
            return result
        }
    }

    private fun scope(): SourceScope = SourceScope(
        sourceId = SourceIds.EALIMI_WEB,
        kind = SourceKind.EALIMI_WEB,
        school = CanonicalSchoolScope(schoolName = "성남정자초등학교"),
        child = ChildSourceScope(schoolLevel = SchoolLevel.ELEMENTARY, grade = 2),
        connectionGeneration = 1L,
        consentEpoch = 1L,
        consentVersion = "test",
        coverageWindow = SourceCoverageWindow(),
        trigger = SourceRunTrigger.MANUAL,
    )
}
