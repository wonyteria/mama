package kr.mom.probe.agent

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.provider.CalendarContract
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kr.mom.probe.BuildConfig
import kr.mom.probe.MainActivity
import kr.mom.probe.calendar.CalendarCommandRecord
import kr.mom.probe.calendar.CalendarAppHandler
import kr.mom.probe.calendar.CalendarAppPreferences
import kr.mom.probe.calendar.CalendarGateway
import kr.mom.probe.calendar.CalendarSaveState
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.reminder.BriefingReminders
import kr.mom.probe.reminder.ExternalAlarmGateway
import kr.mom.probe.reminder.ExternalAlarmHandler
import kr.mom.probe.reminder.ExternalAlarmState
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.task.AssistantTasksActivity
import kr.mom.probe.task.TaskReminderScheduler
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScopeFactory
import kr.mom.probe.ui.AgentButton
import kr.mom.probe.ui.AgentCard
import kr.mom.probe.ui.BellMascot
import kr.mom.probe.ui.Clay
import kr.mom.probe.ui.ClayCard
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.sync.SourceStatusPresentation
import kr.mom.probe.sync.SourceSyncStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AgentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MomTheme { LocalAgent() } }
    }

    @Composable
    private fun LocalAgent() {
        val repository = remember { ProbeRepository.get(this) }
        val ready by repository.isReady.collectAsStateWithLifecycle()
        val settings by repository.settings.collectAsStateWithLifecycle()
        val records by repository.records.collectAsStateWithLifecycle()
        val sourceSnapshots by remember { SourceSyncStateStore.get(this) }.snapshotsFlow.collectAsStateWithLifecycle()
        val repositoryError by repository.lastError.collectAsStateWithLifecycle()
        val allowed = ready && repositoryError == null && settings.consent && settings.onboardingDone &&
            settings.consentVersion == ProbeRules.CONSENT_VERSION
        val taskStore = remember { AssistantTaskStore.get(this) }
        val tasks by taskStore.tasks.collectAsStateWithLifecycle()
        fun currentConsentGeneration(): Long? =
            if (repository.isReady.value && repository.lastError.value == null &&
                repository.settings.value.consent && repository.settings.value.onboardingDone &&
                repository.settings.value.consentVersion == ProbeRules.CONSENT_VERSION
            ) scheduleConsentGeneration(repository.settings.value) else null

        fun requestStillValid(request: PendingScheduleRequest): Boolean =
            currentConsentGeneration() == request.generation

        val calendarGateway = remember {
            CalendarGateway(this) { expected ->
                val current = currentConsentGeneration()
                current != null && (expected == null || current == expected)
            }
        }
        val calendarAppPreferences = remember { CalendarAppPreferences.get(this) }
        val alarmGateway = remember { ExternalAlarmGateway(this) }
        var scheduleRevision by remember { mutableIntStateOf(0) }
        val defaultDuration = remember(scheduleRevision) { calendarGateway.defaultDurationMinutes() }
        val engine = remember(defaultDuration) { LocalAgentEngine(defaultEventDurationMinutes = defaultDuration) }
        val messages = remember { mutableStateListOf(
            ChatMessage(false, "안녕하세요. 모모예요. 준비물, 제출할 것, 마감을 물어보거나 챙길 일을 맡겨주세요."),
        ) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val keyboard = LocalSoftwareKeyboardController.current
        fun <T> encryptedNullableSaver(context: String, encode: (T) -> JSONObject, decode: (JSONObject) -> T): Saver<T?, String> = Saver(
            save = { value -> value?.let { runCatching { encryptScheduleSavedState(encode(it).toString(), context) }.getOrNull() } },
            restore = { encoded -> runCatching { decode(JSONObject(decryptScheduleSavedState(encoded, context))) }.getOrNull() },
        )
        val pendingScheduleRequestSaver = remember { encryptedNullableSaver("pending-schedule-request", ::encodePendingScheduleRequest, ::decodePendingScheduleRequest) }
        val calendarRecordSaver = remember { encryptedNullableSaver("calendar-command-record", ::encodeCalendarCommandRecord, ::decodeCalendarCommandRecord) }
        val pendingCalendarRecoverySaver = remember { encryptedNullableSaver("pending-calendar-recovery", ::encodePendingCalendarRecovery, ::decodePendingCalendarRecovery) }
        val pendingExternalCalendarRequestSaver = remember { encryptedNullableSaver("pending-external-calendar-request", ::encodePendingExternalCalendarRequest, ::decodePendingExternalCalendarRequest) }
        val pendingClarificationSaver = remember { encryptedNullableSaver("pending-clarification", ::encodePendingClarification, ::decodePendingClarification) }
        val externalAlarmOpeningSaver = remember { encryptedNullableSaver("external-alarm-opening", ::encodePendingExternalAlarmOpening, ::decodePendingExternalAlarmOpening) }
        val calendarOpeningSaver = remember { encryptedNullableSaver("calendar-opening", ::encodePendingExternalCalendarOpening, ::decodePendingExternalCalendarOpening) }
        var draft by rememberSaveable { mutableStateOf("") }
        var loaded by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var undoTask by remember { mutableStateOf<AssistantTask?>(null) }
        var undoCalendar by rememberSaveable(stateSaver = calendarRecordSaver) { mutableStateOf<CalendarCommandRecord?>(null) }
        var undoCalendarGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
        var pendingCalendarRecovery by rememberSaveable(stateSaver = pendingCalendarRecoverySaver) { mutableStateOf<PendingCalendarRecovery?>(null) }
        var pendingCalendar by rememberSaveable(stateSaver = pendingScheduleRequestSaver) { mutableStateOf<PendingScheduleRequest?>(null) }
        var pendingCalendarSelection by rememberSaveable(stateSaver = pendingScheduleRequestSaver) { mutableStateOf<PendingScheduleRequest?>(null) }
        var pendingAlarmSelection by rememberSaveable(stateSaver = pendingScheduleRequestSaver) { mutableStateOf<PendingScheduleRequest?>(null) }
        var pendingAlarmFallback by rememberSaveable(stateSaver = pendingScheduleRequestSaver) { mutableStateOf<PendingScheduleRequest?>(null) }
        var pendingMomoAlarm by rememberSaveable(stateSaver = pendingScheduleRequestSaver) { mutableStateOf<PendingScheduleRequest?>(null) }
        var pendingExternalAlarmHandler by rememberSaveable(stateSaver = externalAlarmOpeningSaver) { mutableStateOf<PendingExternalAlarmOpening?>(null) }
        var pendingExternalCalendarHandler by rememberSaveable(stateSaver = calendarOpeningSaver) { mutableStateOf<PendingExternalCalendarOpening?>(null) }
        var pendingExternalCalendarSelection by rememberSaveable(stateSaver = pendingExternalCalendarRequestSaver) { mutableStateOf<PendingExternalCalendarRequest?>(null) }
        var pendingClarification by rememberSaveable(stateSaver = pendingClarificationSaver) { mutableStateOf<PendingClarification?>(null) }
        var pendingCalendarPermissionResult by rememberSaveable { mutableStateOf<Boolean?>(null) }
        var pendingNotificationPermissionResult by rememberSaveable { mutableStateOf<Boolean?>(null) }

        fun showResult(message: String) {
            messages += ChatMessage(false, message)
            error = null
        }

        lateinit var saveCalendarAction: (PendingScheduleRequest) -> Unit
        lateinit var openExternalCalendarInsertAction: (PendingScheduleRequest) -> Unit
        lateinit var saveMomoAlarmAction: (PendingScheduleRequest) -> Unit

        fun clearScheduleState() {
            undoTask = null
            undoCalendar = null
            undoCalendarGeneration = null
            pendingCalendarRecovery = null
            pendingCalendar = null
            pendingCalendarSelection = null
            pendingAlarmSelection = null
            pendingAlarmFallback = null
            pendingMomoAlarm = null
            pendingExternalAlarmHandler = null
            pendingExternalCalendarHandler = null
            pendingExternalCalendarSelection = null
            pendingClarification = null
            pendingCalendarPermissionResult = null
            pendingNotificationPermissionResult = null
        }

        fun clearScheduleStateFromOtherGeneration(generation: Long) {
            if (undoCalendarGeneration != null && undoCalendarGeneration != generation) {
                undoCalendar = null
                undoCalendarGeneration = null
            }
            if (pendingCalendarRecovery?.generation != generation) pendingCalendarRecovery = null
            if (pendingCalendar?.generation != generation) pendingCalendar = null
            if (pendingCalendarSelection?.generation != generation) pendingCalendarSelection = null
            if (pendingAlarmSelection?.generation != generation) pendingAlarmSelection = null
            if (pendingAlarmFallback?.generation != generation) pendingAlarmFallback = null
            if (pendingMomoAlarm?.generation != generation) pendingMomoAlarm = null
            if (pendingExternalAlarmHandler?.generation != generation) pendingExternalAlarmHandler = null
            if (pendingExternalCalendarHandler?.generation != generation) pendingExternalCalendarHandler = null
            if (pendingExternalCalendarSelection?.request?.generation != generation) pendingExternalCalendarSelection = null
            if (pendingClarification?.generation != generation) pendingClarification = null
        }

        fun handleCalendarPermissionResult(request: PendingScheduleRequest?, granted: Boolean) {
            if (request != null && !requestStillValid(request)) showResult("앱 설정이 바뀌어 캘린더 저장을 중단했어요. 다시 요청해주세요.")
            else if (granted && request != null) saveCalendarAction(request)
            else if (request != null) {
                showResult("캘린더 권한을 허용하지 않아 직접 저장하지 않았어요. 대신 캘린더 앱 입력 화면을 열 수 있어요.")
                openExternalCalendarInsertAction(request)
            }
        }

        fun handleNotificationPermissionResult(request: PendingScheduleRequest?, granted: Boolean) {
            if (request != null && !requestStillValid(request)) showResult("앱 설정이 바뀌어 모모 알림 저장을 중단했어요. 다시 요청해주세요.")
            else if (granted && request != null && TaskReminderScheduler.canDeliver(this@AgentActivity)) saveMomoAlarmAction(request)
            else if (request != null) showResult("알림 권한이나 채널이 꺼져 있어 모모 알림을 저장하지 않았어요.")
        }

        val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.READ_CALENDAR] == true && result[Manifest.permission.WRITE_CALENDAR] == true
            if (shouldQueueSchedulePermissionResult(ready)) {
                pendingCalendarPermissionResult = granted
            } else {
                val request = pendingCalendar
                pendingCalendar = null
                handleCalendarPermissionResult(request, granted)
            }
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (shouldQueueSchedulePermissionResult(ready)) {
                pendingNotificationPermissionResult = granted
            } else {
                val request = pendingMomoAlarm
                pendingMomoAlarm = null
                handleNotificationPermissionResult(request, granted)
            }
        }
        val externalAlarm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingExternalAlarmHandler?.let { opening -> if (currentConsentGeneration() == opening.generation) showResult(alarmGateway.markOpened(opening.handler).message) else showResult("앱 설정이 바뀌어 시계 앱 결과 표시를 중단했어요.") }
            pendingExternalAlarmHandler = null
        }
        val externalCalendar = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val opening = pendingExternalCalendarHandler
            val label = opening?.handler?.label ?: "캘린더 앱"
            if (opening == null || currentConsentGeneration() == opening.generation) {
                showResult("$label 일정 입력 화면을 열었어요. 저장은 캘린더 앱에서 마쳐주세요.")
            } else {
                showResult("앱 설정이 바뀌어 캘린더 앱 결과 표시를 중단했어요.")
            }
            pendingExternalCalendarHandler = null
        }

        suspend fun storeOperation(action: () -> Unit): Boolean {
            busy = true
            return try {
                withContext(Dispatchers.IO) { action() }
                error = null
                true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = if (failure is IllegalArgumentException || failure is IllegalStateException || failure is IOException) {
                    failure.message
                } else {
                    "부탁을 불러오거나 저장하지 못했어요. 다시 시도해주세요."
                }
                false
            } finally {
                busy = false
            }
        }

        fun payloadTime(payload: SchedulePayload): String {
            val zone = ZoneId.of(payload.zoneId)
            val formatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
            return "${Instant.ofEpochMilli(payload.startMillis).atZone(zone).format(formatter)}~${Instant.ofEpochMilli(payload.endMillis).atZone(zone).format(formatter)}"
        }

        fun payloadStartTime(payload: SchedulePayload): String {
            val zone = ZoneId.of(payload.zoneId)
            val formatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
            return Instant.ofEpochMilli(payload.startMillis).atZone(zone).format(formatter)
        }

        fun payloadValidationError(payload: SchedulePayload): String? {
            if (payload.startMillis <= System.currentTimeMillis()) return "시작 시각이 지나 다시 요청해주세요."
            if (payload.endMillis <= payload.startMillis) return "끝나는 시간이 시작보다 빠르거나 같아요. 시작~종료를 다시 알려주세요."
            if (payload.endMillis - payload.startMillis > 24L * 60 * 60 * 1000) return "0.8에서는 24시간 이내의 단일 일정만 저장할 수 있어요."
            if (!payload.explicitEnd) {
                val zone = ZoneId.of(payload.zoneId)
                val startDate = Instant.ofEpochMilli(payload.startMillis).atZone(zone).toLocalDate()
                val endDate = Instant.ofEpochMilli(payload.endMillis).atZone(zone).toLocalDate()
                if (endDate != startDate) return "선택한 기본 길이로 잡으면 다음 날에 끝나요. 끝나는 날짜와 시간을 확인해주세요."
            }
            return null
        }

        fun withDefaultDuration(request: PendingScheduleRequest, minutes: Int): PendingScheduleRequest {
            val payload = request.command.payload
            if (payload.explicitEnd) return request
            val updatedPayload = payload.copy(
                endMillis = payload.startMillis + minutes * 60_000L,
                defaultDurationMinutes = minutes,
            )
            val updatedCommand = when (val command = request.command) {
                is CalendarCreateCommand -> command.copy(payload = updatedPayload)
                is AlarmRequestCommand -> command.copy(payload = updatedPayload)
                is ScheduleClarification, is ScheduleNoMutation -> command
            }
            return request.copy(command = updatedCommand)
        }

        fun alarmRequestAsCalendar(request: PendingScheduleRequest): PendingScheduleRequest {
            val payload = request.command.payload
            val minutes = calendarGateway.defaultDurationMinutes()
            val calendarPayload = payload.copy(
                endMillis = payload.startMillis + minutes * 60_000L,
                explicitEnd = false,
                defaultDurationMinutes = minutes,
            )
            return request.copy(command = CalendarCreateCommand(request.command.rawText, calendarPayload))
        }

        fun calendarInsertHandlers(baseIntent: Intent): List<CalendarAppHandler> =
            packageManager.queryIntentActivities(baseIntent, 0).mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                CalendarAppHandler(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = info.loadLabel(packageManager)?.toString().orEmpty().ifBlank { activity.packageName },
                )
            }.distinctBy { it.packageName to it.activityName }.sortedBy { it.label }

        fun calendarInsertIntent(request: PendingScheduleRequest, handler: CalendarAppHandler): Intent {
            val payload = request.command.payload
            return Intent(Intent.ACTION_INSERT)
                .setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.item/event")
                .putExtra(CalendarContract.Events.TITLE, payload.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, payload.startMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, payload.endMillis)
                .setComponent(ComponentName(handler.packageName, handler.activityName))
        }

        fun launchExternalCalendarInsert(request: PendingScheduleRequest, handler: CalendarAppHandler) {
            val validationError = payloadValidationError(request.command.payload)
            if (!requestStillValid(request) || validationError != null) {
                showResult(validationError ?: "앱 설정이 바뀌어 일정 입력 화면을 열지 않았어요. 다시 요청해주세요.")
                return
            }
            val currentHandlers = calendarInsertHandlers(Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.item/event"))
            val live = currentHandlers.firstOrNull { it.packageName == handler.packageName && it.activityName == handler.activityName }
            if (live == null) {
                pendingExternalCalendarSelection = PendingExternalCalendarRequest(request, currentHandlers)
                showResult("전에 고른 캘린더 앱 입력 화면을 찾지 못했어요. 다시 골라주세요.")
                return
            }
            if (!calendarAppPreferences.saveSelectedHandler(live)) {
                showResult("캘린더 앱 선택을 저장하지 못했어요.")
                return
            }
            pendingExternalCalendarHandler = PendingExternalCalendarOpening(live, request.generation)
            try {
                externalCalendar.launch(calendarInsertIntent(request, live))
                showResult("${live.label} 일정 입력 화면을 열게요. 저장은 그 앱에서 마쳐주세요.")
            } catch (_: Exception) {
                pendingExternalCalendarHandler = null
                showResult("일정 입력 화면을 열지 못했어요. 저장은 하지 않았어요.")
            }
        }

        fun openExternalCalendarInsert(request: PendingScheduleRequest) {
            val validationError = payloadValidationError(request.command.payload)
            if (!requestStillValid(request) || validationError != null) {
                showResult(validationError ?: "앱 설정이 바뀌어 일정 입력 화면을 열지 않았어요. 다시 요청해주세요.")
                return
            }
            val payload = request.command.payload
            val intent = Intent(Intent.ACTION_INSERT)
                .setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.item/event")
                .putExtra(CalendarContract.Events.TITLE, payload.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, payload.startMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, payload.endMillis)
            val handlers = calendarInsertHandlers(intent)
            if (handlers.isEmpty()) {
                showResult("일정 입력 화면을 열 수 있는 캘린더 앱을 찾지 못했어요.")
                return
            }
            val selected = calendarAppPreferences.selectedHandler()
            val liveSelected = selected?.let { saved -> handlers.firstOrNull { it.packageName == saved.packageName && it.activityName == saved.activityName } }
            if (liveSelected != null) launchExternalCalendarInsert(request, liveSelected)
            else pendingExternalCalendarSelection = PendingExternalCalendarRequest(request, handlers)
        }

        fun saveCalendar(request: PendingScheduleRequest) {
            if (!requestStillValid(request)) {
                showResult("앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
                return
            }
            scope.launch {
                busy = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        calendarGateway.saveEvent(request.requestId, request.command.rawText, request.command.payload, request.generation)
                    }
                    when (result.state) {
                        CalendarSaveState.SAVED -> {
                            val record = result.record
                            undoCalendar = record
                            undoCalendarGeneration = request.generation
                            pendingCalendarRecovery = null
                            val calendarName = record?.destination?.displayName ?: "선택한 캘린더"
                            val account = record?.destination?.accountName?.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
                            showResult("$calendarName$account 에 ${payloadTime(request.command.payload)} ${request.command.payload.title} 일정을 저장했어요. 휴대폰에서 저장을 확인했어요.")
                        }
                        CalendarSaveState.NEEDS_PERMISSION -> {
                            pendingCalendar = request
                            calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }
                        CalendarSaveState.NEEDS_DESTINATION, CalendarSaveState.STALE_DESTINATION -> {
                            pendingCalendarSelection = request
                            showResult(result.message)
                        }
                        CalendarSaveState.UNCERTAIN -> {
                            pendingCalendarRecovery = result.record?.takeIf { it.eventId != null }?.let { PendingCalendarRecovery(it, request.generation) }
                            showResult(result.message)
                        }
                        CalendarSaveState.FAILED -> showResult(result.message)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "일정 저장을 마치지 못했어요. 캘린더 권한과 계정을 확인해주세요."
                } finally {
                    busy = false
                }
            }
        }

        fun launchExternalAlarm(request: PendingScheduleRequest, handler: ExternalAlarmHandler? = null) {
            if (!requestStillValid(request) || request.command.payload.startMillis <= System.currentTimeMillis()) {
                showResult("앱 설정이 바뀌었거나 알람 시각이 지나 시계 앱 요청을 보내지 않았어요. 다시 요청해주세요.")
                return
            }
            val resolvedHandler = resolveExternalAlarmHandlerForPrepare(handler) { alarmGateway.selectedHandler() }
            val result = alarmGateway.prepare(request.requestId, request.command.payload, resolvedHandler)
            when (result.state) {
                ExternalAlarmState.REQUEST_DISPATCHED -> {
                    if (result.intent == null || result.dispatchKey == null) {
                        showResult(result.message)
                        return
                    }
                    val selected = result.handler ?: resolvedHandler
                    if (selected == null) {
                        pendingAlarmSelection = request
                        showResult("처음 한 번 알람 설정 화면을 열 시계 앱을 골라주세요.")
                        return
                    }
                    try {
                        if (!requestStillValid(request) || request.command.payload.startMillis <= System.currentTimeMillis()) {
                            showResult("앱 설정이 바뀌었거나 알람 시각이 지나 시계 앱 요청을 보내지 않았어요. 다시 요청해주세요.")
                            return
                        }
                        if (!alarmGateway.prepareDispatch(result.dispatchKey)) {
                            showResult("중복 방지 기록을 저장하지 못해 시계 앱 요청을 보내지 않았어요.")
                            return
                        }
                        startActivity(result.intent)
                        if (alarmGateway.markDispatched(result.dispatchKey)) {
                            showResult(result.message.replace("요청할게요", "요청했어요"))
                        } else {
                            showResult("시계 앱에 알람 설정을 요청했지만 중복 방지 기록을 끝까지 저장하지 못했어요.")
                        }
                    } catch (_: Exception) {
                        alarmGateway.clearPreparedAfterLaunchFailure(result.dispatchKey)
                        showResult("시계 앱 알람 설정 화면을 열지 못했어요.")
                    }
                }
                ExternalAlarmState.NEEDS_HANDLER, ExternalAlarmState.HANDLER_STALE -> {
                    pendingAlarmSelection = request
                    showResult(result.message)
                }
                ExternalAlarmState.INCOMPATIBLE_DATE -> {
                    pendingAlarmFallback = request
                    showResult(result.message)
                }
                ExternalAlarmState.NO_HANDLER, ExternalAlarmState.FAILED, ExternalAlarmState.UI_OPENED -> showResult(result.message)
            }
        }

        fun saveMomoAlarm(request: PendingScheduleRequest) {
            if (!requestStillValid(request) || request.command.payload.startMillis <= System.currentTimeMillis()) {
                showResult("앱 설정이 바뀌었거나 알림 시각이 지나 모모 알림을 저장하지 않았어요. 다시 요청해주세요.")
                return
            }
            if (!TaskReminderScheduler.canDeliver(this@AgentActivity)) {
                pendingMomoAlarm = request
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            scope.launch {
                busy = true
                try {
                    val payload = request.command.payload
                    if (!requestStillValid(request) || payload.startMillis <= System.currentTimeMillis()) {
                        showResult("앱 설정이 바뀌었거나 알림 시각이 지나 모모 알림을 저장하지 않았어요. 다시 요청해주세요.")
                        return@launch
                    }
                    val saved = withContext(Dispatchers.IO) {
                        if (!requestStillValid(request) || payload.startMillis <= System.currentTimeMillis()) {
                            throw IllegalStateException("앱 설정이 바뀌었거나 알림 시각이 지나 모모 알림을 저장하지 않았어요. 다시 요청해주세요.")
                        }
                        taskStore.addTask(payload.title, dueAt = payload.startMillis, remindAt = payload.startMillis)
                    }
                    if (saved == null) showResult("이미 부탁 목록에 있는 알림이에요.")
                    else {
                        undoTask = saved
                        val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                        val alarmStyle = BriefingReminders.alarmMode(this@AgentActivity) &&
                            BriefingReminders.alarmPermissions(this@AgentActivity)
                        val delivery = if (alarmStyle) "모모 알람으로" else "일반 알림으로"
                        val timing = if (exact) "지정한 시각에" else "지정한 시각 무렵"
                        showResult("모모에 ${payloadStartTime(payload)} ${payload.title} 알림을 저장했어요. $delivery $timing 알려드리도록 예약했고, 실제 전달은 휴대폰 권한과 절전 상태를 따라요.")
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "모모 알림을 저장하지 못했어요."
                } finally {
                    busy = false
                }
            }
        }

        saveCalendarAction = { saveCalendar(it) }
        openExternalCalendarInsertAction = { openExternalCalendarInsert(it) }
        saveMomoAlarmAction = { saveMomoAlarm(it) }

        fun handleQuestion(question: String) {
            val reply = engine.answer(question, LocalAgentContext(
                settings.childName,
                records,
                tasks,
                kr.mom.probe.data.NoticeDecisionEngine.childProfile(settings),
                SourceScopeFactory.activeScopes(this@AgentActivity, SourceRunTrigger.MANUAL),
                SourceStatusPresentation.message(sourceSnapshots.values),
            ))
            messages += ChatMessage(false, reply.message)
            when (val schedule = reply.scheduleCommand) {
                is CalendarCreateCommand -> currentConsentGeneration()?.let { saveCalendar(PendingScheduleRequest(UUID.randomUUID().toString(), schedule, it)) }
                is AlarmRequestCommand -> currentConsentGeneration()?.let { launchExternalAlarm(PendingScheduleRequest(UUID.randomUUID().toString(), schedule, it)) }
                is ScheduleClarification -> if (schedule.kind == ScheduleClarificationKind.AM_PM) {
                    currentConsentGeneration()?.let { pendingClarification = PendingClarification(schedule, it) }
                }
                is ScheduleNoMutation, null -> Unit
            }
            reply.proposedTask?.let { task ->
                scope.launch {
                    busy = true
                    try {
                        val reminder = reply.proposedRemindAt?.takeIf { TaskReminderScheduler.canDeliver(this@AgentActivity) }
                        val saved = withContext(Dispatchers.IO) { taskStore.addTask(task, dueAt = reply.proposedDueAt, remindAt = reminder) }
                        error = null
                        if (saved == null) {
                            messages += ChatMessage(false, "이미 부탁 목록에 있는 내용이에요.")
                        } else {
                            undoTask = saved
                            val alarmText = if (saved.remindAt != null) " 요청한 시각 알림도 함께 저장했어요." else " 별도 알림은 만들지 않았어요."
                            messages += ChatMessage(false, "부탁 목록에 저장했어요.$alarmText")
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = if (failure is IllegalArgumentException || failure is IllegalStateException || failure is IOException) {
                            failure.message
                        } else {
                            "부탁을 저장하지 못했어요. 다시 시도해주세요."
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        }

        fun send() {
            val question = draft.trim()
            if (question.isBlank() || busy || !loaded) return
            messages += ChatMessage(true, question)
            draft = ""
            keyboard?.hide()
            handleQuestion(question)
        }

        LaunchedEffect(ready, allowed) {
            if (!ready) return@LaunchedEffect
            loaded = false
            if (allowed) {
                val generation = currentConsentGeneration()
                if (generation == null) {
                    clearScheduleState()
                    return@LaunchedEffect
                }
                clearScheduleStateFromOtherGeneration(generation)
                loaded = storeOperation { taskStore.load() }
                if (loaded && pendingCalendarRecovery == null && undoCalendar == null) {
                    pendingCalendarRecovery = withContext(Dispatchers.IO) {
                        calendarGateway.recoverableRecords().firstOrNull { it.consentGeneration == generation }
                    }?.let { PendingCalendarRecovery(it, generation) }
                }
                pendingCalendarPermissionResult?.let { granted ->
                    val request = pendingCalendar
                    pendingCalendarPermissionResult = null
                    pendingCalendar = null
                    handleCalendarPermissionResult(request, granted)
                }
                pendingNotificationPermissionResult?.let { granted ->
                    val request = pendingMomoAlarm
                    pendingNotificationPermissionResult = null
                    pendingMomoAlarm = null
                    handleNotificationPermissionResult(request, granted)
                }
            } else {
                clearScheduleState()
            }
        }
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        Column(
            Modifier.fillMaxSize().background(Clay.Background).safeDrawingPadding().imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { finish() }) { Text("닫기") }
                Text("모모", style = MaterialTheme.typography.titleMedium, color = Clay.Green)
                TextButton(onClick = { startActivity(Intent(this@AgentActivity, AssistantTasksActivity::class.java)) }) { Text("부탁 목록") }
            }

            AgentCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BellMascot(Modifier.size(width = 54.dp, height = 64.dp))
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("엄마 곁의 모모", style = MaterialTheme.typography.titleLarge)
                        Text("챙길 일과 약속을 맡겨주세요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !allowed -> ClayCard(Modifier.fillMaxWidth()) {
                    Text("처음 설정을 마쳐야 모모가 저장된 알림과 부탁을 안전하게 살펴볼 수 있어요.")
                    TextButton(onClick = {
                        startActivity(Intent(this@AgentActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }) { Text("설정하러 가기") }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages) { message -> ChatBubble(message) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    undoCalendar?.let { saved ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("방금 저장한 일정", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                            TextButton(enabled = !busy, onClick = {
                                calendarGateway.viewIntent(saved)?.let { startActivity(it) }
                            }) { Text("일정 보기") }
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val generation = undoCalendarGeneration
                                        if (generation == null) {
                                            showResult("이전 실행 기준을 확인하지 못해 자동으로 삭제하지 않았어요. 일정 화면에서 확인해주세요.")
                                            return@launch
                                        }
                                        val result = withContext(Dispatchers.IO) { calendarGateway.undo(saved, generation) }
                                        showResult(result.message)
                                        result.viewIntent?.let { if (result.state != kr.mom.probe.calendar.CalendarUndoState.UNDONE) startActivity(it) }
                                        undoCalendar = null
                                        undoCalendarGeneration = null
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text("저장 취소") }
                        }
                    }
                    pendingCalendarRecovery?.let { recovery ->
                        val record = recovery.record
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("확인 중인 일정", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                            TextButton(enabled = !busy, onClick = {
                                calendarGateway.viewIntent(record)?.let { startActivity(it) }
                            }) { Text("일정 보기") }
                            TextButton(enabled = !busy, onClick = {
                                val generation = currentConsentGeneration()
                                if (generation == null || generation != recovery.generation) {
                                    showResult("앱 설정이 바뀌어 일정 확인을 중단했어요. 다시 요청해주세요.")
                                } else {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val result = withContext(Dispatchers.IO) { calendarGateway.verifyExistingEvent(record.requestId, generation) }
                                            when (result.state) {
                                                CalendarSaveState.SAVED -> {
                                                    val savedRecord = result.record
                                                    undoCalendar = savedRecord
                                                    undoCalendarGeneration = generation
                                                    pendingCalendarRecovery = null
                                                    val payload = savedRecord?.payload ?: record.payload
                                                    val calendarName = savedRecord?.destination?.displayName ?: "선택한 캘린더"
                                                    val account = savedRecord?.destination?.accountName?.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
                                                    showResult("$calendarName$account 에 ${payloadTime(payload)} ${payload.title} 일정을 확인했어요. 휴대폰에서 저장을 확인했어요.")
                                                }
                                                CalendarSaveState.UNCERTAIN -> {
                                                    pendingCalendarRecovery = result.record?.takeIf { it.eventId != null }?.let { PendingCalendarRecovery(it, generation) }
                                                    showResult(result.message)
                                                }
                                                CalendarSaveState.NEEDS_PERMISSION,
                                                CalendarSaveState.NEEDS_DESTINATION,
                                                CalendarSaveState.STALE_DESTINATION,
                                                CalendarSaveState.FAILED -> showResult(result.message)
                                            }
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }) { Text("다시 확인") }
                        }
                    }
                    undoTask?.let { saved ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("방금 저장한 부탁", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    if (storeOperation { taskStore.delete(saved.id) }) {
                                        messages += ChatMessage(false, "방금 저장한 부탁을 취소했어요.")
                                        undoTask = null
                                    }
                                }
                            }) { Text("취소") }
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= MAX_QUESTION_LENGTH) draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("모모에게 묻기") },
                        placeholder = { Text("예: 내일 뭐 챙겨야 해?") },
                        minLines = 1,
                        maxLines = 3,
                        enabled = loaded && !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        supportingText = { Text("${draft.length}/$MAX_QUESTION_LENGTH") },
                    )
                    AgentButton(
                        text = "모모에게 보내기",
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        enabled = loaded && !busy && draft.isNotBlank(),
                        onClick = ::send,
                    )
                }
            }
        }

        pendingCalendarSelection?.let { request ->
            val calendars = remember(scheduleRevision) { calendarGateway.writableCalendars() }
            AlertDialog(
                onDismissRequest = { pendingCalendarSelection = null },
                title = { Text("저장할 캘린더") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("쓰기 가능한 캘린더 계정을 직접 골라주세요. 끝 시간이 없으면 기본 ${calendarGateway.defaultDurationMinutes()}분으로 저장해요.")
                        if (calendars.isEmpty()) Text("쓰기 가능한 캘린더를 찾지 못했어요.")
                        calendars.forEach { destination ->
                            OutlinedButton(onClick = {
                                if (!requestStillValid(request)) {
                                    showResult("앱 설정이 바뀌어 일정 저장을 중단했어요. 다시 요청해주세요.")
                                } else if (calendarGateway.saveSelectedCalendar(destination)) {
                                    pendingCalendarSelection = null
                                    scheduleRevision++
                                    saveCalendar(request)
                                } else showResult("캘린더 선택을 저장하지 못했어요.")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${destination.displayName}\n${destination.accountName}")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 60, 90).forEach { minutes ->
                                TextButton(onClick = {
                                    if (!requestStillValid(request)) {
                                        showResult("앱 설정이 바뀌어 기본 기간 선택을 중단했어요. 다시 요청해주세요.")
                                    } else if (calendarGateway.saveDefaultDurationMinutes(minutes)) {
                                        scheduleRevision++
                                        pendingCalendarSelection = withDefaultDuration(request, minutes)
                                    }
                                }) { Text("${minutes}분") }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingCalendarSelection = null
                        openExternalCalendarInsert(request)
                    }) { Text("입력 화면 열기") }
                },
                dismissButton = { TextButton(onClick = { pendingCalendarSelection = null }) { Text("취소") } },
            )
        }
        pendingExternalCalendarSelection?.let { selection ->
            AlertDialog(
                onDismissRequest = { pendingExternalCalendarSelection = null },
                title = { Text("입력 화면을 열 앱") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("저장 완료 여부는 모모가 확인할 수 없어요. 앱에서 직접 저장을 마쳐주세요.")
                        selection.handlers.forEach { handler ->
                            OutlinedButton(onClick = {
                                pendingExternalCalendarSelection = null
                                if (requestStillValid(selection.request)) launchExternalCalendarInsert(selection.request, handler)
                                else showResult("앱 설정이 바뀌어 일정 입력 화면을 열지 않았어요. 다시 요청해주세요.")
                            }, modifier = Modifier.fillMaxWidth()) { Text(handler.label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pendingExternalCalendarSelection = null }) { Text("닫기") } },
            )
        }
        pendingClarification?.let { pending ->
            val clarification = pending.clarification
            AlertDialog(
                onDismissRequest = { pendingClarification = null },
                title = { Text("시간 확인") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(clarification.message)
                        clarification.amText?.let { resolved ->
                            OutlinedButton(onClick = {
                                pendingClarification = null
                                if (currentConsentGeneration() == pending.generation) {
                                    messages += ChatMessage(true, resolved)
                                    handleQuestion(resolved)
                                } else {
                                    showResult("앱 설정이 바뀌어 시간 선택을 중단했어요. 다시 요청해주세요.")
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("오전") }
                        }
                        clarification.pmText?.let { resolved ->
                            OutlinedButton(onClick = {
                                pendingClarification = null
                                if (currentConsentGeneration() == pending.generation) {
                                    messages += ChatMessage(true, resolved)
                                    handleQuestion(resolved)
                                } else {
                                    showResult("앱 설정이 바뀌어 시간 선택을 중단했어요. 다시 요청해주세요.")
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("오후") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pendingClarification = null }) { Text("닫기") } },
            )
        }
        pendingAlarmSelection?.let { request ->
            val handlers = remember(scheduleRevision) { alarmGateway.handlers() }
            AlertDialog(
                onDismissRequest = { pendingAlarmSelection = null },
                title = { Text("시계 앱 선택") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("표준 알람 설정 화면을 지원하는 앱만 보여요. 이 연결은 날짜 저장을 확인하지 못해요.")
                        handlers.forEach { handler ->
                            OutlinedButton(onClick = {
                                if (!requestStillValid(request)) {
                                    showResult("앱 설정이 바뀌어 시계 앱 요청을 보내지 않았어요. 다시 요청해주세요.")
                                } else if (alarmGateway.saveSelectedHandler(handler)) {
                                    pendingAlarmSelection = null
                                    scheduleRevision++
                                    launchExternalAlarm(request, handler)
                                } else showResult("시계 앱 선택을 저장하지 못했어요.")
                            }, modifier = Modifier.fillMaxWidth()) { Text(handler.label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pendingAlarmSelection = null }) { Text("닫기") } },
            )
        }
        pendingAlarmFallback?.let { request ->
            AlertDialog(
                onDismissRequest = { pendingAlarmFallback = null },
                title = { Text("날짜 있는 알림") },
                text = { Text("시계 앱 연결은 날짜를 보낼 수 없어요. 이 날짜에는 모모가 직접 알려주거나 캘린더에 일정으로 저장할 수 있어요.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingAlarmFallback = null
                        if (requestStillValid(request)) saveMomoAlarm(request)
                        else showResult("앱 설정이 바뀌어 모모 알림 저장을 중단했어요. 다시 요청해주세요.")
                    }) { Text("모모에서 알리기") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingAlarmFallback = null
                        if (requestStillValid(request)) saveCalendar(alarmRequestAsCalendar(request))
                        else showResult("앱 설정이 바뀌어 캘린더 저장을 중단했어요. 다시 요청해주세요.")
                    }) { Text("캘린더에 저장") }
                },
            )
        }

    }

    @Composable
    private fun ChatBubble(message: ChatMessage) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
        ) {
            Text(
                text = message.text,
                modifier = Modifier.fillMaxWidth(.88f).background(
                    if (message.fromUser) Clay.Green else Clay.Paper,
                    RoundedCornerShape(22.dp),
                ).padding(horizontal = 16.dp, vertical = 13.dp),
                color = if (message.fromUser) Color.White else Clay.Ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    private data class ChatMessage(val fromUser: Boolean, val text: String)

    private val ScheduleCommand.payload: SchedulePayload
        get() = when (this) {
            is CalendarCreateCommand -> payload
            is AlarmRequestCommand -> payload
            is ScheduleClarification, is ScheduleNoMutation -> error("No schedule payload")
        }

    companion object {
        private const val MAX_QUESTION_LENGTH = 500
    }
}











internal fun resolveExternalAlarmHandlerForPrepare(
    explicit: ExternalAlarmHandler?,
    selectedHandler: () -> ExternalAlarmHandler?,
): ExternalAlarmHandler? = explicit ?: selectedHandler()
