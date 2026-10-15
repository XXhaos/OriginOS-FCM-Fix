package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import org.junit.Assert.*
import org.junit.Test

class DaemonBootContinuationTest {
    private val oldBoot = "01234567-89ab-cdef-0123-456789abcdef"
    private val newBoot = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val oldCaller = GmsHeartbeatCallerIdentity(10436, 123, 100, oldBoot)
    private val freshCaller = oldCaller.copy(pid = 456, startTicks = 200, kernelBootId = newBoot)
    private val sample = GmsConnectionObservation.Sample(1, 10208, 263737035, 1_000, 2_000,
        FcmRecoveryPolicy.SIGNER, newBoot, 1_010_000, 10_000, SocketSource(SourceCompleteness.COMPLETE, true),
        SocketSource(SourceCompleteness.COMPLETE, false), true, emptyList(),
        GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1_010_000, 10_000, 0))
    private val parent = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample),
        FcmRecoveryPolicy.binding(sample.copy(bootId = oldBoot)), true)
    private val recovery = FcmRecoverySnapshot(true, parent.recoveryBinding, true, oldBoot, 900_000, 8_000,
        listOf(FcmRecoveryEvent(900_000, 8_000, FcmRecoveryEventAction.REQUEST_RESULT,
            status = FcmRecoveryStatus.REQUESTED, reason = FcmRecoveryReason.COMMAND_ACCEPTED)),
        powerSampleAtMillis = 900_000, mcsConsumed = true, mcsConsumedBoot = oldBoot,
        mcsConsumedAt = 900_001, mcsConsumedElapsed = 8_001)

    private fun baseline(permit: Boolean = false): DaemonProtectionRootSnapshot {
        val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null), schemaVersion = 3, masterEnabledAtMillis = 1_000)
        val source = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.FCM_CONSUMED to FcmRecoveryStoreCodec.encode(recovery.copy(events = emptyList()), legacyMarker = true),
            DaemonDomainRole.FCM_MCS_CONSUMED to FcmRecoveryStoreCodec.encode(recovery.copy(events = emptyList())),
            DaemonDomainRole.RECOVERY_COUNTS to byteArrayOf(9, 8, 7)))
        val base = DaemonProtectionRootSnapshot(source.control.copy(mode = DaemonControlMode.ACTIVE), source.domains())
        return if (!permit) base else changed(base, DaemonDomainRole.HEARTBEAT_DERIVATION,
            requireNotNull(DaemonHeartbeatDerivation.prepare(base, base.control, oldCaller)))
    }
    private fun changed(base: DaemonProtectionRootSnapshot, role: DaemonDomainRole, bytes: ByteArray?) =
        DaemonProtectionRootSnapshot(base.control, base.domains() + (role to bytes), base.receipts, base.halt, base.dataRevision)
    private fun rebind(base: DaemonProtectionRootSnapshot, observed: GmsConnectionObservation.Sample = sample) =
        DaemonHeartbeatDerivation.rebindBundledRecovery(base, base.control, observed, newBoot, 10_000, 10_000, 8_000)

    @Test fun bootRebindChangesOnlyTheTwoBindingsPreservingConsumptionConsentAndEveryOtherRole() {
        val before = baseline(permit = true)
        val after = requireNotNull(rebind(before))
        assertEquals(before.control, after.control); assertEquals(before.dataRevision + 1, after.dataRevision)
        assertEquals(before.receipts, after.receipts)
        assertEquals(parent.copy(recoveryBinding = FcmRecoveryPolicy.binding(sample)),
            FcmProtectionCodec.decode(after.bytes(DaemonDomainRole.FCM_PROTECTION)!!))
        assertEquals(recovery.copy(binding = FcmRecoveryPolicy.binding(sample)),
            FcmRecoveryStoreCodec.decode(after.bytes(DaemonDomainRole.FCM_RECOVERY)!!))
        for (role in DaemonDomainRole.entries - setOf(DaemonDomainRole.FCM_PROTECTION, DaemonDomainRole.FCM_RECOVERY))
            assertArrayEquals(role.name, before.bytes(role), after.bytes(role))
    }

    @Test fun staleDataOrControlCasCannotRebindAndIdempotenceDoesNotWriteAgain() {
        val before = baseline(); val (root, disk) = RootFixtures.root(before)
        assertFalse(root.rebindBundledRecovery(before.control, 2, sample, newBoot, 10_000, 10_000, 8_000))
        assertFalse(root.rebindBundledRecovery(before.control.copy(revision = 2), 1, sample, newBoot, 10_000, 10_000, 8_000))
        assertEquals(1, disk.writes)
        assertTrue(root.rebindBundledRecovery(before.control, 1, sample, newBoot, 10_000, 10_000, 8_000))
        assertEquals(2, disk.writes)
        assertTrue(root.rebindBundledRecovery(before.control, 2, sample, newBoot, 10_000, 10_000, 8_000))
        assertEquals(2, disk.writes)
    }

    @Test fun haltedPendingOffChangedInstallationOrMismatchedOldBindingNeverRebinds() {
        val base = baseline()
        for (role in listOf(DaemonDomainRole.CONTROL_PROPOSAL, DaemonDomainRole.AUTHORITY_STOP_PENDING,
            DaemonDomainRole.STOPPED_BLOCKED, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT,
            DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING))
            assertNull(role.name, rebind(changed(base, role, byteArrayOf(1))))
        assertNull(rebind(DaemonProtectionRootSnapshot(base.control.copy(mode = DaemonControlMode.OFF), base.domains())))
        assertNull(rebind(base, sample.copy(versionCode = sample.versionCode + 1)))
        assertNull(rebind(base, sample.copy(elapsedRealtimeMillis = 10_001)))
        assertNull(rebind(changed(base, DaemonDomainRole.FCM_RECOVERY,
            FcmRecoveryStoreCodec.encode(recovery.copy(binding = "c".repeat(64))))))
    }

    @Test fun freshRealCallerRefreshesOnlyExistingUnusedPermitAfterExactBootRebind() {
        val before = baseline(permit = true)
        assertNull(DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(before, before.control, freshCaller,
            sample, newBoot, 10_000, 10_000, 8_000))
        val rebound = requireNotNull(rebind(before))
        val after = requireNotNull(DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(rebound, rebound.control,
            freshCaller, sample, newBoot, 10_000, 10_000, 8_000))
        val oldPermit = DaemonHeartbeatDerivationPermitCodec.decode(rebound.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!)
        assertEquals(oldPermit.copy(historicalCaller = freshCaller, recoveryBinding = FcmRecoveryPolicy.binding(sample)),
            DaemonHeartbeatDerivationPermitCodec.decode(after.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!))
        for (role in DaemonDomainRole.entries - DaemonDomainRole.HEARTBEAT_DERIVATION)
            assertArrayEquals(role.name, rebound.bytes(role), after.bytes(role))
        assertNotNull(DaemonHeartbeatDerivation.materialize(after, after.control, sample, newBoot, 10_000, 10_000, 8_000))
        val used = changed(rebound, DaemonDomainRole.HEARTBEAT_DERIVATION,
            DaemonHeartbeatDerivationPermitCodec.encode(oldPermit.copy(used = true)))
        for (invalid in listOf(used, changed(rebound, DaemonDomainRole.HEARTBEAT_DERIVATION, null)))
            assertNull(DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(invalid, invalid.control,
                freshCaller, sample, newBoot, 10_000, 10_000, 8_000))
        assertNull(DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(rebound, rebound.control, oldCaller,
            sample, newBoot, 10_000, 10_000, 8_000))
        assertNull(DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(rebound, rebound.control,
            freshCaller.copy(uid = 10437), sample, newBoot, 10_000, 10_000, 8_000))
        val (root, disk) = RootFixtures.root(rebound)
        assertFalse(root.refreshUnusedHeartbeatPermit(rebound.control, rebound.dataRevision + 1,
            freshCaller, sample, newBoot, 10_000, 10_000, 8_000))
        assertFalse(root.refreshUnusedHeartbeatPermit(rebound.control, rebound.dataRevision,
            freshCaller, sample, newBoot, 0, 10_000, 8_000))
        assertEquals(1, disk.writes)
        assertTrue(root.refreshUnusedHeartbeatPermit(rebound.control, rebound.dataRevision,
            freshCaller, sample, newBoot, 10_000, 10_000, 8_000))
        assertEquals(2, disk.writes)
    }
}
