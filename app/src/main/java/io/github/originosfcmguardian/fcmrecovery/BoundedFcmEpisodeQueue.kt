package io.github.originosfcmguardian.fcmrecovery

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/** Diagnostic FIFO only. No caller waits for initialization, storage or queue space. */
internal class BoundedFcmEpisodeQueue(
    initialize: () -> Unit,
    private val coverageGap: (Long, Long) -> Unit,
    private val published: (Long, Boolean) -> Unit,
    private val dropped: (Long) -> Unit,
    capacity: Int = 32,
) {
    private val epoch = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private var appliedEpoch = 0L // Sole worker owner.
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity.also { require(it in 1..32) }),
        { task -> Thread(task, "guardian-fcm-episodes").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    init { worker.execute { runCatching(initialize); publish() } }

    fun offer(atMillis: Long, elapsedMillis: Long, action: () -> Unit) {
        val captured = epoch.get()
        try {
            worker.execute {
                if (captured != epoch.get()) return@execute
                if (appliedEpoch != captured) {
                    runCatching { coverageGap(atMillis, elapsedMillis) }
                    appliedEpoch = captured
                }
                runCatching(action).onFailure { invalidateCoverage() }
                publish()
            }
        } catch (_: RejectedExecutionException) {
            invalidateCoverage()
        }
    }

    private fun invalidateCoverage() {
        epoch.incrementAndGet()
        val count = droppedCount.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 }
        runCatching { dropped(count) }
    }

    private fun publish() = runCatching { published(droppedCount.get(), appliedEpoch != epoch.get()) }.let { Unit }

    /** FIFO acknowledgement after the runtime has closed every producer. Does not discard evidence. */
    internal suspend fun awaitMigrationDrain(): Boolean {
        val drained = CompletableDeferred<Unit>()
        try { worker.execute { drained.complete(Unit) } }
        catch (_: RejectedExecutionException) { return false }
        drained.await()
        return true
    }

    /** Test cleanup only; production never uses stopping this diagnostic queue as an authority action. */
    internal fun closeForTest() { worker.shutdownNow() }
}

/** A worker publication cannot erase a newer concurrent queue-drop indication. */
internal fun mergeFcmEpisodeSnapshot(previous: FcmRecoveryEpisodeSnapshot, incoming: FcmRecoveryEpisodeSnapshot) =
    if (previous.droppedUpdates > incoming.droppedUpdates)
        previous.copy(availability = FcmEpisodeAvailability.UNAVAILABLE)
    else incoming

internal fun droppedFcmEpisodeSnapshot(previous: FcmRecoveryEpisodeSnapshot, dropped: Long) =
    previous.copy(availability = FcmEpisodeAvailability.UNAVAILABLE, droppedUpdates = maxOf(previous.droppedUpdates, dropped))
