package kr.mom.probe.task

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReconcileJournalTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pendingEntriesSurviveUntilCleared() {
        ReconcileJournal.reset(context)
        ReconcileJournal.markPending(context, "fp:one")
        ReconcileJournal.markPending(context, "ext:two")

        assertEquals(setOf("fp:one", "ext:two"), ReconcileJournal.pending(context))

        ReconcileJournal.clearPending(context, "fp:one")
        assertEquals(setOf("ext:two"), ReconcileJournal.pending(context))
    }

    @Test fun failedCommitPropagatesAndLeavesJournalUntouched() {
        ReconcileJournal.reset(context)
        ReconcileJournal.markPending(context, "fp:kept")

        val previous = ReconcileJournal.committer
        ReconcileJournal.committer = { false }
        try {
            assertThrows(IOException::class.java) {
                ReconcileJournal.markPending(context, "fp:lost")
            }
            // The failed write neither mutates the durable set nor pretends to persist.
            assertEquals(setOf("fp:kept"), ReconcileJournal.pending(context))
            assertThrows(IOException::class.java) {
                ReconcileJournal.clearPending(context, "fp:kept")
            }
            assertThrows(IOException::class.java) {
                ReconcileJournal.retire(context, setOf("fp:x"))
            }
        } finally {
            ReconcileJournal.committer = previous
            ReconcileJournal.reset(context)
        }
    }

    @Test fun retiredKeysAccumulateAcrossDeletes() {
        ReconcileJournal.reset(context)
        ReconcileJournal.retire(context, setOf("fp:a", "ext:a"))
        ReconcileJournal.retire(context, setOf("fp:b"))

        assertEquals(setOf("fp:a", "ext:a", "fp:b"), ReconcileJournal.retiredKeys(context))

        ReconcileJournal.reset(context)
        assertTrue(ReconcileJournal.retiredKeys(context).isEmpty())
        assertTrue(ReconcileJournal.pending(context).isEmpty())
    }

    @Test fun activeAlarmsPendingRecoveryExcludesFinishedAndExcluded() {
        val pending = AssistantTask(
            "t1", "x", false, 1L,
            remindAt = null, activeAlarmOccurrenceId = "occ-1",
            activeAlarmScheduledAt = 2L, activeAlarmNotificationId = 27_101,
        )
        val completed = pending.copy(id = "t2", completed = true)
        val suspended = pending.copy(id = "t3", suspended = true)
        val excluded = pending.copy(id = "t4", excluded = true)
        val normal = AssistantTask("t5", "y", false, 1L, remindAt = 3L)

        val recovery = TaskReminderScheduler.alarmsPendingRecovery(
            listOf(pending, completed, suspended, excluded, normal),
        )

        assertEquals(listOf("t1"), recovery.map { it.id })
    }
}
