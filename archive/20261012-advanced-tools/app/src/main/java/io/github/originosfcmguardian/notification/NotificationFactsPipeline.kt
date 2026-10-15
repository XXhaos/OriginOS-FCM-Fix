package io.github.originosfcmguardian.notification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal data class CapturedNotificationFact(
    val packageName: String,
    val uid: Int,
    val observedAtEpochMillis: Long,
    val kind: NotificationFactKind,
)

internal data class NotificationCaptureTarget(
    val packageName: String,
    val uid: Int,
)

internal enum class NotificationFactEligibility {
    ACCEPT,
    REJECT_EVENT,
    INVALIDATE_LIFECYCLE,
}

internal interface NotificationFactsTaskQueue {
    /** Must be a prompt, non-blocking bounded enqueue. */
    fun executeEvent(task: () -> Unit): Boolean
    /** Control tasks are never removed by [clearPending]. */
    fun executeControl(task: () -> Unit): Boolean
    fun clearPending(): Int
}

private data class NotificationCaptureGate(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val clearing: Boolean = false,
    val generation: Long = 0,
    val selectedTargets: Set<NotificationCaptureTarget> = emptySet(),
) {
    fun accepts(captured: CapturedNotificationFact): Boolean =
        enabled && connected && !clearing &&
            NotificationCaptureTarget(captured.packageName, captured.uid) in selectedTargets
}

/**
 * Binder callbacks only read [gate] and attempt a bounded enqueue. PackageManager, binder and
 * fsync work stays on the serial worker. Explicit disable/clear callers run on Dispatchers.IO and
 * use a queued barrier so no stale durable write can complete after those calls return.
 */
internal class NotificationFactsPipeline(
    private val queue: NotificationFactsTaskQueue,
    private val eligibility: (CapturedNotificationFact) -> NotificationFactEligibility,
    private val record: (NotificationFact) -> RecordNotificationFactResult,
    private val onChanged: () -> Unit,
) {
    private val gate = AtomicReference(NotificationCaptureGate())
    private val droppedEvents = AtomicInteger(0)

    fun setEnabled(value: Boolean) {
        updateGate(clearPending = !value) { current ->
            if (current.enabled == value) current
            else current.copy(enabled = value, generation = current.generation + 1)
        }
    }

    /** Explicit user disable. Must only be called off the main thread. */
    fun disableAndAwait(): Boolean {
        updateGate(clearPending = true) { current ->
            current.copy(enabled = false, generation = current.generation + 1)
        }
        return enqueueBarrier { true } ?: false
    }

    fun setConnected(value: Boolean) {
        updateGate(clearPending = !value) { current ->
            if (current.connected == value) current
            else current.copy(connected = value, generation = current.generation + 1)
        }
    }

    /** Every committed selection revision invalidates queued work, even if package/UID is reused. */
    fun updateSelectedTargets(targets: Set<NotificationCaptureTarget>, forceRevision: Boolean) {
        updateGate(clearPending = forceRevision || gate.get().selectedTargets != targets) { current ->
            if (!forceRevision && current.selectedTargets == targets) current
            else current.copy(selectedTargets = targets, generation = current.generation + 1)
        }
    }

    fun isConnected(): Boolean = gate.get().let { it.enabled && it.connected && !it.clearing }

    fun isEnabled(): Boolean = gate.get().enabled

    fun droppedEvents(): Int = droppedEvents.get()

    fun offer(captured: CapturedNotificationFact) {
        val capturedGate = gate.get()
        if (!capturedGate.accepts(captured)) return
        if (!queue.executeEvent { process(capturedGate.generation, captured) }) {
            addDropped(1)
        }
    }

    /** Explicit user clear. Captures stay closed until the clear action has run behind old work. */
    fun clearAndAwait(action: () -> Boolean): Boolean {
        updateGate(clearPending = true) { current ->
            current.copy(clearing = true, generation = current.generation + 1)
        }
        val result = enqueueBarrier(action) ?: false
        if (result) {
            droppedEvents.set(0)
            updateGate(clearPending = true) { current ->
                current.copy(clearing = false, generation = current.generation + 1)
            }
        }
        return result
    }

    private fun process(capturedGeneration: Long, captured: CapturedNotificationFact) {
        if (!isCurrent(capturedGeneration, captured)) {
            addDropped(1)
            onChanged()
            return
        }
        when (eligibility(captured)) {
            NotificationFactEligibility.REJECT_EVENT -> return
            NotificationFactEligibility.INVALIDATE_LIFECYCLE -> {
                setConnected(false)
                addDropped(1)
                onChanged()
                return
            }
            NotificationFactEligibility.ACCEPT -> Unit
        }
        if (!isCurrent(capturedGeneration, captured)) {
            addDropped(1)
            onChanged()
            return
        }
        val result = record(
            NotificationFact(
                packageName = captured.packageName,
                observedAtEpochMillis = captured.observedAtEpochMillis,
                kind = captured.kind,
            ),
        )
        if (result != RecordNotificationFactResult.STORED) addDropped(1)
        onChanged()
    }

    private fun isCurrent(generation: Long, captured: CapturedNotificationFact): Boolean {
        val current = gate.get()
        return current.generation == generation && current.accepts(captured)
    }

    private fun updateGate(
        clearPending: Boolean,
        transform: (NotificationCaptureGate) -> NotificationCaptureGate,
    ) {
        while (true) {
            val current = gate.get()
            val updated = transform(current)
            if (updated == current) return
            if (gate.compareAndSet(current, updated)) {
                break
            }
        }
        if (clearPending) addDropped(queue.clearPending())
        onChanged()
    }

    private fun <T> enqueueBarrier(action: () -> T): T? {
        val result = AtomicReference<Result<T>?>(null)
        val completed = CountDownLatch(1)
        if (!queue.executeControl {
                result.set(runCatching(action))
                completed.countDown()
            }
        ) {
            addDropped(1)
            onChanged()
            return null
        }
        return try {
            if (!completed.await(CONTROL_WAIT_SECONDS, TimeUnit.SECONDS)) return null
            result.get()?.getOrNull()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun addDropped(count: Int) {
        if (count <= 0) return
        droppedEvents.getAndUpdate { current ->
            (current.toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private companion object {
        const val CONTROL_WAIT_SECONDS = 10L
    }
}
