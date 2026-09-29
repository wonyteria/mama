package kr.mom.probe.voice

import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause
import kr.mom.probe.agent.CaptureDisposition
import kr.mom.probe.agent.CaptureLabels
import kr.mom.probe.agent.CapturePlan
import kr.mom.probe.agent.CalendarCreateCommand
import kr.mom.probe.agent.LocalAgentReply
import kr.mom.probe.agent.SchedulePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable write contract: a captureId is the idempotency key across the
 * real task/calendar write, not just the transcript record. Every step is
 * journaled so a mid-batch failure is retryable and never duplicated.
 */
class CaptureWriteCoordinatorTest {

    private class MemSink : CaptureJournalSink {
        val journals = mutableMapOf<String, CaptureWriteJournal>()
        var failOnSave = false
        override fun journalFor(captureId: String): CaptureWriteJournal =
            journals[captureId] ?: CaptureWriteJournal(captureId)
        override fun saveJournal(journal: CaptureWriteJournal) {
            if (failOnSave) throw IllegalStateException("disk full")
            journals[journal.captureId] = journal
        }
    }

    private class SpyWriter(var failOn: ((String, Int) -> Boolean)? = null) : CaptureClauseWriter {
        val writes = mutableListOf<Pair<String, Int>>()
        override fun write(captureId: String, clause: CaptureClause): String? {
            if (failOn?.invoke(captureId, clause.index) == true) {
                throw CaptureWriteException("write failed for ${clause.index}")
            }
            writes.add(captureId to clause.index)
            return "task-${clause.index}"
        }
    }

    private fun clause(index: Int, intent: AgentIntent = AgentIntent.TASK) = CaptureClause(
        index = index,
        transcript = "clause-$index",
        plan = CapturePlan(
            transcript = "clause-$index",
            reply = LocalAgentReply("m", proposedTask = "clause-$index"),
            intent = intent,
            disposition = CaptureDisposition.KEEP_TODAY,
            labels = listOf(CaptureLabels.USER_SPOKE),
            saveable = true,
        ),
        resolved = true,
    )

    private fun batchOf(captureId: String, vararg clauses: CaptureClause) =
        CaptureBatch(captureId, clauses.toList())

    private fun recorder() = mutableListOf<VoiceCaptureRecord>().let { recs ->
        recs to { r: VoiceCaptureRecord -> recs.add(r).let { Unit } }
    }

    @Test fun `same captureId committed twice writes each clause exactly once`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val (records, recordWriter) = recorder()
        val batch = batchOf("cap-1", clause(0), clause(1))
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        assertEquals(listOf("cap-1" to 0, "cap-1" to 1), tasks.writes)
        assertEquals(1, records.size)
        assertTrue(sink.journals.getValue("cap-1").complete)
    }

    @Test fun `task write then record failure leaves retryable journal without duplicate`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val batch = batchOf("cap-2", clause(0))
        val records = mutableListOf<VoiceCaptureRecord>()
        var recordShouldFail = true
        val recordWriter = { r: VoiceCaptureRecord ->
            if (recordShouldFail) throw IllegalStateException("record write failed")
            records.add(r).let { Unit }
        }
        assertThrows(IllegalStateException::class.java) {
            CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        }
        val partial = sink.journals.getValue("cap-2")
        assertEquals(setOf(0), partial.completedWrites)
        assertFalse(partial.recordWritten)
        // Retry after reconstruction — the task must NOT be rewritten.
        recordShouldFail = false
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        assertEquals(listOf("cap-2" to 0), tasks.writes)
        assertEquals(1, records.size)
        assertTrue(sink.journals.getValue("cap-2").complete)
    }

    @Test fun `mid-batch task failure resumes only the unwritten clauses`() {
        val sink = MemSink()
        val tasks = SpyWriter(failOn = { _, i -> i == 1 })
        val (records, recordWriter) = recorder()
        val batch = batchOf("cap-3", clause(0), clause(1), clause(2))
        assertThrows(CaptureWriteException::class.java) {
            CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        }
        assertEquals(listOf("cap-3" to 0), tasks.writes)
        tasks.failOn = null
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter).commit(batch, "t")
        assertEquals(listOf("cap-3" to 0, "cap-3" to 1, "cap-3" to 2), tasks.writes)
        assertEquals(1, records.size)
    }

    @Test fun `journal save failure stays retryable and the idempotent writer prevents duplicates`() {
        val sink = MemSink()
        // Writers MUST be idempotent on (captureId, clause.index) — the real
        // task writer keys AssistantTaskStore.sourceNotificationId on
        // "voice:<captureId>:<index>" and dedupes. Model that contract here:
        // a journal-save throw after a committed write must not duplicate it.
        val tasks = object : CaptureClauseWriter {
            val writes = mutableListOf<Pair<String, Int>>()
            override fun write(captureId: String, clause: CaptureClause): String? {
                writes.firstOrNull { it == captureId to clause.index }
                    ?: writes.add(captureId to clause.index)
                return "task-${clause.index}"
            }
        }
        val (records, recordWriter) = recorder()
        sink.failOnSave = true
        assertThrows(IllegalStateException::class.java) {
            CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter)
                .commit(batchOf("cap-4", clause(0)), "t")
        }
        sink.failOnSave = false
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter)
            .commit(batchOf("cap-4", clause(0)), "t")
        assertEquals(1, tasks.writes.size)
        assertEquals(1, records.size)
        assertTrue(sink.journals.getValue("cap-4").complete)
    }

    @Test fun `calendar clauses route to the calendar writer not the task store`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val events = SpyWriter()
        val (records, recordWriter) = recorder()
        val batch = batchOf("cap-5", clause(0), clause(1, AgentIntent.CALENDAR))
        CaptureWriteCoordinator(sink, tasks, events, recordWriter).commit(batch, "t")
        assertEquals(listOf("cap-5" to 0), tasks.writes)
        assertEquals(listOf("cap-5" to 1), events.writes)
        assertEquals(1, records.size)
    }

    @Test fun `unresolved kept clause fails fast with zero writes`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val records = mutableListOf<VoiceCaptureRecord>()
        val dropped = clause(1).copy(dropped = true)
        val unresolved = clause(2).copy(
            plan = clause(2).plan.copy(
                disposition = CaptureDisposition.NEEDS_CONFIRM, saveable = false,
            ),
            resolved = false,
        )
        val thrown = assertThrows(CaptureWriteException::class.java) {
            CaptureWriteCoordinator(sink, tasks, SpyWriter()) { r -> records.add(r).let { Unit } }
                .commit(batchOf("cap-6", clause(0), dropped, unresolved), "t")
        }
        // Kept-but-unresolved aborts the whole batch before any durable step.
        assertFalse(thrown.retryable)
        assertTrue(tasks.writes.isEmpty())
        assertTrue(records.isEmpty())
        assertFalse(sink.journals.getOrDefault("cap-6", CaptureWriteJournal("cap-6")).complete)
    }

    @Test fun `dropped clause alone does not block the commit`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val (records, recordWriter) = recorder()
        val dropped = clause(1).copy(dropped = true)
        CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter)
            .commit(batchOf("cap-6b", clause(0), dropped), "t")
        assertEquals(listOf("cap-6b" to 0), tasks.writes)
        assertTrue(sink.journals.getValue("cap-6b").complete)
    }

    @Test fun `memo clauses write only the record never a task`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val events = SpyWriter()
        val (records, recordWriter) = recorder()
        // The explicit "메모로 저장" path produces a MEMO_ONLY clause —
        // it must create no AssistantTask and no calendar event.
        val memo = clause(0).copy(
            plan = clause(0).plan.copy(
                intent = AgentIntent.MEMO, disposition = CaptureDisposition.MEMO_ONLY,
            ),
        )
        CaptureWriteCoordinator(sink, tasks, events, recordWriter)
            .commit(batchOf("cap-7", memo, clause(1)), "말한 내용 전체")
        assertTrue("memo clause wrote a task", tasks.writes.all { it.second != 0 })
        assertEquals(listOf("cap-7" to 1), tasks.writes)
        assertTrue(events.writes.isEmpty())
        // The transcript record still captures the memo — text is kept.
        assertEquals(1, records.size)
        assertTrue(records.first().intentType.contains("MEMO"))
        assertTrue(sink.journals.getValue("cap-7").complete)
    }

    @Test fun `kept question clause fails fast before any write`() {
        val sink = MemSink()
        val tasks = SpyWriter()
        val (records, recordWriter) = recorder()
        // Forced writable QUESTION — a defect the boundary must refuse.
        val bad = clause(0).copy(
            plan = clause(0).plan.copy(intent = AgentIntent.QUESTION),
        )
        val thrown = assertThrows(CaptureWriteException::class.java) {
            CaptureWriteCoordinator(sink, tasks, SpyWriter(), recordWriter)
                .commit(batchOf("cap-8", bad), "t")
        }
        assertFalse(thrown.retryable)
        assertTrue(tasks.writes.isEmpty())
        assertTrue(records.isEmpty())
    }

    private fun calendarClause(
        index: Int,
        action: String? = null,
        dueAt: Long? = null,
    ): CaptureClause {
        val payload = SchedulePayload(
            title = "학부모 상담", startMillis = 1_800_000_000_000L,
            endMillis = 1_800_003_600_000L, zoneId = "Asia/Seoul",
            explicitEnd = false, defaultDurationMinutes = 60,
        )
        return clause(index, AgentIntent.CALENDAR).copy(
            action = action, dueAt = dueAt,
            plan = clause(index, AgentIntent.CALENDAR).plan.copy(
                reply = LocalAgentReply(
                    "m", proposedTask = "학부모 상담",
                    scheduleCommand = CalendarCreateCommand("내일 3시 상담", payload),
                ),
            ),
        )
    }

    @Test fun `calendar payload honors the edits the parent confirmed`() {
        val edited = calendarClause(0, action = "상담 준비물 지참", dueAt = 1_900_000_000_000L)
        val payload = calendarPayloadFor(edited)!!
        // Edited title and start reach the gateway; the parsed duration is kept.
        assertEquals("상담 준비물 지참", payload.title)
        assertEquals(1_900_000_000_000L, payload.startMillis)
        assertEquals(1_900_000_000_000L + 3_600_000L, payload.endMillis)
        assertEquals("Asia/Seoul", payload.zoneId)
    }

    @Test fun `calendar payload without edits keeps the parsed values`() {
        val payload = calendarPayloadFor(calendarClause(0))!!
        assertEquals("학부모 상담", payload.title)
        assertEquals(1_800_000_000_000L, payload.startMillis)
    }
}
