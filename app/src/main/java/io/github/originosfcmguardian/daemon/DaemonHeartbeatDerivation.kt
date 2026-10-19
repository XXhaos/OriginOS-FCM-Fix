package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Native grant material. Never accepted from App import/configuration bytes. */
internal data class DaemonHeartbeatDerivationPermit(
    val record: DaemonControlRecord,
    val epoch: String,
    val heartbeatBinding: String,
    val recoveryBinding: String,
    val baselineDigest: String,
    val historicalCaller: GmsHeartbeatCallerIdentity,
    val used: Boolean = false,
) {
    init {
        require(record.mode in setOf(DaemonControlMode.ACTIVE, DaemonControlMode.OFF) && canonicalUuid(epoch))
        require(listOf(heartbeatBinding, recoveryBinding, baselineDigest).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
}

internal object DaemonHeartbeatDerivationPermitCodec {
    fun encode(value: DaemonHeartbeatDerivationPermit): ByteArray {
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(0x47484431); it.writeInt(2)
            it.writeUTF(value.record.owner.installInstance); it.writeLong(value.record.owner.ownerEpoch)
            it.writeLong(value.record.revision); it.writeUTF(value.record.authorityDigest)
            it.writeUTF(value.record.mode.name); it.writeUTF(value.record.migrationId); it.writeUTF(value.epoch)
            it.writeUTF(value.heartbeatBinding); it.writeUTF(value.recoveryBinding); it.writeUTF(value.baselineDigest)
            it.writeUTF(GmsHeartbeatCallerCodec.encodeIdentity(value.historicalCaller)); it.writeBoolean(value.used)
        } }.toByteArray()
        return (body + MessageDigest.getInstance("SHA-256").digest(body)).also {
            require(it.size <= DaemonDomainRole.HEARTBEAT_DERIVATION.maximumBytes)
        }
    }

    fun decode(bytes: ByteArray): DaemonHeartbeatDerivationPermit {
        require(bytes.size in 40..DaemonDomainRole.HEARTBEAT_DERIVATION.maximumBytes)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use {
            require(it.readInt() == 0x47484431)
            val version = it.readInt().also { value -> require(value in 1..2) }
            val owner = DaemonOwnerKey(it.readUTF(), it.readLong()); val revision = it.readLong(); val digest = it.readUTF()
            val mode = if (version == 1) DaemonControlMode.ACTIVE else DaemonControlMode.valueOf(it.readUTF())
            val record = DaemonControlRecord(owner, revision, digest, mode, it.readUTF())
            val epoch = it.readUTF(); val heartbeat = it.readUTF(); val recovery = it.readUTF(); val baseline = it.readUTF()
            val caller = requireNotNull(GmsHeartbeatCallerCodec.decodeIdentity(it.readUTF()))
            val used = it.readUnsignedByte().also { flag -> require(flag in 0..1) } == 1
            DaemonHeartbeatDerivationPermit(record, epoch, heartbeat, recovery, baseline, caller, used).also { _ ->
                require(it.available() == 0)
            }
        }
    }
}

/** Direct codecs only: these functions are also used inside the root's short CAS boundary. */
internal object DaemonHeartbeatDerivation {
    private val baselineRoles = listOf(DaemonDomainRole.HEARTBEAT, DaemonDomainRole.HEARTBEAT_INITIALIZED,
        DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING)

    fun baselineDigest(snapshot: DaemonProtectionRootSnapshot): String {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            for (role in baselineRoles) {
                val value = snapshot.bytes(role)
                out.writeInt(role.ordinal); out.writeInt(value?.size ?: -1); if (value != null) out.write(value)
            }
        } }.toByteArray()
        return DaemonProtectionRootSnapshot.digest(bytes)
    }

    /** Host supplies a REAL historical identity qualified from trusted Binder UID/PID + proc reads.
     * This is a factory for the trusted import/control actor, not an IPC proof verifier. */
    fun prepare(snapshot: DaemonProtectionRootSnapshot, targetActiveRecord: DaemonControlRecord,
        historicalCaller: GmsHeartbeatCallerIdentity): ByteArray? = runCatching {
        if (targetActiveRecord.mode != DaemonControlMode.ACTIVE || targetActiveRecord.owner != snapshot.control.owner ||
            targetActiveRecord.authorityDigest != snapshot.authorityDigest() ||
            targetActiveRecord.migrationId != snapshot.control.migrationId ||
            targetActiveRecord.revision != snapshot.control.revision &&
            (snapshot.control.revision == Long.MAX_VALUE || targetActiveRecord.revision != snapshot.control.revision + 1)) return null
        val parent = parent(snapshot) ?: return null
        if (!eligibleBaseline(snapshot, requireNotNull(parent.epoch)) || !uninterrupted(snapshot)) return null
        val previous = snapshot.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode)
        // No replacing an already-issued or consumed grant for the same parent epoch.
        if (previous?.epoch == parent.epoch) return null
        DaemonHeartbeatDerivationPermitCodec.encode(DaemonHeartbeatDerivationPermit(targetActiveRecord,
            requireNotNull(parent.epoch), requireNotNull(parent.installationBinding), requireNotNull(parent.recoveryBinding),
            baselineDigest(snapshot), historicalCaller))
    }.getOrNull()

    fun masterEnabledAt(snapshot: DaemonProtectionRootSnapshot): Long? {
        val value = ProtectionAuthorityCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY)))
        if (value.storageBlocked || value.mode != ProtectionConsentMode.ACTIVE) return null
        return value.masterEnabledAtMillis ?: value.consents().minOfOrNull { it.consentedAtMillis }
    }

    /** Same-epoch boot continuation only. No consent, consumption, pending state or native grant is reset. */
    fun rebindBundledRecovery(before: DaemonProtectionRootSnapshot, expected: DaemonControlRecord,
        sample: GmsConnectionObservation.Sample, bootId: String, observedAtElapsed: Long,
        nowElapsed: Long, maximumAgeMillis: Long): DaemonProtectionRootSnapshot? {
        if (!freshContinuation(before, expected, sample, bootId, observedAtElapsed, nowElapsed, maximumAgeMillis)) return null
        val parent = parent(before) ?: return null
        if (parent.installationBinding != gmsHeartbeatBindingDigest(sample)) return null
        val recovery = before.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: return null
        if (recovery.binding != parent.recoveryBinding) return null
        val nextBinding = FcmRecoveryPolicy.binding(sample)
        if (nextBinding == parent.recoveryBinding) return before
        val roles = before.domains().toMutableMap()
        roles[DaemonDomainRole.FCM_PROTECTION] = FcmProtectionCodec.encode(parent.copy(recoveryBinding = nextBinding))
        roles[DaemonDomainRole.FCM_RECOVERY] = FcmRecoveryStoreCodec.encode(recovery.copy(binding = nextBinding))
        return DaemonProtectionRootSnapshot(before.control, roles, before.receipts, before.halt,
            Math.addExact(before.dataRevision, 1))
    }

    /** Trusted reconciliation supplies a freshly qualified real App caller, never a stored PID guess. */
    fun refreshUnusedHeartbeatPermit(before: DaemonProtectionRootSnapshot, expected: DaemonControlRecord,
        freshCaller: GmsHeartbeatCallerIdentity, sample: GmsConnectionObservation.Sample, bootId: String,
        observedAtElapsed: Long, nowElapsed: Long, maximumAgeMillis: Long): DaemonProtectionRootSnapshot? {
        if (!freshContinuation(before, expected, sample, bootId, observedAtElapsed, nowElapsed, maximumAgeMillis) ||
            freshCaller.kernelBootId != bootId) return null
        val parent = parent(before) ?: return null
        val permit = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode) ?: return null
        if (permit.used || permit.record != expected || permit.epoch != parent.epoch ||
            permit.heartbeatBinding != parent.installationBinding ||
            permit.heartbeatBinding != gmsHeartbeatBindingDigest(sample) ||
            parent.recoveryBinding != FcmRecoveryPolicy.binding(sample) ||
            permit.historicalCaller.uid != freshCaller.uid || permit.baselineDigest != baselineDigest(before) ||
            !eligibleBaseline(before, permit.epoch)) return null
        val next = permit.copy(recoveryBinding = requireNotNull(parent.recoveryBinding), historicalCaller = freshCaller)
        if (next == permit) return before
        val roles = before.domains().toMutableMap()
        roles[DaemonDomainRole.HEARTBEAT_DERIVATION] = DaemonHeartbeatDerivationPermitCodec.encode(next)
        return DaemonProtectionRootSnapshot(before.control, roles, before.receipts, before.halt,
            Math.addExact(before.dataRevision, 1))
    }

    private fun freshContinuation(before: DaemonProtectionRootSnapshot, expected: DaemonControlRecord,
        sample: GmsConnectionObservation.Sample, bootId: String, observedAtElapsed: Long,
        nowElapsed: Long, maximumAgeMillis: Long): Boolean {
        if (before.control != expected || expected.mode != DaemonControlMode.ACTIVE ||
            expected.authorityDigest != before.authorityDigest() || before.bytes(DaemonDomainRole.CONTROL_PROPOSAL) != null ||
            observedAtElapsed < 0 || nowElapsed < observedAtElapsed || nowElapsed - observedAtElapsed > maximumAgeMillis ||
            sample.bootId != bootId || !sample.isFresh(nowElapsed) || !FcmRecoveryPolicy.qualified(sample, nowElapsed) ||
            !uninterrupted(before) || listOf(DaemonDomainRole.HEARTBEAT_HALT,
                DaemonDomainRole.HEARTBEAT_PENDING).any { before.bytes(it) != null }) return false
        val heartbeat = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
        listOf(DaemonDomainRole.FCM_CONSUMED, DaemonDomainRole.FCM_MCS_CONSUMED).forEach { role ->
            before.bytes(role)?.let(FcmRecoveryStoreCodec::decode)
        }
        return heartbeat == null || heartbeat.pendingArm == null && heartbeat.pendingAttempt == null &&
            heartbeat.resumeBlock == null && heartbeat.pause != HeartbeatPause.OFF_PENDING &&
            heartbeat.lastStatus != GmsHeartbeatStatus.UNKNOWN
    }

    private fun parent(snapshot: DaemonProtectionRootSnapshot): FcmProtectionConsent? {
        if (snapshot.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) != null ||
            snapshot.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) != null) return null
        val value = snapshot.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: return null
        val recovery = snapshot.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: return null
        return value.takeIf { it.configured && it.recoveryPrepared && recovery.configured && recovery.binding == it.recoveryBinding }
    }

    private fun eligibleBaseline(snapshot: DaemonProtectionRootSnapshot, parentEpoch: String): Boolean {
        if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_PENDING) != null) return false
        val halt = snapshot.bytes(DaemonDomainRole.HEARTBEAT_HALT)
        if (halt != null && !halt.contentEquals(byteArrayOf(1))) return false
        val raw = snapshot.bytes(DaemonDomainRole.HEARTBEAT)
        if (raw == null) return halt == null && snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) == null
        if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return false
        val old = HeartbeatSnapshotCodec.decode(raw)
        return !old.configured && old.pause == null && old.remoteSessionId == null && old.durableConsent == null &&
            old.pendingArm == null && old.pendingAttempt == null && old.resumeBlock == null &&
            old.lastStatus != GmsHeartbeatStatus.UNKNOWN && old.lastResultEpoch != parentEpoch && old.generation < Long.MAX_VALUE
    }

    private fun uninterrupted(snapshot: DaemonProtectionRootSnapshot): Boolean {
        if (snapshot.halt != null || listOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.AUTHORITY_STOP_PENDING,
                DaemonDomainRole.STOPPED_BLOCKED).any { snapshot.bytes(it) != null }) return false
        if (snapshot.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
            masterEnabledAt(snapshot) == null) return false
        if (snapshot.bytes(DaemonDomainRole.STOPPED_JOURNAL)?.let(RecoveryJournalCodec::decode)?.latest?.requiresReconcile == true) return false
        val episodes = snapshot.bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode)
        if (episodes?.episodes?.any { it.requestPending || it.endedAtMillis == null && it.requestStatus == FcmRecoveryStatus.UNKNOWN } == true) return false
        return true
    }

    /** Control alone retargets exact native evidence after the host has drained the old endpoint.
     * Runtime admission still requires its existing exact current-record proof. */
    fun applyControlContinuity(before: DaemonProtectionRootSnapshot, projected: DaemonProtectionRootSnapshot,
        change: DaemonRootMutation) {
        if (projected.control.mode !in setOf(DaemonControlMode.ACTIVE, DaemonControlMode.OFF) ||
            before.bytes(DaemonDomainRole.HEARTBEAT_PENDING) != null ||
            before.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null ||
            before.bytes(DaemonDomainRole.AUTHORITY_STOP_PENDING) != null ||
            before.bytes(DaemonDomainRole.STOPPED_BLOCKED) != null) return
        val oldParent = parent(before) ?: return
        val nextParent = parent(projected) ?: return
        if (oldParent.epoch != nextParent.epoch || oldParent.installationBinding != nextParent.installationBinding ||
            oldParent.recoveryBinding != nextParent.recoveryBinding) return
        val permit = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode)
        if (permit != null && !permit.used && permit.record == before.control && permit.epoch == oldParent.epoch &&
            permit.heartbeatBinding == oldParent.installationBinding && permit.recoveryBinding == oldParent.recoveryBinding &&
            permit.baselineDigest == baselineDigest(before) && permit.baselineDigest == baselineDigest(projected) &&
            eligibleBaseline(before, permit.epoch) && eligibleBaseline(projected, permit.epoch)) {
            change.put(DaemonDomainRole.HEARTBEAT_DERIVATION,
                DaemonHeartbeatDerivationPermitCodec.encode(permit.copy(record = projected.control)))
        }
        val proof = before.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)?.let(DaemonHeartbeatOwnerCodec::decode) ?: return
        if (listOf(before, projected).any { it.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true ||
                it.bytes(DaemonDomainRole.HEARTBEAT_HALT) != null }) return
        val old = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return
        val next = projected.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return
        fun clean(value: HeartbeatSnapshot) = value.configured && value.pause == HeartbeatPause.WAITING_HOST &&
            value.remoteSessionId == null && value.pendingArm == null && value.pendingAttempt == null &&
            value.resumeBlock == null && value.lastStatus != GmsHeartbeatStatus.UNKNOWN
        if (clean(old) && clean(next) && proof.record == before.control && proof.epoch == oldParent.epoch &&
            proof.binding == oldParent.installationBinding && proof.generation == old.generation &&
            proof.heartbeatBytesDigest == daemonHeartbeatOwnerDigest(old) &&
            next.generation == old.generation && next.durableConsent == old.durableConsent &&
            old.durableConsent?.epoch == oldParent.epoch && old.bindingDigest == oldParent.installationBinding &&
            daemonHeartbeatOwnerDigest(next) == proof.heartbeatBytesDigest) {
            change.put(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER,
                DaemonHeartbeatOwnerCodec.encode(proof.copy(record = projected.control)))
        }
    }

    /** Returns only the fixed, validated five-role projection; callers cannot supply raw patches. */
    fun materialize(before: DaemonProtectionRootSnapshot, expected: DaemonControlRecord,
        sample: GmsConnectionObservation.Sample, bootId: String, observedAtElapsed: Long,
        nowElapsed: Long, maximumAgeMillis: Long): Pair<HeartbeatSnapshot, DaemonProtectionRootSnapshot>? {
        if (before.control != expected || expected.mode != DaemonControlMode.ACTIVE ||
            observedAtElapsed < 0 || nowElapsed < observedAtElapsed || nowElapsed - observedAtElapsed > maximumAgeMillis ||
            !sample.isFresh(nowElapsed) || sample.bootId != bootId ||
            !FcmRecoveryPolicy.connected(sample, nowElapsed) || GmsHeartbeatPolicy.rejection(sample, nowElapsed) != null ||
            !uninterrupted(before)) return null
        val permit = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode) ?: return null
        if (permit.used || permit.record != expected || permit.historicalCaller.kernelBootId != bootId ||
            baselineDigest(before) != permit.baselineDigest || !eligibleBaseline(before, permit.epoch)) return null
        val parent = parent(before) ?: return null
        if (parent.epoch != permit.epoch || parent.installationBinding != permit.heartbeatBinding ||
            parent.recoveryBinding != permit.recoveryBinding || !parent.admits(sample, true) ||
            gmsHeartbeatBindingDigest(sample) != permit.heartbeatBinding || FcmRecoveryPolicy.binding(sample) != permit.recoveryBinding) return null
        val old = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
        val child = HeartbeatSnapshot(Math.addExact(old?.generation ?: 0, 1), true, HeartbeatPause.WAITING_HOST,
            bindingDigest = permit.heartbeatBinding, lastStatus = old?.lastStatus, lastReason = old?.lastReason,
            schemaVersion = 3, durableConsent = HeartbeatDurableConsent(3, permit.epoch, 0, permit.heartbeatBinding),
            lastResultEpoch = old?.lastResultEpoch, callerIdentity = permit.historicalCaller)
        val domains = before.domains().toMutableMap()
        domains[DaemonDomainRole.HEARTBEAT] = HeartbeatSnapshotCodec.encode(child)
        domains[DaemonDomainRole.HEARTBEAT_INITIALIZED] = byteArrayOf(1)
        domains[DaemonDomainRole.HEARTBEAT_HALT] = null
        domains[DaemonDomainRole.HEARTBEAT_DERIVATION] = DaemonHeartbeatDerivationPermitCodec.encode(permit.copy(used = true))
        domains[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER] = DaemonHeartbeatOwnerCodec.encode(
            DaemonHeartbeatOwner(expected, permit.epoch, child.generation, permit.heartbeatBinding, daemonHeartbeatOwnerDigest(child)))
        return child to DaemonProtectionRootSnapshot(before.control, domains, before.receipts, before.halt,
            Math.addExact(before.dataRevision, 1))
    }
}

/** Environment/package/Binder reads finish before entering either the kernel or root monitor. */
internal class DaemonBundledHeartbeatDerivation(
    private val owner: DaemonOwnerControl,
    private val root: DaemonProtectionRoot,
    private val environment: DaemonOwnerEnvironmentProbe,
    private val guardian: DaemonGuardianInstallation,
    private val bootId: String,
    private val elapsed: () -> Long,
) : DaemonBundledHeartbeatPort {
    override fun renewRevoked(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample,
        closeOriginal: (DaemonProtectionRootSnapshot) -> DaemonHeartbeatConfirmationClosure?): HeartbeatSnapshot? = try {
        val before = root.snapshot()
        val observedAt = elapsed()
        val enabledAt = DaemonHeartbeatDerivation.masterEnabledAt(before)
        if (before.control != record || enabledAt == null ||
            daemonHeartbeatRenewalCandidate(before, sample, bootId, observedAt, observedAt, 8_000)
                ?.callerIdentity?.uid != guardian.identity.uid ||
            environment.read(guardian, bootId, enabledAt) != DaemonOwnerEnvironmentStatus.READY) null
        else {
            val closure = closeOriginal(before)
            if (closure == null) null else owner.renewRevokedHeartbeat(root, record, before.dataRevision,
                closure, sample, bootId, observedAt)
        }
    } catch (_: Exception) { null }
    override fun rebindRecovery(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample): Boolean = try {
        val before = root.snapshot()
        val enabledAt = DaemonHeartbeatDerivation.masterEnabledAt(before)
        val observedAt = elapsed()
        before.control == record && enabledAt != null &&
            environment.read(guardian, bootId, enabledAt) == DaemonOwnerEnvironmentStatus.READY &&
            owner.rebindBundledRecovery(root, record, before.dataRevision, sample, bootId, observedAt)
    } catch (_: Exception) { false }
    override fun derive(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample): HeartbeatSnapshot? = try {
        val before = root.snapshot()
        if (before.control != record) null else {
            val permit = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode)
            val enabledAt = DaemonHeartbeatDerivation.masterEnabledAt(before)
            val observedAt = elapsed()
            if (permit?.historicalCaller?.uid != guardian.identity.uid || enabledAt == null ||
                environment.read(guardian, bootId, enabledAt) != DaemonOwnerEnvironmentStatus.READY) null
            else owner.deriveBundledHeartbeat(root, record, before.dataRevision, sample, bootId, observedAt)
        }
    } catch (_: Exception) { null }
}
