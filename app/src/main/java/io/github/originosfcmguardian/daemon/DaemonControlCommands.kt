package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Domain commands, never raw role bytes. FcmOn material is observed/captured by the shell host. */
internal sealed interface DaemonProtectionCommand {
    /** Completes only the existing inert imported authority; creates no consent or selection. */
    data object Activate : DaemonProtectionCommand
    data object MasterOn : DaemonProtectionCommand
    data object MasterOff : DaemonProtectionCommand
    data class TargetOn(val identity: SelectedTargetIdentity) : DaemonProtectionCommand
    /** An identity-bound OFF cannot remove a subsequently confirmed replacement install. */
    data class TargetOff(val packageName: String, val expectedIdentity: SelectedTargetIdentity? = null) : DaemonProtectionCommand {
        init { require(isSafeAndroidPackageName(packageName) && (expectedIdentity == null || expectedIdentity.packageName == packageName)) }
    }
    data class FcmOn(val sample: GmsConnectionObservation.Sample,
        val historicalCaller: GmsHeartbeatCallerIdentity) : DaemonProtectionCommand
    data object FcmOff : DaemonProtectionCommand
}

internal enum class DaemonControlTargetValidation { MATCH, INVALID, UNAVAILABLE }
/** Fresh shell package/UID evidence, including recipient qualification where requested. No cache. */
internal fun interface DaemonControlTargetValidationPort {
    fun validate(identity: SelectedTargetIdentity, requiresRecipientEligibility: Boolean): DaemonControlTargetValidation
}

/**
 * Trusted host inputs captured after authenticating the real Binder caller and explicit UI action.
 * This is NOT an IPC consent object: a parcel boolean, service startup, permission grant or process
 * reconstruction cannot populate explicitConfirmation. The host separately checks current history,
 * environment, owner/quiescence and network; fcmRecoveryReady means those preparation gates passed.
 */
internal data class DaemonControlCommandContext(val clock: BudgetClock, val fingerprint: String,
    val nowElapsed: Long, val explicitConfirmation: Boolean, val fcmRecoveryReady: Boolean = false)

/**
 * Pure preparation over scratch stores. Existing consent/store operations supply all new tokens,
 * epochs and codecs. No production root is changed before the exact plan is durably staged.
 * null is a bounded decline/unknown; it never authorizes a fallback patch or shell command.
 */
internal class DaemonControlCommandPlanner(private val targets: DaemonControlTargetValidationPort) {
    fun plan(snapshot: DaemonProtectionRootSnapshot, expected: DaemonControlRecord, requestId: String,
        command: DaemonProtectionCommand, context: DaemonControlCommandContext): DaemonControlTransitionPlan? = try {
        prepare(snapshot, expected, requestId, command, context)
    } catch (_: Exception) { null }

    private fun prepare(snapshot: DaemonProtectionRootSnapshot, expected: DaemonControlRecord, requestId: String,
        command: DaemonProtectionCommand, context: DaemonControlCommandContext): DaemonControlTransitionPlan? {
        if (!context.explicitConfirmation || snapshot.control != expected || expected.revision == Long.MAX_VALUE ||
            requestId.isBlank() || requestId.length > 128 || snapshot.halt != null ||
            snapshot.authorityDigest() != expected.authorityDigest ||
            snapshot.receipts.any { it.request.requestId == requestId } ||
            snapshot.bytes(DaemonDomainRole.CONTROL_PROPOSAL) != null ||
            listOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.AUTHORITY_STOP_PENDING,
                DaemonDomainRole.STOPPED_BLOCKED).any { snapshot.bytes(it) != null } ||
            snapshot.bytes(DaemonDomainRole.AUTHORITY_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return null
        if (expected.mode == DaemonControlMode.PREPARED && command != DaemonProtectionCommand.MasterOff &&
            command != DaemonProtectionCommand.Activate) return null
        val old = ProtectionAuthorityCodec.decode(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY)))
        if (old.storageBlocked) return null
        if (command == DaemonProtectionCommand.Activate) {
            if (expected.mode != DaemonControlMode.PREPARED || old.mode != ProtectionConsentMode.ACTIVE) return null
            return DaemonControlTransitionPlan(requestId, expected,
                expected.copy(revision = expected.revision + 1, mode = DaemonControlMode.ACTIVE),
                DaemonControlTransition.ACTIVATE).also { DaemonControlProposalCodec.encode(it) }
        }
        val scratch = ScratchAuthority(old)
        val authority = ProtectionConsentStore(scratch)
        val patch = LinkedHashMap<DaemonDomainRole, ByteArray?>()
        when (command) {
            DaemonProtectionCommand.Activate -> error("Activation handled before configuration")
            DaemonProtectionCommand.MasterOn -> {
                if (!validRuntimeFingerprint(context.fingerprint) || context.nowElapsed < 0) return null
                val selected = selection(snapshot)
                // Validate every retained runnable choice independently; one stale install must not
                // erase unrelated grants or the separately confirmed FCM parent.
                val invalidIds = old.consents().filter { it.id !in old.invalidatedConsentIds && it.restartConsented() }.mapNotNull { consent ->
                    val result = targets.validate(consent.identity, consent is TargetConsent.Recipient)
                    if (result == DaemonControlTargetValidation.UNAVAILABLE) return null
                    consent.id.takeIf { result == DaemonControlTargetValidation.INVALID || consent.identity !in selected ||
                        !consent.acceptsRuntimeFingerprint(context.fingerprint) }
                }.toSet()
                if (!authority.enableMaster(old, context.clock)) return null
                val leases = authority.desiredLeases()
                val invalid = leases.filter { it.consent.id in invalidIds }
                if (invalid.isNotEmpty() && !authority.revokeConfirmedTargets(leases, invalid)) return null
            }
            DaemonProtectionCommand.MasterOff -> if (!authority.pauseMaster(context.clock)) return null
            is DaemonProtectionCommand.TargetOn -> {
                if (!validRuntimeFingerprint(context.fingerprint) ||
                    targets.validate(command.identity, true) != DaemonControlTargetValidation.MATCH) return null
                val selections = ScratchSelection(selection(snapshot))
                if (!TargetSelectionStore(selections).replace(command.identity)) return null
                authority.grantRecipientExplicit(command.identity, context.fingerprint, context.clock)
                patch[DaemonDomainRole.SELECTION] = selections.raw.toByteArray(Charsets.UTF_8)
            }
            is DaemonProtectionCommand.TargetOff -> {
                val current = selection(snapshot)
                val configured = old.consents().firstOrNull { it.identity.packageName == command.packageName }
                if (command.expectedIdentity != null &&
                    (current.any { it.packageName == command.packageName && it != command.expectedIdentity } ||
                        configured?.identity?.let { it != command.expectedIdentity } == true)) return null
                configured?.let { if (!authority.revokeTarget(it.identity)) return null }
                val selections = ScratchSelection(current)
                if (!TargetSelectionStore(selections).remove(command.packageName)) return null
                patch[DaemonDomainRole.SELECTION] = selections.raw.toByteArray(Charsets.UTF_8)
            }
            is DaemonProtectionCommand.FcmOn -> {
                if (old.mode != ProtectionConsentMode.ACTIVE || expected.mode != DaemonControlMode.ACTIVE ||
                    !context.fcmRecoveryReady || context.nowElapsed < 0 ||
                    !FcmRecoveryPolicy.qualified(command.sample, context.nowElapsed) ||
                    command.historicalCaller.kernelBootId != command.sample.bootId || !cleanHeartbeat(snapshot)) return null
                val parentStorage = ScratchParent(snapshot)
                val parent = FcmProtectionStore(parentStorage)
                val recoveryStorage = ScratchRecovery(snapshot)
                val recovery = FcmRecoveryStore(recoveryStorage)
                val confirmed = parent.confirm(command.sample)
                recovery.confirm(FcmRecoveryPolicy.binding(command.sample))
                parent.recoveryPrepared(requireNotNull(confirmed.epoch))
                patch[DaemonDomainRole.FCM_PROTECTION] = FcmProtectionCodec.encode(parent.read())
                patch[DaemonDomainRole.FCM_PROTECTION_HALT] = null
                patch[DaemonDomainRole.FCM_RECOVERY] = FcmRecoveryStoreCodec.encode(recovery.read())
                patch[DaemonDomainRole.FCM_RECOVERY_HALT] = null
                // Child initialization is the host's native derivation permit, bound to this next
                // record and REAL qualified historicalCaller. No App-imported child is fabricated.
            }
            DaemonProtectionCommand.FcmOff -> {
                val parent = FcmProtectionStore(ScratchParent(snapshot))
                val recovery = FcmRecoveryStore(ScratchRecovery(snapshot))
                patch[DaemonDomainRole.FCM_PROTECTION] = FcmProtectionCodec.encode(parent.disable())
                patch[DaemonDomainRole.FCM_PROTECTION_HALT] = byteArrayOf(1)
                patch[DaemonDomainRole.FCM_RECOVERY] = FcmRecoveryStoreCodec.encode(recovery.disable())
                patch[DaemonDomainRole.FCM_RECOVERY_HALT] = byteArrayOf(1)
                val heartbeat = snapshot.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
                if (heartbeat != null) patch[DaemonDomainRole.HEARTBEAT] = HeartbeatSnapshotCodec.encode(
                    heartbeat.copy(configured = false, durableConsent = null))
                patch[DaemonDomainRole.HEARTBEAT_HALT] = byteArrayOf(1)
            }
        }
        val nextAuthority = requireNotNull(scratch.value)
        check(nextAuthority.budget == old.budget)
        val authorityBytes = ProtectionAuthorityCodec.encode(nextAuthority)
        if (!authorityBytes.contentEquals(requireNotNull(snapshot.bytes(DaemonDomainRole.AUTHORITY))))
            patch[DaemonDomainRole.AUTHORITY] = authorityBytes
        val next = expected.copy(revision = expected.revision + 1,
            authorityDigest = DaemonProtectionRootSnapshot.digest(authorityBytes),
            mode = if (nextAuthority.mode == ProtectionConsentMode.ACTIVE) DaemonControlMode.ACTIVE else DaemonControlMode.OFF)
        return DaemonControlTransitionPlan(requestId, expected, next, DaemonControlTransition.SET_AUTHORITY, patch)
            .also { DaemonControlProposalCodec.encode(it) } // Bound the complete staged material now.
    }

    private fun selection(snapshot: DaemonProtectionRootSnapshot): List<SelectedTargetIdentity> {
        val bytes = snapshot.bytes(DaemonDomainRole.SELECTION) ?: return emptyList()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return (TargetSelectionCodec.decode(text) as? TargetSelectionDecodeResult.Valid)?.values
            ?: error("Selection cannot be confirmed")
    }

    private fun cleanHeartbeat(snapshot: DaemonProtectionRootSnapshot): Boolean {
        if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_PENDING) != null) return false
        val raw = snapshot.bytes(DaemonDomainRole.HEARTBEAT)
        if (raw == null) return snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED) == null &&
            snapshot.bytes(DaemonDomainRole.HEARTBEAT_HALT) == null
        if (snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return false
        val old = HeartbeatSnapshotCodec.decode(raw)
        return !old.configured && old.pause == null && old.remoteSessionId == null && old.durableConsent == null &&
            old.pendingArm == null && old.pendingAttempt == null && old.resumeBlock == null &&
            old.lastStatus != GmsHeartbeatStatus.UNKNOWN && old.generation < Long.MAX_VALUE
    }

    private class ScratchAuthority(var value: ProtectionAuthoritySnapshot?) : ProtectionAuthorityStorage {
        override fun <T> exclusive(action: () -> T): T = action()
        override fun read() = value
        override fun write(value: ProtectionAuthoritySnapshot) { this.value = value }
    }
    private class ScratchSelection(values: List<SelectedTargetIdentity>) : TargetSelectionPreferences {
        var raw = TargetSelectionCodec.encode(values)
        override fun read() = raw
        override fun write(value: String): Boolean { raw = value; return true }
        override fun clear(): Boolean { raw = ""; return true }
    }
    private class ScratchParent(snapshot: DaemonProtectionRootSnapshot) : FcmProtectionStorage {
        private var value = snapshot.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
        private var halted = snapshot.bytes(DaemonDomainRole.FCM_PROTECTION_HALT) != null
        override fun read() = value.copy(configured = value.configured && !halted)
        override fun write(value: FcmProtectionConsent) { this.value = value }
        override fun halt() { halted = true }
        override fun releaseHalt() { halted = false }
    }
    private class ScratchRecovery(snapshot: DaemonProtectionRootSnapshot) : FcmRecoveryStorage {
        private var value = snapshot.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
        private var halted = snapshot.bytes(DaemonDomainRole.FCM_RECOVERY_HALT) != null
        override fun read() = value.copy(configured = value.configured && !halted)
        override fun write(value: FcmRecoverySnapshot) { this.value = value }
        override fun halt() { halted = true }
        override fun releaseHalt() { halted = false }
        override fun markConsumed(value: FcmRecoverySnapshot) = error("Configuration cannot consume attempts")
        override fun clearConsumed() = error("Configuration cannot clear attempts")
        override fun markMcsConsumed(value: FcmRecoverySnapshot) = error("Configuration cannot consume attempts")
        override fun clearMcsConsumed() = error("Configuration cannot clear attempts")
    }
}
