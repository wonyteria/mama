package kr.mom.probe.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kr.mom.probe.agent.LocalAgentContext
import kr.mom.probe.agent.LocalAgentEngine
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.reminder.BriefingReminders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceIngestFlowTest {
    private lateinit var context: Context
    private lateinit var repository: ProbeRepository
    private lateinit var stateStore: SourceSyncStateStore

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa")) { "SourceIngestFlowTest must run only in the QA app." }
        repository = ProbeRepository.get(context)
        repository.isReady.first { it }
        assertTrue(repository.deleteAll())
        stateStore = SourceSyncStateStore.get(context)
        assertTrue(stateStore.reset())
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveSourceSelection(setOf("qa.validation.source")))
        assertTrue(repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY))
        assertTrue(repository.deferSetup())
        Unit
    }

    @After fun tearDown() {
        SourceFetcherRegistry.unregister(SourceIds.SCHOOL_WEBSITE)
    }

    @Test fun sourceSyncRunnerFetchesIngestsAndUpdatesSnapshotWithoutNotificationPermission() = runBlocking {
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()
        SourceFetcherRegistry.register(SourceIds.SCHOOL_WEBSITE, object : SourceFetcher {
            override suspend fun fetch(scope: SourceScope, checkpoint: SourceCheckpoint?): SourceFetchResult {
                return resultWith(notice("runner-item", "runner-rev", eventDate = tomorrow)).copy(
                    fetchedAt = fixedNow(),
                    checkpoint = SourceCheckpoint(
                        coverageWindow = SourceCoverageWindow(pageStart = 1, pageEnd = 1, complete = true),
                        lastFetchedAt = fixedNow(),
                        sourceGeneration = scope.connectionGeneration,
                        itemRevisionHashes = mapOf("runner-item" to "runner-rev"),
                    ),
                )
            }
        })

        val disposition = SourceSyncRunner(context).run(SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
        val snapshot = stateStore.snapshot(SourceIds.SCHOOL_WEBSITE)
        val agenda = SourceRecordSelectors.agenda(repository.records.value, child(), listOf(schoolScope()), now = fixedNow(), daysAhead = 7)

        assertEquals(SourceSyncRunDisposition.SUCCESS, disposition)
        assertEquals(SourceSyncStatus.FETCHED, snapshot?.status)
        assertEquals(1, snapshot?.seenCount)
        assertEquals(1, snapshot?.matchedCount)
        assertEquals(1, snapshot?.storedCount)
        assertTrue((snapshot?.lastSuccessAt ?: 0L) > 0L)
        assertEquals("runner-rev", snapshot?.checkpoint?.itemRevisionHashes?.get("runner-item"))
        assertEquals(listOf(tomorrow), agenda.map { it.dateIso })
        assertFalse(repository.settings.value.collectionEnabled)
    }

    @Test fun duplicateRevisionIsIdempotentAndDeletedSourceItemStaysSuppressed() = runBlocking {
        val scope = schoolScope()
        val result = resultWith(notice("item-1", "rev-1"))

        val first = repository.ingestSource(scope, result)
        val second = repository.ingestSource(scope, result)

        assertEquals(1, first.insertedCount)
        assertEquals(1, second.unchangedCount)
        assertEquals(1, repository.records.value.count { it.sourceMetadata?.sourceId == SourceIds.SCHOOL_WEBSITE })

        val stored = repository.records.value.single { it.sourceMetadata?.itemId == "item-1" }
        assertTrue(repository.deleteRecord(stored.id))
        val afterDeleteScope = schoolScope()
        val afterDelete = repository.ingestSource(afterDeleteScope, resultWith(notice("item-1", "rev-2")))

        assertEquals(1, afterDelete.suppressedCount)
        assertFalse(repository.records.value.any { it.sourceMetadata?.itemId == "item-1" })
    }


    @Test fun batchPublishesOnlyOneRevisionPerSourceItem() = runBlocking {
        val scope = schoolScope()
        val first = notice("same-item", "rev-new")
        val duplicate = notice("same-item", "rev-old")

        val receipt = repository.ingestSource(scope, SourceFetchResult(
            sourceId = SourceIds.SCHOOL_WEBSITE,
            status = SourceSyncStatus.FETCHED,
            fetchedAt = fixedNow(),
            items = listOf(first, duplicate),
            coverage = SourceCoverageWindow(pageStart = 1, pageEnd = 1, complete = true),
        ))

        assertEquals(1, receipt.insertedCount)
        assertEquals(1, receipt.skippedCount)
        assertEquals(listOf("rev-new"), repository.records.value.mapNotNull { it.sourceMetadata?.revisionHash })
    }

    @Test fun changedRevisionKeepsOriginalFirstSeenAt() = runBlocking {
        val firstSeen = fixedNow() - 86_400_000L
        val laterSeen = fixedNow()
        repository.ingestSource(schoolScope(), resultWith(notice("changing", "rev-1", firstSeenAt = firstSeen)))

        val receipt = repository.ingestSource(schoolScope(), resultWith(notice("changing", "rev-2", firstSeenAt = laterSeen)))
        val stored = repository.records.value.single { it.sourceMetadata?.itemId == "changing" }

        assertEquals(1, receipt.changedCount)
        assertEquals("rev-2", stored.sourceMetadata?.revisionHash)
        assertEquals(firstSeen, stored.sourceMetadata?.firstSeenAt)
        assertEquals(firstSeen, stored.receivedAt)
    }

    @Test fun sourceGenerationChangeHidesOldSourceRecordsFromAgendaChatAndBriefing() = runBlocking {
        val scope = schoolScope()
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()
        repository.ingestSource(scope, resultWith(notice("event", "rev", eventDate = tomorrow)))
        assertEquals(1, SourceRecordSelectors.agenda(repository.records.value, child(), listOf(scope)).size)

        stateStore.bumpGeneration(SourceIds.SCHOOL_WEBSITE)
        val newScope = schoolScope()
        val filtered = SourceRecordSelectors.activeRecords(repository.records.value, listOf(newScope))
        val agenda = SourceRecordSelectors.agenda(repository.records.value, child(), listOf(newScope))
        val reply = LocalAgentEngine(nowMillis = { fixedNow() }).answer(
            "내일 일정 알려줘",
            LocalAgentContext("QA", repository.records.value, emptyList(), child(), listOf(newScope)),
        )
        val briefing = BriefingReminders.briefingAgenda(context, repository.records.value, repository.settings.value, slot = 1, now = fixedNow())

        assertFalse(filtered.any { it.sourceMetadata != null })
        assertTrue(agenda.isEmpty())
        assertTrue(reply.message.contains("찾지 못했어요"))
        assertTrue(briefing.isEmpty())
    }

    @Test fun noNotificationPermissionStillUsesStoredGradeTwoInformationalAgendaEveryEventDate() = runBlocking {
        val scope = schoolScope()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val notice = notice(
            "multi-day",
            "rev",
            eventDate = today.toString(),
            extraEventDate = today.plusDays(1).toString(),
        )
        repository.ingestSource(scope, resultWith(notice))

        val agenda = SourceRecordSelectors.agenda(repository.records.value, child(), listOf(scope), now = fixedNow(), daysAhead = 7)
        val reply = LocalAgentEngine(nowMillis = { fixedNow() }).answer(
            "내일 학교 일정 뭐야?",
            LocalAgentContext("QA", repository.records.value, emptyList(), child(), listOf(scope)),
        )
        val briefing = BriefingReminders.briefingAgenda(context, repository.records.value, repository.settings.value, slot = 1, now = fixedNow())

        assertEquals(listOf(today.toString(), today.plusDays(1).toString()), agenda.map { it.dateIso })
        assertTrue(reply.message.contains("QA 학교 일정"))
        assertEquals(1, briefing.size)
        assertEquals(today.plusDays(1).toString(), briefing.single().dateIso)
        assertFalse(repository.settings.value.collectionEnabled)
    }

    @Test fun emptyPartialDoesNotAdvanceSuccessOrCheckpoint() {
        val scope = schoolScope()
        val partial = SourceFetchResult(
            sourceId = scope.sourceId,
            status = SourceSyncStatus.PARTIAL,
            fetchedAt = fixedNow(),
            coverage = SourceCoverageWindow(complete = false),
            checkpoint = SourceCheckpoint(cursor = "should-not-commit"),
            issues = listOf(SourceIssue(SourceIssueCode.NETWORK_UNAVAILABLE, "network down")),
        )

        stateStore.markRunning(scope, fixedNow() - 1)
        val snapshot = stateStore.recordResult(scope, partial, null)

        assertFalse(partial.canCommitRecords)
        assertNull(snapshot.lastSuccessAt)
        assertNull(snapshot.checkpoint)
        assertEquals(SourceSyncStatus.PARTIAL, snapshot.status)
    }

    @Test fun stateStoreRejectsLateResultAfterResetInsteadOfRecreatingState() {
        val scope = schoolScope()
        stateStore.markRunning(scope, fixedNow() - 1)
        assertTrue(stateStore.reset())

        val failed = runCatching {
            stateStore.recordResult(scope, resultWith(notice("late", "rev")), null)
        }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertNull(stateStore.snapshot(SourceIds.SCHOOL_WEBSITE))
    }

    @Test fun markRunningRejectsARequestWhoseScopeWasRevokedBeforeCommit() {
        val scope = schoolScope()
        val failed = runCatching {
            stateStore.markRunning(scope, fixedNow()) { false }
        }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertNull(stateStore.snapshot(SourceIds.SCHOOL_WEBSITE))
    }

    @Test fun stateStoreMutationFailsClosedWhenEncryptedPayloadIsCorrupt() {
        context.getSharedPreferences("source-sync-state", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted", "not-valid-base64")
            .commit()

        val failed = runCatching { stateStore.markRunning(schoolScope(), fixedNow()) }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertNull(stateStore.snapshot(SourceIds.SCHOOL_WEBSITE))
    }

    private fun schoolScope(): SourceScope = SourceScopeFactory.scopeFor(context, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)!!

    private fun child() = kr.mom.probe.data.NoticeDecisionEngine.childProfile(repository.settings.value)

    private fun resultWith(item: FetchedNotice) = SourceFetchResult(
        sourceId = item.sourceId,
        status = SourceSyncStatus.FETCHED,
        fetchedAt = fixedNow(),
        items = listOf(item),
        coverage = SourceCoverageWindow(pageStart = 1, pageEnd = 1, complete = true),
    )

    private fun notice(
        itemId: String,
        revisionHash: String,
        eventDate: String = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
        extraEventDate: String? = null,
        firstSeenAt: Long = fixedNow(),
    ): FetchedNotice = FetchedNotice(
        sourceId = SourceIds.SCHOOL_WEBSITE,
        itemId = itemId,
        revisionHash = revisionHash,
        firstSeenAt = firstSeenAt,
        publishedAt = fixedNow(),
        title = "QA 학교 일정",
        body = "QA 검증용 초등 2학년 학교 일정입니다.",
        origin = SourceOrigin("https://snjj-e.goesn.kr/snjj-e/qa/$itemId", "snjj-e.goesn.kr", "qa", itemId),
        contentState = NoticeContentState.VERIFIED,
        obligation = NoticeObligation.INFORMATIONAL,
        audienceFacts = listOf(SourceAudienceFact(
            applicability = NoticeApplicability.APPLIES,
            schoolLevel = SchoolLevel.ELEMENTARY,
            gradeStart = 2,
            gradeEnd = 2,
            evidence = SourceEvidence("대상", "초등 2학년"),
        )),
        dateFacts = listOfNotNull(
            SourceDateFact(NoticeDateRole.EVENT, eventDate, eventDate, evidence = SourceEvidence("일정", eventDate)),
            extraEventDate?.let { SourceDateFact(NoticeDateRole.EVENT, it, it, evidence = SourceEvidence("일정", it)) },
        ),
        evidence = listOf(SourceEvidence("qa", "synthetic test fixture")),
    )

    private fun fixedNow(): Long = Instant.now().toEpochMilli()
}
