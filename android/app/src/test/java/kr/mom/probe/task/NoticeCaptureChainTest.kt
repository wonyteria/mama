package kr.mom.probe.task

import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.RecordSourceMetadata
import kr.mom.probe.sync.SourceKind
import kr.mom.probe.sync.SourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end synthetic chain through the real capture boundary:
 * synthetic notification -> allowlist (ProbeRules.canCapture) ->
 * normalization (NotificationRecordAssembler) -> canonical grouping ->
 * AutoActionCoordinator.routeFor -> applyAutomaticPlansFrom ->
 * evidence-linked task. No real notification is ever read or cancelled —
 * inputs are constructed payloads only.
 */
class NoticeCaptureChainTest {
    private val now = 1_789_300_000_000L
    private val institution = "테스트학교"

    /** A synthetic app notification through the real allowlist+normalizer. */
    private fun appRecord(
        title: String,
        text: String,
        packageName: String,
        appLabel: String,
        key: String,
        postedAt: Long = now,
    ): ProbeRecord = requireNotNull(
        SyntheticNotificationIngest.accept(
            SyntheticNotificationIngest.notification(
                packageName = packageName, appLabel = appLabel,
                title = title, text = text, key = key, postTime = postedAt,
            ),
            SyntheticNotificationIngest.settingsFor(packageName),
        ),
    ) { "allowlist rejected synthetic notification for $packageName" }

    /**
     * A public web notice — mirrors FetchedNotice.toProbeRecord exactly:
     * source:<sourceId> package, itemId as key, source revision id, real
     * source metadata (never used to fake app notifications).
     */
    private fun webRecord(
        title: String,
        text: String,
        itemId: String,
        dateFacts: List<kr.mom.probe.sync.SourceDateFact> = emptyList(),
        postedAt: Long = now,
    ) = ProbeRecord(
        id = kr.mom.probe.data.ProbeRules.sourceRevisionId("school-board", itemId, "rev-$itemId"),
        packageName = "source:school-board", appLabel = "학교 홈페이지",
        postedAt = postedAt, receivedAt = postedAt,
        title = title, text = text, bigText = text, textLines = emptyList(),
        subText = "https://school.example/notice/$itemId", summaryText = null,
        category = "source", channelId = "school-board",
        notificationId = 0, notificationKey = itemId,
        isOngoing = false, isGroupSummary = false, rawHash = "rev-$itemId",
        sourceMetadata = RecordSourceMetadata(
            kind = SourceKind.SCHOOL_WEBSITE, sourceId = "school-board",
            itemId = itemId, revisionHash = "rev-$itemId",
            origin = SourceOrigin(
                canonicalUrl = "https://school.example/notice/$itemId",
                host = "school.example", rawId = itemId,
            ),
            contentState = NoticeContentState.VERIFIED,
            obligation = NoticeObligation.REQUIRED,
            dateFacts = dateFacts,
            firstSeenAt = postedAt, lastFetchedAt = postedAt,
        ),
    )

    private fun toAutoPlan(it: CandidateActionPlan) = AutoTaskPlan(
        actionKind = it.actionKind ?: "submit",
        text = it.text, checklist = it.checklist,
        dueAt = it.dueAt, remindAt = it.remindAt,
        evidenceText = it.evidenceText, sourceTitle = it.sourceTitle,
        sourceLabel = it.sourceLabel, sourceCapturedAt = it.sourceCapturedAt,
        audienceLabel = it.audienceLabel,
    )

    /**
     * Runs the coordinator's real routing gate, then the store's real apply
     * — the same sequence AutoActionCoordinator.handle performs.
     */
    private fun coordinate(
        record: ProbeRecord,
        tasks: List<AssistantTask>,
        child: ChildNoticeProfile = ChildNoticeProfile(),
    ): Pair<List<AssistantTask>, AutoActionCoordinator.Route> {
        val routing = AutoActionCoordinator.routeFor(record, child, institution, now)
        val next = when (routing.route) {
            AutoActionCoordinator.Route.APPLY -> AssistantTaskStore.applyAutomaticPlansFrom(
                tasks = tasks,
                sourceNotificationId = routing.sourceNotificationId,
                sourceRevisionId = record.id,
                plans = routing.plans.map(::toAutoPlan),
                noticeGroupKeys = routing.groupKeys,
                now = now,
                idProvider = { "gen-${tasks.size}-${routing.plans.size}" },
            ).first
            AutoActionCoordinator.Route.SUSPEND -> AssistantTaskStore.suspendAutomaticSourcesForTest(
                tasks, setOf(routing.sourceNotificationId),
            )
            AutoActionCoordinator.Route.REVIEW -> AssistantTaskStore.markNeedsReviewForTest(
                tasks, routing.sourceNotificationId, record.id, routing.groupKeys,
            )
        }
        return next to routing.route
    }

    @Test fun `required dated app notification chains into a stored task with evidence`() {
        val notice = appRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            packageName = "com.schoolbell_e.schoolbell_e", appLabel = "학교종이", key = "app-1",
        )
        val (tasks, route) = coordinate(notice, emptyList())

        assertEquals(AutoActionCoordinator.Route.APPLY, route)
        assertTrue(tasks.isNotEmpty())
        val task = tasks.first()
        assertTrue(task.noticeGroupKeys.isNotEmpty())
        assertNotNull(task.evidenceText)
        assertEquals("학교종이", task.sourceLabel)
    }

    @Test fun `re-ingesting the same notification never duplicates the task`() {
        val first = appRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "n-1",
        )
        val resend = appRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "n-1",
        )

        val (afterFirst, _) = coordinate(first, emptyList())
        val (afterSecond, _) = coordinate(resend, afterFirst)

        assertEquals(afterFirst.size, afterSecond.size)
    }

    @Test fun `same title from a different app notification stays separate`() {
        val first = appRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "n-1",
        )
        val second = appRecord(
            "체험학습 준비물", "준비물: 모자, 간식. 내일 오후 2시까지",
            "com.vaultmicro.kidsnote", "키즈노트", key = "n-2",
        )

        assertTrue(NoticeGrouping.keys(first, institution)
            .intersect(NoticeGrouping.keys(second, institution)).isEmpty())

        val (afterFirst, _) = coordinate(first, emptyList())
        val (afterSecond, _) = coordinate(second, afterFirst)

        assertTrue(afterSecond.size > afterFirst.size)
    }

    @Test fun `duplicate app and web copies of one official item merge`() {
        // App and web copies share nothing except identical content — the
        // content fingerprint (institution + title + date + body start) is
        // the only honest merge key. The web record carries the same parsed
        // due date the app side extracts from the identical body.
        val appCopy = appRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "app-42",
        )
        // "내일" resolves against the fixture clock (receivedAt = now), not
        // the wall clock — the app-side fingerprint parses the body with it.
        val tomorrow = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.of("Asia/Seoul"))
            .toLocalDate().plusDays(1).toString()
        val webCopy = webRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
            itemId = "42",
            dateFacts = listOf(kr.mom.probe.sync.SourceDateFact(
                role = kr.mom.probe.data.NoticeDateRole.DUE,
                text = "내일 오전 9시까지",
                dateIso = tomorrow,
                hasExplicitTime = true,
            )),
        )

        // Sanity: the copies share a fingerprint key but disjoint strong ids.
        assertTrue(NoticeGrouping.keys(appCopy, institution)
            .any { it.startsWith("fp:") && it in NoticeGrouping.keys(webCopy, institution) })

        val (afterApp, _) = coordinate(appCopy, emptyList())
        val (afterWeb, _) = coordinate(webCopy, afterApp)

        assertEquals(afterApp.size, afterWeb.size)
    }

    @Test fun `same title on different official web documents stays separate`() {
        val first = webRecord(
            "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지", itemId = "42",
        )
        val second = webRecord(
            "체험학습 준비물", "준비물: 모자, 간식. 내일 오후 2시까지", itemId = "77",
        )

        val (afterFirst, _) = coordinate(first, emptyList())
        val (afterSecond, _) = coordinate(second, afterFirst)

        assertTrue(afterSecond.size > afterFirst.size)
    }

    @Test fun `informational app notification produces no automatic task`() {
        val notice = appRecord(
            "학교 소식", "다음 주 화요일은 개교기념일로 쉬는 날입니다.",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "info-1",
        )
        val (tasks, route) = coordinate(notice, emptyList())
        assertEquals(AutoActionCoordinator.Route.SUSPEND, route)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `every allowlisted app identity chains through the real capture boundary`() {
        // The five shipped allowlist identities — pinned against the catalog
        // so a package rename fails here rather than silently in production.
        val sources = listOf(
            "com.schoolbell_e.schoolbell_e" to "학교종이",
            "com.ewut.allealimi" to "e알리미",
            "com.iscreammedia.app.hiclass.android" to "하이클래스",
            "com.vaultmicro.kidsnote" to "키즈노트",
            "com.classnote.android.release" to "클래스노트",
        )
        val catalog = kr.mom.probe.ui.SourceCatalog.candidates.map { it.packageName }
        sources.forEach { (pkg, label) ->
            assertTrue("catalog missing $label", pkg in catalog)
        }

        sources.forEachIndexed { index, (pkg, label) ->
            // A non-allowlisted package never reaches normalization.
            assertNull(
                SyntheticNotificationIngest.accept(
                    SyntheticNotificationIngest.notification(
                        pkg, label, "제목", "본문", "bad-$index", now,
                    ),
                    SyntheticNotificationIngest.settingsFor("kr.unrelated.app"),
                ),
            )
            val notice = appRecord(
                "체험학습 준비물", "준비물: 도시락, 물통. 내일 오전 9시까지",
                pkg, label, key = "n-$index",
            )
            // App notifications carry notification identity, never
            // fabricated web-source metadata.
            assertNull("$label: fabricated sourceMetadata", notice.sourceMetadata)
            val (tasks, route) = coordinate(notice, emptyList())
            assertEquals("$label: not promoted", AutoActionCoordinator.Route.APPLY, route)
            assertTrue("$label: no task created", tasks.isNotEmpty())
            val task = tasks.first()
            assertEquals("$label: app label lost", label, task.sourceLabel)
            assertTrue("$label: evidence missing", !task.evidenceText.isNullOrBlank())
            assertTrue("$label: group keys missing", task.noticeGroupKeys.isNotEmpty())
        }
    }

    @Test fun `scope-mismatched notice is never promoted into the task chain`() {
        // A notification declared for middle-school grade 1 runs the full
        // apply path with a 2nd-grade elementary child — the coordinator
        // suspends, zero tasks form, and the record's evidence identity is
        // preserved for history.
        val outOfScope = appRecord(
            "입학 설명회", "중학교 1학년 대상. 준비물: 필기도구. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "scope-1",
        )
        val secondGrade = ChildNoticeProfile(schoolLevel = SchoolLevel.ELEMENTARY, grade = 2)
        val (tasks, route) = coordinate(outOfScope, emptyList(), secondGrade)

        assertEquals(AutoActionCoordinator.Route.SUSPEND, route)
        assertTrue(tasks.isEmpty())
        // History/evidence identity is preserved even though no task may form.
        assertTrue(NoticeGrouping.keys(outOfScope, institution).isNotEmpty())
    }

    @Test fun `completion survives a resync of the same source`() {
        val notice = appRecord(
            "체험학습 준비물", "준비물: 도시락. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "resync-1",
        )
        val (afterApply, _) = coordinate(notice, emptyList())
        val done = afterApply.map { it.copy(completed = true, completedAt = now) }
        // A same-key resend resolves to the same record identity.
        val resend = appRecord(
            "체험학습 준비물", "준비물: 도시락. 내일 오전 9시까지",
            "com.schoolbell_e.schoolbell_e", "학교종이", key = "resync-1",
        )
        val (afterResync, _) = coordinate(resend, done)

        assertTrue(afterResync.all { it.completed })
        assertEquals(done.size, afterResync.size)
    }
}
