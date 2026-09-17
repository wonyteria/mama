package kr.mom.probe.connector

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kr.mom.probe.BuildConfig
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.FetchedNotice
import kr.mom.probe.sync.SourceAudienceFact
import kr.mom.probe.sync.SourceCheckpoint
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceFetcher
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceOrigin
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncLimits
import kr.mom.probe.sync.SourceSyncStatus

data class NeisSchool(
    val officeCode: String,
    val schoolCode: String,
    val name: String,
    val address: String,
    val level: String,
)

data class NeisEvent(
    val date: String,
    val name: String,
    val description: String,
    val audience: NoticeApplicability = NoticeApplicability.UNKNOWN,
)

sealed interface NeisResult<out T> {
    data class Success<T>(val value: T) : NeisResult<T>
    data class Failure(val message: String) : NeisResult<Nothing>
}

private data class NeisJsonResponse(
    val json: JSONObject,
    val byteCount: Int,
)

private val neisGradeFlags = mapOf(
    1 to listOf("ONE_GRADE_EVENT_YN", "FIRST_GRADE_EVENT_YN"),
    2 to listOf("TW_GRADE_EVENT_YN", "TWO_GRADE_EVENT_YN", "SECOND_GRADE_EVENT_YN"),
    3 to listOf("THREE_GRADE_EVENT_YN", "THR_GRADE_EVENT_YN"),
    4 to listOf("FR_GRADE_EVENT_YN", "FOUR_GRADE_EVENT_YN"),
    5 to listOf("FIV_GRADE_EVENT_YN", "FIVE_GRADE_EVENT_YN"),
    6 to listOf("SIX_GRADE_EVENT_YN"),
)

class NeisPublicClient(
    private val apiKey: String = BuildConfig.NEIS_API_KEY,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : SourceFetcher {
    val isSampleMode: Boolean get() = apiKey.isBlank()

    private fun authQuery(): String = if (apiKey.isBlank()) "" else "&KEY=${URLEncoder.encode(apiKey, StandardCharsets.UTF_8)}"

    suspend fun findExactSchool(name: String): NeisResult<NeisSchool> = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.length !in 2..60) return@withContext NeisResult.Failure("학교명을 정확히 입력해주세요.")
        val query = URLEncoder.encode(clean, StandardCharsets.UTF_8)
        val pageSize = if (isSampleMode) 5 else 100
        val response = when (val fetched = getJson("https://open.neis.go.kr/hub/schoolInfo?Type=json&pIndex=1&pSize=$pageSize&SCHUL_NM=$query${authQuery()}")) {
            is NeisResult.Success -> fetched.value
            is NeisResult.Failure -> return@withContext fetched
        }
        val resultCodes = response.resultCodes("schoolInfo")
        if (resultCodes.isEmpty()) return@withContext NeisResult.Failure("나이스 학교 검색 RESULT 구조를 확인하지 못했어요.")
        resultCodes.firstOrNull { it != "INFO-000" && it != "INFO-200" }?.let { result ->
            return@withContext NeisResult.Failure("나이스 API 오류 응답: $result")
        }
        if ("INFO-000" in resultCodes && "INFO-200" in resultCodes) {
            return@withContext NeisResult.Failure("나이스 학교 검색 RESULT 값이 서로 일치하지 않아요.")
        }
        if (resultCodes.all { it == "INFO-200" }) return@withContext NeisResult.Failure("학교를 찾지 못했어요. 학교 이름이나 지역을 함께 적어주세요.")
        val rawRows = response.rows("schoolInfo")
        val rows = rawRows.filter { row ->
            row.optString("ATPT_OFCDC_SC_CODE").isNotBlank() &&
                row.optString("SD_SCHUL_CODE").isNotBlank() &&
                row.optString("SCHUL_NM").isNotBlank()
        }
        if (rows.size != rawRows.size) return@withContext NeisResult.Failure("나이스 학교 검색 row 구조를 확인하지 못했어요.")
        val total = response.listTotalCount("schoolInfo") ?: return@withContext NeisResult.Failure("나이스 학교 검색 전체 row count를 확인하지 못했어요.")
        if (total > rows.size) return@withContext NeisResult.Failure("검색 결과가 많아요. 학교 이름을 더 길게 입력해주세요.")
        val exact = rows.filter { it.optString("SCHUL_NM").replace(" ", "") == clean.replace(" ", "") }
        val candidates = if (exact.isNotEmpty()) exact else rows
        when {
            candidates.isEmpty() -> NeisResult.Failure("학교를 찾지 못했어요. 학교 이름이나 지역을 함께 적어주세요.")
            candidates.size > 1 -> NeisResult.Failure("비슷한 학교가 여러 곳이에요. 지역명과 학교 이름을 함께 적어주세요.")
            else -> candidates.single().let { row -> NeisResult.Success(NeisSchool(
                officeCode = row.optString("ATPT_OFCDC_SC_CODE"), schoolCode = row.optString("SD_SCHUL_CODE"),
                name = row.optString("SCHUL_NM"), address = row.optString("ORG_RDNMA"), level = row.optString("SCHUL_KND_SC_NM"),
            )) }
        }
    }

    suspend fun upcomingEvents(school: NeisSchool, from: LocalDate = LocalDate.now(), days: Long = 30): NeisResult<List<NeisEvent>> = withContext(Dispatchers.IO) {
        val formatter = DateTimeFormatter.BASIC_ISO_DATE
        val pageSize = if (isSampleMode) 5 else 100
        val url = "https://open.neis.go.kr/hub/SchoolSchedule?Type=json&pIndex=1&pSize=$pageSize" +
            "&ATPT_OFCDC_SC_CODE=${school.officeCode}&SD_SCHUL_CODE=${school.schoolCode}" +
            "&AA_FROM_YMD=${from.format(formatter)}&AA_TO_YMD=${from.plusDays(days).format(formatter)}${authQuery()}"
        val response = when (val fetched = getJson(url)) {
            is NeisResult.Success -> fetched.value
            is NeisResult.Failure -> return@withContext fetched
        }
        val resultCodes = response.resultCodes("SchoolSchedule")
        if (resultCodes.isEmpty()) return@withContext NeisResult.Failure("나이스 일정 RESULT 구조를 확인하지 못했어요.")
        resultCodes.firstOrNull { it != "INFO-000" && it != "INFO-200" }?.let { result ->
            return@withContext NeisResult.Failure("나이스 API 오류 응답: $result")
        }
        if ("INFO-000" in resultCodes && "INFO-200" in resultCodes) {
            return@withContext NeisResult.Failure("나이스 일정 RESULT 값이 서로 일치하지 않아요.")
        }
        val total = response.listTotalCount("SchoolSchedule")
        val rows = response.rows("SchoolSchedule")
        if (resultCodes.all { it == "INFO-200" }) {
            return@withContext if (rows.isEmpty() && (total == null || total == 0)) {
                NeisResult.Success(emptyList())
            } else {
                NeisResult.Failure("나이스 일정 0건 코드와 row/count가 서로 일치하지 않아요.")
            }
        }
        if (total == 0 && rows.isNotEmpty()) {
            return@withContext NeisResult.Failure("나이스 일정 전체 row count 0건과 실제 row가 서로 일치하지 않아요.")
        }
        if (total == null && response.optJSONArray("SchoolSchedule") == null) {
            return@withContext NeisResult.Failure("나이스 일정 응답 구조를 확인하지 못했어요.")
        }
        val events = rows.map { row ->
            NeisEvent(row.optString("AA_YMD"), row.optString("EVENT_NM"), row.optString("EVENT_CNTNT"), audienceForGrade(row, 2).applicability)
        }
        if (events.isEmpty() && total != 0) return@withContext NeisResult.Failure("나이스 일정 row 구조를 확인하지 못했어요.")
        NeisResult.Success(events)
    }

    override suspend fun fetch(scope: SourceScope, checkpoint: SourceCheckpoint?): SourceFetchResult = withContext(Dispatchers.IO) {
        val fetchedAt = nowProvider()
        val coverage = requestedCoverage(scope)
        val invalid = validateScope(scope)
        if (invalid != null) {
            return@withContext SourceFetchResult(scope.sourceId, SourceSyncStatus.ERROR, fetchedAt, coverage = coverage, issues = listOf(invalid))
        }
        if (apiKey.isBlank()) {
            return@withContext SourceFetchResult(
                sourceId = scope.sourceId,
                status = SourceSyncStatus.PARTIAL,
                fetchedAt = fetchedAt,
                coverage = coverage.copy(complete = false),
                checkpoint = SourceCheckpoint(coverageWindow = coverage.copy(complete = false), lastFetchedAt = fetchedAt, sourceGeneration = scope.connectionGeneration),
                evidence = listOf(SourceEvidence("mode", "NEIS_API_KEY is empty")),
                issues = listOf(
                    SourceIssue(SourceIssueCode.MISSING_API_KEY, "나이스 전체 조회용 API 키가 설정되지 않았어요."),
                    SourceIssue(SourceIssueCode.SAMPLE_LIMITED, "키 없는 sample 조회를 전체 학교 일정으로 저장하지 않아요."),
                ),
            )
        }

        val formatter = DateTimeFormatter.BASIC_ISO_DATE
        val from = coverage.fromDateIso?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of("Asia/Seoul"))
        val to = coverage.toDateIso?.let(LocalDate::parse) ?: from.plusDays(30)
        val pageSize = 100
        val issues = mutableListOf<SourceIssue>()
        val items = mutableListOf<FetchedNotice>()
        val revisions = checkpoint?.itemRevisionHashes.orEmpty().toMutableMap()
        var page = 1
        var totalCount: Int? = null
        var fetchedRows = 0
        var runBytes = 0
        val seenItemIds = mutableSetOf<String>()
        while (page <= SourceSyncLimits.NEIS_PAGE_LIMIT && fetchedRows < SourceSyncLimits.NEIS_ROW_LIMIT) {
            val url = "https://open.neis.go.kr/hub/SchoolSchedule?Type=json&pIndex=$page&pSize=$pageSize" +
                "&ATPT_OFCDC_SC_CODE=${encode(scope.school.officeCode.orEmpty())}&SD_SCHUL_CODE=${encode(scope.school.schoolCode.orEmpty())}" +
                "&AA_FROM_YMD=${from.format(formatter)}&AA_TO_YMD=${to.format(formatter)}${authQuery()}"
            val jsonResponse = when (val fetched = getJsonResponse(url)) {
                is NeisResult.Success -> fetched.value
                is NeisResult.Failure -> {
                    issues += SourceIssue(SourceIssueCode.HTTP_ERROR, fetched.message, recoverable = true)
                    return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions)
                }
            }
            val response = jsonResponse.json
            runBytes += jsonResponse.byteCount
            if (runBytes > SourceSyncLimits.RUN_BYTES) {
                issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "나이스 조회 용량 상한에 도달했어요.", recoverable = true)
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions)
            }
            val resultCodes = response.resultCodes("SchoolSchedule")
            if (resultCodes.isEmpty()) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 RESULT 구조를 확인하지 못했어요.", recoverable = items.isNotEmpty())
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = items.isEmpty())
            }
            resultCodes.firstOrNull { it != "INFO-000" && it != "INFO-200" }?.let { resultCode ->
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 API 오류 응답: $resultCode")
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = true)
            }
            if ("INFO-000" in resultCodes && "INFO-200" in resultCodes) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 RESULT 값이 서로 일치하지 않아요.", recoverable = items.isNotEmpty())
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = items.isEmpty())
            }
            val rows = response.rows("SchoolSchedule")
            val responseTotalCount = response.listTotalCount("SchoolSchedule")
            if (resultCodes.all { it == "INFO-200" }) {
                if (rows.isNotEmpty() || (responseTotalCount != null && responseTotalCount != 0)) {
                    issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 0건 코드와 row/count가 서로 일치하지 않아요.", recoverable = items.isNotEmpty())
                    return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = items.isEmpty())
                }
                return@withContext if (items.isEmpty() && issues.isEmpty()) {
                    SourceFetchResult(
                        sourceId = scope.sourceId,
                        status = SourceSyncStatus.SUCCESS_EMPTY,
                        fetchedAt = fetchedAt,
                        coverage = coverage.copy(pageStart = 1, pageEnd = page, totalCount = 0, complete = true),
                        checkpoint = SourceCheckpoint(coverageWindow = coverage.copy(pageStart = 1, pageEnd = page, totalCount = 0, complete = true), lastFetchedAt = fetchedAt, sourceGeneration = scope.connectionGeneration),
                        evidence = listOf(SourceEvidence("neisResult", resultCodes.joinToString(","))),
                    )
                } else {
                    issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 total/page 응답이 중간에 0건으로 바뀌었어요.", recoverable = true)
                    neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions)
                }
            }
            if (responseTotalCount == null) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 전체 row count를 확인하지 못했어요.", recoverable = true)
            } else if (totalCount != null && totalCount != responseTotalCount) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 전체 row count가 페이지 사이에서 바뀌었어요.", recoverable = true)
            }
            totalCount = responseTotalCount ?: totalCount
            if (responseTotalCount == 0 && rows.isNotEmpty()) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 전체 row count 0건과 실제 row가 서로 일치하지 않아요.", recoverable = items.isNotEmpty())
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = items.isEmpty())
            }
            if (rows.isEmpty()) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 row 구조를 확인하지 못했어요.")
                return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = true)
            }
            rows.forEach { row ->
                val rowIssue = row.scheduleRowIssue(scope, from, to)
                if (rowIssue != null) {
                    issues += rowIssue
                    if (!rowIssue.recoverable) return@withContext neisResult(scope, fetchedAt, items, issues, coverage, page - 1, totalCount, revisions, forceError = true)
                    return@forEach
                }
                val notice = row.toFetchedNotice(scope, fetchedAt)
                if (!seenItemIds.add(notice.itemId)) {
                    issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 페이지가 중복 row를 반환했어요.", itemId = notice.itemId, recoverable = true)
                    return@forEach
                }
                revisions[notice.itemId] = notice.revisionHash
                items += notice
            }
            fetchedRows = seenItemIds.size
            if (totalCount != null && fetchedRows >= totalCount!!) break
            page++
        }
        if (totalCount != null && fetchedRows < totalCount!!) {
            issues += SourceIssue(SourceIssueCode.PAGE_LIMIT_EXCEEDED, "나이스 일정 페이지 상한 안에서 전체 행을 읽지 못했어요.", recoverable = true)
        }
        if (totalCount == null) {
            issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 전체 row count가 없어 완전 조회로 보지 않았어요.", recoverable = true)
        }
        neisResult(scope, fetchedAt, items, issues, coverage, minOf(page, SourceSyncLimits.NEIS_PAGE_LIMIT), totalCount, revisions)
    }

    private fun getJson(rawUrl: String): NeisResult<JSONObject> = when (val response = getJsonResponse(rawUrl)) {
        is NeisResult.Success -> NeisResult.Success(response.value.json)
        is NeisResult.Failure -> response
    }

    private fun getJsonResponse(rawUrl: String): NeisResult<NeisJsonResponse> {
        val uri = URI(rawUrl)
        if (uri.scheme != "https" || uri.host != "open.neis.go.kr" || (uri.port != -1 && uri.port != 443)) {
            return NeisResult.Failure("허용되지 않은 학교정보 주소예요.")
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            // The official endpoint returns HTTP 500 for Accept: application/json even
            // when Type=json is present; */* is the observed compatible representation.
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("User-Agent", "MomAgentProbe/0.5")
            if (connection.responseCode != 200) return NeisResult.Failure("나이스 서버가 응답하지 않았어요. (${connection.responseCode})")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.contains("json")) return NeisResult.Failure("나이스 응답 형식을 확인하지 못했어요.")
            val bytes = connection.inputStream.use { it.readLimitedBytes(SourceSyncLimits.RESPONSE_BYTES + 1) }
            if (bytes.size > SourceSyncLimits.RESPONSE_BYTES) return NeisResult.Failure("나이스 응답이 예상보다 커서 중단했어요.")
            NeisResult.Success(NeisJsonResponse(JSONObject(bytes.toString(Charsets.UTF_8)), bytes.size))
        } catch (error: Exception) {
            NeisResult.Failure("나이스 연결 오류: ${error.javaClass.simpleName}")
        } finally { connection.disconnect() }
    }

    private fun requestedCoverage(scope: SourceScope): SourceCoverageWindow {
        val from = scope.coverageWindow.fromDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(ZoneId.of("Asia/Seoul"))
        val to = scope.coverageWindow.toDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: from.plusDays(30)
        return SourceCoverageWindow(from.toString(), to.toString(), pageStart = 1, complete = false)
    }

    private fun validateScope(scope: SourceScope): SourceIssue? {
        if (scope.sourceId != SourceIds.NEIS_PUBLIC || scope.kind != SourceKind.NEIS_PUBLIC) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "나이스 공개 source scope가 아니에요.")
        }
        if (scope.school.officeCode.isNullOrBlank() || scope.school.schoolCode.isNullOrBlank()) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "나이스 학교 코드가 없어 일정을 조회하지 않았어요.")
        }
        return null
    }

    private fun neisResult(
        scope: SourceScope,
        fetchedAt: Long,
        items: List<FetchedNotice>,
        issues: List<SourceIssue>,
        requestedCoverage: SourceCoverageWindow,
        pageEnd: Int,
        totalCount: Int?,
        revisions: Map<String, String>,
        forceError: Boolean = false,
    ): SourceFetchResult {
        val partial = issues.any { it.recoverable || it.code in setOf(SourceIssueCode.PAGE_LIMIT_EXCEEDED, SourceIssueCode.CONTENT_LIMIT_EXCEEDED) }
        val complete = !forceError && !partial
        val coverage = requestedCoverage.copy(pageEnd = pageEnd, totalCount = totalCount, complete = complete)
        val status = when {
            forceError -> SourceSyncStatus.ERROR
            partial -> SourceSyncStatus.PARTIAL
            items.isEmpty() -> SourceSyncStatus.SUCCESS_EMPTY
            else -> SourceSyncStatus.FETCHED
        }
        return SourceFetchResult(
            sourceId = scope.sourceId,
            status = status,
            fetchedAt = fetchedAt,
            items = items,
            coverage = coverage,
            checkpoint = SourceCheckpoint(coverageWindow = coverage, lastFetchedAt = fetchedAt, sourceGeneration = scope.connectionGeneration, itemRevisionHashes = revisions),
            issues = issues.distinct(),
        )
    }

    private fun JSONObject.toFetchedNotice(scope: SourceScope, fetchedAt: Long): FetchedNotice {
        val date = optString("AA_YMD")
        val eventName = optString("EVENT_NM")
        val content = optString("EVENT_CNTNT")
        val itemId = listOf(scope.school.officeCode, scope.school.schoolCode, date, eventName).joinToString(":")
        val audienceFacts = audienceFactsForGradeFlags(this, scope.child.grade)
        val revision = ProbeRules.digest(listOf(itemId, content, audienceFacts.toString()).joinToString("\u0000"))
        return FetchedNotice(
            sourceId = scope.sourceId,
            itemId = itemId,
            revisionHash = revision,
            firstSeenAt = fetchedAt,
            publishedAt = null,
            title = eventName,
            body = content,
            origin = SourceOrigin(
                canonicalUrl = "https://open.neis.go.kr/hub/SchoolSchedule",
                host = "open.neis.go.kr",
                rawId = itemId,
            ),
            contentState = NoticeContentState.VERIFIED,
            obligation = NoticeObligation.INFORMATIONAL,
            audienceFacts = audienceFacts,
            dateFacts = listOf(SourceDateFact(NoticeDateRole.EVENT, date, date.toIsoBasicDate(), hasExplicitTime = false, evidence = SourceEvidence("AA_YMD", date))),
            evidence = listOf(SourceEvidence("schoolCode", scope.school.schoolCode.orEmpty()), SourceEvidence("schema", "SchoolSchedule")),
        )
    }

    private fun String.toIsoBasicDate(): String? = runCatching {
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE).toString()
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JSONObject.scheduleRowIssue(scope: SourceScope, from: LocalDate, to: LocalDate): SourceIssue? {
        val office = optString("ATPT_OFCDC_SC_CODE")
        if (office.isBlank()) {
            return SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 교육청 코드가 없어 요청 scope와 대조하지 않았어요.", recoverable = true)
        }
        if (office.isNotBlank() && office != scope.school.officeCode) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "나이스 일정 교육청 코드가 요청 scope와 일치하지 않아요.", recoverable = false)
        }
        val school = optString("SD_SCHUL_CODE")
        if (school.isBlank()) {
            return SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 학교 코드가 없어 요청 scope와 대조하지 않았어요.", recoverable = true)
        }
        if (school.isNotBlank() && school != scope.school.schoolCode) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "나이스 일정 학교 코드가 요청 scope와 일치하지 않아요.", recoverable = false)
        }
        val date = optString("AA_YMD").toIsoBasicDate()?.let(LocalDate::parse)
            ?: return SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 날짜를 확인하지 못했어요.", recoverable = true)
        if (date.isBefore(from) || date.isAfter(to)) {
            return SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 날짜가 요청 범위 밖이에요.", recoverable = true)
        }
        if (optString("EVENT_NM").isBlank()) {
            return SourceIssue(SourceIssueCode.SCHEMA_ERROR, "나이스 일정 제목을 확인하지 못했어요.", recoverable = true)
        }
        return null
    }
}

private fun JSONObject.rows(name: String): List<JSONObject> {
    val parts = optJSONArray(name) ?: return emptyList()
    for (index in 0 until parts.length()) {
        val rows = parts.optJSONObject(index)?.optJSONArray("row") ?: continue
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }
    return emptyList()
}

private fun JSONObject.resultCode(): String? = optJSONObject("RESULT")?.optString("CODE")?.takeIf { it.isNotBlank() }

private fun JSONObject.headResultCode(name: String): String? {
    val head = optJSONArray(name)?.optJSONObject(0)?.optJSONArray("head") ?: return null
    for (index in 0 until head.length()) {
        val code = head.optJSONObject(index)?.optJSONObject("RESULT")?.optString("CODE")
        if (!code.isNullOrBlank()) return code
    }
    return null
}

private fun JSONObject.resultCodes(name: String): List<String> =
    listOfNotNull(resultCode(), headResultCode(name)).distinct()

private fun JSONObject.listTotalCount(name: String): Int? {
    val head = optJSONArray(name)?.optJSONObject(0)?.optJSONArray("head") ?: return null
    for (index in 0 until head.length()) {
        val value = head.optJSONObject(index)?.optInt("list_total_count", -1) ?: -1
        if (value >= 0) return value
    }
    return null
}

private fun audienceForGrade(row: JSONObject, grade: Int?): SourceAudienceFact {
    val observed = observedGradeFlags(row)
    val present = observed.filterValues { it != null }
    if (present.isEmpty() || grade == null) {
        return SourceAudienceFact(
            applicability = NoticeApplicability.UNKNOWN,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = grade,
            gradeEnd = grade,
            schoolWide = false,
            evidence = SourceEvidence("NEIS grade flags", "UNKNOWN"),
        )
    }
    val normalized = present.mapValues { it.value!!.trim().uppercase() }
    val allExplicitYes = (1..6).all { normalized[it] == "Y" }
    val targetValue = normalized[grade]
    val applicability = when {
        allExplicitYes -> NoticeApplicability.APPLIES
        targetValue == "Y" -> NoticeApplicability.APPLIES
        targetValue == "N" -> NoticeApplicability.INELIGIBLE
        else -> NoticeApplicability.UNKNOWN
    }
    return SourceAudienceFact(
        applicability = applicability,
        schoolLevel = SchoolLevel.ELEMENTARY,
        gradeStart = grade,
        gradeEnd = grade,
        schoolWide = allExplicitYes,
        evidence = SourceEvidence("NEIS grade flags", normalized.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }),
    )
}

internal fun neisAudienceForGradeForTest(row: JSONObject, grade: Int?): SourceAudienceFact =
    audienceForGrade(row, grade)

private fun audienceFactsForGradeFlags(row: JSONObject, childGrade: Int?): List<SourceAudienceFact> {
    val observed = observedGradeFlags(row)
    val present = observed.filterValues { it != null }
    if (present.isEmpty()) {
        return listOf(SourceAudienceFact(
            applicability = NoticeApplicability.UNKNOWN,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = childGrade,
            gradeEnd = childGrade,
            schoolWide = false,
            evidence = SourceEvidence("NEIS grade flags", "UNKNOWN"),
        ))
    }
    val normalized = present.mapValues { it.value!!.trim().uppercase() }
    return normalized.toSortedMap().map { (grade, value) ->
        val applicability = when (value) {
            "Y" -> NoticeApplicability.APPLIES
            "N" -> NoticeApplicability.INELIGIBLE
            else -> NoticeApplicability.UNKNOWN
        }
        SourceAudienceFact(
            applicability = applicability,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = grade,
            gradeEnd = grade,
            schoolWide = normalized.size == 6 && normalized.values.all { it == "Y" },
            evidence = SourceEvidence("NEIS grade flag", "${grade}학년=$value"),
        )
    }
}

internal fun neisAudienceFactsForGradeFlagsForTest(row: JSONObject, childGrade: Int?): List<SourceAudienceFact> =
    audienceFactsForGradeFlags(row, childGrade)

private fun observedGradeFlags(row: JSONObject): Map<Int, String?> =
    neisGradeFlags.mapValues { (_, keys) ->
        var observedValue: String? = null
        for (key in keys) {
            val value = row.optString(key).takeIf { it.isNotBlank() }
            if (value != null) {
                observedValue = value
                break
            }
        }
        observedValue
    }

private fun InputStream.readLimitedBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}
