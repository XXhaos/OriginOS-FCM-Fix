package io.github.originosfcmguardian.monitoring

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.github.originosfcmguardian.MainActivity
import io.github.originosfcmguardian.R
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.trace.ProcessTraceCodec
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Explicit, finite debugging only; never started by boot, workers, notification callbacks or FCM. */
class DeepMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestedStop = AtomicBoolean(false)
    private val stopOutcome = AtomicReference<MonitorSessionStatus?>(null)
    private var collector: Job? = null
    private var deadlineGuard: Job? = null
    @Volatile private var executor: ShizukuExecutor? = null
    private var activeId: String? = null
    private val lifetime = MonitorServiceLifetime()
    private var stopPendingIntent: PendingIntent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifetime.onCommand(startId)
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_SESSION)?.let { DeepMonitoringRuntime.stop(it) }
            if (collector == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (collector != null) return START_NOT_STICKY
        val id = intent?.takeIf { it.action == ACTION_START }?.getStringExtra(EXTRA_SESSION)
        val initial = id?.let { DeepMonitoringRuntime.claim(it, ::requestStop) }
        if (id == null || initial == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        requestedStop.set(false)
        stopOutcome.set(null)
        activeId = id
        try {
            check(monitorNotificationsAvailable(this))
            val notification = notification(id)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            DeepMonitoringRuntime.finish(id, initial.copy(status = MonitorSessionStatus.UNAVAILABLE), "无法显示监控停止通知；没有开始采集")
            stopPendingIntent?.cancel()
            stopPendingIntent = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        collector = scope.launchMonitorCollector { collect(initial) }
        deadlineGuard = scope.launch {
            delay((initial.deadlineEpochMillis - System.currentTimeMillis()).coerceIn(0, MONITOR_DURATION_MILLIS))
            stopWithReason(MonitorSessionStatus.DEADLINE)
        }
        return START_NOT_STICKY
    }

    private suspend fun collect(initial: MonitorSession) {
        var session = initial
        var outcome = MonitorSessionStatus.UNAVAILABLE
        var detail = "监控读取不可用，已停止；缺失事件不能视为没有发生。"
        var savedInitially = false
        val store = MonitorSessionStore(this)
        val startedElapsed = SystemClock.elapsedRealtime()
        try {
            withContext(Dispatchers.IO) {
                check(monitorIdentityMatches(this@DeepMonitoringService, initial.identity))
                check(monitorShellReady())
                check(store.save(initial))
                savedInitially = true
            }
            // This lifetime belongs to the service, not the Activity's Shizuku runtime.
            // Readiness/Binder work stays on IO; the executor hops to Main only to bind.
            val source = ShizukuExecutor(applicationContext).also { executor = it; it.openSession() }
            DeepMonitoringRuntime.update(initial.id, session, RUNNING_DETAIL)
            while (!requestedStop.get()) {
                val now = System.currentTimeMillis()
                if (SystemClock.elapsedRealtime() - startedElapsed >= MONITOR_DURATION_MILLIS || now >= initial.deadlineEpochMillis) {
                    outcome = MonitorSessionStatus.DEADLINE
                    detail = "30 分钟监控已结束；记录可能有缺口，不代表完整推送链路。"
                    break
                }
                if (now < initial.startedAtEpochMillis) {
                    detail = "系统时间发生回退，已停止监控以避免错误关联。"
                    break
                }
                if (!monitorNotificationsAvailable(this) || !monitorShellReady()) break
                val validBefore = withContext(Dispatchers.IO) { monitorIdentityMatches(this@DeepMonitoringService, initial.identity) }
                if (!validBefore) {
                    outcome = MonitorSessionStatus.IDENTITY_CHANGED
                    detail = "应用选择或安装身份已改变，已停止；未接收本轮事件。"
                    break
                }
                val result = ReadSessionCoordinator.withSession {
                    try {
                        source.exec(SafeCommand.GetTargetProcessEvents(initial.identity.uid, initial.identity.packageName))
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { source.cleanup() }
                    }
                }
                coroutineContext.ensureActive()
                if (requestedStop.get()) break
                val validAfter = withContext(Dispatchers.IO) { monitorIdentityMatches(this@DeepMonitoringService, initial.identity) }
                if (!validAfter) {
                    outcome = MonitorSessionStatus.IDENTITY_CHANGED
                    detail = "应用选择或安装身份已改变，已停止；未接收本轮事件。"
                    break
                }
                if (!result.completedCleanly) break
                val events = ProcessTraceCodec.decode(result.stdout) ?: break
                // A compromised/malformed batch cannot be mixed into a different target's history.
                if (events.any { it.target.packageName != initial.identity.packageName || it.target.uid != initial.identity.uid }) break
                session = session.appendBatch(events, System.currentTimeMillis())
                if (!withContext(Dispatchers.IO) { store.save(session) }) {
                    detail = "监控记录保存失败，已停止；保留可读取的旧记录。"
                    break
                }
                DeepMonitoringRuntime.update(initial.id, session, RUNNING_DETAIL)
                if (session.status == MonitorSessionStatus.LIMIT_REACHED) {
                    outcome = MonitorSessionStatus.LIMIT_REACHED
                    detail = "已达到 500 条记录上限，监控已停止。"
                    break
                }
                if (session.status == MonitorSessionStatus.DEADLINE) {
                    outcome = MonitorSessionStatus.DEADLINE
                    detail = "30 分钟监控已结束；有限记录可能有缺口。"
                    break
                }
                delay(POLL_MILLIS)
            }
            if (requestedStop.get()) {
                outcome = stopOutcome.get() ?: MonitorSessionStatus.STOPPED
                detail = endMessage(outcome)
            }
        } catch (_: CancellationException) {
            outcome = stopOutcome.get() ?: MonitorSessionStatus.INTERRUPTED
            detail = endMessage(outcome)
        } catch (_: Exception) {
            detail = "监控不可用或记录无法保存，已停止；未输出原始系统日志。"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                executor?.closePermanently()
                executor = null
                session = session.copy(status = outcome)
                if (savedInitially && !store.save(session)) {
                    detail = "读取已停止，但结束状态保存失败；磁盘上的上次记录可能显示中断。"
                }
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                deadlineGuard?.cancel()
                deadlineGuard = null
                DeepMonitoringRuntime.finish(initial.id, session, detail)
                stopPendingIntent?.cancel()
                stopPendingIntent = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                collector = null
                activeId = null
                // STOP is another start command: using the original START id can leave an
                // invisible started service behind. Retire every command received by this run.
                stopSelf(lifetime.stopId())
            }
        }
    }

    private fun requestStop() = stopWithReason(MonitorSessionStatus.STOPPED)

    private fun stopWithReason(reason: MonitorSessionStatus) {
        stopOutcome.compareAndSet(null, reason)
        requestedStop.set(true)
        collector?.cancel()
        val source = executor
        // Do not wait for a blocked synchronous Binder call on the UI thread.
        scope.launch(NonCancellable + Dispatchers.IO) { source?.cleanupBestEffort() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        activeId?.let { DeepMonitoringRuntime.stop(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWithReason(MonitorSessionStatus.DEADLINE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopWithReason(MonitorSessionStatus.INTERRUPTED)
        scope.cancel()
        super.onDestroy()
    }

    private fun endMessage(status: MonitorSessionStatus): String = when (status) {
        MonitorSessionStatus.STOPPED -> "监控已停止，已清理读取进程；未改变电池或自启动设置。"
        MonitorSessionStatus.DEADLINE -> "监控时限已到，读取已停止并清理；未自动重启。"
        else -> "监控已中断并清理；保留已有记录，未自动重启。"
    }

    private fun notification(id: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, DeepMonitoringService::class.java).apply {
            action = ACTION_STOP
            data = Uri.parse("guardian-monitor://session/$id")
            putExtra(EXTRA_SESSION, id)
        }, PendingIntent.FLAG_IMMUTABLE).also { stopPendingIntent = it }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_guardian)
            .setContentTitle("Guardian 手动深度监控正在运行")
            .setContentText("最多 30 分钟；仅记录选中应用的进程与 UID 事件")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止监控", stop).build())
            .build()
    }

    companion object {
        internal const val ACTION_START = "io.github.originosfcmguardian.monitor.START"
        internal const val ACTION_STOP = "io.github.originosfcmguardian.monitor.STOP"
        internal const val EXTRA_SESSION = "session_id"
        private const val NOTIFICATION_ID = 42_731
        private const val POLL_MILLIS = 10_000L
        private const val RUNNING_DETAIL = "仅观察进程启动、UID 活跃和空闲。每 10 秒读取有限记录，可能遗漏；不能证明 FCM 来源或完整长连接。"
    }
}
