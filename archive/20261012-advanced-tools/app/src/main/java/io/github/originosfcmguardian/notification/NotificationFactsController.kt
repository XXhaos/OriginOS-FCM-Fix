package io.github.originosfcmguardian.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.identityStatus
import java.util.concurrent.atomic.AtomicBoolean

class NotificationFactsController(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = AndroidNotificationFactsPreferences(applicationContext)
    private val store = NotificationFactsStore(applicationContext)
    private val targetStore = TargetSelectionStore(applicationContext)
    private val targetProbe = TargetPackageProbe(applicationContext)
    private val componentName = ComponentName(
        applicationContext,
        GuardianNotificationListenerService::class.java,
    )

    init {
        NotificationFactsRuntime.attach(applicationContext)
    }

    /** Read-only snapshot. In particular, it never requests a service rebind. */
    fun snapshot(): NotificationFactsUiState {
        val validTargets = validTargets()
        val history = store.load()
        val accessGranted = hasNotificationAccess(applicationContext)
        return NotificationFactsUiState(
            enabled = preferences.isEnabled(),
            accessGranted = accessGranted,
            connected = NotificationFactsRuntime.isConnected(),
            targetCount = validTargets.size,
            validTargetPackages = validTargets.map { it.packageName },
            events = (history as? NotificationFactsSnapshot.Available)
                ?.events
                .orEmpty(),
            historyCorrupt = history == NotificationFactsSnapshot.Corrupt,
            droppedEvents = NotificationFactsRuntime.droppedEvents(),
            stopPersistenceUncertain = NotificationFactsRuntime.isStopPersistenceUncertain(),
        )
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            if (validTargetCount() < 1 || !preferences.setEnabled(true)) return false
            NotificationFactsRuntime.setStopPersistenceUncertain(false)
            NotificationFactsRuntime.setEnabled(applicationContext, true)
            if (hasNotificationAccess(applicationContext)) {
                runCatching { NotificationListenerService.requestRebind(componentName) }
            }
            return true
        }
        return disableNotificationFacts(
            pause = { NotificationFactsRuntime.disableAndAwait(applicationContext) },
            unbind = NotificationFactsRuntime::requestUnbind,
            persist = { preferences.setEnabled(false) },
            markUncertain = NotificationFactsRuntime::setStopPersistenceUncertain,
        )
    }

    fun clearHistory(): Boolean = NotificationFactsRuntime.clearHistory(applicationContext)

    /** Reads an immutable export snapshot after checking the selected package's pinned identity. */
    fun exportSnapshot(query: NotificationFactsQuery): ByteArray {
        val before = validTargets().firstOrNull { it.packageName == query.packageName }
            ?: throw IllegalStateException("Selected package identity is no longer valid")
        val history = store.load()
        val events = when (history) {
            NotificationFactsSnapshot.Empty -> emptyList()
            is NotificationFactsSnapshot.Available -> history.events
            NotificationFactsSnapshot.Corrupt -> throw IllegalStateException("Notification history is corrupt")
        }
        check(validTargets().any { it == before }) { "Selected package identity changed" }
        return encodeNotificationFactsExport(queryNotificationFacts(events, query))
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        // Bring back the access list when the Settings task is showing another app's settings.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun registerListener(listener: () -> Unit): AutoCloseable {
        val preferenceSubscription = preferences.registerListener(listener)
        val runtimeSubscription = NotificationFactsRuntime.registerListener(listener)
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                preferenceSubscription.close()
                runtimeSubscription.close()
            }
        }
    }

    private fun validTargetCount(): Int = validTargets().size

    private fun validTargets(): List<SelectedTargetIdentity> = runCatching {
        targetStore.load().filter { stored ->
            identityStatus(stored, targetProbe.identity(stored.packageName)) == TargetIdentityStatus.MATCH
        }
    }.getOrDefault(emptyList())
}

/** A failed preference write must never leave the live listener capturing. */
internal fun disableNotificationFacts(
    pause: () -> Boolean,
    unbind: () -> Unit,
    persist: () -> Boolean,
    markUncertain: (Boolean) -> Unit,
): Boolean {
    markUncertain(true)
    runCatching(unbind)
    val drained = runCatching(pause).getOrDefault(false)
    val saved = runCatching(persist).getOrDefault(false)
    markUncertain(!(drained && saved))
    return drained && saved
}
