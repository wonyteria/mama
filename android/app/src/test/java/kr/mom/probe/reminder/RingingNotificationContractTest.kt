package kr.mom.probe.reminder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.task.TaskReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Google Play restricts USE_FULL_SCREEN_INTENT to dedicated alarm/calling
 * apps, so ringing reminders must stay heads-up notifications the user taps
 * to open the activity. These tests inspect the posted Notification objects
 * rather than grepping strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RingingNotificationContractTest {

    private fun armedContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context.getSharedPreferences("briefing-reminders", Context.MODE_PRIVATE)
            .edit().putBoolean("alarmMode", true).commit()
        return context
    }

    @Test
    fun taskRingingNotificationKeepsHeadsUpContractWithoutFullScreenIntent() {
        val context = armedContext()
        val task = AssistantTask(
            id = "task-ring", text = "제출하기", completed = false, createdAt = 0L,
            remindAt = System.currentTimeMillis(),
            activeAlarmOccurrenceId = "occ-1",
            activeAlarmScheduledAt = 123_456L,
        )

        assertTrue("ringing task notification should post", TaskReminderScheduler.notify(context, task))

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertNull("ringing notification must not carry a full-screen intent", posted.fullScreenIntent)
        assertNotNull("tapping must still open the reminder screen", posted.contentIntent)
        assertEquals(TaskAlarmActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component?.className)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertEquals("mom-assistant-alarm", posted.channelId)
        assertEquals(30_000L, posted.timeoutAfter)
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(listOf("소리 끄기", "10분 뒤"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun briefingRingingNotificationKeepsHeadsUpContractWithoutFullScreenIntent() {
        val context = armedContext()

        assertTrue("ringing briefing notification should post", BriefingReminders.notify(context, slot = 0, count = 1, demo = true))

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertNull("ringing notification must not carry a full-screen intent", posted.fullScreenIntent)
        assertNotNull("tapping must still open the briefing screen", posted.contentIntent)
        assertEquals(BriefingActivity::class.java.name, shadowOf(posted.contentIntent).savedIntent.component?.className)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertEquals("mom-assistant-alarm", posted.channelId)
        assertEquals(30_000L, posted.timeoutAfter)
        assertTrue(posted.actions.any { it.title.toString() == "브리핑 열기" })
    }

    @Test
    fun manifestDoesNotRequestFullScreenIntentPermission() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(
            "USE_FULL_SCREEN_INTENT is reserved for dedicated alarm/calling apps and must not be requested",
            info.requestedPermissions.orEmpty().contains("android.permission.USE_FULL_SCREEN_INTENT"),
        )
    }
}
