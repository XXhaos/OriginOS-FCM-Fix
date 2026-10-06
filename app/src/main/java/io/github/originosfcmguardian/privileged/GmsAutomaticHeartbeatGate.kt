package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface GmsHeartbeatLifetime {
    fun alive(): Boolean
    fun link(death: () -> Unit)
    fun unlink()
}
internal class GmsAutoCapability(val session: GmsAutoSession, val callerUid: Int, val bootId: String,
    val lifetime: GmsHeartbeatLifetime, val live: AtomicBoolean = AtomicBoolean(true)) {
    var nextAllowed = 0L
    val attempts = HashSet<String>()
}

/** Final launch and revocation share this monitor. No blocking Binder call or command wait occurs here. */
internal class GmsAutomaticHeartbeatGate(private val clock: () -> Long) {
    private var capability: GmsAutoCapability? = null
    private var lastGeneration = 0L
    private var blocked = false
    private var workerActive = false
    private var closed = false
    @Synchronized fun arm(next: GmsAutoCapability, cancelled: () -> Boolean): Boolean {
        if (closed || blocked || capability?.live?.get() == true || next.session.generation <= lastGeneration ||
            !next.live.get() || cancelled()) return false
        next.nextAllowed = Math.addExact(clock(), GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS)
        capability = next
        lastGeneration = next.session.generation
        return true
    }
    @Synchronized fun prepare(session: GmsAutoSession, callerUid: Int, attemptId: String): Pair<GmsAutoCapability?, GmsHeartbeatReason?> {
        val current = capability
        if (closed || blocked || current == null || !current.live.get() || current.session != session || current.callerUid != callerUid)
            return null to GmsHeartbeatReason.SESSION_REVOKED
        if (!attemptId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            return null to GmsHeartbeatReason.INVALID_REQUEST
        if (!current.attempts.add(attemptId)) return null to GmsHeartbeatReason.DUPLICATE_ATTEMPT
        if (current.attempts.size > 1024) { current.live.set(false); return null to GmsHeartbeatReason.SESSION_REVOKED }
        if (clock() < current.nextAllowed) return null to GmsHeartbeatReason.INTERVAL_NOT_ELAPSED
        return current to null
    }
    @Synchronized fun manualEvent() {
        capability?.takeIf { it.live.get() }?.let { it.nextAllowed = Math.addExact(clock(), GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS) }
    }
    @Synchronized fun launch(cap: GmsAutoCapability?, sample: GmsConnectionObservation.Sample,
        begin: () -> Boolean, start: () -> Process): Pair<Process?, GmsHeartbeatReason?> {
        if (closed || blocked) return null to GmsHeartbeatReason.SESSION_REVOKED
        if (cap != null && (capability !== cap || !cap.live.get() || cap.bootId != sample.bootId ||
                cap.session.bindingDigest != gmsHeartbeatBindingDigest(sample))) {
            cap.live.set(false)
            return null to GmsHeartbeatReason.IDENTITY_CHANGED
        }
        if (cap != null && clock() < cap.nextAllowed) return null to GmsHeartbeatReason.INTERVAL_NOT_ELAPSED
        // Local Binder lifetime state is a final strengthening check; the client pending fence
        // remains required to exclude a previous caller's unknown/in-flight requests.
        if (cap != null && (!runCatching { cap.lifetime.alive() }.getOrDefault(false) || !cap.live.get())) {
            cap.live.set(false)
            return null to GmsHeartbeatReason.SESSION_REVOKED
        }
        if (!begin()) return null to GmsHeartbeatReason.CANCELLED
        manualEvent() // Both request kinds start a complete shared interval.
        return start() to null
    }
    @Synchronized fun revoke(callerUid: Int, generation: Long, id: String?): Boolean {
        val current = capability ?: return generation > 0 && generation <= lastGeneration
        if (current.callerUid != callerUid || current.session.generation != generation ||
            (id != null && id != current.session.id)) return false
        current.live.set(false)
        return true
    }
    @Synchronized fun ended(cap: GmsAutoCapability?, status: GmsHeartbeatStatus, settled: Boolean) {
        if (status == GmsHeartbeatStatus.UNKNOWN || !settled) {
            capability?.live?.set(false)
            // A blocked owner cannot be bypassed by rearming before its worker has actually exited.
            if (!settled) blocked = workerActive
        } else manualEvent()
        if (cap != null && status == GmsHeartbeatStatus.UNKNOWN) cap.live.set(false)
    }
    @Synchronized fun workerEntered() { workerActive = true }
    @Synchronized fun workerExited() { workerActive = false; blocked = false }
    @Synchronized fun retiredLifetime() = capability?.takeIf { !it.live.get() }?.lifetime
    @Synchronized fun close() { capability?.live?.set(false); closed = true }
    fun newCapability(generation: Long, sample: GmsConnectionObservation.Sample, uid: Int, lifetime: GmsHeartbeatLifetime) =
        GmsAutoCapability(GmsAutoSession(UUID.randomUUID().toString(), generation, gmsHeartbeatBindingDigest(sample)), uid, sample.bootId, lifetime)
}
