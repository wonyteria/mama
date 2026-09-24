package kr.mom.probe.reminder

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BriefingRemindersTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before @After fun clear() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsPreserveExistingUserTimeAndEnableMissingEvening() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit()
            .putBoolean("enabled0", true).putInt("hour0", 8).putInt("minute0", 15).commit()

        assertTrue(BriefingReminders.enableDefaults(context))
        assertEquals(BriefingTime(true, 8, 15, false), BriefingReminders.read(context, 0))
        assertEquals(BriefingTime(true, 20, 30, false), BriefingReminders.read(context, 1))
    }

    @Test fun seenStateUsesOnlyDisplayedRecordRevisions() {
        val hidden = record("hidden", 10L)
        val collapsedVisible = listOf(record("shown-4", 40L))
        val hiddenUntilDetails = listOf(record("shown-3", 30L), record("shown-2", 20L))
        val seen = BriefingReminders.seenRevisionIdsFor(collapsedVisible)

        assertTrue(hidden.id !in seen)
        assertTrue(hiddenUntilDetails.none { it.id in seen })
        assertEquals(1, seen.size)
    }

    @Test fun activeBriefingSnoozeRejectsStaleOccurrence() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit()
            .putBoolean("enabled0", true)
            .putString("activeOccurrence0", "new")
            .putLong("activeGeneration0", BriefingReminders.generation(context))
            .putLong("activeScheduledAt0", 1_000L)
            .putInt("activeNotificationId0", 710)
            .commit()

        val result = BriefingReminders.snoozeActive(context, 0, "old", BriefingReminders.generation(context), 10)

        assertEquals(BriefingSnoozeResult.Stale, result)
        assertFalse(context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).contains("snoozeAt0"))
    }

    @Test fun briefingFireRejectsStaleScheduledOccurrenceBeforeActivating() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit()
            .putBoolean("enabled0", true)
            .putString("scheduledOccurrence0", "current")
            .putLong("scheduledAt0", 2_000L)
            .putInt("snoozeCount0", 2)
            .putInt("snoozeMinutes0", 20)
            .commit()

        val stale = BriefingReminders.consumeScheduledFire(context, 0, "old", 2_000L, BriefingReminders.generation(context), 710, delayed = false)
        val current = BriefingReminders.consumeScheduledFire(context, 0, "current", 2_000L, BriefingReminders.generation(context), 710, delayed = false)
        val prefs = context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE)

        assertNull(stale)
        assertEquals(2_000L, current)
        assertEquals("current", prefs.getString("activeOccurrence0", null))
        assertFalse(prefs.contains("scheduledOccurrence0"))
        assertEquals(0, prefs.getInt("snoozeCount0", -1))
        assertEquals(0, prefs.getInt("snoozeMinutes0", -1))
    }

    @Test fun activeBriefingSnoozePersistsNewFireTimeAndKeepsSameChainCounters() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit()
            .putBoolean("enabled0", true)
            .putString("activeOccurrence0", "fire")
            .putLong("activeGeneration0", BriefingReminders.generation(context))
            .putLong("activeScheduledAt0", 1_000L)
            .putInt("activeNotificationId0", 710)
            .putInt("snoozeCount0", 1)
            .putInt("snoozeMinutes0", 10)
            .commit()

        val result = BriefingReminders.snoozeActive(context, 0, "fire", BriefingReminders.generation(context), 10)
        val prefs = context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE)

        assertTrue(result is BriefingSnoozeResult.Scheduled)
        assertTrue(prefs.getLong("snoozeAt0", 0L) > 0L)
        assertTrue(prefs.getString("snoozeOccurrence0", null).orEmpty().isNotBlank())
        assertFalse(prefs.contains("activeOccurrence0"))
        assertEquals(2, prefs.getInt("snoozeCount0", 0))
        assertEquals(20, prefs.getInt("snoozeMinutes0", 0))
    }

    @Test fun snoozedBriefingFirePreservesSameChainCounters() {
        context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE).edit()
            .putBoolean("enabled0", true)
            .putString("snoozeOccurrence0", "snoozed")
            .putLong("snoozeAt0", 3_000L)
            .putInt("snoozeCount0", 2)
            .putInt("snoozeMinutes0", 20)
            .commit()

        val consumed = BriefingReminders.consumeScheduledFire(context, 0, "snoozed", 3_000L, BriefingReminders.generation(context), 710, delayed = true)
        val prefs = context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE)

        assertEquals(3_000L, consumed)
        assertFalse(prefs.contains("snoozeOccurrence0"))
        assertEquals("snoozed", prefs.getString("activeOccurrence0", null))
        assertEquals(2, prefs.getInt("snoozeCount0", 0))
        assertEquals(20, prefs.getInt("snoozeMinutes0", 0))
    }

    @Test fun briefingSnoozeContinuesBeyondTheFourthSnoozeAcrossRestarts() {
        val preferences = context.getSharedPreferences("briefing-reminders", Application.MODE_PRIVATE)
        preferences.edit()
            .putBoolean("enabled0", true)
            .putString("activeOccurrence0", "fire-0")
            .putLong("activeGeneration0", BriefingReminders.generation(context))
            .putInt("activeNotificationId0", 710)
            .commit()

        var occurrence = "fire-0"
        repeat(4) { index ->
            val result = BriefingReminders.snoozeActive(context, 0, occurrence, BriefingReminders.generation(context), 10)
            assertTrue(result is BriefingSnoozeResult.Scheduled)
            if (index < 3) {
                // A process restart re-reads prefs: the snoozed occurrence must fire and be snoozable again.
                val snoozeAt = preferences.getLong("snoozeAt0", 0L)
                val snoozeOccurrence = preferences.getString("snoozeOccurrence0", null)
                assertTrue(snoozeAt > 0L)
                assertTrue(snoozeOccurrence != null)
                val firedAt = BriefingReminders.consumeScheduledFire(
                    context, 0, snoozeOccurrence, snoozeAt, BriefingReminders.generation(context), 710, delayed = true,
                )
                assertEquals(snoozeAt, firedAt)
                occurrence = preferences.getString("activeOccurrence0", null)!!
            }
        }

        assertEquals(4, preferences.getInt("snoozeCount0", 0))
        assertEquals(40, preferences.getInt("snoozeMinutes0", 0))
        assertTrue(preferences.getLong("snoozeAt0", 0L) > 0L)
        assertTrue(preferences.getString("snoozeOccurrence0", null) != null)
    }

    @Test fun briefingTaskProjectionExcludesSuspendedExpiredAndDistantTasks() {
        val now = 1_000L
        val visible = kr.mom.probe.task.AssistantTask("visible", "내일 제출", false, 10L, dueAt = now + 86_400_000L)
        val localNoDue = kr.mom.probe.task.AssistantTask("local", "물통", false, 11L)
        val suspended = kr.mom.probe.task.AssistantTask("suspended", "정정 전", false, 12L, dueAt = now + 86_400_000L, suspended = true)
        val expired = kr.mom.probe.task.AssistantTask("expired", "어제", false, 13L, dueAt = now - 1L)
        val distant = kr.mom.probe.task.AssistantTask("distant", "한참 뒤", false, 14L, dueAt = now + 9 * 86_400_000L)
        val completed = kr.mom.probe.task.AssistantTask("done", "완료", true, 15L)

        val projected = BriefingReminders.briefingTasks(listOf(suspended, expired, distant, completed, localNoDue, visible), now)

        assertEquals(listOf("visible", "local"), projected.map { it.id })
        assertFalse(projected.any { it.suspended || it.completed })
    }

    private fun record(id: String, receivedAt: Long) = kr.mom.probe.data.ProbeRecord(
        id = id, packageName = "pkg", appLabel = "학교", postedAt = receivedAt, receivedAt = receivedAt,
        title = id, text = "준비물: 물통. 내일 오전 9시까지", bigText = "", textLines = emptyList(),
        subText = null, summaryText = null, category = null, channelId = null, notificationId = 1,
        notificationKey = "key-$id", isOngoing = false, isGroupSummary = false, rawHash = "hash",
    )
}
