package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.privileged.*
import io.github.originosfcmguardian.recovery.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DaemonStoppedProtectionTest {
    private val target = SelectedTargetIdentity("example.recipient", 10435, 100, listOf("a".repeat(64)))
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10436, 100, listOf("b".repeat(64))), 79, 200)
    private val caller = DaemonCallerEvidence(10436, installation)
    private val ownerKey = DaemonOwnerKey("test-install", 1)
    private val boot = "11111111-2222-3333-4444-555555555555"

    private class ControlStore(initial: DaemonControlRecord) : DaemonControlPort {
        var read = DaemonControlRead(initial)
        var pointer = DaemonPointerCommitProof("initial", initial)
        val receipts = mutableMapOf<String, DaemonControlReceipt>()
        override fun read() = read
        override fun receipt(owner: DaemonOwnerKey, requestId: String) = receipts[requestId]
        override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = proof == pointer
        override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? {
            if (read.record != expected) return null
            read = read.copy(interrupted = true)
            return DaemonHaltFence(request, expected.revision + 1)
        }
        override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead {
            receipts[receipt.request.requestId] = receipt
            read = read.copy(record = receipt.committed, interrupted = false)
            return read
        }
        override fun reconcile(proof: DaemonReconcileProof) = read.takeIf { proof.pointer == pointer }
        override fun withOwnerLease(start: () -> Unit): Boolean { start(); return true }
    }

    private inner class Fixture(identities: List<SelectedTargetIdentity> = listOf(target), readTimeout: Long = 5_000) : AutoCloseable {
        val now = AtomicLong(10_000)
        var authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null),
            targetConsents = identities.sortedBy { it.packageName }.mapIndexed { i, identity ->
                TargetConsent.Recipient(RecipientConsentToken("12345678-1234-1234-1234-%012d".format(i + 1),
                    1, 1_000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
            }, schemaVersion = 3, masterEnabledAtMillis = 1_000)
        val consent = ProtectionConsentStore(object : ProtectionAuthorityStorage {
            override fun <T> exclusive(action: () -> T) = synchronized(this) { action() }
            override fun read() = authority
            override fun write(value: ProtectionAuthoritySnapshot) { authority = value }
        })
        private var selections = TargetSelectionCodec.encode(identities)
        val selection = TargetSelectionStore(object : TargetSelectionPreferences {
            override fun read() = selections
            override fun write(value: String): Boolean { selections = value; return true }
            override fun clear(): Boolean { selections = ""; return true }
        })
        var journalBytes: ByteArray? = null
        var journalUncertain = false
        val journal = StoppedRecoveryJournal(object : RecoveryJournalStorage {
            override fun read() = journalBytes?.clone()
            override fun write(bytes: ByteArray) { journalBytes = bytes.clone() }
            override fun markUncertain() { journalUncertain = true }
        })
        var countsBytes: ByteArray? = null
        val counts = RecoveryCountsStore(object : RecoveryCountsStorage {
            override fun read() = countsBytes?.clone()
            override fun write(bytes: ByteArray) { countsBytes = bytes.clone() }
            override fun markUnavailable() = Unit
        })
        fun digest(): String = MessageDigest.getInstance("SHA-256").digest(ProtectionAuthorityCodec.encode(authority))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val controlStore = ControlStore(DaemonControlRecord(ownerKey, 1, digest(), DaemonControlMode.ACTIVE, "migration"))
        val gate = DaemonStoppedWriteGate(now::get)
        val owner = DaemonOwnerControl(ownerKey, installation, controlStore, gate, now::get)
        var permission: Boolean? = true
        var guardianStopped = false
        val environment = DaemonOwnerEnvironmentProbe(object : DaemonOwnerEnvironmentReadPort {
            override fun environment() = DaemonOwnerEnvironment(2000, 0, boot, true)
            override fun guardianPackage() = DaemonGuardianPackage(installation, true, true, guardianStopped, true)
            override fun packagesForUid(uid: Int) = listOf(GUARDIAN_PACKAGE_NAME)
            override fun shizukuRuntimePermission(uid: Int) = permission
            override fun guardianExitHistory(uid: Int): List<DaemonGuardianExit> = emptyList()
            override fun wallMillis() = System.currentTimeMillis()
        })
        val readCalls = AtomicInteger()
        val starts = AtomicInteger()
        val awaited = AtomicInteger()
        val seen = CopyOnWriteArrayList<SelectedTargetIdentity>()
        val stopped = identities.associateWith { true }.toMutableMap()
        var observeHook: (Int, Long, SelectedTargetIdentity) -> Unit = { _, _, _ -> }
        var awaitHook: () -> Unit = {}
        var failAwait = false
        var terminateOnClose = true
        @Volatile var processExited = false
        var recipientReadsOnly = true
        val shell = object : DaemonStoppedShellPort {
            override fun observe(id: Long, identity: SelectedTargetIdentity, recipient: Boolean,
                interrupted: () -> Boolean): StoppedTargetObservation {
                val call = readCalls.incrementAndGet()
                seen += identity
                recipientReadsOnly = recipientReadsOnly && recipient
                observeHook(call, id, identity)
                if (interrupted()) return StoppedTargetObservation.Unknown(id, StoppedObservationUnknown.CANCELLED)
                return StoppedTargetObservation.Known(id, identity, synchronized(stopped) { stopped[identity]!! },
                    false, System.currentTimeMillis(), qualifiedRecipient = true)
            }
            override fun prepareUnstop(identity: SelectedTargetIdentity, recipient: Boolean, active: () -> Boolean): DaemonUnstopLaunch? {
                if (!active()) return null
                return DaemonUnstopLaunch {
                    assertEquals(StoppedRecoveryStage.CLAIMED, journal.load().latest?.stage)
                    assertEquals(DaemonOwnerStatus.ACTIVE, owner.status())
                    starts.incrementAndGet()
                    processExited = false
                    object : DaemonStartedUnstop {
                        override fun awaitExit(active: () -> Boolean): Int {
                            awaited.incrementAndGet()
                            awaitHook()
                            if (failAwait) error("unknown process result")
                            check(active())
                            synchronized(stopped) { stopped[identity] = false }
                            processExited = true
                            return 0
                        }
                        override fun close() { if (terminateOnClose) processExited = true }
                        override fun terminated() = processExited
                    }
                }
            }
            override fun close() = Unit
        }
        val protection = DaemonStoppedProtection(owner, gate, consent, selection, journal, counts, ::digest,
            environment, installation, boot, shell, now::get,
            targetReader = BoundedAutomaticTargetRead(readTimeout), readbackPause = {})
        init { assertTrue(owner.reconcile(caller, DaemonReconcileProof(controlStore.pointer, "reconcile"))) }
        fun off() {
            val before = controlStore.read.record
            val pointer = DaemonPointerCommitProof("off", before.copy(revision = before.revision + 1, mode = DaemonControlMode.OFF))
            controlStore.pointer = pointer
            assertTrue(owner.apply(caller, DaemonControlRequest("off", ownerKey, before.revision,
                DaemonControlIntent.SetAuthority(pointer))) is DaemonControlReply.Acknowledged)
        }
        fun nextPoll() { now.addAndGet(DaemonStoppedProtection.POLL_MILLIS + 1) }
        fun count() = counts.snapshot(listOf(target), System.currentTimeMillis()).byIdentity[target]!!.today
        override fun close() { protection.close() }
    }

    @Test fun realEngineClaimsClearsPersistsAndCountsExactlyOneAutomaticReceipt() = runBlocking {
        Fixture().use { f ->
            val originalAuthority = ProtectionAuthorityCodec.encode(f.authority)
            val result = f.protection.pollAsync()
            assertEquals(DaemonStoppedCycleStatus.CHECKED, result.status)
            assertEquals(1, f.starts.get())
            assertEquals(1, f.awaited.get())
            val receipt = result.results.single()
            assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, receipt.outcome)
            assertTrue(receipt.commandSucceeded)
            assertEquals(StoppedRecoveryStage.FINISHED, receipt.record!!.stage)
            assertEquals(StoppedRecoverySource.AUTOMATIC, receipt.record.source)
            assertEquals(4, receipt.record.consentVersion)
            assertEquals(f.journal.load().latest, receipt.record)
            assertEquals(1, f.count())
            assertTrue(f.recipientReadsOnly) // policy4 fresh qualified shared-UID evidence is retained.
            assertArrayEquals(originalAuthority, ProtectionAuthorityCodec.encode(f.authority))
            assertEquals(DaemonStoppedCycleStatus.THROTTLED, f.protection.pollAsync().status)
            f.nextPoll()
            assertEquals(DaemonStoppedCycleStatus.CHECKED, f.protection.pollAsync().status)
            assertEquals(1, f.starts.get())
            assertEquals(1, f.count())
        }
    }

    @Test fun appDisconnectDoesNotStopTheDaemonOwnedAutomaticExecution() = runBlocking {
        Fixture().use { f ->
            f.owner.appDisconnected()
            assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, f.protection.pollAsync().results.single().outcome)
            assertEquals(1, f.starts.get())
            assertEquals(1, f.count())
        }
    }

    @Test fun offDuringFinalGateReadAcknowledgesAndBlocksDelayedActualStart() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.observeHook = { call, _, _ ->
                if (call == 5) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val cycle = async(Dispatchers.Default) { f.protection.pollAsync() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(DaemonStoppedCycleStatus.BUSY, f.protection.pollAsync().status)
                f.off()
                assertEquals(DaemonOwnerStatus.OFF, f.owner.status())
                assertEquals(0, f.starts.get())
            } finally { release.countDown() }
            assertEquals(DaemonStoppedCycleStatus.PENDING, cycle.await().status)
            assertEquals(0, f.starts.get())
            assertTrue(f.journal.load().latest!!.requiresReconcile)
            assertEquals(0, f.count())
        }
    }

    @Test fun offDuringProcessWaitDoesNotWaitOnOwnerMonitorOrReleaseLiveFlight() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.awaitHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.terminateOnClose = false
            val cycle = async(Dispatchers.Default) { f.protection.pollAsync() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.off()
                assertEquals(DaemonOwnerStatus.OFF, f.owner.status())
            } finally { release.countDown() }
            assertEquals(DaemonStoppedCycleStatus.PENDING, cycle.await().status)
            f.protection.close()
            assertFalse(f.protection.isQuiescent())
            f.processExited = true
            assertTrue(f.protection.isQuiescent())
            assertEquals(0, f.count())
        }
    }

    @Test fun boundedReadTimeoutDiscardsLateStoppedEvidenceWithoutAnySpeculativeWrite() = runBlocking {
        Fixture(readTimeout = 30).use { f ->
            val release = CountDownLatch(1)
            f.observeHook = { _, _, _ ->
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
            try {
                assertEquals(DaemonStoppedCycleStatus.CHECKED, f.protection.pollAsync().status)
                assertEquals(0, f.starts.get())
                assertNull(f.journal.load().latest)
            } finally { release.countDown() }
            assertEquals(DaemonStoppedCycleStatus.THROTTLED, f.protection.pollAsync().status)
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun falseFlagsAreReadOnlyAndFairRotationRetainsFourReadChunks() = runBlocking {
        val identities = (0..4).map { target.copy(packageName = "example.recipient$it", uid = 10440 + it) }
        Fixture(identities).use { f ->
            synchronized(f.stopped) { identities.forEach { f.stopped[it] = false } }
            assertEquals(5, f.protection.pollAsync().checkedTargets)
            assertEquals(identities.take(4).toSet(), f.seen.take(4).toSet())
            assertEquals(0, f.starts.get())
            assertNull(f.journal.load().latest)
            f.seen.clear(); f.nextPoll()
            assertEquals(5, f.protection.pollAsync().checkedTargets)
            assertEquals(identities.drop(1).toSet(), f.seen.take(4).toSet())
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun changedLeaseOrPermissionAtFinalReadCannotReachUnstop() = runBlocking {
        for (changePermission in listOf(true, false)) Fixture().use { f ->
            f.observeHook = { call, _, _ ->
                if (call == 4) {
                    if (changePermission) f.permission = false
                    else f.authority = f.authority.copy(generation = f.authority.generation + 1)
                }
            }
            assertEquals(DaemonStoppedCycleStatus.PENDING, f.protection.pollAsync().status)
            assertEquals(0, f.starts.get())
            assertEquals(StoppedRecoveryStage.CLAIMED, f.journal.load().latest!!.stage)
            assertEquals(0, f.count())
        }
    }

    @Test fun unfinishedCommandOccupiesOwnerUntilTerminationThenOnlyReadonlyReconciles() = runBlocking {
        Fixture().use { f ->
            f.failAwait = true
            f.terminateOnClose = false
            assertEquals(DaemonStoppedCycleStatus.PENDING, f.protection.pollAsync().status)
            assertEquals(1, f.starts.get())
            assertNull(f.owner.admit("other:write"))
            f.nextPoll()
            assertEquals(DaemonStoppedCycleStatus.PENDING, f.protection.pollAsync().status)
            assertEquals(1, f.starts.get())
            f.processExited = true
            f.nextPoll()
            assertEquals(DaemonStoppedCycleStatus.RECONCILED, f.protection.pollAsync().status)
            assertEquals(StoppedRecoveryStage.RECONCILED, f.journal.load().latest!!.stage)
            assertEquals(StoppedRecoveryOutcome.STILL_STOPPED, f.journal.load().latest!!.outcome)
            assertEquals(1, f.starts.get())
            assertEquals(0, f.count())
        }
    }

    @Test fun oldPreparedJournalIsResolvedReadonlyAndNeverCountedOrReplayed() = runBlocking {
        Fixture().use { f ->
            val lease = f.consent.desiredLeases().single()
            f.journal.prepareAutomatically(target,
                StoppedTargetObservation.Known(1, target, true, false, System.currentTimeMillis(), qualifiedRecipient = true),
                AutomaticRecoveryConsent(lease.consent.id, lease.consent.consentedAtMillis, 4))
            synchronized(f.stopped) { f.stopped[target] = false }
            assertEquals(DaemonStoppedCycleStatus.RECONCILED, f.protection.pollAsync().status)
            assertEquals(StoppedRecoveryStage.RECONCILED, f.journal.load().latest!!.stage)
            assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, f.journal.load().latest!!.outcome)
            assertEquals(0, f.starts.get())
            assertEquals(0, f.count())
        }
    }
}
