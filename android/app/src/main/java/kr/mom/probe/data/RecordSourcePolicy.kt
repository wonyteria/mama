package kr.mom.probe.data

import kr.mom.probe.sync.RecordSourceDecision
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.RecordSourcePolicy
import kr.mom.probe.sync.SourceAudienceEvaluator
import kr.mom.probe.sync.SourceRecordState
import kr.mom.probe.sync.SourceScope

object DefaultRecordSourcePolicy : RecordSourcePolicy {
    override fun decide(metadata: RecordSourceMetadata?, scope: SourceScope, now: Long): RecordSourceDecision {
        if (metadata == null) return RecordSourceDecision(SourceRecordState.ACTIVE, "앱 알림 기록")
        if (metadata.sourceId != scope.sourceId) return RecordSourceDecision(SourceRecordState.OUT_OF_SCOPE, "다른 출처")
        if (metadata.connectionGeneration != scope.connectionGeneration || metadata.authorizationToken != scope.authorizationToken) {
            return RecordSourceDecision(SourceRecordState.OUT_OF_SCOPE, "이전 연결 범위의 기록")
        }
        if (ProbeRules.isExpired(metadata.firstSeenAt, now)) return RecordSourceDecision(SourceRecordState.EXPIRED, "보관 기간 만료")
        if (metadata.audienceFacts.isNotEmpty()) {
            val audience = SourceAudienceEvaluator.evaluate(metadata.audienceFacts, scope.child.schoolLevel, scope.child.grade)
            if (audience.applicability == NoticeApplicability.INELIGIBLE) {
                return RecordSourceDecision(SourceRecordState.OUT_OF_SCOPE, audience.reason)
            }
        }
        return RecordSourceDecision(SourceRecordState.ACTIVE, "현재 출처와 자녀 범위에 해당")
    }
}
