package kr.mom.probe.reminder

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kr.mom.probe.BuildConfig
import kr.mom.probe.MainActivity
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.task.TaskAlarmSnoozeResult
import kr.mom.probe.task.TaskReminderScheduler
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.ui.displayTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskAlarmActivity : ComponentActivity() {
    private var speech: TextToSpeech? = null
    private var speechReady by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private val pendingAskMomo = PendingUnlockAction()
    private var resumed = false
    private var hasWindowFocus = false
    private var unlockingForAskMomo = false
    private val taskId: String? get() = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID)
    private val occurrenceId: String? get() = intent.getStringExtra(TaskReminderScheduler.EXTRA_OCCURRENCE_ID)
    private val notificationId: Int get() = intent.getIntExtra(TaskReminderScheduler.EXTRA_NOTIFICATION_ID, -1)
    private val scheduledAt: Long get() = intent.getLongExtra(TaskReminderScheduler.EXTRA_SCHEDULED_AT, System.currentTimeMillis())

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshUnlocked()
        continuePendingAskMomo()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (!unlockingForAskMomo) pendingAskMomo.cancel()
    }

    override fun onUserLeaveHint() {
        unlockingForAskMomo = false
        pendingAskMomo.cancel()
        super.onUserLeaveHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocus = hasFocus
        if (hasFocus) {
            refreshUnlocked()
            continuePendingAskMomo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("ringing", false)) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        speech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speechReady = (speech?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
            }
        }
        setContent {
            MomTheme {
                val repository = remember { ProbeRepository.get(this@TaskAlarmActivity) }
                val ready by repository.isReady.collectAsState()
                val settings by repository.settings.collectAsState()
                val error by repository.lastError.collectAsState()
                val allowed = ready && settings.consent && settings.consentVersion == ProbeRules.CONSENT_VERSION &&
                    settings.onboardingDone && error == null
                val store = remember { AssistantTaskStore.get(this@TaskAlarmActivity) }
                val tasks by store.tasks.collectAsState()
                var taskLoading by remember { mutableStateOf(true) }
                var taskError by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                var snoozedUntil by remember { mutableStateOf<Long?>(null) }
                val scope = rememberCoroutineScope()
                val id = taskId
                val occurrence = occurrenceId

                LaunchedEffect(allowed, unlocked, id, occurrence) {
                    if (allowed && unlocked && id != null && occurrence != null) {
                        taskLoading = true
                        try {
                            withContext(Dispatchers.IO) { store.load() }
                            taskError = false
                        } catch (_: Exception) {
                            taskError = true
                        } finally {
                            taskLoading = false
                        }
                    } else {
                        taskLoading = false
                    }
                }

                val activeTask = if (unlocked && allowed && !taskError && id != null && occurrence != null) {
                    tasks.firstOrNull { it.id == id && it.activeAlarmOccurrenceId == occurrence && !it.completed && !it.suspended }
                } else {
                    null
                }
                val screenState = snoozedUntil?.let {
                    AlarmContentState(
                        title = "모모의 부탁",
                        dateText = formatAlarmDate(it),
                        scheduledTimeText = formatAlarmTime(it),
                        actionTitle = "다시 알림을 예약했어요",
                        actionSummary = "${displayTime(it)} 무렵 다시 알려드릴게요.",
                        locked = !unlocked,
                        snoozeEnabled = false,
                        statusText = status,
                        timeLabel = "다음 알림",
                    )
                } ?: taskAlarmState(
                    unlocked = unlocked,
                    allowed = allowed,
                    loading = taskLoading,
                    taskError = taskError,
                    task = activeTask,
                    scheduledAt = activeTask?.activeAlarmScheduledAt ?: scheduledAt,
                    speechReady = speechReady,
                    status = status,
                )
                AlarmContent(
                    state = screenState.copy(snoozeEnabled = screenState.snoozeEnabled && !busy),
                    onStopSound = {
                        pendingAskMomo.cancel()
                        stopOwnNotification()
                        finish()
                    },
                    onSnoozeTenMinutes = {
                        if (!busy && id != null && occurrence != null) {
                            pendingAskMomo.cancel()
                            busy = true
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        store.load()
                                        store.snoozeAlarmOccurrence(id, occurrence, minutes = 10)
                                    }
                                }.getOrNull()
                                when (result) {
                                    is TaskAlarmSnoozeResult.Scheduled -> {
                                        stopOwnNotification()
                                        snoozedUntil = result.nextAt
                                        status = "${displayTime(result.nextAt)} 무렵 다시 알려드릴게요."
                                    }
                                    TaskAlarmSnoozeResult.LimitReached -> status = "이 알림은 다시 알림 한도에 닿았어요."
                                    TaskAlarmSnoozeResult.Stale, TaskAlarmSnoozeResult.Failed, null -> status = "다시 알리지 못했어요. 앱에서 부탁을 확인해주세요."
                                }
                                busy = false
                            }
                        }
                    },
                    onShowDetails = {
                        if (isUnlockedNow()) {
                            startActivity(
                                Intent(this@TaskAlarmActivity, MainActivity::class.java)
                                    .putExtra(kr.mom.probe.widget.AssistantWidgetProvider.EXTRA_OPEN_TODO, true)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                        } else {
                            unlocked = false
                        }
                    },
                    onComplete = {
                        if (!busy && id != null && occurrence != null) {
                            if (!isUnlockedNow()) {
                                unlocked = false
                            } else {
                                pendingAskMomo.cancel()
                                busy = true
                                scope.launch {
                                    val completed = runCatching {
                                        withContext(Dispatchers.IO) { store.completeAlarmOccurrence(id, occurrence) }
                                    }.getOrDefault(false)
                                    if (completed) {
                                        stopOwnNotification()
                                        finish()
                                    } else {
                                        status = "이미 지난 알림이에요. 앱에서 부탁을 확인해주세요."
                                        busy = false
                                    }
                                }
                            }
                        }
                    },
                    onUnlock = { requestUnlockThen() },
                    onSpeak = if (speechReady && activeTask != null) {
                        {
                            if (isUnlockedNow()) {
                                speech?.speak(activeTask.text, TextToSpeech.QUEUE_FLUSH, null, "task-alarm")
                            } else {
                                unlocked = false
                                speech?.stop()
                            }
                        }
                    } else {
                        null
                    },
                    onAskMomo = { openAgentAfterUnlock() },
                )
            }
        }
    }

    private fun refreshUnlocked() {
        unlocked = isUnlockedNow()
    }

    private fun isUnlockedNow(): Boolean = !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requestUnlockThen(afterUnlock: (() -> Unit)? = null) {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlockingForAskMomo = false
                    refreshUnlocked()
                    afterUnlock?.invoke()
                    window.decorView.post {
                        refreshUnlocked()
                        afterUnlock?.invoke()
                    }
                }

                override fun onDismissCancelled() {
                    unlockingForAskMomo = false
                    pendingAskMomo.cancel()
                    refreshUnlocked()
                }

                override fun onDismissError() {
                    unlockingForAskMomo = false
                    pendingAskMomo.cancel()
                    refreshUnlocked()
                }
            },
        )
    }

    private fun openAgentAfterUnlock() {
        if (!isUnlockedNow()) {
            unlocked = false
            pendingAskMomo.begin()
            unlockingForAskMomo = true
            requestUnlockThen { continuePendingAskMomo() }
            return
        }
        pendingAskMomo.cancel()
        stopOwnNotification()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(kr.mom.probe.widget.AssistantWidgetProvider.EXTRA_OPEN_TODO, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun continuePendingAskMomo() {
        if (isFinishing || isDestroyed || !resumed || !hasWindowFocus) return
        pendingAskMomo.runIfUnlocked(::isUnlockedNow) {
            stopOwnNotification()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(kr.mom.probe.widget.AssistantWidgetProvider.EXTRA_OPEN_TODO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }

    private fun stopOwnNotification() {
        if (notificationId > 0) getSystemService(NotificationManager::class.java).cancel(notificationId)
        speech?.stop()
    }

    override fun onStop() {
        stopOwnNotification()
        super.onStop()
    }

    override fun onDestroy() {
        pendingAskMomo.cancel()
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }
}

internal fun taskAlarmState(
    unlocked: Boolean,
    allowed: Boolean,
    loading: Boolean,
    taskError: Boolean,
    task: AssistantTask?,
    scheduledAt: Long,
    speechReady: Boolean,
    status: String? = null,
): AlarmContentState {
    val dateText = formatAlarmDate(scheduledAt)
    val timeText = formatAlarmTime(scheduledAt)
    return when {
        !unlocked -> AlarmContentState(
            title = "모모 알림",
            dateText = dateText,
            scheduledTimeText = timeText,
            actionTitle = "잠금을 풀면 내용을 볼 수 있어요",
            actionSummary = "모모가 알려드릴 부탁이 있어요",
            locked = true,
            snoozeEnabled = allowed,
            statusText = status,
        )
        !allowed -> AlarmContentState(
            title = "모모 알림",
            dateText = dateText,
            scheduledTimeText = timeText,
            actionTitle = "첫 설정을 확인해주세요",
            actionSummary = "앱 설정 상태를 확인한 뒤 부탁 내용을 볼 수 있어요.",
            snoozeEnabled = false,
            statusText = status,
        )
        loading -> AlarmContentState(
            title = "모모 알림",
            dateText = dateText,
            scheduledTimeText = timeText,
            actionTitle = "부탁을 확인하고 있어요",
            actionSummary = "저장된 상태를 불러오는 중이에요.",
            snoozeEnabled = false,
            statusText = status,
        )
        taskError || task == null -> AlarmContentState(
            title = "모모 알림",
            dateText = dateText,
            scheduledTimeText = timeText,
            actionTitle = "이미 지난 알림이에요",
            actionSummary = "앱에서 최신 부탁 목록을 확인해주세요.",
            snoozeEnabled = false,
            statusText = status,
        )
        else -> AlarmContentState(
            title = "모모의 부탁",
            dateText = dateText,
            scheduledTimeText = timeText,
            actionTitle = task.text,
            actionSummary = task.dueAt?.let { "기한 ${displayTime(it)}" } ?: "곧 챙길 일 1개",
            detailLines = listOfNotNull(
                "부탁: ${task.text}",
                task.dueAt?.let { "기한: ${displayTime(it)}" },
                "다시 알림: ${task.snoozeCount}/3회, ${task.snoozeMinutes}/60분",
            ),
            completeEnabled = true,
            showComplete = true,
            showSpeak = speechReady,
            statusText = status,
        )
    }
}

internal fun formatAlarmDate(timeMillis: Long): String =
    SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(Date(timeMillis))

internal fun formatAlarmTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date(timeMillis))
