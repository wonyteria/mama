package kr.mom.probe.data

import android.app.Notification
import kr.mom.probe.sync.RecordSourceMetadata

data class ProbeSettings(
    val consent: Boolean = false,
    val childName: String = "",
    val schoolName: String = "",
    val schoolGrade: Int? = null,
    val schoolLevel: SchoolLevel? = null,
    val selectedPackages: Set<String> = emptySet(),
    val hiddenSourcePackages: Set<String> = emptySet(),
    val collectionEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val consentAt: Long? = null,
    val consentVersion: String? = null,
)

data class ProbeRecord(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val postedAt: Long,
    val receivedAt: Long,
    val title: String,
    val text: String,
    val bigText: String,
    val textLines: List<String>,
    val subText: String?,
    val summaryText: String?,
    val category: String?,
    val channelId: String?,
    val notificationId: Int,
    val notificationKey: String,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val rawHash: String,
    val truncated: Boolean = false,
    val sourceMetadata: RecordSourceMetadata? = null,
)

object ProbeRules {
    const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    const val CONSENT_VERSION = "probe-local-agent-reminders-4"

    private val neverHideCategories = setOf(
        Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL,
        Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL,
        Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_PROGRESS, Notification.CATEGORY_ALARM,
        Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_ERROR,
    )
    private val sensitiveNotificationText =
        Regex("인증|otp|결제|비밀번호|보안", RegexOption.IGNORE_CASE)

    fun canCapture(settings: ProbeSettings, packageName: String, ownPackage: String,
                   hasAccess: Boolean, ongoing: Boolean, groupSummary: Boolean): Boolean =
        settings.consent && settings.consentVersion == CONSENT_VERSION &&
            settings.collectionEnabled && settings.childName.isNotBlank() &&
            settings.onboardingDone && hasAccess && packageName != ownPackage &&
            packageName in settings.selectedPackages && !ongoing && !groupSummary

    /**
     * The original notification may be cancelled only when every gate holds at once:
     * the unified alert was actually posted, the user opted this app into hiding,
     * live listener access still exists, and the notification itself is safe to
     * remove. Calls, messages, OTP/payment/security content and foreground-service
     * or non-clearable notifications are never cancelled — any doubt keeps the
     * original visible.
     */
    fun canHideOriginal(settings: ProbeSettings, packageName: String, ownPackage: String,
                        category: String?, ongoing: Boolean, groupSummary: Boolean,
                        clearable: Boolean, title: String, text: String,
                        unifiedAlertPosted: Boolean, hasAccess: Boolean): Boolean =
        hasAccess && unifiedAlertPosted && clearable &&
            packageName != ownPackage &&
            packageName in settings.selectedPackages &&
            packageName in settings.hiddenSourcePackages &&
            !ongoing && !groupSummary &&
            category !in neverHideCategories &&
            !sensitiveNotificationText.containsMatchIn("$title\n$text")

    fun isExpired(receivedAt: Long, now: Long): Boolean = receivedAt <= now - RETENTION_MS

    fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun revisionId(packageName: String, key: String, postedAt: Long, rawHash: String): String =
        digest(listOf(packageName, key, postedAt.toString(), rawHash).joinToString("\u0000"))

    fun notificationIdentity(packageName: String, key: String): String =
        digest(listOf(packageName, key).joinToString("\u0000"))

    fun sourceRevisionId(sourceId: String, itemId: String, revisionHash: String): String =
        digest(listOf("source", sourceId, itemId, revisionHash).joinToString("\u0000"))

    fun sourceItemIdentity(sourceId: String, itemId: String): String =
        digest(listOf("source-item", sourceId, itemId).joinToString("\u0000"))

    fun recordIdentity(record: ProbeRecord): String = record.sourceMetadata?.let {
        sourceItemIdentity(it.sourceId, it.itemId)
    } ?: notificationIdentity(record.packageName, record.notificationKey)

    fun sourceAuthorizationToken(settings: ProbeSettings): String =
        digest(listOf("source-authorization", (settings.consentAt ?: 0L).toString(), settings.consentVersion.orEmpty()).joinToString("\u0000"))
}
