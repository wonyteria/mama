package kr.mom.probe.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeDecisionEngineTest {
    private val postedAt = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()

    @Test fun manualTranscribedAttachmentFixtureIsOptionalAndIneligibleForElementarySecondGrade() {
        val decision = NoticeDecisionEngine.decide(
            record(
                "2026 토요미래산책(10월) 학생체험 프로그램 신청 안내",
                manualReferenceTranscription,
            ),
            ChildNoticeProfile(grade = 2, schoolLevel = SchoolLevel.ELEMENTARY),
        )

        assertEquals(NoticeContentState.ATTACHMENT_MISSING, decision.contentState)
        assertEquals(NoticeApplicability.INELIGIBLE, decision.applicability)
        assertEquals(NoticeObligation.OPTIONAL_OPPORTUNITY, decision.obligation)
        assertEquals(NoticeAdmissionPolicy.FIRST_COME_FIRST_SERVED, decision.admissionPolicy)
        assertFalse(decision.isRequiredForChild())
        assertNull(NotificationCandidateParser.parse(record("fixture", manualReferenceTranscription), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY)))
    }

    @Test fun manualTranscribedAttachmentFixtureIsLowPriorityOpportunityForElementaryFifthGrade() {
        val decision = NoticeDecisionEngine.decide(
            record("2026 토요미래산책(10월) 학생체험 프로그램 신청 안내", manualReferenceTranscription),
            ChildNoticeProfile(grade = 5, schoolLevel = SchoolLevel.ELEMENTARY),
        )

        assertTrue(decision.isOptionalForChild())
        assertFalse(decision.isRequiredForChild())
        assertEquals(NoticeAdmissionPolicy.FIRST_COME_FIRST_SERVED, decision.admissionPolicy)
        assertTrue(decision.dates.any { it.role == NoticeDateRole.APPLICATION_START && it.text.contains("2026-09-28") && it.hasExplicitTime })
        assertTrue(decision.dates.any { it.role == NoticeDateRole.APPLICATION_END && it.text.contains("2026-10-02") && it.hasExplicitTime })
        assertTrue(decision.dates.any { it.role == NoticeDateRole.EVENT && it.text.contains("2026-10-17") })
        assertTrue(decision.dates.any { it.role == NoticeDateRole.RESULT && it.text.contains("2026-10-07") })
        assertTrue(decision.issues.any { it.contains("비용") })
    }

    @Test fun actualKoreanDottedDateKeepsApplicationPeriodTimes() {
        val decision = NoticeDecisionEngine.decide(
            record("토요미래산책", dottedKoreanTranscription),
            ChildNoticeProfile(grade = 5, schoolLevel = SchoolLevel.ELEMENTARY),
        )

        assertTrue(decision.isOptionalForChild())
        assertEquals(NoticeAdmissionPolicy.FIRST_COME_FIRST_SERVED, decision.admissionPolicy)
        assertTrue(decision.dates.any { it.role == NoticeDateRole.APPLICATION_START && it.text == "2026.9.28.(월)17:00" && it.hasExplicitTime })
        assertTrue(decision.dates.any { it.role == NoticeDateRole.APPLICATION_END && it.text == "2026.10.2.(금)09:00" && it.hasExplicitTime })
    }

    @Test fun negativeFirstComeTextCanStillBeLottery() {
        val decision = NoticeDecisionEngine.decide(
            record("신청 안내", "선착순이 아닙니다. 추첨으로 선정합니다. 대상 초등 5~6학년."),
            ChildNoticeProfile(grade = 5, schoolLevel = SchoolLevel.ELEMENTARY),
        )

        assertEquals(NoticeAdmissionPolicy.LOTTERY, decision.admissionPolicy)
    }

    @Test fun incompleteExtractionBlocksAutomaticCandidate() {
        val truncated = record("준비물 안내", "준비물: 물통. 내일 오전 9시까지").copy(truncated = true)
        val attachment = record("첨부 대상 확인", "첨부 대상 확인. 준비물: 물통. 내일 오전 9시까지")

        assertEquals(NoticeObligation.REQUIRED, NoticeDecisionEngine.decide(truncated).obligation)
        assertNull(NotificationCandidateParser.parse(truncated))
        assertNull(NotificationCandidateParser.parse(attachment))
    }

    @Test fun applicationDeadlineWithoutObligationDoesNotBecomeRequired() {
        val notice = record("방과후학교 신청 안내", "신청 마감은 내일 오후 5시입니다.")
        val decision = NoticeDecisionEngine.decide(notice)

        assertEquals(NoticeObligation.OPTIONAL_OPPORTUNITY, decision.obligation)
        assertFalse(decision.isRequiredForChild())
        assertNull(NotificationCandidateParser.parse(notice))
    }

    @Test fun middleFirstGradeIsNotTreatedAsElementaryFirstGrade() {
        val elementaryNotice = record("초등 1학년 준비물", "초등 1학년 준비물: 물통. 내일 오전 9시까지 챙겨 주세요.")
        val decision = NoticeDecisionEngine.decide(elementaryNotice, ChildNoticeProfile(grade = 1, schoolLevel = SchoolLevel.MIDDLE))

        assertEquals(NoticeApplicability.INELIGIBLE, decision.applicability)
        assertFalse(decision.isRequiredForChild())
    }

    @Test fun unknownSchoolLevelKeepsTargetedNoticeUnconfirmed() {
        val elementaryNotice = record("초등 1학년 준비물", "초등 1학년 준비물: 물통. 내일 오전 9시까지 챙겨 주세요.")
        val decision = NoticeDecisionEngine.decide(elementaryNotice, ChildNoticeProfile(grade = 1, schoolLevel = SchoolLevel.UNKNOWN))

        assertEquals(NoticeApplicability.UNKNOWN, decision.applicability)
        assertFalse(decision.isRequiredForChild())
    }

    private fun record(title: String, text: String) = ProbeRecord(
        id = "id-${title.hashCode()}",
        packageName = "school.app",
        appLabel = "학교",
        postedAt = postedAt,
        receivedAt = postedAt,
        title = title,
        text = text,
        bigText = "",
        textLines = emptyList(),
        subText = null,
        summaryText = null,
        category = null,
        channelId = null,
        notificationId = 1,
        notificationKey = "key-${title.hashCode()}",
        isOngoing = false,
        isGroupSummary = false,
        rawHash = "hash",
    )

    /*
     * Test-only manual reference transcription of the user-provided PNG.
     * This fixture is not OCR output and does not make runtime extraction VERIFIED.
     */
    private val manualReferenceTranscription = """
        2026 토요미래산책(10월) 학생체험 프로그램 신청 안내
        대상 초등 5~6학년, 중 1~3학년
        신청 2026-09-28 월 17:00 ~ 2026-10-02 금 09:00
        ※ 선착순 마감. 홈페이지 공지 및 선정 대상자 문자 발송 2026-10-07 수
        체험 2026-10-17 토 오전 10:00~12:30 또는 오후 14:00~16:30
        체험 2026-10-31 토 오전 10:00~12:30 또는 오후 14:00~16:30
        초등 생태계 병 만들기, 로봇 공룡 만들기
        중등 AI 정조대왕의 힙한 환영, 반도체 공정
        장소 미래과학교육원 전시관 3층 융합과학체험교실
        정원 각 프로그램 40명
        신청 경로 www.gise.kr 체험활동 체험학습신청
        취소 체험일 기준 3일 전까지
        문서 하단 2026-09-17, 전달 파일명 KakaoTalk_20260914_200539030.png
    """.trimIndent()

    private val dottedKoreanTranscription = """
        2026 토요미래산책(10월) 학생체험 프로그램 신청 안내
        대상 초등학교 5~6학년, 중 1~3학년
        신청 2026.9.28.(월)17:00 ~ 2026.10.2.(금)09:00
        ※ 선착순 마감. 홈페이지 공지 및 선정 대상자 문자 발송 2026.10.7.(수)
        체험 2026.10.17.(토) 오전 10:00~12:30 또는 오후 14:00~16:30
        체험 2026.10.31.(토) 오전 10:00~12:30 또는 오후 14:00~16:30
    """.trimIndent()
}
