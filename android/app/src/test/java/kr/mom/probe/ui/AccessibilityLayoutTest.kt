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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kr.mom.probe.connector.ConnectorState
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.reminder.AlarmContent
import kr.mom.probe.reminder.AlarmContentState
import kr.mom.probe.sync.SourceAgendaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Regression coverage for the smallest supported form factors: 360dp wide portrait,
 * 200% font scale, and landscape orientation. Every key action must stay reachable,
 * every clickable element must carry an accessible name, a 48dp touch target, and a
 * top-to-bottom traversal order that matches the visual layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "ko-rKR-w360dp-h780dp-xhdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val NOW = System.currentTimeMillis()

    @Test
    fun welcomeKeepsConsentAndStartReachable() {
        render(fontScale = 2f) { WelcomeScreen(busy = false, onStart = {}, onPolicy = {}) }
        compose.onNodeWithText("수집·보관·삭제 설명 보기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("start").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("설명을 확인했고", substring = true).performScrollTo().performClick()
        compose.onNodeWithTag("start").assertIsEnabled()
        assertAccessibleControls()
    }

    @Test
    fun childInputStaysUsable() {
        var saved: String? = null
        render(fontScale = 2f) { ChildScreen(initial = "", busy = false, onSave = { saved = it }, onBack = {}) }
        compose.onNodeWithTag("child-name").performScrollTo().performTextReplacement("우리 아이")
        compose.onNodeWithTag("save-child").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-child-360-2x")
        compose.onNodeWithTag("save-child").performClick()
        compose.runOnIdle { assertEquals("우리 아이", saved) }
        assertAccessibleControls()
    }

    @Test
    fun childProfileStaysUsable() {
        render(fontScale = 2f) {
            ChildProfileScreen(
                settings = ProbeSettings(childName = "우리 아이", schoolName = "성남정자초등학교", schoolGrade = 2),
                busy = false, onSave = { _, _, _, _ -> }, onBack = {},
            )
        }
        compose.onNodeWithText("아이 이름 또는 별칭").performScrollTo().assertIsDisplayed()
        // schoolName infers ELEMENTARY, so the level selector shows its label.
        compose.onNodeWithText("초등학교", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("a11y-child-profile-360-2x")
        assertAccessibleControls()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w892dp-h411dp-land-xhdpi")
    fun childProfileLandscapeStaysUsable() {
        render {
            ChildProfileScreen(
                settings = ProbeSettings(childName = "우리 아이"),
                busy = false, onSave = { _, _, _, _ -> }, onBack = {},
            )
        }
        compose.onNodeWithText("아이 이름 또는 별칭").assertIsDisplayed()
        screenshot("a11y-child-profile-landscape")
        assertAccessibleControls()
    }

    @Test
    fun sourceSelectionStaysUsable() {
        val apps = listOf(
            SourceApp("test.fixture.school", "테스트 학교 앱", "화면 테스트용 · 설치 확인 아님", "학"),
            SourceApp("test.fixture.academy", "테스트 학원 앱", "화면 테스트용 · 설치 확인 아님", "원"),
        )
        render(fontScale = 2f) {
            SourcesScreen(apps = apps, initial = emptySet(), busy = false,
                onSave = {}, onBack = {}, onSkip = {}, onRefresh = {})
        }
        compose.onNodeWithText("테스트 학교 앱").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("save-sources").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("나중에 설정할게요").performScrollTo().assertIsDisplayed()
        screenshot("a11y-sources-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun accessScreenKeepsSettingsButtonReachable() {
        render(fontScale = 2f) {
            AccessScreen(labels = listOf("테스트 학교 앱"), busy = false, onOpen = {}, onSkip = {}, onBack = {})
        }
        compose.onNodeWithTag("open-access").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("나중에 설정할게요").performScrollTo().assertIsDisplayed()
        screenshot("a11y-access-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun todayKeepsSummaryAndActionsReachable() {
        render(fontScale = 2f) {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "home", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    today(
                        tasks = listOf(task(id = "t1", text = "체험학습 도시락 싸기")),
                        records = listOf(candidateRecord()),
                        unreadCount = 2,
                    )
                }
            }
        }
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
        compose.onNodeWithTag("open-todo-all").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("체험학습 준비물").performScrollTo().assertIsDisplayed()
        screenshot("a11y-today-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun todoKeepsAddAndTaskControlsReachable() {
        render(fontScale = 2f) {
            TodoScreen(
                tasks = listOf(
                    task(
                        id = "prep-1",
                        text = "현장체험학습 준비물",
                        checklist = listOf(
                            kr.mom.probe.task.TaskChecklistItem("c1", "도시락", done = true),
                            kr.mom.probe.task.TaskChecklistItem("c2", "물통"),
                        ),
                    ),
                ),
                busy = false, now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithTag("add-task").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("task-check-prep-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("현장체험학습 준비물").performScrollTo().assertIsDisplayed()
        screenshot("a11y-todo-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun todoNeedsReviewTaskStaysReachable() {
        render(fontScale = 2f) {
            TodoScreen(
                tasks = listOf(
                    kr.mom.probe.task.AssistantTask(
                        id = "review-1", text = "동의서 금요일 제출", completed = false,
                        createdAt = NOW, dueAt = NOW + 86_400_000L, remindAt = NOW + 86_000_000L,
                        sourceNotificationId = "group-1", sourceRevisionId = "r3",
                        sourceKind = kr.mom.probe.task.AssistantTaskSource.AUTO_NOTICE,
                        needsReview = true, revisionSummary = "근거 문구 변경",
                        evidenceText = "동의서는 금요일까지 제출해 주세요.",
                    ),
                ),
                busy = false, now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("동의서 금요일 제출").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("수정 공지 확인 필요", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("동의서 금요일 제출").performScrollTo().performClick()
        compose.onNodeWithText("근거 보기").assertIsDisplayed()
        screenshot("a11y-todo-needs-review-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun detailUncertainNoticeKeepsConfirmReachable() {
        // A bare grade without a school-level context produces UNKNOWN
        // applicability, so the explicit parent-confirm card must stay usable.
        val uncertain = candidateRecord().copy(
            title = "가정통신문", text = "3학년 학부모님께 안내드립니다.",
        )
        render(fontScale = 2f) {
            DetailScreen(
                record = uncertain,
                onBack = {}, onDelete = {}, onSource = {},
                onRemember = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("엄마 확인 필요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("확인하고 할 일로 추가").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-detail-uncertain-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun todoEvidenceDialogStaysScrollable() {
        render(fontScale = 2f) {
            TodoScreen(
                tasks = listOf(
                    kr.mom.probe.task.AssistantTask(
                        id = "evidence-1", text = "동의서 월요일 제출", completed = false,
                        createdAt = NOW, sourceNotificationId = "group-1", sourceRevisionId = "r2",
                        sourceKind = kr.mom.probe.task.AssistantTaskSource.AUTO_NOTICE, actionKind = "submit",
                        evidenceText = "동의서는 월요일까지 제출해 주세요.",
                        originalEvidenceText = "동의서는 금요일까지 제출해 주세요.",
                        sourceTitle = "동의서 제출일 정정", sourceLabel = "학교 앱",
                        sourceCapturedAt = NOW, audienceLabel = "초등 2학년",
                        revisionSummary = "기한 · 근거 문구 변경",
                    ),
                ),
                busy = false, now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("동의서 월요일 제출").performScrollTo().performClick()
        compose.onNodeWithText("근거 보기").assertIsDisplayed().performClick()
        compose.onNodeWithText("할 일 근거").assertIsDisplayed()
        compose.onNodeWithText("공지 수정 반영").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-todo-evidence-360-2x")
    }

    @Test
    fun connectionsKeepSwitchControlsUsable() {
        render(fontScale = 2f) {
            ConnectionsScreen(
                settings = ProbeSettings(
                    consent = true, childName = "QA", schoolName = "성남정자초등학교",
                    schoolGrade = 2, onboardingDone = true,
                ),
                installedApps = listOf(
                    SourceApp("com.ewut.allealimi", "e알리미", "학교 소식", "e"),
                ),
                missingApps = emptyList(), verifiedAppPackages = emptySet(),
                connectorState = ConnectorState(), busy = false,
                onBack = {}, onToggleApp = { _, _ -> }, onConnectNeis = {}, onDisconnectNeis = {},
                onRecommendApp = {},
            )
        }
        compose.onNodeWithText("e알리미").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("나이스 학교정보").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("현재 자동 조회 미지원", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("a11y-connections-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun detailKeepsEvidenceAndActionsReachable() {
        render(fontScale = 2f) {
            DetailScreen(
                record = candidateRecord(),
                onBack = {}, onDelete = {}, onSource = {},
                onRemember = { _, _, _ -> },
            )
        }
        compose.onNodeWithText("근거 확인").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("날짜 확인하고 할 일로 추가").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원래 앱에서 확인").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("이 알림 삭제").performScrollTo().assertIsDisplayed()
        screenshot("a11y-detail-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun alarmKeepsPrimaryControlsUsable() {
        render(fontScale = 2f) {
            AlarmContent(
                state = AlarmContentState(
                    title = "모모의 부탁", dateText = "9월 20일 일요일", scheduledTimeText = "07:00",
                    actionTitle = "등교 가방에 물티슈 넣기", actionSummary = "곧 챙길 일 1개",
                    showComplete = true, completeEnabled = true,
                ),
                onStopSound = {}, onSnoozeTenMinutes = {}, onShowDetails = {}, onComplete = {},
            )
        }
        compose.onNodeWithTag("alarm-time").assertIsDisplayed()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-complete").assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-alarm-360-2x")
        assertAccessibleControls()
    }

    @Test
    fun todaySourceErrorKeepsStatusHonest() {
        render(fontScale = 2f) {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "home", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    today(
                        sourceStatusMessage = "학교 앱 알림 연결에 실패했어요.",
                    )
                }
            }
        }
        compose.onNodeWithText("학교 앱 알림 연결에 실패했어요.", substring = true)
            .performScrollTo().assertIsDisplayed()
        screenshot("a11y-today-source-error-360-2x")
        assertAccessibleControls()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w892dp-h411dp-land-xhdpi")
    fun todayLandscapeKeepsControlsReachable() {
        render {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "home", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    today(tasks = listOf(task(id = "t1", text = "체험학습 도시락 싸기")))
                }
            }
        }
        compose.onNodeWithTag("open-todo-all").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-today-landscape")
        assertAccessibleControls()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w892dp-h411dp-land-xhdpi")
    fun todoLandscapeKeepsControlsReachable() {
        render {
            TodoScreen(
                tasks = listOf(task(id = "t1", text = "체험학습 도시락 싸기")),
                busy = false, now = NOW,
                onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
                onEdit = { _, _, _, _ -> }, onExclude = {}, onAddTask = { _, _, _ -> },
            )
        }
        compose.onNodeWithTag("add-task").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("체험학습 도시락 싸기").performScrollTo().assertIsDisplayed()
        screenshot("a11y-todo-landscape")
        assertAccessibleControls()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w892dp-h411dp-land-xhdpi")
    fun welcomeLandscapeKeepsStartReachable() {
        render {
            WelcomeScreen(busy = false, onStart = {}, onPolicy = {})
        }
        compose.onNodeWithText("설명을 확인했고", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("start").performScrollTo().assertIsDisplayed()
        screenshot("a11y-welcome-landscape")
        assertAccessibleControls()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w892dp-h411dp-land-xhdpi")
    fun alarmLandscapeKeepsControlsUsable() {
        render {
            AlarmContent(
                state = AlarmContentState(
                    title = "모모의 브리핑", dateText = "9월 20일 일요일", scheduledTimeText = "07:00",
                    actionTitle = "체험학습 준비물 · 내일 오전 9시", actionSummary = "곧 챙길 일 1개",
                    showComplete = false,
                ),
                onStopSound = {}, onSnoozeTenMinutes = {}, onShowDetails = {},
            )
        }
        compose.onNodeWithTag("alarm-time").assertIsDisplayed()
        compose.onNodeWithTag("alarm-stop").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-snooze").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("alarm-details").assertIsDisplayed().assertIsEnabled()
        screenshot("a11y-alarm-landscape")
        assertAccessibleControls()
    }

    @Test
    fun newsCardAnnouncesUnreadStateToTalkBack() {
        val record = candidateRecord()
        render {
            NewsScreen(records = listOf(record), readIds = emptySet(), onRecord = {}, onExport = {})
        }
        compose.onNodeWithText(record.title)
            .assert(expectValue(SemanticsProperties.StateDescription, "읽지 않은 소식"))
    }

    @Test
    fun bottomBarAnnouncesTheSelectedTab() {
        render {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "todo", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) }
        }
        compose.onNodeWithText("할 일").assert(expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("오늘").assert(expectValue(SemanticsProperties.Selected, false))
    }

    /**
     * Every clickable element must be announceable by TalkBack (role, state, text, or
     * content description), sized at least 48dp in both dimensions, and ordered in the
     * semantics tree the same way it appears on screen (top to bottom, left to right).
     */
    private fun assertAccessibleControls() {
        val initial = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("Expected at least one clickable element", initial.isNotEmpty())
        var previousTop = Float.NEGATIVE_INFINITY
        var previousLeft = Float.NEGATIVE_INFINITY
        initial.forEachIndexed { index, node ->
            // Scroll containers keep off-screen children in the semantics tree at
            // their pre-scroll positions; TalkBack reaches them by scrolling, so
            // only controls actually rendered in the viewport constrain order.
            val interaction = compose.onAllNodes(hasClickAction())[index]
            if (runCatching { interaction.assertIsDisplayed() }.isFailure) return@forEachIndexed
            val top = node.positionInRoot.y
            val left = node.positionInRoot.x
            // Reading order is strictly top-to-bottom, then left-to-right. A node
            // above the previous row, or to the left inside the same row, is a
            // backward jump TalkBack would announce out of order.
            val sameRow = kotlin.math.abs(top - previousTop) <= 2f
            assertTrue(
                "Traversal order does not follow the visual layout for ${describe(node)}",
                top > previousTop + 2f || (sameRow && left > previousLeft),
            )
            previousTop = top
            previousLeft = left
        }
        initial.indices.forEach { index ->
            val interaction = compose.onAllNodes(hasClickAction())[index]
            // Scrollable parents clip partially visible nodes; always try to scroll the
            // control fully into view before measuring its touch target.
            runCatching { interaction.performScrollTo() }
            val node = interaction.fetchSemanticsNode()
            assertTrue(
                "Clickable element is not rendered on screen: ${describe(node)}",
                node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f,
            )
            val label = describe(node)
            assertTrue("Clickable element has no accessible name: $label", announceable(node))
            val width = with(compose.density) { node.boundsInRoot.width.toDp() }
            val height = with(compose.density) { node.boundsInRoot.height.toDp() }
            assertTrue(
                "Touch target below 48dp for $label: ${width}x${height} at ${node.boundsInRoot}",
                width >= 48.dp && height >= 48.dp,
            )
        }
    }

    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?: node.config.getOrNull(SemanticsProperties.Text)
        return "text=$text role=${node.config.getOrNull(SemanticsProperties.Role)} pos=${node.positionInRoot}"
    }

    private fun announceable(node: SemanticsNode): Boolean {
        val config = node.config
        // A role or toggle state alone is not a name: TalkBack reads "버튼"/"스위치"
        // with nothing to identify what it does. Require text, a content
        // description, a state description, or a labelled progress range.
        return config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true ||
            config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
            config.getOrNull(SemanticsProperties.StateDescription)?.isNotBlank() == true ||
            config.contains(SemanticsProperties.ProgressBarRangeInfo)
    }

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
    ) {
        TodayScreen(
            settings = settings, tasks = tasks, records = records, unreadCount = unreadCount,
            configured = configured, notificationsAllowed = notificationsAllowed,
            sourceAgenda = sourceAgenda, sourceStatusMessage = sourceStatusMessage,
            busy = busy, now = NOW,
            onSetup = {}, onToggle = {}, onToggleItem = { _, _ -> }, onSnooze = { _, _ -> },
            onEdit = { _, _, _, _ -> }, onExclude = {}, onOpenTodo = {}, onOpenNews = {},
            onOpenSettings = {}, onRecord = {}, onEnableNotifications = {},
        )
    }

    private fun candidateRecord() = ProbeRecord(
        id = "candidate-1", packageName = "school.app", appLabel = "학교 앱",
        postedAt = NOW, receivedAt = NOW,
        title = "체험학습 준비물", text = "준비물: 도시락, 물통. 내일 오전 9시까지",
        bigText = "", textLines = emptyList(), subText = null, summaryText = null,
        category = null, channelId = null, notificationId = 1, notificationKey = "key",
        isOngoing = false, isGroupSummary = false, rawHash = "hash",
    )

    private fun task(
        id: String,
        text: String,
        completed: Boolean = false,
        checklist: List<kr.mom.probe.task.TaskChecklistItem> = emptyList(),
    ) = kr.mom.probe.task.AssistantTask(
        id = id, text = text, completed = completed, createdAt = NOW, checklist = checklist,
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
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue("Activity must have a laid-out viewport", decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        assertTrue("Screenshot must have a non-empty viewport", bitmap.width > 0 && bitmap.height > 0)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Activity capture must contain drawn UI", pixels.any { it != pixels[0] })
        file.outputStream().use { output ->
            assertTrue("PNG encoder failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue("PNG was not written: ${file.absolutePath}", file.length() > 0)
    }
}
