package kr.mom.probe.connector

import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.AttachmentFetchState
import kr.mom.probe.sync.FetchedNotice
import kr.mom.probe.sync.SourceAttachment
import kr.mom.probe.sync.SourceAudienceFact
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceOrigin
import kr.mom.probe.sync.SourceScope

data class SchoolWebsiteBoard(
    val boardId: String,
    val menuId: String,
    val label: String,
)

data class SchoolWebsiteListPage(
    val board: SchoolWebsiteBoard,
    val currentPage: Int?,
    val totalPages: Int?,
    val totalCount: Int?,
    val entries: List<SchoolWebsiteListEntry>,
    val issues: List<SourceIssue> = emptyList(),
)

data class SchoolWebsiteListEntry(
    val board: SchoolWebsiteBoard,
    val itemId: String,
    val title: String,
    val publishedDateIso: String?,
    val pinned: Boolean,
    val detailUrl: String,
)

data class SchoolWebsiteDetail(
    val board: SchoolWebsiteBoard,
    val itemId: String,
    val title: String,
    val publishedDateIso: String?,
    val body: String,
    val attachments: List<SourceAttachment>,
    val identityVerified: Boolean,
    val evidence: List<SourceEvidence> = emptyList(),
    val contentState: NoticeContentState? = null,
    val issues: List<SourceIssue> = emptyList(),
)

object SchoolWebsiteParser {
    const val HOST = "snjj-e.goesn.kr"
    const val PATH_PREFIX = "/snjj-e/"
    private val seoul = ZoneId.of("Asia/Seoul")
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dottedDate = Regex("""(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})""")
    private val listRow = Regex("""<tr\b[\s\S]*?</tr>""", RegexOption.IGNORE_CASE)
    private val anchor = Regex("""<a\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bnttInfoBtn\b[^"']*["'])([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val dataId = Regex("""\bdata-id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val pageCount = Regex("""전체\s*<strong[^>]*>\s*([\d,]+)\s*</strong>\s*건""", RegexOption.IGNORE_CASE)
    private val currentPage = Regex("""<strong[^>]*class\s*=\s*["'][^"']*pc_blue[^"']*["'][^>]*>\s*(\d+)\s*</strong>\s*/\s*(\d+)\s*페이지""", RegexOption.IGNORE_CASE)
    private val titleCell = Regex("""<th\b(?=[^>]*\bclass\s*=\s*["'][^"']*\btitle\b[^"']*["'])[^>]*>([\s\S]*?)</th>""", RegexOption.IGNORE_CASE)
    private val uploadedFile = Regex(
        """DEXT5UPLOAD\.AddUploadedFile\([^,]+,\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'""",
        RegexOption.IGNORE_CASE,
    )
    private val href = Regex("""<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val elementaryRange = Regex("""(?:초등|초등학교|초)\s*(?<!\d)([1-6])\s*(?:~|-|∼|부터)\s*(?<!\d)([1-6])\s*학년(?!도)|(?:초등|초등학교|초)\s*(?<!\d)([1-6])\s*학년(?!도)""")
    private val middleRange = Regex("""(?:중등|중학교|중)\s*(?<!\d)([1-3])\s*(?:~|-|∼|부터)\s*(?<!\d)([1-3])\s*학년(?!도)|(?:중등|중학교|중)\s*(?<!\d)([1-3])\s*학년(?!도)|중학생""")
    private val bareElementaryGrade = Regex("""(?<![초중\d])([1-6])\s*학년(?!도)""")
    private val schoolWide = Regex("""전\s*학년|전체\s*학년|학생\s*및\s*학부모|학부모\s*대상|교육\s*가족""")
    private val employment = Regex("""채용|강사\s*모집|교직원|선거""")
    private val optional = Regex("""선택|희망자|희망|방과후|프로그램|수강\s*신청|참여를\s*희망|모집""")
    private val required = Regex("""준비물|챙겨|가져오|제출|회신|납부|입금|스쿨뱅킹|마감|기한""")
    private val notRequired = Regex("""제출할\s*필요가\s*없|회신할\s*필요가\s*없|납부하지\s*않|납부\s*없|준비물\s*없|별도\s*준비물\s*없""")
    private val whitespace = Regex("""\s+""")

    val boards: List<SchoolWebsiteBoard> = listOf(
        SchoolWebsiteBoard("12354", "14296", "공지사항"),
        SchoolWebsiteBoard("12359", "14305", "가정통신문"),
        SchoolWebsiteBoard("12570", "14704", "공지사항(초1~2 맞춤형, 수익자 방과후)"),
    )

    fun parseList(html: String, board: SchoolWebsiteBoard): SchoolWebsiteListPage {
        val issues = mutableListOf<SourceIssue>()
        if (!html.contains("id=\"srchForm\"") || !html.contains("id=\"pagingForm\"")) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_MISSING, "학교 게시판 목록 form 구조를 확인하지 못했어요.", recoverable = true)
        }
        val totalCount = pageCount.find(html)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val pageMatch = currentPage.findAll(html).firstOrNull()
        val page = pageMatch?.groupValues?.get(1)?.toIntOrNull()
        val totalPages = pageMatch?.groupValues?.get(2)?.toIntOrNull()
        val entries = listRow.findAll(html).mapNotNull { rowMatch ->
            val row = rowMatch.value
            val anchorMatch = anchor.find(row) ?: return@mapNotNull null
            val itemId = dataId.find(anchorMatch.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (itemId.isBlank()) return@mapNotNull null
            val title = visibleText(anchorMatch.groupValues[2])
            val rowText = visibleText(row)
            val dateIso = dottedDate.find(rowText)?.toIsoDate()
            SchoolWebsiteListEntry(
                board = board,
                itemId = itemId,
                title = title,
                publishedDateIso = dateIso,
                pinned = rowText.startsWith("공지"),
                detailUrl = detailUrl(board, itemId),
            )
        }.toList()
        if (entries.isEmpty()) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "학교 게시판 목록에서 게시물 링크를 찾지 못했어요.", recoverable = true)
        }
        return SchoolWebsiteListPage(board, page, totalPages, totalCount, entries, issues)
    }

    fun parseDetail(html: String, board: SchoolWebsiteBoard, itemId: String): SchoolWebsiteDetail {
        val issues = mutableListOf<SourceIssue>()
        val formHtml = balancedElement(html, "form", Regex("""\bid\s*=\s*["']nttViewForm["']""", RegexOption.IGNORE_CASE))
        if (formHtml == null) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_MISSING, "게시물 상세 form을 확인하지 못했어요.", itemId = itemId, recoverable = true)
        }
        val scopedHtml = formHtml.orEmpty()
        val actualBoardId = inputValue(scopedHtml, "bbsId")
        val actualMenuId = inputValue(scopedHtml, "mi")
        val actualItemId = inputValue(scopedHtml, "nttSn") ?: inputValue(scopedHtml, "baseSn")
        val identityVerified = actualBoardId == board.boardId && actualMenuId == board.menuId && actualItemId == itemId
        if (formHtml != null && !identityVerified) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "요청한 게시물 ID와 상세 form의 ID가 일치하지 않아요.", itemId = itemId, recoverable = true)
        }
        val title = titleCell.find(scopedHtml)?.groupValues?.get(1)?.let(::visibleText).orEmpty()
        if (title.isBlank()) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "게시물 상세 제목을 찾지 못했어요.", itemId = itemId, recoverable = true)
        }
        val bodyHtml = balancedElement(scopedHtml, "tr", Regex("""\bclass\s*=\s*["'][^"']*\bcont\b[^"']*["']""", RegexOption.IGNORE_CASE))
        val body = bodyHtml?.let(::visibleText).orEmpty()
        if (bodyHtml == null || body.isBlank()) {
            issues += SourceIssue(SourceIssueCode.DOM_CONTRACT_CHANGED, "게시물 상세 본문을 찾지 못했어요.", itemId = itemId, recoverable = true)
        }
        val published = fieldAfterHeader(scopedHtml, "등록일")?.let { dottedDate.find(it)?.toIsoDate() }
        val attachments = parseAttachments(scopedHtml + "\n" + uploadInitializersForBoard(html, board))
        val contentIssue = if (attachments.isNotEmpty()) {
            SourceIssue(SourceIssueCode.UNSUPPORTED_ATTACHMENT, "첨부파일은 링크만 보존했고 본문 해석은 하지 않았어요.", itemId = itemId)
        } else {
            null
        }
        return SchoolWebsiteDetail(
            board = board,
            itemId = itemId,
            title = title,
            publishedDateIso = published,
            body = body,
            attachments = attachments,
            identityVerified = identityVerified,
            issues = issues + listOfNotNull(contentIssue),
        )
    }

    fun toFetchedNotice(
        scope: SourceScope,
        entry: SchoolWebsiteListEntry,
        detail: SchoolWebsiteDetail,
        fetchedAt: Long,
    ): FetchedNotice {
        val title = detail.title.ifBlank { entry.title }
        val body = detail.body
        val publishedIso = detail.publishedDateIso ?: entry.publishedDateIso
        val publishedAt = publishedIso?.let { LocalDate.parse(it).atStartOfDay(seoul).toInstant().toEpochMilli() }
        val bodyForHash = listOf(title, publishedIso.orEmpty(), body, detail.attachments.joinToString("|") { it.url }).joinToString("\u0000")
        val audienceFacts = audienceFacts("$title $body", scope)
        val dateFacts = dateFacts("$title $body", publishedIso)
        val obligation = obligation("$title $body")
        val contentState = detail.contentState ?: when {
            body.isBlank() && detail.attachments.isNotEmpty() -> NoticeContentState.ATTACHMENT_MISSING
            body.isBlank() -> NoticeContentState.FAILED
            detail.attachments.isNotEmpty() && body.contains("첨부") && body.length < 80 -> NoticeContentState.ATTACHMENT_MISSING
            else -> NoticeContentState.VERIFIED
        }
        return FetchedNotice(
            sourceId = scope.sourceId,
            itemId = entry.itemId,
            revisionHash = ProbeRules.digest(bodyForHash),
            firstSeenAt = fetchedAt,
            publishedAt = publishedAt,
            title = title,
            body = body,
            origin = SourceOrigin(
                canonicalUrl = detailUrl(entry.board, entry.itemId),
                host = HOST,
                boardId = entry.board.boardId,
                rawId = entry.itemId,
            ),
            contentState = contentState,
            obligation = obligation,
            audienceFacts = audienceFacts,
            dateFacts = dateFacts,
            attachments = detail.attachments,
            evidence = listOf(
                SourceEvidence("board", entry.board.label),
                SourceEvidence("dom", "#nttViewForm th.title + tr.cont"),
            ) + detail.evidence,
            issues = detail.issues,
        )
    }

    fun detailUrl(board: SchoolWebsiteBoard, itemId: String): String =
        "https://$HOST$PATH_PREFIX" + "na/ntt/selectNttInfo.do?mi=${board.menuId}&bbsId=${board.boardId}&nttSn=$itemId"

    fun listUrl(board: SchoolWebsiteBoard): String =
        "https://$HOST$PATH_PREFIX" + "na/ntt/selectNttList.do?bbsId=${board.boardId}&mi=${board.menuId}"

    fun isAllowedSchoolUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        val portAllowed = uri.port == -1 || uri.port == 443
        return uri.scheme == "https" && uri.userInfo == null && portAllowed && uri.host?.lowercase() == HOST &&
            (path.startsWith(PATH_PREFIX) || path.startsWith("/upload/snjj-e/"))
    }

    private fun audienceFacts(source: String, scope: SourceScope): List<SourceAudienceFact> {
        val explicitElementary = elementaryRange.findAll(source).mapNotNull { match ->
            val single = match.groupValues[3].toIntOrNull()
            val start = single ?: match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = single ?: match.groupValues[2].toIntOrNull() ?: start
            audienceFact(scope, SchoolLevel.ELEMENTARY, start, end, match.value.trim())
        }.toList()
        val explicitMiddle = middleRange.findAll(source).mapNotNull { match ->
            if (match.value == "중학생") return@mapNotNull audienceFact(scope, SchoolLevel.MIDDLE, 1, 3, match.value)
            val single = match.groupValues[3].toIntOrNull()
            val start = single ?: match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = single ?: match.groupValues[2].toIntOrNull() ?: start
            audienceFact(scope, SchoolLevel.MIDDLE, start, end, match.value.trim())
        }.toList()
        val facts = explicitElementary + explicitMiddle
        if (facts.isNotEmpty()) return facts
        val bareFacts = bareElementaryGrade.findAll(source).mapNotNull { match ->
            val grade = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            audienceFact(scope, SchoolLevel.ELEMENTARY, grade, grade, match.value.trim())
        }.toList()
        if (bareFacts.isNotEmpty()) return bareFacts
        if (schoolWide.containsMatchIn(source)) {
            return listOf(SourceAudienceFact(
                applicability = NoticeApplicability.APPLIES,
                schoolLevel = scope.child.schoolLevel,
                gradeStart = scope.child.grade,
                gradeEnd = scope.child.grade,
                schoolWide = true,
                evidence = SourceEvidence("audience", schoolWide.find(source)?.value.orEmpty()),
            ))
        }
        return listOf(SourceAudienceFact(
            applicability = NoticeApplicability.UNKNOWN,
            schoolLevel = scope.child.schoolLevel,
            schoolWide = false,
            evidence = SourceEvidence("audience", ""),
        ))
    }

    private fun audienceFact(
        scope: SourceScope,
        level: SchoolLevel,
        start: Int,
        end: Int,
        evidence: String,
    ): SourceAudienceFact {
        val childGrade = scope.child.grade
        val childLevel = scope.child.schoolLevel
        val applicability = when {
            childGrade == null || childLevel == SchoolLevel.UNKNOWN -> NoticeApplicability.UNKNOWN
            childLevel == level && childGrade in start..end -> NoticeApplicability.APPLIES
            else -> NoticeApplicability.INELIGIBLE
        }
        return SourceAudienceFact(
            applicability = applicability,
            schoolLevel = level,
            gradeStart = start,
            gradeEnd = end,
            schoolWide = false,
            evidence = SourceEvidence("audience", evidence),
        )
    }

    private fun dateFacts(source: String, publishedIso: String?): List<SourceDateFact> {
        val facts = mutableListOf<SourceDateFact>()
        if (publishedIso != null) {
            facts += SourceDateFact(
                role = NoticeDateRole.PUBLICATION,
                text = publishedIso,
                dateIso = publishedIso,
                evidence = SourceEvidence("publishedAt", publishedIso),
            )
        }
        dottedDate.findAll(source).forEach { match ->
            val iso = match.toIsoDate() ?: return@forEach
            val context = source.substring(maxOf(0, match.range.first - 24), minOf(source.length, match.range.last + 25))
            val role = when {
                context.contains("납부") || context.contains("마감") || context.contains("기한") || context.contains("까지") -> NoticeDateRole.DUE
                context.contains("신청") || context.contains("접수") -> NoticeDateRole.APPLICATION_END
                context.contains("취소") -> NoticeDateRole.CANCELLATION
                context.contains("운영") || context.contains("체험") || context.contains("행사") -> NoticeDateRole.EVENT
                else -> NoticeDateRole.UNKNOWN
            }
            facts += SourceDateFact(role, match.value, iso, evidence = SourceEvidence("date", context.trim()))
        }
        return facts.distinctBy { "${it.role}:${it.dateIso}:${it.text}" }
    }

    private fun obligation(source: String): NoticeObligation = when {
        employment.containsMatchIn(source) -> NoticeObligation.INFORMATIONAL
        notRequired.containsMatchIn(source) -> NoticeObligation.INFORMATIONAL
        optional.containsMatchIn(source) -> NoticeObligation.OPTIONAL_OPPORTUNITY
        required.containsMatchIn(source) -> NoticeObligation.REQUIRED
        else -> NoticeObligation.INFORMATIONAL
    }

    private fun parseAttachments(html: String): List<SourceAttachment> {
        val scripted = uploadedFile.findAll(html).mapNotNull { match ->
            val title = decodeEntities(match.groupValues[1]).trim()
            val url = normalizeSchoolUrl(match.groupValues[2]) ?: return@mapNotNull null
            SourceAttachment(title, url, contentTypeFor(title), AttachmentFetchState.LINK_ONLY)
        }
        val linked = href.findAll(html).mapNotNull { match ->
            val url = normalizeSchoolUrl(match.groupValues[1]) ?: return@mapNotNull null
            val title = visibleText(match.groupValues[2]).ifBlank { url.substringAfterLast('/') }
            if (!looksLikeAttachment(url, title)) return@mapNotNull null
            SourceAttachment(title, url, contentTypeFor(title), AttachmentFetchState.LINK_ONLY)
        }
        return (scripted + linked).distinctBy { it.url }.toList()
    }

    private fun uploadInitializersForBoard(html: String, board: SchoolWebsiteBoard): String =
        uploadedFile.findAll(html).map { it.value }
            .filter { it.contains("/bbs_${board.boardId}/") }
            .joinToString("\n")

    private fun fieldAfterHeader(html: String, label: String): String? {
        val pattern = Regex("""<th\b[^>]*>\s*$label\s*</th>\s*<td\b[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.get(1)?.let(::visibleText)
    }

    private fun inputValue(html: String, name: String): String? {
        val pattern = Regex("""<input\b(?=[^>]*\bname\s*=\s*["']$name["'])(?=[^>]*\bvalue\s*=\s*["']([^"']*)["'])[^>]*>""", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun balancedElement(html: String, tag: String, attrPattern: Regex): String? {
        val startRegex = Regex("""<$tag\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val start = startRegex.findAll(html).firstOrNull { attrPattern.containsMatchIn(it.groupValues[1]) } ?: return null
        val tagRegex = Regex("""</?$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 0
        tagRegex.findAll(html, start.range.first).forEach { match ->
            val text = match.value
            if (text.startsWith("</")) depth-- else if (!text.endsWith("/>")) depth++
            if (depth == 0) return html.substring(start.range.first, match.range.last + 1)
        }
        return null
    }

    private fun visibleText(html: String): String = decodeEntities(
        html.replace(Regex("""<\s*(br|p|tr|div|li|table|tbody|thead|td|th)\b[^>]*>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""</\s*(p|tr|div|li|table|tbody|thead|td|th)\s*>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<script\b[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style\b[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " "),
    ).replace(whitespace, " ").trim()

    private fun MatchResult.toIsoDate(): String? {
        val year = groupValues[1].toIntOrNull() ?: return null
        val month = groupValues[2].toIntOrNull() ?: return null
        val day = groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day).format(dateFormatter) }.getOrNull()
    }

    private fun normalizeSchoolUrl(raw: String): String? {
        if (raw.contains("@") || raw.contains("..")) return null
        val absolute = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "https://$HOST$raw"
            else -> return null
        }
        return absolute.takeIf(::isAllowedSchoolUrl)
    }

    private fun looksLikeAttachment(url: String, title: String): Boolean {
        val haystack = "$url $title".lowercase()
        return listOf(".hwpx", ".hwp", ".pdf", ".png", ".jpg", ".jpeg").any { haystack.contains(it) } ||
            haystack.contains("/upload/")
    }

    private fun contentTypeFor(title: String): String? = when (title.substringAfterLast('.', "").lowercase()) {
        "hwpx" -> "application/x-hwpx"
        "hwp" -> "application/x-hwp"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> null
    }

    private fun decodeEntities(value: String): String {
        var decoded = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        decoded = Regex("""&#(\d+);""").replace(decoded) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> runCatching { code.toChar().toString() }.getOrNull() }.orEmpty()
        }
        return Regex("""&#x([0-9a-fA-F]+);""").replace(decoded) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { code.toChar().toString() }.getOrNull() }.orEmpty()
        }
    }
}
