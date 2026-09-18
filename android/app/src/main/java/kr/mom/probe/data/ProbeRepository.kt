package kr.mom.probe.data

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicLong
import kr.mom.probe.service.ProbeNotificationListener
import kr.mom.probe.sync.FetchedNotice
import kr.mom.probe.sync.IngestReceipt
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncStateStore
import kr.mom.probe.sync.SourceSyncStatus
import kr.mom.probe.sync.decodeRecordSourceMetadata
import kr.mom.probe.sync.encodeRecordSourceMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProbeRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val database = Room.databaseBuilder(app, ProbeDatabase::class.java, "mom-probe.db").build()
    private val dao = database.dao()
    private val crypto = ProbeCrypto()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val epoch = AtomicLong(0)
    private val ready = CompletableDeferred<Unit>()
    private var storageReady = false
    private var observationFault = false
    private val observationError = "기록을 읽지 못해 수집을 잠시 멈췄어요. 자동으로 다시 시도합니다. 계속되면 전체 데이터를 삭제해 주세요."
    private val mutableSettings = MutableStateFlow(ProbeSettings())
    private val mutableRecords = MutableStateFlow<List<ProbeRecord>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableConnected = MutableStateFlow(false)
    private val mutableReady = MutableStateFlow(false)
    val settings: StateFlow<ProbeSettings> = mutableSettings.asStateFlow()
    val records: StateFlow<List<ProbeRecord>> = mutableRecords.asStateFlow()
    val lastError: StateFlow<String?> = mutableError.asStateFlow()
    val listenerConnected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    val isReady: StateFlow<Boolean> = mutableReady.asStateFlow()

    init {
        scope.launch {
            try {
                mutex.withLock {
                    mutableSettings.value = dao.settings()?.let { decodeSettings(crypto.decrypt(it.encryptedPayload, "settings")) }
                        ?: ProbeSettings()
                    pruneLocked()
                    storageReady = true
                    // Restoring existing consent is not a new policy generation. Callbacks that
                    // arrived during startup may proceed once ready if no actual setting changed.
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableError.value = "저장된 정보를 읽을 수 없어요. 다시 열어도 계속되면 설정에서 전체 데이터를 삭제해 주세요."
            } finally { mutableReady.value = true; ready.complete(Unit) }
            while (true) {
                try {
                    dao.observeRecords().collect {
                        mutex.withLock {
                            if (storageReady) {
                                val now = System.currentTimeMillis()
                                // Re-read under the mutation lock; an invalidation emitted before deletion
                                // must never republish stale decrypted records after deletion completes.
                                mutableRecords.value = dao.currentRecords().filterNot { ProbeRules.isExpired(it.receivedAt, now) }
                                    .map { decodeRecord(crypto.decrypt(it.encryptedPayload, "record:${it.id}")) }
                                observationFault = false
                                if (mutableError.value == observationError) mutableError.value = null
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutex.withLock {
                        observationFault = true
                        mutableError.value = observationError
                    }
                    // Restart the Room subscription after transient I/O/decryption failures.
                    // A successful full reset also lets this observer recover without app restart.
                    delay(1_000)
                }
            }
        }
        scope.launch {
            ready.await()
            while (true) { delay(60_000); cleanupExpired() }
        }
    }

    fun hasNotificationAccess(): Boolean = app.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(app, ProbeNotificationListener::class.java))

    fun captureEpoch(): Long = epoch.get()
    fun setListenerConnected(connected: Boolean) {
        mutableConnected.value = connected
        if (!connected) epoch.incrementAndGet()
    }

    private suspend fun action(requireStorage: Boolean = true, operation: suspend () -> Unit): Boolean {
        ready.await()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (requireStorage && !storageReady) return@withLock false
                    operation()
                    mutableError.value = if (observationFault) observationError else null
                    true
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = if (error is IllegalArgumentException) error.message ?: "설정 내용을 확인해 주세요."
                        else "저장 작업을 마치지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요."
                    false
                }
            }
        }
    }

    private suspend fun saveLocked(value: ProbeSettings) {
        dao.saveSettings(StoredSettings(encryptedPayload = crypto.encrypt(encodeSettings(value), "settings")))
        mutableSettings.value = value
        epoch.incrementAndGet()
    }

    suspend fun acceptConsent() = action {
        saveLocked(settings.value.copy(consent = true, consentAt = System.currentTimeMillis(), consentVersion = ProbeRules.CONSENT_VERSION))
    }

    suspend fun saveSourceSelection(packages: Set<String>) = action {
        require(packages.size <= 10 && packages.all { it.isNotBlank() && it != app.packageName }) { "챙길 앱을 최대 10개 선택해 주세요." }
        saveLocked(settings.value.copy(selectedPackages = packages.toSet()))
    }

    suspend fun saveChild(name: String, schoolName: String = "", schoolGrade: Int? = null, schoolLevel: SchoolLevel? = null) = action {
        val clean = name.trim()
        val cleanSchool = schoolName.trim()
        require(clean.isNotEmpty() && clean.length <= 20 && clean.none { it.isISOControl() }) { "아이 이름은 1~20자로 입력해 주세요." }
        require(cleanSchool.length <= 60 && cleanSchool.none { it.isISOControl() }) { "학교명은 60자 이내로 입력해 주세요." }
        require(schoolGrade == null || schoolGrade in 1..6) { "학년을 확인해 주세요." }
        saveLocked(settings.value.copy(childName = clean, schoolName = cleanSchool, schoolGrade = schoolGrade, schoolLevel = schoolLevel))
    }

    suspend fun completeSetup() = action {
        val current = settings.value
        require(current.consent && current.consentVersion == ProbeRules.CONSENT_VERSION && current.childName.isNotBlank() && current.selectedPackages.isNotEmpty()) { "최신 설명에 동의하고 챙길 앱과 아이 이름을 설정해 주세요." }
        require(hasNotificationAccess()) { "알림 읽기를 허용한 뒤 다시 시도해 주세요." }
        saveLocked(current.copy(onboardingDone = true, collectionEnabled = true))
    }

    suspend fun deferSetup() = action {
        require(settings.value.consent && settings.value.consentVersion == ProbeRules.CONSENT_VERSION) { "최신 참여 설명에 먼저 동의해 주세요." }
        saveLocked(settings.value.copy(onboardingDone = true, collectionEnabled = false))
    }

    suspend fun setCollectionEnabled(enabled: Boolean) = action {
        if (enabled) {
            val current = settings.value
            require(current.consent && current.consentVersion == ProbeRules.CONSENT_VERSION && current.onboardingDone && current.childName.isNotBlank() && current.selectedPackages.isNotEmpty()) { "최신 설명에 동의하고 챙길 앱과 아이 이름을 설정해 주세요." }
            require(hasNotificationAccess()) { "알림 읽기를 허용한 뒤 다시 시도해 주세요." }
        }
        saveLocked(settings.value.copy(collectionEnabled = enabled))
    }

    suspend fun deleteRecord(id: String) = action {
        epoch.incrementAndGet()
        val metadata = mutableRecords.value.firstOrNull { it.id == id }?.sourceMetadata
        database.withTransaction {
            dao.suppress(id)
            if (metadata != null) {
                dao.insertTombstone(Tombstone(ProbeRules.sourceItemIdentity(metadata.sourceId, metadata.itemId), System.currentTimeMillis()))
            }
            dao.delete(id)
        }
        mutableRecords.value = mutableRecords.value.filterNot { it.id == id }
    }

    suspend fun ingestSource(scope: SourceScope, result: SourceFetchResult): IngestReceipt {
        var receipt = IngestReceipt(result.sourceId, result.status, result.fetchedAt)
        val saved = action {
            receipt = ingestSourceLocked(scope, result)
        }
        return if (saved) receipt else receipt.copy(
            status = SourceSyncStatus.ERROR,
            issues = receipt.issues + SourceIssue(SourceIssueCode.STORAGE_ERROR, mutableError.value ?: "저장하지 못했어요."),
        )
    }

    suspend fun deleteAll() = action(requireStorage = false) {
        epoch.incrementAndGet()
        // Close the collection gate before any fallible disk operations.
        mutableSettings.value = ProbeSettings()
        storageReady = false
        database.withTransaction { dao.deleteRecords(); dao.deleteTombstones(); dao.deleteSettings() }
        crypto.destroyKey()
        mutableRecords.value = emptyList()
        observationFault = false
        storageReady = true
    }

    suspend fun clearSourceRecords(sourceIds: Set<String>) = action {
        if (sourceIds.isEmpty()) return@action
        val ids = mutableRecords.value.filter { record ->
            record.sourceMetadata?.sourceId in sourceIds
        }.map { it.id }
        if (ids.isNotEmpty()) {
            database.withTransaction { dao.deleteIds(ids) }
            mutableRecords.value = mutableRecords.value.filterNot { it.id in ids }
        }
    }

    suspend fun beginReset() = action {
        saveLocked(settings.value.copy(consent = false, collectionEnabled = false, onboardingDone = false))
    }

    suspend fun cleanupExpired() = action { pruneLocked() }

    private suspend fun pruneLocked() {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.deleteExpired(now - ProbeRules.RETENTION_MS)
            dao.deleteExpiredTombstones(now - ProbeRules.RETENTION_MS)
        }
        mutableRecords.value = mutableRecords.value.filterNot { ProbeRules.isExpired(it.receivedAt, now) }
    }

    suspend fun capture(notification: StatusBarNotification, expectedEpoch: Long) = action {
        if (observationFault) return@action
        if (epoch.get() != expectedEpoch) return@action
        val value = notification.notification
        val ongoing = notification.isOngoing
        val summary = value.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (!ProbeRules.canCapture(settings.value, notification.packageName, app.packageName,
                hasNotificationAccess(), ongoing, summary)) return@action
        // Extras are read only after consent, selected-package and live OS access checks.
        val extras = value.extras
        var truncated = false
        fun bounded(value: String): String {
            if (value.length > 32_768) truncated = true
            return value.take(32_768)
        }
        fun rawField(key: String) = extras.getCharSequence(key)?.toString().orEmpty()
        fun field(key: String) = bounded(rawField(key))
        val title = field(Notification.EXTRA_TITLE)
        val text = field(Notification.EXTRA_TEXT)
        val bigText = field(Notification.EXTRA_BIG_TEXT)
        val originalLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
        if (originalLines.size > 100) truncated = true
        val lines = originalLines.take(100).map { bounded(it.toString()) }
        val subText = field(Notification.EXTRA_SUB_TEXT).ifEmpty { null }
        val summaryText = field(Notification.EXTRA_SUMMARY_TEXT).ifEmpty { null }
        // Hash the actual payload, including omitted suffixes, so updates beyond the storage limit
        // remain distinct revisions rather than being silently mistaken for duplicate callbacks.
        val rawHash = ProbeRules.digest(JSONObject().put("title", rawField(Notification.EXTRA_TITLE))
            .put("text", rawField(Notification.EXTRA_TEXT)).put("bigText", rawField(Notification.EXTRA_BIG_TEXT))
            .put("lines", JSONArray(originalLines.map { it.toString() }))
            .put("subText", rawField(Notification.EXTRA_SUB_TEXT))
            .put("summaryText", rawField(Notification.EXTRA_SUMMARY_TEXT)).toString())
        val id = ProbeRules.revisionId(notification.packageName, notification.key, notification.postTime, rawHash)
        if (dao.isDeleted(id) > 0) return@action
        val label = try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(notification.packageName, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { notification.packageName }
        val record = ProbeRecord(id, notification.packageName, label, notification.postTime, System.currentTimeMillis(),
            title, text, bigText, lines, subText, summaryText, value.category, value.channelId,
            notification.id, notification.key, ongoing, summary, rawHash, truncated)
        if (epoch.get() != expectedEpoch || !hasNotificationAccess()) return@action
        pruneLocked()
        val inserted = dao.insert(StoredRecord(id, record.receivedAt, crypto.encrypt(encodeRecord(record), "record:$id")))
        if (inserted != -1L) {
            kr.mom.probe.task.AutoActionCoordinator.handle(app, record, settings.value)
            kr.mom.probe.reminder.AssistantAlertNotifier.notify(app, record)
        }
    }

    private suspend fun ingestSourceLocked(scope: SourceScope, result: SourceFetchResult): IngestReceipt {
        if (!result.canCommitRecords) {
            return IngestReceipt(
                sourceId = result.sourceId,
                status = result.status,
                attemptedAt = result.fetchedAt,
                issues = result.issues,
            )
        }
        if (scope.sourceId != result.sourceId) {
            return IngestReceipt(
                sourceId = result.sourceId,
                status = SourceSyncStatus.ERROR,
                attemptedAt = result.fetchedAt,
                issues = result.issues + SourceIssue(SourceIssueCode.CONSENT_CHANGED, "조회 범위가 바뀌어 저장하지 않았어요."),
            )
        }
        val currentSettings = settings.value
        if (!currentSettings.consent || currentSettings.consentVersion != scope.consentVersion || !currentSettings.onboardingDone) {
            return IngestReceipt(
                sourceId = result.sourceId,
                status = SourceSyncStatus.ERROR,
                attemptedAt = result.fetchedAt,
                issues = result.issues + SourceIssue(SourceIssueCode.CONSENT_CHANGED, "동의 상태가 바뀌어 저장하지 않았어요."),
            )
        }
        if (!sameSourceGeneration(app, scope)) {
            return IngestReceipt(
                sourceId = result.sourceId,
                status = SourceSyncStatus.ERROR,
                attemptedAt = result.fetchedAt,
                issues = result.issues + SourceIssue(SourceIssueCode.CONSENT_CHANGED, "출처 연결 범위가 바뀌어 저장하지 않았어요."),
            )
        }
        if (!sameChildScope(currentSettings, scope)) {
            return IngestReceipt(
                sourceId = result.sourceId,
                status = SourceSyncStatus.ERROR,
                attemptedAt = result.fetchedAt,
                issues = result.issues + SourceIssue(SourceIssueCode.INVALID_SCOPE, "현재 학교와 학년 범위가 달라 저장하지 않았어요."),
            )
        }
        pruneLocked()
        val existing = mutableRecords.value.filter { it.sourceMetadata?.sourceId == scope.sourceId }
        val sourceItemToRecord = existing.mapNotNull { record ->
            record.sourceMetadata?.let { metadata -> ProbeRules.sourceItemIdentity(metadata.sourceId, metadata.itemId) to record }
        }.toMap()
        val storedIds = mutableListOf<String>()
        var inserted = 0
        var changed = 0
        var unchanged = 0
        var suppressed = 0
        var skipped = 0
        val replacedIds = mutableListOf<String>()
        val seenItemIdentities = mutableSetOf<String>()
        val discoveryTimes = mutableListOf<Long>()
        database.withTransaction {
            result.items.forEach { item ->
                if (item.sourceId != scope.sourceId || !originHostMatchesScope(item.origin.host, scope)) {
                    skipped++
                    return@forEach
                }
                val revisionId = ProbeRules.sourceRevisionId(item.sourceId, item.itemId, item.revisionHash)
                val itemIdentity = ProbeRules.sourceItemIdentity(item.sourceId, item.itemId)
                if (!seenItemIdentities.add(itemIdentity)) {
                    skipped++
                    return@forEach
                }
                if (dao.isDeleted(revisionId) > 0 || dao.isDeleted(itemIdentity) > 0) {
                    suppressed++
                    return@forEach
                }
                if (dao.recordExists(revisionId) > 0) {
                    unchanged++
                    return@forEach
                }
                val previous = sourceItemToRecord[itemIdentity]
                val previousFirstSeenAt = previous?.sourceMetadata?.firstSeenAt
                val record = item.toProbeRecord(scope, result.fetchedAt, previousFirstSeenAt)
                val rowId = dao.insert(StoredRecord(revisionId, record.receivedAt, crypto.encrypt(encodeRecord(record), "record:$revisionId")))
                if (rowId == -1L) {
                    unchanged++
                    return@forEach
                }
                previous?.takeIf { it.id != revisionId }?.let { replacedIds += it.id }
                storedIds += revisionId
                if (previous == null) {
                    inserted++
                    discoveryTimes += item.firstSeenAt.takeIf { it > 0L } ?: result.fetchedAt
                } else changed++
            }
        }
        if (!sameSourceGeneration(app, scope) || !sameChildScope(settings.value, scope)) {
            storedIds.forEach { dao.delete(it) }
            mutableRecords.value = mutableRecords.value.filterNot { it.id in storedIds }
            return IngestReceipt(
                sourceId = result.sourceId,
                status = SourceSyncStatus.ERROR,
                attemptedAt = result.fetchedAt,
                issues = result.issues + SourceIssue(SourceIssueCode.CONSENT_CHANGED, "저장 직후 범위가 바뀌어 반영을 취소했어요."),
            )
        }
        if (replacedIds.isNotEmpty()) database.withTransaction { dao.deleteIds(replacedIds) }
        mutableRecords.value = dao.currentRecords().filterNot { ProbeRules.isExpired(it.receivedAt, System.currentTimeMillis()) }
            .map { decodeRecord(crypto.decrypt(it.encryptedPayload, "record:${it.id}")) }
        mutableRecords.value.filter { it.id in storedIds }.forEach { record ->
            kr.mom.probe.task.AutoActionCoordinator.handle(app, record, settings.value)
            kr.mom.probe.reminder.AssistantAlertNotifier.notify(app, record)
        }
        kr.mom.probe.sync.PostingTimeStore.recordDiscoveries(app, scope.sourceId, discoveryTimes)
        return IngestReceipt(
            sourceId = result.sourceId,
            status = result.status,
            attemptedAt = result.fetchedAt,
            committedAt = System.currentTimeMillis(),
            storedRevisionIds = storedIds,
            insertedCount = inserted,
            changedCount = changed,
            unchangedCount = unchanged,
            suppressedCount = suppressed,
            skippedCount = skipped,
            issues = result.issues,
        )
    }

    companion object {
        @Volatile private var instance: ProbeRepository? = null
        fun get(context: Context): ProbeRepository = instance ?: synchronized(this) {
            instance ?: ProbeRepository(context).also { instance = it }
        }
    }
}



private fun sameSourceGeneration(context: Context, scope: SourceScope): Boolean =
    SourceSyncStateStore.get(context).generationMatches(scope)

private fun originHostMatchesScope(originHost: String, scope: SourceScope): Boolean {
    val expected = scope.school.officialHost ?: return true
    return originHost == expected || originHost.endsWith(".$expected")
}

private fun sameChildScope(settings: ProbeSettings, scope: SourceScope): Boolean {
    val currentLevel = settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName)
    val scopeSchool = scope.school.schoolName.replace(" ", "")
    val settingsSchool = settings.schoolName.replace(" ", "")
    return settings.consentVersion == scope.consentVersion &&
        ProbeRules.sourceAuthorizationToken(settings) == scope.authorizationToken &&
        settings.schoolGrade == scope.child.grade &&
        currentLevel == scope.child.schoolLevel &&
        settingsSchool.isNotBlank() &&
        settingsSchool == scopeSchool
}

private fun FetchedNotice.toProbeRecord(scope: SourceScope, fetchedAt: Long, previousFirstSeenAt: Long? = null): ProbeRecord {
    val revisionId = ProbeRules.sourceRevisionId(sourceId, itemId, revisionHash)
    val received = previousFirstSeenAt ?: firstSeenAt.takeIf { it > 0L } ?: fetchedAt
    val metadata = RecordSourceMetadata(
        kind = scope.kind,
        sourceId = sourceId,
        itemId = itemId,
        revisionHash = revisionHash,
        connectionGeneration = scope.connectionGeneration,
        authorizationToken = scope.authorizationToken,
        origin = origin,
        contentState = contentState,
        obligation = obligation,
        audienceFacts = audienceFacts,
        dateFacts = dateFacts,
        attachments = attachments,
        evidence = evidence,
        issues = issues,
        firstSeenAt = received,
        lastFetchedAt = fetchedAt,
        publishedAt = publishedAt,
    )
    return ProbeRecord(
        id = revisionId,
        packageName = "source:$sourceId",
        appLabel = sourceLabel(scope.kind, sourceId),
        postedAt = publishedAt ?: received,
        receivedAt = received,
        title = title.take(512),
        text = body.take(32_768),
        bigText = body.take(32_768),
        textLines = emptyList(),
        subText = origin.canonicalUrl,
        summaryText = null,
        category = "source",
        channelId = sourceId,
        notificationId = 0,
        notificationKey = itemId,
        isOngoing = false,
        isGroupSummary = false,
        rawHash = revisionHash,
        truncated = body.length > 32_768,
        sourceMetadata = metadata,
    )
}

private fun sourceLabel(kind: kr.mom.probe.sync.SourceKind, sourceId: String): String =
    kr.mom.probe.sync.SourceConfigs.get(sourceId)?.label ?: when (kind) {
        kr.mom.probe.sync.SourceKind.NEIS_PUBLIC -> "나이스 학교정보"
        kr.mom.probe.sync.SourceKind.SCHOOL_WEBSITE -> "학교 홈페이지"
        kr.mom.probe.sync.SourceKind.EALIMI_WEB -> "e알리미 웹"
        kr.mom.probe.sync.SourceKind.ANDROID_NOTIFICATION -> "앱 알림"
    }

private fun encodeSettings(value: ProbeSettings) = JSONObject().put("consent", value.consent)
    .put("childName", value.childName).put("selectedPackages", JSONArray(value.selectedPackages.toList()))
    .put("schoolName", value.schoolName).put("schoolGrade", value.schoolGrade ?: JSONObject.NULL)
    .put("schoolLevel", value.schoolLevel?.name ?: JSONObject.NULL)
    .put("collectionEnabled", value.collectionEnabled).put("onboardingDone", value.onboardingDone)
    .put("consentAt", value.consentAt).put("consentVersion", value.consentVersion).toString()

private fun decodeSettings(raw: String): ProbeSettings {
    val json = JSONObject(raw)
    val packages = json.optJSONArray("selectedPackages") ?: JSONArray()
    return ProbeSettings(
        consent = json.optBoolean("consent"), childName = json.optString("childName"),
        schoolName = json.optString("schoolName"), schoolGrade = if (json.isNull("schoolGrade")) null else json.optInt("schoolGrade"),
        schoolLevel = json.nullableString("schoolLevel")?.let { runCatching { SchoolLevel.valueOf(it) }.getOrNull() },
        selectedPackages = (0 until packages.length()).map { packages.getString(it) }.toSet(),
        collectionEnabled = json.optBoolean("collectionEnabled"), onboardingDone = json.optBoolean("onboardingDone"),
        consentAt = if (json.has("consentAt")) json.getLong("consentAt") else null,
        consentVersion = json.nullableString("consentVersion"),
    )
}

private fun encodeRecord(value: ProbeRecord): String = with(value) {
    JSONObject().put("id", id).put("packageName", packageName).put("appLabel", appLabel)
        .put("postedAt", postedAt).put("receivedAt", receivedAt).put("title", title).put("text", text)
        .put("bigText", bigText).put("textLines", JSONArray(textLines)).put("subText", subText)
        .put("summaryText", summaryText).put("category", category).put("channelId", channelId)
        .put("notificationId", notificationId).put("notificationKey", notificationKey)
        .put("isOngoing", isOngoing).put("isGroupSummary", isGroupSummary).put("rawHash", rawHash).put("truncated", truncated)
        .put("sourceMetadata", sourceMetadata?.let(::encodeRecordSourceMetadata) ?: JSONObject.NULL).toString()
}

private fun decodeRecord(raw: String): ProbeRecord = with(JSONObject(raw)) {
    val lines = getJSONArray("textLines")
    ProbeRecord(getString("id"), getString("packageName"), getString("appLabel"), getLong("postedAt"),
        getLong("receivedAt"), getString("title"), getString("text"), getString("bigText"),
        (0 until lines.length()).map { lines.getString(it) }, nullableString("subText"), nullableString("summaryText"),
        nullableString("category"), nullableString("channelId"), getInt("notificationId"), getString("notificationKey"),
        getBoolean("isOngoing"), getBoolean("isGroupSummary"), getString("rawHash"), optBoolean("truncated"),
        optJSONObject("sourceMetadata")?.let(::decodeRecordSourceMetadata))
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
