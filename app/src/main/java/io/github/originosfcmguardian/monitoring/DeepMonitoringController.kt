package io.github.originosfcmguardian.monitoring

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.UUID

class DeepMonitoringController(context: Context) {
    private val context = context.applicationContext

    /** The caller must be a resumed activity responding to an explicit user click. */
    suspend fun start(packageName: String, isVisible: () -> Boolean): String? {
        val identity = withContext(Dispatchers.IO) {
            runCatching { TargetSelectionStore(context).load().singleOrNull { it.packageName == packageName } }.getOrNull()
                ?.takeIf { monitorIdentityMatches(context, it) }
        } ?: return "所选应用身份不可确认，请重新选择应用"
        val refusal = withContext(Dispatchers.IO) {
            when {
                MonitorSessionStore(context).load() == MonitorSessionLoadResult.Corrupt -> "监控记录损坏，请先清除监控记录"
                !monitorShellReady() -> "请先启动并授权 Shizuku shell；未开启监控"
                !monitorNotificationsAvailable(context, createChannel = true) -> "需要允许 Guardian 通知及深度监控通知频道，才能显示停止入口"
                else -> null
            }
        }
        if (refusal != null) return refusal
        return withContext(Dispatchers.Main.immediate) {
            if (!isVisible()) return@withContext "页面已离开；请回到应用后重新开启"
            val now = System.currentTimeMillis()
            val session = MonitorSession(
                id = UUID.randomUUID().toString(), identity = identity,
                startedAtEpochMillis = now, deadlineEpochMillis = now + MONITOR_DURATION_MILLIS,
                status = MonitorSessionStatus.RUNNING, events = emptyList(),
            )
            if (!DeepMonitoringRuntime.reserve(session)) return@withContext "已有监控正在启动、运行或停止"
            try {
                context.startForegroundService(Intent(context, DeepMonitoringService::class.java).apply {
                    action = DeepMonitoringService.ACTION_START
                    putExtra(DeepMonitoringService.EXTRA_SESSION, session.id)
                })
                null
            } catch (_: RuntimeException) {
                DeepMonitoringRuntime.finish(session.id, null, "系统未允许启动监控；没有开始采集")
                "系统未允许启动监控；没有开始采集"
            }
        }
    }

    fun stop() = DeepMonitoringRuntime.stop()

    suspend fun clearHistory(): Boolean = withContext(Dispatchers.IO) {
        DeepMonitoringRuntime.clear(MonitorSessionStore(context))
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val revision = DeepMonitoringRuntime.revision()
        DeepMonitoringRuntime.showHistory(revision, MonitorSessionStore(context).load())
    }
}

internal const val MONITOR_DURATION_MILLIS = 30 * 60 * 1_000L
internal const val MONITOR_CHANNEL = "manual_process_monitor"

internal fun monitorIdentityMatches(context: Context, identity: SelectedTargetIdentity): Boolean = runCatching {
    identity in TargetSelectionStore(context).load() &&
        TargetPackageProbe(context).identity(identity.packageName) == identity &&
        context.packageManager.getPackagesForUid(identity.uid)?.toSet() == setOf(identity.packageName)
}.getOrDefault(false)

internal fun monitorShellReady(): Boolean = runCatching {
    Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

internal fun monitorNotificationsAvailable(context: Context, createChannel: Boolean = false): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = context.getSystemService(NotificationManager::class.java)
    if (createChannel) manager.createNotificationChannel(NotificationChannel(
        MONITOR_CHANNEL, "手动深度监控", NotificationManager.IMPORTANCE_LOW,
    ))
    return manager.areNotificationsEnabled() && manager.getNotificationChannel(MONITOR_CHANNEL)?.let {
        it.importance != NotificationManager.IMPORTANCE_NONE
    } == true
}
