package kr.mom.probe.agent

import android.util.Base64
import kr.mom.probe.calendar.CalendarAppHandler
import kr.mom.probe.calendar.CalendarAttendeeSnapshot
import kr.mom.probe.calendar.CalendarCommandRecord
import kr.mom.probe.calendar.CalendarCommandStatus
import kr.mom.probe.calendar.CalendarDestination
import kr.mom.probe.calendar.CalendarEventSnapshot
import kr.mom.probe.calendar.CalendarReminderSnapshot
import kr.mom.probe.reminder.ExternalAlarmHandler
import kr.mom.probe.data.ProbeCrypto
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSettings
import org.json.JSONArray
import org.json.JSONObject

internal data class PendingScheduleRequest(val requestId: String, val command: ScheduleCommand, val generation: Long)
internal data class PendingExternalCalendarRequest(val request: PendingScheduleRequest, val handlers: List<CalendarAppHandler>)
internal data class PendingClarification(val clarification: ScheduleClarification, val generation: Long)
internal data class PendingCalendarRecovery(val record: CalendarCommandRecord, val generation: Long)
internal data class PendingExternalAlarmOpening(val handler: ExternalAlarmHandler, val generation: Long)
internal data class PendingExternalCalendarOpening(val handler: CalendarAppHandler, val generation: Long)

internal interface ScheduleSavedStateCrypto {
    fun encrypt(value: String, context: String): ByteArray
    fun decrypt(bytes: ByteArray, context: String): String
}

internal object AndroidScheduleSavedStateCrypto : ScheduleSavedStateCrypto {
    override fun encrypt(value: String, context: String): ByteArray = ProbeCrypto().encrypt(value, context)
    override fun decrypt(bytes: ByteArray, context: String): String = ProbeCrypto().decrypt(bytes, context)
}

internal fun encryptScheduleSavedState(
    rawJson: String,
    context: String,
    crypto: ScheduleSavedStateCrypto = AndroidScheduleSavedStateCrypto,
): String =
    Base64.encodeToString(crypto.encrypt(rawJson, "agent-saved-state:$context"), Base64.NO_WRAP)

internal fun decryptScheduleSavedState(
    encoded: String,
    context: String,
    crypto: ScheduleSavedStateCrypto = AndroidScheduleSavedStateCrypto,
): String =
    crypto.decrypt(Base64.decode(encoded, Base64.NO_WRAP), "agent-saved-state:$context")

internal fun shouldQueueSchedulePermissionResult(ready: Boolean): Boolean = !ready

internal fun restoredScheduleGenerationMatches(ready: Boolean, allowed: Boolean, restoredGeneration: Long, currentGeneration: Long?): Boolean =
    ready && allowed && currentGeneration == restoredGeneration

internal fun scheduleConsentGeneration(settings: ProbeSettings): Long? {
    if (!settings.consent || !settings.onboardingDone || settings.consentVersion != ProbeRules.CONSENT_VERSION) return null
    val consentAt = settings.consentAt ?: return null
    val digest = ProbeRules.digest(listOf("schedule-consent", consentAt.toString(), settings.consentVersion).joinToString("\u0000"))
    return digest.take(15).toLong(16) + 1L
}

internal fun encodePendingScheduleRequest(value: PendingScheduleRequest): JSONObject = JSONObject()
    .put("requestId", value.requestId)
    .put("command", encodeScheduleCommand(value.command))
    .put("generation", value.generation)

internal fun decodePendingScheduleRequest(json: JSONObject): PendingScheduleRequest = PendingScheduleRequest(
    requestId = json.getString("requestId"),
    command = decodeScheduleCommand(json.getJSONObject("command")),
    generation = json.getLong("generation"),
)

internal fun encodePendingExternalCalendarRequest(value: PendingExternalCalendarRequest): JSONObject = JSONObject()
    .put("request", encodePendingScheduleRequest(value.request))
    .put("handlers", JSONArray(value.handlers.map(::encodeCalendarAppHandler)))

internal fun decodePendingExternalCalendarRequest(json: JSONObject): PendingExternalCalendarRequest = PendingExternalCalendarRequest(
    request = decodePendingScheduleRequest(json.getJSONObject("request")),
    handlers = json.getJSONArray("handlers").objects(::decodeCalendarAppHandler),
)

internal fun encodePendingClarification(value: PendingClarification): JSONObject = JSONObject()
    .put("clarification", encodeScheduleClarification(value.clarification))
    .put("generation", value.generation)

internal fun decodePendingClarification(json: JSONObject): PendingClarification = PendingClarification(
    clarification = decodeScheduleClarification(json.getJSONObject("clarification")),
    generation = json.getLong("generation"),
)

internal fun encodePendingCalendarRecovery(value: PendingCalendarRecovery): JSONObject = JSONObject()
    .put("record", encodeCalendarCommandRecord(value.record))
    .put("generation", value.generation)

internal fun decodePendingCalendarRecovery(json: JSONObject): PendingCalendarRecovery = PendingCalendarRecovery(
    record = decodeCalendarCommandRecord(json.getJSONObject("record")),
    generation = json.getLong("generation"),
)

internal fun encodePendingExternalAlarmOpening(value: PendingExternalAlarmOpening): JSONObject = JSONObject()
    .put("handler", encodeExternalAlarmHandler(value.handler))
    .put("generation", value.generation)

internal fun decodePendingExternalAlarmOpening(json: JSONObject): PendingExternalAlarmOpening = PendingExternalAlarmOpening(
    handler = decodeExternalAlarmHandler(json.getJSONObject("handler")),
    generation = json.getLong("generation"),
)

internal fun encodePendingExternalCalendarOpening(value: PendingExternalCalendarOpening): JSONObject = JSONObject()
    .put("handler", encodeCalendarAppHandler(value.handler))
    .put("generation", value.generation)

internal fun decodePendingExternalCalendarOpening(json: JSONObject): PendingExternalCalendarOpening = PendingExternalCalendarOpening(
    handler = decodeCalendarAppHandler(json.getJSONObject("handler")),
    generation = json.getLong("generation"),
)

internal fun encodeCalendarCommandRecord(value: CalendarCommandRecord): JSONObject = JSONObject()
    .put("requestId", value.requestId)
    .put("rawText", value.rawText)
    .put("payload", encodeSchedulePayload(value.payload))
    .put("destination", encodeCalendarDestination(value.destination))
    .put("status", value.status.name)
    .put("eventId", value.eventId ?: JSONObject.NULL)
    .put("snapshot", value.snapshot?.let(::encodeCalendarEventSnapshot) ?: JSONObject.NULL)
    .put("createdAt", value.createdAt)
    .put("updatedAt", value.updatedAt)
    .put("message", value.message ?: JSONObject.NULL)
    .put("consentGeneration", value.consentGeneration ?: JSONObject.NULL)

internal fun decodeCalendarCommandRecord(json: JSONObject): CalendarCommandRecord = CalendarCommandRecord(
    requestId = json.getString("requestId"),
    rawText = json.getString("rawText"),
    payload = decodeSchedulePayload(json.getJSONObject("payload")),
    destination = decodeCalendarDestination(json.getJSONObject("destination")),
    status = runCatching { CalendarCommandStatus.valueOf(json.getString("status")) }.getOrDefault(CalendarCommandStatus.UNCERTAIN),
    eventId = json.optionalPositiveLong("eventId"),
    snapshot = if (json.isNull("snapshot")) null else decodeCalendarEventSnapshot(json.getJSONObject("snapshot")),
    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
    message = json.nullableString("message"),
    consentGeneration = json.optionalPositiveLong("consentGeneration"),
)

internal fun encodeCalendarAppHandler(value: CalendarAppHandler): JSONObject = JSONObject()
    .put("packageName", value.packageName)
    .put("activityName", value.activityName)
    .put("label", value.label)

internal fun decodeCalendarAppHandler(json: JSONObject): CalendarAppHandler = CalendarAppHandler(
    packageName = json.getString("packageName"),
    activityName = json.getString("activityName"),
    label = json.getString("label"),
)

internal fun encodeExternalAlarmHandler(value: ExternalAlarmHandler): JSONObject = JSONObject()
    .put("packageName", value.packageName)
    .put("activityName", value.activityName)
    .put("label", value.label)

internal fun decodeExternalAlarmHandler(json: JSONObject): ExternalAlarmHandler = ExternalAlarmHandler(
    packageName = json.getString("packageName"),
    activityName = json.getString("activityName"),
    label = json.getString("label"),
)

private fun encodeScheduleCommand(value: ScheduleCommand): JSONObject = when (value) {
    is CalendarCreateCommand -> JSONObject()
        .put("type", "calendar")
        .put("rawText", value.rawText)
        .put("payload", encodeSchedulePayload(value.payload))
    is AlarmRequestCommand -> JSONObject()
        .put("type", "alarm")
        .put("rawText", value.rawText)
        .put("payload", encodeSchedulePayload(value.payload))
    is ScheduleClarification -> JSONObject()
        .put("type", "clarification")
        .put("value", encodeScheduleClarification(value))
    is ScheduleNoMutation -> JSONObject()
        .put("type", "none")
        .put("rawText", value.rawText)
        .put("reason", value.reason)
}

private fun decodeScheduleCommand(json: JSONObject): ScheduleCommand = when (json.getString("type")) {
    "calendar" -> CalendarCreateCommand(json.getString("rawText"), decodeSchedulePayload(json.getJSONObject("payload")))
    "alarm" -> AlarmRequestCommand(json.getString("rawText"), decodeSchedulePayload(json.getJSONObject("payload")))
    "clarification" -> decodeScheduleClarification(json.getJSONObject("value"))
    "none" -> ScheduleNoMutation(json.getString("rawText"), json.getString("reason"))
    else -> ScheduleNoMutation("", "bad saved state")
}

private fun encodeScheduleClarification(value: ScheduleClarification): JSONObject = JSONObject()
    .put("rawText", value.rawText)
    .put("kind", value.kind.name)
    .put("message", value.message)
    .put("amText", value.amText ?: JSONObject.NULL)
    .put("pmText", value.pmText ?: JSONObject.NULL)

private fun decodeScheduleClarification(json: JSONObject): ScheduleClarification = ScheduleClarification(
    rawText = json.getString("rawText"),
    kind = runCatching { ScheduleClarificationKind.valueOf(json.getString("kind")) }.getOrDefault(ScheduleClarificationKind.UNSUPPORTED),
    message = json.getString("message"),
    amText = json.nullableString("amText"),
    pmText = json.nullableString("pmText"),
)

private fun encodeSchedulePayload(value: SchedulePayload): JSONObject = JSONObject()
    .put("title", value.title)
    .put("startMillis", value.startMillis)
    .put("endMillis", value.endMillis)
    .put("zoneId", value.zoneId)
    .put("explicitEnd", value.explicitEnd)
    .put("defaultDurationMinutes", value.defaultDurationMinutes)

private fun decodeSchedulePayload(json: JSONObject): SchedulePayload = SchedulePayload(
    title = json.getString("title"),
    startMillis = json.getLong("startMillis"),
    endMillis = json.getLong("endMillis"),
    zoneId = json.getString("zoneId"),
    explicitEnd = json.optBoolean("explicitEnd"),
    defaultDurationMinutes = json.optInt("defaultDurationMinutes", ScheduleCommandParser.DEFAULT_DURATION_MINUTES),
)

private fun encodeCalendarDestination(value: CalendarDestination): JSONObject = JSONObject()
    .put("calendarId", value.calendarId)
    .put("displayName", value.displayName)
    .put("accountName", value.accountName)
    .put("accountType", value.accountType)
    .put("ownerAccount", value.ownerAccount)

private fun decodeCalendarDestination(json: JSONObject): CalendarDestination = CalendarDestination(
    calendarId = json.getLong("calendarId"),
    displayName = json.getString("displayName"),
    accountName = json.getString("accountName"),
    accountType = json.getString("accountType"),
    ownerAccount = json.optString("ownerAccount"),
)

private fun encodeCalendarEventSnapshot(value: CalendarEventSnapshot): JSONObject = JSONObject()
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
    .put("reminders", JSONArray(value.reminders.map { JSONObject().put("minutes", it.minutes).put("method", it.method) }))
    .put("attendees", JSONArray(value.attendees.map {
        JSONObject()
            .put("email", it.email ?: JSONObject.NULL)
            .put("name", it.name ?: JSONObject.NULL)
            .put("relationship", it.relationship)
            .put("type", it.type)
            .put("status", it.status)
    }))

private fun decodeCalendarEventSnapshot(json: JSONObject): CalendarEventSnapshot = CalendarEventSnapshot(
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
    reminders = json.optJSONArray("reminders")?.objects { item ->
        CalendarReminderSnapshot(item.getInt("minutes"), item.getInt("method"))
    }.orEmpty(),
    attendees = json.optJSONArray("attendees")?.objects { item ->
        CalendarAttendeeSnapshot(
            email = item.nullableString("email"),
            name = item.nullableString("name"),
            relationship = item.getInt("relationship"),
            type = item.getInt("type"),
            status = item.getInt("status"),
        )
    }.orEmpty(),
)

private fun <T> JSONArray.objects(decode: (JSONObject) -> T): List<T> =
    List(length()) { index -> decode(getJSONObject(index)) }

private fun JSONObject.optionalPositiveLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)
