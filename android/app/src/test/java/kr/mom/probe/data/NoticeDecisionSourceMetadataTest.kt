package kr.mom.probe.data

import java.time.Instant
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceAudienceFact
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeDecisionSourceMetadataTest {
    private val now = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()

    @Test fun sourceMetadataDrivesInformationalAgendaWithoutRequiredTask() {
        val record = sourceRecord(
            title = "학교 전체 행사",
            body = "제목에 준비물이라는 단어가 있어도 typed obligation이 우선이에요.",
            metadata = metadata(
                obligation = NoticeObligation.INFORMATIONAL,
                audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, schoolWide = true, evidence = SourceEvidence("neis", "전학년")),
                dates = listOf(SourceDateFact(NoticeDateRole.EVENT, "9월 16일", "2026-09-16", hasExplicitTime = false)),
            ),
        )

        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile(2, SchoolLevel.ELEMENTARY))

        assertEquals(NoticeApplicability.APPLIES, decision.applicability)
        assertEquals(NoticeObligation.INFORMATIONAL, decision.obligation)
        assertTrue(decision.dates.any { it.role == NoticeDateRole.EVENT && it.dateIso == "2026-09-16" })
        assertFalse(decision.isRequiredForChild())
    }

    @Test fun sourceMetadataBodyGradeConflictBecomesUnknown() {
        val record = sourceRecord(
            title = "6학년 안내",
            body = "본문에는 2학년이라는 단어가 잘못 섞여 있어도 typed audience가 우선이에요.",
            metadata = metadata(
                audience = SourceAudienceFact(NoticeApplicability.INELIGIBLE, SchoolLevel.ELEMENTARY, 6, 6, evidence = SourceEvidence("html", "6학년")),
            ),
        )

        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile(2, SchoolLevel.ELEMENTARY))

        assertEquals(NoticeApplicability.UNKNOWN, decision.applicability)
        assertFalse(decision.isRequiredForChild())
    }


    @Test fun sourceMetadataVerifiedDoesNotCreateActionWhenStoredBodyIsTruncated() {
        val record = sourceRecord(
            title = "준비물 안내",
            body = "준비물: 물통. 내일까지 제출해 주세요.",
            metadata = metadata(
                obligation = NoticeObligation.REQUIRED,
                audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 2, 2, evidence = SourceEvidence("html", "2학년")),
                dates = listOf(SourceDateFact(NoticeDateRole.DUE, "내일", "2026-09-16")),
            ),
        ).copy(truncated = true)

        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile(2, SchoolLevel.ELEMENTARY))

        assertEquals(NoticeContentState.PARTIAL_EXTRACTION, decision.contentState)
        assertEquals(NoticeObligation.REQUIRED, decision.obligation)
        assertEquals(null, decision.action)
        assertTrue(decision.issues.any { it.contains("일부") })
    }

    @Test fun typedAppliesButConflictingBodyGradeBecomesUnknown() {
        val record = sourceRecord(
            title = "현장체험학습",
            body = "대상: 초등 6학년. 준비물: 물통.",
            metadata = metadata(
                obligation = NoticeObligation.INFORMATIONAL,
                audience = SourceAudienceFact(NoticeApplicability.APPLIES, SchoolLevel.ELEMENTARY, 2, 2, evidence = SourceEvidence("html", "2학년")),
            ),
        )

        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile(2, SchoolLevel.ELEMENTARY))

        assertEquals(NoticeApplicability.UNKNOWN, decision.applicability)
        assertTrue(decision.applicabilityReason.contains("원문 확인"))
    }

    private fun metadata(
        obligation: NoticeObligation = NoticeObligation.INFORMATIONAL,
        audience: SourceAudienceFact,
        dates: List<kr.mom.probe.sync.SourceDateFact> = emptyList(),
    ) = RecordSourceMetadata(
        kind = SourceKind.SCHOOL_WEBSITE,
        sourceId = SourceIds.SCHOOL_WEBSITE,
        itemId = "item",
        revisionHash = "hash",
        origin = SourceOrigin("https://snjj-e.goesn.kr/snjj-e/notice", "snjj-e.goesn.kr"),
        contentState = NoticeContentState.VERIFIED,
        obligation = obligation,
        audienceFacts = listOf(audience),
        dateFacts = dates,
        firstSeenAt = now,
        lastFetchedAt = now,
    )

    private fun sourceRecord(title: String, body: String, metadata: RecordSourceMetadata) = ProbeRecord(
        id = "id",
        packageName = "source:${metadata.sourceId}",
        appLabel = "성남정자초 공식 홈페이지",
        postedAt = now,
        receivedAt = now,
        title = title,
        text = body,
        bigText = body,
        textLines = emptyList(),
        subText = metadata.origin.canonicalUrl,
        summaryText = null,
        category = "source",
        channelId = metadata.sourceId,
        notificationId = 0,
        notificationKey = metadata.itemId,
        isOngoing = false,
        isGroupSummary = false,
        rawHash = metadata.revisionHash,
        sourceMetadata = metadata,
    )
}
