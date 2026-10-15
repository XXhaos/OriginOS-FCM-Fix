package io.github.originosfcmguardian.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GuardianNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        NotificationFactsRuntime.attachService(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationFactsRuntime.attachService(this)
        val enabled = NotificationFactsRuntime.isEnabled()
        val granted = hasNotificationAccess(this)
        NotificationFactsRuntime.setConnected(enabled && granted)
        if (!enabled || !granted) requestGuardianUnbind()
    }

    override fun onListenerDisconnected() {
        NotificationFactsRuntime.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        capture(sbn, NotificationFactKind.POSTED_OR_UPDATED)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        capture(sbn, NotificationFactKind.REMOVED)
    }

    override fun onDestroy() {
        NotificationFactsRuntime.detachService(this)
        super.onDestroy()
    }

    internal fun requestGuardianUnbind() {
        runCatching { requestUnbind() }
    }

    private fun capture(sbn: StatusBarNotification, kind: NotificationFactKind) {
        val packageName = sbn.packageName ?: return
        NotificationFactsRuntime.offer(
            CapturedNotificationFact(
                packageName = packageName,
                uid = sbn.uid,
                observedAtEpochMillis = System.currentTimeMillis(),
                kind = kind,
            ),
        )
    }
}
