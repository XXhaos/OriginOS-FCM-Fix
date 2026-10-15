package io.github.originosfcmguardian.privileged

import java.util.concurrent.atomic.AtomicInteger

/** Caller timeout is not worker or child-process completion. */
internal enum class PrivilegedExecutionSettlement { NOT_STARTED, PROCESS_EXIT_CONFIRMED, UNKNOWN }

/** Optional daemon ownership boundary; App-bound transports retain their existing behavior. */
internal interface PrivilegedExecutionFence {
    /** Potentially blocking fresh reads; call BEFORE any inner capability launch monitor. */
    fun prepare(): Boolean
    /** Only the actual fixed ProcessBuilder.start runs inside the owner's short dispatch fence. */
    fun start(launch: () -> Process): Process?
    /** UNKNOWN must retain the owner's pending/occupied state, never manufacture completion. */
    fun workerSettled(value: PrivilegedExecutionSettlement)
}

/** Cleanup is not proof of exit; callers must separately inspect the exact child handle. */
internal fun bestEffortTerminatePrivilegedChild(process: Process) {
    runCatching { process.destroy() }
    runCatching { if (process.isAlive) process.destroyForcibly() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
}

/** Handles cancellation before the submitted Future body starts as well as actual late teardown. */
internal class PrivilegedWorkSettlement(private val fence: PrivilegedExecutionFence?,
    private val onWorkerEnded: (PrivilegedExecutionSettlement, Process?) -> Unit = { _, _ -> }) {
    // 0 queued, 1 running, 2 abandoned before start, 3 settled by actual worker finally.
    private val state = AtomicInteger(0)
    fun workerEntered(): Boolean = state.compareAndSet(0, 1)
    fun abandonQueued() {
        if (state.compareAndSet(0, 2)) publish(PrivilegedExecutionSettlement.NOT_STARTED)
    }
    fun workerEnded(launchEntered: Boolean, child: Process?) {
        if (!state.compareAndSet(1, 3)) return
        val value = when {
            !launchEntered -> PrivilegedExecutionSettlement.NOT_STARTED
            child == null -> PrivilegedExecutionSettlement.UNKNOWN
            runCatching { !child.isAlive }.getOrDefault(false) -> PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED
            else -> PrivilegedExecutionSettlement.UNKNOWN
        }
        publish(value, child)
    }
    private fun publish(value: PrivilegedExecutionSettlement, child: Process? = null) {
        // Losing a completion notification may block the owner, but must never invent an ACK.
        runCatching { fence?.workerSettled(value) }
        onWorkerEnded(value, child)
    }
}

/** Counts submitted work until cancellation-before-entry or the real worker finally, not Future.isDone. */
internal class PrivilegedWorkerActivity {
    private val outstanding = AtomicInteger()
    private val children = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val unknownWithoutHandle = java.util.concurrent.atomic.AtomicBoolean()
    fun track(fence: PrivilegedExecutionFence? = null): PrivilegedWorkSettlement {
        outstanding.incrementAndGet()
        return PrivilegedWorkSettlement(fence) { settlement, child ->
            if (settlement == PrivilegedExecutionSettlement.UNKNOWN) {
                if (child == null) unknownWithoutHandle.set(true) else children.add(child)
            }
            check(outstanding.decrementAndGet() >= 0)
        }
    }
    fun isQuiescent(): Boolean {
        children.removeAll { runCatching { !it.isAlive }.getOrDefault(false) }
        return outstanding.get() == 0 && !unknownWithoutHandle.get() && children.isEmpty()
    }
}
