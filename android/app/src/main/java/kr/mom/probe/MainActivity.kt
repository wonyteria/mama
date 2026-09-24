package kr.mom.probe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.connector.*
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceRecordSelectors
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScopeFactory
import kr.mom.probe.sync.SourceSyncScheduler
import kr.mom.probe.sync.SourceSyncStateStore
import kr.mom.probe.sync.SourceStatusPresentation
import kr.mom.probe.service.ProbeNotificationListener
import kr.mom.probe.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Production research builds protect raw notifications in recents/screenshots.
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val session = ViewModelProvider(this)[ProbeSession::class.java]
        val openAssistant = intent.getBooleanExtra(kr.mom.probe.widget.AssistantWidgetProvider.EXTRA_OPEN_ASSISTANT, false)
        val initialRecordId = intent.getStringExtra(EXTRA_RECORD_ID)
        setContent { MomTheme { ProbeApp(session, openAssistant, initialRecordId) } }
    }

    companion object { const val EXTRA_RECORD_ID = "kr.mom.probe.RECORD_ID" }
}

private data class PendingTaskSave(
    val text: String,
    val sourceNotificationId: String,
    val sourceRevisionId: String,
    val dueAt: Long?,
    val remindAt: Long?,
    val noticeGroupKeys: Set<String> = emptySet(),
)

@Composable
fun ProbeApp(session: ProbeSession, openAssistant: Boolean = false, initialRecordId: String? = null) {
    val context = LocalContext.current
    val repository = remember { ProbeRepository.get(context) }
    val connectorRepository = remember { ConnectorRepository.get(context) }
    val sourceStateStore = remember { SourceSyncStateStore.get(context) }
    val connectorState by connectorRepository.state.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle()
    val records by repository.records.collectAsStateWithLifecycle()
    val assistantStore = remember { kr.mom.probe.task.AssistantTaskStore.get(context) }
    val assistantTasks by assistantStore.tasks.collectAsStateWithLifecycle()
    val ready by repository.isReady.collectAsStateWithLifecycle()
    val storageError by repository.lastError.collectAsStateWithLifecycle()
    val connected by repository.listenerConnected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("welcome") }
    var started by rememberSaveable { mutableStateOf(false) }
    var openedInitialRecord by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(false) }
    var resetWebsites by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var previous by rememberSaveable { mutableStateOf("home") }
    var editingChild by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var assistantAlertsEnabled by remember { mutableStateOf(kr.mom.probe.reminder.AssistantAlertNotifier.isEnabled(context)) }
    var pendingNotificationTest by rememberSaveable { mutableStateOf(false) }
    var pendingAssistantAlertEnable by rememberSaveable { mutableStateOf(false) }
    var pendingTaskSave by remember { mutableStateOf<PendingTaskSave?>(null) }
    var pendingAccessReturn by rememberSaveable { mutableStateOf(false) }
    var accessRequestRefresh by rememberSaveable { mutableIntStateOf(-1) }
    var pendingAppPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var appLoginPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccessRationale by remember { mutableStateOf(false) }
    var pendingWebsiteId by rememberSaveable { mutableStateOf<String?>(null) }
    val sourceSnapshots by sourceStateStore.snapshotsFlow.collectAsStateWithLifecycle()
    val installed = remember(refresh) { SourceCatalog.installed(context) }
    val missing = remember(refresh) { SourceCatalog.missing(context) }
    val snackbar = remember { SnackbarHostState() }
    val validConsent = settings.consent && settings.consentVersion == kr.mom.probe.data.ProbeRules.CONSENT_VERSION

    fun refreshSourceSnapshots() {
        // SourceSyncStateStore exposes a StateFlow; this hook remains for existing command callbacks.
    }


    suspend fun suspendAutomaticTasksForSources(sourceIds: Set<String>) {
        val identities = records.mapNotNull { record ->
            record.sourceMetadata?.takeIf { it.sourceId in sourceIds }?.let { metadata ->
                kr.mom.probe.data.ProbeRules.sourceItemIdentity(metadata.sourceId, metadata.itemId)
            }
        }.toSet()
        if (identities.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { assistantStore.suspendAutomaticSources(identities) }
            }
        }
    }

    fun command(operation: suspend () -> Boolean, after: () -> Unit = {}) {
        if (busy) return
        scope.launch {
            busy = true
            try { if (operation()) after() else message = repository.lastError.value ?: "설정을 확인하고 다시 시도해주세요." }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "작업을 마치지 못했어요. 다시 시도해주세요."
            } finally { busy = false }
        }
    }

    fun proceedSetup() {
        editingChild = false
        screen = when {
            settings.childName.isBlank() -> "child"
            settings.selectedPackages.isEmpty() && connectorState.sites.values.none {
                it.status == ConnectionStatus.CONNECTED
            } -> "connections"
            settings.selectedPackages.isNotEmpty() && !access -> "connections"
            else -> "home"
        }
        if (screen == "connections" && settings.selectedPackages.isNotEmpty() && !access) {
            pendingAppPackage = settings.selectedPackages.first()
            showAccessRationale = true
        } else if (screen == "home" && !settings.onboardingDone) {
            command({ if (settings.selectedPackages.isNotEmpty() && access) repository.completeSetup() else repository.deferSetup() }) {
                SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                refreshSourceSnapshots()
            }
        }
    }

    fun openOfficialUrl(url: String) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https") { message = "안전한 공식 주소를 확인하지 못했어요."; return }
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { message = "공식 사이트를 열 수 없어요." }
    }

    fun openAccess() {
        pendingAccessReturn = true
        accessRequestRefresh = refresh
        val component = ComponentName(context, ProbeNotificationListener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        try { context.startActivity(detail) }
        catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) { message = "휴대폰 설정에서 알림 접근을 찾아 ‘나는 엄마다’를 허용해주세요." }
        }
    }

    fun sendTestNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("local-test", "연결 확인용 알림", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("local-test")?.importance == NotificationManager.IMPORTANCE_NONE) {
            message = "알림 받기가 꺼져 있어요. 휴대폰 앱 알림 설정에서 켜주세요."
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            return
        }
        val pending = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(404, Notification.Builder(context, "local-test").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("나는 엄마다 · 테스트 알림").setContentText("알림 보내기가 준비됐어요. 학교·학원 수집 결과와는 별개예요.")
            .setContentIntent(pending).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        message = "테스트 알림을 보냈어요. 알림창에서 확인해주세요."
    }

    fun saveAssistantTask(request: PendingTaskSave) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val added = withContext(Dispatchers.IO) {
                    assistantStore.addTask(
                        request.text,
                        request.sourceNotificationId,
                        request.dueAt,
                        request.remindAt,
                        sourceRevisionId = request.sourceRevisionId,
                        sourceKind = kr.mom.probe.task.AssistantTaskSource.USER_CONFIRMED_NOTICE,
                        noticeGroupKeys = request.noticeGroupKeys,
                    ) != null
                }
                message = if (added) "모모가 부탁과 알림을 기억해뒀어요." else "이미 모모가 기억하고 있어요."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = error.message ?: "부탁으로 저장하지 못했어요."
            } finally { busy = false }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        val taskRequest = pendingTaskSave
        if (granted && taskRequest != null && kr.mom.probe.task.TaskReminderScheduler.canDeliver(context)) {
            pendingTaskSave = null
            saveAssistantTask(taskRequest)
        } else if (granted && taskRequest != null) {
            message = "휴대폰 설정에서 ‘모모의 부탁 알림’을 켜면 자동으로 저장할게요."
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, kr.mom.probe.task.TaskReminderScheduler.CHANNEL))
        } else if (granted && pendingNotificationTest) sendTestNotification()
        else if (granted && pendingAssistantAlertEnable) {
            assistantAlertsEnabled = kr.mom.probe.reminder.AssistantAlertNotifier.setEnabled(context, true)
            val briefingsEnabled = kr.mom.probe.reminder.BriefingReminders.enableDefaults(context)
            message = if (assistantAlertsEnabled && briefingsEnabled) "아침·저녁에는 한 번에 정리하고, 오늘 긴급한 일만 바로 알려드릴게요." else "비서 알림 설정을 저장하지 못했어요."
        }
        else message = "알림을 켜기 전까지 모모의 안내는 앱 안에서 확인할 수 있어요."
        pendingNotificationTest = false
        pendingAssistantAlertEnable = false
        if (!granted) pendingTaskSave = null
    }
    val websiteLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val siteId = pendingWebsiteId
        pendingWebsiteId = null
        if (siteId != null) {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (siteId == SourceIds.EALIMI_WEB) SourceSyncScheduler.enqueue(context, SourceIds.EALIMI_WEB, SourceRunTrigger.CONNECTION_READY)
                refreshSourceSnapshots()
                message = "웹 방문을 저장했어요. 로그인 유효 여부와 자동 조회는 아직 확인 전이에요."
            } else {
                scope.launch { connectorRepository.disconnect(siteId) }
                message = "로그인을 마치지 않아 연결하지 않았어요."
            }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { session.exportPayload = null; session.exportIds = emptySet() }
        else {
            val content = session.exportPayload
            val requestedIds = session.exportIds
            scope.launch {
                busy = true
                try {
                    val now = System.currentTimeMillis()
                    val current = repository.records.value.filter { now - it.receivedAt < 14L * 86_400_000 }.map { it.id }.toSet()
                    if (!repository.settings.value.consent || content == null || !current.containsAll(requestedIds)) {
                        withContext(Dispatchers.IO) { try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { /* Provider may not support cleanup. */ } }
                        message = if (content == null) "앱이 다시 시작되어 저장을 중단했어요. 자료를 다시 검토해주세요." else "자료가 삭제되거나 보관 기간이 끝났어요. 다시 검토해주세요."
                    } else {
                        withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                            stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                        }
                        message = "검토한 자료를 선택한 위치에 저장했어요."
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.IO) { try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { /* Keep the original local records. */ } }
                    message = "파일을 저장하지 못했어요. 위치를 확인하고 다시 시도해주세요."
                } finally { session.exportPayload = null; session.exportIds = emptySet(); busy = false }
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = repository.hasNotificationAccess()
                notificationsAllowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                assistantAlertsEnabled = kr.mom.probe.reminder.AssistantAlertNotifier.isEnabled(context)
                refresh++
                refreshSourceSnapshots()
                scope.launch { repository.cleanupExpired() }
                SourceSyncScheduler.enqueueForegroundStale(context)
            }
        }
        lifecycle.addObserver(observer)
        access = repository.hasNotificationAccess()
        refreshSourceSnapshots()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(ready, settings.consent, settings.consentVersion) {
        if (ready && !started) {
            screen = when {
                !validConsent -> "welcome"
                settings.onboardingDone -> if (openAssistant) "inbox" else "home"
                settings.childName.isBlank() -> "child"
                else -> "connections"
            }
            started = true
        }
        if (ready && started && !validConsent) screen = "welcome"
        if (ready && screen == "export" && session.exportSnapshot.isEmpty()) {
            screen = "inbox"
            message = "앱이 다시 시작되어 자료 검토를 다시 열어주세요."
        }
    }
    LaunchedEffect(ready, validConsent, settings.onboardingDone) {
        if (ready && validConsent && settings.onboardingDone) {
            runCatching { withContext(Dispatchers.IO) { assistantStore.load() } }
        }
    }
    LaunchedEffect(validConsent, settings.onboardingDone, notificationsAllowed) {
        if (validConsent && settings.onboardingDone) {
            if (notificationsAllowed && !kr.mom.probe.reminder.BriefingReminders.defaultsOffered(context)) {
                val briefings = kr.mom.probe.reminder.BriefingReminders.enableDefaults(context)
                assistantAlertsEnabled = kr.mom.probe.reminder.AssistantAlertNotifier.setEnabled(context, true)
                if (!briefings) message = "기본 브리핑 시간을 저장하지 못했어요."
            } else if (!notificationsAllowed && kr.mom.probe.reminder.BriefingReminders.claimNotificationPrompt(context)) {
                pendingNotificationTest = false
                pendingAssistantAlertEnable = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    LaunchedEffect(refresh, pendingTaskSave, notificationsAllowed) {
        val request = pendingTaskSave
        if (request != null && notificationsAllowed && kr.mom.probe.task.TaskReminderScheduler.canDeliver(context)) {
            pendingTaskSave = null
            saveAssistantTask(request)
        }
    }
    LaunchedEffect(ready, initialRecordId, records, validConsent, settings.onboardingDone) {
        if (ready && validConsent && settings.onboardingDone && !openedInitialRecord && initialRecordId != null && records.any { it.id == initialRecordId }) {
            selectedId = initialRecordId
            previous = "home"
            screen = "detail"
            openedInitialRecord = true
        }
    }
    LaunchedEffect(connectorState.sites, ready, settings.onboardingDone, settings.selectedPackages, access) {
        val usableSite = connectorState.sites.values.any {
            it.status == ConnectionStatus.CONNECTED
        }
        if (ready && !settings.onboardingDone && settings.childName.isNotBlank() && usableSite) {
            val finished = if (settings.selectedPackages.isNotEmpty() && access) repository.completeSetup() else repository.deferSetup()
            if (finished) {
                SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                refreshSourceSnapshots()
                screen = "home"
            }
        }
    }
    LaunchedEffect(ready, refresh) {
        if (ready && settings.consent) {
            val existing = installed.map { it.packageName }.toSet()
            val retained = repository.settings.value.selectedPackages.intersect(existing)
            if (retained != repository.settings.value.selectedPackages && repository.saveSourceSelection(retained)) {
                message = "휴대폰에서 없어진 앱의 새 알림 수집을 껐어요. 저장한 알림은 남아 있어요."
            }
        }
    }
    LaunchedEffect(access, refresh) {
        if (ready && pendingAccessReturn && refresh > accessRequestRefresh) {
            pendingAccessReturn = false
            val packageName = pendingAppPackage
            pendingAppPackage = null
            if (packageName != null) {
                if (access) {
                    val updated = repository.settings.value.selectedPackages + packageName
                    val saved = repository.saveSourceSelection(updated)
                    val finished = if (!saved) false else if (repository.settings.value.onboardingDone) {
                        repository.setCollectionEnabled(true)
                    } else repository.completeSetup()
                    if (finished) {
                        SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                        refreshSourceSnapshots()
                        if (!settings.onboardingDone) screen = "home"
                        appLoginPackage = packageName
                    }
                } else message = "알림 접근을 허용하지 않아 앱 연결을 켜지 않았어요."
            } else if (!access) {
                message = "알림 접근은 아직 꺼져 있어요."
            }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    fun back() {
        val leavingExport = screen == "export"
        screen = when(screen) {
            "connections" -> if (settings.onboardingDone) previous else "child"
            "child" -> if (settings.onboardingDone) previous else "welcome"
            "detail", "export" -> previous
            else -> "home"
        }
        if (leavingExport) session.clearExport()
    }
    BackHandler(screen !in setOf("home", "inbox", "settings", "welcome")) { if (!busy) back() }

    val rootTab = screen in setOf("home", "inbox", "settings")
    Scaffold(
        containerColor = Clay.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (rootTab) ProbeBottomBar(screen) { screen = it }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (storageError != null) {
                Column(Modifier.fillMaxWidth().background(Clay.Peach).padding(14.dp)) {
                    Text(storageError!!, color = Clay.Error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { deleteTarget = "ALL" }, enabled = !busy) { Text("전체 삭제 후 다시 시작") }
                }
            }
            if (!ready || !started) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else when (screen) {
                "welcome" -> WelcomeScreen(busy, { command({ repository.acceptConsent() }) {
                    screen = if (repository.settings.value.onboardingDone) "home" else "child"
                } }, { policy = true })
                "connections" -> ConnectionsScreen(
                    settings = settings,
                    installedApps = installed,
                    missingApps = missing,
                    verifiedAppPackages = records.map { it.packageName }.toSet(),
                    connectorState = connectorState,
                    busy = busy,
                    notificationAccess = access,
                    onBack = ::back,
                    onSkip = { command({ repository.deferSetup() }) {
                        SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                        refreshSourceSnapshots()
                        screen = "home"
                    } },
                    onToggleApp = { packageName, enabled ->
                        if (enabled && !access) {
                            pendingAppPackage = packageName
                            showAccessRationale = true
                        } else {
                            val wasOnboarded = settings.onboardingDone
                            val updated = if (enabled) settings.selectedPackages + packageName else settings.selectedPackages - packageName
                            command({
                                val saved = repository.saveSourceSelection(updated)
                                if (saved && enabled && access) {
                                    if (wasOnboarded) repository.setCollectionEnabled(true) else repository.completeSetup()
                                } else saved
                            }) {
                                if (enabled) {
                                    SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                                    refreshSourceSnapshots()
                                    if (!wasOnboarded) screen = "home"
                                    appLoginPackage = packageName
                                }
                            }
                        }
                    },
                    sourceSnapshots = sourceSnapshots,
                    onRefreshSource = { sourceId ->
                        SourceSyncScheduler.enqueue(context, sourceId, SourceRunTrigger.MANUAL)
                        refreshSourceSnapshots()
                        message = "출처 확인을 시작했어요."
                    },
                    onToggleWebsite = { siteId, enabled ->
                        val definition = ConnectorCatalog.site(siteId)
                        if (definition == null) message = "연결 정보를 찾지 못했어요."
                        else if (enabled) {
                            command({ connectorRepository.markConnecting(siteId) }) {
                                pendingWebsiteId = siteId
                                websiteLogin.launch(Intent(context, WebsiteLoginActivity::class.java)
                                    .putExtra(WebsiteLoginActivity.EXTRA_SITE_ID, siteId))
                            }
                        } else {
                            resetWebsites = true
                        }
                    },
                    onRecommendApp = { app ->
                        val market = android.net.Uri.parse("market://details?id=${app.packageName}")
                        val web = android.net.Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}")
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, market)) }
                        catch (_: Exception) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, web)) }
                            catch (_: Exception) { message = "설치 페이지를 열지 못했어요." }
                        }
                    },
                )
                "child" -> ChildProfileScreen(settings, busy, { name, school, grade, level ->
                    command({
                        val changedSchool = school != repository.settings.value.schoolName
                        val changedGrade = grade != repository.settings.value.schoolGrade || level != repository.settings.value.schoolLevel
                        val changedScope = changedSchool || changedGrade
                        val saved = repository.saveChild(name, school, grade, level)
                        if (saved && changedScope) {
                            SourceSyncScheduler.cancelAll(context)
                            suspendAutomaticTasksForSources(setOf(SourceIds.SCHOOL_WEBSITE, SourceIds.EALIMI_WEB))
                        }
                        if (saved && changedSchool) {
                            sourceStateStore.bumpGeneration(SourceIds.SCHOOL_WEBSITE)
                            sourceStateStore.bumpGeneration(SourceIds.EALIMI_WEB)
                            repository.clearSourceRecords(setOf(SourceIds.SCHOOL_WEBSITE, SourceIds.EALIMI_WEB))
                        }
                        saved
                    }) {
                        SourceSyncScheduler.enqueueActive(context, SourceRunTrigger.CONNECTION_READY)
                        refreshSourceSnapshots()
                        screen = if (editingChild) "settings" else "connections"
                    }
                }, ::back)
                "home" -> {
                    val activeSourceScopes = SourceScopeFactory.activeScopes(context, SourceRunTrigger.MANUAL)
                    val homeRecords = SourceRecordSelectors.activeRecords(records, activeSourceScopes)
                    val sourceStatusMessage = SourceStatusPresentation.message(sourceSnapshots.values)
                    HomeScreen(settings, homeRecords, access, connected, ::proceedSetup, { screen = "inbox" }, {
                    selectedId = it.id; previous = "home"; screen = "detail"
                }, connectedSiteCount = connectorState.sites.values.count {
                    it.status == ConnectionStatus.CONNECTED
                } + activeSourceScopes
                    .count { it.sourceId == SourceIds.SCHOOL_WEBSITE },
                    sourceAgenda = SourceRecordSelectors.agenda(
                        records,
                        kr.mom.probe.data.NoticeDecisionEngine.childProfile(settings),
                        activeSourceScopes,
                        institution = kr.mom.probe.data.NoticeGrouping.institution(settings),
                    ),
                    sourceStatusMessage = sourceStatusMessage,
                    onAgenda = { screen = "inbox" },
                    schoolEventsLimited = sourceStatusMessage != null,
                    pendingTaskCount = kr.mom.probe.reminder.BriefingReminders.briefingTasks(assistantTasks).size,
                    briefingReady = notificationsAllowed && kr.mom.probe.reminder.BriefingReminders.hasEnabledBriefing(context),
                    rememberedGroupKeys = assistantTasks
                        .map { it.noticeGroupKeys + listOfNotNull(it.sourceNotificationId) }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                    onEnableBriefings = {
                        if (notificationsAllowed) {
                            assistantAlertsEnabled = kr.mom.probe.reminder.AssistantAlertNotifier.setEnabled(context, true)
                            val briefingsEnabled = kr.mom.probe.reminder.BriefingReminders.enableDefaults(context)
                            message = if (assistantAlertsEnabled && briefingsEnabled) "아침·저녁 브리핑과 긴급 알림을 켰어요." else "비서 알림 설정을 저장하지 못했어요."
                        } else {
                            pendingNotificationTest = false
                            pendingAssistantAlertEnable = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onAssistant = {
                    context.startActivity(Intent(context, kr.mom.probe.task.AssistantTasksActivity::class.java))
                })
                }
                "inbox" -> InboxScreen(records, { selectedId = it.id; previous = "inbox"; screen = "detail" }, {
                    session.exportSnapshot = records; previous = "inbox"; screen = "export"
                })
                "detail" -> {
                    val record = records.find { it.id == selectedId }
                    if (record == null) Page { BackHeading("받은 알림", ::back); EmptyCard("삭제되었거나 보관 기간이 끝났어요", "현재 남아 있는 알림을 확인해주세요.") }
                    else {
                        val institution = kr.mom.probe.data.NoticeGrouping.institution(settings)
                        val sourceNotificationId = kr.mom.probe.data.NoticeGrouping.groupId(record, institution)
                        val recordKeys = kr.mom.probe.data.NoticeGrouping.keys(record, institution)
                        DetailScreen(record, assistantTasks.any { task ->
                            task.sourceNotificationId == sourceNotificationId ||
                                kr.mom.probe.data.NoticeGrouping.matches(
                                    recordKeys,
                                    task.noticeGroupKeys + listOfNotNull(task.sourceNotificationId),
                                )
                        }, kr.mom.probe.data.NoticeDecisionEngine.childProfile(settings), ::back, { deleteTarget = record.id }, {
                        val intent = context.packageManager.getLaunchIntentForPackage(record.packageName)
                        if (intent == null) message = "원래 앱을 찾지 못했어요. 휴대폰에서 직접 확인해주세요."
                        else try { context.startActivity(intent) } catch (_: Exception) { message = "원래 앱을 열지 못했어요. 직접 확인해주세요." }
                    }, onRemember = { taskText, dueAt, remindAt ->
                        val request = PendingTaskSave(taskText, sourceNotificationId, record.id, dueAt, remindAt, recordKeys)
                        if (remindAt != null && !kr.mom.probe.task.TaskReminderScheduler.canDeliver(context)) {
                            pendingTaskSave = request
                            pendingNotificationTest = false
                            pendingAssistantAlertEnable = false
                            if (!notificationsAllowed) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                message = "휴대폰 설정에서 ‘모모의 부탁 알림’을 켜면 자동으로 저장할게요."
                                context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, kr.mom.probe.task.TaskReminderScheduler.CHANNEL))
                            }
                        } else {
                            saveAssistantTask(request)
                        }
                    })
                    }
                }
                "settings" -> SettingsScreen(settings, access, connected, records.size, busy,
                    { previous = "settings"; screen = "connections" }, { previous = "settings"; editingChild = true; screen = "child" },
                    { enabled -> if (enabled && (!access || settings.childName.isBlank() || settings.selectedPackages.isEmpty())) proceedSetup() else command({ repository.setCollectionEnabled(enabled) }) },
                    { previous = "settings"; pendingAccessReturn = false; openAccess() },
                    { pendingTaskSave = null; pendingAssistantAlertEnable = false; pendingNotificationTest = true; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    { deleteTarget = "ALL" }, { policy = true },
                    onReminders = { context.startActivity(Intent(context, kr.mom.probe.reminder.BriefingSettingsActivity::class.java)) },
                    onWidget = {
                        if (!kr.mom.probe.widget.AssistantWidgetProvider.requestPin(context))
                            message = "홈 화면을 길게 눌러 위젯에서 ‘나는 엄마다’를 선택해주세요."
                    },
                    assistantAlertsEnabled = assistantAlertsEnabled && notificationsAllowed,
                    onAssistantAlerts = { enabled ->
                        if (enabled && !notificationsAllowed) {
                            pendingNotificationTest = false
                            pendingAssistantAlertEnable = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            assistantAlertsEnabled = kr.mom.probe.reminder.AssistantAlertNotifier.setEnabled(context, enabled)
                            message = if (assistantAlertsEnabled == enabled) "중요 후보 바로 알림 설정을 바꿨어요." else "비서 알림 설정을 저장하지 못했어요."
                        }
                    })
                "export" -> ExportScreen(session.exportSnapshot, settings.childName, busy, ::back) { payload, format, ids ->
                    session.exportPayload = payload; session.exportIds = ids
                    saveFile.launch("mom-probe-reviewed-${System.currentTimeMillis()}.$format")
                }
            }
        }
    }
    if (policy) AlertDialog(onDismissRequest = { policy = false }, title = { Text("엄마가 정하는 데이터") },
        text = { PolicyContent(if (settings.consent) ({ policy = false; deleteTarget = "ALL" }) else null) }, confirmButton = { TextButton(onClick = { policy = false }) { Text("확인했어요") } })
    if (showAccessRationale && pendingAppPackage != null) {
        val appName = SourceCatalog.label(pendingAppPackage!!)
        AlertDialog(
            onDismissRequest = { showAccessRationale = false; pendingAppPackage = null },
            title = { Text("$appName 알림을 제가 챙길까요?") },
            text = { Text("Android 설정은 모든 알림에 접근한다고 표시하지만, 나는 엄마다는 엄마가 켠 앱의 새 알림만 이 기기에 저장해요. 허용한 뒤 뒤로가기로 돌아오면 자동으로 확인해요.") },
            confirmButton = { TextButton(onClick = { showAccessRationale = false; openAccess() }) { Text("허용하러 가기") } },
            dismissButton = { TextButton(onClick = { showAccessRationale = false; pendingAppPackage = null }) { Text("취소") } },
        )
    }
    if (appLoginPackage != null) {
        val packageName = appLoginPackage!!
        val appName = SourceCatalog.label(packageName)
        AlertDialog(
            onDismissRequest = { appLoginPackage = null },
            title = { Text("$appName 로그인을 확인해주세요") },
            text = { Text("처음 연결할 때 해당 앱에 로그인되어 있어야 새 알림을 받을 수 있어요. 새 알림을 받으면 수신 이력을 표시해요. 로그인 상태 자체는 확인할 수 없어요.") },
            confirmButton = { TextButton(onClick = {
                appLoginPackage = null
                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launch == null) message = "$appName 앱을 열지 못했어요."
                else try { context.startActivity(launch) } catch (_: Exception) { message = "$appName 앱을 열지 못했어요." }
            }) { Text("앱 열기") } },
            dismissButton = { TextButton(onClick = {
                appLoginPackage = null
                message = "첫 새 알림이 오면 연결 상태를 확인할게요."
            }) { Text("이미 로그인했어요") } },
        )
    }
    if (resetWebsites) AlertDialog(
        onDismissRequest = { if (!busy) resetWebsites = false },
        title = { Text("웹 로그인 데이터를 지울까요?") },
        text = { Text("이 시험 버전은 웹 저장소를 함께 사용해 e알리미·하이클래스 웹 로그인을 모두 지워요. 원래 앱의 로그인은 유지돼요.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            command({
                val cleared = WebsiteSessionManager.clearAll() && connectorRepository.disconnectWebsites()
                if (cleared) {
                    SourceSyncScheduler.cancel(context, SourceIds.EALIMI_WEB)
                    suspendAutomaticTasksForSources(setOf(SourceIds.EALIMI_WEB))
                    sourceStateStore.bumpGeneration(SourceIds.EALIMI_WEB)
                    repository.clearSourceRecords(setOf(SourceIds.EALIMI_WEB))
                }
                cleared
            }) {
                refreshSourceSnapshots()
                resetWebsites = false; message = "이 앱의 웹 로그인 데이터를 지웠어요."
            }
        }) { Text("웹 데이터 삭제") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { resetWebsites = false }) { Text("취소") } },
    )
    if (deleteTarget != null) {
        val all = deleteTarget == "ALL"
        AlertDialog(onDismissRequest = { if (!busy) deleteTarget = null }, title = { Text(if (all) "참여를 종료하고 모두 지울까요?" else "이 알림을 삭제할까요?") },
            text = { Text(if (all) "아이 이름, 선택한 앱, 모아둔 알림, 동의와 암호화 키를 모두 삭제해요. 새 수집도 멈춰요. 되돌릴 수 없으며 외부에 저장한 파일과 캘린더 일정은 따로 관리해야 해요." else "이 휴대폰에서 해당 알림을 영구 삭제해요. 되돌릴 수 없어요.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val target = deleteTarget!!
                command({ if (all) {
                    var deleted = true
                    runCatching { context.getSystemService(NotificationManager::class.java).cancelAll() }.onFailure { deleted = false }
                    runCatching { repository.beginReset() }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    // deleteAll closes the in-memory capture gate before its first fallible disk operation.
                    runCatching { repository.deleteAll() }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.reminder.BriefingReminders.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.reminder.AssistantAlertNotifier.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.calendar.CalendarPreferences.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.calendar.CalendarAppPreferences.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.calendar.CalendarCommandStore.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { kr.mom.probe.reminder.ExternalAlarmGateway.reset(context) }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { withContext(Dispatchers.IO) { kr.mom.probe.task.AssistantTaskStore.reset(context) } }.onFailure { deleted = false }
                    runCatching { WebsiteSessionManager.clearAll() }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { connectorRepository.deleteAll() }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    runCatching { SourceSyncScheduler.cancelAll(context) }.onFailure { deleted = false }
                    runCatching { sourceStateStore.reset() }.onSuccess { if (!it) deleted = false }.onFailure { deleted = false }
                    deleted
                } else {
                    records.find { it.id == target }?.let { kr.mom.probe.reminder.AssistantAlertNotifier.cancel(context, it) }
                    repository.deleteRecord(target)
                } }) {
                    deleteTarget = null; session.clearExport()
                    screen = if (all) "welcome" else previous
                    message = if (all) "이 기기의 자료를 모두 삭제했어요." else "알림을 삭제했어요."
                }
            }) { Text(if (busy) "삭제 중…" else "삭제", color = Clay.Error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("취소") } })
    }
}
