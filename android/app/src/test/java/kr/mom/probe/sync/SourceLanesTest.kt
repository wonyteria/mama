package kr.mom.probe.sync

import kr.mom.probe.connector.ConnectionStatus
import kr.mom.probe.connector.ConnectorState
import kr.mom.probe.connector.SiteConnection
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanesTest {
    private val settings = ProbeSettings(
        consent = true,
        onboardingDone = true,
        schoolName = "성남정자초등학교",
        schoolGrade = 3,
        schoolLevel = SchoolLevel.ELEMENTARY,
    )

    @Test fun `installed app alone is never reported as connected`() {
        val lane = SourceLanes.notificationLane(
            "com.ewut.allealimi", "e알리미",
            installed = true,
            settings = settings.copy(selectedPackages = emptySet()),
            verifiedPackages = emptySet(),
            notificationAccess = true,
        )

        assertEquals(LaneHealth.NEEDS_SETUP, lane.health)
        assertFalse(lane.enabled)
        assertFalse(lane.statusText.contains("com."))
    }

    @Test fun `enabled app with access waits for first notification rather than claiming connection`() {
        val lane = SourceLanes.notificationLane(
            "com.ewut.allealimi", "e알리미",
            installed = true,
            settings = settings.copy(selectedPackages = setOf("com.ewut.allealimi")),
            verifiedPackages = emptySet(),
            notificationAccess = true,
        )

        assertEquals(LaneHealth.TEMPORARILY_UNCERTAIN, lane.health)
        assertEquals("첫 알림 기다리는 중", lane.statusText)
    }

    @Test fun `notification lane is ready only with capture evidence`() {
        val lane = SourceLanes.notificationLane(
            "com.ewut.allealimi", "e알리미",
            installed = true,
            settings = settings.copy(selectedPackages = setOf("com.ewut.allealimi")),
            verifiedPackages = setOf("com.ewut.allealimi"),
            notificationAccess = true,
        )

        assertEquals(LaneHealth.READY, lane.health)
    }

    @Test fun `preferred app mapping does not disable the web lane`() {
        val connectors = ConnectorState(
            sites = mapOf(
                SourceIds.EALIMI_WEB to SiteConnection(
                    id = SourceIds.EALIMI_WEB,
                    childId = "primary-child",
                    status = ConnectionStatus.SESSION_READY,
                ),
            ),
        )
        val withAppPreferred = SourceLanes.services(
            settings.copy(selectedPackages = setOf("com.ewut.allealimi")),
            connectors, emptyMap(),
            installedPackages = setOf("com.ewut.allealimi"),
            verifiedPackages = setOf("com.ewut.allealimi"),
            notificationAccess = true,
        )
        val withoutApp = SourceLanes.services(
            settings.copy(selectedPackages = emptySet()),
            connectors, emptyMap(),
            installedPackages = setOf("com.ewut.allealimi"),
            verifiedPackages = emptySet(),
            notificationAccess = true,
        )

        val webWithApp = withAppPreferred.first { it.name == "e알리미" }.lanes.single { it.name == "e알리미 웹" }
        val webWithoutApp = withoutApp.first { it.name == "e알리미" }.lanes.single { it.name == "e알리미 웹" }
        assertEquals(webWithoutApp.health, webWithApp.health)
        assertTrue(webWithApp.enabled)
        val notification = withAppPreferred.first { it.name == "e알리미" }
            .lanes.single { LaneCapability.NOTIFICATION_CAPTURE in it.capabilities }
        assertEquals(LaneHealth.READY, notification.health)
    }

    @Test fun `school website lane reports unsupported outside the verified institution`() {
        val lane = SourceLanes.schoolWebsiteLane(
            settings.copy(schoolName = "다른학교"),
            snapshot = null,
        )

        assertEquals(LaneHealth.UNSUPPORTED, lane.health)
        assertFalse(lane.enabled)
    }

    @Test fun `web lane without implemented adapter reports unsupported not connected`() {
        val lane = SourceLanes.webLane("e알리미 웹", SourceIds.EALIMI_WEB, ConnectorState(), null)

        assertEquals(LaneHealth.UNSUPPORTED, lane.health)
        assertFalse(lane.enabled)
        assertFalse(lane.statusText.contains(SourceIds.EALIMI_WEB))
    }

    @Test fun `keyless NEIS lane is disabled until a secure proxy exists`() {
        val connectors = ConnectorState(
            sites = mapOf(
                SourceIds.NEIS_PUBLIC to SiteConnection(
                    id = SourceIds.NEIS_PUBLIC,
                    childId = "primary-child",
                    status = ConnectionStatus.CONNECTED,
                ),
            ),
        )

        val lane = SourceLanes.neisLane(connectors, snapshot = null, sampleMode = false)

        assertEquals(LaneHealth.UNSUPPORTED, lane.health)
        assertFalse(lane.enabled)
        assertTrue(lane.statusText.contains("자동 조회 미지원"))
    }

    @Test fun `lanes describe what each transport can actually do`() {
        val app = SourceLanes.notificationLane(
            "com.ewut.allealimi", "e알리미", installed = true,
            settings = settings.copy(selectedPackages = setOf("com.ewut.allealimi")),
            verifiedPackages = setOf("com.ewut.allealimi"), notificationAccess = true,
        )
        val web = SourceLanes.schoolWebsiteLane(settings, snapshot = null)
        val unsupported = SourceLanes.webLane("e알리미 웹", SourceIds.EALIMI_WEB, ConnectorState(), null)

        assertTrue(app.capabilityText.contains("알림"))
        assertTrue(web.capabilityText.contains("본문"))
        assertTrue(unsupported.capabilityText.contains("지원"))
        listOf(app, web, unsupported).forEach { lane ->
            assertFalse(lane.capabilityText.contains("com."))
            assertFalse(lane.capabilityText.contains("-web"))
        }
    }

    @Test fun `snapshot health shows the last successful check instead of implying live health`() {
        val fetched = SourceSyncSnapshot(
            sourceId = SourceIds.SCHOOL_WEBSITE, kind = SourceKind.SCHOOL_WEBSITE,
            status = SourceSyncStatus.FETCHED, storedCount = 3,
            lastSuccessAt = 1_718_586_000_000L, lastAttemptAt = 1_718_586_000_000L,
        )
        val text = SourceLanes.schoolWebsiteLane(settings, fetched).statusText

        assertTrue(text.contains("마지막 확인"))
        assertFalse(text.contains(SourceIds.SCHOOL_WEBSITE))
    }

    @Test fun `unsupported school website lane is still displayable with its own status`() {
        val lane = SourceLanes.schoolWebsiteLane(settings.copy(schoolName = "다른학교"), snapshot = null)

        assertEquals(LaneHealth.UNSUPPORTED, lane.health)
        assertFalse(lane.enabled)
        // The row must be renderable with this status rather than hidden silently.
        assertTrue(lane.statusText.isNotBlank())
    }

    @Test fun `no lane exposes internal identifiers in user-facing text`() {
        val connectors = ConnectorState(
            sites = mapOf(
                SourceIds.EALIMI_WEB to SiteConnection(
                    id = SourceIds.EALIMI_WEB, childId = "primary-child",
                    status = ConnectionStatus.SESSION_READY,
                ),
            ),
        )
        val services = SourceLanes.services(
            settings.copy(selectedPackages = setOf("com.ewut.allealimi")),
            connectors,
            snapshots = mapOf(
                SourceIds.SCHOOL_WEBSITE to SourceSyncSnapshot(
                    sourceId = SourceIds.SCHOOL_WEBSITE,
                    kind = SourceKind.SCHOOL_WEBSITE,
                    status = SourceSyncStatus.FETCHED,
                    storedCount = 4,
                ),
            ),
            installedPackages = setOf("com.ewut.allealimi"),
            verifiedPackages = setOf("com.ewut.allealimi"),
            notificationAccess = true,
        )

        services.flatMap { it.lanes }.forEach { lane ->
            assertFalse(lane.name, lane.statusText.contains("sourceId"))
            assertFalse(lane.name, lane.statusText.contains("com."))
            assertFalse(lane.name, lane.statusText.contains("-web"))
            assertFalse(lane.name, lane.statusText.contains("public"))
        }
    }
}
