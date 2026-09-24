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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import java.io.File
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.NotificationCandidateParser
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

    @Test
    fun homeWithoutSetupShowsRecoveryAndNoInventedRecords() {
        var setupRequests = 0
        render {
            Scaffold(
                bottomBar = { ProbeBottomBar(current = "home", onSelect = {}) },
                containerColor = Clay.Background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    HomeScreen(
                        settings = ProbeSettings(consent = true, onboardingDone = true),
                        records = emptyList(),
                        access = false,
                        connected = false,
                        onSetup = { setupRequests++ },
                        onInbox = {},
                        onRecord = {},
                    )
                }
            }
        }
        compose.onNodeWithText("마지막 준비를 도와드릴게요").assertIsDisplayed()
        compose.onNodeWithTag("resume-setup").assertIsEnabled()
        screenshot("home-setup")
        compose.onNodeWithTag("resume-setup").performClick()
        compose.runOnIdle { assertEquals(1, setupRequests) }
        compose.onNodeWithText("마지막 준비를 도와드릴게요").assertIsDisplayed()
    }

    @Test
    fun homeAcceptsAWebsiteOnlyConnection() {
        render {
            HomeScreen(
                settings = ProbeSettings(consent = true, childName = "검증 아이", schoolName = "검증초등학교", schoolGrade = 2, onboardingDone = true),
                records = emptyList(), access = false, connected = false,
                onSetup = {}, onInbox = {}, onRecord = {}, connectedSiteCount = 1,
            )
        }
        compose.onAllNodesWithText("설정 이어하기").assertCountEquals(0)
        compose.onNodeWithText("연결한 곳 1개").performScrollTo().assertIsDisplayed()
    }


    @Test
    fun homeScheduleUsesStoredAgenda() {
        var agendaOpens = 0
        val sourceRecord = candidateRecord().copy(id = "source-agenda-record", title = "QA 학교 일정", appLabel = "성남정자초 공식 홈페이지")
        render {
            HomeScreen(
                settings = ProbeSettings(consent = true, childName = "QA", schoolName = "성남정자초등학교", schoolGrade = 2, onboardingDone = true),
                records = emptyList(), access = false, connected = false,
                onSetup = {}, onInbox = {}, onRecord = {}, connectedSiteCount = 1,
                sourceAgenda = listOf(SourceAgendaItem(sourceRecord, "Stored agenda", "2026-09-16", "성남정자초 공식 홈페이지")),
                onAgenda = { agendaOpens++ },
            )
        }

        compose.onNodeWithText("1개").assertIsDisplayed()
        compose.onNodeWithText("09월 16일 Stored agenda").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일정 보기").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, agendaOpens) }
    }

    @Test
    fun homeScheduleShowsEmptyStateWhenStoredAgendaIsEmpty() {
        render {
            HomeScreen(
                settings = ProbeSettings(consent = true, childName = "QA", schoolName = "성남정자초등학교", schoolGrade = 2, onboardingDone = true),
                records = emptyList(), access = false, connected = false,
                onSetup = {}, onInbox = {}, onRecord = {}, connectedSiteCount = 1,
                sourceAgenda = emptyList(),
            )
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
    fun homePrioritizesActionCandidateInAgentBento() {
        val record = candidateRecord()
        var opened: String? = null
        render {
            HomeScreen(
                settings = ProbeSettings(
                    consent = true, childName = "민서", selectedPackages = setOf("school.app"),
                    collectionEnabled = true, onboardingDone = true,
                ),
                records = listOf(record), access = true, connected = true,
                onSetup = {}, onInbox = {}, onRecord = { opened = it.id },
                pendingTaskCount = 0, briefingReady = true, onAssistant = {},
            )
        }
        compose.onNodeWithText("곧 챙길 일 1개").assertIsDisplayed()
        compose.onNodeWithText("내용 보기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(record.id, opened) }
        screenshot("home-agent-bento")
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
        compose.onNodeWithText("날짜 확인하고 모모에게 맡기기").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            val task = candidateTaskText(record, NotificationCandidateParser.parse(record)!!)
            assertTrue(task.contains("체험학습 준비물"))
            assertTrue(task.contains("도시락, 물통"))
        }
        screenshot("detail-agent-action")
    }

    @Test
    fun homeDoesNotCountRememberedCandidateTwice() {
        val record = candidateRecord()
        render {
            HomeScreen(
                settings = ProbeSettings(
                    consent = true, childName = "민서", selectedPackages = setOf("school.app"),
                    collectionEnabled = true, onboardingDone = true,
                ),
                records = listOf(record), access = true, connected = true,
                onSetup = {}, onInbox = {}, onRecord = {}, pendingTaskCount = 1,
                rememberedGroupKeys = setOf(NoticeGrouping.keys(record, NoticeGrouping.institution(ProbeSettings()))), onAssistant = {},
            )
        }
        compose.onNodeWithText("곧 챙길 일 1개").assertIsDisplayed()
        compose.onNodeWithText("부탁 확인하기").assertIsDisplayed()
    }

    @Test
    fun homeGroupsUpdatedRevisionsOfSameNotification() {
        val first = candidateRecord()
        val updated = first.copy(id = "candidate-2", rawHash = "updated", receivedAt = first.receivedAt + 1)
        render {
            HomeScreen(
                settings = ProbeSettings(
                    consent = true, childName = "민서", selectedPackages = setOf("school.app"),
                    collectionEnabled = true, onboardingDone = true,
                ),
                records = listOf(updated, first), access = true, connected = true,
                onSetup = {}, onInbox = {}, onRecord = {}, onAssistant = {},
            )
        }
        compose.onNodeWithText("곧 챙길 일 1개").assertIsDisplayed()
        compose.onNodeWithText("후보 1 · 부탁 0").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w320dp-h640dp-xhdpi")
    fun agentHomeRemainsUsableAtLargeText() {
        val record = candidateRecord()
        render(fontScale = 1.5f) {
            HomeScreen(
                settings = ProbeSettings(
                    consent = true, childName = "민서", selectedPackages = setOf("school.app"),
                    collectionEnabled = true, onboardingDone = true,
                ),
                records = listOf(record), access = true, connected = true,
                onSetup = {}, onInbox = {}, onRecord = {}, briefingReady = true,
                onAssistant = {},
            )
        }
        compose.onNodeWithText("내용 보기").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("챙길 일").performScrollTo().assertIsDisplayed()
        screenshot("home-agent-large-text")
    }

    private fun candidateRecord() = ProbeRecord(
        id = "candidate-1", packageName = "school.app", appLabel = "학교 앱",
        postedAt = System.currentTimeMillis(), receivedAt = System.currentTimeMillis(),
        title = "체험학습 준비물", text = "준비물: 도시락, 물통. 내일 오전 9시까지",
        bigText = "", textLines = emptyList(), subText = null, summaryText = null,
        category = null, channelId = null, notificationId = 1, notificationKey = "key",
        isOngoing = false, isGroupSummary = false, rawHash = "hash",
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
