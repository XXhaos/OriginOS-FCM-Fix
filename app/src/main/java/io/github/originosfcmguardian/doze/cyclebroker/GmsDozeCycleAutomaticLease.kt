package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.GmsDozeCycleAttemptRef

/** One actual host/connection incarnation. Closing is irreversible and never means global OFF. */
internal class GmsDozeCycleAutomaticLease(
    val hostId: String,
    val connectionGeneration: Long,
    private val eligible: () -> Boolean,
    private val closeLocal: (GmsDozeCycleAttemptRef) -> Unit,
) {
    private val monitor = Any()
    private var closed = false
    private var reference: GmsDozeCycleAttemptRef? = null
    private var outbound = false
    private var dispatched = false

    init { require(hostId.isNotBlank() && connectionGeneration >= 0) }

    /** The actual-host getter is never invoked under a lease, ledger or start monitor. */
    fun checkEligible(): Boolean {
        val fresh = try { eligible() } catch (_: Exception) { false }
        if (!fresh) close()
        return fresh && !isClosed()
    }
    fun isClosed(): Boolean = synchronized(monitor) { closed }
    fun ref(): GmsDozeCycleAttemptRef? = synchronized(monitor) { reference }

    /** Covers loss while grant CAS is outside the monitor, including the CAS-to-pin interval. */
    fun attach(ref: GmsDozeCycleAttemptRef): Boolean = synchronized(monitor) {
        check(reference == null || reference == ref)
        reference = ref
        if (closed) closeLocal(ref)
        !closed
    }
    fun close(): GmsDozeCycleAttemptRef? = synchronized(monitor) {
        if (closed) return@synchronized null
        closed = true
        reference?.also(closeLocal)
    }

    /** Only the short core one-use reservation is allowed here; no disk or Binder operation. */
    fun reserveOutbound(ref: GmsDozeCycleAttemptRef, reserve: () -> Boolean): Boolean = synchronized(monitor) {
        if (closed || reference != ref || outbound) return@synchronized false
        if (!reserve()) return@synchronized false
        outbound = true
        true
    }

    /** Called on the actual start lane. The gate is left BEFORE calling remote.start. */
    fun admitDispatch(): Boolean = synchronized(monitor) {
        if (closed || !outbound || dispatched) return@synchronized false
        dispatched = true
        true
    }
}
