package kr.mom.probe.sync

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.SchoolLevel

enum class SourceKind {
    ANDROID_NOTIFICATION,
    NEIS_PUBLIC,
    SCHOOL_WEBSITE,
    EALIMI_WEB,
}

enum class SourceSyncStatus {
    NEVER,
    RUNNING,
    FETCHED,
    SUCCESS_EMPTY,
    PARTIAL,
    AUTH_REQUIRED,
    UNSUPPORTED,
    OFFLINE,
    ERROR,
}

enum class SourceIssueCode {
    AUTH_EXPIRED,
    CHECKPOINT_NOT_COMMITTED,
    CONSENT_CHANGED,
    CONTENT_LIMIT_EXCEEDED,
    DETAIL_FETCH_FAILED,
    DOM_CONTRACT_MISSING,
    DOM_CONTRACT_CHANGED,
    HTTP_ERROR,
    INVALID_SCOPE,
    MISSING_API_KEY,
    NETWORK_UNAVAILABLE,
    PAGE_LIMIT_EXCEEDED,
    PARSE_ERROR,
    RESPONSE_LIMIT_EXCEEDED,
    SAMPLE_LIMITED,
    SCHEMA_ERROR,
    STORAGE_ERROR,
    UNSUPPORTED_ATTACHMENT,
    UNSUPPORTED_SOURCE,
}

enum class AttachmentFetchState {
    NONE,
    LINK_ONLY,
    FETCHED,
    MISSING,
    UNSUPPORTED,
    BLOCKED,
}

enum class SourceRunTrigger {
    CONNECTION_READY,
    MANUAL,
    PERIODIC,
    FOREGROUND_STALE,
    BRIEFING_STALE,
    POSTING_WINDOW,
}

enum class SourceRecordState {
    ACTIVE,
    REVISED,
    DELETED_TOMBSTONE,
    EXPIRED,
    OUT_OF_SCOPE,
}

data class CanonicalSchoolScope(
    val schoolName: String,
    val officeCode: String? = null,
    val schoolCode: String? = null,
    val officialHost: String? = null,
    val officialPathPrefix: String? = null,
)

data class ChildSourceScope(
    val profileId: String = "primary-child",
    val schoolLevel: SchoolLevel = SchoolLevel.UNKNOWN,
    val grade: Int? = null,
)

data class SourceCoverageWindow(
    val fromDateIso: String? = null,
    val toDateIso: String? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
    val totalCount: Int? = null,
    val complete: Boolean = false,
)

data class SourceScope(
    val sourceId: String,
    val kind: SourceKind,
    val school: CanonicalSchoolScope,
    val child: ChildSourceScope,
    val connectionGeneration: Long,
    val consentEpoch: Long,
    val authorizationToken: String = "",
    val consentVersion: String,
    val coverageWindow: SourceCoverageWindow,
    val trigger: SourceRunTrigger,
)

data class SourceCheckpoint(
    val cursor: String? = null,
    val coverageWindow: SourceCoverageWindow? = null,
    val lastFetchedAt: Long? = null,
    val sourceGeneration: Long = 0,
    val itemRevisionHashes: Map<String, String> = emptyMap(),
)

data class SourceEvidence(
    val label: String,
    val value: String,
)

data class SourceIssue(
    val code: SourceIssueCode,
    val message: String,
    val itemId: String? = null,
    val recoverable: Boolean = false,
)

data class SourceAudienceFact(
    val applicability: NoticeApplicability,
    val schoolLevel: SchoolLevel = SchoolLevel.UNKNOWN,
    val gradeStart: Int? = null,
    val gradeEnd: Int? = null,
    val schoolWide: Boolean = false,
    val evidence: SourceEvidence,
)

data class SourceDateFact(
    val role: NoticeDateRole,
    val text: String,
    val dateIso: String? = null,
    val preciseAt: Long? = null,
    val hasExplicitTime: Boolean = false,
    val evidence: SourceEvidence? = null,
)

data class SourceAttachment(
    val title: String,
    val url: String,
    val contentType: String? = null,
    val state: AttachmentFetchState,
)

data class SourceOrigin(
    val canonicalUrl: String,
    val host: String,
    val boardId: String? = null,
    val rawId: String? = null,
)

data class FetchedNotice(
    val sourceId: String,
    val itemId: String,
    val revisionHash: String,
    val firstSeenAt: Long,
    val publishedAt: Long? = null,
    val title: String,
    val body: String,
    val origin: SourceOrigin,
    val contentState: NoticeContentState,
    val obligation: NoticeObligation = NoticeObligation.INFORMATIONAL,
    val audienceFacts: List<SourceAudienceFact> = emptyList(),
    val dateFacts: List<SourceDateFact> = emptyList(),
    val attachments: List<SourceAttachment> = emptyList(),
    val evidence: List<SourceEvidence> = emptyList(),
    val issues: List<SourceIssue> = emptyList(),
)

data class SourceFetchResult(
    val sourceId: String,
    val status: SourceSyncStatus,
    val fetchedAt: Long,
    val items: List<FetchedNotice> = emptyList(),
    val coverage: SourceCoverageWindow,
    val checkpoint: SourceCheckpoint? = null,
    val evidence: List<SourceEvidence> = emptyList(),
    val issues: List<SourceIssue> = emptyList(),
) {
    val canCommitRecords: Boolean
        get() = when (status) {
            SourceSyncStatus.FETCHED -> items.isNotEmpty()
            SourceSyncStatus.PARTIAL -> items.isNotEmpty()
            SourceSyncStatus.SUCCESS_EMPTY -> coverage.complete
            else -> false
        }
}

interface SourceFetcher {
    suspend fun fetch(scope: SourceScope, checkpoint: SourceCheckpoint?): SourceFetchResult
}

data class IngestReceipt(
    val sourceId: String,
    val status: SourceSyncStatus,
    val attemptedAt: Long,
    val committedAt: Long? = null,
    val storedRevisionIds: List<String> = emptyList(),
    val insertedCount: Int = 0,
    val changedCount: Int = 0,
    val unchangedCount: Int = 0,
    val suppressedCount: Int = 0,
    val skippedCount: Int = 0,
    val issues: List<SourceIssue> = emptyList(),
)

interface SourceIngestor {
    suspend fun ingest(scope: SourceScope, result: SourceFetchResult): IngestReceipt
}

data class SourceSyncSnapshot(
    val sourceId: String,
    val kind: SourceKind,
    val authorizationToken: String = "",
    val status: SourceSyncStatus = SourceSyncStatus.NEVER,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastCompleteAt: Long? = null,
    val coverageWindow: SourceCoverageWindow? = null,
    val seenCount: Int = 0,
    val matchedCount: Int = 0,
    val storedCount: Int = 0,
    val attachmentState: AttachmentFetchState = AttachmentFetchState.NONE,
    val reasonCode: SourceIssueCode? = null,
    val message: String? = null,
    val checkpoint: SourceCheckpoint? = null,
    val sourceGeneration: Long = 0,
)

data class SourceConfig(
    val sourceId: String,
    val kind: SourceKind,
    val label: String,
    val description: String,
    val requiresPrivateSession: Boolean = false,
    val available: Boolean = true,
)

data class RecordSourceMetadata(
    val kind: SourceKind,
    val sourceId: String,
    val itemId: String,
    val revisionHash: String,
    val connectionGeneration: Long = 0,
    val authorizationToken: String = "",
    val origin: SourceOrigin,
    val contentState: NoticeContentState,
    val obligation: NoticeObligation,
    val audienceFacts: List<SourceAudienceFact> = emptyList(),
    val dateFacts: List<SourceDateFact> = emptyList(),
    val attachments: List<SourceAttachment> = emptyList(),
    val evidence: List<SourceEvidence> = emptyList(),
    val issues: List<SourceIssue> = emptyList(),
    val firstSeenAt: Long,
    val lastFetchedAt: Long,
    val publishedAt: Long? = null,
)

data class RecordSourceDecision(
    val state: SourceRecordState,
    val reason: String,
)

interface RecordSourcePolicy {
    fun decide(metadata: RecordSourceMetadata?, scope: SourceScope, now: Long): RecordSourceDecision
}

object SourceIds {
    const val NEIS_PUBLIC = "neis-public"
    const val SCHOOL_WEBSITE = "school-website-snjj"
    const val EALIMI_WEB = "ealimi-web"
}

object SourceConfigs {
    val all = listOf(
        SourceConfig(
            sourceId = SourceIds.NEIS_PUBLIC,
            kind = SourceKind.NEIS_PUBLIC,
            label = "나이스 학교정보",
            description = "공개 학사일정",
        ),
        SourceConfig(
            sourceId = SourceIds.SCHOOL_WEBSITE,
            kind = SourceKind.SCHOOL_WEBSITE,
            label = "성남정자초 공식 홈페이지",
            description = "공지·가정통신문·현재 초등 학년",
        ),
        SourceConfig(
            sourceId = SourceIds.EALIMI_WEB,
            kind = SourceKind.EALIMI_WEB,
            label = "e알리미 웹",
            description = "보호자 개인 공지",
            requiresPrivateSession = true,
            available = false,
        ),
    )

    fun get(sourceId: String): SourceConfig? = all.firstOrNull { it.sourceId == sourceId }
}

object SourceSyncLimits {
    const val RUN_TIMEOUT_MS = 120_000L
    const val READINESS_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val RESPONSE_BYTES = 1_048_576
    const val RUN_BYTES = 5_242_880
    const val SCHOOL_BOARD_PAGE_LIMIT = 5
    const val SCHOOL_BOARD_DETAIL_LIMIT = 30
    const val NEIS_PAGE_LIMIT = 10
    const val NEIS_ROW_LIMIT = 1_000
}
