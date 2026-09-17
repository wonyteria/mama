package kr.mom.probe.connector

import android.content.Context
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceFetcher
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceIssue
import kr.mom.probe.sync.SourceIssueCode
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceSyncLimits
import kr.mom.probe.sync.SourceSyncStatus
import kr.mom.probe.sync.SourceCheckpoint

class EalimiNoticeClient(
    private val renderer: EalimiPageRenderer,
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceFetcher {
    constructor(context: Context) : this(AndroidEalimiPageRenderer(context.applicationContext))

    override suspend fun fetch(scope: SourceScope, checkpoint: SourceCheckpoint?): SourceFetchResult {
        val fetchedAt = clock()
        val definition = ConnectorCatalog.site(SourceIds.EALIMI_WEB)
            ?: return failure(scope, fetchedAt, SourceSyncStatus.UNSUPPORTED, SourceIssueCode.UNSUPPORTED_SOURCE, "e알리미 연결 정의가 없어요.")
        if (scope.sourceId != SourceIds.EALIMI_WEB || scope.kind != SourceKind.EALIMI_WEB) {
            return failure(scope, fetchedAt, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 source scope가 올바르지 않아요.")
        }
        if (scope.school.schoolName.isBlank() || scope.child.grade == null) {
            return failure(scope, fetchedAt, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 조회에 필요한 학교/학년 범위가 없어요.")
        }

        return when (val rendered = renderer.render(definition, definition.startUrl, EalimiDomReader.DOM_READ_SCRIPT, SourceSyncLimits.RUN_TIMEOUT_MS)) {
            is EalimiRenderedPage.Success -> EalimiDomReader.parse(rendered.finalUrl, rendered.payload, scope, fetchedAt)
            is EalimiRenderedPage.BlockedNavigation -> failure(scope, fetchedAt, SourceSyncStatus.ERROR, SourceIssueCode.INVALID_SCOPE, "e알리미 허용 주소 밖으로 이동했어요.")
            is EalimiRenderedPage.HttpError -> failure(scope, fetchedAt, SourceSyncStatus.ERROR, SourceIssueCode.HTTP_ERROR, "e알리미 HTTP 오류: ${rendered.statusCode}")
            is EalimiRenderedPage.RenderError -> failure(scope, fetchedAt, SourceSyncStatus.ERROR, SourceIssueCode.DOM_CONTRACT_CHANGED, "e알리미 화면 렌더링을 완료하지 못했어요.")
            EalimiRenderedPage.Timeout -> failure(scope, fetchedAt, SourceSyncStatus.PARTIAL, SourceIssueCode.DETAIL_FETCH_FAILED, "e알리미 조회 시간이 초과됐어요.")
        }
    }

    private fun failure(scope: SourceScope, fetchedAt: Long, status: SourceSyncStatus, code: SourceIssueCode, message: String): SourceFetchResult =
        SourceFetchResult(
            sourceId = scope.sourceId,
            status = status,
            fetchedAt = fetchedAt,
            coverage = scope.coverageWindow.copy(complete = false),
            issues = listOf(SourceIssue(code, message, recoverable = status == SourceSyncStatus.PARTIAL)),
        )
}

interface EalimiPageRenderer {
    suspend fun render(definition: SiteDefinition, url: String, script: String, timeoutMs: Long): EalimiRenderedPage
}

sealed interface EalimiRenderedPage {
    data class Success(val finalUrl: String, val payload: String) : EalimiRenderedPage
    data class BlockedNavigation(val url: String) : EalimiRenderedPage
    data class HttpError(val statusCode: Int) : EalimiRenderedPage
    data class RenderError(val code: Int) : EalimiRenderedPage
    data object Timeout : EalimiRenderedPage
}

private class AndroidEalimiPageRenderer(private val context: Context) : EalimiPageRenderer {
    override suspend fun render(definition: SiteDefinition, url: String, script: String, timeoutMs: Long): EalimiRenderedPage =
        when (val result = WebsiteSessionManager.renderAuthenticatedDom(context, definition, url, script, timeoutMs)) {
            is WebsiteDomRenderResult.Success -> EalimiRenderedPage.Success(result.finalUrl, result.payload)
            is WebsiteDomRenderResult.BlockedNavigation -> EalimiRenderedPage.BlockedNavigation(result.url)
            is WebsiteDomRenderResult.HttpError -> EalimiRenderedPage.HttpError(result.statusCode)
            is WebsiteDomRenderResult.RenderError -> EalimiRenderedPage.RenderError(result.code)
            WebsiteDomRenderResult.Timeout -> EalimiRenderedPage.Timeout
        }
}
