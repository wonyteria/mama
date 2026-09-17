package kr.mom.probe.data

import org.junit.Assert.*
import org.junit.Test

class ProbeRulesTest {
    private val valid = ProbeSettings(
        consent = true, childName = "지우", schoolName = "테스트초등학교", schoolGrade = 2,
        selectedPackages = setOf("school.app"), collectionEnabled = true, onboardingDone = true,
        consentAt = 1, consentVersion = ProbeRules.CONSENT_VERSION,
    )

    private fun allowed(settings: ProbeSettings = valid, pkg: String = "school.app", access: Boolean = true,
                        ongoing: Boolean = false, summary: Boolean = false) =
        ProbeRules.canCapture(settings, pkg, "kr.mom.probe", access, ongoing, summary)

    @Test fun `collection requires every gate and excludes unselected packages`() {
        assertTrue(allowed())
        assertFalse(allowed(valid.copy(consent = false)))
        assertFalse(allowed(valid.copy(consentVersion = "old")))
        assertFalse(allowed(valid.copy(childName = " ")))
        assertFalse(allowed(valid.copy(collectionEnabled = false)))
        assertFalse(allowed(valid.copy(onboardingDone = false)))
        assertFalse(allowed(access = false))
        assertFalse(allowed(pkg = "unselected.app"))
        assertFalse(allowed(valid.copy(selectedPackages = setOf("kr.mom.probe")), pkg = "kr.mom.probe"))
        assertFalse(allowed(ongoing = true))
        assertFalse(allowed(summary = true))
    }

    @Test fun `same notification revision is stable but updates and later posts differ`() {
        val first = ProbeRules.revisionId("school.app", "key", 1, "old")
        assertEquals(first, ProbeRules.revisionId("school.app", "key", 1, "old"))
        assertNotEquals(first, ProbeRules.revisionId("school.app", "key", 1, "updated"))
        assertNotEquals(first, ProbeRules.revisionId("school.app", "key", 2, "old"))
        assertNotEquals(ProbeRules.revisionId("ab", "c", 1, "x"), ProbeRules.revisionId("a", "bc", 1, "x"))
        assertEquals(ProbeRules.notificationIdentity("school.app", "key"), ProbeRules.notificationIdentity("school.app", "key"))
        assertNotEquals(ProbeRules.notificationIdentity("school.app", "key"), ProbeRules.notificationIdentity("school.app", "other"))
    }

    @Test fun `retention expires exactly fourteen days after receipt`() {
        val received = 1_000L
        assertFalse(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS - 1))
        assertTrue(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS))
        assertTrue(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS + 1))
    }
}
