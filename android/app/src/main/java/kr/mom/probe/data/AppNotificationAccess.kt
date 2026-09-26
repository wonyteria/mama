package kr.mom.probe.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Best-effort checks for whether a *source* app can post notifications.
 * Mama reads those popups through its own notification listener, so a source
 * app whose notifications are switched off can never produce records.
 */
object AppNotificationAccess {
    private const val POST_NOTIFICATION_OP = "android:post_notification"

    /**
     * Returns null when the app is not installed or the state cannot be read,
     * so callers never present an unknown state as blocked.
     */
    fun canPostNotifications(context: Context, packageName: String): Boolean? {
        val info = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            // checkOpNoThrow exists since API 29 and no longer requires the unsafe
            // variant; foreign-package failures still land in the catch as "unknown".
            appOps.checkOpNoThrow(POST_NOTIFICATION_OP, info.uid, packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }
    }

    /** Opens the per-app notification settings; falls back to app details. */
    fun openNotificationSettings(context: Context, packageName: String): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
        )
        return candidates.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}
