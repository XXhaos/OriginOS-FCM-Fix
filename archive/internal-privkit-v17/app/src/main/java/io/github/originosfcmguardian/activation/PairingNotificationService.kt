package io.github.originosfcmguardian.activation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.originosfcmguardian.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import priv.kit.core.Privilege
import java.util.UUID

data class PairingNotificationStatus(val active: Boolean = false, val message: String = "通知配对尚未开始。")

/** Explicit and finite. No redelivery, boot path, daemon startup, recovery or code persistence. */
class PairingNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: InternalActivationController
    private var session: PairingNotificationSession? = null
    private var discovery: Job? = null
    private var inputDeadline: Job? = null
    private var totalDeadline: Job? = null
    private var replyIntent: PendingIntent? = null
    private var cancelIntent: PendingIntent? = null
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        controller = InternalActivationController.get(applicationContext)
        currentService = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val identity = intent?.data?.takeIf { it.scheme == "guardian-pairing" && it.host == "session" }?.lastPathSegment
        when (intent?.action) {
            ACTION_START -> {
                val candidate = gate.find(identity)
                if (session == null && candidate != null && candidate.start(identity, now())) begin(candidate)
                else if (session == null) {
                    if (candidate != null) {
                        candidate.close()
                        cancelController(controller, candidate)
                        synchronized(runtimeLock) {
                            if (gate.release(candidate)) mutableStatus.value = PairingNotificationStatus(false, "配对输入窗口已过期，请重新主动开始。")
                        }
                    }
                    stopSelf(startId)
                }
            }
            ACTION_REPLY -> {
                receiveReply(intent, identity)
                if (session == null) stopSelf(startId) // Cold or recreated-process replies are never admitted.
            }
            ACTION_CANCEL -> {
                if (session?.matches(identity) == true) retire("通知配对已取消。请回应用核对可能仍在清理的操作。")
                else if (session == null) stopSelf(startId)
            }
            else -> if (session == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin(own: PairingNotificationSession) {
        session = own
        try {
            check(notificationsAvailable(this))
            val initial = notification(own, PairingNotificationPhase.DISCOVERING, SEARCHING)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(NOTIFICATION_ID, initial)
        } catch (_: RuntimeException) {
            retire("无法显示配对通知；未开始搜索或配对。")
            return
        }
        publish(own, SEARCHING)
        totalDeadline = scope.launch {
            delay(own.remainingTotal(now()))
            retire("配对会话已超时。已取消本次操作，请回应用核对清理状态。")
        }
        inputDeadline = scope.launch {
            delay(own.remainingInput(now()))
            if (own.phase != PairingNotificationPhase.WORKING)
                retire("60 秒搜索与输入窗口已结束。请重新主动开始通知配对。")
        }
        discovery = scope.launch {
            try {
                val remaining = own.remainingInput(now())
                check(remaining > 0)
                val port = withTimeout(remaining) {
                    withContext(Dispatchers.IO) {
                        Privilege.createAdbManager().discoverPairingPort(timeoutMillis = remaining)
                    }
                }
                if (!own.discovered(port, now()) || !gate.owns(own)) {
                    if (session === own) retire("搜索或输入窗口已结束，未开始配对。")
                    return@launch
                }
                val waiting = "已发现系统配对服务。保持配对码对话框开启，在此通知输入六位配对码。"
                updateNotification(own, PairingNotificationPhase.WAITING, waiting)
                publish(own, waiting)
            } catch (failure: CancellationException) {
                if (session === own && own.matches(own.identity)) {
                    val safe = activationDiagnostic(ActivationStage.PAIRING_DISCOVERY, failure)
                    retire("${safe.guidance} [${safe.code}]")
                }
            } catch (failure: LinkageError) {
                val safe = activationDiagnostic(ActivationStage.PAIRING_DISCOVERY, failure)
                if (session === own) retire("${safe.guidance} [${safe.code}]")
            } catch (failure: Exception) {
                val safe = activationDiagnostic(ActivationStage.PAIRING_DISCOVERY, failure)
                if (session === own) retire("${safe.guidance} [${safe.code}]")
            }
        }
    }

    private fun receiveReply(intent: Intent, identity: String?) {
        val own = session
        val claim = own?.claimReply(identity, now())
        if (own == null || claim == null || !gate.owns(own)) {
            clearReply(intent)
            return
        }
        // Remove the reply action before inspecting any RemoteInput data. One reply per session.
        replyIntent?.cancel()
        replyIntent = null
        try {
            updateNotification(own, PairingNotificationPhase.WORKING, "正在配对 Guardian，请稍候。")
        } catch (_: RuntimeException) {
            clearReply(intent)
            retire("无法更新配对通知，未提交配对。")
            return
        }
        publish(own, "正在配对 Guardian，请稍候。")
        var secret: String? = null
        try {
            val results = RemoteInput.getResultsFromIntent(intent)
            val input = results?.getCharSequence(REMOTE_INPUT_KEY)
            if (validNotificationPairingCode(input)) secret = input.toString()
            results?.clear()
            clearReply(intent)
            val code = secret
            if (code == null) {
                retire("配对码须为六位数字，未提交配对。请重新主动开始通知配对。")
                return
            }
            val remainingBudget = minOf(claim.operationBudgetMillis, own.remainingTotal(now()) - PAIRING_CLEANUP_RESERVE_MILLIS)
            if (remainingBudget <= 0) {
                retire("配对会话时限已到，未提交配对。请重新主动开始。")
                return
            }
            // Controller admission reads private files/settings. Never block timeout/cancel on Main.
            scope.launch(Dispatchers.IO) {
                try {
                    own.operationToken.ensureLive()
                    val accepted = controller.pairFromNotification(code, claim.port, own.identity, remainingBudget, own.operationToken) { result ->
                        main.post {
                            if (session === own && gate.owns(own) && own.matches(own.identity)) retire(pairingNotificationResult(result))
                        }
                    }
                    if (!accepted) main.post {
                        if (session === own && own.operationToken.isLive)
                            retire(pairingNotificationResult(controller.status.value.copy(paired = false)))
                    }
                } catch (_: CancellationException) {
                    // Revocation is already terminal; never resurrect or resubmit the reply.
                } catch (_: RuntimeException) {
                    main.post {
                        if (session === own && own.operationToken.isLive) retire("无法提交本次通知回复，未确认配对。请重新主动开始。")
                    }
                }
            }
        } catch (_: RuntimeException) {
            retire("无法读取或提交本次通知回复，未确认配对。请重新主动开始。")
        } finally {
            secret = null
            clearReply(intent)
        }
    }

    private fun clearReply(intent: Intent) {
        intent.clipData = null
        intent.replaceExtras(null as Bundle?)
    }

    private fun updateNotification(own: PairingNotificationSession, phase: PairingNotificationPhase, text: String) {
        check(notificationsAvailable(this))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(own, phase, text))
    }

    /** Foreground timeout never waits for the controller's non-cancellable transaction cleanup. */
    private fun retire(message: String) {
        val own = session
        session = null
        own?.close() // Revoke nonce before cancellation or any asynchronous completion.
        discovery?.cancel(); discovery = null
        inputDeadline?.cancel(); inputDeadline = null
        totalDeadline?.cancel(); totalDeadline = null
        replyIntent?.cancel(); replyIntent = null
        cancelIntent?.cancel(); cancelIntent = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (own != null) {
            synchronized(runtimeLock) {
                if (gate.release(own)) mutableStatus.value = PairingNotificationStatus(false, message)
            }
            runCatching {
                if (notificationsAvailable(this)) getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(own, PairingNotificationPhase.TERMINAL, message))
            }
        }
        stopSelf(latestStartId)
        if (own != null) cancelController(controller, own)
    }

    override fun onTimeout(startId: Int) {
        retire("系统配对时限已到，已取消本次操作。请回应用核对清理状态。")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        retire("系统配对时限已到，已取消本次操作。请回应用核对清理状态。")
    }

    override fun onDestroy() {
        val own = session
        session = null
        own?.close()
        if (own != null) {
            synchronized(runtimeLock) {
                if (gate.release(own)) mutableStatus.value = PairingNotificationStatus(false, "通知配对会话已结束，请重新主动开始。")
            }
        }
        replyIntent?.cancel(); cancelIntent?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (own != null) cancelController(controller, own)
        if (currentService === this) currentService = null
        super.onDestroy()
    }

    private fun notification(own: PairingNotificationSession, phase: PairingNotificationPhase, text: String): Notification {
        val plan = pairingNotificationPlan(phase)
        val open = PendingIntent.getActivity(this, 0, Intent(this, InternalActivationActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian_tile).setContentTitle("Guardian 通知配对")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(plan.ongoing).setAutoCancel(plan.autoCancel)
            .setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (plan.ongoing) {
            val cancel = cancelIntent ?: PendingIntent.getService(this, 1,
                sessionIntent(this, ACTION_CANCEL, own.identity), PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                .also { cancelIntent = it }
            builder.addAction(Notification.Action.Builder(null, "取消配对", cancel).build())
        }
        if (plan.allowReply) {
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val reply = replyIntent ?: PendingIntent.getService(this, 2,
                sessionIntent(this, ACTION_REPLY, own.identity), PendingIntent.FLAG_CANCEL_CURRENT or mutable)
                .also { replyIntent = it }
            val input = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("六位配对码")
                .setAllowFreeFormInput(true).build()
            builder.addAction(Notification.Action.Builder(null, "输入配对码", reply)
                .addRemoteInput(input).setAllowGeneratedReplies(false).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build())
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "guardian_pairing"
        private const val NOTIFICATION_ID = 4107
        private const val REMOTE_INPUT_KEY = "guardian_pairing_code"
        private const val ACTION_START = "io.github.originosfcmguardian.pairing.START"
        private const val ACTION_REPLY = "io.github.originosfcmguardian.pairing.REPLY"
        private const val ACTION_CANCEL = "io.github.originosfcmguardian.pairing.CANCEL"
        private const val SEARCHING = "正在搜索系统配对服务。请打开无线调试的配对码对话框；搜索与输入共用 60 秒窗口。"
        private val runtimeLock = Any()
        // Lives only to drain exact-owner cancellation after service teardown; no retry/start path.
        private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun cancelController(controller: InternalActivationController, own: PairingNotificationSession) {
            cancellationScope.launch { controller.cancelNotificationPairing(own.identity) }
        }
        private val gate = PairingNotificationGate()
        private val mutableStatus = MutableStateFlow(PairingNotificationStatus())
        val status = mutableStatus.asStateFlow()
        @Volatile private var currentService: PairingNotificationService? = null
        private fun now() = SystemClock.elapsedRealtime()
        private fun sessionIntent(context: Context, action: String, identity: String) =
            Intent(context, PairingNotificationService::class.java).setAction(action)
                .setData(Uri.Builder().scheme("guardian-pairing").authority("session").appendPath(identity).build())

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "无线调试配对", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null); channel.enableVibration(false); channel.enableLights(false); channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun notificationsAvailable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            return channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked != true } ?: true
        }

        /** Called only by an explicit foreground UI action; reservation is visible synchronously. */
        fun start(context: Context): Boolean {
            ensureChannel(context)
            if (!notificationsAvailable(context)) return false
            val controller = InternalActivationController.get(context.applicationContext)
            val own = synchronized(runtimeLock) {
                val reserved = gate.reserve(UUID.randomUUID().toString(), now()) ?: return false
                if (!controller.reserveNotificationPairing(reserved.identity)) { gate.release(reserved); return false }
                mutableStatus.value = PairingNotificationStatus(true, SEARCHING)
                reserved
            }
            return try {
                context.startForegroundService(sessionIntent(context, ACTION_START, own.identity))
                true
            } catch (_: RuntimeException) {
                own.close()
                cancelController(controller, own)
                synchronized(runtimeLock) {
                    if (gate.release(own)) mutableStatus.value = PairingNotificationStatus(false, "配对通知服务无法启动，未开始配对。")
                }
                false
            }
        }

        fun cancel(context: Context) {
            val own = synchronized(runtimeLock) { gate.active()?.also { it.close() } } ?: return
            val controller = InternalActivationController.get(context.applicationContext)
            cancelController(controller, own)
            Handler(Looper.getMainLooper()).post {
                val service = currentService
                if (service?.session === own) service.retire("通知配对已取消。请回应用核对清理状态。")
                else synchronized(runtimeLock) {
                    if (gate.release(own)) mutableStatus.value = PairingNotificationStatus(false, "通知配对已取消。")
                }
            }
        }

        private fun publish(own: PairingNotificationSession, message: String) = synchronized(runtimeLock) {
            if (gate.owns(own) && own.matches(own.identity)) mutableStatus.value = PairingNotificationStatus(true, message)
        }
    }
}
