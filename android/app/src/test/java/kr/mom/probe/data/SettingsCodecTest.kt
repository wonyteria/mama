package kr.mom.probe.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test fun `hidden source packages survive encode and decode`() {
        val settings = ProbeSettings(
            consent = true, childName = "지우", schoolName = "테스트초등학교", schoolGrade = 2,
            schoolLevel = SchoolLevel.ELEMENTARY,
            selectedPackages = setOf("school.app", "ealimi.app"),
            hiddenSourcePackages = setOf("school.app"),
            collectionEnabled = true, onboardingDone = true,
            consentAt = 5, consentVersion = ProbeRules.CONSENT_VERSION,
        )
        assertEquals(settings, decodeSettings(encodeSettings(settings)))
    }

    @Test fun `settings stored before the hide option decode with originals kept`() {
        // Reboot/upgrade persistence: older payloads have no hiddenSourcePackages
        // key and must default to keeping every original notification.
        val legacy = JSONObject()
            .put("consent", true)
            .put("childName", "지우")
            .put("selectedPackages", JSONArray(listOf("school.app")))
            .put("collectionEnabled", true)
            .put("onboardingDone", true)
            .put("consentAt", 5)
            .put("consentVersion", ProbeRules.CONSENT_VERSION)
            .toString()
        val decoded = decodeSettings(legacy)
        assertTrue(decoded.hiddenSourcePackages.isEmpty())
        assertEquals(setOf("school.app"), decoded.selectedPackages)
        assertTrue(decoded.onboardingDone)
    }
}
