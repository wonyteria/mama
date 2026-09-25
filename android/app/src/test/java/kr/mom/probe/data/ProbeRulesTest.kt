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

    @Test fun `original hides only for opted in app with a posted unified alert`() {
        val hidden = valid.copy(hiddenSourcePackages = setOf("school.app"))
        fun hide(settings: ProbeSettings = hidden, pkg: String = "school.app",
                 category: String? = null, ongoing: Boolean = false, summary: Boolean = false,
                 clearable: Boolean = true, title: String = "학교 공지", text: String = "준비물 안내",
                 posted: Boolean = true, access: Boolean = true) =
            ProbeRules.canHideOriginal(settings, pkg, "kr.mom.probe", category, ongoing, summary,
                clearable, title, text, posted, access)

        assertTrue(hide())
        // Default is keep-original: hiding requires per-app opt-in.
        assertFalse(hide(valid))
        assertFalse(hide(hidden.copy(hiddenSourcePackages = setOf("other.app"))))
        assertFalse(hide(hidden.copy(selectedPackages = emptySet())))
        // Unified alert must actually be posted before the original can go away.
        assertFalse(hide(posted = false))
        // Listener access revoked means we can no longer observe: keep everything.
        assertFalse(hide(access = false))
        // Foreground-service, group-summary and non-clearable notifications stay.
        assertFalse(hide(ongoing = true))
        assertFalse(hide(summary = true))
        assertFalse(hide(clearable = false))
        // Our own notifications are never candidates.
        assertFalse(hide(pkg = "kr.mom.probe"))
    }

    @Test fun `calls messages and sensitive content are never hidden`() {
        val hidden = valid.copy(hiddenSourcePackages = setOf("school.app"))
        fun hide(category: String? = null, title: String = "학교 공지", text: String = "안내") =
            ProbeRules.canHideOriginal(hidden, "school.app", "kr.mom.probe", category,
                false, false, true, title, text, true, true)

        listOf(
            android.app.Notification.CATEGORY_CALL,
            android.app.Notification.CATEGORY_MISSED_CALL,
            android.app.Notification.CATEGORY_MESSAGE,
            android.app.Notification.CATEGORY_EMAIL,
            android.app.Notification.CATEGORY_SERVICE,
            android.app.Notification.CATEGORY_SYSTEM,
            android.app.Notification.CATEGORY_PROGRESS,
            android.app.Notification.CATEGORY_ALARM,
            android.app.Notification.CATEGORY_STATUS,
            android.app.Notification.CATEGORY_NAVIGATION,
            android.app.Notification.CATEGORY_TRANSPORT,
            android.app.Notification.CATEGORY_ERROR,
        ).forEach { assertFalse("category $it", hide(it)) }

        assertFalse(hide(title = "인증번호 123456"))
        assertFalse(hide(text = "결제 완료되었습니다"))
        assertFalse(hide(title = "OTP 004512"))
        assertFalse(hide(text = "비밀번호가 변경되었습니다"))
        assertFalse(hide(title = "보안 경고"))

        assertTrue(hide(title = "방과후 안내", text = "준비물을 확인해주세요"))
    }

    @Test fun `retention expires exactly fourteen days after receipt`() {
        val received = 1_000L
        assertFalse(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS - 1))
        assertTrue(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS))
        assertTrue(ProbeRules.isExpired(received, received + ProbeRules.RETENTION_MS + 1))
    }
}
