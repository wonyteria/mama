package kr.mom.probe.sync

import android.content.Context
import android.util.Base64
import java.io.IOException
import kr.mom.probe.data.ProbeCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class SourceSyncStateStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("source-sync-state", Context.MODE_PRIVATE)
    private val crypto = ProbeCrypto()
    private val mutableSnapshots = MutableStateFlow(loadReadOnly())
    val snapshotsFlow: StateFlow<Map<String, SourceSyncSnapshot>> = mutableSnapshots.asStateFlow()

    @Synchronized
    fun snapshot(sourceId: String): SourceSyncSnapshot? = loadReadOnly()[sourceId]

    @Synchronized
    fun snapshots(): Map<String, SourceSyncSnapshot> = loadReadOnly()

    @Synchronized
    fun checkpoint(sourceId: String): SourceCheckpoint? = loadReadOnly()[sourceId]?.checkpoint


    @Synchronized
    fun generationMatches(scope: SourceScope): Boolean = runCatching {
        val previous = loadStrict()[scope.sourceId]
        if (previous == null) {
            scope.connectionGeneration == 0L
        } else {
            previous.sourceGeneration == scope.connectionGeneration &&
                (previous.authorizationToken.isBlank() || previous.authorizationToken == scope.authorizationToken)
        }
    }.getOrDefault(false)

    @Synchronized
    fun markRunning(
        scope: SourceScope,
        now: Long = System.currentTimeMillis(),
        canCommit: () -> Boolean = { true },
    ): SourceSyncSnapshot {
        val current = loadStrict()
        val previous = current[scope.sourceId]
        requireSnapshotMatchesScope(previous, scope, allowMissing = true)
        if (!canCommit()) throw IOException("출처 연결 범위가 바뀌어 조회 시작 상태를 저장하지 않았어요.")
        val next = previous?.copy(
            kind = scope.kind,
            status = SourceSyncStatus.RUNNING,
            lastAttemptAt = now,
            coverageWindow = scope.coverageWindow,
            reasonCode = null,
            message = null,
            authorizationToken = scope.authorizationToken,
            sourceGeneration = scope.connectionGeneration,
        ) ?: SourceSyncSnapshot(
            sourceId = scope.sourceId,
            kind = scope.kind,
            status = SourceSyncStatus.RUNNING,
            lastAttemptAt = now,
            coverageWindow = scope.coverageWindow,
            authorizationToken = scope.authorizationToken,
            sourceGeneration = scope.connectionGeneration,
        )
        persist(current + (scope.sourceId to next))
        return next
    }

    @Synchronized
    fun discardIfMatches(scope: SourceScope): Boolean {
        val current = loadStrict()
        val previous = current[scope.sourceId] ?: return true
        if (previous.sourceGeneration != scope.connectionGeneration ||
            (previous.authorizationToken.isNotBlank() && previous.authorizationToken != scope.authorizationToken)
        ) return false
        persist(current - scope.sourceId)
        return true
    }

    @Synchronized
    fun recordResult(scope: SourceScope, result: SourceFetchResult, receipt: IngestReceipt?): SourceSyncSnapshot {
        val current = loadStrict()
        val previous = current[scope.sourceId]
        requireSnapshotMatchesScope(previous, scope, allowMissing = false)
        val stored = receipt?.insertedCount.orZero() + receipt?.changedCount.orZero()
        val committed = receipt?.committedAt != null
        val firstIssue = (receipt?.issues.orEmpty() + result.issues).firstOrNull()
        val effectiveStatus = if (receipt?.status == SourceSyncStatus.ERROR) SourceSyncStatus.ERROR else result.status
        val complete = effectiveStatus in setOf(SourceSyncStatus.FETCHED, SourceSyncStatus.SUCCESS_EMPTY) && result.coverage.complete
        val next = SourceSyncSnapshot(
            sourceId = scope.sourceId,
            kind = scope.kind,
            status = effectiveStatus,
            lastAttemptAt = result.fetchedAt,
            lastSuccessAt = if (committed && result.canCommitRecords) result.fetchedAt else previous?.lastSuccessAt,
            lastCompleteAt = if (committed && complete) result.fetchedAt else previous?.lastCompleteAt,
            coverageWindow = result.coverage,
            seenCount = result.items.size,
            matchedCount = result.items.count {
                SourceAudienceEvaluator.appliesToChild(it.audienceFacts, scope.child.schoolLevel, scope.child.grade)
            },
            storedCount = stored,
            attachmentState = attachmentState(result.items),
            reasonCode = firstIssue?.code,
            message = firstIssue?.message,
            checkpoint = if (committed) result.checkpoint ?: previous?.checkpoint else previous?.checkpoint,
            sourceGeneration = scope.connectionGeneration,
            authorizationToken = scope.authorizationToken,
        )
        persist(current + (scope.sourceId to next))
        return next
    }

    @Synchronized
    fun bumpGeneration(sourceId: String) {
        val current = loadStrict()
        val previous = current[sourceId] ?: SourceSyncSnapshot(
            sourceId = sourceId,
            kind = SourceConfigs.get(sourceId)?.kind ?: SourceKind.ANDROID_NOTIFICATION,
        )
        persist(current + (sourceId to previous.copy(
            status = SourceSyncStatus.NEVER,
            checkpoint = null,
            reasonCode = SourceIssueCode.CONSENT_CHANGED,
            message = "출처 연결 범위가 바뀌어 다시 확인해야 해요.",
            sourceGeneration = previous.sourceGeneration + 1,
            authorizationToken = "",
        )))
    }

    @Synchronized
    fun reset(): Boolean {
        val cleared = preferences.edit().clear().commit()
        if (cleared) mutableSnapshots.value = emptyMap()
        return cleared
    }

    private fun attachmentState(items: List<FetchedNotice>): AttachmentFetchState {
        val states = items.flatMap { it.attachments }.map { it.state }
        return when {
            states.isEmpty() -> AttachmentFetchState.NONE
            states.any { it == AttachmentFetchState.MISSING || it == AttachmentFetchState.UNSUPPORTED || it == AttachmentFetchState.BLOCKED } -> AttachmentFetchState.MISSING
            states.any { it == AttachmentFetchState.LINK_ONLY } -> AttachmentFetchState.LINK_ONLY
            else -> AttachmentFetchState.FETCHED
        }
    }

    private fun loadReadOnly(): Map<String, SourceSyncSnapshot> = runCatching { loadStrict() }.getOrDefault(emptyMap())

    private fun loadStrict(): Map<String, SourceSyncSnapshot> = runCatching {
        val encoded = preferences.getString("encrypted", null) ?: return emptyMap()
        val array = JSONArray(crypto.decrypt(Base64.decode(encoded, Base64.NO_WRAP), "source-sync-state"))
        buildMap {
            for (index in 0 until array.length()) {
                val snapshot = decodeSourceSnapshot(array.getJSONObject(index))
                put(snapshot.sourceId, snapshot)
            }
        }
    }.getOrElse { error -> throw IOException("저장된 출처 상태를 읽지 못했어요.", error) }

    private fun requireSnapshotMatchesScope(previous: SourceSyncSnapshot?, scope: SourceScope, allowMissing: Boolean) {
        if (previous == null) {
            if (allowMissing) return
            throw IOException("출처 상태가 사라져 늦은 조회 결과를 반영하지 않았어요.")
        }
        if (previous.sourceGeneration != scope.connectionGeneration ||
            (previous.authorizationToken.isNotBlank() && previous.authorizationToken != scope.authorizationToken)
        ) {
            throw IOException("출처 연결 범위가 바뀌어 늦은 조회 결과를 반영하지 않았어요.")
        }
    }

    private fun persist(value: Map<String, SourceSyncSnapshot>) {
        val array = JSONArray(value.values.sortedBy { it.sourceId }.map(::encodeSourceSnapshot))
        val encoded = Base64.encodeToString(crypto.encrypt(array.toString(), "source-sync-state"), Base64.NO_WRAP)
        if (!preferences.edit().putString("encrypted", encoded).commit()) throw IOException("출처 상태를 저장하지 못했어요.")
        mutableSnapshots.value = value
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        @Volatile private var instance: SourceSyncStateStore? = null
        fun get(context: Context): SourceSyncStateStore = instance ?: synchronized(this) {
            instance ?: SourceSyncStateStore(context).also { instance = it }
        }
    }
}
