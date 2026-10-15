package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity

internal data class DaemonAppCommand(val kind: Int, val target: SelectedTargetIdentity? = null) {
    val disabling get() = kind in setOf(DaemonWire.MASTER_OFF, DaemonWire.FCM_OFF, DaemonWire.TARGET_OFF)
}

/** One active command, one pending OFF, no queued enables and no unbounded Mutex waiters. */
internal class DaemonAppCommandAdmission {
    enum class Offer { START, QUEUED_OFF, REJECTED }
    private var active = false
    private var pending: DaemonAppCommand? = null
    @Synchronized fun busy() = active
    @Synchronized fun offer(value: DaemonAppCommand): Offer {
        if (!active) { active = true; return Offer.START }
        if (!value.disabling) return Offer.REJECTED
        if (pending == value) return Offer.QUEUED_OFF
        if (pending != null) return Offer.REJECTED // No ACK or optimistic switch change for rejection.
        pending = value
        return Offer.QUEUED_OFF
    }
    @Synchronized fun finishAndNext(): DaemonAppCommand? {
        check(active)
        return pending.also { pending = null; if (it == null) active = false }
    }
}
