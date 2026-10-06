package io.github.originosfcmguardian.doze

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One active fixed read with terminal cancellation; no queued/replayed work or authority. */
internal class DozeOwnerReadOnlySession(
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val closed = AtomicBoolean(false)
    private val current = AtomicReference<Scope?>()

    fun read(block: (Scope) -> DozeOwnerCandidates): DozeOwnerReadOnlySummary {
        val requested = wallTime()
        val started = nanoTime()
        val scope = Scope(started, nanoTime)
        fun rejected(code: DozeOwnerReadOnlyCode) = DozeOwnerReadOnlySummary(
            requested, wallTime(), TimeUnit.NANOSECONDS.toMillis(nanoTime() - started).coerceAtLeast(0), code,
        )
        if (closed.get()) return rejected(DozeOwnerReadOnlyCode.CANCELLED)
        if (!current.compareAndSet(null, scope)) return rejected(DozeOwnerReadOnlyCode.BUSY)
        try {
            if (closed.get()) scope.cancel()
            scope.requireActive()
            val candidates = block(scope)
            scope.requireActive()
            val summary = summarizeDozeOwnerReadOnly(candidates, requested, wallTime(), scope.elapsedMillis())
            // Digesting or a blocked collaborator cannot publish after cancellation/the shared deadline.
            scope.requireActive()
            return summary
        } catch (_: Throwable) {
            return rejected(when {
                closed.get() || scope.cancelled.get() -> DozeOwnerReadOnlyCode.CANCELLED
                scope.remainingMillis() <= 0 -> DozeOwnerReadOnlyCode.DEADLINE
                else -> DozeOwnerReadOnlyCode.REJECTED
            })
        } finally {
            scope.cancel()
            current.compareAndSet(scope, null)
        }
    }

    fun cancel() {
        closed.set(true)
        current.get()?.cancel()
    }

    fun readEffective(
        block: (Scope) -> DozeEffectiveOwnerResult,
        uncertainBinderExit: () -> Boolean,
    ): DozeEffectiveOwnerReadOnlySummary {
        val requested = wallTime()
        val started = nanoTime()
        val scope = Scope(started, nanoTime)
        fun summary(result: DozeEffectiveOwnerResult) = DozeEffectiveOwnerReadOnlySummary(requested,
            wallTime(), TimeUnit.NANOSECONDS.toMillis(nanoTime() - started).coerceAtLeast(0), result, uncertainBinderExit())
        fun rejected(reason: DozeEffectiveOwnerRejection) = summary(DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.UNKNOWN, reason))
        if (closed.get()) return rejected(DozeEffectiveOwnerRejection.CANCELLED)
        if (!current.compareAndSet(null, scope)) return rejected(DozeEffectiveOwnerRejection.BUSY)
        var partial: DozeEffectiveOwnerResult? = null
        try {
            if (closed.get()) scope.cancel()
            scope.requireActive()
            val result = block(scope)
            partial = result
            scope.requireActive()
            val value = summary(result)
            scope.requireActive()
            return value
        } catch (failure: Throwable) {
            val reason = when {
                uncertainBinderExit() -> DozeEffectiveOwnerRejection.IN_FLIGHT
                failure is TimeoutException || scope.remainingMillis() <= 0 -> DozeEffectiveOwnerRejection.DEADLINE
                closed.get() || scope.cancelled.get() -> DozeEffectiveOwnerRejection.CANCELLED
                else -> DozeEffectiveOwnerRejection.READ_FAILED
            }
            val prior = partial
            return if (prior == null) rejected(reason) else summary(prior.copy(code = DozeEffectiveOwnerCode.UNKNOWN,
                rejection = reason, gmsAppId = -1, ownerCount = 0, structureDigest = "", mappingDigest = ""))
        } finally {
            scope.cancel()
            current.compareAndSet(scope, null)
        }
    }

    internal class Scope(private val started: Long, private val nanoTime: () -> Long) {
        val cancelled = AtomicBoolean(false)
        private val running = AtomicReference<Process?>()
        fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(nanoTime() - started).coerceAtLeast(0)
        fun remainingMillis(): Long = (REQUEST_MILLIS - elapsedMillis()).coerceAtLeast(0)
        fun mayContinue(): Boolean = !cancelled.get() && remainingMillis() > 0 && !Thread.currentThread().isInterrupted
        fun requireActive() { check(mayContinue()) { "Debug owner read is closed" } }
        fun onProcess(process: Process?) {
            if (process == null) {
                running.set(null)
                return
            }
            if (!mayContinue()) {
                runCatching { process.destroyForcibly() }
                error("Debug owner read is closed")
            }
            running.set(process)
            if (!mayContinue()) {
                cancel()
                error("Debug owner read closed during dispatch")
            }
        }
        fun cancel() {
            cancelled.set(true)
            running.getAndSet(null)?.let { runCatching { it.destroyForcibly() } }
        }
    }

    companion object { const val REQUEST_MILLIS = 40_000L }
}
