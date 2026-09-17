package kr.mom.probe.sync

import java.time.Instant
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRecordSelectorsTest {
    private val now = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()

    @Test fun agendaIncludesInformationalElementarySecondGradeEvents() {
        val record = sourceRecord(
            "현장체험학습",
            audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 2, 2, evidence = SourceEvidence("grade", "초등 2학년")),
            dates = listOf(SourceDateFact(NoticeDateRole.EVENT, "9월 16일", "2026-09-16")),
        )

        val agenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY), now, 7)

        assertEquals(listOf("현장체험학습"), agenda.map { it.title })
    }

    @Test fun hwpOnlyNoticeRemainsStoredButDoesNotInventAgenda() {
        val record = sourceRecord(
            "첨부 안내",
            contentState = NoticeContentState.ATTACHMENT_MISSING,
            attachments = listOf(SourceAttachment("안내문.hwpx", "https://snjj-e.goesn.kr/file.hwpx", "application/hwpml", AttachmentFetchState.LINK_ONLY)),
            issues = listOf(SourceIssue(SourceIssueCode.UNSUPPORTED_ATTACHMENT, "첨부 본문은 아직 읽지 않았어요.")),
        )

        val agenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY), now, 7)

        assertTrue(record.sourceMetadata?.issues?.any { it.code == SourceIssueCode.UNSUPPORTED_ATTACHMENT } == true)
        assertEquals(emptyList<SourceAgendaItem>(), agenda)
    }


    @Test fun optionalOpportunityWithIncompleteContentIsNotAssignedAgenda() {
        val record = sourceRecord(
            "선택 프로그램",
            audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 2, 2, evidence = SourceEvidence("grade", "초등 2학년")),
            dates = listOf(SourceDateFact(NoticeDateRole.EVENT, "9월 16일", "2026-09-16")),
            contentState = NoticeContentState.ATTACHMENT_MISSING,
            obligation = NoticeObligation.OPTIONAL_OPPORTUNITY,
        )

        val agenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY), now, 7)

        assertEquals(emptyList<SourceAgendaItem>(), agenda)
    }

    @Test fun agendaIncludesEveryValidatedEventDateForOneNotice() {
        val record = sourceRecord(
            "다일 일정",
            audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 2, 2, evidence = SourceEvidence("grade", "초등 2학년")),
            dates = listOf(
                SourceDateFact(NoticeDateRole.EVENT, "9월 15일", "2026-09-15"),
                SourceDateFact(NoticeDateRole.EVENT, "9월 16일", "2026-09-16"),
            ),
        )

        val agenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY), now, 7)

        assertEquals(listOf("2026-09-15", "2026-09-16"), agenda.map { it.dateIso })
    }

    @Test fun storedPositiveAudienceRangeReevaluatesWhenChildGradeChanges() {
        val record = sourceRecord(
            "고학년 체험",
            audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 5, 6, evidence = SourceEvidence("grade", "초등 5~6학년")),
            dates = listOf(SourceDateFact(NoticeDateRole.EVENT, "9월 16일", "2026-09-16")),
        )

        val gradeTwoAgenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(2, SchoolLevel.ELEMENTARY), now, 7)
        val gradeSixAgenda = SourceRecordSelectors.agenda(listOf(record), ChildNoticeProfile(6, SchoolLevel.ELEMENTARY), now, 7)

        assertEquals(emptyList<SourceAgendaItem>(), gradeTwoAgenda)
        assertEquals(listOf("고학년 체험"), gradeSixAgenda.map { it.title })
    }

    private fun sourceRecord(
        title: String,
        audience: SourceAudienceFact = SourceAudienceFact(NoticeApplicability.UNKNOWN, SchoolLevel.ELEMENTARY, evidence = SourceEvidence("unknown", "대상 미확인")),
        dates: List<SourceDateFact> = emptyList(),
        contentState: NoticeContentState = NoticeContentState.VERIFIED,
        obligation: NoticeObligation = NoticeObligation.INFORMATIONAL,
        attachments: List<SourceAttachment> = emptyList(),
        issues: List<SourceIssue> = emptyList(),
    ): ProbeRecord {
        val metadata = RecordSourceMetadata(
            kind = SourceKind.SCHOOL_WEBSITE,
            sourceId = SourceIds.SCHOOL_WEBSITE,
            itemId = "item-$title",
            revisionHash = "hash-$title",
            origin = SourceOrigin("https://snjj-e.goesn.kr/$title", "snjj-e.goesn.kr"),
            contentState = contentState,
            obligation = obligation,
            audienceFacts = listOf(audience),
            dateFacts = dates,
            attachments = attachments,
            issues = issues,
            firstSeenAt = now,
            lastFetchedAt = now,
        )
        return ProbeRecord(
            id = "record-$title",
            packageName = "source:${SourceIds.SCHOOL_WEBSITE}",
            appLabel = "성남정자초 공식 홈페이지",
            postedAt = now,
            receivedAt = now,
            title = title,
            text = "",
            bigText = "",
            textLines = emptyList(),
            subText = metadata.origin.canonicalUrl,
            summaryText = null,
            category = "source",
            channelId = SourceIds.SCHOOL_WEBSITE,
            notificationId = 0,
            notificationKey = metadata.itemId,
            isOngoing = false,
            isGroupSummary = false,
            rawHash = metadata.revisionHash,
            sourceMetadata = metadata,
        )
    }
}
