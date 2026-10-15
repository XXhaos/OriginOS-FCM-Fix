package io.github.originosfcmguardian.daemon

/** Negative-only service lifecycle. All callbacks under this monitor must be memory-only. */
internal class DaemonServiceDrain {
    private var closed = false
    private var transactions = 0
    private var exitClaimed = false
    private var hostIdentity: Any? = null

    @Synchronized fun enter(): Boolean {
        if (closed) return false
        transactions = Math.addExact(transactions, 1)
        return true
    }
    @Synchronized fun leave() {
        check(transactions > 0)
        transactions--
    }
    @Synchronized fun closeAdmission(): Boolean {
        if (closed) return false
        closed = true
        return true
    }

    /** Even a raced closed host must remain tracked until its actual workers release the lease. */
    @Synchronized fun publishHost(identity: Any, publish: () -> Unit): Boolean {
        check(transactions > 0 && !exitClaimed)
        publish()
        hostIdentity = identity
        return !closed
    }

    /** No elapsed deadline can substitute for any of these actual drain observations. */
    @Synchronized fun claimExit(creating: Boolean, callersQuiescent: () -> Boolean, hostReleased: Boolean,
        observedHostIdentity: Any? = null): Boolean {
        if (!closed || exitClaimed || transactions != 0 || creating || !hostReleased ||
            observedHostIdentity !== hostIdentity) return false
        // Probe workers can outlive a timed-out transaction. Sample only AFTER all entries ended;
        // an earlier true observation could predate that transaction's later worker submission.
        if (!callersQuiescent()) return false
        exitClaimed = true
        return true
    }
}
