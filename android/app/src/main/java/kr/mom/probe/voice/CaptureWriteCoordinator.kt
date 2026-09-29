package kr.mom.probe.voice

import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CaptureBatch
import kr.mom.probe.agent.CaptureClause

/**
 * Durable per-clause write progress for one capture. Persisted between
 * every step so a crash or failed write mid-batch leaves a resumable
 * journal — retrying the same captureId can never duplicate a write that
 * already committed, and an unfinished batch stays visibly retryable.
 */
data class CaptureWriteJournal(
    val captureId: String,
    /** Clause indexes whose task/calendar write already committed. */
    val completedWrites: Set<Int> = emptySet(),
    /** Task ids written per clause, for the transcript record link. */
    val writtenTaskIds: Map<Int, String> = emptyMap(),
    val recordWritten: Boolean = false,
    val complete: Boolean = false,
)

/** Durable journal persistence boundary — implemented by VoiceCaptureStore. */
interface CaptureJournalSink {
    fun journalFor(captureId: String): CaptureWriteJournal
    /** Must persist the journal durably or throw — callers treat a throw as a failed write. */
    fun saveJournal(journal: CaptureWriteJournal)
}

/** One durable write — must be idempotent on (captureId, clause.index). */
fun interface CaptureClauseWriter {
    /** Returns the durable id of the written record (task id / event id), if any. */
    fun write(captureId: String, clause: CaptureClause): String?
}

/** Failure that carries whether a retry can help — surfaced honestly to the UI. */
class CaptureWriteException(message: String, val retryable: Boolean = true) : Exception(message)

/**
 * Transaction boundary for a confirmed capture batch. Every durable step
 * (task/calendar write, transcript record, completion mark) is journaled
 * before the next begins, so a failure anywhere leaves a retryable state
 * with exactly-once semantics per clause.
 */
class CaptureWriteCoordinator(
    private val journals: CaptureJournalSink,
    private val taskWriter: CaptureClauseWriter,
    private val calendarWriter: CaptureClauseWriter,
    /** Persists the transcript record; throws when the write fails. */
    private val recordWriter: (record: VoiceCaptureRecord) -> Unit,
) {
    fun commit(
        batch: CaptureBatch,
        transcript: String,
        createdAt: Long = System.currentTimeMillis(),
    ): CaptureWriteJournal {
        var journal = journals.journalFor(batch.captureId)
        if (journal.complete) return journal
        batch.clauses.filter { it.writable }.forEach { clause ->
            if (clause.index in journal.completedWrites) return@forEach
            val writer = if (clause.intent == AgentIntent.CALENDAR) calendarWriter else taskWriter
            val writtenId = writer.write(batch.captureId, clause)
            journal = journal.copy(
                completedWrites = journal.completedWrites + clause.index,
                writtenTaskIds = writtenId?.let { journal.writtenTaskIds + (clause.index to it) }
                    ?: journal.writtenTaskIds,
            )
            journals.saveJournal(journal)
        }
        if (!journal.recordWritten) {
            recordWriter(
                VoiceCaptureRecord(
                    id = batch.captureId,
                    rawTranscript = transcript,
                    intentType = batch.kept.map { it.intent.name }.distinct().joinToString(","),
                    createdAt = createdAt,
                    linkedTaskId = journal.writtenTaskIds.values.firstOrNull(),
                ),
            )
            journal = journal.copy(recordWritten = true)
            journals.saveJournal(journal)
        }
        journal = journal.copy(complete = true)
        journals.saveJournal(journal)
        return journal
    }
}
