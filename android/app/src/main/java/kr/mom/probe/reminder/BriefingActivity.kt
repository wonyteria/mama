package kr.mom.probe.reminder

import android.content.Intent
import android.app.KeyguardManager
import android.app.NotificationManager
import android.view.WindowManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import java.util.Locale
import kr.mom.probe.BuildConfig
import kr.mom.probe.task.AssistantTasksActivity
import kr.mom.probe.task.AssistantTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.ui.displayTime
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.sync.SourceStatusPresentation
import kr.mom.probe.sync.SourceSyncStateStore

class BriefingActivity : ComponentActivity() {
    private var speech: TextToSpeech? = null
    private var speechReady by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private val pendingAskMomo = PendingUnlockAction()
    private var resumed = false
    private var hasWindowFocus = false
    private var unlockingForAskMomo = false
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
            if (status == TextToSpeech.SUCCESS) speechReady = (speech?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
        }
        setContent {
            MomTheme {
                val repository = remember { ProbeRepository.get(this) }
                val ready by repository.isReady.collectAsState()
                val settings by repository.settings.collectAsState()
                val records by repository.records.collectAsState()
                val sourceSnapshots by remember { SourceSyncStateStore.get(this@BriefingActivity) }.snapshotsFlow.collectAsState()
                val error by repository.lastError.collectAsState()
                val taskStore = remember { AssistantTaskStore.get(this) }
                val tasks by taskStore.tasks.collectAsState()
                var taskError by remember { mutableStateOf(false) }
                var taskLoading by remember { mutableStateOf(true) }
                var status by remember { mutableStateOf<String?>(null) }
                var snoozedUntil by remember { mutableStateOf<Long?>(null) }
                val scope = rememberCoroutineScope()
                val demo = intent.getBooleanExtra(BriefingReminders.DEMO, false)
                val slot = intent.getIntExtra("slot", 0)
                val occurrenceId = intent.getStringExtra(BriefingReminders.OCCURRENCE_ID)
                val generation = intent.getLongExtra(BriefingReminders.GENERATION, BriefingReminders.generation(this@BriefingActivity))
                val consentAllowed = ready && settings.consent && settings.consentVersion == ProbeRules.CONSENT_VERSION &&
                    settings.onboardingDone && error == null
                val allowed = unlocked && consentAllowed
                LaunchedEffect(allowed) {
                    if (allowed) {
                        taskLoading = true
                        try { withContext(Dispatchers.IO) { taskStore.load() }; taskError = false } catch (_: Exception) { taskError = true }
                        finally { taskLoading = false }
                    } else {
                        taskLoading = false
                    }
                }
                val pending = if (allowed && !taskError) BriefingReminders.briefingTasks(tasks) else emptyList()
                val linkedNotificationIds = if (allowed && !taskError) tasks.mapNotNull { it.sourceNotificationId }.toSet() else emptySet()
                val unseen = if (allowed) remember(records, settings, tasks) { BriefingReminders.unseenRecords(this, repository, tasks) } else emptyList()
                val agenda = if (allowed) remember(records, settings, slot) { BriefingReminders.briefingAgenda(this@BriefingActivity, records, settings, slot) } else emptyList()
                val child = remember(settings.schoolGrade, settings.schoolLevel, settings.schoolName) { NoticeDecisionEngine.childProfile(settings) }
                val noticeRows = remember(unseen, child) {
                    unseen.map { it to NoticeDecisionEngine.decide(it, child) }
                        .filter { it.second.isRequiredForChild() }
                        .filter { it.second.source.notificationId !in linkedNotificationIds }
                        .sortedWith(compareBy<Pair<kr.mom.probe.data.ProbeRecord, kr.mom.probe.data.NoticeDecision>> { it.second.action?.dueAt ?: Long.MAX_VALUE }
                            .thenByDescending { it.first.receivedAt })
                }
                val agendaSummaries = remember(agenda) {
                    agenda.take(3).map { "${it.title} · ${it.sourceLabel}" }
                }
                val visibleNoticeRows = remember(noticeRows, agendaSummaries) {
                    noticeRows.take((3 - agendaSummaries.size).coerceAtLeast(0))
                }
                val count = noticeRows.size
                val noticeSummaries = remember(visibleNoticeRows) {
                    visibleNoticeRows.map { (record, decision) ->
                        val action = decision.action
                        val due = action?.dueAt?.let { " · ${displayTime(it)}" }
                            ?: action?.whenText?.let { " · 원문 $it" }.orEmpty()
                        "${action?.label ?: record.title.ifBlank { "내용 확인 필요" }}$due"
                    }
                }
                val visiblyRenderedNoticeRows = visibleNoticeRows.take(
                    initiallyVisibleBriefingNoticeCount(agendaSummaries.size, visibleNoticeRows.size),
                )
                val text = when {
                    !unlocked -> "모모가 알려드릴 소식이 있어요. 잠금을 풀고 확인해 주세요."
                    !allowed -> "첫 설정을 마친 뒤 다시 불러주세요."
                    demo -> "비서 알림 미리보기예요. 앞으로 정한 시간에 새로 모인 알림을 알려드릴게요."
                    taskLoading -> "부탁을 확인하고 있어요."
                    taskError -> "부탁을 불러오지 못했어요. 새 알림은 ${count}개예요."
                    count == 0 && pending.isEmpty() && agenda.isEmpty() -> "새 알림과 남은 부탁이 없어요. 연결된 모든 앱과 사이트를 확인했다는 뜻은 아니에요."
                    else -> buildString {
                        append("곧 챙길 내용을 한 번에 정리했어요.")
                        agendaSummaries.forEach { append("\n• 일정: $it") }
                        noticeSummaries.forEach { append("\n• $it") }
                        pending.take((3 - agendaSummaries.size - noticeSummaries.size).coerceAtLeast(0)).forEach { append("\n• 부탁: ${it.text}") }
                        val summarized = agendaSummaries.size + noticeSummaries.size + pending.take((3 - agendaSummaries.size - noticeSummaries.size).coerceAtLeast(0)).size
                        val remaining = agenda.size + count + pending.size - summarized
                        if (remaining > 0) append("\n그 밖에 새 소식과 부탁 ${remaining}개가 더 있어요.")
                    }
                }
                val scheduledAt = intent.getLongExtra(BriefingReminders.SCHEDULED_AT, System.currentTimeMillis())
                val firstAction = agendaSummaries.firstOrNull()?.let { "일정: $it" } ?: noticeSummaries.firstOrNull() ?: pending.firstOrNull()?.let { "부탁: ${it.text}" }
                val totalItems = agenda.size + count + pending.size
                val sourceStatusMessage = SourceStatusPresentation.message(sourceSnapshots.values)
                val actionTitle = when {
                    !unlocked -> "잠금을 풀면 내용을 볼 수 있어요"
                    !allowed -> "첫 설정을 확인해주세요"
                    demo -> "비서 알림 미리보기"
                    taskLoading -> "부탁을 확인하고 있어요"
                    taskError -> "부탁을 불러오지 못했어요"
                    firstAction != null -> firstAction
                    else -> "새 알림과 남은 부탁이 없어요"
                }
                val actionSummary = when {
                    !unlocked -> "모모가 알려드릴 소식이 있어요"
                    !allowed -> "앱에서 처음 설정을 마치면 내용을 볼 수 있어요."
                    demo -> "정한 시간에 새로 모인 알림을 알려드릴게요."
                    taskLoading -> "저장된 부탁을 불러오는 중이에요."
                    taskError -> "앱에서 부탁 목록을 다시 확인해주세요."
                    totalItems > 1 -> "곧 챙길 일 1개 · 다른 일 ${totalItems - 1}개"
                    totalItems == 1 -> "곧 챙길 일 1개"
                    sourceStatusMessage != null -> sourceStatusMessage
                    else -> "연결된 모든 앱과 사이트를 확인했다는 뜻은 아니에요."
                }
                val details = remember(text, sourceStatusMessage) {
                    text.lines().filter { it.isNotBlank() } + listOfNotNull(sourceStatusMessage?.let { "수집 상태: $it" })
                }
                LaunchedEffect(allowed, demo, taskLoading, taskError, visiblyRenderedNoticeRows) {
                    if (allowed && !demo && !taskLoading && !taskError && visiblyRenderedNoticeRows.isNotEmpty()) {
                        BriefingReminders.markSeenRecords(this@BriefingActivity, visiblyRenderedNoticeRows.map { it.first })
                    }
                }
                val alarmState = snoozedUntil?.let {
                    AlarmContentState(
                        title = if (demo) "비서 미리보기" else "모모의 브리핑",
                        dateText = formatAlarmDate(it),
                        scheduledTimeText = formatAlarmTime(it),
                        actionTitle = "다시 알림을 예약했어요",
                        actionSummary = "${displayTime(it)} 무렵 다시 알려드릴게요.",
                        locked = !unlocked,
                        snoozeEnabled = false,
                        showComplete = false,
                        statusText = status,
                        timeLabel = "다음 알림",
                    )
                } ?: AlarmContentState(
                    title = if (demo) "비서 미리보기" else "모모의 브리핑",
                    dateText = formatAlarmDate(scheduledAt),
                    scheduledTimeText = formatAlarmTime(scheduledAt),
                    actionTitle = actionTitle,
                    actionSummary = actionSummary,
                    detailLines = details,
                    locked = !unlocked,
                    snoozeEnabled = consentAllowed && !demo,
                    showComplete = false,
                    showSpeak = unlocked && allowed && speechReady && (!taskLoading || demo),
                    statusText = status ?: if (!speechReady && unlocked) "한국어 음성 엔진이 준비되지 않았어요. 화면으로 확인할 수 있어요." else null,
                )
                AlarmContent(
                    state = alarmState,
                    onStopSound = {
                        pendingAskMomo.cancel()
                        stopAlarm()
                        finish()
                    },
                    onSnoozeTenMinutes = {
                        if (demo) {
                            pendingAskMomo.cancel()
                            stopAlarm()
                            finish()
                        } else {
                            pendingAskMomo.cancel()
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    BriefingReminders.snoozeActive(this@BriefingActivity, slot, occurrenceId, generation, 10)
                                }
                                when (result) {
                                    is BriefingSnoozeResult.Scheduled -> {
                                        stopAlarm()
                                        snoozedUntil = result.nextAt
                                        status = "${displayTime(result.nextAt)} 무렵 다시 알려드릴게요."
                                    }
                                    BriefingSnoozeResult.Disabled -> status = "브리핑 시간이 꺼져 있어 다시 알리지 않았어요."
                                    BriefingSnoozeResult.Stale -> status = "이미 지난 브리핑이에요. 앱에서 최신 내용을 확인해주세요."
                                    BriefingSnoozeResult.Failed -> status = "다시 알리지 못했어요. 소리 끄기를 눌러주세요."
                                }
                            }
                        }
                    },
                    onShowDetails = {
                        if (allowed && !demo && !isUnlockedNow()) {
                            unlocked = false
                        }
                    },
                    onUnlock = { requestUnlockThen() },
                    onSpeak = if (unlocked && allowed && speechReady) {
                        {
                            if (isUnlockedNow()) {
                                pendingAskMomo.cancel()
                                stopAlarm()
                                speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "briefing")
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
        stopAlarm()
        startActivity(Intent(this, AssistantTasksActivity::class.java))
        finish()
    }

    private fun continuePendingAskMomo() {
        if (isFinishing || isDestroyed || !resumed || !hasWindowFocus) return
        pendingAskMomo.runIfUnlocked(::isUnlockedNow) {
            stopAlarm()
            startActivity(Intent(this, AssistantTasksActivity::class.java))
            finish()
        }
    }

    private fun stopAlarm() {
        val demo = intent.getBooleanExtra(BriefingReminders.DEMO, false)
        val fallback = (if (demo) 1_710 else 710) + intent.getIntExtra("slot", 0)
        val notificationId = intent.getIntExtra(BriefingReminders.NOTIFICATION_ID, fallback)
        BriefingReminders.stopActive(
            this,
            intent.getIntExtra("slot", 0),
            intent.getStringExtra(BriefingReminders.OCCURRENCE_ID),
            intent.getLongExtra(BriefingReminders.GENERATION, BriefingReminders.generation(this)),
            demo,
            notificationId,
        )
        if (demo) getSystemService(NotificationManager::class.java).cancel(notificationId)
        speech?.stop()
    }
    override fun onStop() { stopAlarm(); speech?.stop(); super.onStop() }
    override fun onDestroy() { pendingAskMomo.cancel(); speech?.shutdown(); speech = null; super.onDestroy() }
}

internal fun initiallyVisibleBriefingNoticeCount(agendaCount: Int, noticeCount: Int): Int =
    if (agendaCount == 0 && noticeCount > 0) 1 else 0
