package kr.mom.probe.sync

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAudienceEvaluatorTest {
    @Test fun positiveDeclaredRangeCanBeReevaluatedForAnotherGrade() {
        val facts = listOf(SourceAudienceFact(
            applicability = NoticeApplicability.APPLIES,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = 5,
            gradeEnd = 6,
            evidence = SourceEvidence("neis", "5~6학년 Y"),
        ))

        val gradeTwo = SourceAudienceEvaluator.evaluate(facts, SchoolLevel.ELEMENTARY, 2)
        val gradeSix = SourceAudienceEvaluator.evaluate(facts, SchoolLevel.ELEMENTARY, 6)

        assertEquals(NoticeApplicability.INELIGIBLE, gradeTwo.applicability)
        assertEquals(NoticeApplicability.APPLIES, gradeSix.applicability)
        assertFalse(SourceAudienceEvaluator.appliesToChild(facts, SchoolLevel.ELEMENTARY, 2))
        assertTrue(SourceAudienceEvaluator.appliesToChild(facts, SchoolLevel.ELEMENTARY, 6))
    }

    @Test fun negativeDeclaredRangeOnlyExcludesTheNamedGrade() {
        val facts = listOf(SourceAudienceFact(
            applicability = NoticeApplicability.INELIGIBLE,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = 6,
            gradeEnd = 6,
            evidence = SourceEvidence("html", "6학년 대상 아님"),
        ))

        val gradeTwo = SourceAudienceEvaluator.evaluate(facts, SchoolLevel.ELEMENTARY, 2)
        val gradeSix = SourceAudienceEvaluator.evaluate(facts, SchoolLevel.ELEMENTARY, 6)

        assertEquals(NoticeApplicability.UNKNOWN, gradeTwo.applicability)
        assertEquals(NoticeApplicability.INELIGIBLE, gradeSix.applicability)
    }

    @Test fun matchingUnknownFactPreventsPositiveRangeFromGuessingOutOfScope() {
        val facts = listOf(
            SourceAudienceFact(
                applicability = NoticeApplicability.APPLIES,
                schoolLevel = SchoolLevel.ELEMENTARY,
                gradeStart = 5,
                gradeEnd = 6,
                evidence = SourceEvidence("neis", "5~6학년 Y"),
            ),
            SourceAudienceFact(
                applicability = NoticeApplicability.UNKNOWN,
                schoolLevel = SchoolLevel.ELEMENTARY,
                gradeStart = 2,
                gradeEnd = 2,
                evidence = SourceEvidence("neis", "2학년 미확인"),
            ),
        )

        val gradeTwo = SourceAudienceEvaluator.evaluate(facts, SchoolLevel.ELEMENTARY, 2)

        assertEquals(NoticeApplicability.UNKNOWN, gradeTwo.applicability)
    }
}
