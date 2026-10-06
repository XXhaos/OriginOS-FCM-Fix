package io.github.originosfcmguardian.activation

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Pairing secrets belong to a finite notification session, never to this Activity. */
class InternalActivationActivity : ComponentActivity() {
    private lateinit var controller: InternalActivationController
    private var pairingNotificationsAvailable by mutableStateOf(false)
    private var localMessage by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        controller = InternalActivationController.get(applicationContext)
        PairingNotificationService.ensureChannel(this)
        setContent {
            MaterialTheme {
                val status by controller.status.collectAsStateWithLifecycle()
                val pairing by PairingNotificationService.status.collectAsStateWithLifecycle()
                var connectPort by remember { mutableStateOf("") }
                val leave = { controller.cancelUiOperation(); finish() }
                BackHandler(onBack = leave)
                InternalActivationScreen(
                    status = status,
                    pairingActive = pairing.active,
                    pairingMessage = localMessage.ifBlank { pairing.message },
                    pairingNotificationsAvailable = pairingNotificationsAvailable,
                    connectPort = connectPort,
                    onConnectPortChange = { connectPort = it },
                    onPair = {
                        localMessage = ""
                        refreshNotificationAvailability()
                        if (!pairingNotificationsAvailable) openNotificationSettings()
                        else if (!PairingNotificationService.start(this)) {
                            localMessage = "未能开始通知配对，请检查通知权限和当前操作状态。"
                        }
                    },
                    onNotificationSettings = { openNotificationSettings() },
                    onStart = {
                        localMessage = ""
                        controller.activate(connectPort)
                    },
                    onCancel = {
                        localMessage = ""
                        if (pairing.active) PairingNotificationService.cancel(this)
                        else controller.cancelUiOperation()
                    },
                    onManualCleanup = { controller.acknowledgeManualCleanup() },
                    onOpenSettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            .onFailure { localMessage = "无法打开开发者选项，请从系统设置手动进入。" }
                    },
                    onReturn = leave,
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
        refreshNotificationAvailability()
        localMessage = ""
    }
    override fun onStop() {
        // Notification-owned pairing deliberately survives navigation to system settings.
        if (::controller.isInitialized) controller.cancelUiOperation()
        super.onStop()
    }

    private fun refreshNotificationAvailability() {
        pairingNotificationsAvailable = PairingNotificationService.notificationsAvailable(this)
    }

    private fun openNotificationSettings() {
        runCatching {
            val intent = if (getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, PairingNotificationService.CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }.onFailure { localMessage = "请从系统设置允许 Guardian 的“无线调试配对”通知。" }
    }
}
