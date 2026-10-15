package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

internal enum class GmsRpcLane {
    BACKGROUND_OBSERVATION, INTERACTIVE_OBSERVATION, HEARTBEAT_WRITE, REVOKE,
}

/** Process lifetime ownership, not a pool per short-lived binding or polling cycle. */
internal object ProcessGmsRpcLanes {
    private val owner = GmsRpcLanes()
    fun lane(lane: GmsRpcLane): GmsObservationRpc = owner.lane(lane)
    val cleanup: GmsBindingCleanup get() = owner.cleanup
    fun isQuiescent(): Boolean = GmsRpcLane.entries.all { lane(it).isQuiescent() }
}

/** Separately owned in tests so fault injection never poisons the process singleton. */
internal class GmsRpcLanes(budgetMillis: Long = 11_000) : AutoCloseable {
    val cleanup = GmsBindingCleanup()
    private val lanes = GmsRpcLane.entries.associateWith {
        GmsObservationRpc(budgetMillis, "guardian-gms-${it.name.lowercase()}")
    }
    fun lane(lane: GmsRpcLane): GmsObservationRpc = lanes.getValue(lane)
    override fun close() { lanes.values.forEach { it.close() }; cleanup.close() }
}

/** Reserve cleanup capacity before binding, so healthy concurrent retirements cannot be dropped. */
internal class GmsBindingCleanup(private val capacity: Int = 8) : AutoCloseable {
    init { require(capacity > 0) }
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        { runnable -> Thread(runnable, "guardian-gms-cleanup").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val retained = mutableSetOf<Lease>()
    private var closed = false

    inner class Lease internal constructor() {
        internal var action: (() -> Unit)? = null
        internal var queued = false
        /** May be called repeatedly by retirement and stale callbacks; only one cleanup runs. */
        fun cleanup(action: () -> Unit) = synchronized(this@GmsBindingCleanup) {
            if (this !in retained) return@synchronized
            if (this.action == null) this.action = action
            submit(this)
        }
        /** Valid only when binding never started, hence no manager connection needs removal. */
        fun unused() = synchronized(this@GmsBindingCleanup) {
            check(!queued && action == null)
            retained.remove(this)
            Unit
        }
    }

    @Synchronized fun acquire(): Lease? {
        if (closed) return null
        // Retry a failed unbind only on a later external opportunity, never a hot loop.
        retained.toList().forEach(::submit)
        if (retained.size >= capacity) return null
        return Lease().also { retained.add(it) }
    }

    private fun submit(lease: Lease) {
        val action = lease.action ?: return
        if (closed || lease.queued) return
        lease.queued = true
        try {
            worker.execute {
                var success = false
                try { action(); success = true } catch (_: Throwable) { }
                finally {
                    synchronized(this) {
                        lease.queued = false
                        if (success) retained.remove(lease)
                    }
                }
            }
        } catch (_: RejectedExecutionException) { lease.queued = false }
    }

    @Synchronized override fun close() { closed = true; worker.shutdownNow() }
}

internal class GmsObservationRpc(
    private val budgetMillis: Long = 11_000,
    workerName: String = "guardian-gms-observation-rpc",
) {
    init { require(budgetMillis in 1..11_000) }
    // Capacity one is only a completion handoff: busy stays set through stuck actions, so
    // abandoned system calls cannot accumulate queued work. Release before continuation resume.
    private val reader = worker(workerName)
    private val readerBusy = AtomicBoolean(false)
    internal fun isQuiescent(): Boolean = !readerBusy.get()
    /** Also bounds synchronous availability and binding Binder calls before the read endpoint. */
    suspend fun <T> bounded(action: () -> T): T = withTimeout(budgetMillis) {
        suspendCancellableCoroutine { continuation ->
            if (!readerBusy.compareAndSet(false, true)) {
                continuation.resumeWith(Result.failure(RejectedExecutionException())); return@suspendCancellableCoroutine
            }
            try { reader.submit {
                if (!continuation.isActive) { readerBusy.set(false); return@submit }
                try {
                    val value = action()
                    readerBusy.set(false)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Throwable) {
                    readerBusy.set(false)
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            } } catch (error: RejectedExecutionException) {
                readerBusy.set(false)
                continuation.resumeWith(Result.failure(error)); return@suspendCancellableCoroutine
            }
            // Leave an admitted handoff to run its inactive guard and release admission.
            // A stuck Binder keeps admission occupied; the cancelled caller returns promptly.
        }
    }
    suspend fun read(requestId: Long, serviceUid: () -> Int, payload: () -> String?): GmsConnectionObservation {
        fun unknown(reason: GmsUnknown) = GmsConnectionObservation.Unknown(requestId, reason)
        if (requestId <= 0) return unknown(GmsUnknown.INVALID_REQUEST)
        return try { withTimeout(budgetMillis) {
            suspendCancellableCoroutine { continuation ->
                if (!readerBusy.compareAndSet(false, true)) {
                    continuation.resume(unknown(GmsUnknown.BUSY)); return@suspendCancellableCoroutine
                }
                try { reader.submit {
                    fun publish(result: GmsConnectionObservation) { readerBusy.set(false); if (continuation.isActive) continuation.resume(result) }
                    try {
                        if (!continuation.isActive) { readerBusy.set(false); return@submit }
                        val uid = serviceUid()
                        if (!continuation.isActive) { readerBusy.set(false); return@submit }
                        if (uid != 2000) { publish(unknown(GmsUnknown.SHELL_UNAVAILABLE)); return@submit }
                        val raw = payload()
                        publish(GmsObservationCodec.decode(raw, requestId))
                    } catch (_: Throwable) { publish(unknown(GmsUnknown.BRIDGE_UNAVAILABLE)) }
                } } catch (_: RejectedExecutionException) {
                    readerBusy.set(false)
                    continuation.resume(unknown(GmsUnknown.BUSY)); return@suspendCancellableCoroutine
                }
            }
        } } catch (cancelled: CancellationException) {
            unknown(if (cancelled is TimeoutCancellationException) GmsUnknown.TIMED_OUT else GmsUnknown.CANCELLED)
        }
    }
    /** Uses the same admission as bounded calls; production cleanup uses the independent REVOKE lane. */
    fun cleanupBestEffort(action: () -> Unit): Boolean {
        if (!readerBusy.compareAndSet(false, true)) return false
        return try {
            reader.execute {
                try { runCatching(action) } finally { readerBusy.set(false) }
            }
            true
        } catch (_: RejectedExecutionException) { readerBusy.set(false); false }
    }
    fun close() { reader.shutdownNow() }
    private fun worker(name: String) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
}
