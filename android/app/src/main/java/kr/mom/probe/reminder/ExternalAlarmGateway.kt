package kr.mom.probe.reminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.util.Base64
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kr.mom.probe.agent.SchedulePayload
import kr.mom.probe.data.ProbeCrypto
import kr.mom.probe.data.ProbeRules
import org.json.JSONObject

data class ExternalAlarmHandler(
    val packageName: String,
    val activityName: String,
    val label: String,
) : Serializable

enum class ExternalAlarmState {
    REQUEST_DISPATCHED,
    UI_OPENED,
    NO_HANDLER,
    NEEDS_HANDLER,
    HANDLER_STALE,
    INCOMPATIBLE_DATE,
    FAILED,
}

enum class ExternalAlarmDispatchStatus {
    PREPARED,
    DISPATCHED,
    UNCERTAIN,
    FAILED,
}

data class ExternalAlarmResult(
    val state: ExternalAlarmState,
    val message: String,
    val intent: Intent? = null,
    val handler: ExternalAlarmHandler? = null,
    val dispatchKey: String? = null,
)

class ExternalAlarmGateway(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun handlers(): List<ExternalAlarmHandler> {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        return app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                ExternalAlarmHandler(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = info.loadLabel(app.packageManager)?.toString().orEmpty().ifBlank { activity.packageName },
                )
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label }
    }

    fun selectedHandler(): ExternalAlarmHandler? {
        val encoded = preferences.getString(KEY_HANDLER, null) ?: return null
        return runCatching {
            val json = JSONObject(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), KEY_HANDLER))
            ExternalAlarmHandler(json.getString("packageName"), json.getString("activityName"), json.getString("label"))
        }.getOrNull()
    }

    fun saveSelectedHandler(handler: ExternalAlarmHandler): Boolean {
        val raw = JSONObject()
            .put("packageName", handler.packageName)
            .put("activityName", handler.activityName)
            .put("label", handler.label)
            .toString()
        val encoded = Base64.encodeToString(ProbeCrypto().encrypt(raw, KEY_HANDLER), Base64.NO_WRAP)
        return preferences.edit().putString(KEY_HANDLER, encoded).commit()
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    fun prepare(requestId: String, payload: SchedulePayload, handler: ExternalAlarmHandler? = selectedHandler()): ExternalAlarmResult {
        val available = handlers()
        if (available.isEmpty()) {
            return ExternalAlarmResult(ExternalAlarmState.NO_HANDLER, "표준 알람 설정 화면을 열 수 있는 시계 앱을 찾지 못했어요.")
        }
        val chosen = handler ?: return ExternalAlarmResult(ExternalAlarmState.NEEDS_HANDLER, "처음 한 번 알람 설정 화면을 열 시계 앱을 골라주세요.")
        val live = available.firstOrNull { it.packageName == chosen.packageName && it.activityName == chosen.activityName }
            ?: return ExternalAlarmResult(ExternalAlarmState.HANDLER_STALE, "전에 고른 시계 앱을 찾지 못했어요. 다시 골라주세요.")
        val desired = Instant.ofEpochMilli(payload.startMillis)
        val currentDeviceZone = deviceZone()
        val wallCheck = nextWallTimeOccurrence(payload, nowMillis(), currentDeviceZone)
        if (wallCheck != desired) {
            return ExternalAlarmResult(
                ExternalAlarmState.INCOMPATIBLE_DATE,
                "이 시계 앱 연결은 날짜를 지정할 수 없어요. 이 날짜에는 모모 알림이나 캘린더 저장으로 진행할 수 있어요.",
            )
        }
        val key = dispatchKey(requestId, payload, live)
        val statuses = dispatchStatuses()
            ?: return ExternalAlarmResult(
                ExternalAlarmState.REQUEST_DISPATCHED,
                "시계 앱 알람 요청 기록을 읽지 못해 중복 방지를 위해 다시 보내지 않았어요. 앱 데이터를 확인해주세요.",
                handler = live,
                dispatchKey = key,
            )
        when (dispatchStatus(statuses, key)) {
            ExternalAlarmDispatchStatus.DISPATCHED ->
                return ExternalAlarmResult(ExternalAlarmState.REQUEST_DISPATCHED, "${live.label} 시계 앱에 같은 알람 설정을 이미 요청했어요. 저장 여부는 해당 앱에서 확인해주세요.", handler = live, dispatchKey = key)
            ExternalAlarmDispatchStatus.PREPARED, ExternalAlarmDispatchStatus.UNCERTAIN ->
                return ExternalAlarmResult(ExternalAlarmState.REQUEST_DISPATCHED, "${live.label} 시계 앱 알람 요청 결과를 확인 중이에요. 중복 방지를 위해 다시 보내지 않았어요.", handler = live, dispatchKey = key)
            ExternalAlarmDispatchStatus.FAILED, null -> Unit
        }
        val local = desired.atZone(currentDeviceZone)
        val source = desired.atZone(ZoneId.of(payload.zoneId))
        val zoneNote = if (currentDeviceZone.id != payload.zoneId) {
            " 요청 기준 ${source.monthValue}월 ${source.dayOfMonth}일 %02d:%02d(${payload.zoneId})를 휴대폰 시간대 ${local.monthValue}월 ${local.dayOfMonth}일 %02d:%02d로 열어요."
                .format(source.hour, source.minute, local.hour, local.minute)
        } else {
            ""
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, local.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, local.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, payload.title)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .setComponent(ComponentName(live.packageName, live.activityName))
        return ExternalAlarmResult(ExternalAlarmState.REQUEST_DISPATCHED, "${live.label} 시계 앱에 %02d:%02d 알람 설정을 요청할게요.$zoneNote".format(local.hour, local.minute), intent, live, key)
    }

    fun markOpened(handler: ExternalAlarmHandler): ExternalAlarmResult =
        ExternalAlarmResult(ExternalAlarmState.UI_OPENED, "${handler.label} 시계 앱에 알람 설정 화면을 열었어요. 저장은 해당 앱에서 마쳐주세요.")

    fun prepareDispatch(dispatchKey: String): Boolean = setDispatchStatus(dispatchKey, ExternalAlarmDispatchStatus.PREPARED)

    fun markDispatched(dispatchKey: String): Boolean = setDispatchStatus(dispatchKey, ExternalAlarmDispatchStatus.DISPATCHED)

    fun clearPreparedAfterLaunchFailure(dispatchKey: String): Boolean {
        val current = dispatchStatus(dispatchKey)
        if (current != ExternalAlarmDispatchStatus.PREPARED) return true
        val next = JSONObject((dispatchStatuses() ?: return false).toString())
        next.remove(dispatchKey)
        return preferences.edit().putString(KEY_DISPATCHED, next.toString()).commit()
    }

    private fun dispatchStatus(dispatchKey: String): ExternalAlarmDispatchStatus? =
        dispatchStatuses()?.let { dispatchStatus(it, dispatchKey) }

    private fun dispatchStatus(statuses: JSONObject, dispatchKey: String): ExternalAlarmDispatchStatus? =
        statuses.optString(dispatchKey).takeIf { it.isNotBlank() }?.let {
            runCatching { ExternalAlarmDispatchStatus.valueOf(it) }.getOrNull()
        }

    private fun setDispatchStatus(dispatchKey: String, status: ExternalAlarmDispatchStatus): Boolean {
        val next = dispatchStatuses() ?: return false
        next.put(dispatchKey, status.name)
        while (next.length() > MAX_DISPATCH_KEYS) next.remove(next.keys().next())
        return preferences.edit().putString(KEY_DISPATCHED, next.toString()).commit()
    }

    private fun dispatchStatuses(): JSONObject? =
        runCatching { JSONObject(preferences.getString(KEY_DISPATCHED, null) ?: "{}") }.getOrNull()

    private fun dispatchKey(requestId: String, payload: SchedulePayload, handler: ExternalAlarmHandler): String =
        ProbeRules.digest(listOf(requestId, handler.packageName, handler.activityName, payload.title, payload.startMillis.toString()).joinToString("\u0000"))

    companion object {
        private const val PREFS = "external-alarm-preferences"
        private const val KEY_HANDLER = "selected-set-alarm-handler"
        private const val KEY_DISPATCHED = "dispatched-set-alarm-keys"
        private const val MAX_DISPATCH_KEYS = 50
        fun reset(context: Context): Boolean = ExternalAlarmGateway(context).clear()
        internal fun isCompatibleWithStandardSetAlarm(payload: SchedulePayload, nowMillis: Long, deviceZone: ZoneId): Boolean =
            nextWallTimeOccurrence(payload, nowMillis, deviceZone) == Instant.ofEpochMilli(payload.startMillis)

        private fun nextWallTimeOccurrence(payload: SchedulePayload, nowMillis: Long, deviceZone: ZoneId): Instant {
            val desired = Instant.ofEpochMilli(payload.startMillis).atZone(deviceZone)
            val now = Instant.ofEpochMilli(nowMillis).atZone(deviceZone)
            val todayCandidate = LocalDateTime.of(now.toLocalDate(), desired.toLocalTime()).atZone(deviceZone).toInstant()
            return if (todayCandidate.isAfter(Instant.ofEpochMilli(nowMillis))) todayCandidate
            else LocalDateTime.of(now.toLocalDate().plusDays(1), desired.toLocalTime()).atZone(deviceZone).toInstant()
        }
    }
}
