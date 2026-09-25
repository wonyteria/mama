package kr.mom.probe.task

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskReminderSchedulerTest {
    @Test fun fullScreenStopActionUsesTheSameStateTransitionAsNotificationStop() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val intent = TaskReminderScheduler.stopIntent(
            context,
            taskId = "task-1",
            occurrenceId = "occurrence-1",
            notificationId = 27_100,
            scheduledAt = 123_456L,
        )

        assertTrue(TaskReminderScheduler.isStop(intent))
        assertEquals("task-1", TaskReminderScheduler.taskId(intent))
        assertEquals("occurrence-1", TaskReminderScheduler.occurrenceId(intent))
        assertEquals(27_100, TaskReminderScheduler.notificationId(intent))
        assertEquals(TaskReminderReceiver::class.java.name, intent.component?.className)
    }
}
