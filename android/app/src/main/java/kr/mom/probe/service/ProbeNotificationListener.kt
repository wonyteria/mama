package kr.mom.probe.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.awaitReadyCaptureEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProbeNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { ProbeRepository.get(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        repository.setListenerConnected(true)
        scope.launch { repository.cleanupExpired() }
        // Deliberately never request activeNotifications: past notifications are not collected.
    }

    override fun onListenerDisconnected() {
        repository.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val callbackEpoch = repository.captureEpoch()
        scope.launch {
            val epoch = awaitReadyCaptureEpoch(callbackEpoch, repository.isReady, repository::captureEpoch) ?: return@launch
            if (!ProbeRules.canCapture(repository.settings.value, sbn.packageName, packageName,
                    true, sbn.isOngoing, sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)) return@launch
            repository.capture(sbn, epoch)
            // The original is cancelled only after capture + analysis + unified
            // alert posting all succeeded and the app is opted into hiding.
            if (repository.shouldHideOriginal(sbn)) runCatching { cancelNotification(sbn.key) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // A dismissed unified alert must not let later originals be hidden.
        if (sbn != null && sbn.packageName == packageName) {
            kr.mom.probe.reminder.AssistantAlertNotifier.markAlertGone(this, sbn.id)
        }
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        repository.setListenerConnected(false)
        scope.cancel()
        super.onDestroy()
    }
}
