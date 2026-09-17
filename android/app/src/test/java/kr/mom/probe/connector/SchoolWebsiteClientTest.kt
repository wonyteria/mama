package kr.mom.probe.connector

import java.io.File
import java.time.Instant
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.document.DocumentCompleteness
import kr.mom.probe.document.DocumentFormat
import kr.mom.probe.document.DocumentIssueCode
import kr.mom.probe.document.DocumentTextIssue
import kr.mom.probe.document.DocumentTextResult
import kr.mom.probe.document.DocumentTypedText
import kr.mom.probe.sync.AttachmentFetchState
import kr.mom.probe.sync.CanonicalSchoolScope
import kr.mom.probe.sync.ChildSourceScope
import kr.mom.probe.sync.SourceCheckpoint
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncLimits
import kr.mom.probe.sync.SourceSyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SchoolWebsiteClientTest {
    private val parentClassAttachmentUrl = "https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_12359/2026/09/637E0D84-5917-9208-3DEF-475796380F5D.hwp"
    private val septemberFixtureNow = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()

    @Test fun rejectsMissingCanonicalSchoolScopeBeforeNetwork() = runBlocking {
        val client = SchoolWebsiteClient(ThrowingSchoolWebsiteHttp, nowProvider = { septemberFixtureNow })
        val wrongScope = scope().copy(school = CanonicalSchoolScope("다른초등학교"))

        val result = client.fetch(wrongScope, null)

        assertEquals(SourceSyncStatus.ERROR, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test fun reportsPartialWhenPostedPaginationReturnsWrongPage() = runBlocking {
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to resource("family-list-page1.html"),
                SchoolWebsiteParser.listUrl(board("12359")) to resource("family-list-page1.html"),
                SchoolWebsiteParser.listUrl(board("12570")) to resource("family-list-page1.html"),
            ),
            detail = resource("family-detail-1953062-grade6.html"),
            postBody = { _, _ -> resource("family-list-page1.html") },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(scope(), null)

        assertEquals(SourceSyncStatus.PARTIAL, result.status)
        assertEquals(3, result.items.size)
        assertTrue(result.issues.any { it.message.contains("페이지 번호") })
        assertTrue(result.checkpoint?.itemRevisionHashes?.isNotEmpty() == true)
    }

    @Test fun resumedFetchRechecksBoardFrontPageBeforeContinuingCursor() = runBlocking {
        val familyBoard = board("12359")
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to oldSingleEntryPage(board("12354"), 1),
                SchoolWebsiteParser.listUrl(familyBoard) to resource("family-list-page1.html"),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = resource("family-detail-1953062-grade6.html"),
            postBody = { _, form -> oldSingleEntryPage(familyBoard, form.getValue("currPage").toInt()) },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(
            scope(),
            SourceCheckpoint(
                cursor = "12359:2",
                coverageWindow = SourceCoverageWindow(fromDateIso = "2026-09-01", toDateIso = "2026-09-15", pageStart = 1, pageEnd = 1, complete = false),
                sourceGeneration = 1L,
            ),
        )

        assertTrue(http.gets.contains(SchoolWebsiteParser.listUrl(familyBoard)))
        assertTrue(http.posts.any { it["bbsId"] == "12359" && it["currPage"] == "2" })
        assertTrue(result.items.any { it.itemId == "1953062" })
    }

    @Test fun ignoresCursorWhenCheckpointGenerationOrCoverageDoesNotMatch() = runBlocking {
        val familyBoard = board("12359")
        val noticeBoard = board("12354")
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(noticeBoard) to oldSingleEntryPage(noticeBoard, 1),
                SchoolWebsiteParser.listUrl(familyBoard) to resource("family-list-page1.html"),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = resource("family-detail-1953062-grade6.html"),
            postBody = { _, form -> oldSingleEntryPage(familyBoard, form.getValue("currPage").toInt()) },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(
            scope(),
            SourceCheckpoint(
                cursor = "12359:2",
                coverageWindow = SourceCoverageWindow(fromDateIso = "2026-08-01", toDateIso = "2026-08-15", pageStart = 1, pageEnd = 1, complete = false),
                sourceGeneration = 99L,
            ),
        )

        assertEquals(SchoolWebsiteParser.listUrl(noticeBoard), http.gets.first())
        assertTrue(result.issues.any { it.code == SourceIssueCode.CHECKPOINT_NOT_COMMITTED })
    }

    @Test fun returnsPartialAndCursorWhenRecentPagesExceedPageLimit() = runBlocking {
        val noticeBoard = board("12354")
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(noticeBoard) to recentSingleEntryPage(noticeBoard, 1),
                SchoolWebsiteParser.listUrl(board("12359")) to oldSingleEntryPage(board("12359"), 1),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = resource("family-detail-1953062-grade6.html"),
            postBody = { _, form -> recentSingleEntryPage(noticeBoard, form.getValue("currPage").toInt()) },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(scope(), null)

        assertEquals(SourceSyncStatus.PARTIAL, result.status)
        assertTrue(result.issues.any { it.code == SourceIssueCode.PAGE_LIMIT_EXCEEDED })
        assertEquals("12354:6", result.checkpoint?.cursor)
        assertEquals(false, result.coverage.complete)
    }

    @Test fun downloadsSameBoardHwpAndRebuildsFactsFromPartialExtractedText() = runBlocking {
        val familyBoard = board("12359")
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to oldSingleEntryPage(board("12354"), 1),
                SchoolWebsiteParser.listUrl(familyBoard) to singleEntryPage(
                    board = familyBoard,
                    page = 1,
                    published = "2026.09.15",
                    itemId = "1951993",
                    title = "2026 학부모 수업 참여의 날 안내",
                    totalPages = 1,
                ),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = resource("family-detail-1951993-parent-day.html"),
            binaryBody = { url, _ ->
                assertEquals(parentClassAttachmentUrl, url)
                SchoolWebsiteBinaryResponse(200, "application/x-hwp", hwpOleMagic() + ByteArray(64))
            },
        )
        val extractor = SchoolWebsiteDocumentTextExtractor { _, filename, _ ->
            assertEquals("가정통신문(2026).hwp", filename)
            DocumentTextResult(
                format = DocumentFormat.HWP5,
                typedText = DocumentTypedText(listOf("대상: 초등 2학년 학부모", "2026 학부모 수업 참여의 날 안내")),
                completeness = DocumentCompleteness.PARTIAL,
                issues = listOf(DocumentTextIssue(DocumentIssueCode.EMBEDDED_BINARY_SKIPPED, "문서 안의 그림 자료는 본문으로 처리하지 않았어요.", recoverable = true)),
                evidence = listOf("BodyText/Section0"),
            )
        }
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow }, documentTextExtractor = extractor)

        val result = client.fetch(scope(), null)
        val notice = result.items.single { it.itemId == "1951993" }

        assertEquals(SourceSyncStatus.PARTIAL, result.status)
        assertEquals(NoticeContentState.PARTIAL_EXTRACTION, notice.contentState)
        assertTrue(notice.body.contains("초등 2학년"))
        assertTrue(notice.audienceFacts.any { it.applicability == NoticeApplicability.APPLIES && it.gradeStart == 2 })
        assertTrue(notice.attachments.any { it.url == parentClassAttachmentUrl && it.state == AttachmentFetchState.FETCHED })
        assertTrue(notice.evidence.any { it.label.startsWith("attachmentText:") && it.value.contains("초등 2학년") })
        assertTrue(notice.issues.any { it.message.contains("그림 자료") })
    }

    @Test fun extractsRealParentClassHwpFixtureWhenPresent() = runBlocking {
        val fixture = File("device-qa/public-fixtures/parent-class-notice-1951993.hwp")
        assumeTrue("public HWP fixture is intentionally kept outside app src", fixture.isFile)
        val familyBoard = board("12359")
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to oldSingleEntryPage(board("12354"), 1),
                SchoolWebsiteParser.listUrl(familyBoard) to singleEntryPage(
                    board = familyBoard,
                    page = 1,
                    published = "2026.09.15",
                    itemId = "1951993",
                    title = "2026 학부모 수업 참여의 날 안내",
                    totalPages = 1,
                ),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = resource("family-detail-1951993-parent-day.html"),
            binaryBody = { url, _ ->
                assertEquals(parentClassAttachmentUrl, url)
                SchoolWebsiteBinaryResponse(200, "application/x-hwp", fixture.readBytes())
            },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(scope(), null)
        val notice = result.items.single { it.itemId == "1951993" }

        assertEquals(NoticeContentState.PARTIAL_EXTRACTION, notice.contentState)
        assertTrue(notice.body.contains("학부모 수업 참여"))
        assertTrue(notice.body.contains("2학년"))
        assertTrue(notice.attachments.any { it.url == parentClassAttachmentUrl && it.state == AttachmentFetchState.FETCHED })
        assertTrue(notice.issues.any { it.code == SourceIssueCode.UNSUPPORTED_ATTACHMENT })
    }

    @Test fun completeTextPlusUnreadAttachmentStaysPartialWhenHtmlBodyIsEmpty() = runBlocking {
        val familyBoard = board("12359")
        val secondAttachmentUrl = "https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_12359/2026/09/second.hwp"
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to oldSingleEntryPage(board("12354"), 1),
                SchoolWebsiteParser.listUrl(familyBoard) to singleEntryPage(
                    board = familyBoard,
                    page = 1,
                    published = "2026.09.15",
                    itemId = "1951993",
                    title = "2026 학부모 수업 참여의 날 안내",
                    totalPages = 1,
                ),
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = detailWithAttachments("1951993", parentClassAttachmentUrl, secondAttachmentUrl),
            binaryBody = { url, _ ->
                if (url == parentClassAttachmentUrl) {
                    SchoolWebsiteBinaryResponse(200, "application/x-hwp", hwpOleMagic() + ByteArray(16))
                } else {
                    SchoolWebsiteBinaryResponse(500, "text/plain", ByteArray(0))
                }
            },
        )
        val extractor = SchoolWebsiteDocumentTextExtractor { _, _, _ ->
            DocumentTextResult(
                format = DocumentFormat.HWP5,
                typedText = DocumentTypedText(listOf("대상: 초등 2학년 학부모")),
                completeness = DocumentCompleteness.COMPLETE,
            )
        }
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow }, documentTextExtractor = extractor)

        val notice = client.fetch(scope(), null).items.single { it.itemId == "1951993" }

        assertEquals(NoticeContentState.PARTIAL_EXTRACTION, notice.contentState)
        assertTrue(notice.attachments.any { it.url == parentClassAttachmentUrl && it.state == AttachmentFetchState.FETCHED })
        assertTrue(notice.attachments.any { it.url == secondAttachmentUrl && it.state == AttachmentFetchState.LINK_ONLY })
        assertTrue(notice.issues.any { it.code == SourceIssueCode.DETAIL_FETCH_FAILED })
    }

    @Test fun stopsAttachmentRequestsWhenRemainingByteBudgetIsExceeded() = runBlocking {
        val familyBoard = board("12359")
        val secondAttachmentUrl = "https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_12359/2026/09/second.hwp"
        val calls = mutableListOf<Pair<String, Int>>()
        val familyList = singleEntryPage(
            board = familyBoard,
            page = 1,
            published = "2026.09.15",
            itemId = "1951993",
            title = "2026 학부모 수업 참여의 날 안내",
            totalPages = 1,
        )
        val baseDetail = detailWithAttachments("1951993", parentClassAttachmentUrl, secondAttachmentUrl)
        val targetRemaining = 256
        val fillerBytes = SourceSyncLimits.RUN_BYTES -
            familyList.toByteArray(Charsets.UTF_8).size -
            baseDetail.toByteArray(Charsets.UTF_8).size -
            targetRemaining
        val largeDetail = detailWithAttachments(
            itemId = "1951993",
            firstUrl = parentClassAttachmentUrl,
            secondUrl = secondAttachmentUrl,
            filler = "x".repeat(fillerBytes.coerceAtLeast(0)),
        )
        val http = FixtureSchoolWebsiteHttp(
            mapOf(
                SchoolWebsiteParser.listUrl(board("12354")) to oldSingleEntryPage(board("12354"), 1),
                SchoolWebsiteParser.listUrl(familyBoard) to familyList,
                SchoolWebsiteParser.listUrl(board("12570")) to oldSingleEntryPage(board("12570"), 1),
            ),
            detail = largeDetail,
            binaryBody = { url, maxBytes ->
                calls += url to maxBytes
                SchoolWebsiteBinaryResponse(200, "application/x-hwp", ByteArray(maxBytes + 1))
            },
        )
        val client = SchoolWebsiteClient(http, nowProvider = { septemberFixtureNow })

        val result = client.fetch(
            scope(),
            SourceCheckpoint(
                cursor = "12359:1",
                coverageWindow = SourceCoverageWindow(fromDateIso = "2026-09-01", toDateIso = "2026-09-15", pageStart = 1, pageEnd = 0, complete = false),
                sourceGeneration = 1L,
            ),
        )

        assertEquals(SourceSyncStatus.PARTIAL, result.status)
        assertEquals(1, calls.size)
        assertTrue(calls.single().second in 1 until SourceSyncLimits.RESPONSE_BYTES)
        assertTrue(result.issues.any { it.code == SourceIssueCode.CONTENT_LIMIT_EXCEEDED })
    }

    private fun board(id: String): SchoolWebsiteBoard = SchoolWebsiteParser.boards.first { it.boardId == id }

    private fun scope() = SourceScope(
        sourceId = SourceIds.SCHOOL_WEBSITE,
        kind = SourceKind.SCHOOL_WEBSITE,
        school = CanonicalSchoolScope("성남정자초등학교", officialHost = SchoolWebsiteParser.HOST, officialPathPrefix = SchoolWebsiteParser.PATH_PREFIX),
        child = ChildSourceScope(schoolLevel = SchoolLevel.ELEMENTARY, grade = 2),
        connectionGeneration = 1L,
        consentEpoch = 1L,
        consentVersion = "test",
        coverageWindow = SourceCoverageWindow(),
        trigger = SourceRunTrigger.MANUAL,
    )

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("schoolweb/$name")).readText(Charsets.UTF_8)

    private fun recentSingleEntryPage(board: SchoolWebsiteBoard, page: Int): String = singleEntryPage(board, page, "2026.09.15")

    private fun oldSingleEntryPage(board: SchoolWebsiteBoard, page: Int): String = singleEntryPage(board, page, "2026.01.01")

    private fun singleEntryPage(
        board: SchoolWebsiteBoard,
        page: Int,
        published: String,
        itemId: String = "1953062",
        title: String = "2026학년도 6학년 1일형 현장체험학습 스쿨뱅킹 안내장",
        totalPages: Int = 43,
    ): String = """
        <form id="srchForm" method="post">
            <input name="currPage" value="$page" />
            <input name="listCo" value="10" />
            <input name="bbsId" value="${board.boardId}" />
            <input name="mi" value="${board.menuId}" />
        </form>
        <form id="pagingForm" method="post"></form>
        <div>전체 <strong>427</strong>건</div>
        <strong class="pc_blue">$page</strong> / $totalPages 페이지
        <table><tbody>
            <tr>
                <td></td>
                <td><a class="nttInfoBtn" data-id="$itemId">$title</a></td>
                <td>$published</td>
            </tr>
        </tbody></table>
    """.trimIndent()

    private fun hwpOleMagic(): ByteArray =
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    private fun detailWithAttachments(itemId: String, firstUrl: String, secondUrl: String, filler: String = ""): String = """
        <form name="nttViewForm" id="nttViewForm" method="post">
            <input type="hidden" name="mi" value="14305"/>
            <input type="hidden" name="bbsId" value="12359"/>
            <input type="hidden" name="nttSn" value="$itemId"/>
            <table><tbody>
                <tr><th scope="col" colspan="2" class="title">2026 학부모 수업 참여의 날 안내</th></tr>
                <tr><th scope="row">등록일</th><td>2026.09.15</td></tr>
            <tr class="cont"><td colspan="2"></td></tr>
        </tbody></table>
        </form>
        $filler
        <script>
            DEXT5UPLOAD.AddUploadedFile('0', '가정통신문(2026).hwp', '$firstUrl', '265216');
            DEXT5UPLOAD.AddUploadedFile('1', '추가안내.hwp', '$secondUrl', '100');
        </script>
    """.trimIndent()
}

private object ThrowingSchoolWebsiteHttp : SchoolWebsiteHttp {
    override fun get(url: String): SchoolWebsiteHttpResponse = error("network should not be called")
    override fun post(url: String, form: Map<String, String>): SchoolWebsiteHttpResponse = error("network should not be called")
    override suspend fun getBytes(url: String, maxBytes: Int): SchoolWebsiteBinaryResponse = error("network should not be called")
}

private class FixtureSchoolWebsiteHttp(
    private val lists: Map<String, String>,
    private val detail: String,
    private val postBody: (String, Map<String, String>) -> String = { _, _ -> lists.values.first() },
    private val binaryBody: (String, Int) -> SchoolWebsiteBinaryResponse = { _, _ -> SchoolWebsiteBinaryResponse(404, "text/plain", ByteArray(0)) },
) : SchoolWebsiteHttp {
    val gets = mutableListOf<String>()
    val posts = mutableListOf<Map<String, String>>()

    override fun get(url: String): SchoolWebsiteHttpResponse {
        gets += url
        val body = lists[url] ?: detailFor(url)
        return SchoolWebsiteHttpResponse(200, "text/html; charset=UTF-8", body)
    }

    override fun post(url: String, form: Map<String, String>): SchoolWebsiteHttpResponse {
        posts += form
        return SchoolWebsiteHttpResponse(200, "text/html; charset=UTF-8", postBody(url, form))
    }

    override suspend fun getBytes(url: String, maxBytes: Int): SchoolWebsiteBinaryResponse = binaryBody(url, maxBytes)

    private fun detailFor(url: String): String {
        val uri = java.net.URI(url)
        val query = uri.rawQuery.orEmpty().split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val mi = query["mi"].orEmpty()
        val bbsId = query["bbsId"].orEmpty()
        val nttSn = query["nttSn"].orEmpty()
        val detailMatchesRequest = detail.contains("name=\"mi\" value=\"$mi\"") &&
            detail.contains("name=\"bbsId\" value=\"$bbsId\"") &&
            detail.contains("name=\"nttSn\" value=\"$nttSn\"")
        return if (detailMatchesRequest) detail else """
            <form name="nttViewForm" id="nttViewForm" method="post">
                <input type="hidden" name="mi" value="$mi"/>
                <input type="hidden" name="bbsId" value="$bbsId"/>
                <input type="hidden" name="nttSn" value="$nttSn" />
                <table><tbody>
                    <tr><th scope="col" colspan="2" class="title">fixture $nttSn</th></tr>
                    <tr><th scope="row">등록일</th><td>2026.09.15</td></tr>
                    <tr class="cont"><td colspan="2">fixture body $nttSn</td></tr>
                </tbody></table>
            </form>
        """.trimIndent()
    }
}
