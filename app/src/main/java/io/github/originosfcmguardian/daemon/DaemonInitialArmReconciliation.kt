package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.autoprotection.ProtectionConsentMode
import io.github.originosfcmguardian.core.gms.FcmRecoveryStatus
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryStoreCodec
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec

/** Bounded canonical import parsed outside the kernel; its aggregate and byte arrays are immutable. */
internal class DaemonInitialArmSource private constructor(internal val prepared: DaemonProtectionRootSnapshot,
    val importDigest: String) {
    companion object {
        fun capture(expected: DaemonControlRecord, raw: ByteArray, importDigest: String): DaemonInitialArmSource? = try {
            val frozen = DaemonFrozenMigrationExport.capture(
                DaemonMigrationIntent(expected.owner, expected.migrationId), raw, importDigest)
            DaemonInitialArmSource(frozen.snapshot(), frozen.sha256)
        } catch (_: Exception) { null }
    }
}

/** Compatibility repair for the exact first imported-child ARM failure, never heartbeat UNKNOWN. */
internal object DaemonInitialArmReconciliation {
    /** Hash/canonical decoding is bounded and must finish outside owner/root monitors. */
    fun capture(expected: DaemonControlRecord, originalPreparedRaw: ByteArray,
        importDigest: String): DaemonInitialArmSource? = DaemonInitialArmSource.capture(expected, originalPreparedRaw, importDigest)

    fun project(before: DaemonProtectionRootSnapshot, originalPreparedRaw: ByteArray,
        importDigest: String): Pair<HeartbeatSnapshot, DaemonProtectionRootSnapshot>? =
        capture(before.control, originalPreparedRaw, importDigest)?.let { project(before, it) }

    /** Actual old-process-gone and actual native-worker drain are additional trusted Host preconditions. */
    fun project(before: DaemonProtectionRootSnapshot,
        source: DaemonInitialArmSource): Pair<HeartbeatSnapshot, DaemonProtectionRootSnapshot>? = try {
        projectChecked(before, source.prepared)
    } catch (_: Exception) { null }

    private fun projectChecked(before: DaemonProtectionRootSnapshot,
        source: DaemonProtectionRootSnapshot): Pair<HeartbeatSnapshot, DaemonProtectionRootSnapshot>? {
        val active = before.control
        if (active.mode != DaemonControlMode.ACTIVE || active.revision != 2L ||
            source.control != active.copy(mode = DaemonControlMode.PREPARED, revision = 1) ||
            before.authorityDigest() != active.authorityDigest || source.authorityDigest() != active.authorityDigest ||
            before.halt != null || source.halt != null || !barriersClear(source, false) || !barriersClear(before, true)) return null
        val receipt = before.receipts.singleOrNull() ?: return null
        val activation = receipt.request.intent as? DaemonControlIntent.Activate ?: return null
        if (receipt.committed != active || receipt.request.owner != active.owner || receipt.request.expectedRevision != 1L ||
            activation.pointer.record != active || source.receipts.isNotEmpty()) return null
        val authority = ProtectionAuthorityCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.AUTHORITY)))
        if (authority.mode != ProtectionConsentMode.ACTIVE || authority.storageBlocked ||
            before.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
            source.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return null
        val original = source.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return null
        val current = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return null
        val consent = original.durableConsent ?: return null
        if (original.schemaVersion != 3 || !original.configured || consent.version !in 2..3 ||
            original.callerIdentity == null || original.pause != HeartbeatPause.WAITING_HOST ||
            original.remoteSessionId != null || original.pendingArm != null || original.pendingAttempt != null ||
            original.resumeBlock != null || original.lastStatus == GmsHeartbeatStatus.UNKNOWN || original.generation == Long.MAX_VALUE ||
            source.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
            before.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return null
        val operation = current.pendingArm ?: return null
        val pending = before.bytes(DaemonDomainRole.HEARTBEAT_PENDING)?.let(HeartbeatPendingCodec::decode) ?: return null
        if (current != original.copy(generation = original.generation + 1, pause = HeartbeatPause.RECONFIRM,
                pendingArm = operation, resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER) ||
            pending != HeartbeatPending(HeartbeatPendingKind.ARM, consent.epoch,
                current.generation, operation) ||
            before.bytes(DaemonDomainRole.HEARTBEAT_HALT)?.contentEquals(byteArrayOf(1)) != true) return null
        val parent = source.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: return null
        val nowParent = before.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: return null
        if (parent != nowParent || !parent.configured || !parent.recoveryPrepared || parent.epoch != consent.epoch ||
            parent.installationBinding != original.bindingDigest || consent.bindingDigest != original.bindingDigest) return null
        for (snapshot in listOf(source, before)) {
            val recovery = snapshot.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: return null
            if (!recovery.configured || recovery.binding != parent.recoveryBinding ||
                recovery.events.any { it.status == FcmRecoveryStatus.UNKNOWN }) return null
        }
        val cleaned = current.copy(pendingArm = null, resumeBlock = null, pause = HeartbeatPause.WAITING_HOST)
        val roles = before.domains().toMutableMap()
        roles[DaemonDomainRole.HEARTBEAT] = HeartbeatSnapshotCodec.encode(cleaned)
        roles[DaemonDomainRole.HEARTBEAT_PENDING] = null
        roles[DaemonDomainRole.HEARTBEAT_HALT] = null
        return cleaned to DaemonProtectionRootSnapshot(active, roles, before.receipts, before.halt,
            Math.addExact(before.dataRevision, 1))
    }

    private fun barriersClear(snapshot: DaemonProtectionRootSnapshot, allowExactArmBarrier: Boolean): Boolean {
        val barred = listOf(DaemonDomainRole.CONTROL_PROPOSAL, DaemonDomainRole.AUTHORITY_STOP_PENDING,
            DaemonDomainRole.STOPPED_BLOCKED, DaemonDomainRole.COUNTS_UNAVAILABLE, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT,
            DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, DaemonDomainRole.HEARTBEAT_DERIVATION)
        if (barred.any { snapshot.bytes(it) != null } || !allowExactArmBarrier &&
            listOf(DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING).any { snapshot.bytes(it) != null }) return false
        if (snapshot.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)?.latest?.requiresReconcile == true) return false
        if (snapshot.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)?.episodes?.any {
                it.requestPending || it.requestStatus == FcmRecoveryStatus.UNKNOWN } == true) return false
        for (role in listOf(DaemonDomainRole.FCM_CONSUMED, DaemonDomainRole.FCM_MCS_CONSUMED))
            snapshot.bytes(role)?.let(FcmRecoveryStoreCodec::decode)
        return true
    }
}
