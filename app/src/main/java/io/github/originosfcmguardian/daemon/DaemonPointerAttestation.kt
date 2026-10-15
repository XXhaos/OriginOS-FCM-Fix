package io.github.originosfcmguardian.daemon

import java.security.SecureRandom
import java.util.Base64

internal enum class DaemonPointerPurpose { TRANSITION, RECONCILE }

/** Shell-issued nonce, bound to one exact immutable proposal (including its domain patch). */
internal data class DaemonPointerChallenge internal constructor(
    val nonce: String,
    val purpose: DaemonPointerPurpose,
    val pointer: DaemonPointerCommitProof,
    val patchDigest: String,
)

/**
 * The trusted App sends this ONLY after durable pointer commit AND independent App-side readback.
 * This is an authenticated App attestation, NOT independent shell proof of App-private storage.
 */
internal data class DaemonAppPointerAttestation(val challenge: DaemonPointerChallenge,
    val pointer: DaemonPointerCommitProof)

/**
 * Per-owner/process, bounded, one-use challenge registry. Only the most recently issued challenge
 * can attest or authorize anything; retaining/cancelling entries never restores an older challenge.
 * The future Binder host must capture the caller's real UID and fresh installation itself;
 * never deserialize DaemonCallerEvidence as truth. attest() runs before entering the kernel monitor.
 * Kernel verifyPointerCommit is local lookup only.
 * No registry state survives an owner restart, even if its durable owner key has not changed.
 */
internal class DaemonPointerAttestationRegistry(
    private val owner: DaemonOwnerKey,
    private val approvedCaller: DaemonGuardianInstallation,
    private val maximumEntries: Int = 16,
) {
    private data class Entry(val challenge: DaemonPointerChallenge,
        val plan: DaemonControlTransitionPlan?, var accepted: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>()
    private val random = SecureRandom()
    private var currentNonce: String? = null

    init { require(maximumEntries in 1..64) }

    fun authenticated(caller: DaemonCallerEvidence): Boolean =
        caller.realUid == approvedCaller.identity.uid && caller.installation == approvedCaller

    /** Local revocation only; no callback/IO. Rejected proposals cannot revive an older proof. */
    @Synchronized internal fun invalidateCurrent() { entries.clear(); currentNonce = null }

    @Synchronized internal fun issue(record: DaemonControlRecord, plan: DaemonControlTransitionPlan?): DaemonPointerChallenge {
        check(record.owner == owner) { "Pointer challenge owner" }
        check(plan == null || plan.next == record) { "Pointer challenge/proposal mismatch" }
        var nonce: String
        do { nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)) }
        while (entries.containsKey(nonce))
        val challenge = DaemonPointerChallenge(nonce,
            if (plan == null) DaemonPointerPurpose.RECONCILE else DaemonPointerPurpose.TRANSITION,
            DaemonPointerCommitProof(nonce, record), plan?.patchDigest ?: DaemonProtectionRootSnapshot.digest(byteArrayOf()))
        // Obsolete entries can never authorize anything. Evict them rather than letting abandoned
        // App requests permanently exhaust this process's bounded registry.
        entries.clear()
        check(entries.size < maximumEntries)
        entries[nonce] = Entry(challenge, plan)
        // A newer proposal can replace the App's current durable pointer. No older local proof
        // may authorize apply/reconcile after this point, including one previously attested.
        currentNonce = nonce
        return challenge
    }

    @Synchronized fun attest(caller: DaemonCallerEvidence, value: DaemonAppPointerAttestation): Boolean {
        if (!authenticated(caller)) return false
        val entry = entries[value.challenge.nonce] ?: return false
        if (currentNonce != value.challenge.nonce) return false
        // A failed authenticated answer burns the nonce too; there is no correction/replay path.
        if (entry.accepted) return false
        if (entry.challenge != value.challenge || value.pointer != entry.challenge.pointer) {
            discard(value.challenge.nonce); return false
        }
        entry.accepted = true
        return true
    }

    @Synchronized internal fun transition(proof: DaemonPointerCommitProof): DaemonControlTransitionPlan? =
        current(proof, DaemonPointerPurpose.TRANSITION)?.plan

    @Synchronized internal fun consumeTransition(proof: DaemonPointerCommitProof): DaemonControlTransitionPlan? {
        val plan = transition(proof) ?: return null
        discard(proof.commitId)
        return plan
    }

    @Synchronized internal fun consumeReconciliation(proof: DaemonPointerCommitProof): Boolean {
        if (current(proof, DaemonPointerPurpose.RECONCILE) == null) return false
        discard(proof.commitId)
        return true
    }

    /** Cancellation never grants authority. A new proposal requires a fresh random challenge. */
    @Synchronized fun cancel(challenge: DaemonPointerChallenge) {
        if (entries[challenge.nonce]?.challenge == challenge) {
            discard(challenge.nonce)
        }
    }

    // Called only under the registry monitor; no callback, root access or IO is permitted here.
    private fun current(proof: DaemonPointerCommitProof, purpose: DaemonPointerPurpose): Entry? =
        entries[proof.commitId]?.takeIf { currentNonce == proof.commitId && it.accepted &&
            it.challenge.pointer == proof && it.challenge.purpose == purpose }

    private fun discard(nonce: String) {
        entries.remove(nonce)
        if (currentNonce == nonce) currentNonce = null
    }
}
