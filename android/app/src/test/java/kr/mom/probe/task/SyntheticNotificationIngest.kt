package kr.mom.probe.task

import kr.mom.probe.data.NotificationRecordAssembler
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSettings

/**
 * Test-only synthetic notification intake. It sits upstream of the real
 * capture boundary — the allowlist/filter gate ([ProbeRules.canCapture])
 * and the real extras normalizer ([NotificationRecordAssembler]) — but
 * never touches StatusBarNotification, the listener service, the
 * notification tray, or the DAO. No real user notification is ever read,
 * dumped, or cancelled here.
 */
object SyntheticNotificationIngest {

    fun accept(
        input: NotificationRecordAssembler.Input,
        settings: ProbeSettings,
        ownPackage: String = "kr.mom.probe.qa",
        hasAccess: Boolean = true,
        receivedAt: Long = input.postTime,
    ): ProbeRecord? {
        if (!ProbeRules.canCapture(
                settings, input.packageName, ownPackage,
                hasAccess, input.ongoing, input.groupSummary,
            )
        ) {
            return null
        }
        return NotificationRecordAssembler.assemble(input, receivedAt)
    }

    /** Consent-complete settings with the given packages allowlisted. */
    fun settingsFor(vararg packages: String): ProbeSettings = ProbeSettings(
        consent = true,
        childName = "테스트아이",
        schoolName = "성남정자초등학교",
        selectedPackages = packages.toSet(),
        collectionEnabled = true,
        onboardingDone = true,
        consentVersion = ProbeRules.CONSENT_VERSION,
    )

    fun notification(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        key: String,
        postTime: Long,
        bigText: String = "",
        textLines: List<String> = emptyList(),
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ): NotificationRecordAssembler.Input = NotificationRecordAssembler.Input(
        packageName = packageName,
        key = key,
        postTime = postTime,
        notificationId = 1,
        ongoing = ongoing,
        groupSummary = groupSummary,
        appLabel = appLabel,
        title = title,
        text = text,
        bigText = bigText,
        textLines = textLines,
    )
}
