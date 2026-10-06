package io.github.originosfcmguardian.fcmprotection

/** Actual host readiness is not write authority. OFF must not prevent first-consent preparation. */
internal class FcmProtectionHostReadiness {
    @Volatile private var hostId: String? = null
    @Volatile var eligible = false
        private set
    @Synchronized fun attach(id: String) { hostId = id; eligible = false }
    @Synchronized fun invalidate() { eligible = false }
    @Synchronized fun end(id: String) { if (hostId == id) { hostId = null; eligible = false } }
    @Synchronized fun poll(id: String, recovery: () -> Boolean, heartbeat: () -> Boolean,
        actionsAllowed: () -> Boolean, startActions: () -> Unit): Boolean {
        if (hostId != id) return false
        val recoveryReady = recovery(); val heartbeatReady = heartbeat()
        eligible = recoveryReady && heartbeatReady
        if (eligible && actionsAllowed()) startActions()
        return eligible
    }
}
