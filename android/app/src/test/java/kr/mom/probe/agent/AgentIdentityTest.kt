package kr.mom.probe.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kr.mom.probe.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentIdentityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `identity contract is ravi with legacy wake aliases`() {
        assertEquals("ravi", AgentIdentity.ID)
        assertEquals("라비", AgentIdentity.displayName)
        assertEquals("rabbit", AgentIdentity.MASCOT)
        assertTrue(AgentIdentity.wakeNames.containsAll(listOf("라비", "라비야", "모모", "모모야")))
        // Longest-first ordering is a contract: shorter names must not eat a longer suffix.
        for (i in AgentIdentity.wakeNames.indices) {
            for (j in i + 1 until AgentIdentity.wakeNames.size) {
                assertTrue(
                    AgentIdentity.wakeNames[i].length >= AgentIdentity.wakeNames[j].length,
                )
            }
        }
    }

    @Test fun `strips ravi and momo wake names at boundaries`() {
        assertEquals("내일 물티슈 챙겨줘", AgentIdentity.stripWakeName("라비야 내일 물티슈 챙겨줘"))
        assertEquals("내일 물티슈 챙겨줘", AgentIdentity.stripWakeName("라비 내일 물티슈 챙겨줘"))
        assertEquals("내일 물티슈 챙겨줘", AgentIdentity.stripWakeName("모모야 내일 물티슈 챙겨줘"))
        assertEquals("내일 물티슈 챙겨줘", AgentIdentity.stripWakeName("모모 내일 물티슈 챙겨줘"))
        assertEquals("체육복 챙겨줘", AgentIdentity.stripWakeName("라비야, 체육복 챙겨줘"))
        assertEquals("체육복 챙겨줘", AgentIdentity.stripWakeName("모모야! 체육복 챙겨줘"))
        assertEquals("", AgentIdentity.stripWakeName("라비야"))
        assertEquals("", AgentIdentity.stripWakeName("모모"))
    }

    @Test fun `preserves ordinary words that merely start with a wake name`() {
        assertEquals("모모랜드 가자", AgentIdentity.stripWakeName("모모랜드 가자"))
        assertEquals("라비올리 귀엽다", AgentIdentity.stripWakeName("라비올리 귀엽다"))
        assertEquals("모모야기차 시간 알려줘", AgentIdentity.stripWakeName("모모야기차 시간 알려줘"))
        assertEquals("내일 물티슈 챙겨줘", AgentIdentity.stripWakeName("내일 물티슈 챙겨줘"))
    }

    @Test fun `widget resource mirror matches the kotlin identity`() {
        assertTrue(context.getString(R.string.assistant_widget_name).contains(AgentIdentity.displayName))
        assertEquals(AgentIdentity.displayName, context.getString(R.string.assistant_widget_title))
        assertTrue(context.getString(R.string.assistant_widget_description).contains(AgentIdentity.displayName))
        assertTrue(context.getString(R.string.assistant_widget_accessibility).contains(AgentIdentity.displayName))
    }

    @Test fun `no user-visible 모모 literal outside the identity contract`() {
        val main = File(System.getProperty("user.dir"), "src/main")
        assertTrue("src/main must be readable from the unit test working dir", main.isDirectory)
        val offenders = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.readText().contains("모모") }
            .map { it.relativeTo(main).path }
            .toList()
        // Only AgentIdentity may hold 모모 — as the legacy wake-alias list.
        // Stable identifiers use "mom"/"mama" latin names and are not covered here.
        assertEquals(listOf("java/kr/mom/probe/agent/AgentIdentity.kt"), offenders)
    }
}
