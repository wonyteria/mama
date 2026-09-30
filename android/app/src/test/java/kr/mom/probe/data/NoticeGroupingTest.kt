package kr.mom.probe.data

import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeGroupingTest {
    private val institution = NoticeGrouping.institution(
        ProbeSettings(schoolName = "성남정자초등학교")
    )

    @Test
    fun `app notification and web copy of the same notice share one group`() {
        val body = "가정통신문 12호. 10월 15일 체험학습 준비물: 도시락, 물통. " +
            "세부 안내는 첨부 문서를 확인해 주세요. 학교 생활에 필요한 준비물 안내입니다."
        val appCopy = notification(title = "가정통신문 12호", body = body)
        val webCopy = source(
            sourceId = "school-website-snjj", itemId = "921", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=921",
            title = "가정통신문 12호", body = body,
            dateIso = "2026-10-15", role = NoticeDateRole.EVENT,
        )

        assertEquals(
            NoticeGrouping.groupId(appCopy, institution),
            NoticeGrouping.groupId(webCopy, institution),
        )
        val groups = NoticeGrouping.groupIds(listOf(appCopy, webCopy), institution)
        assertEquals(groups.getValue(appCopy.id), groups.getValue(webCopy.id))
        assertEquals(1, NoticeGrouping.representatives(listOf(appCopy, webCopy), institution).size)
    }

    @Test
    fun `same title but different notices stay in separate groups`() {
        val first = notification(title = "준비물 안내", body = "내일 체험학습 준비물: 도시락을 챙겨 주세요.", notificationKey = "a")
        val second = notification(title = "준비물 안내", body = "다음 주 소풍 준비물: 물통과 모자를 챙겨 주세요.", notificationKey = "b")

        assertNotEquals(
            NoticeGrouping.groupId(first, institution),
            NoticeGrouping.groupId(second, institution),
        )
        assertEquals(2, NoticeGrouping.representatives(listOf(first, second), institution).size)
    }

    @Test
    fun `title alone never merges records`() {
        val undated = notification(title = "가정통신문", body = "학부모 총회 안내입니다.", notificationKey = "a")
        val dated = source(
            sourceId = "school-website-snjj", itemId = "77", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=77",
            title = "가정통신문", body = "학부모 총회 안내입니다.",
            dateIso = "2026-11-02", role = NoticeDateRole.EVENT,
        )
        val sameTitleDifferentBody = notification(
            title = "가정통신문", body = "독서 프로그램 참여 안내입니다.", notificationKey = "c",
        )

        assertNotEquals(
            NoticeGrouping.groupId(undated, institution),
            NoticeGrouping.groupId(dated, institution),
        )
        assertNotEquals(
            NoticeGrouping.groupId(undated, institution),
            NoticeGrouping.groupId(sameTitleDifferentBody, institution),
        )
    }

    @Test
    fun `official external id merges records from the same institution`() {
        val listing = source(
            sourceId = "school-website-snjj", itemId = "55", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/listNtt.do",
            title = "방과후 안내", body = "신청 안내",
        )
        val detail = source(
            sourceId = "school-website-snjj", itemId = "55", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=55",
            title = "방과후 안내", body = "신청 안내 본문",
        )
        val other = source(
            sourceId = "school-website-snjj", itemId = "56", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=56",
            title = "방과후 안내", body = "신청 안내 본문",
        )

        val groups = NoticeGrouping.groupIds(listOf(listing, detail, other), institution)
        assertEquals(groups.getValue(listing.id), groups.getValue(detail.id))
        assertNotEquals(groups.getValue(detail.id), groups.getValue(other.id))
        // Identical title+body fingerprint must not merge items with disjoint official IDs.
        assertFalse(NoticeGrouping.matches(
            NoticeGrouping.keys(detail, institution),
            NoticeGrouping.keys(other, institution),
        ))
        assertTrue(NoticeGrouping.matches(
            NoticeGrouping.keys(listing, institution),
            NoticeGrouping.keys(detail, institution),
        ))
    }

    @Test
    fun `keyless fingerprint copy cannot bridge disjoint official notices`() {
        val body = "동일한 안내 문구와 제목을 사용하는 반복 공지입니다. 신청 내용을 확인해 주세요."
        val officialA = source(
            sourceId = "school-website-snjj", itemId = "101", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=101",
            title = "신청 안내", body = body,
        )
        val appOnly = notification(title = "신청 안내", body = body, notificationKey = "app-copy")
        val officialB = source(
            sourceId = "school-website-snjj", itemId = "102", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=102",
            title = "신청 안내", body = body,
        )

        val groups = NoticeGrouping.groupIds(listOf(officialA, appOnly, officialB), institution)

        assertNotEquals(groups.getValue(officialA.id), groups.getValue(officialB.id))
        assertNotEquals(groups.getValue(officialA.id), groups.getValue(appOnly.id))
        assertNotEquals(groups.getValue(officialB.id), groups.getValue(appOnly.id))
    }

    @Test
    fun `same notice via ealimi app and school website merges on content fingerprint`() {
        val body = "10월 20일 현장체험학습 출발 시간 안내. 오전 8시 40분까지 등교해 주세요. " +
            "준비물과 점심 도시락은 가정에서 준비해 주세요."
        val appCopy = notification(
            title = "현장체험학습 안내", body = body, packageName = "com.ewut.allealimi",
        )
        val webCopy = source(
            sourceId = "school-website-snjj", itemId = "88", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=88",
            title = "현장체험학습 안내", body = body,
            dateIso = "2026-10-20", role = NoticeDateRole.EVENT,
        )

        // The notification lane cannot see the official URL; the shared fingerprint must merge them.
        assertEquals(
            NoticeGrouping.groupId(appCopy, institution),
            NoticeGrouping.groupId(webCopy, institution),
        )
    }

    @Test
    fun `representatives prefers the strongest available body`() {
        val body = "가정통신문 본문. ".repeat(20)
        val weak = notification(title = "안내", body = body, notificationKey = "n")
        val strong = source(
            sourceId = "school-website-snjj", itemId = "9", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=9",
            title = "안내", body = body, verified = true,
        )

        val representatives = NoticeGrouping.representatives(listOf(weak, strong), institution)

        assertEquals(listOf(strong), representatives)
    }

    @Test
    fun `linked detects group membership across lanes`() {
        val body = "제출 안내. 10월 15일까지 서류를 제출해 주세요. 세부 내용은 본문을 참고해 주세요."
        val appCopy = notification(title = "서류 제출", body = body)
        val webCopy = source(
            sourceId = "school-website-snjj", itemId = "31", host = "snjj-e.goesn.kr",
            canonicalUrl = "https://snjj-e.goesn.kr/snjj-e/na/ntt/viewNtt.do?nttSn=31",
            title = "서류 제출", body = body,
            dateIso = "2026-10-15", role = NoticeDateRole.DUE,
        )
        val linkedKeys = NoticeGrouping.keys(appCopy, institution)

        assertTrue(NoticeGrouping.linked(webCopy, institution, linkedKeys))
        assertFalse(
            NoticeGrouping.linked(
                notification(title = "다른 알림", body = "관계없는 내용", notificationKey = "x"),
                institution,
                linkedKeys,
            ),
        )
    }

    private fun notification(
        title: String,
        body: String,
        packageName: String = "com.ewut.allealimi",
        notificationKey: String = "key-${body.hashCode()}",
    ) = ProbeRecord(
        id = "rev-$notificationKey", packageName = packageName, appLabel = "e알리미",
        postedAt = POSTED_AT, receivedAt = POSTED_AT + 1, title = title, text = "", bigText = body,
        textLines = emptyList(), subText = null, summaryText = null, category = null,
        channelId = null, notificationId = 1, notificationKey = notificationKey,
        isOngoing = false, isGroupSummary = false, rawHash = "h-$notificationKey",
    )

    private fun source(
        sourceId: String,
        itemId: String,
        host: String,
        canonicalUrl: String,
        title: String,
        body: String,
        dateIso: String? = null,
        role: NoticeDateRole = NoticeDateRole.EVENT,
        verified: Boolean = false,
    ) = ProbeRecord(
        id = "rev-$sourceId-$itemId", packageName = "source:$sourceId", appLabel = "웹",
        postedAt = POSTED_AT, receivedAt = POSTED_AT + 2, title = title, text = "", bigText = body,
        textLines = emptyList(), subText = null, summaryText = null, category = null,
        channelId = null, notificationId = 0, notificationKey = itemId,
        isOngoing = false, isGroupSummary = false, rawHash = "h-$itemId",
        sourceMetadata = RecordSourceMetadata(
            kind = SourceKind.SCHOOL_WEBSITE,
            sourceId = sourceId,
            itemId = itemId,
            revisionHash = "r-$itemId",
            origin = SourceOrigin(canonicalUrl = canonicalUrl, host = host, rawId = itemId),
            contentState = if (verified) NoticeContentState.VERIFIED else NoticeContentState.NOTIFICATION_ONLY,
            obligation = NoticeObligation.INFORMATIONAL,
            dateFacts = listOfNotNull(dateIso?.let {
                SourceDateFact(role = role, text = it, dateIso = it, evidence = SourceEvidence("t", it))
            }),
            firstSeenAt = POSTED_AT,
            lastFetchedAt = POSTED_AT + 2,
        ),
    )

    private companion object {
        // 2026-10-01 in UTC; implicit-year dates in Korean copy resolve to 2026 under this anchor.
        const val POSTED_AT = 1_790_812_800_000L
    }
}
