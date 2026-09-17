package kr.mom.probe.data

import kr.mom.probe.sync.RecordSourceMetadata

data class ProbeSettings(
    val consent: Boolean = false,
    val childName: String = "",
    val schoolName: String = "",
    val schoolGrade: Int? = null,
    val schoolLevel: SchoolLevel? = null,
    val selectedPackages: Set<String> = emptySet(),
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

    fun canCapture(settings: ProbeSettings, packageName: String, ownPackage: String,
                   hasAccess: Boolean, ongoing: Boolean, groupSummary: Boolean): Boolean =
        settings.consent && settings.consentVersion == CONSENT_VERSION &&
            settings.collectionEnabled && settings.childName.isNotBlank() &&
            settings.onboardingDone && hasAccess && packageName != ownPackage &&
            packageName in settings.selectedPackages && !ongoing && !groupSummary

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
