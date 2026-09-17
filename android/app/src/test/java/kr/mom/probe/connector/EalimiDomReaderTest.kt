package kr.mom.probe.connector

import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.CanonicalSchoolScope
import kr.mom.probe.sync.ChildSourceScope
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EalimiDomReaderTest {
    @Test fun loginPageReturnsAuthRequired() {
        val payload = JSONObject()
            .put("contractVersion", EalimiDomReader.CONTRACT_VERSION)
            .put("url", "https://www.ealimi.com/Member/SignIn")
            .put("loginForm", true)
            .toString()

        val result = EalimiDomReader.parse("https://www.ealimi.com/Member/SignIn", payload, scope(), 10L)

        assertEquals(SourceSyncStatus.AUTH_REQUIRED, result.status)
        assertTrue(result.issues.any { it.code.name == "AUTH_EXPIRED" })
        assertTrue(result.items.isEmpty())
    }

    @Test fun pendingAuthenticatedContractFailsClosedAsUnsupported() {
        val payload = JSONObject()
            .put("contractVersion", EalimiDomReader.CONTRACT_VERSION)
            .put("url", "https://www.ealimi.com/Home")
            .put("unsupported", true)
            .put("issue", "authenticated_notice_dom_contract_pending")
            .toString()

        val result = EalimiDomReader.parse("https://www.ealimi.com/Home", payload, scope(), 10L)

        assertEquals(SourceSyncStatus.UNSUPPORTED, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test fun verifiedAuthenticatedNoticeRequiresMatchingSchoolAndGrade() {
        val notice = JSONObject()
            .put("id", "notice-1")
            .put("title", "2학년 현장체험 안내")
            .put("body", "2학년 보호자에게 전달된 안내입니다.")
            .put("detailUrl", "https://www.ealimi.com/Notice/Detail/notice-1")
            .put("detailRead", true)
            .put("publishedText", "2026.09.15")
            .put("publishedAt", 1_778_880_000_000L)
            .put("attachments", JSONArray().put(JSONObject()
                .put("title", "가정통신문.pdf")
                .put("url", "https://www.ealimi.com/Attachment/notice-1")))
        val payload = JSONObject()
            .put("contractVersion", EalimiDomReader.CONTRACT_VERSION)
            .put("url", "https://www.ealimi.com/Notice")
            .put("authenticated", true)
            .put("listRead", true)
            .put("school", JSONObject().put("name", "성남정자초등학교").put("level", "ELEMENTARY"))
            .put("child", JSONObject().put("grade", 2))
            .put("notices", JSONArray().put(notice))
            .toString()

        val result = EalimiDomReader.parse("https://www.ealimi.com/Notice", payload, scope(), 10L)

        assertEquals(SourceSyncStatus.FETCHED, result.status)
        assertEquals(1, result.items.size)
        assertEquals("notice-1", result.items.single().itemId)
        assertEquals(1, result.items.single().attachments.size)
        assertEquals(true, result.coverage.complete)
    }

    @Test fun mismatchedChildScopeIsNotStored() {
        val payload = JSONObject()
            .put("contractVersion", EalimiDomReader.CONTRACT_VERSION)
            .put("url", "https://www.ealimi.com/Notice")
            .put("authenticated", true)
            .put("listRead", true)
            .put("school", JSONObject().put("name", "성남정자초등학교").put("level", "ELEMENTARY"))
            .put("child", JSONObject().put("grade", 3))
            .put("notices", JSONArray())
            .toString()

        val result = EalimiDomReader.parse("https://www.ealimi.com/Notice", payload, scope(), 10L)

        assertEquals(SourceSyncStatus.ERROR, result.status)
        assertTrue(result.items.isEmpty())
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
