package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.TargetSelectionPreferences
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

/**
 * The read/copy/write methods of existing stores may straddle a control-actor OFF. Compare the
 * protected projection against the CURRENT aggregate at commit, not the old caller snapshot.
 * This guard also covers direct root patches. Runtime pause/result/generation data is separate.
 */
internal object DaemonDomainConsentGuard {
    fun protectedConfigurationChanged(before: DaemonProtectionRootSnapshot, after: DaemonProtectionRootSnapshot): Boolean {
        fun changed(role: DaemonDomainRole): Boolean = !before.sameRole(after, role)
        if (listOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.AUTHORITY_INITIALIZED,
                DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.SELECTION).any(::changed)) return true
        if (changed(DaemonDomainRole.FCM_PROTECTION)) {
            val a = before.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
            val b = after.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
            if (a != b) return true
        }
        if (changed(DaemonDomainRole.FCM_RECOVERY)) {
            val a = before.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
            val b = after.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
            if (a.configured != b.configured || a.binding != b.binding) return true
        }
        if (changed(DaemonDomainRole.HEARTBEAT)) {
            val a = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
            val b = after.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode)
            if ((a?.configured ?: false) != (b?.configured ?: false) || a?.durableConsent != b?.durableConsent ||
                a?.bindingDigest != b?.bindingDigest) return true
        }
        for (role in listOf(DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT,
                DaemonDomainRole.HEARTBEAT_HALT)) {
            if (before.bytes(role) != null && after.bytes(role) == null) return true
        }
        return false
    }
    fun requireUnchanged(before: DaemonProtectionRootSnapshot, after: DaemonProtectionRootSnapshot) {
        check(!protectedConfigurationChanged(before, after)) { "Protected configuration changes require kernel control actor" }
    }
}

/** Existing domain codecs over ONE root. No Android Context, app callback or alternate authority. */
internal class DaemonDomainStores(private val root: DaemonProtectionRoot) {
    private class DecodedAuthority(val bytes: ByteArray, val value: ProtectionAuthoritySnapshot)
    private var decodedAuthority: DecodedAuthority? = null

    /** Called only inside the existing root transaction, after fresh storage/marker checks. */
    private fun authorityValue(bytes: ByteArray): ProtectionAuthoritySnapshot {
        decodedAuthority?.let { if (it.bytes.contentEquals(bytes)) return it.value }
        fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        fun identity(value: io.github.originosfcmguardian.autostart.SelectedTargetIdentity) =
            value.copy(signerSha256 = frozen(value.signerSha256))
        fun consent(value: TargetConsent): TargetConsent = when (value) {
            is TargetConsent.Legacy -> TargetConsent.Legacy(value.value.copy(identity = identity(value.identity)))
            is TargetConsent.Recipient -> TargetConsent.Recipient(value.value.copy(identity = identity(value.identity)))
        }
        val raw = ProtectionAuthorityCodec.decode(bytes)
        val value = raw.copy(token = raw.token?.let { it.copy(identity = identity(it.identity)) },
            budget = raw.budget.copy(attempts = frozen(raw.budget.attempts)),
            targetConsents = frozen(raw.targetConsents.map(::consent)), invalidatedConsentIds = frozen(raw.invalidatedConsentIds))
        decodedAuthority = DecodedAuthority(bytes.copyOf(), value)
        return value
    }

    /** Both native heartbeat state and its ownership proof commit in the same root transaction. */
    fun <T> heartbeatTransaction(action: () -> T): T = atomic(action)
    fun heartbeatBytes(): ByteArray? = bytes(DaemonDomainRole.HEARTBEAT)
    val heartbeatOwnerProvenance: DaemonHeartbeatOwnerStorage = object : DaemonHeartbeatOwnerStorage {
        override fun read(): ByteArray? = bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)
        override fun write(bytes: ByteArray?) = root.put(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, bytes)
    }
    private fun bytes(role: DaemonDomainRole): ByteArray? {
        check(root.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) == null) { "Interrupted imported storage requires reconciliation" }
        return root.bytes(role)
    }
    private fun present(role: DaemonDomainRole) = bytes(role) != null
    private fun marker(role: DaemonDomainRole) = root.put(role, byteArrayOf(1))
    private fun remove(role: DaemonDomainRole) = root.put(role, null)
    private fun initialized(role: DaemonDomainRole) { check(bytes(role)?.contentEquals(byteArrayOf(1)) == true) }
    private fun <T> atomic(action: () -> T): T = root.transaction { action() }

    val authority: ProtectionAuthorityStorage = object : ProtectionAuthorityStorage {
        override fun <T> exclusive(action: () -> T): T = atomic(action)
        override fun read(): ProtectionAuthoritySnapshot? = atomic {
            val original = bytes(DaemonDomainRole.AUTHORITY)
            if (original == null) {
                check(!present(DaemonDomainRole.AUTHORITY_INITIALIZED) && !present(DaemonDomainRole.AUTHORITY_STOP_PENDING) &&
                    !present(DaemonDomainRole.LEGACY_BUDGET) && !present(DaemonDomainRole.LEGACY_BUDGET_INITIALIZED))
                null
            } else {
                initialized(DaemonDomainRole.AUTHORITY_INITIALIZED)
                val value = authorityValue(original)
                if (present(DaemonDomainRole.AUTHORITY_STOP_PENDING)) authorityWithRetirementMarker(value) else value
            }
        }
        override fun write(value: ProtectionAuthoritySnapshot) = atomic {
            check(root.inControlTransaction()) { "Use the control actor for authority mutations" }
            read(); check(!present(DaemonDomainRole.AUTHORITY_STOP_PENDING) && !value.storageBlocked)
            marker(DaemonDomainRole.AUTHORITY_INITIALIZED)
            root.put(DaemonDomainRole.AUTHORITY, ProtectionAuthorityCodec.encode(value))
        }
    }

    val recoveryJournal: RecoveryJournalStorage = object : RecoveryJournalStorage {
        override fun read(): ByteArray? = atomic {
            check(!present(DaemonDomainRole.STOPPED_BLOCKED))
            bytes(DaemonDomainRole.STOPPED_JOURNAL)
        }
        override fun write(bytes: ByteArray) = atomic {
            read(); RecoveryJournalCodec.decode(bytes)
            root.put(DaemonDomainRole.STOPPED_JOURNAL, bytes)
        }
        override fun markUncertain() = marker(DaemonDomainRole.STOPPED_BLOCKED)
    }

    val recoveryCounts: RecoveryCountsStorage = object : RecoveryCountsStorage {
        override fun read(): ByteArray? = atomic {
            check(!present(DaemonDomainRole.COUNTS_UNAVAILABLE)); bytes(DaemonDomainRole.RECOVERY_COUNTS)
        }
        override fun write(bytes: ByteArray) = atomic {
            read(); RecoveryCountsCodec.decode(bytes)
            root.put(DaemonDomainRole.RECOVERY_COUNTS, bytes)
        }
        override fun markUnavailable() = marker(DaemonDomainRole.COUNTS_UNAVAILABLE)
    }

    val fcmProtection: FcmProtectionStorage = object : FcmProtectionStorage {
        override fun read(): FcmProtectionConsent = atomic {
            val saved = bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode) ?: FcmProtectionConsent()
            saved.copy(configured = saved.configured && !present(DaemonDomainRole.FCM_PROTECTION_HALT))
        }
        override fun write(value: FcmProtectionConsent) = atomic {
            read(); root.put(DaemonDomainRole.FCM_PROTECTION, FcmProtectionCodec.encode(value))
        }
        override fun halt() = marker(DaemonDomainRole.FCM_PROTECTION_HALT)
        override fun releaseHalt() = remove(DaemonDomainRole.FCM_PROTECTION_HALT)
    }

    val fcmRecovery: FcmRecoveryStorage = object : FcmRecoveryStorage {
        override fun read(): FcmRecoverySnapshot = atomic {
            val saved = bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode) ?: FcmRecoverySnapshot()
            val consumed = bytes(DaemonDomainRole.FCM_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
            val mcs = bytes(DaemonDomainRole.FCM_MCS_CONSUMED)?.let(FcmRecoveryStoreCodec::decode)
            saved.copy(configured = saved.configured && !present(DaemonDomainRole.FCM_RECOVERY_HALT),
                consumed = saved.consumed || consumed != null,
                consumedBoot = consumed?.consumedBoot ?: saved.consumedBoot,
                consumedAt = consumed?.consumedAt ?: saved.consumedAt,
                consumedElapsed = consumed?.consumedElapsed ?: saved.consumedElapsed,
                mcsConsumed = saved.mcsConsumed || mcs != null,
                mcsConsumedBoot = mcs?.mcsConsumedBoot ?: saved.mcsConsumedBoot,
                mcsConsumedAt = mcs?.mcsConsumedAt ?: saved.mcsConsumedAt,
                mcsConsumedElapsed = mcs?.mcsConsumedElapsed ?: saved.mcsConsumedElapsed)
        }
        override fun write(value: FcmRecoverySnapshot) = atomic {
            read(); root.put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(value))
        }
        override fun markConsumed(value: FcmRecoverySnapshot) = atomic {
            check(!present(DaemonDomainRole.FCM_CONSUMED))
            root.put(DaemonDomainRole.FCM_CONSUMED, FcmRecoveryStoreCodec.encode(value.copy(events = emptyList()), legacyMarker = true))
        }
        override fun clearConsumed() = remove(DaemonDomainRole.FCM_CONSUMED)
        override fun markMcsConsumed(value: FcmRecoverySnapshot) = atomic {
            check(!present(DaemonDomainRole.FCM_MCS_CONSUMED))
            root.put(DaemonDomainRole.FCM_MCS_CONSUMED, FcmRecoveryStoreCodec.encode(value.copy(events = emptyList())))
        }
        override fun clearMcsConsumed() = remove(DaemonDomainRole.FCM_MCS_CONSUMED)
        override fun halt() = marker(DaemonDomainRole.FCM_RECOVERY_HALT)
        override fun releaseHalt() = remove(DaemonDomainRole.FCM_RECOVERY_HALT)
    }

    val heartbeat: HeartbeatStorage = object : HeartbeatStorage {
        override fun <T> exclusive(action: () -> T): T = atomic(action)
        override fun read(): HeartbeatSnapshot? = atomic {
            val original = bytes(DaemonDomainRole.HEARTBEAT)
            if (original == null) { check(!present(DaemonDomainRole.HEARTBEAT_INITIALIZED)); null }
            else { initialized(DaemonDomainRole.HEARTBEAT_INITIALIZED); HeartbeatSnapshotCodec.decode(original) }
        }
        override fun write(value: HeartbeatSnapshot) = atomic {
            read(); marker(DaemonDomainRole.HEARTBEAT_INITIALIZED)
            root.put(DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(value))
        }
        override fun coldBarrier(): HeartbeatResumeBlock? = atomic {
            when {
                present(DaemonDomainRole.HEARTBEAT_HALT) -> HeartbeatResumeBlock.COLD_RECONSTRUCTION
                present(DaemonDomainRole.HEARTBEAT_PENDING) -> HeartbeatResumeBlock.LEFTOVER_PENDING
                else -> null
            }
        }
        override fun halt() = marker(DaemonDomainRole.HEARTBEAT_HALT)
        override fun pending(value: HeartbeatPending) = atomic {
            check(!present(DaemonDomainRole.HEARTBEAT_PENDING))
            root.put(DaemonDomainRole.HEARTBEAT_PENDING, HeartbeatPendingCodec.encode(value))
        }
        override fun clearPending(value: HeartbeatPending) = atomic {
            check(bytes(DaemonDomainRole.HEARTBEAT_PENDING)?.let(HeartbeatPendingCodec::decode) == value)
            remove(DaemonDomainRole.HEARTBEAT_PENDING)
        }
        override fun releaseForExplicitConsent() = atomic {
            check(!present(DaemonDomainRole.HEARTBEAT_PENDING)); remove(DaemonDomainRole.HEARTBEAT_HALT)
        }
        override fun unsettledPending() = present(DaemonDomainRole.HEARTBEAT_PENDING)
    }

    val fcmEpisodes: FcmRecoveryEpisodeStorage = object : FcmRecoveryEpisodeStorage {
        override fun read(): FcmRecoveryEpisodeSnapshot = atomic {
            bytes(DaemonDomainRole.FCM_EPISODES)?.let(FcmRecoveryEpisodeCodec::decode) ?: FcmRecoveryEpisodeSnapshot()
        }
        override fun write(value: FcmRecoveryEpisodeSnapshot) = root.put(DaemonDomainRole.FCM_EPISODES,
            FcmRecoveryEpisodeCodec.encode(value))
    }

    val selection: TargetSelectionPreferences = object : TargetSelectionPreferences {
        override fun read(): String = atomic {
            val original = bytes(DaemonDomainRole.SELECTION) ?: return@atomic ""
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString()
        }
        override fun write(value: String): Boolean {
            root.put(DaemonDomainRole.SELECTION, value.toByteArray(Charsets.UTF_8)); return true
        }
        override fun clear(): Boolean { remove(DaemonDomainRole.SELECTION); return true }
        // The daemon control actor explicitly invalidates/rechecks its loop after a committed
        // selection change. SharedPreferences' app-process listener is not an authority channel.
    }
}
