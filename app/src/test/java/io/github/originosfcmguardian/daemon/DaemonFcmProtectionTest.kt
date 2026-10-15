package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
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

class DaemonFcmProtectionTest {
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10436, 100, listOf("b".repeat(64))), 79, 200)

    private class Child : Process() {
        var running = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue(): Int { check(!running); return 0 }
        override fun isAlive() = running
        override fun destroy() { running = false }
    }

    private inner class Fixture(heartbeatEpoch: String = epoch, child: Boolean = false, cleanOff: Boolean = false,
        recoveryBoot: String = boot, heartbeatVersion: Int = 3, heartbeatBoot: String = boot,
        nativeOwned: Boolean = false) : AutoCloseable {
        val now = AtomicLong(10_000)
        var state = GcmConnectionState.SERVICE_ABSENT
        var network = true
        var permission = true
        var explicitStopped = false
        var oldAppGone = false
        var useHandoff = child
        var badHandoff = false
        var writes = 0
        var arms = 0
        var pulses = 0
        var revoked = 0
        var finishWorker = true
        var beforePrepare: (() -> Unit)? = null
        var lastFence: PrivilegedExecutionFence? = null
        var process = Child()
        var heartbeatUnknown = false
        var workerIdle = true
        var derivations = 0
        var digestReads = 0
        var exerciseCancellation = false
        var readUnknown = false
        var readFailure = false
        var deriveChild: ((DaemonControlRecord, GmsConnectionObservation.Sample) -> HeartbeatSnapshot?)? = null
        fun wall() = 1_000_000L + now.get()
        fun sample() = GmsConnectionObservation.Sample(1, 10208, 263737035, 1_000, 2_000,
            FcmRecoveryPolicy.SIGNER, boot, wall(), now.get(), SocketSource(SourceCompleteness.COMPLETE, false),
            SocketSource(SourceCompleteness.COMPLETE, state == GcmConnectionState.REPORTED_CONNECTED), true, emptyList(),
            GcmServiceSource(state, SourceCompleteness.COMPLETE, wall(), now.get(), 0))
        val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null), schemaVersion = 3,
            masterEnabledAtMillis = 1_000)
        val bundled = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample()),
            FcmRecoveryPolicy.binding(sample().copy(bootId = recoveryBoot)), true)
        val initialHeartbeat = HeartbeatSnapshot(5, true, HeartbeatPause.WAITING_HOST,
            bindingDigest = bundled.installationBinding, schemaVersion = 3,
            durableConsent = HeartbeatDurableConsent(heartbeatVersion, heartbeatEpoch, 0, requireNotNull(bundled.installationBinding)),
            callerIdentity = GmsHeartbeatCallerIdentity(10436, 123, 100, heartbeatBoot))
        private val initial = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), buildMap {
            put(DaemonDomainRole.FCM_PROTECTION, FcmProtectionCodec.encode(bundled))
            put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, bundled.recoveryBinding)))
            if (child) {
                put(DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(if (cleanOff)
                    initialHeartbeat.copy(configured = false, durableConsent = null, pause = null) else initialHeartbeat))
                put(DaemonDomainRole.HEARTBEAT_INITIALIZED, byteArrayOf(1))
            }
        }).let {
            val record = it.control.copy(mode = DaemonControlMode.ACTIVE)
            val roles = it.domains().toMutableMap()
            if (nativeOwned) roles[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER] = DaemonHeartbeatOwnerCodec.encode(
                DaemonHeartbeatOwner(record, heartbeatEpoch, initialHeartbeat.generation,
                    requireNotNull(initialHeartbeat.bindingDigest), daemonHeartbeatOwnerDigest(initialHeartbeat)))
            DaemonProtectionRootSnapshot(record, roles)
        }
        val root = RootFixtures.root(initial).first
        val domains = DaemonDomainStores(root)
        val record = initial.control
        val pointer = DaemonPointerCommitProof("initial", record)
        val control = object : DaemonControlPort {
            override fun read() = DaemonControlRead(record)
            override fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt? = null
            override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = proof == pointer
            override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? = null
            override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead = error("unused")
            override fun reconcile(proof: DaemonReconcileProof) = read().takeIf { proof.pointer == pointer }
            override fun withOwnerLease(start: () -> Unit): Boolean { start(); return true }
        }
        val gate = DaemonFcmWriteGate(now::get)
        val owner = DaemonOwnerControl(record.owner, installation, control,
            DaemonProtectionWriteRouter(DaemonPrerequisitePort { null }, gate), now::get)
        val environment = DaemonOwnerEnvironmentProbe(object : DaemonOwnerEnvironmentReadPort {
            override fun environment() = DaemonOwnerEnvironment(2000, 0, boot, true)
            override fun guardianPackage() = DaemonGuardianPackage(installation, true, true, explicitStopped, true)
            override fun packagesForUid(uid: Int) = listOf(GUARDIAN_PACKAGE_NAME)
            override fun shizukuRuntimePermission(uid: Int) = permission
            override fun guardianExitHistory(uid: Int) = emptyList<DaemonGuardianExit>()
            override fun wallMillis() = wall()
        })
        val shell = object : DaemonFcmShellPort {
            override fun read(id: Long, cancelled: () -> Boolean): GmsConnectionObservation {
                if (readFailure) error("Read failed")
                if (readUnknown) return GmsConnectionObservation.Unknown(id, GmsUnknown.TIMED_OUT)
                if (exerciseCancellation) {
                    val before = digestReads
                    repeat(1_000) { assertFalse(cancelled()) }
                    assertEquals(before, digestReads)
                }
                return sample().copy(requestId = id)
            }
            override fun usableNetwork() = network
            override fun disconnectedNetwork() = network
            override fun previousHeartbeatOwnerGone(value: HeartbeatSnapshot, cancelled: () -> Boolean) = oldAppGone && !cancelled()
            override fun recover(id: Long, uid: Int, binding: String, authorize: () -> Boolean,
                cancelled: () -> Boolean, operation: FcmRecoveryOperation, fence: PrivilegedExecutionFence): FcmRecoveryResult {
                assertTrue(FcmRecoveryStore(domains.fcmRecovery).read().consumed(operation))
                lastFence = fence
                beforePrepare?.invoke()
                if (!fence.prepare() || !authorize()) {
                    fence.workerSettled(PrivilegedExecutionSettlement.NOT_STARTED)
                    return FcmRecoveryResult(id, FcmRecoveryStatus.NOT_SENT, FcmRecoveryReason.AUTHORITY_LOST, operation)
                }
                assertNotNull(fence.start { writes++; process })
                if (finishWorker) fence.workerSettled(PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED)
                return FcmRecoveryResult(id, if (finishWorker) FcmRecoveryStatus.REQUESTED else FcmRecoveryStatus.UNKNOWN,
                    if (finishWorker) FcmRecoveryReason.COMMAND_ACCEPTED else FcmRecoveryReason.TIMED_OUT, operation)
            }
            override fun arm(id: Long, uid: Int, generation: Long, binding: String, lifetime: GmsHeartbeatLifetime,
                cancelled: () -> Boolean): GmsAutoArmResult {
                assertTrue(domains.heartbeat.unsettledPending()); assertTrue(lifetime.alive()); arms++
                return GmsAutoArmResult(GmsAutoSession("abcdefab-1234-1234-1234-123456789abc", generation, binding), GmsAutoArmReason.ARMED)
            }
            override fun heartbeat(id: Long, uid: Int, session: GmsAutoSession, attempt: String,
                cancelled: () -> Boolean, fence: PrivilegedExecutionFence): GmsHeartbeatResult {
                assertEquals(attempt, domains.heartbeat.read()?.pendingAttempt)
                assertTrue(domains.heartbeat.unsettledPending())
                assertTrue(fence.prepare()); assertNotNull(fence.start { pulses++; Child() })
                fence.workerSettled(PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED)
                return if (heartbeatUnknown) GmsHeartbeatResult.unknown(id, GmsHeartbeatReason.TIMED_OUT)
                    else GmsHeartbeatResult(id, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)
            }
            override fun revoke(uid: Int, session: GmsAutoSession): Boolean { revoked++; return true }
            override fun isQuiescent() = workerIdle
            override fun close() = Unit
        }
        val engine = DaemonFcmProtection(owner, gate, domains, { digestReads++; root.authorityDigest() }, environment,
            installation, boot, now::get, ::wall, shell, 555,
            DaemonHeartbeatHandoffPort { pinned, heartbeat ->
                if (!useHandoff) null else DaemonHeartbeatHandoffProof(
                    if (badHandoff) pinned.copy(revision = pinned.revision + 1) else pinned, heartbeat,
                    DaemonProtectionRootSnapshot.digest(requireNotNull(root.bytes(DaemonDomainRole.HEARTBEAT))))
            }, bundledHeartbeat = object : DaemonBundledHeartbeatPort {
                override fun derive(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample): HeartbeatSnapshot? {
                    derivations++
                    return deriveChild?.invoke(record, sample)
                }
                override fun rebindRecovery(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample) =
                    DaemonBundledHeartbeatDerivation(owner, root, environment, installation, boot, now::get).rebindRecovery(record, sample)
            })
        init { assertTrue(owner.reconcile(DaemonCallerEvidence(installation.identity.uid, installation),
            DaemonReconcileProof(pointer, "initial"))) }
        suspend fun poll(): DaemonFcmCycle { now.addAndGet(30_000); return engine.pollAsync() }
        override fun close() { engine.close() }
    }

    @Test fun productionCycleConsumesBeforeDispatchAndDoesNotReplayAbsence() = runBlocking {
        Fixture().use { f ->
            assertEquals(DaemonFcmCycleStatus.CHECKED, f.poll().status)
            assertEquals(1, f.writes)
            assertEquals(DaemonFcmCycleStatus.CHECKED, f.poll().status)
            assertEquals(1, f.writes)
            val saved = FcmRecoveryStore(f.domains.fcmRecovery).read()
            assertTrue(saved.consumed)
            assertTrue(saved.events.any { it.action == FcmRecoveryEventAction.REQUEST_RESULT && it.status == FcmRecoveryStatus.REQUESTED })
            assertNotNull(f.owner.admit("stopped:another:operation"))
        }
    }

    @Test fun displayReadinessUpdatesEveryNormalCycleWhileObservationJournalRemainsDeduplicated() = runBlocking {
        Fixture().use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            val first = requireNotNull(f.engine.diagnosticObservation().recoveryReadiness)
            val initialEvents = FcmRecoveryStore(f.domains.fcmRecovery).read().events.size
            repeat(4) { f.poll() }
            val pair = f.engine.diagnosticObservation()
            val current = requireNotNull(pair.recoveryReadiness)
            assertTrue(current.admitted)
            assertTrue(current.checkedAtElapsedMillis - first.checkedAtElapsedMillis > 60_000)
            assertTrue(current.matches(f.record, pair.observation as GmsConnectionObservation.Sample))
            assertEquals(initialEvents, FcmRecoveryStore(f.domains.fcmRecovery).read().events.size)
            assertEquals(0, f.writes)
        }
    }

    @Test fun displayReadinessCannotSurviveNetworkLossControlQuiesceFailureOrClose() = runBlocking {
        Fixture().use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll(); assertTrue(requireNotNull(f.engine.diagnosticObservation().recoveryReadiness).admitted)
            f.network = false
            f.poll(); assertFalse(requireNotNull(f.engine.diagnosticObservation().recoveryReadiness).admitted)
            f.network = true
            f.poll(); assertTrue(requireNotNull(f.engine.diagnosticObservation().recoveryReadiness).admitted)
            f.engine.controlCommitted(); assertNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.poll(); assertTrue(requireNotNull(f.engine.diagnosticObservation().recoveryReadiness).admitted)
            f.permission = false
            f.poll(); assertNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.permission = true
            f.poll(); assertTrue(requireNotNull(f.engine.diagnosticObservation().recoveryReadiness).admitted)
            f.engine.close(); assertNull(f.engine.diagnosticObservation().recoveryReadiness)
        }
    }

    @Test fun unknownAndFailedCyclesClearDisplayEvidenceAndThrottledCopiesCannotExtendItsAge() = runBlocking {
        Fixture().use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll(); assertNotNull(f.engine.diagnosticObservation().recoveryReadiness)
            assertEquals(DaemonFcmCycleStatus.THROTTLED, f.engine.pollAsync().status)
            assertNotNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.now.addAndGet(30_001)
            assertNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.readUnknown = true
            f.poll()
            assertTrue(f.engine.diagnosticObservation().observation is GmsConnectionObservation.Unknown)
            assertNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.readUnknown = false
            f.poll(); assertNotNull(f.engine.diagnosticObservation().recoveryReadiness)
            f.readFailure = true
            assertEquals(DaemonFcmCycleStatus.UNAVAILABLE, f.poll().status)
            assertNull(f.engine.diagnosticObservation().recoveryReadiness)
        }
    }

    @Test fun newBootRecoveryBindingContinuesBeforeParentAdmissionWithoutNewConsent() = runBlocking {
        Fixture(recoveryBoot = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").use { f ->
            val before = f.bundled
            assertEquals(DaemonFcmCycleStatus.CHECKED, f.poll().status)
            assertEquals(1, f.writes)
            val after = FcmProtectionStore(f.domains.fcmProtection).read()
            assertEquals(before.copy(recoveryBinding = FcmRecoveryPolicy.binding(f.sample())), after)
            assertEquals(f.record, f.root.snapshot().control)
            f.poll(); assertEquals(1, f.writes)
        }
    }

    @Test fun durableDaemonProvenanceCannotBypassV2CrossBootScopeButV3Continues() = runBlocking {
        for (version in 2..3) Fixture(child = true, heartbeatVersion = version,
            heartbeatBoot = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", nativeOwned = true).use { f ->
            f.useHandoff = false
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(if (version == 3) 1 else 0, f.arms)
            assertEquals(0, f.pulses)
        }
    }

    @Test fun cancellationPollingUsesOnlyMemoryWhileDurableDispatchChecksRemain() = runBlocking {
        Fixture(child = true).use { f ->
            f.exerciseCancellation = true
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(1, f.arms)
            assertTrue(f.digestReads > 0)
        }
    }

    @Test fun actualConnectedReadRearmsAndLaterAbsenceCanConsumeOneNewAttempt() = runBlocking {
        Fixture().use { f ->
            f.poll(); f.state = GcmConnectionState.REPORTED_CONNECTED; f.poll()
            assertFalse(FcmRecoveryStore(f.domains.fcmRecovery).read().consumed)
            f.state = GcmConnectionState.SERVICE_ABSENT; f.poll()
            assertEquals(2, f.writes)
        }
    }

    @Test fun privilegeRevocationAndExplicitUserStopDenyDispatchWithoutAppLivenessRequirement() = runBlocking {
        Fixture().use { f -> f.permission = false; f.poll(); assertEquals(0, f.writes) }
        Fixture().use { f -> f.explicitStopped = true; f.poll(); assertEquals(0, f.writes) }
        Fixture().use { f -> f.network = false; f.poll(); assertEquals(0, f.writes) }
    }

    @Test fun controlQuiesceAfterConsumeBeforePrepareDeniesActualLaunch() = runBlocking {
        Fixture().use { f ->
            f.beforePrepare = { assertTrue(f.owner.quiesceForControl(DaemonCallerEvidence(10436, installation), f.record)) }
            f.poll()
            assertEquals(0, f.writes)
            assertTrue(FcmRecoveryStore(f.domains.fcmRecovery).read().consumed)
        }
    }

    @Test fun timeoutRetainsGlobalAdmissionThroughActualWorkerExitAndExistingPollReaps() = runBlocking {
        Fixture().use { f ->
            f.finishWorker = false; f.process.running = true
            assertEquals(DaemonFcmCycleStatus.PENDING, f.poll().status)
            assertNull(f.owner.admit("stopped:another:operation"))
            assertEquals(DaemonFcmCycleStatus.PENDING, f.poll().status)
            f.lastFence!!.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
            assertEquals(DaemonFcmCycleStatus.PENDING, f.poll().status)
            f.process.running = false
            assertEquals(DaemonFcmCycleStatus.CHECKED, f.poll().status)
            assertNotNull(f.owner.admit("stopped:another:operation"))
        }
    }

    @Test fun exactCleanHandoffArmsWhileAppMayStillExistThenWaitsFullFifteenMinutes() = runBlocking {
        Fixture(child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll(); assertEquals(1, f.arms); assertEquals(0, f.pulses)
            f.now.addAndGet(GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS - 30_001)
            f.poll(); assertEquals(0, f.pulses)
            f.poll(); assertEquals(1, f.pulses)
            f.now.addAndGet(GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS * 3)
            f.poll(); assertEquals(2, f.pulses) // No historical catch-up requests.
            assertFalse(f.domains.heartbeat.unsettledPending())
        }
    }

    @Test fun wrongParentChildEpochOrStaleHandoffCannotArmAndMissingChildIsNotMinted() = runBlocking {
        Fixture(heartbeatEpoch = "abcdefab-1234-1234-1234-123456789abc", child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED; f.poll(); assertEquals(0, f.arms)
        }
        Fixture(child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED; f.badHandoff = true; f.poll(); assertEquals(0, f.arms)
        }
        Fixture().use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED; f.poll()
            assertNull(f.domains.heartbeat.read()); assertEquals(0, f.arms)
        }
    }

    @Test fun unknownHeartbeatPreservesPendingAndNeverReplaysOnSubsequentPolls() = runBlocking {
        Fixture(child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED; f.poll()
            f.heartbeatUnknown = true; f.now.addAndGet(GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS); f.poll()
            assertEquals(1, f.pulses); assertTrue(f.domains.heartbeat.unsettledPending())
            repeat(3) { f.now.addAndGet(GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS); f.poll() }
            assertEquals(1, f.pulses); assertEquals(1, f.arms)
            assertEquals(HeartbeatResumeBlock.UNKNOWN_RESULT, f.domains.heartbeat.read()?.resumeBlock)
        }
    }

    @Test fun cleanDaemonOwnershipSurvivesRecreationEvenWhileHistoricalAppCallerMayStillExist() = runBlocking {
        Fixture(child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED; f.poll()
            val encoded = f.domains.heartbeatOwnerProvenance.read()
            assertNotNull(encoded)
            val proof = DaemonHeartbeatOwnerCodec.decode(requireNotNull(encoded))
            assertEquals(f.record, proof.record)
            assertEquals(f.domains.heartbeat.read()?.generation, proof.generation)
            assertEquals(daemonHeartbeatOwnerDigest(requireNotNull(f.domains.heartbeat.read())), proof.heartbeatBytesDigest)
            f.engine.close()
            // close acknowledges the old native endpoint; emulate restart with the clean persisted bytes.
            // Historical app caller liveness is irrelevant only when exact daemon provenance matches.
            val saved = f.domains.heartbeat.read()!!
            assertEquals(HeartbeatPause.WAITING_HOST, saved.pause)
            assertNull(saved.remoteSessionId)
            f.useHandoff = false
            val recreated = DaemonFcmProtection(f.owner, f.gate, f.domains, f.root::authorityDigest,
                f.environment, installation, boot, f.now::get, f::wall, f.shell, 556)
            recreated.use {
                f.now.addAndGet(30_000)
                assertEquals(DaemonFcmCycleStatus.CHECKED, recreated.pollAsync().status)
                assertEquals(2, f.arms)
                assertEquals(0, f.pulses)
            }
        }
    }

    @Test fun handoffProofIsSingleUseAndCodecRefusesCorruption() {
        Fixture(child = true).use { f ->
            val digest = DaemonProtectionRootSnapshot.digest(requireNotNull(f.domains.heartbeatBytes()))
            val proof = DaemonHeartbeatHandoffProof(f.record, f.initialHeartbeat, digest)
            assertFalse(proof.consume(f.record.copy(revision = 2), f.initialHeartbeat, digest))
            assertTrue(proof.consume(f.record, f.initialHeartbeat, digest))
            assertFalse(proof.consume(f.record, f.initialHeartbeat, digest))
            val owner = DaemonHeartbeatOwner(f.record, epoch, 5, requireNotNull(f.initialHeartbeat.bindingDigest), digest)
            assertEquals(owner, DaemonHeartbeatOwnerCodec.decode(DaemonHeartbeatOwnerCodec.encode(owner)))
            val corrupted = DaemonHeartbeatOwnerCodec.encode(owner).also { it[20] = (it[20].toInt() xor 1).toByte() }
            RootFixtures.fails { DaemonHeartbeatOwnerCodec.decode(corrupted) }
        }
    }

    @Test fun closeKeepsLifetimeLeaseRequiredUntilWorkerAndChildAreBothSettled() = runBlocking {
        Fixture().use { f ->
            f.finishWorker = false; f.process.running = true; f.poll(); f.engine.close()
            assertFalse(f.engine.isQuiescent())
            f.lastFence!!.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
            assertFalse(f.engine.isQuiescent())
            f.process.running = false
            assertTrue(f.engine.isQuiescent())
        }
    }

    @Test fun actualRunnerActivityBlocksPollAndStorageReleaseAfterFutureHasReturned() = runBlocking {
        Fixture().use { f ->
            f.workerIdle = false
            assertEquals(DaemonFcmCycleStatus.PENDING, f.poll().status)
            assertEquals(0, f.writes)
            f.engine.close()
            assertFalse(f.engine.isQuiescent())
            f.workerIdle = true
            assertTrue(f.engine.isQuiescent())
        }
    }

    @Test fun onlyQualifiedConnectedMissingFirstChildRequestsControlDerivation() = runBlocking {
        Fixture().use { f ->
            f.poll()
            assertEquals(0, f.derivations)
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.permission = false
            f.poll()
            assertEquals(0, f.derivations)
            f.permission = true
            f.poll()
            assertEquals(1, f.derivations)
            assertEquals(0, f.arms) // A null control response cannot be replaced with locally minted consent.
        }
        Fixture(child = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(0, f.derivations)
        }
        Fixture(child = true, cleanOff = true).use { f ->
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(1, f.derivations)
            assertEquals(0, f.arms)
        }
    }

    @Test fun durableOwnerProofMustMatchExactKernelRecordAndStableOwnershipProjection() = runBlocking {
        Fixture(child = true).use { f ->
            f.useHandoff = false
            val child = requireNotNull(f.domains.heartbeat.read())
            val bad = DaemonHeartbeatOwner(f.record.copy(revision = f.record.revision + 1), epoch,
                child.generation, requireNotNull(child.bindingDigest), daemonHeartbeatOwnerDigest(child))
            f.domains.heartbeatOwnerProvenance.write(DaemonHeartbeatOwnerCodec.encode(bad))
            f.state = GcmConnectionState.REPORTED_CONNECTED
            f.poll()
            assertEquals(0, f.arms)
            val good = bad.copy(record = f.record)
            f.domains.heartbeatOwnerProvenance.write(DaemonHeartbeatOwnerCodec.encode(good))
            f.poll()
            assertEquals(1, f.arms)
            assertEquals(f.initialHeartbeat.callerIdentity, f.domains.heartbeat.read()?.callerIdentity)
        }
    }

    @Test fun ownershipProjectionIgnoresResultDiagnosticsButBindsConsentSessionAndCaller() {
        Fixture(child = true).use { f ->
            val child = f.initialHeartbeat
            val digest = daemonHeartbeatOwnerDigest(child)
            assertEquals(digest, daemonHeartbeatOwnerDigest(child.copy(lastStatus = GmsHeartbeatStatus.REQUESTED,
                lastReason = GmsHeartbeatReason.COMMAND_ACCEPTED, lastResultEpoch = epoch)))
            assertNotEquals(digest, daemonHeartbeatOwnerDigest(child.copy(generation = child.generation + 1)))
            val caller = requireNotNull(child.callerIdentity)
            assertNotEquals(digest, daemonHeartbeatOwnerDigest(child.copy(
                callerIdentity = caller.copy(pid = caller.pid + 1))))
            assertNotEquals(digest, daemonHeartbeatOwnerDigest(child.copy(
                pause = null, remoteSessionId = "abcdefab-1234-1234-1234-123456789abc")))
        }
    }

    @Test fun trustedHandoffRetargetTransfersOneUseWithoutDuplicatingOrReconstructingClosure() {
        Fixture(child = true).use { f ->
            val heartbeat = f.initialHeartbeat
            val digest = DaemonProtectionRootSnapshot.digest(requireNotNull(f.domains.heartbeatBytes()))
            val original = DaemonHeartbeatHandoffProof(f.record, heartbeat, digest)
            val off = f.record.copy(revision = f.record.revision + 1, mode = DaemonControlMode.OFF)
            val transferred = requireNotNull(original.retarget(off, heartbeat, digest))
            assertFalse(original.consume(f.record, heartbeat, digest))
            assertNull(original.retarget(off, heartbeat, digest))
            val on = off.copy(revision = off.revision + 1, mode = DaemonControlMode.ACTIVE)
            assertNull(transferred.retarget(on.copy(revision = on.revision + 1), heartbeat, digest))
            assertNull(transferred.retarget(on, heartbeat.copy(generation = heartbeat.generation + 1), digest))
            val resumed = requireNotNull(transferred.retarget(on, heartbeat, digest))
            assertFalse(transferred.consume(off, heartbeat, digest))
            assertTrue(resumed.consume(on, heartbeat, digest))
            assertFalse(resumed.consume(on, heartbeat, digest))
        }
    }
}
