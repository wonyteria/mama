package kr.mom.probe.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PostingTimeStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    @Before
    fun clear() {
        PostingTimeStore.reset(context, "school-website-snjj")
        PostingTimeStore.reset(context, "ealimi-web")
    }

    private fun at(date: String, hour: Int): Long =
        LocalDate.parse(date).atTime(hour, 30).atZone(seoul).toInstant().toEpochMilli()

    @Test
    fun `not learned with too few observations`() {
        PostingTimeStore.recordDiscoveries(context, "school-website-snjj", listOf(
            at("2026-09-20", 15), at("2026-09-21", 15),
        ))
        assertNull(PostingTimeStore.learnedCheckHour(context, "school-website-snjj"))
    }

    @Test
    fun `learns modal hour minus lookahead`() {
        PostingTimeStore.recordDiscoveries(context, "school-website-snjj", listOf(
            at("2026-09-20", 15), at("2026-09-21", 15),
            at("2026-09-22", 16), at("2026-09-23", 15), at("2026-09-24", 9),
        ))
        // Modal discovery hour 15 -> check at 12 (15 - 3).
        assertEquals(12, PostingTimeStore.learnedCheckHour(context, "school-website-snjj"))
    }

    @Test
    fun `single day is not enough days`() {
        PostingTimeStore.recordDiscoveries(context, "ealimi-web", listOf(
            at("2026-09-20", 10), at("2026-09-20", 10), at("2026-09-20", 10), at("2026-09-20", 10),
        ))
        assertNull(PostingTimeStore.learnedCheckHour(context, "ealimi-web"))
    }

    @Test
    fun `describe formats afternoon discovery hour`() {
        PostingTimeStore.recordDiscoveries(context, "ealimi-web", listOf(
            at("2026-09-20", 18), at("2026-09-21", 18), at("2026-09-22", 18), at("2026-09-23", 18),
        ))
        val hint = PostingTimeStore.describeLearnedWindow(context, "ealimi-web")
        assertNotNull(hint)
        assertEquals("주로 오후 6시쯤 새 소식이 발견돼요", hint)
    }

    @Test
    fun `reset clears learning`() {
        PostingTimeStore.recordDiscoveries(context, "ealimi-web", listOf(
            at("2026-09-20", 18), at("2026-09-21", 18), at("2026-09-22", 18), at("2026-09-23", 18),
        ))
        PostingTimeStore.reset(context, "ealimi-web")
        assertNull(PostingTimeStore.learnedCheckHour(context, "ealimi-web"))
    }

    @Test
    fun `empty observations are ignored`() {
        PostingTimeStore.recordDiscoveries(context, "ealimi-web", emptyList())
        assertNull(PostingTimeStore.learnedCheckHour(context, "ealimi-web"))
    }

    @Test
    fun `millisUntilNextHour targets today or tomorrow`() {
        // at() sets minute 30, so "now" is 10:30.
        val now = at("2026-09-24", 10)
        // Next 12:00 is today, 1.5h away.
        assertEquals(90 * 60_000L, SourceSyncScheduler.millisUntilNextHour(12, now))
        // 10:00 today already passed -> tomorrow 10:00, 23.5h away.
        assertEquals(23 * 3600_000L + 30 * 60_000L, SourceSyncScheduler.millisUntilNextHour(10, now))
    }
}
