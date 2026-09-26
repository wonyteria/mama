package kr.mom.probe.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import java.io.File
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.NotificationCandidateParser
import kr.mom.probe.connector.ConnectorState
import kr.mom.probe.sync.SourceAgendaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Renders production composables using Android native graphics, without opening the
 * repository, Keystore, notification listener, or an external app. These screenshots
 * are UI evidence only; fixture source apps do not prove real package discovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "ko-rKR-w412dp-h892dp-xhdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProbeScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val NOW = System.currentTimeMillis()

    @Composable
    private fun today(
        settings: ProbeSettings = ProbeSettings(consent = true, onboardingDone = true),
        tasks: List<kr.mom.probe.task.AssistantTask> = emptyList(),
        records: List<ProbeRecord> = emptyList(),
        unreadCount: Int = records.size,
        configured: Boolean = true,
        notificationsAllowed: Boolean = true,
        sourceAgenda: List<SourceAgendaItem> = emptyList(),
        sourceStatusMessage: String? = null,
        busy: Boolean = false,
        onSetup: () -> Unit = {},
        onRecord: (ProbeRecord) -> Unit = {},
        onOpenTodo: () -> Unit = {},
        onOpenNews: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        TodayScreen(
            settings = settings,
            tasks = tasks,
            records = records,
            unreadCount = unreadCount,
            configured = configured,
            notificationsAllowed = notificationsAllowed,
            sourceAgenda = sourceAgenda,
            sourceStatusMessage = sourceStatusMessage,
            busy = busy,
            now = NOW,
            onSetup = onSetup,
            onToggle = {},
            onToggleItem = { _, _ -> },
            onSnooze = { _, _ -> },
            onEdit = { _, _, _, _ -> },
            onExclude = {},
            onOpenTodo = onOpenTodo,
            onOpenNews = onOpenNews,
            onOpenSettings = onOpenSettings,
            onRecord = onRecord,
            onEnableNotifications = {},
        )
    }

    @Test
    fun todayWithoutSetupShowsRecoveryAndNoInventedRecords() {
        var setupRequests = 0
        render {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "home", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    today(configured = false, onSetup = { setupRequests++ })
                }
            }
        }
        compose.onNodeWithText("마지막 준비를 도와드릴게요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("resume-setup").assertIsEnabled()
        screenshot("today-setup")
        compose.onNodeWithTag("resume-setup").performClick()
        compose.runOnIdle { assertEquals(1, setupRequests) }
        compose.onNodeWithText("마지막 준비를 도와드릴게요").assertIsDisplayed()
    }

    @Test
    fun todayShowsOpenTodoCountAndListLink() {
        var todoOpens = 0
        render {
            today(
                tasks = listOf(
                    task(id = "t1", text = "체험학습 도시락 싸기"),
                    task(id = "t2", text = "가정통신문 회신", completed = true),
                ),
                onOpenTodo = { todoOpens++ },
            )
        }
        compose.onNodeWithText("남은 할 일\n1개", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("체험학습 도시락 싸기").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("가정통신문 회신").assertCountEquals(0)
        compose.onNodeWithTag("open-todo-all").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, todoOpens) }
        screenshot("today-open-count")
    }

    @Test
    fun todoShowsChecklistProgress() {
        render {
            TodoScreen(
                tasks = listOf(
                    task(
                        id = "prep-1",
                        text = "현장체험학습 준비물",
                        checklist = listOf(
                            kr.mom.probe.task.TaskChecklistItem("c1", "도시락", done = true),
                            kr.mom.probe.task.TaskChecklistItem("c2", "물통", done = true),
                            kr.mom.probe.task.TaskChecklistItem("c3", "모자"),
                            kr.mom.probe.task.TaskChecklistItem("c4", "돗자리"),
                        ),
                    ),
                ),
                busy = false,
                now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("2/4 준비").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("할 일 1개").assertIsDisplayed()
        screenshot("todo-checklist-progress")
    }

    @Test
    fun todoShowsEvidenceAndRevisionDifference() {
        render {
            TodoScreen(
                tasks = listOf(
                    kr.mom.probe.task.AssistantTask(
                        id = "evidence-1",
                        text = "동의서 월요일 제출",
                        completed = false,
                        createdAt = NOW,
                        sourceNotificationId = "group-1",
                        sourceRevisionId = "revision-2",
                        sourceKind = kr.mom.probe.task.AssistantTaskSource.AUTO_NOTICE,
                        actionKind = "submit",
                        evidenceText = "동의서는 월요일까지 제출해 주세요.",
                        originalEvidenceText = "동의서는 금요일까지 제출해 주세요.",
                        sourceTitle = "동의서 제출일 정정",
                        sourceLabel = "학교 앱",
                        sourceCapturedAt = NOW,
                        audienceLabel = "초등 2학년",
                        revisionSummary = "기한 · 근거 문구 변경",
                    ),
                ),
                busy = false,
                now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }

        compose.onNodeWithText("동의서 월요일 제출").performClick()
        compose.onNodeWithText("근거 보기").assertIsDisplayed().performClick()
        compose.onNodeWithText("공지 수정 반영").assertIsDisplayed()
        compose.onNodeWithText("동의서는 금요일까지 제출해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("동의서는 월요일까지 제출해 주세요.").assertIsDisplayed()
    }

    @Test
    fun todoCompletedTasksFoldBehindToggle() {
        render {
            TodoScreen(
                tasks = listOf(
                    task(id = "done-1", text = "끝낸 일", completed = true),
                ),
                busy = false,
                now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("할 일 0개").assertIsDisplayed()
        compose.onNodeWithText("완료한 일 1개 보기").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("끝낸 일").assertIsDisplayed()
    }

    @Test
    fun addTaskDialogStaysOpenAndKeepsInputWhenSaveFails() {
        // autoAdvance off: the focused field's cursor blink holds a frame awaiter
        // forever, so once the dialog is open no waitForIdle-based API can be used.
        // Every interaction below goes through the compose root registry and direct
        // semantics action invocation instead.
        compose.mainClock.autoAdvance = false
        var attempts = 0
        render {
            TodoScreen(
                tasks = emptyList(),
                busy = false,
                now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {},
                // Injected save failure: the first attempt reports false, the retry true.
                onAddTask = { _, _, done -> done(attempts++ > 0) },
            )
        }
        assertNotNull(nodeByTag("add-task"))
        invokeOnClick("add-task")
        // Dialog.show() is posted to the PAUSED main looper: pump it (plus the test
        // clock) manually until the dialog root composes. Once it is open, the
        // focused field's cursor blink pins every waitForIdle-based API forever,
        // so all later steps use direct semantics action invocation instead.
        awaitDialog("task-edit-text")

        compose.runOnUiThread {
            val field = nodeByTag("task-edit-text")!!
            @Suppress("UNCHECKED_CAST")
            val setText = field.config[SemanticsActions.SetText]!!.action as (AnnotatedString) -> Boolean
            setText(AnnotatedString("도시락 싸기"))
        }
        pump()
        invokeOnClick("task-save")
        pump()

        // Failed save keeps the dialog, the typed text, and shows a retryable error.
        val fieldAfter = nodeByTag("task-edit-text")
        assertNotNull(fieldAfter)
        assertEquals("도시락 싸기", fieldAfter!!.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text)
        assertNotNull(nodeByText("저장하지 못했어요. 다시 시도해주세요."))
        assertEquals(1, attempts)

        invokeOnClick("task-save")
        pump()
        assertEquals(2, attempts)
        org.junit.Assert.assertNull(nodeByTag("task-edit-text"))
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap { flatten(it) }

    private fun allNodes(): List<SemanticsNode> {
        val env = compose.javaClass.getDeclaredField("environment").apply { isAccessible = true }.get(compose)
        val registry = env.javaClass.getMethod("getComposeRootRegistry\$ui_test_release").invoke(env)
        @Suppress("UNCHECKED_CAST")
        val roots = (
            registry.javaClass.getMethod("getRegisteredComposeRoots").invoke(registry)
                as Set<androidx.compose.ui.platform.ViewRootForTest>
            ) + (
            registry.javaClass.getMethod("getCreatedComposeRoots").invoke(registry)
                as Set<androidx.compose.ui.platform.ViewRootForTest>
            )
        return roots.flatMap { flatten(it.semanticsOwner.rootSemanticsNode) }
    }

    private fun nodeByTag(tag: String): SemanticsNode? = compose.runOnUiThread {
        allNodes().firstOrNull {
            it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == tag
        }
    }

    private fun nodeByText(text: String): SemanticsNode? = compose.runOnUiThread {
        allNodes().firstOrNull {
            it.config.getOrElseNullable(SemanticsProperties.Text) { null }
                ?.any { item -> item.text == text } == true
        }
    }

    private fun invokeOnClick(tag: String) = compose.runOnUiThread {
        nodeByTag(tag)!!.config[SemanticsActions.OnClick]!!.action!!.invoke()
    }

    private fun pump() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun awaitDialog(tag: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (nodeByTag(tag) == null) {
            if (System.currentTimeMillis() > deadline) org.junit.Assert.fail("dialog node $tag never appeared")
            pump()
        }
    }

    @Test
    fun uncertainNoticeOffersExplicitParentConfirmInsteadOfAutoTask() {
        // A bare grade with no school-level marker makes the audience UNKNOWN, so
        // the engine builds no action — the user must still be able to confirm
        // the target themselves and create an evidence-linked task.
        val record = candidateRecord().copy(
            title = "3학년 체험학습 준비물",
            text = "3학년 체험학습 준비물을 챙겨주세요.",
        )
        render {
            DetailScreen(record, onBack = {}, onDelete = {}, onSource = {})
        }

        compose.onNodeWithText("엄마 확인 필요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("확인하고 할 일로 추가").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun newsSeparatesReadStateFromTodoCompletion() {
        val record = candidateRecord()
        val readIds = androidx.compose.runtime.mutableStateOf(emptySet<String>())
        render {
            NewsScreen(
                records = listOf(record),
                readIds = readIds.value,
                onRecord = {},
                onExport = {},
            )
        }
        compose.onNodeWithTag("unread-dot", useUnmergedTree = true).assertExists()
        screenshot("news-unread")
        compose.runOnIdle { readIds.value = setOf(record.id) }
        compose.onAllNodesWithTag("unread-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun connectionsShowAppsOnlyWhenTheSameServiceAlsoHasAWebsite() {
        render {
            ConnectionsScreen(
                settings = ProbeSettings(
                    consent = true,
                    childName = "QA",
                    schoolName = "성남정자초등학교",
                    schoolGrade = 2,
                    onboardingDone = true,
                ),
                installedApps = listOf(
                    SourceApp("com.ewut.allealimi", "e알리미", "학교 소식", "e"),
                    SourceApp("com.iscreammedia.app.hiclass.android", "하이클래스", "학교 소식", "Hi"),
                ),
                missingApps = emptyList(),
                verifiedAppPackages = emptySet(),
                connectorState = ConnectorState(),
                busy = false,
                onBack = {},
                onToggleApp = { _, _ -> },
                onConnectNeis = {},
                onDisconnectNeis = {},
                onRecommendApp = {},
            )
        }

        compose.onNodeWithText("e알리미").assertIsDisplayed()
        compose.onNodeWithText("하이클래스").assertIsDisplayed()
        compose.onAllNodesWithText("e알리미 웹").assertCountEquals(0)
        compose.onAllNodesWithText("하이클래스 웹").assertCountEquals(0)
        compose.onAllNodesWithText("e알리미 사이트 열기").assertCountEquals(0)
        compose.onAllNodesWithText("하이클래스 사이트 열기").assertCountEquals(0)
        compose.onAllNodesWithText("정리되면 원본 알림 숨기기").assertCountEquals(0)
        screenshot("connections-apps-only")
    }

    @Test
    fun connectionsStillShowsSchoolWebsiteRowWhenUnsupported() {
        render {
            ConnectionsScreen(
                settings = ProbeSettings(
                    consent = true,
                    childName = "QA",
                    schoolName = "다른초등학교",
                    schoolGrade = 2,
                    onboardingDone = true,
                ),
                installedApps = emptyList(),
                missingApps = emptyList(),
                verifiedAppPackages = emptySet(),
                connectorState = ConnectorState(),
                busy = false,
                onBack = {},
                onToggleApp = { _, _ -> },
                onConnectNeis = {},
                onDisconnectNeis = {},
                onRecommendApp = {},
            )
        }

        // An unsupported school keeps its row and explains why instead of vanishing.
        compose.onNodeWithText("학교 공식 홈페이지").assertIsDisplayed()
        compose.onNodeWithText("지금은 확인된 학교 홈페이지만 연결해요").assertIsDisplayed()
    }

    @Test
    fun todayShowsUnreadNewsCount() {
        render {
            today(unreadCount = 3)
        }
        compose.onNodeWithText("새 소식").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3개").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("읽지 않은 소식").performScrollTo().assertIsDisplayed()
    }


    @Test
    fun todayScheduleUsesStoredAgenda() {
        val sourceRecord = candidateRecord().copy(id = "source-agenda-record", title = "QA 학교 일정", appLabel = "성남정자초 공식 홈페이지")
        render {
            today(
                unreadCount = 0,
                sourceAgenda = listOf(SourceAgendaItem(sourceRecord, "Stored agenda", "2026-09-16", "성남정자초 공식 홈페이지")),
            )
        }

        compose.onNodeWithText("1개").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("09월 16일 Stored agenda").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun todayShowsEmptyAgendaInsteadOfInventedSchedule() {
        render {
            today(unreadCount = 0, sourceAgenda = emptyList())
        }

        compose.onAllNodesWithText("0개", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("저장된 학교 일정 없음").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun welcomeRequiresConsentBeforeStarting() {
        var starts = 0
        render { WelcomeScreen(busy = false, onStart = { starts++ }, onPolicy = {}) }
        screenshot("welcome")
        compose.onNodeWithTag("start").performScrollTo().assertIsNotEnabled()
        val consent = compose.onNodeWithText(
            "설명을 확인했고, 이 기기에서 알림을 모아 챙길 후보를 찾는 데 동의해요. (필수)",
        )
        consent.performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("start").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun sourceSelectionSavesOnlyUserChosenFixture() {
        var saved: Set<String>? = null
        val fixtureApps = listOf(
            SourceApp("test.fixture.school", "테스트 학교 앱", "화면 테스트용 · 설치 확인 아님", "학"),
            SourceApp("test.fixture.academy", "테스트 학원 앱", "화면 테스트용 · 설치 확인 아님", "원"),
        )
        render {
            SourcesScreen(
                apps = fixtureApps,
                initial = emptySet(),
                busy = false,
                onSave = { saved = it },
                onBack = {},
                onSkip = {},
                onRefresh = {},
            )
        }
        compose.onNodeWithTag("save-sources").assertIsNotEnabled()
        compose.onNodeWithText("테스트 학교 앱").performClick().assertIsOn()
        screenshot("sources-fixture")
        compose.onNodeWithTag("save-sources").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("test.fixture.school"), saved) }
    }

    @Test
    fun childScreenStartsWithEmptyDisabledInput() {
        render { ChildScreen(initial = "", busy = false, onSave = {}, onBack = {}) }
        compose.onNodeWithTag("child-name").assertIsDisplayed()
        compose.onNodeWithTag("save-child").assertIsNotEnabled()
        screenshot("child")
    }

    @Test
    @Config(qualifiers = "ko-rKR-w320dp-h640dp-xhdpi")
    fun childRemainsReachableAtSmallWidthAndLargeText() {
        var savedName: String? = null
        render(fontScale = 1.5f) {
            ChildScreen(initial = "", busy = false, onSave = { savedName = it }, onBack = {})
        }
        val input = compose.onNodeWithTag("child-name")
        val next = compose.onNodeWithTag("save-child")
        next.performScrollTo().assertIsNotEnabled()
        input.performScrollTo().performTextReplacement("   ")
        next.performScrollTo().assertIsNotEnabled()
        input.performScrollTo().performTextReplacement("가".repeat(21))
        next.performScrollTo().assertIsNotEnabled()
        input.performScrollTo().performTextReplacement("  우리 아이  ")
        input.assertTextContains("  우리 아이  ")
        next.performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("child-small-large-text")
        next.performClick()
        compose.runOnIdle { assertEquals("우리 아이", savedName) }
    }

    @Test
    fun todaySurfacesActionCandidateForReview() {
        val record = candidateRecord()
        var opened: String? = null
        render {
            today(
                records = listOf(record),
                onRecord = { opened = it.id },
            )
        }
        compose.onNodeWithText("확인할 소식").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("체험학습 준비물").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(record.id, opened) }
        screenshot("today-review-candidate")
    }

    @Test
    fun detailConvertsCandidateToRememberedTaskOnce() {
        val record = candidateRecord()
        render {
            DetailScreen(
                record = record,
                onBack = {}, onDelete = {}, onSource = {},
                onRemember = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("날짜 확인하고 할 일로 추가").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            val task = candidateTaskText(record, NotificationCandidateParser.parse(record)!!)
            assertTrue(task.contains("체험학습 준비물"))
            assertTrue(task.contains("도시락, 물통"))
        }
        screenshot("detail-agent-action")
    }

    @Test
    fun todaySkipsCandidateAlreadySavedAsTask() {
        val record = candidateRecord()
        render {
            today(
                tasks = listOf(
                    task(
                        id = "linked-1",
                        text = "체험학습 준비물 챙기기",
                        sourceNotificationId = ProbeRules.recordIdentity(record),
                    ),
                ),
                records = listOf(record),
            )
        }
        compose.onAllNodesWithText("확인할 소식").assertCountEquals(0)
    }

    @Test
    fun todayGroupsUpdatedRevisionsOfSameNotification() {
        val first = candidateRecord()
        val updated = first.copy(id = "candidate-2", rawHash = "updated", receivedAt = first.receivedAt + 1)
        render {
            today(
                records = listOf(updated, first),
            )
        }
        compose.onNodeWithText("확인할 소식").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("체험학습 준비물").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "ko-rKR-w320dp-h640dp-xhdpi")
    fun todayRemainsUsableAtSmallWidthAndLargeText() {
        val record = candidateRecord()
        render(fontScale = 1.5f) {
            today(
                tasks = listOf(task(id = "t1", text = "체험학습 도시락 싸기")),
                records = listOf(record),
            )
        }
        compose.onNodeWithTag("open-todo-all").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("확인할 소식").performScrollTo().assertIsDisplayed()
        screenshot("today-large-text")
    }

    private fun candidateRecord() = ProbeRecord(
        id = "candidate-1", packageName = "school.app", appLabel = "학교 앱",
        postedAt = System.currentTimeMillis(), receivedAt = System.currentTimeMillis(),
        title = "체험학습 준비물", text = "준비물: 도시락, 물통. 내일 오전 9시까지",
        bigText = "", textLines = emptyList(), subText = null, summaryText = null,
        category = null, channelId = null, notificationId = 1, notificationKey = "key",
        isOngoing = false, isGroupSummary = false, rawHash = "hash",
    )

    private fun task(
        id: String,
        text: String,
        completed: Boolean = false,
        sourceNotificationId: String? = null,
        checklist: List<kr.mom.probe.task.TaskChecklistItem> = emptyList(),
    ) = kr.mom.probe.task.AssistantTask(
        id = id,
        text = text,
        completed = completed,
        createdAt = NOW,
        sourceNotificationId = sourceNotificationId,
        checklist = checklist,
    )

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MomTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Clay.Background) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        val file = File(directory, "$name.png")
        // Compose captureToImage uses PixelCopy, whose platform callback does not
        // complete in this Windows Robolectric runtime. Draw the actual Activity
        // view tree to a native Android Canvas instead; no HTML or replica UI.
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue("Activity must have a laid-out viewport", decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        assertTrue("Screenshot must have a non-empty viewport", bitmap.width > 0 && bitmap.height > 0)
        // A successful file write alone is not evidence of a rendered screen.
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Activity capture must contain drawn UI", pixels.any { it != pixels[0] })
        file.outputStream().use { output ->
            assertTrue("PNG encoder failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue("PNG was not written: ${file.absolutePath}", file.length() > 0)
    }
}
