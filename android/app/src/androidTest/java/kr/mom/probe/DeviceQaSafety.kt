package kr.mom.probe

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.service.ProbeNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Safety boundary for destructive QA (deleteAll / store reset / settings
 * writes) and for the notification-listener grant the pipeline tests need.
 *
 * Destructive tests run ONLY on an isolated emulator. On any physical device
 * they skip honestly: a wiped or emptied-looking QA install still carries
 * state these tests must not touch (calendar commands, sync state, alarm
 * registrations, onboarding flags), so "records and tasks look empty" is never
 * accepted as proof that a wipe is safe.
 */
object DeviceQaSafety {
    fun isEmulator(): Boolean = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("sdk_gphone") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.PRODUCT.contains("sdk")

    /**
     * Skips the test unless it is running on an emulator, with the reason
     * recorded in the test XML instead of silently wiping a physical device.
     */
    fun requireDestructibleState(context: Context, testName: String) {
        assumeTrue(
            "$testName performs a destructive repository reset; destructive tests " +
                "run only on an isolated emulator so a physical device keeps all " +
                "existing QA state untouched.",
            isEmulator(),
        )
    }

    /** Raw `enabled_notification_listeners` value, verbatim ("" when unset). */
    fun enabledListeners(context: Context): String =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()

    /**
     * Enabled-listener setting captured before a test touched it.
     * `modified == false` means the QA component was already enabled and the
     * setting must be left exactly as found.
     */
    class ListenerRestore(val originalEnabled: String, val modified: Boolean)

    /**
     * Enables the QA listener through the instrumentation shell after capturing
     * the existing value verbatim. Only the QA component is granted; user
     * listeners are never removed or reordered by hand.
     */
    fun grantQaListenerAccess(
        context: Context,
        instrumentation: android.app.Instrumentation,
        repository: ProbeRepository,
    ): ListenerRestore {
        val original = enabledListeners(context)
        val component = ComponentName(context, ProbeNotificationListener::class.java).flattenToString()
        if (original.split(':').any { it == component }) {
            return ListenerRestore(original, modified = false)
        }
        val automation = instrumentation.uiAutomation
        runCatching {
            automation.executeShellCommand("cmd notification allow_listener $component").close()
        }
        if (waitUntil(15_000) { repository.hasNotificationAccess() }) {
            return ListenerRestore(original, modified = true)
        }
        // Fallback: append the QA component without dropping existing listeners.
        val updated = (original.split(':') + component)
            .filter { it.isNotBlank() }
            .joinToString(":")
        runCatching {
            automation.executeShellCommand("settings put secure enabled_notification_listeners $updated").close()
        }
        waitUntil(15_000) { repository.hasNotificationAccess() }
        return ListenerRestore(original, modified = true)
    }

    /**
     * Restores the captured enabled-listener value and proves it. A blank
     * original means the device had no enabled listeners: `settings put` with
     * an empty value is rejected, so the key is deleted instead. The restored
     * value is compared verbatim and the QA component must be gone — any
     * mismatch or leftover grant fails the calling test rather than passing
     * with the listener still enabled.
     */
    fun restoreQaListenerAccess(context: Context, restore: ListenerRestore) {
        if (!restore.modified) return
        val component = ComponentName(context, ProbeNotificationListener::class.java).flattenToString()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // `allow_listener` records a separate policy approval — the system
        // re-adds an approved component to the enabled list, so the grant
        // must be revoked before the verbatim value is written back.
        runCatching {
            automation.executeShellCommand("cmd notification disallow_listener $component").close()
        }
        if (restore.originalEnabled.isBlank()) {
            automation.executeShellCommand("settings delete secure enabled_notification_listeners").close()
        } else {
            automation.executeShellCommand(
                "settings put secure enabled_notification_listeners ${restore.originalEnabled}"
            ).close()
        }
        waitUntil(15_000) { enabledListeners(context) == restore.originalEnabled }
        assertEquals(
            "enabled_notification_listeners was not restored verbatim",
            restore.originalEnabled,
            enabledListeners(context),
        )
        assertFalse(
            "QA listener survived teardown",
            enabledListeners(context).split(':').any { it == component },
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(400)
        }
        return condition()
    }
}
