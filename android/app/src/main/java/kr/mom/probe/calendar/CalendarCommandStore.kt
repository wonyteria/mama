package kr.mom.probe.calendar

import android.content.Context
import android.util.Base64
import java.io.IOException
import java.io.Serializable
import kr.mom.probe.agent.SchedulePayload
import kr.mom.probe.data.ProbeCrypto
import org.json.JSONArray
import org.json.JSONObject

enum class CalendarCommandStatus {
    PREPARED,
    INSERTED,
    SAVED,
    UNCERTAIN,
    FAILED,
    UNDONE,
}

data class CalendarEventSnapshot(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val description: String?,
    val location: String?,
    val hasAlarm: Boolean,
    val hasAttendeeData: Boolean,
    val rrule: String?,
    val reminders: List<CalendarReminderSnapshot> = emptyList(),
    val attendees: List<CalendarAttendeeSnapshot> = emptyList(),
) : Serializable

data class CalendarReminderSnapshot(
    val minutes: Int,
    val method: Int,
) : Serializable

data class CalendarAttendeeSnapshot(
    val email: String?,
    val name: String?,
    val relationship: Int,
    val type: Int,
    val status: Int,
) : Serializable

data class CalendarCommandRecord(
    val requestId: String,
    val rawText: String,
    val payload: SchedulePayload,
    val destination: CalendarDestination,
    val status: CalendarCommandStatus,
    val eventId: Long? = null,
    val snapshot: CalendarEventSnapshot? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val message: String? = null,
    val consentGeneration: Long? = null,
) : Serializable

sealed interface CalendarClaimResult {
    data class Claimed(val record: CalendarCommandRecord) : CalendarClaimResult
    data class Existing(val record: CalendarCommandRecord) : CalendarClaimResult
    data class Conflict(val record: CalendarCommandRecord) : CalendarClaimResult
    data object CommitBlocked : CalendarClaimResult
}

interface CalendarJournal {
    fun find(requestId: String): CalendarCommandRecord?
    fun claim(requestId: String, rawText: String, payload: SchedulePayload, destination: CalendarDestination, consentGeneration: Long? = null): CalendarClaimResult
    fun update(record: CalendarCommandRecord)
    fun unresolved(): List<CalendarCommandRecord> = emptyList()
    fun claimIfAllowed(
        requestId: String,
        rawText: String,
        payload: SchedulePayload,
        destination: CalendarDestination,
        consentGeneration: Long? = null,
        canCommit: () -> Boolean,
    ): CalendarClaimResult = if (canCommit()) claim(requestId, rawText, payload, destination, consentGeneration) else CalendarClaimResult.CommitBlocked
    fun updateIfAllowed(record: CalendarCommandRecord, canCommit: () -> Boolean): Boolean {
        if (!canCommit()) return false
        update(record)
        return true
    }
}

class CalendarCommandStore private constructor(context: Context) : CalendarJournal {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun records(): List<CalendarCommandRecord> = read()

    @Synchronized
    override fun unresolved(): List<CalendarCommandRecord> =
        read().filter { it.status in unresolvedStatuses }

    @Synchronized
    override fun find(requestId: String): CalendarCommandRecord? = read().firstOrNull { it.requestId == requestId }

    @Synchronized
    override fun claim(requestId: String, rawText: String, payload: SchedulePayload, destination: CalendarDestination, consentGeneration: Long?): CalendarClaimResult {
        find(requestId)?.let {
            return if (it.payload == payload) CalendarClaimResult.Existing(it) else CalendarClaimResult.Conflict(it)
        }
        val record = CalendarCommandRecord(requestId, rawText, payload, destination, CalendarCommandStatus.PREPARED, consentGeneration = consentGeneration)
        save(listOf(record) + read())
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
        read().firstOrNull { it.requestId == requestId }?.let {
            return if (it.payload == payload) CalendarClaimResult.Existing(it) else CalendarClaimResult.Conflict(it)
        }
        val record = CalendarCommandRecord(requestId, rawText, payload, destination, CalendarCommandStatus.PREPARED, consentGeneration = consentGeneration)
        save(listOf(record) + read())
        return CalendarClaimResult.Claimed(record)
    }

    @Synchronized
    override fun update(record: CalendarCommandRecord) {
        val records = read()
        val existing = records.firstOrNull { it.requestId == record.requestId }
        if (existing != null && shouldKeepCurrent(existing, record)) return
        val current = records.filterNot { it.requestId == record.requestId }
        save(listOf(record.copy(updatedAt = System.currentTimeMillis())) + current)
    }

    @Synchronized
    override fun updateIfAllowed(record: CalendarCommandRecord, canCommit: () -> Boolean): Boolean {
        if (!canCommit()) return false
        update(record)
        return true
    }

    @Synchronized
    fun clear(): Boolean = preferences.edit().clear().commit()

    private fun read(): List<CalendarCommandRecord> {
        val encoded = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val raw = ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), KEY_RECORDS)
            val array = JSONArray(raw)
            List(array.length()) { index -> decodeRecord(array.getJSONObject(index)) }
        } catch (error: Exception) {
            throw IllegalStateException("캘린더 실행 기록을 읽지 못해 새 저장을 막았어요. 앱 데이터를 확인해주세요.", error)
        }
    }

    private fun save(records: List<CalendarCommandRecord>) {
        val unresolved = records.filter { it.status in unresolvedStatuses }
        val terminal = records.filterNot { it.status in unresolvedStatuses }.take(MAX_TERMINAL_RECORDS)
        val retained = (unresolved + terminal).distinctBy { it.requestId }
        val array = JSONArray().apply { retained.forEach { put(encodeRecord(it)) } }
        val encoded = Base64.encodeToString(ProbeCrypto().encrypt(array.toString(), KEY_RECORDS), Base64.NO_WRAP)
        if (!preferences.edit().putString(KEY_RECORDS, encoded).commit()) throw IOException("캘린더 실행 기록을 저장하지 못했어요.")
    }

    private fun shouldKeepCurrent(current: CalendarCommandRecord, next: CalendarCommandRecord): Boolean {
        if (current.status == CalendarCommandStatus.UNDONE && next.status != CalendarCommandStatus.UNDONE) return true
        if (current.status == CalendarCommandStatus.SAVED && next.status != CalendarCommandStatus.UNDONE) return true
        if (current.eventId != null && next.eventId == null &&
            next.status in setOf(CalendarCommandStatus.PREPARED, CalendarCommandStatus.UNCERTAIN, CalendarCommandStatus.FAILED)
        ) return true
        return false
    }

    companion object {
        private const val PREFS = "calendar-command-journal"
        private const val KEY_RECORDS = "calendar-command-records"
        private const val MAX_TERMINAL_RECORDS = 50
        private val unresolvedStatuses = setOf(
            CalendarCommandStatus.PREPARED,
            CalendarCommandStatus.INSERTED,
            CalendarCommandStatus.UNCERTAIN,
        )
        @Volatile private var instance: CalendarCommandStore? = null
        fun get(context: Context): CalendarCommandStore = instance ?: synchronized(this) {
            instance ?: CalendarCommandStore(context).also { instance = it }
        }
        fun reset(context: Context): Boolean = get(context).clear()
    }
}

private fun encodeRecord(value: CalendarCommandRecord): JSONObject = JSONObject()
    .put("requestId", value.requestId)
    .put("rawText", value.rawText)
    .put("payload", encodePayload(value.payload))
    .put("destination", JSONObject(encodeDestination(value.destination)))
    .put("status", value.status.name)
    .put("eventId", value.eventId ?: JSONObject.NULL)
    .put("snapshot", value.snapshot?.let(::encodeSnapshot) ?: JSONObject.NULL)
    .put("createdAt", value.createdAt)
    .put("updatedAt", value.updatedAt)
    .put("message", value.message ?: JSONObject.NULL)
    .put("consentGeneration", value.consentGeneration ?: JSONObject.NULL)

private fun decodeRecord(json: JSONObject): CalendarCommandRecord = CalendarCommandRecord(
    requestId = json.getString("requestId"),
    rawText = json.getString("rawText"),
    payload = decodePayload(json.getJSONObject("payload")),
    destination = decodeDestination(json.getJSONObject("destination").toString()),
    status = runCatching { CalendarCommandStatus.valueOf(json.getString("status")) }.getOrDefault(CalendarCommandStatus.UNCERTAIN),
    eventId = json.optionalLong("eventId"),
    snapshot = if (json.isNull("snapshot")) null else decodeSnapshot(json.getJSONObject("snapshot")),
    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
    message = json.nullableString("message"),
    consentGeneration = json.optionalLong("consentGeneration"),
)

private fun encodePayload(value: SchedulePayload): JSONObject = JSONObject()
    .put("title", value.title)
    .put("startMillis", value.startMillis)
    .put("endMillis", value.endMillis)
    .put("zoneId", value.zoneId)
    .put("explicitEnd", value.explicitEnd)
    .put("defaultDurationMinutes", value.defaultDurationMinutes)

private fun decodePayload(json: JSONObject): SchedulePayload = SchedulePayload(
    title = json.getString("title"),
    startMillis = json.getLong("startMillis"),
    endMillis = json.getLong("endMillis"),
    zoneId = json.getString("zoneId"),
    explicitEnd = json.optBoolean("explicitEnd"),
    defaultDurationMinutes = json.optInt("defaultDurationMinutes", 60),
)

private fun encodeSnapshot(value: CalendarEventSnapshot): JSONObject = JSONObject()
    .put("eventId", value.eventId)
    .put("calendarId", value.calendarId)
    .put("title", value.title)
    .put("startMillis", value.startMillis)
    .put("endMillis", value.endMillis)
    .put("timeZone", value.timeZone)
    .put("description", value.description ?: JSONObject.NULL)
    .put("location", value.location ?: JSONObject.NULL)
    .put("hasAlarm", value.hasAlarm)
    .put("hasAttendeeData", value.hasAttendeeData)
    .put("rrule", value.rrule ?: JSONObject.NULL)
    .put("reminders", JSONArray(value.reminders.map { reminder ->
        JSONObject().put("minutes", reminder.minutes).put("method", reminder.method)
    }))
    .put("attendees", JSONArray(value.attendees.map { attendee ->
        JSONObject()
            .put("email", attendee.email ?: JSONObject.NULL)
            .put("name", attendee.name ?: JSONObject.NULL)
            .put("relationship", attendee.relationship)
            .put("type", attendee.type)
            .put("status", attendee.status)
    }))

private fun decodeSnapshot(json: JSONObject): CalendarEventSnapshot = CalendarEventSnapshot(
    eventId = json.getLong("eventId"),
    calendarId = json.getLong("calendarId"),
    title = json.getString("title"),
    startMillis = json.getLong("startMillis"),
    endMillis = json.getLong("endMillis"),
    timeZone = json.getString("timeZone"),
    description = json.nullableString("description"),
    location = json.nullableString("location"),
    hasAlarm = json.optBoolean("hasAlarm"),
    hasAttendeeData = json.optBoolean("hasAttendeeData"),
    rrule = json.nullableString("rrule"),
    reminders = json.optJSONArray("reminders")?.let { array ->
        List(array.length()) { index ->
            val reminder = array.getJSONObject(index)
            CalendarReminderSnapshot(reminder.getInt("minutes"), reminder.getInt("method"))
        }
    }.orEmpty(),
    attendees = json.optJSONArray("attendees")?.let { array ->
        List(array.length()) { index ->
            val attendee = array.getJSONObject(index)
            CalendarAttendeeSnapshot(
                email = attendee.nullableString("email"),
                name = attendee.nullableString("name"),
                relationship = attendee.getInt("relationship"),
                type = attendee.getInt("type"),
                status = attendee.getInt("status"),
            )
        }
    }.orEmpty(),
)

private fun JSONObject.optionalLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)


