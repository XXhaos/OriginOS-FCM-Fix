package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.privileged.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** The host is exercised through its authenticated pointer protocol and real FCM domain engine. */
class DaemonProtectionHostTest {
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val guardian = DaemonGuardianInstallation(SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME,
        10436, 100, listOf("b".repeat(64))), 79, 200)
    private val caller = DaemonCallerEvidence(10436, guardian)
    private val historicalCaller = GmsHeartbeatCallerIdentity(10436, 123, 100, boot)
    private val recipient = SelectedTargetIdentity("example.recipient", 10435, 100, listOf("a".repeat(64)))

    private class Child : Process() {
        @Volatile var running = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue(): Int { check(!running); return 0 }
        override fun isAlive() = running
        override fun destroy() = Unit // Deliberately models an actual worker that has not exited.
    }

    private inner class Fixture(emptyRoot: Boolean = false, activeHeartbeat: Boolean = false,
        configuredTarget: Boolean = false, existingRoot: DaemonProtectionRoot? = null,
        collectDiagnostics: Boolean = false) : AutoCloseable {
        val now = AtomicLong(10_000)
        var writes = 0
        var reads = 0
        var arms = 0
        var oldAppGone = false
        var revokeAcknowledged = true
        var freshHeartbeatEndpoint = false
        var beforeHeartbeatRevoke: (() -> Unit)? = null
        var connection = GcmConnectionState.SERVICE_ABSENT
        var beforeFcmRead: (() -> Unit)? = null
        var gmsVersion = 263737035L
        var gmsUpdatedAt = 2_000L
        var released = 0
        var releaseFailures = 0
        var stoppedCloseFailures = 0
        var stoppedCloses = 0
        var fcmCloses = 0
        var permission: Boolean? = true
        var principal: DaemonGuardianPackage? = DaemonGuardianPackage(guardian, true, true, false, true)
        var exits: List<DaemonGuardianExit>? = emptyList()
        var settle = true
        var idle = true
        var fence: PrivilegedExecutionFence? = null
        val child = Child()
        fun wall() = 1_000_000L + now.get()
        fun sample() = GmsConnectionObservation.Sample(1, 10208, gmsVersion, 1_000, gmsUpdatedAt,
            FcmRecoveryPolicy.SIGNER, boot, wall(), now.get(), SocketSource(SourceCompleteness.COMPLETE, false),
            SocketSource(SourceCompleteness.COMPLETE, connection == GcmConnectionState.REPORTED_CONNECTED), true, emptyList(),
            GcmServiceSource(connection, SourceCompleteness.COMPLETE, wall(), now.get(), 0))
        val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            // Same wall/elapsed offset as wall(): explicit master ON rightly rejects clock jumps.
            BudgetSnapshot(BudgetClock(1, 100, 1_000_100), emptyList(), null), schemaVersion = 3,
            masterEnabledAtMillis = 1_000, targetConsents = if (configuredTarget) listOf(TargetConsent.Recipient(
                RecipientConsentToken("abcdefab-1234-1234-1234-123456789abc", 1, 1_000, recipient,
                    VERIFIED_AUTOSTART_FINGERPRINT))) else emptyList())
        val parent = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample()),
            FcmRecoveryPolicy.binding(sample()), true)
        val initial = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding)),
        ).toMutableMap().apply {
            if (configuredTarget) put(DaemonDomainRole.SELECTION, TargetSelectionCodec.encode(listOf(recipient)).toByteArray(Charsets.UTF_8))
            if (activeHeartbeat) {
                put(DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(importedHeartbeat()))
                put(DaemonDomainRole.HEARTBEAT_INITIALIZED, byteArrayOf(1))
            }
        }).let { DaemonProtectionRootSnapshot(it.control.copy(mode = DaemonControlMode.ACTIVE), it.domains()) }
        val root = existingRoot ?: if (emptyRoot) DaemonProtectionRoot(MemoryDaemonRootStorage()) else RootFixtures.root(initial).first
        val stopped = object : DaemonStoppedShellPort {
            override fun observe(id: Long, identity: SelectedTargetIdentity, recipient: Boolean,
                interrupted: () -> Boolean): StoppedTargetObservation = error("No stopped targets configured")
            override fun capable(active: () -> Boolean) = false
            override fun startUnstop(identity: SelectedTargetIdentity): DaemonStartedUnstop = error("No stopped write")
            override fun close() { stoppedCloses++; if (stoppedCloseFailures-- > 0) error("Stopped close failed") }
        }
        val fcm = object : DaemonFcmShellPort {
            override fun read(id: Long, cancelled: () -> Boolean): GmsConnectionObservation {
                reads++
                beforeFcmRead?.also { beforeFcmRead = null }?.invoke()
                return sample().copy(requestId = id)
            }
            override fun usableNetwork() = true
            override fun disconnectedNetwork() = true
            override fun previousHeartbeatOwnerGone(value: HeartbeatSnapshot, cancelled: () -> Boolean) = oldAppGone && !cancelled()
            override fun recover(id: Long, uid: Int, binding: String, authorize: () -> Boolean,
                cancelled: () -> Boolean, operation: FcmRecoveryOperation, fence: PrivilegedExecutionFence): FcmRecoveryResult {
                this@Fixture.fence = fence
                assertTrue(authorize()); assertTrue(fence.prepare())
                assertNotNull(fence.start { writes++; child })
                if (settle) fence.workerSettled(PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED)
                return FcmRecoveryResult(id, if (settle) FcmRecoveryStatus.REQUESTED else FcmRecoveryStatus.UNKNOWN,
                    if (settle) FcmRecoveryReason.COMMAND_ACCEPTED else FcmRecoveryReason.TIMED_OUT, operation)
            }
            override fun arm(id: Long, uid: Int, generation: Long, binding: String, lifetime: GmsHeartbeatLifetime,
                cancelled: () -> Boolean): GmsAutoArmResult {
                assertTrue(lifetime.alive()); assertFalse(cancelled()); arms++
                return GmsAutoArmResult(GmsAutoSession("abcdefab-1234-1234-1234-123456789abc", generation, binding),
                    GmsAutoArmReason.ARMED)
            }
            override fun heartbeat(id: Long, uid: Int, session: GmsAutoSession, attempt: String,
                cancelled: () -> Boolean, fence: PrivilegedExecutionFence): GmsHeartbeatResult = error("No heartbeat")
            override fun revoke(uid: Int, session: GmsAutoSession): Boolean {
                beforeHeartbeatRevoke?.also { beforeHeartbeatRevoke = null }?.invoke()
                return revokeAcknowledged
            }
            override fun freshHeartbeatEndpoint() = this@Fixture.freshHeartbeatEndpoint
            override fun isQuiescent() = idle
            override fun close() { fcmCloses++ }
        }
        val diagnosticRuntime = if (collectDiagnostics) DaemonDiagnosticsRuntime(
            DaemonDiagnosticsSession("host-session", boot, 555, 123, 93, wall(), now.get(), wall(), now.get(), DaemonDiagnosticsState.BEGIN),
            open = { object : DaemonDiagnosticsStorage {
                override fun read(): DaemonDiagnosticsSnapshot? = null
                override fun write(value: DaemonDiagnosticsSnapshot) = Unit
                override fun close() = Unit
            } }, capture = { DaemonDiagnosticsSample("host-session", it.atMillis, it.elapsedMillis) },
            wall = ::wall, elapsed = now::get) else null
        val host = DaemonProtectionHost(initial.control.owner, guardian, boot, "migration", root,
            ownerLease = { start -> start(); true }, releaseLease = {
                if (releaseFailures-- > 0) error("Lease release failed")
                released++
            },
            environmentReadPort = object : DaemonOwnerEnvironmentReadPort {
                override fun environment() = DaemonOwnerEnvironment(2000, 0, boot, true)
                override fun guardianPackage() = principal
                override fun packagesForUid(uid: Int) = listOf(GUARDIAN_PACKAGE_NAME)
                override fun shizukuRuntimePermission(uid: Int) = permission
                override fun guardianExitHistory(uid: Int) = exits
                override fun wallMillis() = wall()
            }, stoppedShell = stopped, fcmShell = fcm, elapsed = now::get, wall = ::wall,
            processId = 555, fingerprint = { VERIFIED_AUTOSTART_FINGERPRINT },
            commandClock = { BudgetClock(1, now.get(), wall()) }, recoveryReady = { true }, startScheduler = false,
            diagnostics = diagnosticRuntime)
        fun reconcile() {
            val challenge = requireNotNull(host.currentPointerChallenge(caller))
            assertTrue(host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertTrue(host.reconcile(caller, DaemonReconcileProof(challenge.pointer, "reconcile")))
        }
        suspend fun poll() { now.addAndGet(30_001); host.pollOnce() }
        fun importedHeartbeat(pending: Boolean = false) = HeartbeatSnapshot(5, true, HeartbeatPause.WAITING_HOST,
            bindingDigest = parent.installationBinding, schemaVersion = 3,
            durableConsent = HeartbeatDurableConsent(3, epoch, 0, requireNotNull(parent.installationBinding)),
            callerIdentity = historicalCaller,
            pendingArm = "abcdefab-1234-1234-1234-123456789abc".takeIf { pending })
        fun importHeartbeat(pending: Boolean = false): Pair<String, String> {
            val domains = initial.domains().toMutableMap()
            val heartbeat = HeartbeatSnapshotCodec.encode(importedHeartbeat(pending))
            domains[DaemonDomainRole.HEARTBEAT] = heartbeat
            domains[DaemonDomainRole.HEARTBEAT_INITIALIZED] = byteArrayOf(1)
            val prepared = DaemonProtectionRootSnapshot(initial.control.copy(mode = DaemonControlMode.PREPARED), domains)
            val bytes = DaemonProtectionRootCodec.encode(prepared)
            val digest = DaemonProtectionRootSnapshot.digest(bytes)
            val id = host.beginImport(caller, bytes.size, digest)
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + DaemonImportProtocol.MAX_CHUNK_BYTES, bytes.size)
                offset = host.appendImport(caller, id, offset, bytes.copyOfRange(offset, end))
            }
            assertEquals(prepared.control, host.finishImport(caller, id, historicalCaller))
            return digest to DaemonProtectionRootSnapshot.digest(heartbeat)
        }
        fun activation(): DaemonPointerChallenge = requireNotNull(host.stage(caller, root.snapshot().control,
            "activate", DaemonProtectionCommand.Activate))
        fun applyActivation(challenge: DaemonPointerChallenge) {
            val request = requireNotNull(host.requestForChallenge(caller, challenge))
            assertTrue(host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertTrue(host.apply(caller, request) is DaemonControlReply.Acknowledged)
        }
        fun off(): DaemonControlReply {
            val before = root.snapshot().control
            val challenge = requireNotNull(host.stage(caller, before, "off", DaemonProtectionCommand.MasterOff))
            assertTrue(host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            return host.apply(caller, DaemonControlRequest("off", before.owner, before.revision,
                DaemonControlIntent.SetAuthority(challenge.pointer)))
        }
        override fun close() { child.running = false; idle = true; fence?.workerSettled(PrivilegedExecutionSettlement.UNKNOWN); host.close() }
    }

    @Test fun productionCycleMetricsMeasureAwaitedCallsAndPreserveReturnedStatuses() = runBlocking {
        Fixture(collectDiagnostics = true).use { f ->
            val runtime = requireNotNull(f.diagnosticRuntime)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (runtime.snapshot().sessions.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            // Before pointer reconciliation neither engine has been invoked.
            f.poll()
            assertEquals(0, runtime.snapshot().sessions.single().cycleMetrics!!.stopped.completedCount)
            f.reconcile()
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.beforeFcmRead = { f.now.addAndGet(125) }
            f.poll()
            val first = runtime.snapshot().sessions.single().cycleMetrics!!
            assertEquals(1, first.stopped.outcomes.waiting)
            assertEquals(1, first.fcm.outcomes.checked)
            assertEquals(125, first.fcm.totalElapsedMillis)
            assertEquals(125, first.fcm.maxElapsedMillis)
            // Calling again without advancing the fake monotonic clock returns actual throttles.
            f.host.pollOnce()
            val throttled = runtime.snapshot().sessions.single().cycleMetrics!!
            assertEquals(1, throttled.stopped.outcomes.throttled)
            assertEquals(1, throttled.fcm.outcomes.throttled)
            assertEquals(2, throttled.fcm.completedCount)
            assertEquals(125, throttled.fcm.totalElapsedMillis)
            // The engine contains the exception and returns UNAVAILABLE; no invented failure outcome.
            f.beforeFcmRead = { error("contained diagnostic engine read failure") }
            f.poll()
            val unavailable = runtime.snapshot().sessions.single().cycleMetrics!!
            assertEquals(3, unavailable.fcm.completedCount)
            assertEquals(1, unavailable.fcm.outcomes.unavailable)
            assertEquals(2, unavailable.stopped.outcomes.waiting)
            first.validate(); throttled.validate(); unavailable.validate()
        }
    }

    private fun unusedPermitRoot(f: Fixture, previousBoot: String, masterOff: Boolean = false): DaemonProtectionRoot {
        val authority = if (masterOff) f.authority.copy(mode = ProtectionConsentMode.USER_PAUSED) else f.authority
        val parent = f.parent.copy(recoveryBinding = FcmRecoveryPolicy.binding(f.sample().copy(bootId = previousBoot)))
        val original = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding))))
        val baseline = DaemonProtectionRootSnapshot(original.control.copy(mode =
            if (masterOff) DaemonControlMode.OFF else DaemonControlMode.ACTIVE), original.domains())
        val permit = DaemonHeartbeatDerivationPermit(baseline.control, epoch, requireNotNull(parent.installationBinding),
            requireNotNull(parent.recoveryBinding), DaemonHeartbeatDerivation.baselineDigest(baseline),
            historicalCaller.copy(kernelBootId = previousBoot))
        return RootFixtures.root(DaemonProtectionRootSnapshot(baseline.control, baseline.domains() +
            (DaemonDomainRole.HEARTBEAT_DERIVATION to DaemonHeartbeatDerivationPermitCodec.encode(permit)))).first
    }

    @Test fun freshReconciliationRebindsParentBeforeRefreshingExistingCrossBootUnusedPermit() {
        Fixture().use { template ->
            val previousBoot = "11234567-89ab-cdef-0123-456789abcdef"
            val root = unusedPermitRoot(template, previousBoot)
            Fixture(existingRoot = root).use { f ->
                f.connection = GcmConnectionState.REPORTED_CONNECTED
                val before = root.snapshot()
                val fresh = historicalCaller.copy(pid = 999, startTicks = 20_000)
                var qualifications = 0
                val challenge = requireNotNull(f.host.currentPointerChallenge(caller))
                assertTrue(f.host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
                assertTrue(f.host.reconcile(caller, DaemonReconcileProof(challenge.pointer, "fresh-caller")) {
                    qualifications++; fresh
                })
                val after = root.snapshot()
                assertEquals(before.control, after.control)
                assertEquals(before.dataRevision + 2, after.dataRevision)
                val parent = FcmProtectionCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.FCM_PROTECTION)))
                val permit = DaemonHeartbeatDerivationPermitCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)))
                assertEquals(FcmRecoveryPolicy.binding(f.sample()), parent.recoveryBinding)
                assertEquals(parent.recoveryBinding, permit.recoveryBinding)
                assertEquals(fresh, permit.historicalCaller)
                assertFalse(permit.used)
                assertNull(after.bytes(DaemonDomainRole.HEARTBEAT))
                assertEquals(1, qualifications)
                assertEquals(1, f.reads)
                assertEquals(0, f.writes)
                assertEquals(0, f.arms)
            }
        }
    }

    @Test fun failedUnusedPermitRefreshRemainsUnreconciledForAFreshBoundedRetry() {
        Fixture().use { template ->
            val root = unusedPermitRoot(template, "11234567-89ab-cdef-0123-456789abcdef")
            Fixture(existingRoot = root).use { f ->
                f.connection = GcmConnectionState.UNKNOWN
                val before = DaemonProtectionRootCodec.encode(root.snapshot())
                fun attempt(): Boolean {
                    val challenge = requireNotNull(f.host.currentPointerChallenge(caller))
                    assertTrue(f.host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
                    return f.host.reconcile(caller, DaemonReconcileProof(challenge.pointer, "retry")) { historicalCaller }
                }
                assertFalse(attempt())
                assertTrue(f.host.status(caller).needsReconciliation)
                assertArrayEquals(before, DaemonProtectionRootCodec.encode(root.snapshot()))
                f.connection = GcmConnectionState.REPORTED_CONNECTED
                assertTrue(attempt())
                assertFalse(f.host.status(caller).needsReconciliation)
            }
        }
    }

    @Test fun offOrAbsentUnusedPermitNeverSamplesGmsOrQualifiesAReplacementCaller() {
        Fixture().use { ordinary ->
            val challenge = requireNotNull(ordinary.host.currentPointerChallenge(caller))
            assertTrue(ordinary.host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertTrue(ordinary.host.reconcile(caller, DaemonReconcileProof(challenge.pointer, "ordinary")) {
                error("No unused permit")
            })
            assertEquals(0, ordinary.reads)
            val root = unusedPermitRoot(ordinary, "11234567-89ab-cdef-0123-456789abcdef", masterOff = true)
            Fixture(existingRoot = root).use { off ->
                val before = DaemonProtectionRootCodec.encode(root.snapshot())
                val pointer = requireNotNull(off.host.currentPointerChallenge(caller))
                assertTrue(off.host.attest(caller, DaemonAppPointerAttestation(pointer, pointer.pointer)))
                assertTrue(off.host.reconcile(caller, DaemonReconcileProof(pointer.pointer, "off")) { error("Master OFF") })
                assertEquals(0, off.reads)
                assertArrayEquals(before, DaemonProtectionRootCodec.encode(root.snapshot()))
            }
        }
    }

    @Test fun intactActiveRootCannotPollUntilFreshCurrentPointerReconciliation() = runBlocking {
        Fixture().use { f ->
            f.poll(); assertEquals(0, f.reads); assertEquals(0, f.writes)
            assertEquals(DaemonOwnerStatus.UNRECONCILED, f.host.status(caller).status)
            f.reconcile(); f.poll()
            assertEquals(1, f.writes)
            val header = f.host.beginSnapshot(caller)
            val first = f.host.snapshotChunk(caller, header.token, header.dataRevision, 0, minOf(header.size, 64 * 1024))
            assertTrue(first.isNotEmpty())
        }
    }

    @Test fun hostStatusPairsCurrentSampleWithDisplayReadinessAndQuiesceInvalidatesIt() = runBlocking {
        Fixture().use { f ->
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.reconcile(); f.poll()
            val status = f.host.status(caller)
            val sample = status.latestGmsObservation as GmsConnectionObservation.Sample
            val evidence = requireNotNull(status.recoveryReadiness)
            assertTrue(evidence.admitted)
            assertTrue(evidence.isFreshFor(requireNotNull(status.record), sample, status.sessionStartedElapsed, f.now.get()))
            assertNotNull(f.host.currentPointerChallenge(caller))
            assertNull(f.host.status(caller).recoveryReadiness)
            assertEquals(0, f.writes)
        }
    }

    @Test fun unavailableLifecycleCannotExposeOldTrueDisplayReadinessAfterPermissionReturns() = runBlocking {
        Fixture().use { f ->
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.reconcile(); f.poll()
            assertNotNull(f.host.status(caller).recoveryReadiness)
            f.permission = null
            f.poll()
            f.permission = true // Status can authenticate again, but the old observation is not a new cycle.
            assertNull(f.host.status(caller).recoveryReadiness)
            f.poll()
            assertNotNull(f.host.status(caller).recoveryReadiness)
        }
    }

    @Test fun ordinaryAppDisconnectPreservesIndependentActualRecovery() = runBlocking {
        Fixture().use { f ->
            f.reconcile(); f.host.appDisconnected(caller); f.poll()
            assertEquals(1, f.writes)
            assertEquals(DaemonControlMode.ACTIVE, f.root.snapshot().control.mode)
            assertEquals(0, f.released)
        }
    }

    @Test fun offAcknowledgesDuringUnsettledWriteAndRetainsLeaseUntilActualExit() = runBlocking {
        Fixture().use { f ->
            f.reconcile(); f.settle = false; f.child.running = true; f.idle = false
            // The initial read lane must be available; the returned write then remains unsettled.
            f.idle = true; f.poll(); f.idle = false
            assertEquals(1, f.writes)
            assertTrue(f.off() is DaemonControlReply.Acknowledged)
            assertEquals(DaemonControlMode.OFF, f.root.snapshot().control.mode)
            f.poll(); assertEquals(1, f.writes)
            f.host.close(); assertFalse(f.host.reapClosed()); assertEquals(0, f.released)
            f.fence!!.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
            assertFalse(f.host.reapClosed()); assertEquals(0, f.released)
            f.child.running = false; f.idle = true
            assertTrue(f.host.reapClosed()); assertEquals(1, f.released)
        }
    }

    @Test fun revokedRuntimeGrantClosesEvenBeforePreparedImportAndDoesNotChangeConsent() = runBlocking {
        Fixture(emptyRoot = true).use { f ->
            f.permission = false; f.poll()
            assertEquals(1, f.released); assertEquals(0, f.writes)
        }
        Fixture().use { f ->
            f.reconcile()
            val before = f.root.snapshot().bytes(DaemonDomainRole.AUTHORITY)
            f.permission = false; f.poll()
            assertEquals(1, f.released); assertEquals(0, f.writes)
            assertArrayEquals(before, f.root.snapshot().bytes(DaemonDomainRole.AUTHORITY))
            RootFixtures.fails { f.host.status(caller) }
        }
    }

    @Test fun failingBranchCloseStillClosesOtherBranchAndCanBeRetried() {
        Fixture().use { f ->
            f.stoppedCloseFailures = 1
            f.host.close()
            assertEquals(1, f.stoppedCloses)
            assertEquals(1, f.fcmCloses)
            assertFalse(f.host.isFullyReleased())
            assertEquals(0, f.released)
            f.host.close()
            assertEquals(2, f.stoppedCloses)
            assertEquals(1, f.fcmCloses)
            assertTrue(f.host.isFullyReleased())
            assertEquals(1, f.released)
        }
    }

    @Test fun failedLeaseReleaseIsNotReportedAsReleasedAndRetriesExactlyOnceToSuccess() {
        Fixture().use { f ->
            f.releaseFailures = 1
            f.host.close()
            assertFalse(f.host.isFullyReleased())
            assertEquals(0, f.released)
            assertTrue(f.host.reapClosed())
            assertTrue(f.host.isFullyReleased())
            assertEquals(1, f.released)
            assertTrue(f.host.reapClosed())
            assertEquals(1, f.released)
        }
    }

    @Test fun exactInitialImportArmCleanupNeedsActualOldGoneAndDrainWithoutGrantingReadiness() {
        val template = Fixture(activeHeartbeat = true)
        val prepared = DaemonProtectionRootSnapshot(template.initial.control.copy(mode = DaemonControlMode.PREPARED,
            migrationId = "7f231d03-499d-4dc7-863a-07599b9b38c4"),
            template.initial.domains())
        template.close()
        val raw = DaemonProtectionRootCodec.encode(prepared)
        val sha = DaemonProtectionRootSnapshot.digest(raw)
        val active = prepared.control.copy(revision = 2, mode = DaemonControlMode.ACTIVE)
        assertNotNull("Fixture must be an admissible canonical frozen import",
            DaemonInitialArmReconciliation.capture(active, raw, sha))
        val pointer = DaemonPointerCommitProof("initial-active", active)
        val request = DaemonControlRequest("activate", active.owner, 1, DaemonControlIntent.Activate(pointer))
        val original = HeartbeatSnapshotCodec.decode(requireNotNull(prepared.bytes(DaemonDomainRole.HEARTBEAT)))
        val operation = "abcdefab-1234-1234-1234-123456789abc"
        val failed = original.copy(generation = original.generation + 1, pause = HeartbeatPause.RECONFIRM,
            pendingArm = operation, resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER)
        val roles = prepared.domains().toMutableMap().apply {
            put(DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(failed))
            put(DaemonDomainRole.HEARTBEAT_HALT, byteArrayOf(1))
            put(DaemonDomainRole.HEARTBEAT_PENDING, HeartbeatPendingCodec.encode(HeartbeatPending(
                HeartbeatPendingKind.ARM, requireNotNull(original.durableConsent).epoch, failed.generation, operation)))
        }
        val pending = DaemonProtectionRootSnapshot(active, roles, listOf(DaemonControlReceipt(request, active)), dataRevision = 5)
        Fixture(existingRoot = RootFixtures.root(pending).first).use { f ->
            val challenge = requireNotNull(f.host.currentPointerChallenge(caller))
            assertTrue(f.host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertFalse(f.host.reconcile(caller, DaemonReconcileProof(challenge.pointer, "pending")))
            assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.host.status(caller).status)
            val before = DaemonProtectionRootCodec.encode(f.root.snapshot())
            assertFalse(f.host.reconcileInitialHeartbeatArm(caller, active, 5, raw, sha))
            assertArrayEquals(before, DaemonProtectionRootCodec.encode(f.root.snapshot()))
            f.oldAppGone = true
            f.idle = false
            assertFalse(f.host.reconcileInitialHeartbeatArm(caller, active, 5, raw, sha))
            f.idle = true
            assertTrue(f.host.reconcileInitialHeartbeatArm(caller, active, 5, raw, sha))
            val after = f.root.snapshot()
            assertEquals(active, after.control)
            assertEquals(6L, after.dataRevision)
            assertNull(after.bytes(DaemonDomainRole.HEARTBEAT_HALT))
            assertNull(after.bytes(DaemonDomainRole.HEARTBEAT_PENDING))
            assertEquals(original.copy(generation = failed.generation),
                HeartbeatSnapshotCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.HEARTBEAT))))
            assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.host.status(caller).status)
            assertEquals(0, f.reads)
            assertEquals(0, f.writes)
            assertEquals(0, f.arms)
            f.reconcile()
            assertEquals(DaemonOwnerStatus.ACTIVE, f.host.status(caller).status)
            assertFalse(f.host.reconcileInitialHeartbeatArm(caller, active, 6, raw, sha))
        }
    }

    @Test fun unknownPrivilegeWaitsWhileChangedInstallationClosesWithoutSilentConsentRewrite() = runBlocking {
        Fixture().use { f ->
            f.reconcile(); f.permission = null; f.poll()
            assertEquals(0, f.released); assertEquals(0, f.writes)
            f.permission = true; f.poll()
            assertEquals(1, f.writes)
            val before = f.root.snapshot().bytes(DaemonDomainRole.AUTHORITY)
            f.principal = f.principal!!.copy(installation = guardian.copy(versionCode = guardian.versionCode + 1))
            f.poll()
            assertEquals(1, f.released)
            assertArrayEquals(before, f.root.snapshot().bytes(DaemonDomainRole.AUTHORITY))
        }
    }

    @Test fun removeTaskKeepsActualOwnerButHistoricalStopBlocksUntilExplicitNewMasterOn() = runBlocking {
        Fixture().use { f ->
            f.reconcile()
            f.exits = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 1_001_000,
                DaemonRequestedExitDetail.REMOVE_TASK))
            f.poll(); assertEquals(1, f.writes); assertEquals(0, f.released)
            f.exits = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 1_001_001,
                DaemonRequestedExitDetail.EXPLICIT_STOP))
            f.poll(); assertEquals(1, f.writes); assertEquals(0, f.released)
            assertTrue(f.host.status(caller).needsReconciliation)
            assertNull(f.host.status(caller).sessionStartedElapsed)
            f.reconcile(); f.poll() // Pointer reconciliation cannot supersede a user stop.
            assertEquals(1, f.writes); assertNull(f.host.status(caller).sessionStartedElapsed)
            val old = f.root.snapshot().control
            val confirmedAt = f.wall()
            val challenge = requireNotNull(f.host.stage(caller, old, "explicit-new-on", DaemonProtectionCommand.MasterOn))
            assertTrue(f.host.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertTrue(f.host.apply(caller, requireNotNull(f.host.requestForChallenge(caller, challenge))) is DaemonControlReply.Acknowledged)
            assertEquals(confirmedAt, ProtectionAuthorityCodec.decode(f.root.snapshot().bytes(DaemonDomainRole.AUTHORITY)!!).masterEnabledAtMillis)
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertNotNull(f.host.status(caller).sessionStartedElapsed)
            assertFalse(f.host.status(caller).needsReconciliation)
        }
    }

    @Test fun rejectedUnstagedCommandExposesAdmissionClosureAndFreshPointerRestoresIt() = runBlocking {
        Fixture().use { f ->
            f.reconcile()
            val target = SelectedTargetIdentity("test.package", 10400, 100, listOf("a".repeat(64)))
            assertNull(f.host.stage(caller, f.root.snapshot().control, "declined", DaemonProtectionCommand.TargetOn(target)))
            assertEquals(DaemonOwnerStatus.ACTIVE, f.host.status(caller).status)
            assertTrue(f.host.status(caller).needsReconciliation)
            assertNull(f.root.snapshot().bytes(DaemonDomainRole.CONTROL_PROPOSAL))
            f.reconcile()
            assertFalse(f.host.status(caller).needsReconciliation)
            f.poll(); assertEquals(1, f.writes)
        }
    }

    @Test fun permissionRevocationKeepsGlobalLeaseUntilUncertainChildActuallyExits() = runBlocking {
        Fixture().use { f ->
            f.reconcile(); f.settle = false; f.child.running = true
            f.poll(); assertEquals(1, f.writes)
            f.idle = false; f.permission = false; f.poll()
            assertEquals(0, f.released)
            f.fence!!.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
            assertFalse(f.host.reapClosed())
            f.child.running = false; f.idle = true
            assertTrue(f.host.reapClosed()); assertEquals(1, f.released)
        }
    }

    @Test fun currentActivateNonceAcceptsExactTrustedImportDrainAndArmsWhileOldAppStillExists() = runBlocking {
        Fixture(emptyRoot = true).use { f ->
            val (rawDigest, heartbeatDigest) = f.importHeartbeat()
            val challenge = f.activation()
            assertTrue(f.host.attestMigrationDrain(caller, challenge, rawDigest, heartbeatDigest, historicalCaller))
            assertFalse(f.host.attestMigrationDrain(caller, challenge, rawDigest, heartbeatDigest, historicalCaller))
            f.applyActivation(challenge)
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertFalse(f.oldAppGone); assertEquals(1, f.arms); assertEquals(0, f.writes)
        }
    }

    @Test fun restartedPreparedHostReverifiesFrozenUploadWithoutChangingExistingActivateProposal() {
        Fixture(emptyRoot = true).use { first ->
            val (digest, heartbeatDigest) = first.importHeartbeat()
            val frozen = DaemonProtectionRootCodec.encode(first.root.snapshot())
            val challenge = first.activation()
            val staged = DaemonProtectionRootCodec.encode(first.root.snapshot())
            first.host.close()
            Fixture(existingRoot = first.root).use { restarted ->
                val session = restarted.host.beginImport(caller, frozen.size, digest)
                assertEquals(session, restarted.host.beginImport(caller, frozen.size, digest))
                assertEquals(0, restarted.host.importOffset(caller, session))
                var offset = 0
                while (offset < frozen.size) {
                    val end = minOf(offset + DaemonImportProtocol.MAX_CHUNK_BYTES, frozen.size)
                    offset = restarted.host.appendImport(caller, session, offset, frozen.copyOfRange(offset, end))
                }
                assertEquals(frozen.size, restarted.host.importOffset(caller, session))
                assertEquals(first.root.snapshot().control, restarted.host.finishImport(caller, session, historicalCaller))
                assertArrayEquals(staged, DaemonProtectionRootCodec.encode(first.root.snapshot()))
                assertEquals(first.root.snapshot().control, restarted.host.finishImport(caller, session, historicalCaller))
                assertArrayEquals(staged, DaemonProtectionRootCodec.encode(first.root.snapshot()))
                val resumed = requireNotNull(restarted.host.resumeStaged(caller, challenge.pointer.record))
                assertTrue(restarted.host.attestMigrationDrain(caller, resumed, digest, heartbeatDigest, historicalCaller))
                assertEquals(0, restarted.writes)
            }
        }
    }

    @Test fun activeRootCannotStartAReplacementImport() {
        Fixture().use { f ->
            val bytes = DaemonProtectionRootCodec.encode(f.initial)
            RootFixtures.fails { f.host.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)) }
        }
    }

    @Test fun preparedMasterOffAtomicallyReplacesActivateAndBurnsOldPointerAndDrainProofs() {
        Fixture(emptyRoot = true, configuredTarget = true).use { f ->
            val (digest, heartbeatDigest) = f.importHeartbeat()
            val activate = f.activation()
            val oldRequest = requireNotNull(f.host.requestForChallenge(caller, activate))
            assertTrue(f.host.attest(caller, DaemonAppPointerAttestation(activate, activate.pointer)))
            assertTrue(f.host.attestMigrationDrain(caller, activate, digest, heartbeatDigest, historicalCaller))
            val before = f.root.snapshot()
            val stop = requireNotNull(f.host.stage(caller, before.control, "migration-stop", DaemonProtectionCommand.MasterOff))
            val after = f.root.snapshot()
            assertEquals(before.dataRevision + 1, after.dataRevision)
            assertEquals(before.control, after.control)
            for (role in DaemonDomainRole.entries.filter { it != DaemonDomainRole.CONTROL_PROPOSAL })
                assertArrayEquals(role.name, before.bytes(role), after.bytes(role))
            val plan = DaemonControlProposalCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.CONTROL_PROPOSAL)))
            assertEquals("migration-stop", plan.requestId)
            assertEquals(DaemonControlMode.OFF, plan.next.mode)
            assertEquals(DaemonControlTransition.SET_AUTHORITY, plan.transition)
            assertNull(plan.nativeHeartbeatDerivation())
            assertFalse(f.host.attest(caller, DaemonAppPointerAttestation(activate, activate.pointer)))
            assertFalse(f.host.attestMigrationDrain(caller, activate, digest, heartbeatDigest, historicalCaller))
            assertTrue(f.host.apply(caller, oldRequest) is DaemonControlReply.Rejected)
            val retry = requireNotNull(f.host.stage(caller, after.control, "migration-stop", DaemonProtectionCommand.MasterOff))
            assertNotEquals(stop.nonce, retry.nonce)
            assertEquals(after.dataRevision, f.root.snapshot().dataRevision)
            f.applyActivation(retry)
            val paused = f.root.snapshot()
            assertEquals(DaemonControlMode.OFF, paused.control.mode)
            val authority = ProtectionAuthorityCodec.decode(requireNotNull(paused.bytes(DaemonDomainRole.AUTHORITY)))
            assertEquals(ProtectionConsentMode.USER_PAUSED, authority.mode)
            assertEquals(listOf(recipient), authority.consents().map { it.identity })
            assertNull(paused.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))
            assertEquals(0, f.writes)
            assertEquals(0, f.arms)
        }
    }

    @Test fun restartedPreparedStopResumesSameTypedPlanAndRejectsDifferentStopId() {
        Fixture(emptyRoot = true).use { first ->
            first.importHeartbeat()
            first.activation()
            requireNotNull(first.host.stage(caller, first.root.snapshot().control, "migration-stop", DaemonProtectionCommand.MasterOff))
            val stopped = DaemonProtectionRootCodec.encode(first.root.snapshot())
            first.host.close()
            Fixture(existingRoot = first.root).use { restarted ->
                assertNull(restarted.host.stage(caller, first.root.snapshot().control, "other-stop", DaemonProtectionCommand.MasterOff))
                assertArrayEquals(stopped, DaemonProtectionRootCodec.encode(first.root.snapshot()))
                val retry = requireNotNull(restarted.host.stage(caller, first.root.snapshot().control,
                    "migration-stop", DaemonProtectionCommand.MasterOff))
                assertArrayEquals(stopped, DaemonProtectionRootCodec.encode(first.root.snapshot()))
                restarted.applyActivation(retry)
                assertEquals(DaemonControlMode.OFF, first.root.snapshot().control.mode)
            }
        }
    }

    @Test fun preparedSupersessionRejectsNonActivateProposalAndNativeOwnerMarkers() {
        Fixture(emptyRoot = true).use { f ->
            f.importHeartbeat()
            val stop = requireNotNull(f.host.stage(caller, f.root.snapshot().control, "first-stop", DaemonProtectionCommand.MasterOff))
            val saved = DaemonProtectionRootCodec.encode(f.root.snapshot())
            assertNull(f.host.stage(caller, f.root.snapshot().control, "different-stop", DaemonProtectionCommand.MasterOff))
            assertNull(f.host.stage(caller, f.root.snapshot().control, "target-off", DaemonProtectionCommand.TargetOff(recipient.packageName)))
            assertNull(f.host.stage(caller, f.root.snapshot().control, "fcm-off", DaemonProtectionCommand.FcmOff))
            assertArrayEquals(saved, DaemonProtectionRootCodec.encode(f.root.snapshot()))
            assertNotNull(f.host.requestForChallenge(caller, stop))
        }
        for (role in listOf(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, DaemonDomainRole.HEARTBEAT_DERIVATION)) {
            Fixture(emptyRoot = true).use { f ->
                f.importHeartbeat()
                f.activation()
                val before = f.root.snapshot()
                // Model an already present invalid native marker. The live root correctly forbids
                // injecting derivation authority through an ordinary domain write.
                val marked = RootFixtures.root(DaemonProtectionRootSnapshot(before.control,
                    before.domains() + (role to byteArrayOf(1)), dataRevision = before.dataRevision)).first
                Fixture(existingRoot = marked).use { reopened ->
                    val saved = DaemonProtectionRootCodec.encode(marked.snapshot())
                    assertNull(reopened.host.stage(caller, marked.snapshot().control, "migration-stop", DaemonProtectionCommand.MasterOff))
                    assertArrayEquals(saved, DaemonProtectionRootCodec.encode(marked.snapshot()))
                }
            }
        }
    }

    @Test fun importDrainRejectsUnissuedNonceMismatchedDigestsCallerAndPendingHeartbeat() {
        Fixture(emptyRoot = true).use { f ->
            val (rawDigest, heartbeatDigest) = f.importHeartbeat()
            val challenge = f.activation()
            val forged = challenge.copy(nonce = "unissued", pointer = challenge.pointer.copy(commitId = "unissued"))
            assertFalse(f.host.attestMigrationDrain(caller, forged, rawDigest, heartbeatDigest, historicalCaller))
            assertFalse(f.host.attestMigrationDrain(caller, challenge, "a".repeat(64), heartbeatDigest, historicalCaller))
            assertFalse(f.host.attestMigrationDrain(caller, challenge, rawDigest, "a".repeat(64), historicalCaller))
            assertFalse(f.host.attestMigrationDrain(caller, challenge, rawDigest, heartbeatDigest, historicalCaller.copy(pid = 124)))
            assertTrue(f.host.attestMigrationDrain(caller, challenge, rawDigest, heartbeatDigest, historicalCaller))
        }
        Fixture(emptyRoot = true).use { f ->
            val (rawDigest, heartbeatDigest) = f.importHeartbeat(pending = true)
            val challenge = f.activation()
            assertFalse(f.host.attestMigrationDrain(caller, challenge, rawDigest, heartbeatDigest, historicalCaller))
        }
    }

    @Test fun coldOwnerUsesRealOldAppGoneEvidenceWithoutReconstructingAnImportAttestation() = runBlocking {
        // This new host opens an existing ACTIVE root and has never received an import upload.
        Fixture(activeHeartbeat = true).use { f ->
            f.reconcile()
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.poll(); assertEquals(0, f.arms)
            f.oldAppGone = true
            f.poll(); assertEquals(1, f.arms)
        }
    }

    @Test fun drainedMigrationClosureCarriesThroughImmediateTargetAndMasterEditsBeforeFirstPoll() = runBlocking {
        Fixture(emptyRoot = true, configuredTarget = true).use { f ->
            val (rawDigest, heartbeatDigest) = f.importHeartbeat()
            val activation = f.activation()
            assertTrue(f.host.attestMigrationDrain(caller, activation, rawDigest, heartbeatDigest, historicalCaller))
            f.applyActivation(activation)
            listOf(DaemonProtectionCommand.TargetOff(recipient.packageName, recipient),
                DaemonProtectionCommand.MasterOff, DaemonProtectionCommand.MasterOn).forEachIndexed { index, command ->
                val challenge = requireNotNull(f.host.stage(caller, f.root.snapshot().control, "edit-$index", command)) {
                    "Expected staging after a consistent fixture clock for $command"
                }
                f.applyActivation(challenge)
            }
            assertEquals(0, f.arms)
            assertFalse(f.oldAppGone)
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(1, f.arms)
            assertEquals(0, f.writes)
            assertEquals(f.root.snapshot().control, DaemonHeartbeatOwnerCodec.decode(
                requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER))).record)
        }
    }

    @Test fun busyFcmOffAcknowledgesThenLateActualDrainAllowsNewFcmOn() = runBlocking {
        Fixture(emptyRoot = true).use { f ->
            val (rawDigest, heartbeatDigest) = f.importHeartbeat()
            val activation = f.activation()
            assertTrue(f.host.attestMigrationDrain(caller, activation, rawDigest, heartbeatDigest, historicalCaller))
            f.applyActivation(activation)
            f.connection = GcmConnectionState.REPORTED_CONNECTED
            f.poll(); assertEquals(1, f.arms)
            var acknowledgedWhileBusy = false
            f.beforeFcmRead = {
                // The read's Future has returned control while its actual worker remains occupied.
                f.idle = false
                val off = requireNotNull(f.host.stage(caller, f.root.snapshot().control, "fcm-off", DaemonProtectionCommand.FcmOff))
                f.applyActivation(off)
                acknowledgedWhileBusy = true
                val saved = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
                assertFalse(saved.configured); assertNotNull(saved.remoteSessionId)
            }
            f.poll()
            assertTrue(acknowledgedWhileBusy)
            assertNull(f.host.stageFcmOn(caller, f.root.snapshot().control, "on-before-drain", historicalCaller))
            val retained = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
            assertFalse(retained.configured); assertNotNull(retained.remoteSessionId)
            f.idle = true
            val on = requireNotNull(f.host.stageFcmOn(caller, f.root.snapshot().control, "on-after-drain", historicalCaller))
            val clean = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
            assertFalse(clean.configured); assertNull(clean.remoteSessionId); assertNull(clean.pause)
            assertTrue(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT)?.contentEquals(byteArrayOf(1)) == true)
            f.applyActivation(on)
            f.poll()
            assertEquals(2, f.arms); assertEquals(0, f.writes)
            assertNotEquals(epoch, FcmProtectionCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.FCM_PROTECTION))).epoch)
        }
    }

    private fun settledRevocationRoot(f: Fixture, recoveryBoot: String = boot): DaemonProtectionRoot {
        val heartbeat = f.importedHeartbeat().copy(pause = null, remoteSessionId = epoch,
            lastStatus = GmsHeartbeatStatus.NOT_SENT, lastReason = GmsHeartbeatReason.SESSION_REVOKED,
            lastResultEpoch = epoch, resumeBlock = HeartbeatResumeBlock.SECURITY_FAILURE,
            callerIdentity = historicalCaller.copy(kernelBootId = recoveryBoot))
        val parent = f.parent.copy(recoveryBinding = FcmRecoveryPolicy.binding(f.sample().copy(bootId = recoveryBoot)))
        return RootFixtures.root(DaemonProtectionRootSnapshot(f.initial.control, f.initial.domains() + mapOf(
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding)),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(heartbeat),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1)))).first
    }

    @Test fun explicitConfirmationRepairsKnownUnsentSessionAfterOriginalRevokeAndReadback() = runBlocking {
        Fixture().use { template ->
            Fixture(existingRoot = settledRevocationRoot(template)).use { f ->
                f.reconcile()
                val before = f.root.snapshot()
                f.revokeAcknowledged = false
                assertNull(f.host.stageFcmOn(caller, before.control, "no-ack", historicalCaller))
                assertArrayEquals(before.bytes(DaemonDomainRole.HEARTBEAT), f.root.bytes(DaemonDomainRole.HEARTBEAT))
                assertNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
                f.revokeAcknowledged = true
                f.idle = false
                assertNull(f.host.stageFcmOn(caller, before.control, "not-drained", historicalCaller))
                f.idle = true
                val challenge = requireNotNull(f.host.stageFcmOn(caller, before.control, "confirmed", historicalCaller))
                // Staging changes no saved consent, failure evidence or execution state.
                assertArrayEquals(before.bytes(DaemonDomainRole.HEARTBEAT), f.root.bytes(DaemonDomainRole.HEARTBEAT))
                f.applyActivation(challenge)
                assertNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
                val saved = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
                assertFalse(saved.configured); assertNull(saved.remoteSessionId); assertNull(saved.resumeBlock)
                assertEquals(GmsHeartbeatStatus.NOT_SENT, saved.lastStatus)
                assertEquals(GmsHeartbeatReason.SESSION_REVOKED, saved.lastReason)
                assertArrayEquals(before.bytes(DaemonDomainRole.AUTHORITY), f.root.bytes(DaemonDomainRole.AUTHORITY))
                f.connection = GcmConnectionState.REPORTED_CONNECTED
                f.poll()
                assertEquals(1, f.arms)
                assertNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER))
                assertNotEquals(epoch, FcmProtectionCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.FCM_PROTECTION))).epoch)
            }
        }
    }

    @Test fun coldConfirmationRequiresBothNeverArmedNativeEndpointAndOldAppGone() = runBlocking {
        Fixture().use { template ->
            Fixture(existingRoot = settledRevocationRoot(template)).use { f ->
                f.reconcile(); f.revokeAcknowledged = false
                val before = f.root.snapshot()
                f.oldAppGone = true
                assertNull(f.host.stageFcmOn(caller, before.control, "native-still-possible", historicalCaller))
                f.freshHeartbeatEndpoint = true; f.oldAppGone = false
                assertNull(f.host.stageFcmOn(caller, before.control, "old-app-still-possible", historicalCaller))
                f.oldAppGone = true
                val challenge = requireNotNull(f.host.stageFcmOn(caller, before.control, "cold-confirmed", historicalCaller))
                f.applyActivation(challenge)
                f.connection = GcmConnectionState.REPORTED_CONNECTED; f.poll()
                assertEquals(1, f.arms)
            }
            Fixture(existingRoot = settledRevocationRoot(template)).use { f ->
                f.reconcile(); assertTrue(f.off() is DaemonControlReply.Acknowledged)
                f.oldAppGone = true; f.freshHeartbeatEndpoint = true
                assertNull(f.host.stageFcmOn(caller, f.root.snapshot().control, "master-stays-off", historicalCaller))
                assertEquals(DaemonControlMode.OFF, f.root.snapshot().control.mode)
            }
        }
    }

    @Test fun normalDaemonPollRenewsSettledSessionWithoutNewConsentOrAppConfirmation() = runBlocking {
        Fixture().use { template ->
            for (cold in listOf(false, true)) Fixture(existingRoot = settledRevocationRoot(template)).use { f ->
                f.reconcile()
                f.connection = GcmConnectionState.REPORTED_CONNECTED
                f.revokeAcknowledged = !cold; f.freshHeartbeatEndpoint = cold; f.oldAppGone = cold
                val before = f.root.snapshot()
                val oldChild = HeartbeatSnapshotCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.HEARTBEAT)))
                f.poll()
                assertEquals(1, f.arms)
                assertEquals(before.control, f.root.snapshot().control)
                assertArrayEquals(before.bytes(DaemonDomainRole.FCM_PROTECTION), f.root.bytes(DaemonDomainRole.FCM_PROTECTION))
                assertArrayEquals(before.bytes(DaemonDomainRole.AUTHORITY), f.root.bytes(DaemonDomainRole.AUTHORITY))
                assertArrayEquals(before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION), f.root.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))
                val resumed = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
                assertEquals(oldChild.durableConsent, resumed.durableConsent)
                assertEquals(oldChild.generation + 1, resumed.generation)
                assertEquals(oldChild.lastStatus, resumed.lastStatus)
                assertEquals(oldChild.lastReason, resumed.lastReason)
                assertEquals(oldChild.lastResultEpoch, resumed.lastResultEpoch)
                assertNull(resumed.resumeBlock); assertNull(resumed.pause); assertNotNull(resumed.remoteSessionId)
                assertNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
                f.poll(); assertEquals(1, f.arms)
            }
        }
    }

    @Test fun automaticRenewalKeepsBlockWithoutClosureAndOffWinsDuringClosureProbe() = runBlocking {
        Fixture().use { template ->
            Fixture(existingRoot = settledRevocationRoot(template)).use { f ->
                f.reconcile(); f.connection = GcmConnectionState.REPORTED_CONNECTED
                f.gmsVersion++; f.gmsUpdatedAt++
                f.revokeAcknowledged = false
                val original = f.root.bytes(DaemonDomainRole.HEARTBEAT)
                f.poll()
                assertEquals(0, f.arms); assertArrayEquals(original, f.root.bytes(DaemonDomainRole.HEARTBEAT))
                assertNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
                f.revokeAcknowledged = true
                f.beforeHeartbeatRevoke = { assertTrue(f.off() is DaemonControlReply.Acknowledged) }
                f.poll()
                assertEquals(0, f.arms)
                assertEquals(DaemonControlMode.OFF, f.root.snapshot().control.mode)
                assertArrayEquals(original, f.root.bytes(DaemonDomainRole.HEARTBEAT))
                assertNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
            }
        }
    }

    @Test fun trustedGmsUpdateRenewsExistingBlockedOrLiveChildWithoutAnotherOptIn() = runBlocking {
        Fixture().use { template ->
            for (terminal in listOf<GmsHeartbeatReason?>(null, GmsHeartbeatReason.SESSION_REVOKED, GmsHeartbeatReason.IDENTITY_CHANGED)) {
                val root = settledRevocationRoot(template)
                if (terminal != GmsHeartbeatReason.SESSION_REVOKED) {
                    val before = root.snapshot()
                    val child = HeartbeatSnapshotCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.HEARTBEAT)))
                    val roles = before.domains() + mapOf(
                        DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(child.copy(
                            resumeBlock = if (terminal == null) null else HeartbeatResumeBlock.SECURITY_FAILURE,
                            lastReason = terminal ?: child.lastReason)),
                        DaemonDomainRole.HEARTBEAT_HALT to if (terminal == null) null else byteArrayOf(1))
                    Fixture(existingRoot = RootFixtures.root(DaemonProtectionRootSnapshot(before.control, roles)).first).use { f ->
                        assertUpdateContinues(f, liveFirst = terminal == null)
                    }
                } else Fixture(existingRoot = root).use { f -> assertUpdateContinues(f, liveFirst = false) }
            }
        }
    }

    private suspend fun assertUpdateContinues(f: Fixture, liveFirst: Boolean) {
        f.reconcile(); f.connection = GcmConnectionState.REPORTED_CONNECTED
        f.oldAppGone = true
        if (liveFirst) { f.poll(); assertEquals(1, f.arms) }
        val before = f.root.snapshot()
        val old = HeartbeatSnapshotCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.HEARTBEAT)))
        f.gmsVersion++; f.gmsUpdatedAt++
        f.poll()
        assertEquals(if (liveFirst) 2 else 1, f.arms)
        val parent = FcmProtectionCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.FCM_PROTECTION)))
        val resumed = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
        assertEquals(epoch, parent.epoch)
        assertEquals(gmsHeartbeatBindingDigest(f.sample()), parent.installationBinding)
        assertEquals(FcmRecoveryPolicy.binding(f.sample()), parent.recoveryBinding)
        assertEquals(parent.installationBinding, resumed.bindingDigest)
        assertEquals(old.durableConsent!!.copy(bindingDigest = requireNotNull(parent.installationBinding)), resumed.durableConsent)
        assertEquals(old.generation + 1, resumed.generation)
        assertEquals(old.lastReason, resumed.lastReason); assertEquals(old.lastResultEpoch, resumed.lastResultEpoch)
        assertNull(resumed.resumeBlock); assertNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
        assertEquals(before.control, f.root.snapshot().control)
        for (role in listOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.SELECTION, DaemonDomainRole.RECOVERY_COUNTS,
            DaemonDomainRole.HEARTBEAT_DERIVATION, DaemonDomainRole.FCM_CONSUMED, DaemonDomainRole.FCM_MCS_CONSUMED))
            assertArrayEquals(role.name, before.bytes(role), f.root.bytes(role))
    }

    @Test fun bootContinuationUnblocksExistingRecoveryBeforeConnectionWithoutArmingHeartbeat() = runBlocking {
        val previousBoot = "11234567-89ab-cdef-0123-456789abcdef"
        Fixture().use { template ->
            for (state in listOf(GcmConnectionState.SERVICE_ABSENT, GcmConnectionState.REPORTED_DISCONNECTED)) {
                Fixture(existingRoot = settledRevocationRoot(template, previousBoot)).use { f ->
                    f.reconcile(); f.connection = state
                    f.revokeAcknowledged = false; f.freshHeartbeatEndpoint = true; f.oldAppGone = true
                    val before = f.root.snapshot()
                    val original = HeartbeatSnapshotCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.HEARTBEAT)))
                    f.poll()
                    assertEquals(0, f.arms); assertEquals(0, f.writes)
                    assertNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
                    val waiting = HeartbeatSnapshotCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.HEARTBEAT)))
                    assertEquals(HeartbeatPause.WAITING_HOST, waiting.pause)
                    assertEquals(original.durableConsent, waiting.durableConsent)
                    assertEquals(original.lastStatus, waiting.lastStatus)
                    assertEquals(original.lastReason, waiting.lastReason)
                    assertArrayEquals(before.bytes(DaemonDomainRole.FCM_PROTECTION), f.root.bytes(DaemonDomainRole.FCM_PROTECTION))
                    f.poll()
                    assertEquals(0, f.arms); assertEquals(1, f.writes)
                    val continued = FcmProtectionCodec.decode(requireNotNull(f.root.bytes(DaemonDomainRole.FCM_PROTECTION)))
                    assertEquals(epoch, continued.epoch)
                    assertEquals(FcmRecoveryPolicy.binding(f.sample()), continued.recoveryBinding)
                    assertEquals(before.control, f.root.snapshot().control)
                    assertArrayEquals(before.bytes(DaemonDomainRole.AUTHORITY), f.root.bytes(DaemonDomainRole.AUTHORITY))
                }
            }
        }
    }
}
