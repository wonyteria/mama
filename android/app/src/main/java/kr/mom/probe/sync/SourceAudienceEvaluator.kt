package kr.mom.probe.sync

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.SchoolLevel

data class SourceAudienceDecision(
    val applicability: NoticeApplicability,
    val reason: String,
    val evidenceValues: List<String>,
)

object SourceAudienceEvaluator {
    fun evaluate(
        facts: List<SourceAudienceFact>,
        schoolLevel: SchoolLevel,
        grade: Int?,
    ): SourceAudienceDecision {
        val evidence = facts.map { it.evidence.value }.filter { it.isNotBlank() }
        if (facts.isEmpty()) {
            return SourceAudienceDecision(
                applicability = NoticeApplicability.APPLIES,
                reason = "검증된 출처 범위에서 일반 공지로 보았어요.",
                evidenceValues = evidence,
            )
        }

        val matchingApplies = facts.filter { it.applicability == NoticeApplicability.APPLIES && it.matchesChild(schoolLevel, grade) }
        val matchingExclusions = facts.filter { it.applicability == NoticeApplicability.INELIGIBLE && it.matchesChild(schoolLevel, grade) }
        if (matchingApplies.isNotEmpty() && matchingExclusions.isNotEmpty()) {
            return SourceAudienceDecision(
                applicability = NoticeApplicability.UNKNOWN,
                reason = "검증된 출처의 포함·제외 대상 정보가 충돌해 원문 확인이 필요해요.",
                evidenceValues = evidence,
            )
        }
        if (matchingApplies.isNotEmpty()) {
            return SourceAudienceDecision(
                applicability = NoticeApplicability.APPLIES,
                reason = "검증된 출처의 대상 정보가 현재 자녀 범위에 해당해요.",
                evidenceValues = evidence,
            )
        }
        if (matchingExclusions.isNotEmpty()) {
            return SourceAudienceDecision(
                applicability = NoticeApplicability.INELIGIBLE,
                reason = "검증된 출처의 제외 대상 정보가 현재 자녀 범위에 해당해요.",
                evidenceValues = evidence,
            )
        }

        val childScopeIncomplete = schoolLevel == SchoolLevel.UNKNOWN || grade == null
        val matchingUnknown = facts.any { it.applicability == NoticeApplicability.UNKNOWN && it.couldDescribeChild(schoolLevel, grade) }
        if (matchingUnknown || childScopeIncomplete) {
            return SourceAudienceDecision(
                applicability = NoticeApplicability.UNKNOWN,
                reason = "검증된 출처의 대상 정보를 현재 자녀 범위와 확정하지 못했어요.",
                evidenceValues = evidence,
            )
        }

        val hasPositiveAudience = facts.any { it.applicability == NoticeApplicability.APPLIES }
        return if (hasPositiveAudience) {
            SourceAudienceDecision(
                applicability = NoticeApplicability.INELIGIBLE,
                reason = "검증된 출처의 포함 대상 정보가 현재 자녀 범위와 달라요.",
                evidenceValues = evidence,
            )
        } else {
            SourceAudienceDecision(
                applicability = NoticeApplicability.UNKNOWN,
                reason = "검증된 출처의 제외 대상 정보만으로 현재 자녀 포함 여부를 확정하지 못했어요.",
                evidenceValues = evidence,
            )
        }
    }

    fun appliesToChild(
        facts: List<SourceAudienceFact>,
        schoolLevel: SchoolLevel,
        grade: Int?,
    ): Boolean = evaluate(facts, schoolLevel, grade).applicability == NoticeApplicability.APPLIES

    private fun SourceAudienceFact.matchesChild(schoolLevel: SchoolLevel, grade: Int?): Boolean {
        if (schoolWide) return true
        if (this.schoolLevel != SchoolLevel.UNKNOWN && schoolLevel != this.schoolLevel) return false
        val start = gradeStart
        val end = gradeEnd ?: start
        if (start == null || end == null) return this.schoolLevel == SchoolLevel.UNKNOWN || schoolLevel == this.schoolLevel
        return grade != null && grade in start..end
    }

    private fun SourceAudienceFact.couldDescribeChild(schoolLevel: SchoolLevel, grade: Int?): Boolean {
        if (schoolWide) return true
        if (this.schoolLevel != SchoolLevel.UNKNOWN && schoolLevel != SchoolLevel.UNKNOWN && schoolLevel != this.schoolLevel) return false
        val start = gradeStart
        val end = gradeEnd ?: start
        return grade == null || start == null || end == null || grade in start..end
    }
}
