package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.autoprotection.*
import org.junit.Assert.*
import org.junit.Test

class DaemonMigrationValidationTest {
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val binding = "a".repeat(64)
    private val parent = FcmProtectionConsent(true, epoch, binding, "b".repeat(64), true)
    private val heartbeat = HeartbeatSnapshot(1, true, bindingDigest = binding,
        remoteSessionId = "c15b5c0f-3686-4689-8659-011ec7f2a03d", schemaVersion = 3,
        durableConsent = HeartbeatDurableConsent(3, epoch, 0, binding),
        callerIdentity = GmsHeartbeatCallerIdentity(10435, 42, 100, "01234567-89ab-cdef-0123-456789abcdef"))
    private fun snapshot(parent: FcmProtectionConsent? = this.parent,
        child: HeartbeatSnapshot? = heartbeat, extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()) =
        RootFixtures.snapshot(extras = mapOf(
            DaemonDomainRole.FCM_PROTECTION to parent?.let(FcmProtectionCodec::encode),
            DaemonDomainRole.HEARTBEAT to child?.let(HeartbeatSnapshotCodec::encode)) + extras)

    @Test fun rawActiveChildKeepsExactBytesAndItsOriginalBundledParent() {
        val root = snapshot()
        val raw = DaemonProtectionRootCodec.encode(root)
        validateDaemonMigrationHeartbeat(root)
        assertArrayEquals(raw, DaemonProtectionRootCodec.encode(root))
        assertEquals(heartbeat, HeartbeatSnapshotCodec.decode(root.bytes(DaemonDomainRole.HEARTBEAT)!!))
    }

    @Test fun wrongMissingOrUnpreparedParentCannotBecomeAColdMigrationCandidate() {
        listOf<FcmProtectionConsent?>(null, parent.copy(configured = false), parent.copy(recoveryPrepared = false),
            parent.copy(epoch = "23456789-1234-1234-1234-123456789abc"), parent.copy(installationBinding = "c".repeat(64)))
            .forEach { invalid -> assertThrows(Exception::class.java) { validateDaemonMigrationHeartbeat(snapshot(parent = invalid)) } }
        assertThrows(Exception::class.java) { validateDaemonMigrationHeartbeat(snapshot(child = heartbeat.copy(durableConsent = null))) }
    }

    @Test fun absentAndDisabledChildRemainUnchangedButHaltAndPendingAreNeverCleared() {
        validateDaemonMigrationHeartbeat(snapshot(child = null))
        validateDaemonMigrationHeartbeat(snapshot(child = heartbeat.copy(configured = false, durableConsent = null)))
        listOf(DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING,
            DaemonDomainRole.FCM_PROTECTION_HALT).forEach { role ->
            val root = snapshot(extras = mapOf(role to byteArrayOf(1)))
            assertThrows(Exception::class.java) { validateDaemonMigrationHeartbeat(root) }
            assertArrayEquals(byteArrayOf(1), root.bytes(role))
        }
    }

    @Test fun onlyExactSavedUserPauseCanCompleteMigrationAsOffWithoutInferringAnEnable() {
        val paused = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.USER_PAUSED, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000_100), emptyList(), null), schemaVersion = 3)
        fun root(value: ProtectionAuthoritySnapshot, extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()) =
            RootFixtures.snapshot(ProtectionAuthorityCodec.encode(value), extras)
        val original = root(paused)
        val bytes = DaemonProtectionRootCodec.encode(original)
        assertTrue(pausedDaemonMigrationRequiresStop(original))
        assertArrayEquals(bytes, DaemonProtectionRootCodec.encode(original))
        assertFalse(pausedDaemonMigrationRequiresStop(root(paused.copy(mode = ProtectionConsentMode.ACTIVE, masterEnabledAtMillis = 1_000))))
        listOf(DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.INTERRUPTED_ARTIFACTS).forEach { role ->
            assertThrows(Exception::class.java) { pausedDaemonMigrationRequiresStop(root(paused, mapOf(role to byteArrayOf(1)))) }
        }
    }
}
