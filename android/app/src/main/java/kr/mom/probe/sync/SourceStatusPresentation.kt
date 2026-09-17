package kr.mom.probe.sync

object SourceStatusPresentation {
    fun message(snapshots: Collection<SourceSyncSnapshot>): String? = when {
        snapshots.any { it.status == SourceSyncStatus.AUTH_REQUIRED } -> "로그인이 필요한 출처가 있어요"
        snapshots.any { it.status == SourceSyncStatus.OFFLINE || it.status == SourceSyncStatus.ERROR } ->
            "새 소식을 확인하지 못한 출처가 있어요"
        snapshots.any { it.status == SourceSyncStatus.PARTIAL } -> "일부 소식과 첨부만 확인했어요"
        else -> null
    }
}
