package kr.mom.probe.sync

import kr.mom.probe.connector.ConnectionStatus
import kr.mom.probe.connector.ConnectorState
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.SchoolLevel

/** Capabilities a lane may honestly advertise for 1.1. DESIGN.md §6. */
enum class LaneCapability { NOTIFICATION_CAPTURE, LISTING_DISCOVERY, BODY_EXTRACTION, REVISION_DETECTION }

enum class LaneHealth { READY, NEEDS_SETUP, UNSUPPORTED, TEMPORARILY_UNCERTAIN, ERROR }

/** One transport lane inside a user-visible [SourceService]. Never leaks internal ids. */
data class SourceLane(
    val name: String,
    val capabilities: Set<LaneCapability>,
    val health: LaneHealth,
    val statusText: String,
    val enabled: Boolean = false,
    /** Internal handle used only for wiring (package name, source id). Never shown. */
    val internalKey: String = "",
)

/** One user-visible row per recognizable service or institution, holding parallel lanes. */
data class SourceService(
    val name: String,
    val lanes: List<SourceLane>,
)

/**
 * Resolves each service's lanes from real signals: connector state, sync snapshots, notification
 * access, and captured-record evidence. Installed apps are never reported as connected, and a
 * preferred app mapping never disables a verified web lane — lanes are evaluated independently.
 */
object SourceLanes {

    private data class NotificationService(val serviceName: String, val packageName: String)

    private val notificationServices = listOf(
        NotificationService("학교종이", "com.schoolbell_e.schoolbell_e"),
        NotificationService("e알리미", "com.ewut.allealimi"),
        NotificationService("하이클래스", "com.iscreammedia.app.hiclass.android"),
        NotificationService("키즈노트", "com.vaultmicro.kidsnote"),
        NotificationService("클래스노트", "com.classnote.android.release"),
    )

    fun notificationLane(
        packageName: String,
        serviceName: String,
        installed: Boolean,
        settings: ProbeSettings,
        verifiedPackages: Set<String>,
        notificationAccess: Boolean,
    ): SourceLane {
        val enabled = packageName in settings.selectedPackages
        val verified = packageName in verifiedPackages
        val (health, text) = when {
            !installed -> LaneHealth.NEEDS_SETUP to "앱을 설치하면 알림을 모을 수 있어요"
            !enabled -> LaneHealth.NEEDS_SETUP to "꺼짐"
            !notificationAccess -> LaneHealth.NEEDS_SETUP to "알림 접근 권한이 필요해요"
            verified -> LaneHealth.READY to "알림 수신 이력 있음"
            else -> LaneHealth.TEMPORARILY_UNCERTAIN to "첫 알림 기다리는 중"
        }
        return SourceLane(
            name = "$serviceName 앱 알림",
            capabilities = setOf(LaneCapability.NOTIFICATION_CAPTURE, LaneCapability.REVISION_DETECTION),
            health = health,
            statusText = text,
            enabled = enabled,
            internalKey = packageName,
        )
    }

    /** The verified school-website adapter lane. Only the verified institution is advertised. */
    fun schoolWebsiteLane(settings: ProbeSettings, snapshot: SourceSyncSnapshot?): SourceLane {
        val normalized = settings.schoolName.replace(" ", "")
        val level = settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName)
        val supported = normalized == "성남정자초등학교" && level == SchoolLevel.ELEMENTARY &&
            settings.schoolGrade in 1..6
        val (health, text) = when {
            !supported && settings.schoolName.isBlank() ->
                LaneHealth.NEEDS_SETUP to "학교를 입력하면 지원 여부를 확인해요"
            !supported ->
                LaneHealth.UNSUPPORTED to "지금은 확인된 학교 홈페이지만 연결해요"
            else -> snapshotHealth(snapshot, ready = "공지·가정통신문을 확인할 수 있어요")
        }
        return SourceLane(
            name = "학교 공식 홈페이지",
            capabilities = setOf(
                LaneCapability.LISTING_DISCOVERY,
                LaneCapability.BODY_EXTRACTION,
                LaneCapability.REVISION_DETECTION,
            ),
            health = health,
            statusText = text,
            enabled = supported,
            internalKey = SourceIds.SCHOOL_WEBSITE,
        )
    }

    /**
     * Private web-adapter lanes. e알리미/하이클래스 DOM capture is not implemented, so these lanes
     * honestly report UNSUPPORTED regardless of whether a login session exists.
     */
    fun webLane(name: String, sourceId: String, connectorState: ConnectorState, snapshot: SourceSyncSnapshot?): SourceLane {
        val connection = connectorState.sites[sourceId]
        val session = connection?.status in setOf(ConnectionStatus.SESSION_READY, ConnectionStatus.CONNECTED)
        val (health, text) = when {
            connection?.status == ConnectionStatus.REAUTH_REQUIRED ->
                LaneHealth.ERROR to "다시 로그인이 필요해요"
            session && snapshot != null -> snapshotHealth(snapshot, ready = "웹 방문이 저장됐어요")
            session -> LaneHealth.TEMPORARILY_UNCERTAIN to "웹 방문 저장됨 · 자동 조회는 아직 지원하지 않아요"
            else -> LaneHealth.UNSUPPORTED to "개인 공지 자동 조회는 아직 지원하지 않아요"
        }
        return SourceLane(
            name = name,
            capabilities = emptySet(),
            health = health,
            statusText = text,
            enabled = session,
            internalKey = sourceId,
        )
    }

    /** All visible services with their lanes resolved independently. */
    fun services(
        settings: ProbeSettings,
        connectorState: ConnectorState,
        snapshots: Map<String, SourceSyncSnapshot>,
        installedPackages: Set<String>,
        verifiedPackages: Set<String>,
        notificationAccess: Boolean,
    ): List<SourceService> {
        val ealimi = notificationServices.first { it.packageName == "com.ewut.allealimi" }
        val hiclass = notificationServices.first { it.packageName == "com.iscreammedia.app.hiclass.android" }
        val services = mutableListOf<SourceService>()
        services += SourceService(
            name = "학교 공식 홈페이지",
            lanes = listOf(schoolWebsiteLane(settings, snapshots[SourceIds.SCHOOL_WEBSITE])),
        )
        services += SourceService(
            name = "e알리미",
            lanes = listOf(
                notificationLane(ealimi.packageName, ealimi.serviceName,
                    installed = ealimi.packageName in installedPackages,
                    settings = settings, verifiedPackages = verifiedPackages,
                    notificationAccess = notificationAccess),
                webLane("e알리미 웹", SourceIds.EALIMI_WEB, connectorState, snapshots[SourceIds.EALIMI_WEB]),
            ),
        )
        services += SourceService(
            name = "하이클래스",
            lanes = listOf(
                notificationLane(hiclass.packageName, hiclass.serviceName,
                    installed = hiclass.packageName in installedPackages,
                    settings = settings, verifiedPackages = verifiedPackages,
                    notificationAccess = notificationAccess),
                webLane("하이클래스 웹", "hiclass-web", connectorState, null),
            ),
        )
        notificationServices.filter { it.packageName != ealimi.packageName && it.packageName != hiclass.packageName }
            .forEach { spec ->
                services += SourceService(
                    name = spec.serviceName,
                    lanes = listOf(notificationLane(spec.packageName, spec.serviceName,
                        installed = spec.packageName in installedPackages,
                        settings = settings, verifiedPackages = verifiedPackages,
                        notificationAccess = notificationAccess)),
                )
            }
        return services
    }

    private fun snapshotHealth(snapshot: SourceSyncSnapshot?, ready: String): Pair<LaneHealth, String> =
        when (snapshot?.status) {
            SourceSyncStatus.FETCHED -> LaneHealth.READY to "조회 완료 · 저장 ${snapshot.storedCount}개"
            SourceSyncStatus.SUCCESS_EMPTY -> LaneHealth.READY to "정상 응답 · 새 소식 0개"
            SourceSyncStatus.PARTIAL -> LaneHealth.TEMPORARILY_UNCERTAIN to "일부만 확인했어요 · ${snapshot.message ?: "미확인 범위 있음"}"
            SourceSyncStatus.AUTH_REQUIRED -> LaneHealth.ERROR to "다시 로그인이 필요해요"
            SourceSyncStatus.UNSUPPORTED -> LaneHealth.UNSUPPORTED to "자동 조회 구현 연결 전이에요"
            SourceSyncStatus.OFFLINE -> LaneHealth.TEMPORARILY_UNCERTAIN to "오프라인 · 마지막 자료를 유지해요"
            SourceSyncStatus.ERROR -> LaneHealth.ERROR to "확인이 필요해요 · ${snapshot.message ?: "오류"}"
            SourceSyncStatus.RUNNING -> LaneHealth.TEMPORARILY_UNCERTAIN to "확인하는 중이에요"
            SourceSyncStatus.NEVER, null -> LaneHealth.READY to ready
        }
}
