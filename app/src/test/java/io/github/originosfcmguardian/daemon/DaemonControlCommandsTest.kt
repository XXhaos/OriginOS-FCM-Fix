package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import org.junit.Assert.*
import org.junit.Test

class DaemonControlCommandsTest {
    private val fingerprint = "vivo/device/product:16/BUILD/123:user/release-keys"
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val first = SelectedTargetIdentity("com.example.first", 10201, 100, listOf("a".repeat(64)))
    private val second = SelectedTargetIdentity("com.example.second", 10202, 200, listOf("b".repeat(64)))
    private val token1 = TargetConsent.Recipient(RecipientConsentToken("11111111-1111-1111-1111-111111111111", 2, 1_001_000, first, fingerprint))
    private val token2 = TargetConsent.Recipient(RecipientConsentToken("22222222-2222-2222-2222-222222222222", 3, 1_001_000, second, fingerprint))
    private val budget = BudgetSnapshot(BudgetClock(1, 1_000, 1_001_000), listOf(100), null)
    private val authority = ProtectionAuthoritySnapshot(5, ProtectionConsentMode.ACTIVE, null, null, budget,
        listOf(token1, token2), 3, 1_001_000)
    private val context = DaemonControlCommandContext(BudgetClock(1, 10_000, 1_010_000), fingerprint, 10_000, true, true)
    private val sample = GmsConnectionObservation.Sample(1, 10208, 263737035, 1_000, 2_000,
        FcmRecoveryPolicy.SIGNER, boot, 1_010_000, 10_000, SocketSource(SourceCompleteness.COMPLETE, true),
        SocketSource(SourceCompleteness.COMPLETE, false), true, emptyList(),
        GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1_010_000, 10_000, 0))
    private val historical = GmsHeartbeatCallerIdentity(10436, 123, 100, boot)
    private val parent = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample), FcmRecoveryPolicy.binding(sample), true)

    private fun snapshot(state: ProtectionAuthoritySnapshot = authority,
        extras: Map<DaemonDomainRole, ByteArray?> = emptyMap(), mode: DaemonControlMode = DaemonControlMode.ACTIVE): DaemonProtectionRootSnapshot =
        RootFixtures.snapshot(ProtectionAuthorityCodec.encode(state), mapOf(
            DaemonDomainRole.SELECTION to TargetSelectionCodec.encode(listOf(first, second)).toByteArray(),
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.STOPPED_JOURNAL to byteArrayOf(1, 2, 3),
            DaemonDomainRole.RECOVERY_COUNTS to byteArrayOf(4, 5, 6),
            DaemonDomainRole.FCM_EPISODES to byteArrayOf(7, 8, 9),
        ) + extras).let { DaemonProtectionRootSnapshot(it.control.copy(mode = mode), it.domains()) }

    private fun planner(validate: (SelectedTargetIdentity, Boolean) -> DaemonControlTargetValidation = { _, _ -> DaemonControlTargetValidation.MATCH }) =
        DaemonControlCommandPlanner(DaemonControlTargetValidationPort(validate))

    private fun planned(command: DaemonProtectionCommand, base: DaemonProtectionRootSnapshot = snapshot(),
        context: DaemonControlCommandContext = this.context) = requireNotNull(planner().plan(base, base.control, "command", command, context))

    private fun nextAuthority(plan: DaemonControlTransitionPlan, base: DaemonProtectionRootSnapshot) = ProtectionAuthorityCodec.decode(
        plan.patch()[DaemonDomainRole.AUTHORITY] ?: requireNotNull(base.bytes(DaemonDomainRole.AUTHORITY)))

    @Test fun explicitConfirmationExpectedRootAndReplayAreMandatoryBeforeAnyFreshRead() {
        var reads = 0
        val planner = planner { _, _ -> reads++; DaemonControlTargetValidation.MATCH }
        val base = snapshot()
        assertNull(planner.plan(base, base.control, "request", DaemonProtectionCommand.MasterOn, context.copy(explicitConfirmation = false)))
        assertNull(planner.plan(base, base.control.copy(revision = 2), "request", DaemonProtectionCommand.MasterOn, context))
        assertNull(planner.plan(base, base.control, "x".repeat(129), DaemonProtectionCommand.MasterOn, context))
        assertNull(planner.plan(base, base.control, "", DaemonProtectionCommand.MasterOn, context))
        val request = DaemonControlRequest("already-used", base.control.owner, 1, DaemonControlIntent.Prepare("migration", base.control.authorityDigest))
        val replay = DaemonProtectionRootSnapshot(base.control, base.domains(), listOf(DaemonControlReceipt(request, base.control)))
        assertNull(planner.plan(replay, replay.control, request.requestId, DaemonProtectionCommand.MasterOn, context))
        val exhausted = DaemonProtectionRootSnapshot(base.control.copy(revision = Long.MAX_VALUE), base.domains())
        assertNull(planner.plan(exhausted, exhausted.control, "request", DaemonProtectionCommand.MasterOn, context))
        assertEquals(0, reads)
    }

    @Test fun activationOnlyFinishesInertImportedActiveAuthorityWithoutCreatingConsent() {
        val base = snapshot(mode = DaemonControlMode.PREPARED)
        val plan = planned(DaemonProtectionCommand.Activate, base)
        assertEquals(DaemonControlTransition.ACTIVATE, plan.transition)
        assertTrue(plan.patch().isEmpty())
        assertEquals(base.control.authorityDigest, plan.next.authorityDigest)
        assertEquals(DaemonControlMode.ACTIVE, plan.next.mode)
        assertNull(planner().plan(base, base.control, "unconfirmed", DaemonProtectionCommand.Activate,
            context.copy(explicitConfirmation = false)))
        val paused = snapshot(authority.copy(mode = ProtectionConsentMode.USER_PAUSED), mode = DaemonControlMode.PREPARED)
        assertNull(planner().plan(paused, paused.control, "paused", DaemonProtectionCommand.Activate, context))
        val active = snapshot()
        assertNull(planner().plan(active, active.control, "active", DaemonProtectionCommand.Activate, context))
    }

    @Test fun masterOffRetainsExactChoicesTokensFcmAndBudgetWhileChangingOneAuthorityRoot() {
        val base = snapshot()
        val original = DaemonProtectionRootCodec.encode(base)
        val plan = planned(DaemonProtectionCommand.MasterOff, base)
        val next = nextAuthority(plan, base)
        assertEquals(ProtectionConsentMode.USER_PAUSED, next.mode)
        assertEquals(authority.consents(), next.consents())
        assertEquals(budget, next.budget)
        assertEquals(setOf(DaemonDomainRole.AUTHORITY), plan.patch().keys)
        assertEquals(DaemonControlMode.OFF, plan.next.mode)
        assertEquals(base.control.revision + 1, plan.next.revision)
        assertEquals(DaemonProtectionRootSnapshot.digest(ProtectionAuthorityCodec.encode(next)), plan.next.authorityDigest)
        assertArrayEquals(original, DaemonProtectionRootCodec.encode(base))
    }

    @Test fun masterOnInvalidatesOnlyBadIdentityAndRetainsUnrelatedTokenAndFcmConsent() {
        val base = snapshot(authority.copy(mode = ProtectionConsentMode.USER_PAUSED))
        val seen = mutableListOf<SelectedTargetIdentity>()
        val plan = requireNotNull(planner { identity, recipient ->
            assertTrue(recipient); seen += identity
            if (identity == first) DaemonControlTargetValidation.INVALID else DaemonControlTargetValidation.MATCH
        }.plan(base, base.control, "master-on", DaemonProtectionCommand.MasterOn, context))
        val next = nextAuthority(plan, base)
        assertEquals(listOf(first, second), seen)
        assertEquals(ProtectionConsentMode.ACTIVE, next.mode)
        assertEquals(listOf(token1.id), next.invalidatedConsentIds)
        assertEquals(listOf(token1, token2), next.consents())
        assertEquals(listOf(token2), next.runnableConsents())
        assertEquals(budget, next.budget)
        assertEquals(setOf(DaemonDomainRole.AUTHORITY), plan.patch().keys)
        assertEquals(context.clock.epoch, next.masterEnabledAtMillis)
        assertNull(planner { _, _ -> DaemonControlTargetValidation.UNAVAILABLE }
            .plan(base, base.control, "unknown", DaemonProtectionCommand.MasterOn, context))
    }

    @Test fun masterOnKeepsLegacyFirmwareScopeAndRecipientFirmwareFlexibility() {
        val telegram = SelectedTargetIdentity("org.telegram.messenger", 10203, 300, listOf("c".repeat(64)))
        val legacy = TargetConsent.Legacy(DurableConsentToken("33333333-3333-3333-3333-333333333333", 2,
            1_001_000, telegram, fingerprint, policyVersion = RESTART_CONSENT_POLICY_VERSION))
        val state = authority.copy(mode = ProtectionConsentMode.USER_PAUSED, targetConsents = listOf(token1, legacy))
        val base = snapshot(state, mapOf(DaemonDomainRole.SELECTION to TargetSelectionCodec.encode(listOf(first, telegram)).toByteArray()))
        val plan = planned(DaemonProtectionCommand.MasterOn, base, context.copy(fingerprint = "$fingerprint-new"))
        val next = nextAuthority(plan, base)
        assertEquals(listOf(legacy.id), next.invalidatedConsentIds)
        assertEquals(listOf(token1), next.runnableConsents())
    }

    @Test fun targetOnRequiresFreshEligibilityAndUpdatesOnlyItsExactSelectionAndToken() {
        val base = snapshot()
        val replacement = first.copy(firstInstallTime = 999)
        assertNull(planner { _, _ -> DaemonControlTargetValidation.INVALID }
            .plan(base, base.control, "invalid", DaemonProtectionCommand.TargetOn(replacement), context))
        val plan = planned(DaemonProtectionCommand.TargetOn(replacement), base)
        val next = nextAuthority(plan, base)
        assertEquals(ProtectionConsentMode.ACTIVE, next.mode)
        assertEquals(token2, next.consents().single { it.identity == second })
        assertNotEquals(token1.id, next.consents().single { it.identity == replacement }.id)
        assertEquals(budget, next.budget)
        val selected = TargetSelectionCodec.decode(String(requireNotNull(plan.patch()[DaemonDomainRole.SELECTION]))) as TargetSelectionDecodeResult.Valid
        assertEquals(listOf(replacement, second), selected.values)
        assertEquals(setOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.SELECTION), plan.patch().keys)
    }

    @Test fun targetOffPreservesMasterIntentAndRejectsStaleIdentityRemoval() {
        val base = snapshot()
        val plan = planned(DaemonProtectionCommand.TargetOff(first.packageName, first), base)
        val next = nextAuthority(plan, base)
        assertEquals(ProtectionConsentMode.ACTIVE, next.mode)
        assertEquals(DaemonControlMode.ACTIVE, plan.next.mode)
        assertEquals(listOf(token2), next.consents())
        assertEquals(budget, next.budget)
        val selected = TargetSelectionCodec.decode(String(requireNotNull(plan.patch()[DaemonDomainRole.SELECTION]))) as TargetSelectionDecodeResult.Valid
        assertEquals(listOf(second), selected.values)
        assertNull(planner().plan(base, base.control, "stale", DaemonProtectionCommand.TargetOff(first.packageName,
            first.copy(firstInstallTime = 998)), context))
        val paused = snapshot(authority.copy(mode = ProtectionConsentMode.USER_PAUSED), mode = DaemonControlMode.OFF)
        assertEquals(ProtectionConsentMode.USER_PAUSED, nextAuthority(planned(DaemonProtectionCommand.TargetOn(first), paused), paused).mode)
    }

    @Test fun fcmOnUsesOnePreparedParentEpochAndPreservesConsumptionAndAllHistory() {
        val event = FcmRecoveryEvent(1_001_000, 1_000, FcmRecoveryEventAction.OBSERVATION)
        val recovery = FcmRecoverySnapshot(false, "a".repeat(64), true, boot, 1_001_000, 1_000,
            listOf(event), 1_001_000, true, boot, 1_001_000, 1_000)
        val base = snapshot(extras = mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.FCM_CONSUMED to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.FCM_MCS_CONSUMED to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.FCM_PROTECTION_HALT to byteArrayOf(1), DaemonDomainRole.FCM_RECOVERY_HALT to byteArrayOf(1)))
        val original = DaemonProtectionRootCodec.encode(base)
        val plan = planned(DaemonProtectionCommand.FcmOn(sample, historical), base)
        val freshParent = FcmProtectionCodec.decode(requireNotNull(plan.patch()[DaemonDomainRole.FCM_PROTECTION]))
        val freshRecovery = FcmRecoveryStoreCodec.decode(requireNotNull(plan.patch()[DaemonDomainRole.FCM_RECOVERY]))
        assertTrue(freshParent.configured && freshParent.recoveryPrepared)
        assertNotEquals(parent.epoch, freshParent.epoch)
        assertEquals(FcmRecoveryPolicy.binding(sample), freshParent.recoveryBinding)
        assertEquals(freshParent.recoveryBinding, freshRecovery.binding)
        assertEquals(recovery, freshRecovery.copy(configured = recovery.configured, binding = recovery.binding))
        assertNull(plan.patch()[DaemonDomainRole.FCM_PROTECTION_HALT])
        assertNull(plan.patch()[DaemonDomainRole.FCM_RECOVERY_HALT])
        assertEquals(setOf(DaemonDomainRole.FCM_PROTECTION, DaemonDomainRole.FCM_PROTECTION_HALT,
            DaemonDomainRole.FCM_RECOVERY, DaemonDomainRole.FCM_RECOVERY_HALT), plan.patch().keys)
        assertArrayEquals(original, DaemonProtectionRootCodec.encode(base))
        assertEquals(base.control.authorityDigest, plan.next.authorityDigest)
    }

    @Test fun fcmOnCannotBypassPreparationFreshnessOrUncertainOldHeartbeat() {
        val base = snapshot()
        val command = DaemonProtectionCommand.FcmOn(sample, historical)
        assertNull(planner().plan(base, base.control, "no-ready", command, context.copy(fcmRecoveryReady = false)))
        assertNull(planner().plan(base, base.control, "old", command, context.copy(nowElapsed = 100_000)))
        val dirty = snapshot(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(
            HeartbeatSnapshot(1, false, pause = HeartbeatPause.OFF_PENDING)),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1)))
        assertNull(planner().plan(dirty, dirty.control, "dirty", command, context))
        val paused = snapshot(authority.copy(mode = ProtectionConsentMode.USER_PAUSED), mode = DaemonControlMode.OFF)
        assertNull(planner().plan(paused, paused.control, "master-off", command, context))
        assertNull(planner().plan(base, base.control, "wrong-boot", command.copy(historicalCaller = historical.copy(
            kernelBootId = "aaaaaaaa-89ab-cdef-0123-456789abcdef")), context))
    }

    @Test fun fcmOffRetainsPendingRuntimeEvidenceAndMasterAndAppChoices() {
        val heartbeat = HeartbeatSnapshot(8, true, pendingAttempt = epoch, bindingDigest = "b".repeat(64),
            remoteSessionId = epoch, schemaVersion = 3, durableConsent = HeartbeatDurableConsent(3, epoch, 0, "b".repeat(64)),
            callerIdentity = historical)
        val base = snapshot(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(heartbeat),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1)))
        val plan = planned(DaemonProtectionCommand.FcmOff, base)
        val off = HeartbeatSnapshotCodec.decode(requireNotNull(plan.patch()[DaemonDomainRole.HEARTBEAT]))
        assertEquals(heartbeat, off.copy(configured = true, durableConsent = heartbeat.durableConsent))
        assertFalse(off.configured)
        assertEquals(authority, nextAuthority(plan, base))
        assertEquals(DaemonControlMode.ACTIVE, plan.next.mode)
        assertFalse(plan.patch().containsKey(DaemonDomainRole.SELECTION))
        for (role in listOf(DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT))
            assertArrayEquals(byteArrayOf(1), plan.patch()[role])
    }

    @Test fun interruptedStorageAndCorruptSelectionNeverProduceARepairOrGrant() {
        for (role in listOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.AUTHORITY_STOP_PENDING,
            DaemonDomainRole.STOPPED_BLOCKED, DaemonDomainRole.CONTROL_PROPOSAL)) {
            val base = snapshot(extras = mapOf(role to byteArrayOf(1)))
            assertNull(planner().plan(base, base.control, "blocked", DaemonProtectionCommand.MasterOn, context))
        }
        val corrupt = snapshot(extras = mapOf(DaemonDomainRole.SELECTION to byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(planner().plan(corrupt, corrupt.control, "corrupt", DaemonProtectionCommand.TargetOn(first), context))
        val plan = planned(DaemonProtectionCommand.MasterOff, corrupt)
        assertFalse(plan.patch().containsKey(DaemonDomainRole.SELECTION))
    }
}
