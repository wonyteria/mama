package kr.mom.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceStatusPresentationTest {
    @Test fun authenticationRemainsVisibleAfterTheConnectionLeavesTheActiveScopeList() {
        val auth = SourceSyncSnapshot("ealimi-web", SourceKind.EALIMI_WEB, status = SourceSyncStatus.AUTH_REQUIRED)
        val partial = SourceSyncSnapshot("school-web", SourceKind.SCHOOL_WEBSITE, status = SourceSyncStatus.PARTIAL)
        assertEquals("로그인이 필요한 출처가 있어요", SourceStatusPresentation.message(listOf(partial, auth)))
    }

    @Test fun healthyOrNeverStartedSourcesDoNotAddAWarning() {
        val healthy = SourceSyncSnapshot("school-web", SourceKind.SCHOOL_WEBSITE, status = SourceSyncStatus.FETCHED)
        assertNull(SourceStatusPresentation.message(listOf(healthy)))
        assertNull(SourceStatusPresentation.message(emptyList()))
    }
}
