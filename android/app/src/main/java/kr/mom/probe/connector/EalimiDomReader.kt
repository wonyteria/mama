package kr.mom.probe.connector

import java.net.URI
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
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceOrigin
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncStatus
import org.json.JSONArray
import org.json.JSONObject

object EalimiDomReader {
    const val CONTRACT_VERSION = "ealimi-auth-dom-v1"

    val DOM_READ_SCRIPT: String = """
        (function() {
          const href = String(location.href || "");
          const loginForm = !!(
            document.querySelector("#signInSubmitBtn") ||
            document.querySelector("input[name='id']") ||
            document.querySelector("input[type='password']")
          );
          return JSON.stringify({
            contractVersion: "$CONTRACT_VERSION",
            url: href,
            loginForm: loginForm,
            authenticated: false,
            unsupported: !loginForm,
            issue: loginForm ? "login_form" : "authenticated_notice_dom_contract_pending"
          });
        })();
    """.trimIndent()

    fun parse(finalUrl: String, rawPayload: String, scope: SourceScope, fetchedAt: Long): SourceFetchResult {
        val coverage = scope.coverageWindow.copy(complete = false)
        if (!ConnectorCatalog.isAllowedHttps(requireEalimiDefinition(), finalUrl)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 허용 주소 밖으로 이동했어요.")
        }
        val payload = decodeEvaluateJavascriptPayload(rawPayload)
            ?: return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.PARSE_ERROR, "e알리미 DOM 결과를 읽지 못했어요.")
        val json = runCatching { JSONObject(payload) }.getOrElse {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.PARSE_ERROR, "e알리미 DOM 결과 형식이 올바르지 않아요.")
        }
        val observedUrl = json.optString("url", finalUrl).ifBlank { finalUrl }
        if (!ConnectorCatalog.isAllowedHttps(requireEalimiDefinition(), observedUrl)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 DOM 결과 주소가 허용 범위를 벗어났어요.")
        }
        if (isLoginState(observedUrl, json)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.AUTH_REQUIRED, SourceIssueCode.AUTH_EXPIRED, "e알리미 로그인이 필요해요.",
                evidence = listOf(SourceEvidence("auth_dom", "official_sign_in")))
        }
        if (json.optBoolean("unsupported", false) || json.optString("contractVersion") != CONTRACT_VERSION) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.UNSUPPORTED, SourceIssueCode.DOM_CONTRACT_MISSING, "검증된 e알리미 공지 DOM 계약이 아직 없어요.")
        }
        if (!json.optBoolean("authenticated", false)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.AUTH_REQUIRED, SourceIssueCode.AUTH_EXPIRED, "e알리미 인증 상태를 확인하지 못했어요.")
        }
        if (!matchesScope(json, scope)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 학교/학년 범위가 현재 자녀 설정과 달라요.")
        }
        if (!json.optBoolean("listRead", false)) {
            return failure(scope, fetchedAt, coverage, SourceSyncStatus.ERROR, SourceIssueCode.DOM_CONTRACT_CHANGED, "e알리미 공지 목록을 검증하지 못했어요.")
        }

        val notices = json.optJSONArray("notices") ?: JSONArray()
        val items = mutableListOf<FetchedNotice>()
        val issues = mutableListOf<SourceIssue>()
        for (index in 0 until notices.length()) {
            val notice = notices.optJSONObject(index) ?: continue
            val parsed = parseNotice(notice, scope, fetchedAt)
            if (parsed == null) {
                issues += SourceIssue(SourceIssueCode.SCHEMA_ERROR, "e알리미 공지 항목 구조가 올바르지 않아요.", recoverable = false)
            } else {
                items += parsed
            }
        }
        if (issues.isNotEmpty()) {
            return SourceFetchResult(
                sourceId = scope.sourceId,
                status = SourceSyncStatus.PARTIAL,
                fetchedAt = fetchedAt,
                items = items,
                coverage = coverage,
                evidence = scopeEvidence(json),
                issues = issues,
            )
        }
        return SourceFetchResult(
            sourceId = scope.sourceId,
            status = if (items.isEmpty()) SourceSyncStatus.SUCCESS_EMPTY else SourceSyncStatus.FETCHED,
            fetchedAt = fetchedAt,
            items = items,
            coverage = scope.coverageWindow.copy(complete = true, totalCount = items.size),
            evidence = scopeEvidence(json),
        )
    }

    private fun parseNotice(json: JSONObject, scope: SourceScope, fetchedAt: Long): FetchedNotice? {
        val itemId = json.optString("id").trim()
        val title = json.optString("title").trim()
        val body = json.optString("body").trim()
        val detailUrl = json.optString("detailUrl").trim()
        if (itemId.isBlank() || title.isBlank() || detailUrl.isBlank()) return null
        if (itemId.any { it.isISOControl() } || title.any { it.isISOControl() }) return null
        if (!ConnectorCatalog.isAllowedHttps(requireEalimiDefinition(), detailUrl)) return null
        if (!json.optBoolean("detailRead", false)) return null

        val attachments = parseAttachments(json.optJSONArray("attachments") ?: JSONArray())
        val publishedText = json.optString("publishedText").trim()
        val publishedAt = json.optLongOrNull("publishedAt")
        val revisionPayload = JSONObject()
            .put("id", itemId)
            .put("title", title)
            .put("body", body)
            .put("detailUrl", detailUrl)
            .put("publishedAt", publishedAt ?: JSONObject.NULL)
            .put("attachments", JSONArray(attachments.map { attachment ->
                JSONObject().put("title", attachment.title).put("url", attachment.url).put("state", attachment.state.name)
            }))
        val uri = URI(detailUrl)
        return FetchedNotice(
            sourceId = scope.sourceId,
            itemId = itemId,
            revisionHash = ProbeRules.digest(revisionPayload.toString()),
            firstSeenAt = fetchedAt,
            publishedAt = publishedAt,
            title = title,
            body = body,
            origin = SourceOrigin(canonicalUrl = detailUrl, host = uri.host.lowercase(), rawId = itemId),
            contentState = if (body.isBlank() && attachments.isNotEmpty()) NoticeContentState.ATTACHMENT_MISSING else NoticeContentState.VERIFIED,
            obligation = NoticeObligation.INFORMATIONAL,
            audienceFacts = listOf(SourceAudienceFact(
                applicability = NoticeApplicability.APPLIES,
                schoolLevel = scope.child.schoolLevel,
                gradeStart = scope.child.grade,
                gradeEnd = scope.child.grade,
                evidence = SourceEvidence("ealimi_scope", "verified_child_school_grade"),
            )),
            dateFacts = if (publishedText.isNotBlank() || publishedAt != null) listOf(SourceDateFact(
                role = NoticeDateRole.PUBLICATION,
                text = publishedText.ifBlank { "publishedAt" },
                preciseAt = publishedAt,
                evidence = SourceEvidence("ealimi_publication", "verified_detail"),
            )) else emptyList(),
            attachments = attachments,
            evidence = listOf(SourceEvidence("ealimi_detail", "verified_read_only_detail")),
        )
    }

    private fun parseAttachments(array: JSONArray): List<SourceAttachment> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            val title = item.optString("title").trim().ifBlank { "첨부" }
            if (!ConnectorCatalog.isAllowedHttps(requireEalimiDefinition(), url)) continue
            val contentType = item.optString("contentType").trim().takeIf { it.isNotBlank() }
            add(SourceAttachment(title, url, contentType, AttachmentFetchState.LINK_ONLY))
        }
    }

    private fun matchesScope(json: JSONObject, scope: SourceScope): Boolean {
        val school = json.optJSONObject("school") ?: return false
        val child = json.optJSONObject("child") ?: return false
        val observedSchool = normalizeSchool(school.optString("name"))
        val expectedSchool = normalizeSchool(scope.school.schoolName)
        val observedGrade = child.optIntOrNull("grade") ?: return false
        val observedLevel = when (school.optString("level").uppercase()) {
            "ELEMENTARY", "초등", "초등학교" -> SchoolLevel.ELEMENTARY
            "MIDDLE", "중등", "중학교" -> SchoolLevel.MIDDLE
            else -> SchoolLevel.UNKNOWN
        }
        return observedSchool.isNotBlank() &&
            observedSchool == expectedSchool &&
            observedGrade == scope.child.grade &&
            (scope.child.schoolLevel == SchoolLevel.UNKNOWN || observedLevel == scope.child.schoolLevel)
    }

    private fun scopeEvidence(json: JSONObject): List<SourceEvidence> = listOf(
        SourceEvidence("ealimi_contract", json.optString("contractVersion", "missing")),
        SourceEvidence("ealimi_scope", "verified_school_child"),
    )

    private fun isLoginState(url: String, json: JSONObject): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return json.optBoolean("loginForm", false) || path.contains("/member/signin")
    }

    private fun failure(
        scope: SourceScope,
        fetchedAt: Long,
        coverage: SourceCoverageWindow,
        status: SourceSyncStatus,
        code: SourceIssueCode,
        message: String,
        evidence: List<SourceEvidence> = emptyList(),
    ): SourceFetchResult = SourceFetchResult(
        sourceId = scope.sourceId,
        status = status,
        fetchedAt = fetchedAt,
        coverage = coverage,
        evidence = evidence,
        issues = listOf(SourceIssue(code, message, recoverable = status == SourceSyncStatus.AUTH_REQUIRED)),
    )

    private fun decodeEvaluateJavascriptPayload(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return null
        return if (trimmed.startsWith("\"")) {
            runCatching { JSONObject("{\"value\":$trimmed}").getString("value") }.getOrNull()
        } else {
            trimmed
        }
    }

    private fun normalizeSchool(value: String): String = value
        .replace("\\s+".toRegex(), "")
        .replace("초등학교", "초")
        .trim()

    private fun requireEalimiDefinition(): SiteDefinition =
        ConnectorCatalog.site(SourceIds.EALIMI_WEB) ?: error("missing ealimi site definition")
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
