package io.github.originosfcmguardian.core.doze

/** Fixed GMS scope is device-global; neither package, appId nor profile scope is caller supplied. */
const val GMS_GLOBAL_DOZE_PACKAGE = "com.google.android.gms"
const val GMS_GLOBAL_OWNER_PROOF_VERSION = 1

data class GmsGlobalInstallation(
    val appId: Int,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    /** Full lowercase SHA-256 digests, unique and already sorted; never normalized by the codec. */
    val signerSha256: List<String>,
)

data class GmsGlobalGuardianBinding(val userId: Int, val installation: GmsGlobalInstallation)

data class GmsGlobalEnvironment(
    val fingerprint: String,
    val executionUid: Int,
    val currentUserId: Int,
)

sealed interface GmsGlobalOwnerEvidence {
    data object Unknown : GmsGlobalOwnerEvidence

    /** Produced by a complete canonical PM getter sweep, never by raw Settings.appId alone. */
    data class Qualified(
        val proofVersion: Int,
        val mappingSha256: String,
        val exclusiveGmsAppId: Int,
    ) : GmsGlobalOwnerEvidence
}

data class GmsGlobalDozeIdentity(
    val gms: GmsGlobalInstallation,
    val guardian: GmsGlobalGuardianBinding,
    val environment: GmsGlobalEnvironment,
    val owners: GmsGlobalOwnerEvidence,
)

data class GmsGlobalDozeObservation(val identity: GmsGlobalDozeIdentity, val rawWhitelist: String)
enum class GmsGlobalDozeOperation { ADD, REMOVE }

/** Callee evidence for the actual fixed command; a missing/incomplete/mismatched receipt is uncertain. */
data class GmsGlobalDozeDispatchReceipt(
    val operation: GmsGlobalDozeOperation,
    val identity: GmsGlobalDozeIdentity,
    val completed: Boolean,
)

fun interface GmsGlobalDozeDispatchedCall {
    /** Return/throw only after the actual callee AND cleanup terminate, including on timeout. */
    fun awaitTerminationAndCleanup(): GmsGlobalDozeDispatchReceipt
}

interface GmsGlobalDozeDevicePort {
    /** Fresh complete full whitelist + installation + qualified mapping, or UNKNOWN/throw. */
    fun probe(): GmsGlobalDozeObservation
    /**
     * Bounded synchronous actual fixed-command dispatch, invoked inside dispatchAdd's short lock.
     * Return only when the command has irreversibly entered the callee/OS, not when a Future/RPC
     * has merely been queued. Do not wait for command completion here. If the transport cannot
     * provide that boundary, it cannot implement this port. The returned call owns termination
     * and cleanup evidence; preparation alone never authorizes a write.
     */
    fun dispatchAddFixedGms(): GmsGlobalDozeDispatchedCall
    fun removeFixedGms(): GmsGlobalDozeDispatchReceipt
}

interface GmsGlobalDozeDocumentPort {
    /** Exact bytes; null only for confirmed absence. Unknown/corrupt bytes must be retained. */
    fun load(): ByteArray?
    /** Durable atomic byte CAS; validate the transition under the same storage lock. */
    fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean
}

/**
 * Required shared admission for BOTH legacy and new operations, including all engine instances.
 * The adapter must hold admission until the actual callee AND cleanup terminate. A timeout or
 * cancelled Future is not proof of exit. Cross-process coordination needs an OS lock; a private
 * JVM monitor is insufficient. No default/private implementation is provided here.
 *
 * revokeAdds synchronously closes ADD admission, even while an operation holds serialized.
 * allowAdds opens it only if no later revocation occurred. Methods must be thread safe and the
 * gate fail closed on interrupted storage. Serialized callbacks may be reentrant for OFF.
 * dispatchAdd and revokeAdds must share the same atomic lock/OS coordination. dispatchAdd checks
 * the gate AND synchronously invokes actualDispatch under that short lock. Actual dispatch is
 * the ADD linearization point; OFF linearizes at revokeAdds closing that gate. Command completion
 * and cleanup waits run outside the revocation lock, while serialized admission remains held.
 */
interface GmsGlobalDozeAdmissionPort {
    fun <T> serialized(operation: () -> T): T
    fun revocationVersion(): Long
    fun revokeAdds(): Long
    fun allowAdds(expectedRevocationVersion: Long): Boolean
    fun addsAllowed(expectedRevocationVersion: Long): Boolean
    fun dispatchAdd(expectedRevocationVersion: Long,
        actualDispatch: () -> GmsGlobalDozeDispatchedCall): GmsGlobalDozeDispatchedCall?
}

data class GmsGlobalDozeConsent(
    val enabled: Boolean,
    val epoch: Long,
    /** Every exact-byte document update advances this revision, including transaction results. */
    val generation: Long,
    val confirmedAtMillis: Long,
    val gms: GmsGlobalInstallation,
    val guardian: GmsGlobalGuardianBinding,
    val pinnedFingerprint: String,
)

data class GmsGlobalDozeTransaction(
    val id: String,
    val epoch: Long,
    val gms: GmsGlobalInstallation,
    val ownerProofVersion: Int,
    val baseline: String,
    val applied: String?,
    val phase: DozePhase,
)

data class GmsGlobalDozeDocument(
    val consent: GmsGlobalDozeConsent,
    val transaction: GmsGlobalDozeTransaction? = null,
)
