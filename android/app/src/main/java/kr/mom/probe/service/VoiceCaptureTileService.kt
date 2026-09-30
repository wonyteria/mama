package kr.mom.probe.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import kr.mom.probe.voice.VoiceQuickCaptureActivity

/**
 * Quick Settings entry into voice capture. The tile is a pure launcher —
 * it only opens the activity; nothing listens until the user taps the mic
 * button there. Declared with the signature-level BIND_QUICK_SETTINGS_TILE
 * permission; the target activity is non-exported and explicit-intent only.
 */
class VoiceCaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = VoiceQuickCaptureActivity.intent(this)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 4210, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            // The PendingIntent overload does not exist before API 34; the
            // platform only enforces the deprecation against U+ devices.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
