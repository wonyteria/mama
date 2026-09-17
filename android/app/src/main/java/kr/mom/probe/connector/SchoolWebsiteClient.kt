package kr.mom.probe.connector

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.document.DocumentCompleteness
import kr.mom.probe.document.DocumentIssueCode
import kr.mom.probe.document.DocumentTextExtractor
import kr.mom.probe.document.DocumentTextResult
import kr.mom.probe.sync.AttachmentFetchState
import kr.mom.probe.sync.SourceAttachment
import kr.mom.probe.sync.SourceCheckpoint
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceFetcher
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncLimits
import kr.mom.probe.sync.SourceSyncStatus

interface SchoolWebsiteHttp {
    fun get(url: String): SchoolWebsiteHttpResponse
    fun post(url: String, form: Map<String, String>): SchoolWebsiteHttpResponse
    suspend fun getBytes(url: String, maxBytes: Int): SchoolWebsiteBinaryResponse
}

data class SchoolWebsiteHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
)

data class SchoolWebsiteBinaryResponse(
    val statusCode: Int,
    val contentType: String,
    val bytes: ByteArray,
)

fun interface SchoolWebsiteDocumentTextExtractor {
    fun extract(bytes: ByteArray, filename: String?, mimeType: String?): DocumentTextResult
}

class SchoolWebsiteClient(
    private val http: SchoolWebsiteHttp = UrlConnectionSchoolWebsiteHttp(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val documentTextExtractor: SchoolWebsiteDocumentTextExtractor =
        SchoolWebsiteDocumentTextExtractor { bytes, filename, mimeType -> DocumentTextExtractor.extract(bytes, filename, mimeType) },
) : SourceFetcher {
    override suspend fun fetch(scope: SourceScope, checkpoint: SourceCheckpoint?): SourceFetchResult = withContext(Dispatchers.IO) {
        val fetchedAt = nowProvider()
        val today = Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val cutoff = today.minusDays(14)
        val baseCoverage = SourceCoverageWindow(fromDateIso = cutoff.toString(), toDateIso = today.toString(), pageStart = 1, pageEnd = 0, complete = false)
        val scopeIssue = validateScope(scope)
        if (scopeIssue != null) {
            return@withContext SourceFetchResult(
                sourceId = scope.sourceId,
                status = SourceSyncStatus.ERROR,
                fetchedAt = fetchedAt,
                coverage = baseCoverage,
                issues = listOf(scopeIssue),
            )
        }

        val issues = mutableListOf<SourceIssue>()
        val items = mutableListOf<kr.mom.probe.sync.FetchedNotice>()
        val revisions = checkpoint?.itemRevisionHashes.orEmpty().toMutableMap()
        val seenItemIds = mutableSetOf<String>()
        var runBytes = 0
        var pageEnd = 0
        var totalCount: Int? = null
        var detailCount = 0
        var cursor: String? = null
        val parsedResume = parseCursor(checkpoint?.cursor)
        val resume = if (parsedResume != null && checkpoint.canResume(parsedResume, scope, baseCoverage)) {
            parsedResume
        } else {
            if (parsedResume != null) {
                issues += SourceIssue(SourceIssueCode.CHECKPOINT_NOT_COMMITTED, "학교 게시판 checkpoint 범위가 현재 조회 범위와 맞지 않아 처음부터 다시 읽어요.", recoverable = true)
            }
            null
        }
        val boardPlan = orderedBoards(resume?.boardId)

        for (board in boardPlan) {
            val startPage = if (board.boardId == resume?.boardId) resume.page else 1
            val endPage = startPage + SourceSyncLimits.SCHOOL_BOARD_PAGE_LIMIT - 1
            val pagesToFetch = if (board.boardId == resume?.boardId && startPage > 1) {
                listOf(1) + (startPage..endPage).toList()
            } else {
                (startPage..endPage).toList()
            }
            var stoppedForOldEntries = false
            for (page in pagesToFetch) {
                val listResponse = runCatching { fetchList(board, page) }.getOrElse { error ->
                    issues += SourceIssue(SourceIssueCode.HTTP_ERROR, "학교 게시판 목록을 읽지 못했어요: ${error.javaClass.simpleName}", recoverable = true)
                    cursor = cursorFor(board, page)
                    return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                }
                runBytes += listResponse.body.toByteArray(Charsets.UTF_8).size
                if (runBytes > SourceSyncLimits.RUN_BYTES) {
                    issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "학교 게시판 조회 용량 상한에 도달했어요.", recoverable = true)
                    cursor = cursorFor(board, page)
                    return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                }
                if (listResponse.statusCode != 200 || !listResponse.contentType.lowercase().contains("html")) {
                    issues += SourceIssue(SourceIssueCode.HTTP_ERROR, "학교 게시판 목록 응답을 확인하지 못했어요. (${listResponse.statusCode})", recoverable = true)
                    cursor = cursorFor(board, page)
                    return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                }
                val listPage = SchoolWebsiteParser.parseList(listResponse.body, board)
                issues += listPage.issues
                pageEnd = maxOf(pageEnd, page)
                totalCount = maxOf(totalCount ?: 0, listPage.totalCount ?: 0)
                val totalPagesOnList = listPage.totalPages
                if (listPage.currentPage != page) {
                    issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "학교 게시판 페이지 번호가 요청과 일치하지 않아요.", recoverable = true)
                    cursor = cursorFor(board, page)
                    return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                }
                if (totalPagesOnList == null) {
                    issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "학교 게시판 전체 페이지 수를 확인하지 못했어요.", recoverable = true)
                    cursor = cursorFor(board, page)
                    return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                }
                val entries = listPage.entries
                if (entries.isEmpty()) break
                val entriesInScope = entries.filter { it.isInFetchWindow(cutoff, today) }
                for (entry in entriesInScope) {
                    if (!seenItemIds.add("${entry.board.boardId}:${entry.itemId}")) continue
                    if (detailCount >= SourceSyncLimits.SCHOOL_BOARD_DETAIL_LIMIT) {
                        issues += SourceIssue(SourceIssueCode.PAGE_LIMIT_EXCEEDED, "학교 게시판 상세 조회 상한에 도달했어요.", recoverable = true)
                        cursor = cursorFor(board, page)
                        return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                    }
                    val detailResponse = try {
                        http.get(entry.detailUrl)
                    } catch (error: Exception) {
                        issues += SourceIssue(SourceIssueCode.DETAIL_FETCH_FAILED, "게시물 상세를 읽지 못했어요: ${error.javaClass.simpleName}", itemId = entry.itemId, recoverable = true)
                        continue
                    }
                    runBytes += detailResponse.body.toByteArray(Charsets.UTF_8).size
                    detailCount++
                    if (detailResponse.statusCode != 200 || !detailResponse.contentType.lowercase().contains("html")) {
                        issues += SourceIssue(SourceIssueCode.DETAIL_FETCH_FAILED, "게시물 상세 응답을 확인하지 못했어요. (${detailResponse.statusCode})", itemId = entry.itemId, recoverable = true)
                        continue
                    }
                    val parsedDetail = SchoolWebsiteParser.parseDetail(detailResponse.body, board, entry.itemId)
                    val enrichment = if (parsedDetail.identityVerified) {
                        enrichAttachmentText(parsedDetail, remainingBytes = SourceSyncLimits.RUN_BYTES - runBytes, deadlineAt = fetchedAt + SourceSyncLimits.RUN_TIMEOUT_MS)
                    } else {
                        AttachmentTextEnrichment(parsedDetail, 0)
                    }
                    val detail = enrichment.detail
                    runBytes += enrichment.bytesRead
                    issues += detail.issues
                    if (!detail.identityVerified) continue
                    if (runBytes > SourceSyncLimits.RUN_BYTES) {
                        issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "학교 게시판 조회 용량 상한에 도달했어요.", recoverable = true)
                        cursor = cursorFor(board, page)
                        return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                    }
                    val notice = SchoolWebsiteParser.toFetchedNotice(scope, entry, detail, fetchedAt)
                    revisions[notice.itemId] = notice.revisionHash
                    items += notice
                    if (runBytes > SourceSyncLimits.RUN_BYTES) {
                        issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "학교 게시판 조회 용량 상한에 도달했어요.", recoverable = true)
                        cursor = cursorFor(board, page)
                        return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
                    }
                }
                if (entriesInScope.isEmpty() && entries.all { it.isOlderThanFetchWindow(cutoff, today) }) {
                    stoppedForOldEntries = true
                    break
                }
                if (totalPagesOnList <= page) {
                    stoppedForOldEntries = true
                    break
                }
            }
            if (!stoppedForOldEntries) {
                issues += SourceIssue(SourceIssueCode.PAGE_LIMIT_EXCEEDED, "학교 게시판 페이지 상한 안에서 전체 목록을 확인하지 못했어요.", recoverable = true)
                cursor = cursorFor(board, endPage + 1)
                return@withContext result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
            }
        }
        result(scope, fetchedAt, items, issues, revisions, baseCoverage, pageEnd, totalCount, cursor)
    }

    private fun validateScope(scope: SourceScope): SourceIssue? {
        if (scope.sourceId != SourceIds.SCHOOL_WEBSITE || scope.kind != SourceKind.SCHOOL_WEBSITE) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "학교 홈페이지 source scope가 아니에요.")
        }
        val schoolName = scope.school.schoolName.replace(" ", "")
        if (schoolName !in setOf("성남정자초등학교", "성남정자초")) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "검증된 성남정자초 학교 scope가 아니에요.")
        }
        val host = scope.school.officialHost?.lowercase()
        if (host != SchoolWebsiteParser.HOST) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "검증된 성남정자초 공식 host가 아니에요.")
        }
        val path = scope.school.officialPathPrefix
        if (path == null || !path.startsWith(SchoolWebsiteParser.PATH_PREFIX)) {
            return SourceIssue(SourceIssueCode.INVALID_SCOPE, "검증된 성남정자초 학교 path가 아니에요.")
        }
        return null
    }

    private fun fetchList(board: SchoolWebsiteBoard, page: Int): SchoolWebsiteHttpResponse =
        if (page == 1) {
            http.get(SchoolWebsiteParser.listUrl(board))
        } else {
            http.post(
                "https://${SchoolWebsiteParser.HOST}${SchoolWebsiteParser.PATH_PREFIX}na/ntt/selectNttList.do",
                mapOf(
                    "currPage" to page.toString(),
                    "listCo" to "10",
                    "bbsId" to board.boardId,
                    "mi" to board.menuId,
                    "searchType" to "",
                    "searchValue" to "",
                ),
            )
        }

    private fun result(
        scope: SourceScope,
        fetchedAt: Long,
        items: List<kr.mom.probe.sync.FetchedNotice>,
        issues: List<SourceIssue>,
        revisions: Map<String, String>,
        baseCoverage: SourceCoverageWindow,
        pageEnd: Int,
        totalCount: Int?,
        cursor: String?,
    ): SourceFetchResult {
        val partial = issues.any { it.recoverable || it.code in setOf(SourceIssueCode.PAGE_LIMIT_EXCEEDED, SourceIssueCode.CONTENT_LIMIT_EXCEEDED, SourceIssueCode.UNSUPPORTED_ATTACHMENT) } ||
            items.any { it.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED) }
        val coverage = baseCoverage.copy(pageEnd = pageEnd, totalCount = totalCount, complete = !partial)
        val status = when {
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
            checkpoint = SourceCheckpoint(cursor = cursor, coverageWindow = coverage, lastFetchedAt = fetchedAt, sourceGeneration = scope.connectionGeneration, itemRevisionHashes = revisions),
            issues = issues.distinct(),
        )
    }

    private fun cursorFor(board: SchoolWebsiteBoard, page: Int): String = "${board.boardId}:$page"

    private data class ResumeCursor(val boardId: String, val page: Int)

    private fun parseCursor(raw: String?): ResumeCursor? {
        val parts = raw?.split(":") ?: return null
        if (parts.size != 2) return null
        val boardId = parts[0]
        if (SchoolWebsiteParser.boards.none { it.boardId == boardId }) return null
        val page = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ResumeCursor(boardId, page)
    }

    private fun orderedBoards(startBoardId: String?): List<SchoolWebsiteBoard> {
        if (startBoardId == null) return SchoolWebsiteParser.boards
        val index = SchoolWebsiteParser.boards.indexOfFirst { it.boardId == startBoardId }
        if (index < 0) return SchoolWebsiteParser.boards
        return SchoolWebsiteParser.boards.drop(index) + SchoolWebsiteParser.boards.take(index)
    }

    private fun SourceCheckpoint?.canResume(resume: ResumeCursor, scope: SourceScope, coverage: SourceCoverageWindow): Boolean {
        val checkpoint = this ?: return false
        val checkpointCoverage = checkpoint.coverageWindow ?: return false
        return checkpoint.sourceGeneration == scope.connectionGeneration &&
            checkpointCoverage.fromDateIso == coverage.fromDateIso &&
            checkpointCoverage.toDateIso == coverage.toDateIso &&
            checkpointCoverage.pageStart == 1 &&
            (checkpointCoverage.pageEnd ?: 0) >= resume.page - 1
    }

    private fun SchoolWebsiteListEntry.isInFetchWindow(cutoff: LocalDate, today: LocalDate): Boolean {
        val published = publishedDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
        val inRecentWindow = !published.isBefore(cutoff) && !published.isAfter(today)
        val pinnedInCurrentAcademicYear = pinned && !published.isAfter(today) && academicYear(published) == academicYear(today)
        return inRecentWindow || pinnedInCurrentAcademicYear
    }

    private fun SchoolWebsiteListEntry.isOlderThanFetchWindow(cutoff: LocalDate, today: LocalDate): Boolean {
        val published = publishedDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return published.isBefore(cutoff) && !(pinned && academicYear(published) == academicYear(today))
    }

    private fun academicYear(date: LocalDate): Int = if (date.monthValue >= 3) date.year else date.year - 1

    private data class AttachmentTextEnrichment(
        val detail: SchoolWebsiteDetail,
        val bytesRead: Int,
    )

    private suspend fun enrichAttachmentText(
        detail: SchoolWebsiteDetail,
        remainingBytes: Int,
        deadlineAt: Long,
    ): AttachmentTextEnrichment {
        if (detail.attachments.isEmpty()) return AttachmentTextEnrichment(detail, 0)
        val bodyParts = mutableListOf<String>()
        if (detail.body.isNotBlank()) bodyParts += detail.body
        val attachments = detail.attachments.toMutableList()
        val issues = detail.issues.filterNot {
            it.code == SourceIssueCode.UNSUPPORTED_ATTACHMENT && it.message.contains("링크만 보존")
        }.toMutableList()
        val evidence = detail.evidence.toMutableList()
        var bytesRead = 0
        var sawCompleteText = false
        var sawPartialText = false
        var sawUnsupported = false
        var expectedDocumentAttachments = 0
        var unreadExpectedAttachments = false

        for (index in attachments.indices) {
            val attachment = attachments[index]
            if (!attachment.canFetchDocumentAttachment(detail.board)) continue
            expectedDocumentAttachments++
            currentCoroutineContext().ensureActive()
            val remainingForAttachment = remainingBytes - bytesRead
            if (remainingForAttachment <= 0 || nowProvider() >= deadlineAt) {
                unreadExpectedAttachments = true
                issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "첨부파일 조회 상한에 도달해 남은 첨부를 읽지 않았어요.", itemId = detail.itemId, recoverable = true)
                break
            }
            val maxAttachmentBytes = minOf(SourceSyncLimits.RESPONSE_BYTES, remainingForAttachment)
            val downloaded = try {
                http.getBytes(attachment.url, maxAttachmentBytes)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                // A transport can fail after consuming most of its admitted response.
                // Charge the full allowance so repeated failures cannot bypass the run cap.
                bytesRead += maxAttachmentBytes
                unreadExpectedAttachments = true
                issues += SourceIssue(SourceIssueCode.DETAIL_FETCH_FAILED, "첨부파일을 읽지 못했어요: ${error.javaClass.simpleName}", itemId = detail.itemId, recoverable = true)
                continue
            }
            currentCoroutineContext().ensureActive()
            bytesRead += downloaded.bytes.size
            if (downloaded.bytes.size > maxAttachmentBytes) {
                unreadExpectedAttachments = true
                issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "첨부파일 응답이 남은 안전 한도를 넘어 중단했어요.", itemId = detail.itemId, recoverable = true)
                return AttachmentTextEnrichment(
                    detail.copy(
                        body = bodyParts.joinToString("\n\n").trim(),
                        attachments = attachments,
                        issues = issues.distinct(),
                        evidence = evidence.distinct(),
                        contentState = when {
                            bodyParts.isNotEmpty() -> NoticeContentState.PARTIAL_EXTRACTION
                            else -> NoticeContentState.ATTACHMENT_MISSING
                        },
                    ),
                    bytesRead,
                )
            }
            if (downloaded.statusCode != 200) {
                unreadExpectedAttachments = true
                issues += SourceIssue(SourceIssueCode.DETAIL_FETCH_FAILED, "첨부파일 응답을 확인하지 못했어요. (${downloaded.statusCode})", itemId = detail.itemId, recoverable = true)
                continue
            }
            if (!downloaded.bytes.hasExpectedDocumentMagic(attachment.title)) {
                attachments[index] = attachment.copy(state = AttachmentFetchState.UNSUPPORTED)
                issues += SourceIssue(SourceIssueCode.UNSUPPORTED_ATTACHMENT, "첨부파일 형식이 제목과 일치하지 않아 본문을 읽지 않았어요.", itemId = detail.itemId, recoverable = true)
                sawUnsupported = true
                unreadExpectedAttachments = true
                continue
            }
            currentCoroutineContext().ensureActive()
            if (nowProvider() >= deadlineAt) {
                unreadExpectedAttachments = true
                issues += SourceIssue(SourceIssueCode.CONTENT_LIMIT_EXCEEDED, "첨부파일 본문 추출 시간 상한에 도달해 남은 첨부를 읽지 않았어요.", itemId = detail.itemId, recoverable = true)
                break
            }
            val extracted = documentTextExtractor.extract(downloaded.bytes, attachment.title, attachment.contentType ?: downloaded.contentType)
            currentCoroutineContext().ensureActive()
            extracted.issues.forEach { documentIssue ->
                issues += SourceIssue(documentIssue.toSourceIssueCode(), documentIssue.message, itemId = detail.itemId, recoverable = documentIssue.recoverable)
            }
            if (extracted.text.isNotBlank()) {
                bodyParts += extracted.text
                attachments[index] = attachment.copy(state = AttachmentFetchState.FETCHED)
                evidence += SourceEvidence("attachmentText:${attachment.title}", extracted.text.oneLineExcerpt())
                evidence += SourceEvidence("attachmentCompleteness:${attachment.title}", extracted.completeness.name)
                extracted.evidence.forEach { item -> evidence += SourceEvidence("attachmentExtractor:${attachment.title}", item) }
                when (extracted.completeness) {
                    DocumentCompleteness.COMPLETE -> sawCompleteText = true
                    DocumentCompleteness.PARTIAL -> sawPartialText = true
                    DocumentCompleteness.UNSUPPORTED -> {
                        sawUnsupported = true
                        unreadExpectedAttachments = true
                    }
                }
            } else if (extracted.completeness == DocumentCompleteness.UNSUPPORTED) {
                attachments[index] = attachment.copy(state = AttachmentFetchState.UNSUPPORTED)
                sawUnsupported = true
                unreadExpectedAttachments = true
            }
        }

        val hasUnreadLinkOnly = attachments.any { it.state == AttachmentFetchState.LINK_ONLY }
        if (hasUnreadLinkOnly) {
            issues += SourceIssue(SourceIssueCode.UNSUPPORTED_ATTACHMENT, "일부 첨부파일은 링크만 보존했고 본문 해석은 하지 않았어요.", itemId = detail.itemId)
        }
        val override = when {
            sawPartialText -> NoticeContentState.PARTIAL_EXTRACTION
            detail.body.isBlank() && sawCompleteText && (unreadExpectedAttachments || sawUnsupported || hasUnreadLinkOnly) -> NoticeContentState.PARTIAL_EXTRACTION
            detail.body.isBlank() && sawCompleteText && expectedDocumentAttachments > 0 -> NoticeContentState.VERIFIED
            detail.body.isBlank() && (unreadExpectedAttachments || sawUnsupported) -> NoticeContentState.ATTACHMENT_MISSING
            else -> detail.contentState
        }
        return AttachmentTextEnrichment(
            detail.copy(
                body = bodyParts.joinToString("\n\n").trim(),
                attachments = attachments,
                issues = issues.distinct(),
                evidence = evidence.distinct(),
                contentState = override,
            ),
            bytesRead,
        )
    }

    private fun SourceAttachment.canFetchDocumentAttachment(board: SchoolWebsiteBoard): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        val extension = title.substringAfterLast('.', "").lowercase()
        return SchoolWebsiteParser.isAllowedSchoolUrl(url) &&
            uri.query == null &&
            uri.fragment == null &&
            path.startsWith("/upload/snjj-e/na/bbs_${board.boardId}/") &&
            extension in setOf("hwp", "hwpx")
    }

    private fun ByteArray.hasExpectedDocumentMagic(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "hwp" -> startsWith(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
            "hwpx" -> startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            else -> false
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun kr.mom.probe.document.DocumentTextIssue.toSourceIssueCode(): SourceIssueCode = when (code) {
        DocumentIssueCode.CONTENT_LIMIT_EXCEEDED,
        DocumentIssueCode.INPUT_TOO_LARGE,
        DocumentIssueCode.CYCLE_DETECTED -> SourceIssueCode.CONTENT_LIMIT_EXCEEDED
        else -> SourceIssueCode.UNSUPPORTED_ATTACHMENT
    }

    private fun String.oneLineExcerpt(): String =
        replace(Regex("""\s+"""), " ").trim().take(240)
}

class UrlConnectionSchoolWebsiteHttp : SchoolWebsiteHttp {
    override fun get(url: String): SchoolWebsiteHttpResponse = open(url, "GET", emptyMap())

    override fun post(url: String, form: Map<String, String>): SchoolWebsiteHttpResponse = open(url, "POST", form)

    override suspend fun getBytes(url: String, maxBytes: Int): SchoolWebsiteBinaryResponse {
        if (!SchoolWebsiteParser.isAllowedSchoolUrl(url)) throw IllegalArgumentException("Disallowed school URL")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            currentCoroutineContext().ensureActive()
            connection.requestMethod = "GET"
            connection.connectTimeout = SourceSyncLimits.CONNECT_TIMEOUT_MS
            connection.readTimeout = SourceSyncLimits.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/octet-stream,*/*")
            connection.setRequestProperty("User-Agent", "MomAgentProbe/0.9")
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val limit = minOf(SourceSyncLimits.RESPONSE_BYTES, maxBytes)
            val bytes = stream?.use { it.readLimitedBytesCancellable(limit + 1) } ?: ByteArray(0)
            if (bytes.size > limit) throw IllegalStateException("Response limit exceeded")
            SchoolWebsiteBinaryResponse(status, connection.contentType.orEmpty(), bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(rawUrl: String, method: String, form: Map<String, String>): SchoolWebsiteHttpResponse {
        if (!SchoolWebsiteParser.isAllowedSchoolUrl(rawUrl)) throw IllegalArgumentException("Disallowed school URL")
        val connection = URI(rawUrl).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = SourceSyncLimits.CONNECT_TIMEOUT_MS
            connection.readTimeout = SourceSyncLimits.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/html,*/*")
            connection.setRequestProperty("User-Agent", "MomAgentProbe/0.9")
            if (method == "POST") {
                val encoded = form.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                }.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(encoded) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readLimitedBytes(SourceSyncLimits.RESPONSE_BYTES + 1) } ?: ByteArray(0)
            if (bytes.size > SourceSyncLimits.RESPONSE_BYTES) throw IllegalStateException("Response limit exceeded")
            SchoolWebsiteHttpResponse(status, connection.contentType.orEmpty(), bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
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

    private suspend fun InputStream.readLimitedBytesCancellable(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var remaining = limit
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }
}
