package io.github.originosfcmguardian.daemon

/** Finite first-adoption admission only; never owns, clears or reconstructs migration storage. */
internal class DaemonAdoptionRetry {
    internal class Ticket internal constructor(internal val epoch: Long) {
        internal var started = false
    }

    private var readyIdentity: Any? = null
    private var epoch = 0L
    private var attempts = 0
    private var active: Ticket? = null
    private var pending = false

    /** Duplicate ready callbacks on the same server cannot replenish the attempt budget. */
    @Synchronized fun readiness(identity: Any?) {
        if (readyIdentity == identity) return
        readyIdentity = identity
        epoch++
        attempts = 0
        if (identity == null) pending = false
    }

    @Synchronized fun offer(identity: Any?, eligible: Boolean, local: Boolean): Ticket? {
        // An unread/temporarily unavailable sample is not evidence of a new ready epoch.
        // Only an explicit dead/denied event calls readiness(null).
        if (identity != null) readiness(identity)
        if (identity == null || !eligible || !local) { pending = false; return null }
        if (attempts >= MAX_ATTEMPTS) return null
        if (active != null) { pending = true; return null }
        pending = false
        return Ticket(epoch).also { active = it }
    }

    /** Consume only immediately before read-only preflight, after the fresh candidate checks. */
    @Synchronized fun start(ticket: Ticket): Boolean {
        if (active !== ticket || ticket.epoch != epoch || readyIdentity == null || ticket.started ||
            attempts >= MAX_ATTEMPTS) return false
        ticket.started = true
        attempts++
        return true
    }

    @Synchronized fun current(ticket: Ticket): Boolean =
        active === ticket && ticket.epoch == epoch && readyIdentity != null && ticket.started

    /** A dispatched preflight always counts, including cancellation/timeout; queued work never counts. */
    @Synchronized fun finish(ticket: Ticket): Boolean {
        if (active !== ticket) return false
        active = null
        val retry = pending && readyIdentity != null && attempts < MAX_ATTEMPTS
        pending = false
        return retry
    }

    private companion object { const val MAX_ATTEMPTS = 2 }
}
