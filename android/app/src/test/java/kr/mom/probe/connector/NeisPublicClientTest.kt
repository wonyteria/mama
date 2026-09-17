package kr.mom.probe.connector

import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.CanonicalSchoolScope
import kr.mom.probe.sync.ChildSourceScope
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeisPublicClientTest {
    @Test fun missingApiKeyIsPartialAndDoesNotClaimEmptySuccess() = runBlocking {
        val result = NeisPublicClient(apiKey = "", nowProvider = { 1_779_900_000_000L }).fetch(scope(), null)

        assertEquals(SourceSyncStatus.PARTIAL, result.status)
        assertTrue(result.items.isEmpty())
        assertTrue(result.issues.any { it.code == SourceIssueCode.MISSING_API_KEY })
        assertTrue(result.issues.any { it.code == SourceIssueCode.SAMPLE_LIMITED })
        assertEquals(false, result.coverage.complete)
    }

    @Test fun gradeFlagParserKeepsUnknownWhenOfficialFieldsAreAbsent() {
        val fact = neisAudienceForGradeForTest(JSONObject("""{"AA_YMD":"20260915","EVENT_NM":"행사"}"""), 2)

        assertEquals(kr.mom.probe.data.NoticeApplicability.UNKNOWN, fact.applicability)
    }

    @Test fun gradeFlagFactsPreserveOtherGradesForFutureReevaluation() {
        val facts = neisAudienceFactsForGradeFlagsForTest(
            JSONObject("""{"TW_GRADE_EVENT_YN":"N","SIX_GRADE_EVENT_YN":"Y"}"""),
            childGrade = 2,
        )

        assertTrue(facts.any {
            it.gradeStart == 2 && it.gradeEnd == 2 && it.applicability == kr.mom.probe.data.NoticeApplicability.INELIGIBLE
        })
        assertTrue(facts.any {
            it.gradeStart == 6 && it.gradeEnd == 6 && it.applicability == kr.mom.probe.data.NoticeApplicability.APPLIES
        })
    }

    private fun scope() = SourceScope(
        sourceId = SourceIds.NEIS_PUBLIC,
        kind = SourceKind.NEIS_PUBLIC,
        school = CanonicalSchoolScope("성남정자초등학교", officeCode = "J10", schoolCode = "7530167"),
        child = ChildSourceScope(schoolLevel = SchoolLevel.ELEMENTARY, grade = 2),
        connectionGeneration = 1L,
        consentEpoch = 1L,
        consentVersion = "test",
        coverageWindow = SourceCoverageWindow(fromDateIso = "2026-09-15", toDateIso = "2026-10-15"),
        trigger = SourceRunTrigger.MANUAL,
    )
}
