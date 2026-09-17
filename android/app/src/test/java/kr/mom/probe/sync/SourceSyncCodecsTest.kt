package kr.mom.probe.sync

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceSyncCodecsTest {
    @Test fun sourceMetadataRoundTripsTypedFactsWithoutPersonalScopeValues() {
        val metadata = RecordSourceMetadata(
            kind = SourceKind.SCHOOL_WEBSITE,
            sourceId = SourceIds.SCHOOL_WEBSITE,
            itemId = "bbs-12359-1951993",
            revisionHash = "revision-hash",
            origin = SourceOrigin("https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttInfo.do?mi=14305&bbsId=12359&nttSn=1951993", "snjj-e.goesn.kr", "12359", "1951993"),
            contentState = NoticeContentState.ATTACHMENT_MISSING,
            obligation = NoticeObligation.INFORMATIONAL,
            audienceFacts = listOf(SourceAudienceFact(NoticeApplicability.UNKNOWN, SchoolLevel.ELEMENTARY, evidence = SourceEvidence("selector", "본문 없이 첨부만 있는 공지"))),
            dateFacts = listOf(SourceDateFact(NoticeDateRole.PUBLICATION, "2026.09.15", "2026-09-15")),
            attachments = listOf(SourceAttachment("안내문.hwpx", "https://snjj-e.goesn.kr/download.hwpx", "application/hwpml", AttachmentFetchState.LINK_ONLY)),
            evidence = listOf(SourceEvidence("board", "가정통신문")),
            issues = listOf(SourceIssue(SourceIssueCode.UNSUPPORTED_ATTACHMENT, "첨부 본문은 아직 읽지 않았어요.")),
            firstSeenAt = 1_000L,
            lastFetchedAt = 2_000L,
            publishedAt = 900L,
        )

        val encoded = encodeRecordSourceMetadata(metadata)
        val decoded = decodeRecordSourceMetadata(encoded)

        assertEquals(metadata, decoded)
        assertFalse(encoded.toString().contains("cookie", ignoreCase = true))
        assertFalse(encoded.toString().contains("password", ignoreCase = true))
    }

    @Test fun unknownSourceContentStateDecodesFailClosed() {
        val metadata = RecordSourceMetadata(
            kind = SourceKind.SCHOOL_WEBSITE,
            sourceId = SourceIds.SCHOOL_WEBSITE,
            itemId = "item",
            revisionHash = "hash",
            origin = SourceOrigin("https://snjj-e.goesn.kr/snjj-e/notice", "snjj-e.goesn.kr"),
            contentState = NoticeContentState.VERIFIED,
            obligation = NoticeObligation.INFORMATIONAL,
            firstSeenAt = 1L,
            lastFetchedAt = 2L,
        )
        val encoded = encodeRecordSourceMetadata(metadata).put("contentState", "FUTURE_UNKNOWN")

        val decoded = decodeRecordSourceMetadata(encoded)

        assertEquals(NoticeContentState.PARTIAL_EXTRACTION, decoded.contentState)
    }
}
