package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.core.gms.FcmRecoveryReason
import io.github.originosfcmguardian.core.gms.FcmRecoveryStatus
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmprotection.FcmProtectionConsent
import io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeBlock
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeOutcome
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryStoreCodec
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatPause
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatPendingCodec
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshotCodec
import io.github.originosfcmguardian.gmsheartbeat.currentEpochUnknown

/**
 * Settles leftover in-flight or halted FCM/heartbeat markers ONLY after the trusted host has
 * verified that all local workers/child processes are quiescent and any previous endpoint is closed.
 * Never fabricates consent, alters selected targets or replays a prior command.
 */
internal object DaemonQuiescentHeartbeatSettlement {
    fun needsSettlement(snapshot: DaemonProtectionRootSnapshot): Boolean = runCatching {
        if (snapshot.control.mode !in setOf(DaemonControlMode.ACTIVE, DaemonControlMode.OFF) ||
            snapshot.halt != null || snapshot.authorityDigest() != snapshot.control.authorityDigest ||
            snapshot.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
            listOf(DaemonDomainRole.CONTROL_PROPOSAL, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
                DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED)
                .any { snapshot.bytes(it) != null }) return false
        val authority = ProtectionAuthorityCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY)))
        if (authority.storageBlocked) return false
        val episodes = snapshot.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
        val episodesDirty = episodes?.episodes?.any {
            it.outcome == FcmRecoveryEpisodeOutcome.OBSERVING || it.requestPending
        } == true
        val parent = snapshot.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
        val recovery = snapshot.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode)
        val parentActive = parent.configured && parent.recoveryPrepared &&
            snapshot.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) == null &&
            snapshot.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) == null &&
            recovery?.configured == true && recovery.binding == parent.recoveryBinding
        val pendingBytes = snapshot.bytes(DaemonDomainRole.HEARTBEAT_PENDING)?.also { HeartbeatPendingCodec.decode(it) }
        val haltBytes = snapshot.bytes(DaemonDomainRole.HEARTBEAT_HALT)
        if (haltBytes != null && !haltBytes.contentEquals(byteArrayOf(1))) return false
        val rawHeartbeat = snapshot.bytes(DaemonDomainRole.HEARTBEAT)
        if (rawHeartbeat == null) {
            if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) != null) return false
            return episodesDirty || pendingBytes != null
        }
        if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return false
        val heartbeat = HeartbeatSnapshotCodec.decode(rawHeartbeat)
        if (heartbeat.generation == Long.MAX_VALUE) return false
        val heartbeatDirty = if (parentActive && heartbeat.configured && heartbeat.schemaVersion == 3 &&
            heartbeat.durableConsent?.version in 2..3 && heartbeat.durableConsent?.epoch == parent.epoch &&
            heartbeat.bindingDigest == parent.installationBinding && heartbeat.callerIdentity != null) {
            pendingBytes != null || haltBytes != null || heartbeat.pendingArm != null ||
                heartbeat.pendingAttempt != null || heartbeat.resumeBlock != null ||
                heartbeat.currentEpochUnknown() || heartbeat.lastStatus == GmsHeartbeatStatus.UNKNOWN ||
                heartbeat.pause in setOf(HeartbeatPause.OFF_PENDING, HeartbeatPause.UNKNOWN_RESULT,
                    HeartbeatPause.LEFTOVER_PENDING, HeartbeatPause.RECONFIRM, HeartbeatPause.STORAGE_FAILURE,
                    HeartbeatPause.HOST_LOST) ||
                snapshot.control.mode == DaemonControlMode.OFF && heartbeat.remoteSessionId != null
        } else {
            pendingBytes != null || heartbeat.configured || heartbeat.durableConsent != null ||
                heartbeat.pause != null || heartbeat.remoteSessionId != null ||
                heartbeat.pendingArm != null || heartbeat.pendingAttempt != null ||
                heartbeat.resumeBlock != null || heartbeat.lastStatus == GmsHeartbeatStatus.UNKNOWN
        }
        episodesDirty || heartbeatDirty
    }.getOrDefault(false)

    fun project(before: DaemonProtectionRootSnapshot): DaemonProtectionRootSnapshot? = runCatching {
        if (!needsSettlement(before)) return null
        val roles = before.domains().toMutableMap()
        before.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)?.let { snapshot ->
            val reconstructed = snapshot.episodes.map { episode ->
                val closed = if (episode.outcome == FcmRecoveryEpisodeOutcome.OBSERVING) episode.copy(
                    endedAtMillis = maxOf(episode.lastObservedAtMillis, episode.requestAtMillis ?: 0),
                    endedElapsedMillis = maxOf(episode.lastObservedElapsedMillis, episode.requestElapsedMillis ?: 0),
                    outcome = FcmRecoveryEpisodeOutcome.COVERAGE_GAP,
                    lastBlock = FcmRecoveryEpisodeBlock.OBSERVATION_UNKNOWN) else episode
                if (closed.requestPending) closed.copy(requestPending = false,
                    requestStatus = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.BRIDGE_UNAVAILABLE)
                else closed
            }
            if (reconstructed != snapshot.episodes) {
                roles[DaemonDomainRole.FCM_EPISODES] = FcmRecoveryEpisodeCodec.encode(
                    snapshot.copy(availability = FcmEpisodeAvailability.AVAILABLE, episodes = reconstructed))
            }
        }
        val parent = before.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
        val recovery = before.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode)
        val parentActive = parent.configured && parent.recoveryPrepared &&
            before.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) == null &&
            before.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) == null &&
            recovery?.configured == true && recovery.binding == parent.recoveryBinding
        val heartbeat = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
        if (heartbeat == null) {
            roles[DaemonDomainRole.HEARTBEAT_PENDING] = null
        } else if (parentActive && heartbeat.configured && heartbeat.schemaVersion == 3 &&
            heartbeat.durableConsent?.version in 2..3 && heartbeat.durableConsent?.epoch == parent.epoch &&
            heartbeat.bindingDigest == parent.installationBinding && heartbeat.callerIdentity != null) {
            val clearUnknown = heartbeat.lastStatus == GmsHeartbeatStatus.UNKNOWN
            val cleaned = heartbeat.copy(
                generation = Math.addExact(heartbeat.generation, 1),
                pause = HeartbeatPause.WAITING_HOST,
                pendingAttempt = null,
                pendingArm = null,
                remoteSessionId = null,
                resumeBlock = null,
                lastStatus = if (clearUnknown) null else heartbeat.lastStatus,
                lastReason = if (clearUnknown) null else heartbeat.lastReason,
                lastResultEpoch = if (clearUnknown) null else heartbeat.lastResultEpoch,
            )
            roles[DaemonDomainRole.HEARTBEAT] = HeartbeatSnapshotCodec.encode(cleaned)
            roles[DaemonDomainRole.HEARTBEAT_INITIALIZED] = byteArrayOf(1)
            roles[DaemonDomainRole.HEARTBEAT_PENDING] = null
            roles[DaemonDomainRole.HEARTBEAT_HALT] = null
            roles[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER] = DaemonHeartbeatOwnerCodec.encode(
                DaemonHeartbeatOwner(before.control, requireNotNull(parent.epoch), cleaned.generation,
                    requireNotNull(parent.installationBinding), daemonHeartbeatOwnerDigest(cleaned)))
        } else {
            val clearUnknown = heartbeat.lastStatus == GmsHeartbeatStatus.UNKNOWN
            val cleaned = heartbeat.copy(
                generation = Math.addExact(heartbeat.generation, 1),
                configured = false,
                durableConsent = null,
                pause = null,
                pendingAttempt = null,
                pendingArm = null,
                remoteSessionId = null,
                resumeBlock = null,
                lastStatus = if (clearUnknown) null else heartbeat.lastStatus,
                lastReason = if (clearUnknown) null else heartbeat.lastReason,
                lastResultEpoch = null,
            )
            roles[DaemonDomainRole.HEARTBEAT] = HeartbeatSnapshotCodec.encode(cleaned)
            roles[DaemonDomainRole.HEARTBEAT_INITIALIZED] = byteArrayOf(1)
            roles[DaemonDomainRole.HEARTBEAT_PENDING] = null
            roles[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER] = null
            roles[DaemonDomainRole.HEARTBEAT_HALT] = byteArrayOf(1)
        }
        val next = DaemonProtectionRootSnapshot(before.control, roles, before.receipts, before.halt,
            Math.addExact(before.dataRevision, 1))
        if (next.sameContent(before)) null else next
    }.getOrNull()
}
