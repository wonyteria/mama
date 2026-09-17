package kr.mom.probe.connector

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.AttachmentFetchState
import kr.mom.probe.sync.CanonicalSchoolScope
import kr.mom.probe.sync.ChildSourceScope
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolWebsiteParserTest {
    private val familyBoard = SchoolWebsiteParser.boards.first { it.boardId == "12359" }

    @Test fun parsesObservedListSelectorsAndPublishedDates() {
        val page = SchoolWebsiteParser.parseList(resource("family-list-page1.html"), familyBoard)

        assertEquals(427, page.totalCount)
        assertEquals(1, page.currentPage)
        assertEquals(43, page.totalPages)
        val feeNotice = page.entries.first { it.itemId == "1953062" }
        assertEquals("2026학년도 6학년 1일형 현장체험학습 스쿨뱅킹 안내장", feeNotice.title)
        assertEquals("2026-09-04", feeNotice.publishedDateIso)
        assertEquals("https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttInfo.do?mi=14305&bbsId=12359&nttSn=1953062", feeNotice.detailUrl)
        assertTrue(page.issues.isEmpty())
    }

    @Test fun parsesPostPaginationFixtureAsSecondPage() {
        val page = SchoolWebsiteParser.parseList(resource("family-list-page2-post.html"), familyBoard)

        assertEquals(2, page.currentPage)
        assertEquals("1908174", page.entries.first().itemId)
        assertFalse(page.entries.any { it.itemId == "1953062" })
    }

    @Test fun preservesNestedDetailBodyAndMarksGradeSixNoticeIneligibleForSecondGrade() {
        val entry = SchoolWebsiteParser.parseList(resource("family-list-page1.html"), familyBoard).entries.first { it.itemId == "1953062" }
        val detail = SchoolWebsiteParser.parseDetail(resource("family-detail-1953062-grade6.html"), familyBoard, "1953062")
        val notice = SchoolWebsiteParser.toFetchedNotice(scope(), entry, detail, fetchedAt = 1_779_900_000_000L)

        assertEquals("2026학년도 6학년 1일형 현장체험학습 스쿨뱅킹 안내장", detail.title)
        assertTrue(detail.body.contains("운영 대상"))
        assertTrue(detail.body.contains("개인 도시락"))
        assertTrue(detail.body.length > 1_000)
        assertTrue(notice.audienceFacts.any {
            it.applicability == NoticeApplicability.INELIGIBLE && it.gradeStart == 6 && it.gradeEnd == 6
        })
        assertEquals(NoticeContentState.VERIFIED, notice.contentState)
        assertTrue(notice.dateFacts.any { it.role == NoticeDateRole.PUBLICATION && it.dateIso == "2026-09-04" })
    }

    @Test fun keepsHwpxAttachmentAsLinkOnlyWithUnsupportedIssue() {
        val detail = SchoolWebsiteParser.parseDetail(resource("family-detail-1953062-grade6.html"), familyBoard, "1953062")

        val attachment = detail.attachments.single { it.title.endsWith(".hwpx") }
        assertTrue(attachment.url.startsWith("https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_12359/"))
        assertEquals(AttachmentFetchState.LINK_ONLY, attachment.state)
        assertTrue(detail.issues.any { it.message.contains("링크만 보존") })
    }

    @Test fun rejectsDetailWhenRequestedItemDoesNotMatchHiddenFormIds() {
        val detail = SchoolWebsiteParser.parseDetail(resource("family-detail-1953062-grade6.html"), familyBoard, "1951993")

        assertFalse(detail.identityVerified)
        assertTrue(detail.issues.any { it.message.contains("ID가 일치하지 않아요") })
    }

    @Test fun parentParticipationNoticeWithEmptyHtmlBodyStaysAttachmentOnly() {
        val entry = SchoolWebsiteParser.parseList(resource("family-list-page1.html"), familyBoard).entries.first { it.itemId == "1951993" }
        val detail = SchoolWebsiteParser.parseDetail(resource("family-detail-1951993-parent-day.html"), familyBoard, "1951993")
        val notice = SchoolWebsiteParser.toFetchedNotice(scope(), entry, detail, fetchedAt = 1_779_900_000_000L)

        assertEquals("2026 학부모 수업 참여의 날 안내", detail.title)
        assertEquals("", detail.body)
        assertEquals(NoticeContentState.ATTACHMENT_MISSING, notice.contentState)
        assertTrue(detail.attachments.any { it.title == "가정통신문(2026).hwp" && it.state == AttachmentFetchState.LINK_ONLY })
        assertTrue(notice.issues.any { it.message.contains("본문을 찾지 못했어요") })
        assertFalse(notice.audienceFacts.any { it.gradeStart == 6 && it.evidence.value.contains("2026") })
        assertTrue(notice.audienceFacts.any { it.applicability == NoticeApplicability.UNKNOWN && !it.schoolWide && it.evidence.value.isBlank() })
    }

    @Test fun explicitGradeRangeIsUnknownWhenChildGradeIsUnknown() {
        val entry = SchoolWebsiteParser.parseList(resource("family-list-page1.html"), familyBoard).entries.first { it.itemId == "1953062" }
        val detail = SchoolWebsiteParser.parseDetail(resource("family-detail-1953062-grade6.html"), familyBoard, "1953062")
        val notice = SchoolWebsiteParser.toFetchedNotice(scope(grade = null), entry, detail, fetchedAt = 1_779_900_000_000L)

        assertTrue(notice.audienceFacts.any {
            it.applicability == NoticeApplicability.UNKNOWN && it.gradeStart == 6 && it.gradeEnd == 6
        })
    }

    @Test fun mixedElementaryAndMiddleRangesDoNotUnionIntoElementarySecondGrade() {
        val notice = SchoolWebsiteParser.toFetchedNotice(
            scope = scope(),
            entry = SchoolWebsiteListEntry(familyBoard, "mixed", "혼합 대상", "2026-09-15", false, SchoolWebsiteParser.detailUrl(familyBoard, "mixed")),
            detail = SchoolWebsiteDetail(
                board = familyBoard,
                itemId = "mixed",
                title = "학생체험 프로그램 신청 안내",
                publishedDateIso = "2026-09-15",
                body = "대상 초등 5~6학년, 중 1~3학년. 신청 안내입니다.",
                attachments = emptyList(),
                identityVerified = true,
            ),
            fetchedAt = 1_779_900_000_000L,
        )

        assertFalse(notice.audienceFacts.any { it.applicability == NoticeApplicability.APPLIES })
        assertTrue(notice.audienceFacts.any {
            it.schoolLevel == SchoolLevel.ELEMENTARY && it.gradeStart == 5 && it.gradeEnd == 6 && it.applicability == NoticeApplicability.INELIGIBLE
        })
        assertTrue(notice.audienceFacts.any {
            it.schoolLevel == SchoolLevel.MIDDLE && it.gradeStart == 1 && it.gradeEnd == 3 && it.applicability == NoticeApplicability.INELIGIBLE
        })
    }

    @Test fun negatedSubmissionTextDoesNotBecomeRequiredAction() {
        val notice = SchoolWebsiteParser.toFetchedNotice(
            scope = scope(),
            entry = SchoolWebsiteListEntry(familyBoard, "negated", "제출 없음 안내", "2026-09-15", false, SchoolWebsiteParser.detailUrl(familyBoard, "negated")),
            detail = SchoolWebsiteDetail(
                board = familyBoard,
                itemId = "negated",
                title = "학부모 안내",
                publishedDateIso = "2026-09-15",
                body = "본 안내는 참고용이며 제출할 필요가 없습니다. 별도 준비물 없습니다.",
                attachments = emptyList(),
                identityVerified = true,
            ),
            fetchedAt = 1_779_900_000_000L,
        )

        assertEquals(NoticeObligation.INFORMATIONAL, notice.obligation)
    }

    private fun scope(grade: Int? = 2) = SourceScope(
        sourceId = SourceIds.SCHOOL_WEBSITE,
        kind = SourceKind.SCHOOL_WEBSITE,
        school = CanonicalSchoolScope(
            schoolName = "성남정자초등학교",
            officialHost = SchoolWebsiteParser.HOST,
            officialPathPrefix = SchoolWebsiteParser.PATH_PREFIX,
        ),
        child = ChildSourceScope(schoolLevel = SchoolLevel.ELEMENTARY, grade = grade),
        connectionGeneration = 1L,
        consentEpoch = 1L,
        consentVersion = "test",
        coverageWindow = SourceCoverageWindow(),
        trigger = SourceRunTrigger.MANUAL,
    )

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("schoolweb/$name")).readText(Charsets.UTF_8)
}
