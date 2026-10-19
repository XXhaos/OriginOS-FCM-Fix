package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal enum class DaemonControlTransition { ACTIVATE, SET_AUTHORITY }

/**
 * An immutable configuration proposal, NOT a command or consent grant. The trusted domain-command
 * adapter must validate provenance and explicit consent before staging. No result/journal/count role
 * is accepted. FCM/HB values may change only their protected configuration projection.
 */
internal class DaemonControlTransitionPlan(
    val requestId: String,
    val expected: DaemonControlRecord,
    val next: DaemonControlRecord,
    val transition: DaemonControlTransition,
    patch: Map<DaemonDomainRole, ByteArray?> = emptyMap(),
    nativeHeartbeatDerivation: ByteArray? = null,
) {
    private val content = patch.mapValues { it.value?.copyOf() }
    private val nativeDerivation = nativeHeartbeatDerivation?.copyOf()
    val patchDigest: String
    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(expected.revision < Long.MAX_VALUE && next.owner == expected.owner &&
            next.revision == expected.revision + 1 && next.migrationId == expected.migrationId)
        require(next.mode != DaemonControlMode.PREPARED)
        require(content.keys.all { it in CONFIGURATION_ROLES })
        require(content.all { (role, bytes) -> bytes == null || bytes.size <= role.maximumBytes })
        nativeDerivation?.let { DaemonHeartbeatDerivationPermitCodec.decode(it) }
        if (transition == DaemonControlTransition.ACTIVATE) require(expected.mode == DaemonControlMode.PREPARED &&
            next.mode == DaemonControlMode.ACTIVE && expected.authorityDigest == next.authorityDigest && content.isEmpty())
        else require(expected.mode != DaemonControlMode.PREPARED || next.mode == DaemonControlMode.OFF)
        patchDigest = DaemonProtectionRootSnapshot.digest(ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                for (role in DaemonDomainRole.entries.filter(content::containsKey)) {
                    val bytes = content[role]; out.writeInt(role.ordinal); out.writeInt(bytes?.size ?: -1)
                    if (bytes != null) out.write(bytes)
                }
                // Separate native material is never a general configuration patch role.
                if (nativeDerivation != null) {
                    out.writeInt(DaemonDomainRole.HEARTBEAT_DERIVATION.ordinal)
                    out.writeInt(nativeDerivation.size); out.write(nativeDerivation)
                }
            }
        }.toByteArray())
    }
    fun patch(): Map<DaemonDomainRole, ByteArray?> = content.mapValues { it.value?.copyOf() }
    fun nativeHeartbeatDerivation(): ByteArray? = nativeDerivation?.copyOf()
    fun request(pointer: DaemonPointerCommitProof): DaemonControlRequest {
        require(pointer.record == next)
        return DaemonControlRequest(requestId, expected.owner, expected.revision, when (transition) {
            DaemonControlTransition.ACTIVATE -> DaemonControlIntent.Activate(pointer)
            DaemonControlTransition.SET_AUTHORITY -> DaemonControlIntent.SetAuthority(pointer)
        })
    }
    companion object {
        private val CONFIGURATION_ROLES = setOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.SELECTION,
            DaemonDomainRole.FCM_PROTECTION, DaemonDomainRole.FCM_PROTECTION_HALT,
            DaemonDomainRole.FCM_RECOVERY, DaemonDomainRole.FCM_RECOVERY_HALT,
            DaemonDomainRole.HEARTBEAT, DaemonDomainRole.HEARTBEAT_HALT)
    }
}

/**
 * Concrete single-CONTROL-root adapter. No legacy role files, App-private file reads or high-level
 * synchronized stores are used here. Domain-command validation and authenticated App attestation run
 * outside the kernel monitor; pointer verification inside apply() is a process-local lookup only.
 *
 * Integration: closeAdmission must invoke kernel.quiesceForControl(caller, expected), before any
 * pointer/callback IO. Staging durably records the exact proposal before issuing a pointer challenge;
 * restart only resumes that proposal with fresh App attestation. validateCommand is a trusted
 * existing command adapter, not a parcel boolean.
 * ownerLease must hold the actual shell lifetime lease across the immediate command-start callback.
 * A failed root instance stays poisoned. Interrupted transactions need explicit resolution by the
 * future host; old ACTIVE snapshots/pointers are never selected as a recovery fallback.
 */
internal class DaemonRootControlPort(
    private val root: DaemonProtectionRoot,
    private val owner: DaemonOwnerKey,
    private val attestations: DaemonPointerAttestationRegistry,
    private val validateCommand: (DaemonControlTransitionPlan) -> Boolean,
    private val ownerLease: (() -> Unit) -> Boolean,
) : DaemonControlPort {
    /** No lock is held while quiescing the kernel or validating the trusted domain command. */
    fun stage(caller: DaemonCallerEvidence, plan: DaemonControlTransitionPlan,
        closeAdmission: () -> Boolean): DaemonPointerChallenge? {
        if (!attestations.authenticated(caller) || plan.expected.owner != owner) return null
        attestations.invalidateCurrent() // Revoke old control proofs before competing with an apply.
        if (!closeAdmission()) return null
        if (!validateCommand(plan)) return null
        val before = root.snapshot()
        if (before.control != plan.expected || before.halt != null || before.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null)
            return null
        if (before.receipts.any { it.request.requestId == plan.requestId }) return null
        validatePatch(before, plan)
        validateProposedRuntime(before, plan)
        val proposal = DaemonControlProposalCodec.encode(plan)
        val previous = before.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.let(DaemonControlProposalCodec::decode)
        if (previous != null && previous.requestId == plan.requestId &&
            !DaemonControlProposalCodec.encode(previous).contentEquals(proposal)) return null
        root.transaction(before.dataRevision) { change ->
            check(change.control == plan.expected && change.halt == null)
            change.put(DaemonDomainRole.CONTROL_PROPOSAL, proposal)
        }
        val saved = root.snapshot()
        check(saved.control == plan.expected && saved.halt == null &&
            saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.contentEquals(proposal) == true)
        return attestations.issue(plan.next, plan)
    }

    /**
     * Recover only the exact persisted proposal after a pointer-before-apply process loss. The host
     * must establish admission is closed (including an already closed UNRECONCILED restart).
     * The App rewrites/reads back the SAME next record with the returned fresh nonce, then attests.
     * Applying it uses the kernel's explicit resumeAttestedTransition path, never normal dispatch.
     */
    fun resumeStaged(caller: DaemonCallerEvidence, pointerRecord: DaemonControlRecord,
        closeAdmission: () -> Boolean): DaemonPointerChallenge? {
        if (!attestations.authenticated(caller) || pointerRecord.owner != owner) return null
        attestations.invalidateCurrent()
        if (!closeAdmission()) return null
        val before = root.snapshot()
        val proposal = before.bytes(DaemonDomainRole.CONTROL_PROPOSAL) ?: return null
        val plan = DaemonControlProposalCodec.decode(proposal)
        if (!resumable(before, plan, pointerRecord)) return null
        if (!validateCommand(plan)) return null // Trusted domain callback is outside ALL monitors.
        val current = root.snapshot()
        if (!resumable(current, plan, pointerRecord) ||
            current.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.contentEquals(proposal) != true) return null
        // Original runtime bytes were validated before durable staging. Current runtime history
        // may have advanced; validate/apply only the protected projection, preserving that history.
        validatePatch(current, plan)
        return attestations.issue(plan.next, plan)
    }

    /** Fresh App readback of its CURRENT pointer; a staged next pointer must use resumeStaged. */
    fun challengeCurrentPointer(caller: DaemonCallerEvidence, closeAdmission: () -> Boolean): DaemonPointerChallenge? {
        if (!attestations.authenticated(caller)) return null
        attestations.invalidateCurrent()
        if (!closeAdmission()) return null
        val current = root.snapshot()
        if (current.control.owner != owner || current.halt != null ||
            current.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null ||
            current.bytes(DaemonDomainRole.CONTROL_PROPOSAL) != null) return null
        return attestations.issue(current.control, null)
    }

    fun attest(caller: DaemonCallerEvidence, value: DaemonAppPointerAttestation) = attestations.attest(caller, value)
    fun inspectForReconciliation(): DaemonProtectionRootSnapshot = root.inspectForReconciliation()

    override fun read(): DaemonControlRead = root.snapshot().let(::readState)
    override fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt? {
        if (owner != this.owner) return null
        return root.snapshot().receipts.firstOrNull { it.request.owner == owner && it.request.requestId == requestId }
    }
    override fun verifyPointerCommit(proof: DaemonPointerCommitProof): Boolean =
        proof.record.owner == owner && attestations.transition(proof) != null

    override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? {
        if (expected.owner != owner || request.owner != owner || request.expectedRevision != expected.revision ||
            expected.revision == Long.MAX_VALUE) return null
        val plan = request.pointer()?.let(attestations::transition)
        if (request.intent !is DaemonControlIntent.Prepare && (plan == null || plan.expected != expected ||
                plan.request(checkNotNull(request.pointer())) != request)) return null
        val before = root.snapshot()
        if (before.control != expected || before.halt != null || before.receipts.any { it.request.requestId == request.requestId } ||
            before.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null) return null
        if (request.intent is DaemonControlIntent.Prepare &&
            (expected.mode != DaemonControlMode.OFF || request.intent.authorityDigest != expected.authorityDigest)) return null
        if (plan != null) {
            if (!savedProposalMatches(before, plan)) return null
            validatePatch(before, plan)
        }
        val fence = DaemonHaltFence(request, expected.revision + 1)
        root.controlTransaction(before.dataRevision) { change ->
            check(change.control == expected && change.halt == null)
            change.halt = fence
        }
        val verified = root.snapshot()
        check(verified.control == expected && verified.halt == fence)
        return fence
    }

    override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead {
        check(receipt.request == fence.request && receipt.committed.owner == owner &&
            receipt.committed.revision == fence.nextRevision)
        val before = root.snapshot()
        check(before.halt == fence && before.control.revision == receipt.request.expectedRevision)
        val pointer = receipt.request.pointer()
        val plan = pointer?.let(attestations::consumeTransition)
        if (pointer != null) check(plan != null && plan.expected == before.control && plan.next == receipt.committed &&
            plan.request(pointer) == receipt.request && savedProposalMatches(before, plan))
        else {
            val intent = receipt.request.intent as? DaemonControlIntent.Prepare ?: error("Missing staged control plan")
            check(before.control.mode == DaemonControlMode.OFF && receipt.committed == before.control.copy(
                revision = fence.nextRevision, mode = DaemonControlMode.PREPARED, migrationId = intent.migrationId,
                authorityDigest = intent.authorityDigest))
        }
        root.controlTransaction(before.dataRevision) { change ->
            check(change.halt == fence && change.control == before.control)
            if (plan != null) {
                validatePatch(before, plan)
                applyPatch(change, plan)
                val projected = projectDaemonControl(before, plan)
                DaemonHeartbeatDerivation.applyControlContinuity(before, projected, change)
                plan.nativeHeartbeatDerivation()?.let { change.put(DaemonDomainRole.HEARTBEAT_DERIVATION, it) }
            }
            change.control = receipt.committed
            change.remember(receipt)
            change.put(DaemonDomainRole.CONTROL_PROPOSAL, null)
            change.halt = null // Exact fence, configuration, revision and receipt share ONE write/readback.
        }
        val after = root.snapshot()
        check(after.control == receipt.committed && after.halt == null && after.receipts.contains(receipt) &&
            after.bytes(DaemonDomainRole.CONTROL_PROPOSAL) == null)
        return readState(after)
    }

    override fun reconcile(proof: DaemonReconcileProof): DaemonControlRead? {
        if (proof.reconciliationId.isBlank() || proof.reconciliationId.length > 128 ||
            proof.pointer.record.owner != owner || !attestations.consumeReconciliation(proof.pointer)) return null
        val current = root.snapshot()
        if (current.control != proof.pointer.record || current.halt != null ||
            current.bytes(DaemonDomainRole.CONTROL_PROPOSAL) != null) return null
        return readState(current).takeUnless { it.interrupted }
    }

    override fun withOwnerLease(start: () -> Unit): Boolean = ownerLease(start)

    private fun resumable(snapshot: DaemonProtectionRootSnapshot, plan: DaemonControlTransitionPlan,
        pointer: DaemonControlRecord): Boolean = snapshot.control == plan.expected && plan.expected.owner == owner &&
        plan.next == pointer && snapshot.halt == null && snapshot.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) == null &&
        snapshot.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) == null && snapshot.bytes(DaemonDomainRole.STOPPED_BLOCKED) == null &&
        snapshot.receipts.none { it.request.requestId == plan.requestId }

    private fun savedProposalMatches(snapshot: DaemonProtectionRootSnapshot, plan: DaemonControlTransitionPlan): Boolean =
        snapshot.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.contentEquals(DaemonControlProposalCodec.encode(plan)) == true

    private fun readState(value: DaemonProtectionRootSnapshot): DaemonControlRead {
        check(value.control.owner == owner && value.authorityDigest() == value.control.authorityDigest)
        // Decode original codecs without calling a synchronized domain store or modifying bytes.
        ProtectionAuthorityCodec.decode(requireNotNull(value.bytes(DaemonDomainRole.AUTHORITY)))
        check(value.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true)
        val interrupted = value.halt != null || value.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null ||
            value.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) != null || value.bytes(DaemonDomainRole.STOPPED_BLOCKED) != null
        val stopped = value.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)?.latest?.requiresReconcile == true
        val heartbeat = value.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
        if (value.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) != null) check(heartbeat != null)
        if (heartbeat != null) check(value.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true)
        val heartbeatPending = value.bytes(DaemonDomainRole.HEARTBEAT_PENDING)?.let { HeartbeatPendingCodec.decode(it); true } == true ||
            heartbeat?.let { it.pendingAttempt != null || it.pendingArm != null || it.currentEpochUnknown() } == true
        value.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode)
        value.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode)
        value.bytes(DaemonDomainRole.FCM_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
        value.bytes(DaemonDomainRole.FCM_MCS_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
        val episodes = value.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
        // Consumption means attempt-used, not in-flight. Ended UNKNOWN episodes are diagnostic
        // history. A positive requestPending is a conservative barrier; its absence is NOT proof
        // that the old FCM executor is quiescent (handoff must establish that separately).
        val fcmPending = episodes?.episodes?.any { it.requestPending } == true
        return DaemonControlRead(value.control, interrupted, stopped || heartbeatPending || fcmPending)
    }

    private fun validatePatch(before: DaemonProtectionRootSnapshot, plan: DaemonControlTransitionPlan) {
        check(before.control == plan.expected)
        check(before.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) == null)
        val next = projectDaemonControl(before, plan)
        check(next.authorityDigest() == plan.next.authorityDigest)
        // The old quota/debt is historical data, never reset by a config operation.
        check(ProtectionAuthorityCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.AUTHORITY))).budget ==
            ProtectionAuthorityCodec.decode(requireNotNull(next.bytes(DaemonDomainRole.AUTHORITY))).budget)
        plan.nativeHeartbeatDerivation()?.let { bytes ->
            // A commit's exact control fence is removed only in this validation projection.
            val unfenced = DaemonProtectionRootSnapshot(next.control, next.domains(), next.receipts, null, next.dataRevision)
            val permit = DaemonHeartbeatDerivationPermitCodec.decode(bytes)
            check(DaemonHeartbeatDerivation.prepare(unfenced, plan.next, permit.historicalCaller)?.contentEquals(bytes) == true) {
                "Native derivation no longer matches parent, baseline or target control"
            }
        }
    }

    private fun applyPatch(change: DaemonRootMutation, plan: DaemonControlTransitionPlan) {
        applyDaemonControlConfiguration(change, plan)
    }

    private fun validateProposedRuntime(before: DaemonProtectionRootSnapshot, plan: DaemonControlTransitionPlan) {
        for ((role, bytes) in plan.patch()) when (role) {
            DaemonDomainRole.FCM_RECOVERY -> {
                val proposed = FcmRecoveryStoreCodec.decode(requireNotNull(bytes))
                val current = before.bytes(role)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
                check(proposed.copy(configured = current.configured, binding = current.binding) == current) {
                    "Configuration proposal cannot replace FCM bookkeeping"
                }
            }
            DaemonDomainRole.HEARTBEAT -> {
                val proposed = HeartbeatSnapshotCodec.decode(requireNotNull(bytes))
                val current = requireNotNull(before.bytes(role)?.let(HeartbeatSnapshotCodec::decode))
                val configOnly = current.copy(configured = proposed.configured, bindingDigest = proposed.bindingDigest,
                    durableConsent = proposed.durableConsent)
                check(proposed == configOnly || proposed == normalizeDaemonHeartbeatOff(before, configOnly) ||
                    proposed == daemonHeartbeatConfirmationRetirement(before, plan)) {
                    "Configuration proposal cannot replace heartbeat bookkeeping"
                }
            }
            else -> Unit
        }
    }

    private fun DaemonControlRequest.pointer(): DaemonPointerCommitProof? = when (val value = intent) {
        is DaemonControlIntent.Activate -> value.pointer
        is DaemonControlIntent.SetAuthority -> value.pointer
        is DaemonControlIntent.Prepare -> null
    }
}

/** Pure configuration projection shared with the trusted host's native permit factory. */
internal fun projectDaemonControl(snapshot: DaemonProtectionRootSnapshot,
    plan: DaemonControlTransitionPlan): DaemonProtectionRootSnapshot {
    check(snapshot.control == plan.expected)
    val change = DaemonRootMutation(snapshot)
    applyDaemonControlConfiguration(change, plan)
    change.control = plan.next
    return change.freeze()
}

private fun applyDaemonControlConfiguration(change: DaemonRootMutation, plan: DaemonControlTransitionPlan) {
        val retirement = daemonHeartbeatConfirmationRetirement(change.freeze(), plan)
        check(!daemonRequestsHeartbeatRetirement(plan) || retirement != null) { "Heartbeat retirement evidence changed" }
        for ((role, bytes) in plan.patch()) when (role) {
            DaemonDomainRole.AUTHORITY -> {
                check(bytes != null && !ProtectionAuthorityCodec.decode(bytes).storageBlocked)
                change.put(role, bytes)
            }
            DaemonDomainRole.FCM_PROTECTION -> { check(bytes != null); FcmProtectionCodec.decode(bytes); change.put(role, bytes) }
            DaemonDomainRole.FCM_RECOVERY -> {
                val proposed = FcmRecoveryStoreCodec.decode(requireNotNull(bytes))
                val current = change.bytes(role)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
                // Only config is copied. Newer events, consumption and power data always win.
                change.put(role, FcmRecoveryStoreCodec.encode(current.copy(configured = proposed.configured, binding = proposed.binding)))
            }
            DaemonDomainRole.HEARTBEAT -> {
                val proposed = HeartbeatSnapshotCodec.decode(requireNotNull(bytes))
                val current = requireNotNull(change.bytes(role)?.let(HeartbeatSnapshotCodec::decode)) { "Heartbeat must be imported first" }
                // Preserve generation, sessions, pending operations, results and caller evidence.
                val configOnly = current.copy(configured = proposed.configured,
                    bindingDigest = proposed.bindingDigest, durableConsent = proposed.durableConsent)
                if (retirement != null) {
                    // Original closure/drain was attested before staging. Exact retired bytes are
                    // revalidated above, including every pending/result/identity field.
                    change.put(role, HeartbeatSnapshotCodec.encode(retirement))
                    change.put(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, null)
                } else change.put(role, HeartbeatSnapshotCodec.encode(normalizeDaemonHeartbeatOff(change.freeze(), configOnly)))
            }
            DaemonDomainRole.SELECTION -> {
                if (bytes != null) {
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    check(TargetSelectionCodec.decode(text) is TargetSelectionDecodeResult.Valid)
                }
                change.put(role, bytes)
            }
            DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT, DaemonDomainRole.HEARTBEAT_HALT -> {
                check(bytes == null || bytes.contentEquals(byteArrayOf(1)))
                change.put(role, bytes)
            }
            else -> error("Not a configuration role")
        }
}

/** Only an already drained child can lose WAITING_HOST when explicit configuration becomes OFF. */
private fun normalizeDaemonHeartbeatOff(snapshot: DaemonProtectionRootSnapshot, value: HeartbeatSnapshot): HeartbeatSnapshot =
    if (!value.configured && value.durableConsent == null && snapshot.bytes(DaemonDomainRole.HEARTBEAT_PENDING) == null &&
        value.pause == HeartbeatPause.WAITING_HOST && value.remoteSessionId == null && value.pendingArm == null &&
        value.pendingAttempt == null && value.resumeBlock == null && value.lastStatus != GmsHeartbeatStatus.UNKNOWN)
        value.copy(pause = null) else value
