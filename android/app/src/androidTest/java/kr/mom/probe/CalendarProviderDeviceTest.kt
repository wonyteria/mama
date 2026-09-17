package kr.mom.probe

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kr.mom.probe.agent.SchedulePayload
import kr.mom.probe.calendar.CalendarCommandStore
import kr.mom.probe.calendar.CalendarDestination
import kr.mom.probe.calendar.CalendarGateway
import kr.mom.probe.calendar.CalendarPreferences
import kr.mom.probe.calendar.CalendarSaveState
import kr.mom.probe.calendar.CalendarUndoState
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.reminder.ExternalAlarmGateway
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CalendarProvider device smoke that creates and cleans only its own temporary local calendar.
 */
@RunWith(AndroidJUnit4::class)
class CalendarProviderDeviceTest {
    @get:Rule
    val calendarPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val accountName = "momo_qa_${System.currentTimeMillis()}@local"
    private val calendarName = "MOMO_QA_${System.currentTimeMillis()}"

    @Test
    fun productionGatewaySavesReadsBackAndUndoesOwnedLocalCalendarEvent() {
        initializeQaRepository()
        var calendarId: Long? = null
        var eventId: Long? = null
        try {
            calendarId = insertTemporaryLocalCalendar()
            val destination = readOwnCalendar(calendarId)
            assertTrue(CalendarPreferences.get(context).saveSelectedCalendar(destination))

            val start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
            val payload = SchedulePayload(
                title = "MOMO_QA_PROVIDER_SMOKE_${calendarId}",
                startMillis = start,
                endMillis = start + 45 * 60_000L,
                zoneId = ZoneId.of("Asia/Seoul").id,
                explicitEnd = true,
                defaultDurationMinutes = 45,
            )
            val gateway = CalendarGateway(context)
            val saved = gateway.saveEvent(
                requestId = "device-calendar-${calendarId}",
                rawText = "QA provider smoke",
                payload = payload,
            )

            assertEquals(saved.message, CalendarSaveState.SAVED, saved.state)
            val record = requireNotNull(saved.record) { "CalendarGateway did not return a saved record." }
            eventId = requireNotNull(record.eventId) { "CalendarGateway saved without an event id." }
            assertEquals(calendarId, record.destination.calendarId)
            assertEquals(payload.title, record.snapshot?.title)
            assertEquals(payload.startMillis, record.snapshot?.startMillis)
            assertEquals(payload.endMillis, record.snapshot?.endMillis)

            val undone = gateway.undo(record, expectedGeneration = null)
            assertEquals(undone.message, CalendarUndoState.UNDONE, undone.state)
            eventId = null
        } finally {
            eventId?.let { deleteOwnedEvent(it) }
            calendarId?.let { deleteOwnedCalendar(it) }
        }
    }

    private fun initializeQaRepository() = runBlocking {
        check(context.packageName.endsWith(".qa")) {
            "CalendarProviderDeviceTest must target the debug QA application id, not release user data."
        }
        val repository = ProbeRepository.get(context)
        repository.deleteAll()
        CalendarCommandStore.reset(context)
        CalendarPreferences.reset(context)
        ExternalAlarmGateway.reset(context)
        assertTrue(repository.acceptConsent())
        assertTrue(repository.saveChild("QA 아이"))
        assertTrue(repository.saveSourceSelection(setOf("kr.mom.synthetic.school")))
        assertTrue(repository.deferSetup())
        AssistantTaskStore.reset(context)
    }

    private fun insertTemporaryLocalCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, calendarName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendarName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2E7D32.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(syncAdapterCalendarUri(CalendarContract.Calendars.CONTENT_URI), values)
        assertNotNull("CalendarProvider rejected temporary local QA calendar insert.", uri)
        return requireNotNull(uri?.lastPathSegment?.toLongOrNull()) { "Temporary local calendar id was missing." }
    }

    private fun readOwnCalendar(calendarId: Long): CalendarDestination {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        resolver.query(uri, projection, null, null, null).use { cursor ->
            assertNotNull("Temporary local calendar could not be read back.", cursor)
            val live = requireNotNull(cursor)
            assertTrue("Temporary local calendar row was missing.", live.moveToFirst())
            return CalendarDestination(
                calendarId = live.getLong(0),
                displayName = live.getString(1).orEmpty(),
                accountName = live.getString(2).orEmpty(),
                accountType = live.getString(3).orEmpty(),
                ownerAccount = live.getString(4).orEmpty(),
            )
        }
    }

    private fun deleteOwnedEvent(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    private fun deleteOwnedCalendar(calendarId: Long) {
        resolver.delete(
            syncAdapterCalendarUri(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)),
            null,
            null,
        )
        assertFalse("Temporary local QA calendar still exists after cleanup.", ownCalendarExists(calendarId))
    }

    private fun ownCalendarExists(calendarId: Long): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        resolver.query(uri, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.DELETED), null, null, null).use { cursor ->
            if (cursor?.moveToFirst() != true) return false
            return cursor.getInt(1) == 0
        }
    }
    private fun syncAdapterCalendarUri(base: Uri): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}


