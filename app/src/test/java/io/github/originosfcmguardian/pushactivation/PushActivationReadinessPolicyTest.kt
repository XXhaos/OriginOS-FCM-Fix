package io.github.originosfcmguardian.pushactivation

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.daemon.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class PushActivationReadinessPolicyTest {
    private val epoch = "00000000-0000-0000-0000-000000000001"
    private val clean = HeartbeatSnapshot(1, false, schemaVersion = 3)
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1000), emptyList(), null), schemaVersion = 3, masterEnabledAtMillis = 1000)

    private fun root(heartbeat: HeartbeatSnapshot = clean, extras: Map<DaemonDomainRole, ByteArray?> = emptyMap(),
        master: ProtectionConsentMode = ProtectionConsentMode.ACTIVE): DaemonProtectionRootSnapshot {
        val original = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority.copy(mode = master)), mapOf(
            DaemonDomainRole.SELECTION to TargetSelectionCodec.encode(emptyList()).toByteArray(),
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(FcmProtectionConsent()),
            DaemonDomainRole.FCM_PROTECTION_HALT to byteArrayOf(1),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot()),
            DaemonDomainRole.FCM_RECOVERY_HALT to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(heartbeat),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1),
        ) + extras)
        return DaemonProtectionRootSnapshot(original.control.copy(mode =
            if (master == ProtectionConsentMode.ACTIVE) DaemonControlMode.ACTIVE else DaemonControlMode.OFF), original.domains(), dataRevision = 7)
    }

    private fun project(root: DaemonProtectionRootSnapshot) = DaemonUiProjection.project(root,
        DaemonAppStatus(root.control, if (root.control.mode == DaemonControlMode.ACTIVE) DaemonOwnerStatus.ACTIVE else DaemonOwnerStatus.OFF,
            root.dataRevision, false, false, 100, 1000, 2000),
        1000, 3000, ZoneId.of("UTC"))

    private fun reason(root: DaemonProtectionRootSnapshot, ui: DaemonUiSnapshot = project(root)) =
        pushActivationBlockReason(ui.automatic, ui.fcm, ui.heartbeatUi, ui.recoveryUi, root)

    @Test fun canonicalOffHaltFromActualDaemonProjectionCanReachNewConfirmationWithoutCreatingAuthority() {
        val root = root()
        val before = DaemonProtectionRootCodec.encode(root)
        val ui = project(root)
        assertTrue(ui.hostReady); assertTrue(ui.automatic.enabled)
        assertFalse(ui.fcm.configured); assertEquals(FcmMaintenanceState.OFF, ui.fcm.maintenance)
        assertFalse(ui.heartbeatUi.configured); assertTrue(ui.heartbeatUi.resumeBlocked)
        assertNull(reason(root, ui)) // New explicit ON may ask the authoritative planner to validate.
        assertFalse(ui.fcm.configured); assertFalse(ui.heartbeatUi.active); assertFalse(ui.recoveryUi.configured)
        assertArrayEquals(before, DaemonProtectionRootCodec.encode(root))
        assertArrayEquals(byteArrayOf(1), root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
    }

    @Test fun offProjectionNeverHidesUnknownPendingOrRetainedEndpointState() {
        val unsafe = listOf(
            clean.copy(configured = true),
            clean.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.TIMED_OUT),
            clean.copy(resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER),
            clean.copy(pause = HeartbeatPause.UNKNOWN_RESULT),
            clean.copy(pause = HeartbeatPause.OFF_PENDING),
            clean.copy(remoteSessionId = epoch),
            clean.copy(pendingArm = epoch),
            clean.copy(remoteSessionId = epoch, bindingDigest = "a".repeat(64), pendingAttempt = epoch),
            clean.copy(generation = Long.MAX_VALUE),
        )
        unsafe.forEach { assertEquals(it.toString(), PushActivationReason.RECONCILIATION_REQUIRED, reason(root(it))) }
    }

    @Test fun malformedOrUnavailableRootAndInterruptedStorageCannotUseCleanOffException() {
        for (extras in listOf(
            mapOf(DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(2)),
            mapOf(DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(2)),
            mapOf(DaemonDomainRole.HEARTBEAT to byteArrayOf(1)),
            mapOf(DaemonDomainRole.HEARTBEAT_PENDING to byteArrayOf(1)),
            mapOf(DaemonDomainRole.AUTHORITY_INITIALIZED to byteArrayOf(2)),
            mapOf(DaemonDomainRole.STOPPED_JOURNAL to byteArrayOf(1)),
            mapOf(DaemonDomainRole.CONTROL_PROPOSAL to byteArrayOf(1)),
            mapOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS to byteArrayOf(1)),
            mapOf(DaemonDomainRole.FCM_PROTECTION_HALT to byteArrayOf(2)),
            mapOf(DaemonDomainRole.FCM_RECOVERY_HALT to byteArrayOf(2)),
        )) assertNotNull(reason(root(extras = extras)))
        val ui = project(root())
        assertEquals(PushActivationReason.RECONCILIATION_REQUIRED,
            pushActivationBlockReason(ui.automatic, ui.fcm, ui.heartbeatUi, ui.recoveryUi, null))
    }

    @Test fun pendingOrUnsettledUnknownRecoveryDoesNotBecomeANewConfirmationOpportunity() {
        val pending = FcmRecoveryEpisode(1, 123, 100, 1000, 1000, FcmRecoveryEpisodeKind.SERVICE_ABSENT,
            1000, 1000, requestAtMillis = 1000, requestElapsedMillis = 1000,
            requestCorrelationId = epoch, requestPending = true)
        val unknown = pending.copy(requestPending = false, requestStatus = FcmRecoveryStatus.UNKNOWN,
            reason = FcmRecoveryReason.TIMED_OUT)
        for (episode in listOf(pending, unknown)) {
            val marked = root(extras = mapOf(DaemonDomainRole.FCM_EPISODES to FcmRecoveryEpisodeCodec.encode(
                FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(episode)))))
            assertEquals(PushActivationReason.RECONCILIATION_REQUIRED, reason(marked))
        }
    }

    @Test fun explicitStopStorageAndReconciliationFencesOverrideCanonicalOffEligibility() {
        val root = root(); val ui = project(root)
        assertEquals(PushActivationReason.RECONCILIATION_REQUIRED,
            reason(root, ui.copy(automatic = ui.automatic.copy(waitingForReconciliation = true))))
        assertEquals(PushActivationReason.RECONCILIATION_REQUIRED,
            reason(root, ui.copy(heartbeatUi = ui.heartbeatUi.copy(stopPending = true))))
        assertEquals(PushActivationReason.STORAGE_UNAVAILABLE,
            reason(root, ui.copy(automatic = ui.automatic.copy(revocationSaveFailed = true))))
        assertEquals(PushActivationReason.STORAGE_UNAVAILABLE,
            reason(root, ui.copy(fcm = ui.fcm.copy(storageBlocked = true))))
        assertEquals(PushActivationReason.STORAGE_UNAVAILABLE,
            reason(root, ui.copy(recoveryUi = ui.recoveryUi.copy(storageBlocked = true))))
        assertEquals(PushActivationReason.RECONCILIATION_REQUIRED,
            reason(root, ui.copy(fcm = ui.fcm.copy(configured = true))))
    }

    @Test fun newExplicitOnStartsCleanPausedMasterBeforeWaitingForActualActiveHostToDispatchFcm() = runBlocking {
        for (paused in listOf(ProtectionConsentMode.USER_PAUSED, ProtectionConsentMode.DISABLED)) {
            var currentRoot = root(master = paused)
            var ui = project(currentRoot)
            var saved: PushActivationSnapshot? = null
            val store = PushActivationStore(object : PushActivationStorage {
                override fun read() = saved
                override fun write(value: PushActivationSnapshot) { saved = value }
                override fun <T> exclusive(action: () -> T) = action()
            })
            var masterStarts = 0; var fcmDispatches = 0
            val port = object : PushActivationPort {
                override fun installation() = PushActivationInstallation(10_123, 100, 102, 200, listOf("a".repeat(64)))
                override fun readiness(): PushActivationReadiness {
                    val block = reason(currentRoot, ui)
                    return PushActivationReadiness(currentRoot.control.mode,
                        masterOn = ui.automatic.masterEnabled, masterKnown = ui.automatic.configurationAvailable,
                        ready = ui.hostReady && ui.automatic.enabled && block == null,
                        configured = ui.hostReady && ui.fcm.configured && !ui.fcm.requiresConfirmation,
                        canStartMaster = !ui.automatic.masterEnabled && ui.automatic.configurationAvailable && block == null,
                        block = block)
                }
                override fun startMaster() { masterStarts++ }
                override suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch {
                    check(current() && ui.hostReady && ui.automatic.masterEnabled)
                    check(admit { fcmDispatches++ })
                    // Only this fake authoritative backend confirms FCM, never the admission policy.
                    ui = ui.copy(fcm = ui.fcm.copy(configured = true), heartbeatUi = ui.heartbeatUi.copy(resumeBlocked = false))
                    return PushActivationDispatch.CONFIGURED
                }
            }
            val lifetime = SupervisorJob()
            try {
                val controller = PushActivationController(store, port, CoroutineScope(lifetime + Dispatchers.Unconfined))
                assertFalse(ui.hostReady); assertFalse(ui.automatic.masterEnabled); assertTrue(ui.heartbeatUi.resumeBlocked)
                assertNull(reason(currentRoot, ui))
                assertTrue(controller.confirmExplicit())
                withTimeout(2_000) { while (masterStarts != 1) yield() }
                assertEquals(0, fcmDispatches); assertFalse(ui.fcm.configured)
                assertEquals(PushActivationMode.PENDING, saved?.mode)
                currentRoot = root(); ui = project(currentRoot)
                assertTrue(ui.hostReady); assertTrue(ui.automatic.masterEnabled)
                controller.changed()
                withTimeout(2_000) { while (saved?.mode != PushActivationMode.COMPLETE) yield() }
                assertEquals(1, fcmDispatches)
            } finally { lifetime.cancel() }
        }
    }
}
