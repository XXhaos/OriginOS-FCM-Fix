package io.github.originosfcmguardian.pushactivation

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.autoprotection.ProtectionConsentMode
import io.github.originosfcmguardian.core.gms.FcmRecoveryStatus
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.daemon.*
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshotCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec

/** Admission to a new explicit confirmation only; the backend still supplies all write authority. */
internal fun pushActivationBlockReason(automatic: AutomaticProtectionState, fcm: FcmProtectionUiState,
    heartbeat: GmsAutoHeartbeatUiState, recovery: FcmRecoveryUiState,
    remoteRoot: DaemonProtectionRootSnapshot?): PushActivationReason? = when {
    automatic.revocationSaveFailed || fcm.storageBlocked || recovery.storageBlocked -> PushActivationReason.STORAGE_UNAVAILABLE
    automatic.waitingForReconciliation || heartbeat.stopPending || fcm.maintenance == FcmMaintenanceState.STOP_PENDING ->
        PushActivationReason.RECONCILIATION_REQUIRED
    heartbeat.resumeBlocked && !cleanOffHeartbeatMayStart(fcm, heartbeat, recovery, remoteRoot) ->
        PushActivationReason.RECONCILIATION_REQUIRED
    else -> null
}

/** A canonical OFF halt prevents resume of old authority. It does not forbid a freshly confirmed ON.
 * Inspect exact immutable root bytes rather than treating every resumeBlocked projection as clean.
 * No marker is changed, no previous ticket is unblocked and no consent is created here. */
private fun cleanOffHeartbeatMayStart(fcm: FcmProtectionUiState, heartbeat: GmsAutoHeartbeatUiState,
    recovery: FcmRecoveryUiState, root: DaemonProtectionRootSnapshot?): Boolean = runCatching {
    if (root == null || fcm.configured || heartbeat.configured || recovery.configured ||
        root.control.mode !in setOf(DaemonControlMode.ACTIVE, DaemonControlMode.OFF) || root.halt != null ||
        root.authorityDigest() != root.control.authorityDigest ||
        root.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
        root.bytes(DaemonDomainRole.HEARTBEAT_HALT)?.contentEquals(byteArrayOf(1)) != true ||
        root.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
        listOf(DaemonDomainRole.HEARTBEAT_PENDING, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED,
            DaemonDomainRole.CONTROL_PROPOSAL).any { root.bytes(it) != null }) return false
    val authority = ProtectionAuthorityCodec.decode(requireNotNull(root.bytes(DaemonDomainRole.AUTHORITY)))
    if (authority.storageBlocked) return false
    if (root.control.mode == DaemonControlMode.ACTIVE) {
        if (DaemonHeartbeatDerivation.masterEnabledAt(root) == null) return false
    } else if (authority.mode !in setOf(ProtectionConsentMode.USER_PAUSED, ProtectionConsentMode.DISABLED)) return false
    val saved = HeartbeatSnapshotCodec.decode(requireNotNull(root.bytes(DaemonDomainRole.HEARTBEAT)))
    if (saved.configured || saved.pause != null || saved.remoteSessionId != null || saved.durableConsent != null ||
        saved.pendingArm != null || saved.pendingAttempt != null || saved.resumeBlock != null ||
        saved.lastStatus == GmsHeartbeatStatus.UNKNOWN || saved.generation == Long.MAX_VALUE) return false
    // Parent/recovery OFF markers remain intact; malformed values cannot be treated as plain OFF.
    for (role in listOf(DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT)) {
        root.bytes(role)?.let { if (!it.contentEquals(byteArrayOf(1))) return false }
    }
    if (root.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)?.latest?.requiresReconcile == true) return false
    val episodes = root.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
    episodes?.episodes?.none { it.requestPending || it.endedAtMillis == null && it.requestStatus == FcmRecoveryStatus.UNKNOWN } != false
}.getOrDefault(false)
