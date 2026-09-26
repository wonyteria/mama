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

    @Test fun syncFallsBackToInexactAlarmWhenExactPermissionIsRevoked() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val future = System.currentTimeMillis() + 3_600_000L
        val task = AssistantTask(
            id = "task-inexact", text = "제출하기", completed = false,
            createdAt = 0L, remindAt = future,
        )

        TaskReminderScheduler.sync(context, listOf(task))

        val alarm = org.robolectric.Shadows.shadowOf(manager).nextScheduledAlarm
        org.junit.Assert.assertNotNull("revoked exact permission must still schedule an inexact alarm", alarm)
        assertEquals(future, alarm!!.triggerAtTime)
    }
}
