package io.github.originosfcmguardian.notification

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.identityStatus
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationFactsRuntime {
    private const val MAX_PENDING_EVENTS = 64
    private val attachmentLock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val stopPersistenceUncertain = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        15L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_PENDING_EVENTS),
        ThreadFactory { task -> Thread(task, "guardian-notification-facts").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }
    private val taskQueue = object : NotificationFactsTaskQueue {
        override fun executeEvent(task: () -> Unit): Boolean = try {
            executor.execute(EventTask(task))
            true
        } catch (_: RejectedExecutionException) {
            false
        }

        override fun executeControl(task: () -> Unit): Boolean = try {
            executor.execute(ControlTask(task))
            true
        } catch (_: RejectedExecutionException) {
            false
        }

        override fun clearPending(): Int {
            var removed = 0
            executor.queue.toList().forEach { task ->
                if (task is EventTask && executor.queue.remove(task)) removed++
            }
            return removed
        }
    }

    private class EventTask(private val action: () -> Unit) : Runnable {
        override fun run() = action()
    }

    private class ControlTask(private val action: () -> Unit) : Runnable {
        override fun run() = action()
    }

    @Volatile
    private var applicationContext: Context? = null
    @Volatile
    private var serviceReference = WeakReference<GuardianNotificationListenerService>(null)
    private var selectionSubscription: AutoCloseable? = null

    private val pipeline = NotificationFactsPipeline(
        queue = taskQueue,
        eligibility = ::eligibility,
        record = { event ->
            val context = applicationContext
            if (context == null) {
                RecordNotificationFactResult.FAILED
            } else {
                NotificationFactsStore(context).record(event)
            }
        },
        onChanged = ::notifyListeners,
    )

    fun attach(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        synchronized(attachmentLock) {
            if (selectionSubscription == null) {
                val targetStore = TargetSelectionStore(appContext)
                selectionSubscription = targetStore.registerListener {
                    refreshSelectedTargets(appContext, forceRevision = true)
                }
                refreshSelectedTargets(appContext, forceRevision = false)
            }
        }
        pipeline.setEnabled(
            AndroidNotificationFactsPreferences(appContext).isEnabled() &&
                !stopPersistenceUncertain.get(),
        )
    }

    fun attachService(service: GuardianNotificationListenerService) {
        attach(service)
        serviceReference = WeakReference(service)
    }

    fun detachService(service: GuardianNotificationListenerService) {
        if (serviceReference.get() === service) {
            serviceReference.clear()
            pipeline.setConnected(false)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        applicationContext = context.applicationContext
        pipeline.setEnabled(enabled)
    }

    fun disableAndAwait(context: Context): Boolean {
        applicationContext = context.applicationContext
        return pipeline.disableAndAwait()
    }

    fun setConnected(connected: Boolean) = pipeline.setConnected(connected)

    fun isConnected(): Boolean = pipeline.isConnected()

    fun isEnabled(): Boolean = pipeline.isEnabled()

    fun droppedEvents(): Int = pipeline.droppedEvents()

    fun isStopPersistenceUncertain(): Boolean = stopPersistenceUncertain.get()

    fun setStopPersistenceUncertain(value: Boolean) {
        if (stopPersistenceUncertain.getAndSet(value) != value) notifyListeners()
    }

    fun offer(captured: CapturedNotificationFact) = pipeline.offer(captured)

    fun clearHistory(context: Context): Boolean {
        attach(context)
        return pipeline.clearAndAwait { NotificationFactsStore(context).clear() }
    }

    fun requestUnbind() {
        pipeline.setConnected(false)
        serviceReference.get()?.requestGuardianUnbind()
    }

    fun registerListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun eligibility(captured: CapturedNotificationFact): NotificationFactEligibility {
        val context = applicationContext ?: return NotificationFactEligibility.INVALIDATE_LIFECYCLE
        if (!AndroidNotificationFactsPreferences(context).isEnabled()) {
            return NotificationFactEligibility.INVALIDATE_LIFECYCLE
        }
        if (!hasNotificationAccess(context)) {
            return NotificationFactEligibility.INVALIDATE_LIFECYCLE
        }
        val targetStore = TargetSelectionStore(context)
        val selected = runCatching { targetStore.load() }.getOrNull()
            ?: return NotificationFactEligibility.REJECT_EVENT
        if (selected.none { it.packageName == captured.packageName && it.uid == captured.uid }) {
            return NotificationFactEligibility.REJECT_EVENT
        }
        val current = TargetPackageProbe(context).identity(captured.packageName)
        val refreshedSelection = runCatching { targetStore.load() }.getOrNull()
            ?: return NotificationFactEligibility.REJECT_EVENT
        val identityEligibility = notificationFactEligibility(captured, refreshedSelection, current)
        if (identityEligibility != NotificationFactEligibility.ACCEPT) return identityEligibility
        if (!AndroidNotificationFactsPreferences(context).isEnabled() || !hasNotificationAccess(context)) {
            return NotificationFactEligibility.INVALIDATE_LIFECYCLE
        }
        return NotificationFactEligibility.ACCEPT
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching(listener) }
    }

    private fun refreshSelectedTargets(context: Context, forceRevision: Boolean) {
        val targets = runCatching { TargetSelectionStore(context).load() }
            .getOrDefault(emptyList())
            .mapTo(linkedSetOf()) { selected ->
                NotificationCaptureTarget(selected.packageName, selected.uid)
            }
        pipeline.updateSelectedTargets(targets, forceRevision)
    }
}

internal fun notificationFactEligibility(
    captured: CapturedNotificationFact,
    selected: List<SelectedTargetIdentity>,
    currentIdentity: SelectedTargetIdentity?,
): NotificationFactEligibility {
    val stored = selected.singleOrNull {
        it.packageName == captured.packageName && it.uid == captured.uid
    } ?: return NotificationFactEligibility.REJECT_EVENT
    return if (identityStatus(stored, currentIdentity) == TargetIdentityStatus.MATCH) {
        NotificationFactEligibility.ACCEPT
    } else {
        NotificationFactEligibility.REJECT_EVENT
    }
}

internal fun hasNotificationAccess(context: Context): Boolean = runCatching {
    context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(
            ComponentName(context, GuardianNotificationListenerService::class.java),
        )
}.getOrDefault(false)
