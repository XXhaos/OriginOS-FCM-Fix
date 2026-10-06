package io.github.originosfcmguardian.privilegebridge

/** This is a read transport choice, never consent for recovery or unattended startup. */
enum class ReadOnlyTransportChoice { APP_OWNED, EXTERNAL_SHIZUKU }

sealed interface PrivilegeIdentityResult {
    data class Verified(val generation: Long, val shellUid: Int, val activeUserId: Int) : PrivilegeIdentityResult
    data class Unavailable(val reason: Reason) : PrivilegeIdentityResult
    /** The caller must invoke the existing external adapter explicitly. No automatic retry. */
    data object ExternalFallbackSelected : PrivilegeIdentityResult

    enum class Reason { DISCONNECTED, REJECTED_IDENTITY, BUSY, REVOKED, STALE, TIMED_OUT, TRANSPORT_FAILED }
}

internal data class IdentityTicket(val generation: Long, val server: Any)

/** Memory-only, single-flight read generation. Never usable as a write authorization. */
internal class ReadOnlyBridgeGate {
    private var generation = 0L
    private var current: IdentityTicket? = null
    private var invalidated = false
    private var revoked = false

    @Synchronized
    fun begin(server: Any, serverUid: Int, ownerUid: Int): IdentityTicket? {
        if (revoked || current != null || serverUid != 2000 || ownerUid !in 10_000..99_999) return null
        if (generation == Long.MAX_VALUE) { revoked = true; return null }
        return IdentityTicket(++generation, server).also { current = it; invalidated = false }
    }

    @Synchronized
    fun accepts(ticket: IdentityTicket, server: Any?, uid: Int, userId: Int, returnedGeneration: Long): Boolean =
        !revoked && !invalidated && current === ticket && server === ticket.server && uid == 2000 && userId == 0 &&
            returnedGeneration == ticket.generation

    @Synchronized
    fun invalidate(ticket: IdentityTicket) { if (current === ticket) invalidated = true }

    @Synchronized
    fun finish(ticket: IdentityTicket) { if (current === ticket) current = null }

    @Synchronized
    fun revoke() { revoked = true; current = null }

    @Synchronized
    fun isRevoked(): Boolean = revoked

    @Synchronized
    fun isBusy(): Boolean = current != null
}

internal fun authenticatedGuardianCaller(callerUid: Int, owners: List<String>?): Boolean =
    callerUid in 10_000..99_999 && owners == listOf("io.github.originosfcmguardian")
