package io.github.originosfcmguardian.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.originosfcmguardian.MainActivity
import io.github.originosfcmguardian.R

internal class FcmNotificationPublisher(private val context: Context) {
    fun publish(): NotificationOutcome {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return NotificationOutcome.POST_FAILED
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FCM 受控自测",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "仅显示 Guardian 自身受控 FCM 回调结果"
                },
            )
            if (!notificationPermissionGranted(Build.VERSION.SDK_INT) {
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                }
            ) {
                return NotificationOutcome.PERMISSION_DENIED
            }
            if (!manager.areNotificationsEnabled()) {
                return NotificationOutcome.APP_NOTIFICATIONS_BLOCKED
            }
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                return NotificationOutcome.CHANNEL_BLOCKED
            }
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_guardian)
                .setContentTitle("Guardian FCM 自测回调")
                .setContentText("已在本机保存一条受控测试回调")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            NotificationOutcome.POST_REQUESTED
        } catch (_: Throwable) {
            NotificationOutcome.POST_FAILED
        }
    }

    private companion object {
        const val CHANNEL_ID = "guardian_fcm_probe_v1"
        const val NOTIFICATION_ID = 0x46434d
    }
}

internal inline fun notificationPermissionGranted(
    sdkInt: Int,
    permissionResult: () -> Int,
): Boolean = sdkInt < 33 || permissionResult() == PackageManager.PERMISSION_GRANTED
