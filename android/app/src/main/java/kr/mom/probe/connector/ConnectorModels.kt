package kr.mom.probe.connector

import java.net.URI

enum class ConnectionKind { APP, WEBSITE }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, SESSION_READY, CONNECTED, REAUTH_REQUIRED, ERROR }

data class ChildProfile(
    val id: String = "primary-child",
    val name: String = "",
    val schoolName: String = "",
    val grade: Int? = null,
)

data class SiteConnection(
    val id: String,
    val childId: String,
    val status: ConnectionStatus,
    val connectedAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val lastObservedUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ConnectorState(
    val child: ChildProfile = ChildProfile(),
    val sites: Map<String, SiteConnection> = emptyMap(),
)

data class SiteDefinition(
    val id: String,
    val name: String,
    val description: String,
    val startUrl: String,
    val allowedHostSuffixes: Set<String>,
    val mark: String,
    val available: Boolean,
)

object ConnectorCatalog {
    val sites = listOf(
        SiteDefinition(
            id = "neis-public",
            name = "나이스 학교정보",
            description = "공식 OpenAPI · 학사일정·급식·시간표",
            startUrl = "https://open.neis.go.kr/",
            allowedHostSuffixes = setOf("open.neis.go.kr"),
            mark = "N",
            available = true,
        ),
        SiteDefinition(
            id = "neis-parent",
            name = "나이스 학부모서비스",
            description = "성적·출결·학교생활 · 공식 위임 연동 준비 중",
            startUrl = "https://parents.neis.go.kr/csp-prnt/#/prn-main/intro",
            allowedHostSuffixes = setOf("neis.go.kr"),
            mark = "N",
            available = false,
        ),
        SiteDefinition(
            id = "ealimi-web",
            name = "e알리미 웹",
            description = "보호자 웹 연결 방식 검증 중",
            startUrl = "https://www.ealimi.com/Member/SignIn",
            allowedHostSuffixes = setOf("ealimi.com"),
            mark = "e",
            available = true,
        ),
        SiteDefinition(
            id = "hiclass-web",
            name = "하이클래스 웹",
            description = "보호자 웹 연결 방식 검증 중",
            startUrl = "https://www.hiclass.net/",
            allowedHostSuffixes = setOf("hiclass.net"),
            mark = "Hi",
            available = true,
        ),
    )

    fun site(id: String): SiteDefinition? = sites.firstOrNull { it.id == id }

    fun isAllowedHttps(definition: SiteDefinition, rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return definition.allowedHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    fun isLikelySignedIn(definition: SiteDefinition, rawUrl: String): Boolean {
        // Navigation alone cannot prove authentication, even on a private-looking path.
        return false
    }
}
