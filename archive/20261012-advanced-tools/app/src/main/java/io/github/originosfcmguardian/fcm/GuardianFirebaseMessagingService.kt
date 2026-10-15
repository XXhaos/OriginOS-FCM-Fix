package io.github.originosfcmguardian.fcm

import android.os.Process as AndroidProcess
import android.os.SystemClock
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GuardianFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null) return
        val probe = GuardianProbeParser.parse(message.data) ?: return
        val receivedWall = System.currentTimeMillis()
        val receivedElapsed = SystemClock.elapsedRealtime()
        val processStartedElapsed = coherentProcessStartedAtElapsedMillis(
            processStartedAtElapsedMillis = AndroidProcess.getStartElapsedRealtime(),
            receivedAtElapsedMillis = receivedElapsed,
        )
        val (interactive, idle, network) = FcmReceiptContext.capture(this)
        val receipt = FcmReceipt(
            testId = probe.testId,
            sentAtEpochMillis = probe.sentAtEpochMillis,
            receivedAtEpochMillis = receivedWall,
            receivedAtElapsedMillis = receivedElapsed,
            messagePriority = message.priority.toMessagePriority(),
            deviceInteractive = interactive,
            deviceIdleMode = idle,
            networkAvailability = network,
            processStartedAtElapsedMillis = processStartedElapsed,
        )
        val store = FcmEvidenceStore(this)
        if (store.recordFirst(receipt) !is RecordReceiptResult.Stored) return

        val outcome = FcmNotificationPublisher(this).publish()
        val duration = (SystemClock.elapsedRealtime() - receivedElapsed).coerceAtLeast(0)
        store.updateNotification(probe.testId, outcome, duration)
    }

    override fun onNewToken(token: String) {
        // A refreshed token is intentionally neither logged nor persisted. The user must
        // explicitly request and reveal the current token from the app before controlled use.
    }
}

internal fun coherentProcessStartedAtElapsedMillis(
    processStartedAtElapsedMillis: Long,
    receivedAtElapsedMillis: Long,
): Long? = processStartedAtElapsedMillis.takeIf { it in 0..receivedAtElapsedMillis }

private fun Int.toMessagePriority(): MessagePriority = when (this) {
    RemoteMessage.PRIORITY_HIGH -> MessagePriority.HIGH
    RemoteMessage.PRIORITY_NORMAL -> MessagePriority.NORMAL
    else -> MessagePriority.UNKNOWN
}
