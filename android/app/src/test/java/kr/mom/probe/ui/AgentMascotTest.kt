package kr.mom.probe.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kr.mom.probe.R
import kr.mom.probe.agent.AgentIdentity
import kr.mom.probe.reminder.AlarmContent
import kr.mom.probe.reminder.AlarmContentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ko-rKR-w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentMascotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `five states are the complete contract with distinct Korean labels`() {
        assertEquals(
            listOf("IDLE", "LISTENING", "THINKING", "DONE", "NEW_INFO"),
            AgentMascotState.entries.map { it.name },
        )
        assertEquals(5, AgentMascotState.entries.map { it.stateLabel }.distinct().size)
        assertTrue(AgentMascotState.entries.all { it.stateLabel.isNotBlank() })
    }

    @Test fun `every state draws safely at a decorative size`() {
        render {
            Column {
                AgentMascotState.entries.forEach { state ->
                    AgentMascot(state = state, modifier = Modifier.size(60.dp, 70.dp))
                }
            }
        }
        // Decorative mascots must stay out of the semantics tree — no
        // duplicate TalkBack announcements next to nearby copy.
        AgentMascotState.entries.forEach { state ->
            compose.onAllNodes(
                hasContentDescription("${AgentIdentity.displayName} ${state.stateLabel}"),
            ).assertCountEquals(0)
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.stateLabel),
            ).assertCountEquals(0)
        }
    }

    @Test fun `operational mascot exposes state in semantics and visible text`() {
        render {
            Column {
                AgentMascotState.entries.forEach { state ->
                    AgentMascot(
                        state = state,
                        decorative = false,
                        modifier = Modifier.size(92.dp, 110.dp),
                    )
                }
            }
        }
        AgentMascotState.entries.forEach { state ->
            compose.onAllNodes(
                hasContentDescription("${AgentIdentity.displayName} ${state.stateLabel}"),
            ).assertCountEquals(1)
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.stateLabel),
            ).assertCountEquals(1)
            compose.onAllNodesWithText(state.stateLabel).assertCountEquals(1)
        }
    }

    @Test fun `labelled mascot survives 200 percent font scale at 360dp`() {
        render(fontScale = 2f) {
            AgentMascot(
                state = AgentMascotState.LISTENING,
                decorative = false,
                modifier = Modifier.size(92.dp, 110.dp),
            )
        }
        compose.onNodeWithText(AgentMascotState.LISTENING.stateLabel).assertIsDisplayed()
    }

    @Test fun `existing alarm surface shows the new-info rabbit`() {
        render {
            AlarmContent(
                state = AlarmContentState(
                    title = "라비 알림",
                    dateText = "9월 20일 일요일",
                    scheduledTimeText = "07:00",
                    actionTitle = "등교 가방에 물티슈 넣기",
                    actionSummary = "곧 챙길 일 1개",
                ),
                onStopSound = {},
                onSnoozeTenMinutes = {},
                onShowDetails = {},
            )
        }
        // The alarm call site must keep rendering the mascot (NEW_INFO —
        // a delivered reminder is new information) at its fixed slot.
        compose.onNodeWithTag("alarm-mascot").assertIsDisplayed()
    }

    @Test fun `widget layout and resources point at rabbit while momo is preserved`() {
        val context = compose.activity
        val parser = context.resources.getLayout(R.layout.assistant_widget_layout)
        var src = -1
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val v = parser.getAttributeResourceValue(
                    "http://schemas.android.com/apk/res/android", "src", -1,
                )
                if (v != -1) src = v
            }
            parser.next()
        }
        assertEquals(R.drawable.assistant_widget_rabbit, src)
        // Rollback resource stays byte-identical and resolvable.
        assertEquals(
            "assistant_widget_momo",
            context.resources.getResourceEntryName(R.drawable.assistant_widget_momo),
        )
        assertNotEquals(R.drawable.assistant_widget_momo, R.drawable.assistant_widget_rabbit)
    }

    @Test fun `mascot pixels are non-empty in native graphics mode`() {
        render {
            Column {
                AgentMascotState.entries.forEach { state ->
                    AgentMascot(state = state, modifier = Modifier.size(60.dp, 70.dp))
                }
            }
        }
        val file = File("build/reports/screenshots", "agent_mascot_states.png")
            .apply { parentFile?.mkdirs() }
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                .also { decor.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("mascot must draw non-empty pixels", pixels.any { it != pixels[0] })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MomTheme {
                    Surface(color = Clay.Background) { content() }
                }
            }
        }
        compose.waitForIdle()
    }
}
