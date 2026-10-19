package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryStoreCodec
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec

/** Host-only evidence after original native closure and actual worker drain, never an IPC input. */
internal data class DaemonHeartbeatConfirmationClosure(val record: DaemonControlRecord, val heartbeatDigest: String) {
    fun matches(snapshot: DaemonProtectionRootSnapshot): Boolean = record == snapshot.control &&
        snapshot.bytes(DaemonDomainRole.HEARTBEAT)?.let(DaemonProtectionRootSnapshot::digest) == heartbeatDigest
}

/** An explicit confirmation may retire only settled authority, never an uncertain operation. */
internal fun daemonHeartbeatConfirmationCandidate(snapshot: DaemonProtectionRootSnapshot,
    allowSettledIdentityChange: Boolean = false): HeartbeatSnapshot? = runCatching {
    if (snapshot.control.mode != DaemonControlMode.ACTIVE || snapshot.halt != null ||
        listOf(DaemonDomainRole.HEARTBEAT_PENDING, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED,
            DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT,
            DaemonDomainRole.CONTROL_PROPOSAL).any { snapshot.bytes(it) != null } ||
        snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return null
    val parent = snapshot.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: return null
    val old = snapshot.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return null
    val consent = old.durableConsent ?: return null
    if (!parent.configured || !parent.recoveryPrepared || !old.configured || old.schemaVersion != 3 ||
        consent.version != 3 || old.callerIdentity == null || consent.epoch != parent.epoch ||
        old.bindingDigest != parent.installationBinding || consent.bindingDigest != parent.installationBinding ||
        old.pendingArm != null || old.pendingAttempt != null || old.currentEpochUnknown() ||
        old.lastStatus == GmsHeartbeatStatus.UNKNOWN || old.generation == Long.MAX_VALUE ||
        old.pause !in setOf(null, HeartbeatPause.WAITING_HOST)) return null
    val halt = snapshot.bytes(DaemonDomainRole.HEARTBEAT_HALT)
    val settledRevocation = old.resumeBlock == HeartbeatResumeBlock.SECURITY_FAILURE &&
        old.lastStatus == GmsHeartbeatStatus.NOT_SENT && (old.lastReason == GmsHeartbeatReason.SESSION_REVOKED ||
            allowSettledIdentityChange && old.lastReason == GmsHeartbeatReason.IDENTITY_CHANGED) &&
        halt?.contentEquals(byteArrayOf(1)) == true
    if (!settledRevocation && (old.resumeBlock != null || halt != null)) return null
    val episodes = snapshot.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
    if (episodes?.episodes?.any { it.requestPending || it.endedAtMillis == null &&
            it.requestStatus == FcmRecoveryStatus.UNKNOWN } == true) return null
    old
}.getOrNull()

/** Existing fixed-target consent, freshly revalidated Google-signed GMS; never a new grant/epoch.
 * Old hashes cannot establish install continuity. This policy intentionally revalidates the current
 * trusted package (including a trusted reinstall), not an inferred old UID/version tuple.
 */
internal fun daemonHeartbeatRenewalCandidate(before: DaemonProtectionRootSnapshot,
    sample: GmsConnectionObservation.Sample, bootId: String, observedAtElapsed: Long,
    nowElapsed: Long, maximumAgeMillis: Long): HeartbeatSnapshot? = runCatching {
    val old = daemonHeartbeatConfirmationCandidate(before, allowSettledIdentityChange = true) ?: return null
    val changed = old.bindingDigest != gmsHeartbeatBindingDigest(sample)
    val revoked = old.resumeBlock == HeartbeatResumeBlock.SECURITY_FAILURE && old.lastStatus == GmsHeartbeatStatus.NOT_SENT &&
        (old.lastReason == GmsHeartbeatReason.SESSION_REVOKED || changed && old.lastReason == GmsHeartbeatReason.IDENTITY_CHANGED)
    if ((!revoked && !(changed && old.resumeBlock == null)) || before.authorityDigest() != before.control.authorityDigest ||
        before.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
        DaemonHeartbeatDerivation.masterEnabledAt(before) == null || observedAtElapsed < 0 ||
        nowElapsed < observedAtElapsed || nowElapsed - observedAtElapsed > maximumAgeMillis ||
        sample.bootId != bootId || !FcmRecoveryPolicy.qualified(sample, nowElapsed)) return null
    val parent = FcmProtectionCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.FCM_PROTECTION)))
    val recovery = before.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: return null
    if (!recovery.configured || recovery.binding != parent.recoveryBinding ||
        before.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)?.latest?.requiresReconcile == true) return null
    listOf(DaemonDomainRole.FCM_CONSUMED, DaemonDomainRole.FCM_MCS_CONSUMED).forEach {
        before.bytes(it)?.let(FcmRecoveryStoreCodec::decode)
    }
    before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode)
    before.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)?.let(DaemonHeartbeatOwnerCodec::decode)
    old
}.getOrNull()

internal fun daemonHeartbeatRenewal(before: DaemonProtectionRootSnapshot, closure: DaemonHeartbeatConfirmationClosure,
    sample: GmsConnectionObservation.Sample, bootId: String, observedAtElapsed: Long,
    nowElapsed: Long, maximumAgeMillis: Long): Pair<HeartbeatSnapshot, DaemonProtectionRootSnapshot>? {
    if (!closure.matches(before)) return null
    val old = daemonHeartbeatRenewalCandidate(before, sample, bootId, observedAtElapsed, nowElapsed, maximumAgeMillis) ?: return null
    val parent = FcmProtectionCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.FCM_PROTECTION)))
    val recovery = FcmRecoveryStoreCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.FCM_RECOVERY)))
    val binding = gmsHeartbeatBindingDigest(sample)
    // Leave boot-only rebinding to its existing continuation path.
    val recoveryBinding = if (parent.installationBinding != binding) FcmRecoveryPolicy.binding(sample)
        else parent.recoveryBinding
    val next = old.copy(pause = HeartbeatPause.WAITING_HOST, remoteSessionId = null, resumeBlock = null,
        bindingDigest = binding, durableConsent = requireNotNull(old.durableConsent).copy(bindingDigest = binding))
    val proof = DaemonHeartbeatOwner(before.control, requireNotNull(next.durableConsent).epoch, next.generation,
        requireNotNull(next.bindingDigest), daemonHeartbeatOwnerDigest(next))
    val roles = before.domains() + mapOf(
        DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent.copy(installationBinding = binding,
            recoveryBinding = recoveryBinding)),
        DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(recovery.copy(binding = recoveryBinding)),
        DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(next),
        DaemonDomainRole.HEARTBEAT_HALT to null,
        DaemonDomainRole.HEARTBEAT_DAEMON_OWNER to DaemonHeartbeatOwnerCodec.encode(proof))
    return next to DaemonProtectionRootSnapshot(before.control, roles, before.receipts, before.halt,
        Math.addExact(before.dataRevision, 1))
}

internal fun HeartbeatSnapshot.retiredForExplicitConfirmation() = copy(configured = false, pause = null,
    remoteSessionId = null, durableConsent = null, resumeBlock = null)

internal fun daemonRequestsHeartbeatRetirement(plan: DaemonControlTransitionPlan): Boolean = plan.patch().let { patch ->
    patch.containsKey(DaemonDomainRole.HEARTBEAT) && patch.containsKey(DaemonDomainRole.HEARTBEAT_HALT) &&
        patch[DaemonDomainRole.HEARTBEAT_HALT] == null &&
        patch[DaemonDomainRole.FCM_PROTECTION]?.let(FcmProtectionCodec::decode)?.configured == true
}

/** Fixed projection of an already trusted/staged confirmation, not a general runtime patch. */
internal fun daemonHeartbeatConfirmationRetirement(before: DaemonProtectionRootSnapshot,
    plan: DaemonControlTransitionPlan): HeartbeatSnapshot? = runCatching {
    if (!daemonRequestsHeartbeatRetirement(plan) || before.control != plan.expected || plan.transition != DaemonControlTransition.SET_AUTHORITY ||
        plan.next.mode != DaemonControlMode.ACTIVE) return null
    val patch = plan.patch()
    if (!patch.containsKey(DaemonDomainRole.HEARTBEAT_HALT) || patch[DaemonDomainRole.HEARTBEAT_HALT] != null) return null
    val oldParent = before.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: return null
    val newParent = patch[DaemonDomainRole.FCM_PROTECTION]?.let(FcmProtectionCodec::decode) ?: return null
    if (!newParent.configured || !newParent.recoveryPrepared || newParent.epoch == oldParent.epoch) return null
    // The exact staged proposal/fence is checked by the control actor before this projection.
    val unfenced = DaemonProtectionRootSnapshot(before.control,
        before.domains() + (DaemonDomainRole.CONTROL_PROPOSAL to null), before.receipts, null, before.dataRevision)
    val old = daemonHeartbeatConfirmationCandidate(unfenced) ?: return null
    val proposed = patch[DaemonDomainRole.HEARTBEAT]?.let(HeartbeatSnapshotCodec::decode) ?: return null
    proposed.takeIf { it == old.retiredForExplicitConfirmation() }
}.getOrNull()
