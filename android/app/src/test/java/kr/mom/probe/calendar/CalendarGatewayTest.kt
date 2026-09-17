package kr.mom.probe.calendar

import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.mom.probe.agent.SchedulePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGatewayTest {
    @Test
    fun `provider deleted flag is treated as logical absence`() {
        assertTrue(calendarProviderDeletedFlagIsAbsent(1))
        assertFalse(calendarProviderDeletedFlagIsAbsent(0))
    }
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = millis(2026, 9, 15, 10, 0)
    private val destination = CalendarDestination(7, "개인", "mom@example.com", "LOCAL", "mom@example.com")

    @Test
    fun savesOnlyAfterInsertReadbackMatches() {
        val backend = FakeBackend(destination)
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })
        val payload = payload()

        val result = gateway.saveEvent("request-1", "raw", payload)

        assertEquals(CalendarSaveState.SAVED, result.state)
        assertEquals(1, backend.insertCount)
        assertEquals(CalendarCommandStatus.SAVED, journal.find("request-1")!!.status)
        assertTrue(journal.find("request-1")!!.snapshot != null)
    }

    @Test
    fun retryOfPreparedRequestDoesNotInsertAgain() {
        val backend = FakeBackend(destination)
        val journal = MemoryJournal()
        val payload = payload()
        journal.claim("request-1", "raw", payload, destination, null)
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })

        val result = gateway.saveEvent("request-1", "raw", payload)

        assertEquals(CalendarSaveState.UNCERTAIN, result.state)
        assertEquals(0, backend.insertCount)
    }

    @Test
    fun insertedRequestCanRecoverAfterStartPassesAndPreferenceChanges() {
        val payload = payload()
        val backend = FakeBackend(destination)
        backend.events[1] = CalendarEventSnapshot(1, destination.calendarId, payload.title, payload.startMillis, payload.endMillis, payload.zoneId, null, null, false, false, null)
        val journal = MemoryJournal()
        journal.update(CalendarCommandRecord("request-1", "raw", payload, destination, CalendarCommandStatus.INSERTED, eventId = 1))
        val changedPreference = destination.copy(calendarId = 12, accountName = "changed@example.com")
        val gateway = CalendarGateway(FakePrefs(changedPreference), journal, backend, nowMillis = { millis(2026, 10, 17, 16, 0) })

        val result = gateway.saveEvent("request-1", "raw", payload)

        assertEquals(CalendarSaveState.SAVED, result.state)
        assertEquals(0, backend.insertCount)
        assertEquals(destination, journal.find("request-1")!!.destination)
    }

    @Test
    fun reusedRequestIdWithDifferentPayloadIsRejected() {
        val journal = MemoryJournal()
        journal.claim("request-1", "raw", payload(), destination, null)
        val gateway = CalendarGateway(FakePrefs(destination), journal, FakeBackend(destination), nowMillis = { now })

        val result = gateway.saveEvent("request-1", "raw", payload().copy(title = "다른 제목"))

        assertEquals(CalendarSaveState.FAILED, result.state)
    }

    @Test
    fun insertDispatchFailureIsUncertainAndNotRetried() {
        val backend = FakeBackend(destination).apply { throwOnInsert = true }
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })

        val first = gateway.saveEvent("request-1", "raw", payload())
        val second = gateway.saveEvent("request-1", "raw", payload())

        assertEquals(CalendarSaveState.UNCERTAIN, first.state)
        assertEquals(CalendarSaveState.UNCERTAIN, second.state)
        assertEquals(1, backend.insertCount)
    }

    @Test
    fun concurrentSameRequestOnlyOneCallerInserts() {
        val backend = FakeBackend(destination).apply {
            insertStarted = CountDownLatch(1)
            insertGate = CountDownLatch(1)
        }
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })
        val payload = payload()
        val results = Collections.synchronizedList(mutableListOf<CalendarSaveState>())
        val first = Thread {
            results += gateway.saveEvent("request-1", "raw", payload).state
        }
        val second = Thread {
            results += gateway.saveEvent("request-1", "raw", payload).state
        }

        first.start()
        assertTrue(backend.insertStarted!!.await(1, TimeUnit.SECONDS))
        second.start()
        second.join(1_000)
        assertFalse(second.isAlive)
        backend.insertGate!!.countDown()
        first.join()

        assertEquals(1, backend.insertCount)
        assertTrue(results.contains(CalendarSaveState.SAVED))
        assertTrue(results.contains(CalendarSaveState.UNCERTAIN))
        assertEquals(CalendarCommandStatus.SAVED, journal.find("request-1")!!.status)
    }

    @Test
    fun defaultDurationCrossingMidnightIsRejectedBeforeInsert() {
        val backend = FakeBackend(destination)
        val gateway = CalendarGateway(FakePrefs(destination), MemoryJournal(), backend, nowMillis = { now })
        val payload = SchedulePayload("상담", millis(2026, 10, 17, 23, 30), millis(2026, 10, 18, 1, 0), zone.id, explicitEnd = false, defaultDurationMinutes = 90)

        val result = gateway.saveEvent("request-1", "raw", payload)

        assertEquals(CalendarSaveState.FAILED, result.state)
        assertEquals(0, backend.insertCount)
    }

    @Test
    fun generationChangeBeforeReadbackDoesNotCommitUncertainDowngrade() {
        var allowed = true
        val backend = FakeBackend(destination).apply {
            throwOnRead = true
            beforeRead = { allowed = false }
        }
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now }) { expected -> allowed && expected == 7L }

        val result = gateway.saveEvent("request-1", "raw", payload(), expectedGeneration = 7L)

        assertEquals(CalendarSaveState.UNCERTAIN, result.state)
        assertEquals(CalendarCommandStatus.INSERTED, journal.find("request-1")!!.status)
    }

    @Test
    fun journalReadFailureBlocksNewInsert() {
        val backend = FakeBackend(destination)
        val gateway = CalendarGateway(FakePrefs(destination), ThrowingJournal(), backend, nowMillis = { now })

        val result = gateway.saveEvent("request-1", "raw", payload())

        assertEquals(CalendarSaveState.FAILED, result.state)
        assertEquals(0, backend.insertCount)
    }
    @Test
    fun verifyExistingMissingJournalRecordNeverInserts() {
        val backend = FakeBackend(destination)
        val gateway = CalendarGateway(FakePrefs(destination), MemoryJournal(), backend, nowMillis = { now })

        val result = gateway.verifyExistingEvent("missing-request", expectedGeneration = 1L)

        assertEquals(CalendarSaveState.UNCERTAIN, result.state)
        assertEquals(0, backend.insertCount)
    }

    @Test
    fun claimCommitBlockedDoesNotPersistPreparedRecord() {
        val journal = MemoryJournal()

        val claim = journal.claimIfAllowed("request-1", "raw", payload(), destination, null) { false }

        assertEquals(CalendarClaimResult.CommitBlocked, claim)
        assertNull(journal.find("request-1"))
    }

    @Test
    fun staleCalendarBindingBlocksWrite() {
        val backend = FakeBackend(destination.copy(accountName = "other@example.com"))
        val gateway = CalendarGateway(FakePrefs(destination), MemoryJournal(), backend, nowMillis = { now })

        val result = gateway.saveEvent("request-1", "raw", payload())

        assertEquals(CalendarSaveState.STALE_DESTINATION, result.state)
        assertEquals(0, backend.insertCount)
    }

    @Test
    fun undoDeletesOnlyUnchangedSavedSnapshot() {
        val backend = FakeBackend(destination)
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })
        val saved = gateway.saveEvent("request-1", "raw", payload()).record!!

        val undo = gateway.undo(saved, 1)

        assertEquals(CalendarUndoState.UNDONE, undo.state)
        assertNull(backend.readEvent(1))
        assertEquals(CalendarCommandStatus.UNDONE, journal.find("request-1")!!.status)
    }

    @Test
    fun undoOffersViewWhenEventChanged() {
        val backend = FakeBackend(destination)
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })
        val saved = gateway.saveEvent("request-1", "raw", payload()).record!!
        backend.events[1] = backend.events[1]!!.copy(title = "바뀐 제목")

        val undo = gateway.undo(saved, 1)

        assertEquals(CalendarUndoState.EXTERNALLY_CHANGED, undo.state)
        assertTrue(backend.events.containsKey(1))
        assertTrue(undo.viewIntent != null)
    }

    @Test
    fun undoCannotVerifyWhenReadbackUnavailable() {
        val backend = FakeBackend(destination)
        val journal = MemoryJournal()
        val gateway = CalendarGateway(FakePrefs(destination), journal, backend, nowMillis = { now })
        val saved = gateway.saveEvent("request-1", "raw", payload()).record!!
        backend.throwOnRead = true

        val undo = gateway.undo(saved, 1)

        assertEquals(CalendarUndoState.CANNOT_VERIFY, undo.state)
        assertTrue(backend.events.containsKey(1))
    }

    private fun payload() = SchedulePayload("치과", millis(2026, 10, 17, 14, 0), millis(2026, 10, 17, 15, 0), zone.id, false, 60)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private class FakePrefs(private var destination: CalendarDestination?) : CalendarPreferencesPort {
        override fun selectedCalendar(): CalendarDestination? = destination
        override fun saveSelectedCalendar(destination: CalendarDestination): Boolean { this.destination = destination; return true }
        override fun defaultDurationMinutes(): Int = 60
        override fun saveDefaultDurationMinutes(minutes: Int): Boolean = true
    }

    private class MemoryJournal : CalendarJournal {
        private val records = linkedMapOf<String, CalendarCommandRecord>()
        @Synchronized
        override fun find(requestId: String): CalendarCommandRecord? = records[requestId]
        @Synchronized
        override fun unresolved(): List<CalendarCommandRecord> =
            records.values.filter { it.status in setOf(CalendarCommandStatus.PREPARED, CalendarCommandStatus.INSERTED, CalendarCommandStatus.UNCERTAIN) }
        @Synchronized
        override fun claim(requestId: String, rawText: String, payload: SchedulePayload, destination: CalendarDestination, consentGeneration: Long?): CalendarClaimResult {
            records[requestId]?.let {
                return if (it.payload == payload) CalendarClaimResult.Existing(it) else CalendarClaimResult.Conflict(it)
            }
            val record = CalendarCommandRecord(requestId, rawText, payload, destination, CalendarCommandStatus.PREPARED, consentGeneration = consentGeneration)
            records[requestId] = record
            return CalendarClaimResult.Claimed(record)
        }
        @Synchronized
        override fun claimIfAllowed(
            requestId: String,
            rawText: String,
            payload: SchedulePayload,
            destination: CalendarDestination,
            consentGeneration: Long?,
            canCommit: () -> Boolean,
        ): CalendarClaimResult {
            if (!canCommit()) return CalendarClaimResult.CommitBlocked
            return claim(requestId, rawText, payload, destination, consentGeneration)
        }
        @Synchronized
        override fun update(record: CalendarCommandRecord) {
            val current = records[record.requestId]
            if (current != null && shouldKeepCurrent(current, record)) return
            records[record.requestId] = record
        }
        @Synchronized
        override fun updateIfAllowed(record: CalendarCommandRecord, canCommit: () -> Boolean): Boolean {
            if (!canCommit()) return false
            update(record)
            return true
        }

        private fun shouldKeepCurrent(current: CalendarCommandRecord, next: CalendarCommandRecord): Boolean {
            if (current.status == CalendarCommandStatus.UNDONE && next.status != CalendarCommandStatus.UNDONE) return true
            if (current.status == CalendarCommandStatus.SAVED && next.status != CalendarCommandStatus.UNDONE) return true
            if (current.eventId != null && next.eventId == null &&
                next.status in setOf(CalendarCommandStatus.PREPARED, CalendarCommandStatus.UNCERTAIN, CalendarCommandStatus.FAILED)
            ) return true
            return false
        }
    }

    private class ThrowingJournal : CalendarJournal {
        override fun find(requestId: String): CalendarCommandRecord? = throw IllegalStateException("corrupt")
        override fun claim(requestId: String, rawText: String, payload: SchedulePayload, destination: CalendarDestination, consentGeneration: Long?): CalendarClaimResult = throw IllegalStateException("corrupt")
        override fun update(record: CalendarCommandRecord) = Unit
    }

    private class FakeBackend(private val liveDestination: CalendarDestination) : CalendarBackend {
        val events = mutableMapOf<Long, CalendarEventSnapshot>()
        var insertCount = 0
        var throwOnInsert = false
        var throwOnRead = false
        var beforeRead: (() -> Unit)? = null
        var insertStarted: CountDownLatch? = null
        var insertGate: CountDownLatch? = null
        override fun hasCalendarPermissions(): Boolean = true
        override fun writableCalendars(): List<CalendarDestination> = listOf(liveDestination)
        override fun readCalendar(destination: CalendarDestination): CalendarDestination? =
            writableCalendars().firstOrNull { it.calendarId == destination.calendarId }
        override fun insert(payload: SchedulePayload, destination: CalendarDestination): Long {
            insertCount++
            insertStarted?.countDown()
            insertGate?.await()
            if (throwOnInsert) throw IllegalStateException("ambiguous")
            events[1] = CalendarEventSnapshot(1, destination.calendarId, payload.title, payload.startMillis, payload.endMillis, payload.zoneId, null, null, false, false, null)
            return 1
        }
        override fun readEvent(eventId: Long): CalendarEventSnapshot? {
            beforeRead?.invoke()
            if (throwOnRead) throw IllegalStateException("unavailable")
            return events[eventId]
        }
        override fun deleteIfUnchanged(snapshot: CalendarEventSnapshot): Boolean? =
            if (events[snapshot.eventId] == snapshot) {
                events.remove(snapshot.eventId)
                true
            } else {
                false
            }
        override fun viewEventIntent(eventId: Long): Intent = Intent(Intent.ACTION_VIEW)
    }
}








