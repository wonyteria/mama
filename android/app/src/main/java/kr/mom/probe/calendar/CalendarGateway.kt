package kr.mom.probe.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant
import java.time.ZoneId
import kr.mom.probe.agent.SchedulePayload

enum class CalendarSaveState {
    SAVED,
    NEEDS_PERMISSION,
    NEEDS_DESTINATION,
    STALE_DESTINATION,
    UNCERTAIN,
    FAILED,
}

enum class CalendarUndoState {
    UNDONE,
    CANNOT_VERIFY,
    EXTERNALLY_CHANGED,
    FAILED,
}

data class CalendarSaveResult(
    val state: CalendarSaveState,
    val record: CalendarCommandRecord? = null,
    val message: String,
)

data class CalendarUndoResult(
    val state: CalendarUndoState,
    val message: String,
    val viewIntent: Intent? = null,
)

interface CalendarBackend {
    fun hasCalendarPermissions(): Boolean
    fun writableCalendars(): List<CalendarDestination>
    fun readCalendar(destination: CalendarDestination): CalendarDestination?
    fun insert(payload: SchedulePayload, destination: CalendarDestination): Long
    fun readEvent(eventId: Long): CalendarEventSnapshot?
    fun deleteIfUnchanged(snapshot: CalendarEventSnapshot): Boolean?
    fun viewEventIntent(eventId: Long): Intent
}

class CalendarGateway(
    private val preferences: CalendarPreferencesPort,
    private val journal: CalendarJournal,
    private val backend: CalendarBackend,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val mutationAllowed: (Long?) -> Boolean = { true },
) {
    constructor(context: Context) : this(
        CalendarPreferences.get(context),
        CalendarCommandStore.get(context),
        ProviderCalendarBackend(context.applicationContext),
        System::currentTimeMillis,
        { true },
    )

    constructor(context: Context, mutationAllowed: (Long?) -> Boolean) : this(
        CalendarPreferences.get(context),
        CalendarCommandStore.get(context),
        ProviderCalendarBackend(context.applicationContext),
        System::currentTimeMillis,
        mutationAllowed,
    )

    fun hasCalendarPermissions(): Boolean = backend.hasCalendarPermissions()
    fun defaultDurationMinutes(): Int = preferences.defaultDurationMinutes()
    fun saveDefaultDurationMinutes(minutes: Int): Boolean = preferences.saveDefaultDurationMinutes(minutes)
    fun selectedCalendar(): CalendarDestination? = preferences.selectedCalendar()
    fun saveSelectedCalendar(destination: CalendarDestination): Boolean = preferences.saveSelectedCalendar(destination)
    fun writableCalendars(): List<CalendarDestination> = backend.writableCalendars()
    fun recoverableRecords(): List<CalendarCommandRecord> = try {
        journal.unresolved().filter { it.eventId != null }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveEvent(requestId: String, rawText: String, payload: SchedulePayload, expectedGeneration: Long? = null): CalendarSaveResult {
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = "앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
        }
        try {
            journal.find(requestId)?.let { existing ->
                if (existing.payload != payload) {
                    return CalendarSaveResult(CalendarSaveState.FAILED, existing, "같은 요청 ID에 다른 일정 내용이 들어와 새로 저장하지 않았어요.")
                }
                return resumeExisting(existing, expectedGeneration)
            }
        } catch (error: Exception) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = error.message ?: "캘린더 실행 기록을 읽지 못해 새 저장을 막았어요.")
        }

        if (payload.startMillis <= nowMillis()) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = "저장하려던 시작 시각이 이미 지났어요. 날짜와 시간을 다시 알려주세요.")
        }
        validateNewPayload(payload)?.let { return CalendarSaveResult(CalendarSaveState.FAILED, message = it) }
        if (!backend.hasCalendarPermissions()) {
            return CalendarSaveResult(CalendarSaveState.NEEDS_PERMISSION, message = "캘린더 권한이 필요해요. 허용한 뒤 저장할 캘린더를 고를게요.")
        }
        val destination = preferences.selectedCalendar()
            ?: return CalendarSaveResult(CalendarSaveState.NEEDS_DESTINATION, message = "처음 한 번 저장할 캘린더 계정을 골라주세요.")
        val liveDestination = backend.readCalendar(destination)
            ?: return CalendarSaveResult(CalendarSaveState.STALE_DESTINATION, message = "전에 고른 캘린더를 찾지 못했어요. 저장 위치를 다시 골라주세요.")
        if (liveDestination != destination) {
            return CalendarSaveResult(CalendarSaveState.STALE_DESTINATION, message = "캘린더 계정 정보가 달라졌어요. 저장 위치를 다시 골라주세요.")
        }
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = "앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
        }

        val claim = try {
            journal.claimIfAllowed(requestId, rawText, payload, destination, expectedGeneration) { mutationAllowed(expectedGeneration) }
        } catch (error: Exception) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = error.message ?: "캘린더 실행 기록을 읽지 못해 새 저장을 막았어요.")
        }
        val prepared = when (claim) {
            is CalendarClaimResult.Claimed -> claim.record
            is CalendarClaimResult.Existing -> return resumeExisting(claim.record, expectedGeneration)
            is CalendarClaimResult.Conflict -> return CalendarSaveResult(CalendarSaveState.FAILED, claim.record, "같은 요청 ID에 다른 일정 내용이 들어와 새로 저장하지 않았어요.")
            CalendarClaimResult.CommitBlocked -> return CalendarSaveResult(CalendarSaveState.FAILED, message = "앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
        }
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.FAILED, prepared, "앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
        }
        val eventId = try {
            backend.insert(payload, destination)
        } catch (error: Exception) {
            if (!mutationAllowed(expectedGeneration)) {
                return CalendarSaveResult(CalendarSaveState.UNCERTAIN, prepared, "캘린더 앱에 저장 요청을 보냈을 수 있지만 앱 설정이 바뀌어 확인 기록 저장을 중단했어요.")
            }
            return uncertain(prepared, "캘린더 앱에 저장 요청을 보냈지만 결과를 확인하지 못했어요. 중복 방지를 위해 같은 요청으로 다시 만들지 않았어요.", expectedGeneration)
        }
        val inserted = prepared.copy(status = CalendarCommandStatus.INSERTED, eventId = eventId)
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, inserted, "일정을 만들었을 수 있지만 앱 설정이 바뀌어 추가 확인을 중단했어요.")
        }
        try {
            if (!journal.updateIfAllowed(inserted) { mutationAllowed(expectedGeneration) }) {
                return CalendarSaveResult(CalendarSaveState.UNCERTAIN, inserted, "일정을 만들었을 수 있지만 앱 설정이 바뀌어 추가 확인을 중단했어요.")
            }
        } catch (_: Exception) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, inserted, "일정을 만들었을 수 있지만 실행 기록을 끝까지 저장하지 못했어요. 같은 요청으로 새 일정을 만들지 않았어요.")
        }
        return verifyInserted(inserted, expectedGeneration)
    }

    fun verifyExistingEvent(requestId: String, expectedGeneration: Long?): CalendarSaveResult {
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = "앱 설정이 바뀌어 일정 확인을 중단했어요. 다시 요청해주세요.")
        }
        val record = try {
            journal.find(requestId)
        } catch (error: Exception) {
            return CalendarSaveResult(CalendarSaveState.FAILED, message = error.message ?: "캘린더 실행 기록을 읽지 못해 확인을 중단했어요.")
        } ?: return CalendarSaveResult(CalendarSaveState.UNCERTAIN, message = "이 요청의 실행 기록을 찾지 못해 새로 저장하지 않았어요.")
        if (record.eventId == null) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, record, "저장된 일정 ID가 없어 새로 저장하지 않고 확인을 멈췄어요.")
        }
        return verifyInserted(record, expectedGeneration)
    }

    private fun resumeExisting(record: CalendarCommandRecord, expectedGeneration: Long?): CalendarSaveResult = when (record.status) {
        CalendarCommandStatus.SAVED -> CalendarSaveResult(CalendarSaveState.SAVED, record, "이미 같은 요청으로 저장한 일정이에요.")
        CalendarCommandStatus.INSERTED -> verifyInserted(record, expectedGeneration)
        CalendarCommandStatus.PREPARED -> CalendarSaveResult(CalendarSaveState.UNCERTAIN, record, "저장 요청 결과를 확인 중이에요. 중복 방지를 위해 같은 요청으로 새 일정을 만들지 않았어요.")
        CalendarCommandStatus.UNCERTAIN -> if (record.eventId != null) verifyInserted(record, expectedGeneration)
            else CalendarSaveResult(CalendarSaveState.UNCERTAIN, record, record.message ?: "일정 저장 여부를 확인 중이에요.")
        CalendarCommandStatus.FAILED -> CalendarSaveResult(CalendarSaveState.FAILED, record, record.message ?: "이 요청은 저장에 실패했어요.")
        CalendarCommandStatus.UNDONE -> CalendarSaveResult(CalendarSaveState.FAILED, record, "이미 취소한 요청이에요.")
    }

    private fun verifyInserted(record: CalendarCommandRecord, expectedGeneration: Long?): CalendarSaveResult {
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, record, "일정을 만들었을 수 있지만 앱 설정이 바뀌어 추가 확인을 중단했어요.")
        }
        val eventId = record.eventId ?: return uncertain(record, "저장 결과를 확인 중이에요. 중복 방지를 위해 새로 저장하지 않았어요.", expectedGeneration)
        val snapshot = try {
            backend.readEvent(eventId)
        } catch (_: Exception) {
            null
        } ?: return uncertain(record, "일정을 만들었을 수 있지만 지금은 다시 읽어 확인하지 못했어요.", expectedGeneration)
        if (!matches(record.payload, record.destination, snapshot)) {
            return uncertain(record, "일정을 만들었을 수 있지만 저장된 값이 요청과 달라 보여 확인이 필요해요.", expectedGeneration)
        }
        val saved = record.copy(status = CalendarCommandStatus.SAVED, eventId = eventId, snapshot = snapshot, message = null)
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, saved, "일정을 만들었을 수 있지만 앱 설정이 바뀌어 확인 기록 저장을 중단했어요.")
        }
        return try {
            if (journal.updateIfAllowed(saved) { mutationAllowed(expectedGeneration) }) {
                CalendarSaveResult(CalendarSaveState.SAVED, saved, "캘린더에 저장했어요.")
            } else {
                CalendarSaveResult(CalendarSaveState.UNCERTAIN, saved, "일정을 만들었을 수 있지만 앱 설정이 바뀌어 확인 기록 저장을 중단했어요.")
            }
        } catch (_: Exception) {
            CalendarSaveResult(CalendarSaveState.UNCERTAIN, saved, "일정을 확인했지만 실행 기록을 끝까지 저장하지 못했어요. 같은 요청으로 새 일정을 만들지 않았어요.")
        }
    }

    private fun uncertain(record: CalendarCommandRecord, message: String, expectedGeneration: Long?): CalendarSaveResult {
        val next = record.copy(status = CalendarCommandStatus.UNCERTAIN, message = message)
        if (!mutationAllowed(expectedGeneration)) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, next, message)
        }
        try {
            if (!journal.updateIfAllowed(next) { mutationAllowed(expectedGeneration) }) {
                return CalendarSaveResult(CalendarSaveState.UNCERTAIN, next, message)
            }
        } catch (_: Exception) {
            return CalendarSaveResult(CalendarSaveState.UNCERTAIN, next, message)
        }
        return CalendarSaveResult(CalendarSaveState.UNCERTAIN, next, message)
    }

    fun undo(record: CalendarCommandRecord, expectedGeneration: Long?): CalendarUndoResult {
        if (!mutationAllowed(expectedGeneration)) return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "앱 설정이 바뀌어 자동 취소를 중단했어요.")
        val snapshot = record.snapshot ?: return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "모모가 만든 일정인지 확인할 기록이 부족해요.")
        val current = try {
            backend.readEvent(snapshot.eventId)
        } catch (_: Exception) {
            return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "현재 일정을 확인하지 못해 자동으로 삭제하지 않았어요.", backend.viewEventIntent(snapshot.eventId))
        } ?: return CalendarUndoResult(CalendarUndoState.UNDONE, "방금 저장한 일정은 이미 없어졌어요.")
        if (current != snapshot) {
            return CalendarUndoResult(CalendarUndoState.EXTERNALLY_CHANGED, "일정이 바뀌어서 자동으로 삭제하지 않았어요. 일정 화면에서 확인해주세요.", backend.viewEventIntent(snapshot.eventId))
        }
        if (!mutationAllowed(expectedGeneration)) return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "앱 설정이 바뀌어 자동 취소를 중단했어요.", backend.viewEventIntent(snapshot.eventId))
        val deleted = backend.deleteIfUnchanged(snapshot)
        if (deleted == null) {
            return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "변경 보호 삭제를 보장할 수 없어 자동 삭제하지 않았어요. 일정 화면에서 확인해주세요.", backend.viewEventIntent(snapshot.eventId))
        }
        if (!deleted) return CalendarUndoResult(CalendarUndoState.FAILED, "방금 저장한 일정을 취소하지 못했어요. 일정 화면에서 확인해주세요.", backend.viewEventIntent(snapshot.eventId))
        if (!mutationAllowed(expectedGeneration)) return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "앱 설정이 바뀌어 취소 기록 저장을 중단했어요.", backend.viewEventIntent(snapshot.eventId))
        try {
            if (!journal.updateIfAllowed(record.copy(status = CalendarCommandStatus.UNDONE)) { mutationAllowed(expectedGeneration) }) {
                return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "앱 설정이 바뀌어 취소 기록 저장을 중단했어요.", backend.viewEventIntent(snapshot.eventId))
            }
        } catch (_: Exception) {
            return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "일정 삭제 뒤 취소 기록을 저장하지 못했어요. 일정 화면에서 확인해주세요.", backend.viewEventIntent(snapshot.eventId))
        }
        val afterDelete = try {
            backend.readEvent(snapshot.eventId)
        } catch (_: Exception) {
            return CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "삭제 요청 뒤 결과를 확인하지 못했어요.", backend.viewEventIntent(snapshot.eventId))
        }
        return if (afterDelete == null) {
            CalendarUndoResult(CalendarUndoState.UNDONE, "방금 저장한 일정을 취소했어요.")
        } else {
            CalendarUndoResult(CalendarUndoState.CANNOT_VERIFY, "삭제 요청 뒤에도 일정이 남아 있는지 확인이 필요해요.", backend.viewEventIntent(snapshot.eventId))
        }
    }

    fun viewIntent(record: CalendarCommandRecord): Intent? = record.eventId?.let { backend.viewEventIntent(it) }

    private fun matches(payload: SchedulePayload, destination: CalendarDestination, snapshot: CalendarEventSnapshot): Boolean =
        snapshot.calendarId == destination.calendarId &&
            snapshot.title == payload.title &&
            snapshot.startMillis == payload.startMillis &&
            snapshot.endMillis == payload.endMillis &&
            snapshot.timeZone == payload.zoneId

    private fun validateNewPayload(payload: SchedulePayload): String? {
        if (payload.endMillis <= payload.startMillis) return "끝나는 시간이 시작보다 빠르거나 같아요. 시작~종료를 다시 알려주세요."
        if (payload.endMillis - payload.startMillis > MAX_DURATION_MILLIS) return "0.8에서는 24시간 이내의 단일 일정만 저장할 수 있어요."
        if (!payload.explicitEnd) {
            val zone = ZoneId.of(payload.zoneId)
            val startDate = Instant.ofEpochMilli(payload.startMillis).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(payload.endMillis).atZone(zone).toLocalDate()
            if (endDate != startDate) return "선택한 기본 길이로 잡으면 다음 날에 끝나요. 끝나는 날짜와 시간을 확인해주세요."
        }
        return null
    }

    companion object {
        private const val MAX_DURATION_MILLIS = 24L * 60 * 60 * 1000
    }
}

class ProviderCalendarBackend(private val context: Context) : CalendarBackend {
    private val resolver: ContentResolver = context.contentResolver

    override fun hasCalendarPermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun writableCalendars(): List<CalendarDestination> {
        if (!hasCalendarPermissions()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null).use { cursor ->
            if (cursor == null) return emptyList()
            val result = mutableListOf<CalendarDestination>()
            while (cursor.moveToNext()) {
                val access = cursor.getInt(5)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                result += CalendarDestination(
                    calendarId = cursor.getLong(0),
                    displayName = cursor.getString(1).orEmpty(),
                    accountName = cursor.getString(2).orEmpty(),
                    accountType = cursor.getString(3).orEmpty(),
                    ownerAccount = cursor.getString(4).orEmpty(),
                )
            }
            return result
        }
    }

    override fun readCalendar(destination: CalendarDestination): CalendarDestination? =
        writableCalendars().firstOrNull {
            it.calendarId == destination.calendarId &&
                it.accountName == destination.accountName &&
                it.accountType == destination.accountType &&
                it.ownerAccount == destination.ownerAccount &&
                it.displayName == destination.displayName
        }

    override fun insert(payload: SchedulePayload, destination: CalendarDestination): Long {
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, destination.calendarId)
            put(CalendarContract.Events.TITLE, payload.title)
            put(CalendarContract.Events.DTSTART, payload.startMillis)
            put(CalendarContract.Events.DTEND, payload.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, payload.zoneId)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("CalendarProvider가 저장 URI를 돌려주지 않았어요.")
        return uri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("저장한 일정 ID를 확인하지 못했어요.")
    }

    override fun readEvent(eventId: Long): CalendarEventSnapshot? {
        val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.HAS_ALARM,
            CalendarContract.Events.HAS_ATTENDEE_DATA,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DELETED,
        )
        resolver.query(uri, projection, null, null, null).use { cursor ->
            if (cursor == null) throw IllegalStateException("Calendar event query failed")
            if (!cursor.moveToFirst()) return null
            if (calendarProviderDeletedFlagIsAbsent(cursor.getInt(11))) return null
            return CalendarEventSnapshot(
                eventId = cursor.getLong(0),
                calendarId = cursor.getLong(1),
                title = cursor.getString(2).orEmpty(),
                startMillis = cursor.getLong(3),
                endMillis = cursor.getLong(4),
                timeZone = cursor.getString(5).orEmpty(),
                description = cursor.getString(6),
                location = cursor.getString(7),
                hasAlarm = cursor.getInt(8) != 0,
                hasAttendeeData = cursor.getInt(9) != 0,
                rrule = cursor.getString(10),
                reminders = readReminders(eventId),
                attendees = readAttendees(eventId),
            )
        }
    }

    override fun deleteIfUnchanged(snapshot: CalendarEventSnapshot): Boolean? {
        val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, snapshot.eventId.toString())
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newAssertQuery(uri)
            .withSelection(unchangedSelection(snapshot), unchangedArgs(snapshot))
            .withExpectedCount(1)
            .build()
        operations += ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
            .withSelection("${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(snapshot.eventId.toString()))
            .withExpectedCount(snapshot.reminders.size)
            .build()
        snapshot.reminders.forEach { reminder ->
            operations += ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Reminders.EVENT_ID}=? AND ${CalendarContract.Reminders.MINUTES}=? AND ${CalendarContract.Reminders.METHOD}=?",
                    arrayOf(snapshot.eventId.toString(), reminder.minutes.toString(), reminder.method.toString()),
                )
                .withExpectedCount(1)
                .build()
        }
        operations += ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
            .withSelection("${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(snapshot.eventId.toString()))
            .withExpectedCount(snapshot.attendees.size)
            .build()
        snapshot.attendees.forEach { attendee ->
            operations += ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                .withSelection(attendeeSelection(attendee), attendeeArgs(snapshot.eventId, attendee))
                .withExpectedCount(1)
                .build()
        }
        operations += ContentProviderOperation.newDelete(uri).build()
        return try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun viewEventIntent(eventId: Long): Intent = Intent(Intent.ACTION_VIEW)
        .setData(Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString()))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun unchangedSelection(snapshot: CalendarEventSnapshot): String {
        val parts = mutableListOf(
            "${CalendarContract.Events._ID}=?",
            "${CalendarContract.Events.CALENDAR_ID}=?",
            "${CalendarContract.Events.TITLE}=?",
            "${CalendarContract.Events.DTSTART}=?",
            "${CalendarContract.Events.DTEND}=?",
            "${CalendarContract.Events.EVENT_TIMEZONE}=?",
            "${CalendarContract.Events.HAS_ALARM}=?",
            "${CalendarContract.Events.HAS_ATTENDEE_DATA}=?",
        )
        parts += if (snapshot.description == null) "${CalendarContract.Events.DESCRIPTION} IS NULL" else "${CalendarContract.Events.DESCRIPTION}=?"
        parts += if (snapshot.location == null) "${CalendarContract.Events.EVENT_LOCATION} IS NULL" else "${CalendarContract.Events.EVENT_LOCATION}=?"
        parts += if (snapshot.rrule == null) "${CalendarContract.Events.RRULE} IS NULL" else "${CalendarContract.Events.RRULE}=?"
        return parts.joinToString(" AND ")
    }

    private fun unchangedArgs(snapshot: CalendarEventSnapshot): Array<String> {
        val args = mutableListOf(
            snapshot.eventId.toString(),
            snapshot.calendarId.toString(),
            snapshot.title,
            snapshot.startMillis.toString(),
            snapshot.endMillis.toString(),
            snapshot.timeZone,
            if (snapshot.hasAlarm) "1" else "0",
            if (snapshot.hasAttendeeData) "1" else "0",
        )
        snapshot.description?.let(args::add)
        snapshot.location?.let(args::add)
        snapshot.rrule?.let(args::add)
        return args.toTypedArray()
    }

    private fun readReminders(eventId: Long): List<CalendarReminderSnapshot> {
        val projection = arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null,
        ).use { cursor ->
            if (cursor == null) throw IllegalStateException("Calendar reminders query failed")
            val result = mutableListOf<CalendarReminderSnapshot>()
            while (cursor.moveToNext()) result += CalendarReminderSnapshot(cursor.getInt(0), cursor.getInt(1))
            return result.sortedWith(compareBy<CalendarReminderSnapshot> { it.minutes }.thenBy { it.method })
        }
    }

    private fun readAttendees(eventId: Long): List<CalendarAttendeeSnapshot> {
        val projection = arrayOf(
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_TYPE,
            CalendarContract.Attendees.ATTENDEE_STATUS,
        )
        resolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            projection,
            "${CalendarContract.Attendees.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null,
        ).use { cursor ->
            if (cursor == null) throw IllegalStateException("Calendar attendees query failed")
            val result = mutableListOf<CalendarAttendeeSnapshot>()
            while (cursor.moveToNext()) {
                result += CalendarAttendeeSnapshot(
                    email = cursor.getString(0),
                    name = cursor.getString(1),
                    relationship = cursor.getInt(2),
                    type = cursor.getInt(3),
                    status = cursor.getInt(4),
                )
            }
            return result.sortedWith(
                compareBy<CalendarAttendeeSnapshot> { it.email.orEmpty() }
                    .thenBy { it.name.orEmpty() }
                    .thenBy { it.relationship }
                    .thenBy { it.type }
                    .thenBy { it.status },
            )
        }
    }

    private fun attendeeSelection(attendee: CalendarAttendeeSnapshot): String {
        val parts = mutableListOf(
            "${CalendarContract.Attendees.EVENT_ID}=?",
            "${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP}=?",
            "${CalendarContract.Attendees.ATTENDEE_TYPE}=?",
            "${CalendarContract.Attendees.ATTENDEE_STATUS}=?",
        )
        parts += if (attendee.email == null) "${CalendarContract.Attendees.ATTENDEE_EMAIL} IS NULL" else "${CalendarContract.Attendees.ATTENDEE_EMAIL}=?"
        parts += if (attendee.name == null) "${CalendarContract.Attendees.ATTENDEE_NAME} IS NULL" else "${CalendarContract.Attendees.ATTENDEE_NAME}=?"
        return parts.joinToString(" AND ")
    }

    private fun attendeeArgs(eventId: Long, attendee: CalendarAttendeeSnapshot): Array<String> {
        val args = mutableListOf(
            eventId.toString(),
            attendee.relationship.toString(),
            attendee.type.toString(),
            attendee.status.toString(),
        )
        attendee.email?.let(args::add)
        attendee.name?.let(args::add)
        return args.toTypedArray()
    }
}


internal fun calendarProviderDeletedFlagIsAbsent(deleted: Int): Boolean = deleted != 0
