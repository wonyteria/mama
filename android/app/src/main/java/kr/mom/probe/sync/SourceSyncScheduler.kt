package kr.mom.probe.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kr.mom.probe.connector.ConnectionStatus
import kr.mom.probe.connector.ConnectorCatalog
import kr.mom.probe.connector.ConnectorRepository
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSourceIngestor
import kr.mom.probe.data.SchoolLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

object SourceFetcherRegistry {
    private val fetchers = ConcurrentHashMap<String, SourceFetcher>()

    fun register(sourceId: String, fetcher: SourceFetcher) {
        fetchers[sourceId] = fetcher
    }

    fun unregister(sourceId: String) {
        fetchers.remove(sourceId)
    }

    fun get(sourceId: String): SourceFetcher? = fetchers[sourceId]
}

object SourceSyncScheduler {
    private const val SOURCE_ID = "sourceId"
    private const val TRIGGER = "trigger"
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private val learnableSourceIds = setOf(SourceIds.SCHOOL_WEBSITE, SourceIds.EALIMI_WEB)

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ConnectorCatalog.sites.filterNot { ConnectorCatalog.shouldShowWebsite(it.id) }
            .forEach { cancel(context, it.id) }
        SourceConfigs.all.filter { it.available && ConnectorCatalog.shouldShowWebsite(it.sourceId) }.forEach { config ->
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "source-sync-periodic-${config.sourceId}",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SourceSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(input(config.sourceId, SourceRunTrigger.PERIODIC))
                    .build(),
            )
        }
        scheduleLearnedWindows(context)
    }

    /**
     * Once a source's usual discovery hour is learned, an extra one-shot check
     * runs just before that window each day. The 6-hour baseline poll stays in
     * place, so coverage never depends on the learned estimate being right.
     */
    fun scheduleLearnedWindows(context: Context, now: Long = System.currentTimeMillis()) {
        val workManager = WorkManager.getInstance(context)
        val activeIds = SourceScopeFactory.activeScopes(context, SourceRunTrigger.POSTING_WINDOW)
            .map { it.sourceId }.toSet()
        learnableSourceIds.forEach { sourceId ->
            val workName = "source-sync-window-$sourceId"
            val checkHour = if (sourceId in activeIds) PostingTimeStore.learnedCheckHour(context, sourceId) else null
            if (checkHour == null) {
                workManager.cancelUniqueWork(workName)
                return@forEach
            }
            val delayMs = millisUntilNextHour(checkHour, now)
            workManager.enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SourceSyncWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(input(sourceId, SourceRunTrigger.POSTING_WINDOW))
                    .build(),
            )
        }
    }

    internal fun millisUntilNextHour(hour: Int, now: Long): Long {
        val zonedNow = Instant.ofEpochMilli(now).atZone(seoul)
        var target = zonedNow.withMinute(0).withSecond(0).withNano(0).withHour(hour)
        if (!target.isAfter(zonedNow.plusMinutes(5))) target = target.plusDays(1)
        return target.toInstant().toEpochMilli() - now
    }

    fun enqueue(context: Context, sourceId: String, trigger: SourceRunTrigger = SourceRunTrigger.MANUAL) {
        if (!ConnectorCatalog.shouldShowWebsite(sourceId)) {
            cancel(context, sourceId)
            return
        }
        schedulePeriodic(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "source-sync-now-$sourceId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SourceSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(input(sourceId, trigger))
                .build(),
        )
    }

    fun enqueueActive(context: Context, trigger: SourceRunTrigger) {
        SourceScopeFactory.activeScopes(context, trigger).forEach { scope -> enqueue(context, scope.sourceId, trigger) }
    }

    fun cancel(context: Context, sourceId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("source-sync-now-$sourceId")
        workManager.cancelUniqueWork("source-sync-periodic-$sourceId")
        workManager.cancelUniqueWork("source-sync-window-$sourceId")
    }

    fun cancelAll(context: Context) {
        SourceConfigs.all.forEach { cancel(context, it.sourceId) }
    }

    fun enqueueForegroundStale(context: Context, now: Long = System.currentTimeMillis()) {
        val store = SourceSyncStateStore.get(context)
        SourceScopeFactory.activeScopes(context, SourceRunTrigger.FOREGROUND_STALE)
            .filter { scope ->
                val lastAttempt = store.snapshot(scope.sourceId)?.lastAttemptAt
                lastAttempt == null || now - lastAttempt >= 30 * 60_000L
            }
            .forEach { scope -> enqueue(context, scope.sourceId, SourceRunTrigger.FOREGROUND_STALE) }
    }

    internal fun sourceId(parameters: WorkerParameters): String? = parameters.inputData.getString(SOURCE_ID)

    internal fun trigger(parameters: WorkerParameters): SourceRunTrigger =
        parameters.inputData.getString(TRIGGER)?.let { runCatching { SourceRunTrigger.valueOf(it) }.getOrNull() }
            ?: SourceRunTrigger.PERIODIC

    private fun input(sourceId: String, trigger: SourceRunTrigger): Data =
        Data.Builder().putString(SOURCE_ID, sourceId).putString(TRIGGER, trigger.name).build()
}

object SourceScopeFactory {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun activeScopes(context: Context, trigger: SourceRunTrigger): List<SourceScope> {
        val ids = enabledSourceIds(context)
        return ids.mapNotNull { scopeFor(context, it, trigger) }
    }

    fun scopeFor(context: Context, sourceId: String, trigger: SourceRunTrigger): SourceScope? {
        val app = context.applicationContext
        val repository = ProbeRepository.get(app)
        val settings = repository.settings.value
        if (!repository.isReady.value || !settings.consent || settings.consentVersion != ProbeRules.CONSENT_VERSION || !settings.onboardingDone) {
            return null
        }
        val grade = settings.schoolGrade ?: return null
        val level = settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName)
        if (level == SchoolLevel.UNKNOWN || settings.schoolName.isBlank()) return null
        val today = LocalDate.now(seoul)
        val child = ChildSourceScope(schoolLevel = level, grade = grade)
        val base = SourceCoverageWindow(today.toString(), today.plusDays(30).toString())
        val consentEpoch = repository.captureEpoch()
        val authorizationToken = ProbeRules.sourceAuthorizationToken(settings)
        return when (sourceId) {
            SourceIds.SCHOOL_WEBSITE -> if (isSeongnamJeongjaElementary(settings.schoolName, level, grade)) {
                SourceScope(
                    sourceId = SourceIds.SCHOOL_WEBSITE,
                    kind = SourceKind.SCHOOL_WEBSITE,
                    school = CanonicalSchoolScope(
                        schoolName = "성남정자초등학교",
                        officialHost = "snjj-e.goesn.kr",
                        officialPathPrefix = "/snjj-e/",
                    ),
                    child = child,
                    connectionGeneration = generationFor(app, sourceId),
                    consentEpoch = consentEpoch,
                    authorizationToken = authorizationToken,
                    consentVersion = ProbeRules.CONSENT_VERSION,
                    coverageWindow = base.copy(pageStart = 1, pageEnd = SourceSyncLimits.SCHOOL_BOARD_PAGE_LIMIT),
                    trigger = trigger,
                )
            } else {
                null
            }
            SourceIds.NEIS_PUBLIC -> neisScope(app, child, consentEpoch, authorizationToken, trigger, base)
            SourceIds.EALIMI_WEB -> if (ConnectorCatalog.shouldShowWebsite(SourceIds.EALIMI_WEB)) {
                privateScope(app, child, consentEpoch, authorizationToken, trigger, base)
            } else {
                null
            }
            else -> null
        }
    }

    private fun enabledSourceIds(context: Context): Set<String> {
        val connectors = ConnectorRepository.get(context.applicationContext).state.value.sites
        val ids = mutableSetOf<String>()
        if (isSeongnamJeongjaElementaryFromSettings(context)) ids += SourceIds.SCHOOL_WEBSITE
        if (connectors[SourceIds.NEIS_PUBLIC]?.status == ConnectionStatus.CONNECTED) ids += SourceIds.NEIS_PUBLIC
        if (ConnectorCatalog.shouldShowWebsite(SourceIds.EALIMI_WEB) &&
            connectors[SourceIds.EALIMI_WEB]?.status in setOf(ConnectionStatus.SESSION_READY, ConnectionStatus.CONNECTED)
        ) ids += SourceIds.EALIMI_WEB
        return ids
    }

    private fun neisScope(
        context: Context,
        child: ChildSourceScope,
        consentEpoch: Long,
        authorizationToken: String,
        trigger: SourceRunTrigger,
        coverage: SourceCoverageWindow,
    ): SourceScope? {
        val connection = ConnectorRepository.get(context).state.value.sites[SourceIds.NEIS_PUBLIC] ?: return null
        if (connection.status != ConnectionStatus.CONNECTED) return null
        val schoolName = connection.metadata["schoolName"].orEmpty()
        val officeCode = connection.metadata["officeCode"].orEmpty().ifBlank { null }
        val schoolCode = connection.metadata["schoolCode"].orEmpty().ifBlank { null }
        if (schoolName.isBlank() || officeCode == null || schoolCode == null) return null
        return SourceScope(
            sourceId = SourceIds.NEIS_PUBLIC,
            kind = SourceKind.NEIS_PUBLIC,
            school = CanonicalSchoolScope(schoolName = schoolName, officeCode = officeCode, schoolCode = schoolCode, officialHost = "open.neis.go.kr"),
            child = child,
            connectionGeneration = generationFor(context, SourceIds.NEIS_PUBLIC),
            consentEpoch = consentEpoch,
            authorizationToken = authorizationToken,
            consentVersion = ProbeRules.CONSENT_VERSION,
            coverageWindow = coverage,
            trigger = trigger,
        )
    }

    private fun privateScope(
        context: Context,
        child: ChildSourceScope,
        consentEpoch: Long,
        authorizationToken: String,
        trigger: SourceRunTrigger,
        coverage: SourceCoverageWindow,
    ): SourceScope? {
        val connection = ConnectorRepository.get(context).state.value.sites[SourceIds.EALIMI_WEB] ?: return null
        if (connection.status !in setOf(ConnectionStatus.SESSION_READY, ConnectionStatus.CONNECTED)) return null
        val settings = ProbeRepository.get(context).settings.value
        return SourceScope(
            sourceId = SourceIds.EALIMI_WEB,
            kind = SourceKind.EALIMI_WEB,
            school = CanonicalSchoolScope(schoolName = settings.schoolName, officialHost = "ealimi.com"),
            child = child,
            connectionGeneration = generationFor(context, SourceIds.EALIMI_WEB),
            consentEpoch = consentEpoch,
            authorizationToken = authorizationToken,
            consentVersion = ProbeRules.CONSENT_VERSION,
            coverageWindow = coverage,
            trigger = trigger,
        )
    }

    private fun generationFor(context: Context, sourceId: String): Long =
        SourceSyncStateStore.get(context).snapshot(sourceId)?.sourceGeneration ?: 0L

    private fun isSeongnamJeongjaElementaryFromSettings(context: Context): Boolean {
        val settings = ProbeRepository.get(context.applicationContext).settings.value
        val level = settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName)
        return isSeongnamJeongjaElementary(settings.schoolName, level, settings.schoolGrade)
    }

    private fun isSeongnamJeongjaElementary(schoolName: String, level: SchoolLevel, grade: Int?): Boolean {
        val normalized = schoolName.replace(" ", "")
        return level == SchoolLevel.ELEMENTARY && grade in 1..6 &&
            normalized == "성남정자초등학교"
    }

    fun isCurrentScope(context: Context, scope: SourceScope): Boolean {
        val current = scopeFor(context.applicationContext, scope.sourceId, scope.trigger) ?: return false
        return current.sourceId == scope.sourceId &&
            current.kind == scope.kind &&
            current.school == scope.school &&
            current.child == scope.child &&
            current.connectionGeneration == scope.connectionGeneration &&
            current.authorizationToken == scope.authorizationToken &&
            current.consentVersion == scope.consentVersion
    }
}

class SourceSyncWorker(context: Context, private val workerParameters: WorkerParameters) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val sourceId = SourceSyncScheduler.sourceId(workerParameters) ?: return Result.success()
        val trigger = SourceSyncScheduler.trigger(workerParameters)
        val disposition = SourceSyncRunner(applicationContext).run(sourceId, trigger)
        if (trigger == SourceRunTrigger.POSTING_WINDOW && disposition == SourceSyncRunDisposition.SUCCESS) {
            // One-shot window checks re-enqueue themselves for the next day.
            SourceSyncScheduler.scheduleLearnedWindows(applicationContext)
        }
        return when (disposition) {
            SourceSyncRunDisposition.SUCCESS -> Result.success()
            SourceSyncRunDisposition.RETRY -> Result.retry()
        }
    }
}

internal enum class SourceSyncRunDisposition {
    SUCCESS,
    RETRY,
}

internal class SourceSyncRunner(
    context: Context,
    private val store: SourceSyncStateStore = SourceSyncStateStore.get(context.applicationContext),
    private val fetcherProvider: (String) -> SourceFetcher? = SourceFetcherRegistry::get,
    private val ingestorFactory: (Context) -> SourceIngestor = { ProbeSourceIngestor(it) },
) {
    private val app = context.applicationContext

    suspend fun run(sourceId: String, trigger: SourceRunTrigger): SourceSyncRunDisposition {
        val lock = SourceRunLocks.lock(sourceId)
        return lock.withLock {
            try {
                withTimeout(SourceSyncLimits.READINESS_TIMEOUT_MS) { ProbeRepository.get(app).isReady.first { it } }
                val scope = SourceScopeFactory.scopeFor(app, sourceId, trigger) ?: return@withLock SourceSyncRunDisposition.SUCCESS
                try {
                    store.markRunning(scope) { SourceScopeFactory.isCurrentScope(app, scope) }
                } catch (error: java.io.IOException) {
                    if (!SourceScopeFactory.isCurrentScope(app, scope)) return@withLock SourceSyncRunDisposition.SUCCESS
                    throw error
                }
                if (!SourceScopeFactory.isCurrentScope(app, scope)) {
                    store.discardIfMatches(scope)
                    return@withLock SourceSyncRunDisposition.SUCCESS
                }
                val fetcher = fetcherProvider(sourceId)
                if (fetcher == null) {
                    if (SourceScopeFactory.isCurrentScope(app, scope)) {
                        store.recordResult(
                            scope,
                            SourceFetchResult(
                                sourceId = sourceId,
                                status = SourceSyncStatus.UNSUPPORTED,
                                fetchedAt = System.currentTimeMillis(),
                                coverage = scope.coverageWindow,
                                issues = listOf(SourceIssue(SourceIssueCode.UNSUPPORTED_SOURCE, "이 출처의 실제 조회 구현이 아직 연결되지 않았어요.")),
                            ),
                            null,
                        )
                    }
                    return@withLock SourceSyncRunDisposition.SUCCESS
                }
                val checkpoint = store.checkpoint(sourceId)
                val result = try {
                    withTimeout(SourceSyncLimits.RUN_TIMEOUT_MS) { fetcher.fetch(scope, checkpoint) }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    SourceFetchResult(
                        sourceId = sourceId,
                        status = SourceSyncStatus.PARTIAL,
                        fetchedAt = System.currentTimeMillis(),
                        coverage = scope.coverageWindow,
                        issues = listOf(SourceIssue(SourceIssueCode.DETAIL_FETCH_FAILED, "출처 조회 시간이 길어 일부만 확인했어요.", recoverable = true)),
                    )
                }
                if (result.status == SourceSyncStatus.AUTH_REQUIRED) {
                    if (SourceScopeFactory.isCurrentScope(app, scope)) {
                        store.recordResult(scope, result, null)
                        ConnectorRepository.get(app).requireReauth(sourceId)
                    }
                    return@withLock SourceSyncRunDisposition.SUCCESS
                }
                if (!SourceScopeFactory.isCurrentScope(app, scope)) {
                    store.discardIfMatches(scope)
                    return@withLock SourceSyncRunDisposition.SUCCESS
                }
                val receipt = if (result.canCommitRecords) ingestorFactory(app).ingest(scope, result) else null
                if (!SourceScopeFactory.isCurrentScope(app, scope)) {
                    store.discardIfMatches(scope)
                    return@withLock SourceSyncRunDisposition.SUCCESS
                }
                store.recordResult(scope, result, receipt)
                when {
                    result.status == SourceSyncStatus.OFFLINE -> SourceSyncRunDisposition.RETRY
                    result.status == SourceSyncStatus.PARTIAL && result.issues.any { it.isRetryableFetchIssue() } -> SourceSyncRunDisposition.RETRY
                    result.status == SourceSyncStatus.ERROR && result.issues.any { it.recoverable } -> SourceSyncRunDisposition.RETRY
                    receipt?.issues?.any { it.code == SourceIssueCode.STORAGE_ERROR && it.recoverable } == true -> SourceSyncRunDisposition.RETRY
                    else -> SourceSyncRunDisposition.SUCCESS
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                SourceSyncRunDisposition.RETRY
            }
        }
    }
}

private object SourceRunLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun lock(sourceId: String): Mutex = locks.getOrPut(sourceId) { Mutex() }
}

private fun SourceIssue.isRetryableFetchIssue(): Boolean =
    recoverable && code in setOf(SourceIssueCode.HTTP_ERROR, SourceIssueCode.NETWORK_UNAVAILABLE, SourceIssueCode.DETAIL_FETCH_FAILED)
