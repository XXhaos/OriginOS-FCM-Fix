package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshotCodec
import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.autoprotection.ProtectionConsentMode

/** Honor a local explicit OFF that won the handoff race; never infer positive consent from it. */
internal fun pausedDaemonMigrationRequiresStop(snapshot: DaemonProtectionRootSnapshot): Boolean {
    val authority = ProtectionAuthorityCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY)))
    if (authority.mode != ProtectionConsentMode.USER_PAUSED) return false
    check(authority.schemaVersion == 3 && !authority.storageBlocked && snapshot.halt == null &&
        snapshot.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) == true &&
        snapshot.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) == null &&
        snapshot.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) == null)
    return true
}

/** Inert cold export is usable only under its original confirmed bundled parent. Never rewrites it. */
internal fun validateDaemonMigrationHeartbeat(snapshot: DaemonProtectionRootSnapshot) {
    val child = snapshot.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return
    if (!child.configured) return
    val parent = FcmProtectionCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.FCM_PROTECTION)))
    val consent = requireNotNull(child.durableConsent)
    check(parent.configured && parent.recoveryPrepared && consent.epoch == parent.epoch &&
        consent.bindingDigest == parent.installationBinding && child.bindingDigest == parent.installationBinding)
    check(snapshot.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) == null &&
        snapshot.bytes(DaemonDomainRole.HEARTBEAT_HALT) == null && snapshot.bytes(DaemonDomainRole.HEARTBEAT_PENDING) == null)
}
