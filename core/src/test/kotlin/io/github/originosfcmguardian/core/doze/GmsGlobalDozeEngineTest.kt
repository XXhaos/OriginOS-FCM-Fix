package io.github.originosfcmguardian.core.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmsGlobalDozeEngineTest {
    private val fingerprint = "qualified-host-fingerprint"
    private val baseline = "system,android,1000\nuser,com.example.other,10123\n"
    private val row = "user,$GMS_GLOBAL_DOZE_PACKAGE,10208\n"
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64), "b".repeat(64)))
    private val guardian = GmsGlobalGuardianBinding(0, GmsGlobalInstallation(10493, 60, 50, 300, listOf("c".repeat(64))))
    private fun identity() = GmsGlobalDozeIdentity(gms, guardian, GmsGlobalEnvironment(fingerprint, 2000, 0),
        GmsGlobalOwnerEvidence.Qualified(GMS_GLOBAL_OWNER_PROOF_VERSION, "d".repeat(64), gms.appId))

    /** Real exact-byte CAS/transition validation, shared by every reconstructed engine in a test. */
    private class MemoryDocument : GmsGlobalDozeDocumentPort {
        private var bytes: ByteArray? = null
        var rejectCas = false
        var mismatchedReadback = false
        var onCommit: ((GmsGlobalDozeDocument) -> Unit)? = null
        @Synchronized override fun load(): ByteArray? = if (mismatchedReadback) byteArrayOf(9) else bytes?.clone()
        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (rejectCas || !(bytes?.contentEquals(expected ?: byteArrayOf()) ?: (expected == null))) return false
            GmsGlobalDozeCodec.validateTransition(expected, replacement)
            bytes = replacement.clone()
            onCommit?.invoke(GmsGlobalDozeCodec.decode(replacement))
            return true
        }
        @Synchronized fun setRaw(raw: ByteArray) { bytes = raw.clone() }
        fun decoded() = GmsGlobalDozeCodec.decode(assertNotNull(load()))
    }

    /** Host monitor is intentionally host-only; production requires shared process/storage locks. */
    private class SharedAdmission : GmsGlobalDozeAdmissionPort {
        private val operationLock = Any()
        private val gateLock = Any()
        private var version = 0L
        private var allowed = false
        var beforeAddDispatch: (() -> Unit)? = null
        override fun <T> serialized(operation: () -> T): T = synchronized(operationLock) { operation() }
        override fun revocationVersion(): Long = synchronized(gateLock) { version }
        override fun revokeAdds(): Long = synchronized(gateLock) { allowed = false; ++version }
        override fun allowAdds(expectedRevocationVersion: Long): Boolean = synchronized(gateLock) {
            if (version != expectedRevocationVersion) false else { allowed = true; true }
        }
        override fun addsAllowed(expectedRevocationVersion: Long): Boolean = synchronized(gateLock) {
            allowed && version == expectedRevocationVersion
        }
        override fun dispatchAdd(expectedRevocationVersion: Long,
            actualDispatch: () -> GmsGlobalDozeDispatchedCall): GmsGlobalDozeDispatchedCall? {
            beforeAddDispatch?.invoke()
            return synchronized(gateLock) {
                if (allowed && version == expectedRevocationVersion) actualDispatch() else null
            }
        }
    }

    private class MemoryLegacy : DozeJournalPort {
        var bytes: ByteArray? = null
        @Synchronized override fun load(): ByteArray? = bytes?.clone()
        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (!(bytes?.contentEquals(expected ?: byteArrayOf()) ?: (expected == null))) return false
            DozeJournalCodec.validateTransition(expected, replacement)
            bytes = replacement.clone()
            return true
        }
    }

    private class Device(var observation: GmsGlobalDozeObservation) : GmsGlobalDozeDevicePort {
        var probes = 0
        var adds = 0
        var removes = 0
        var onProbe: ((Int) -> Unit)? = null
        var onAdd: (() -> Unit)? = null
        var onRemove: (() -> Unit)? = null
        var receiptTransform: (GmsGlobalDozeDispatchReceipt) -> GmsGlobalDozeDispatchReceipt = { it }
        override fun probe(): GmsGlobalDozeObservation { probes++; onProbe?.invoke(probes); return observation }
        override fun dispatchAddFixedGms(): GmsGlobalDozeDispatchedCall {
            adds++
            val dispatchIdentity = observation.identity
            observation = observation.copy(rawWhitelist = observation.rawWhitelist + "user,$GMS_GLOBAL_DOZE_PACKAGE,${dispatchIdentity.gms.appId}\n")
            return GmsGlobalDozeDispatchedCall {
                onAdd?.invoke()
                receiptTransform(GmsGlobalDozeDispatchReceipt(GmsGlobalDozeOperation.ADD, dispatchIdentity, true))
            }
        }
        override fun removeFixedGms(): GmsGlobalDozeDispatchReceipt {
            removes++
            val dispatchIdentity = observation.identity
            observation = observation.copy(rawWhitelist = observation.rawWhitelist.replace(
                "user,$GMS_GLOBAL_DOZE_PACKAGE,${dispatchIdentity.gms.appId}\n", ""))
            onRemove?.invoke()
            return receiptTransform(GmsGlobalDozeDispatchReceipt(GmsGlobalDozeOperation.REMOVE, dispatchIdentity, true))
        }
    }

    private inner class Fixture(raw: String = baseline) {
        val device = Device(GmsGlobalDozeObservation(identity(), raw))
        val document = MemoryDocument()
        val legacy = MemoryLegacy()
        val admission = SharedAdmission()
        var restoreAllowed = true
        fun engine() = GmsGlobalDozeEngine(device, document, legacy, admission, fingerprint, { restoreAllowed })
        fun enable() {
            assertEquals(DozeOutcomeCode.NO_WRITE, engine().enable(1000).code)
            device.probes = 0
        }
        fun apply(): String {
            assertEquals(DozeOutcomeCode.APPLIED, engine().apply().code)
            return assertNotNull(document.decoded().transaction).id
        }
    }

    @Test fun explicitConsentFreshApplyAndNamedRestoreAcrossReconstruction() {
        val f = Fixture()
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
        f.enable()
        val id = f.apply()
        assertEquals(baseline + row, f.device.observation.rawWhitelist)
        assertEquals(DozeOutcomeCode.APPLIED, f.engine().inspect().code)
        assertEquals(DozeOutcomeCode.RESTORED, f.engine().restore(id).code)
        assertEquals(baseline, f.device.observation.rawWhitelist)
        assertEquals(1, f.device.adds)
        assertEquals(1, f.device.removes)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().enable(2000).code)
        assertEquals(DozePhase.RESTORED, f.document.decoded().transaction?.phase)
    }

    @Test fun existingSystemOrUserMembershipClaimsNoOwnership() {
        for (source in listOf("system", "user")) {
            val f = Fixture(baseline + "$source,$GMS_GLOBAL_DOZE_PACKAGE,10208\n")
            assertEquals(DozeOutcomeCode.NO_WRITE, f.engine().inspect().code)
            f.enable()
            assertEquals(DozeOutcomeCode.NO_WRITE, f.engine().apply().code)
            assertNull(f.document.decoded().transaction)
            assertEquals(0, f.device.adds)
            assertEquals(0, f.device.removes)
        }
    }

    @Test fun exceptIdleUnknownAndConflictingMembershipBlock() {
        val invalidRaw = listOf(baseline + "system-excidle,$GMS_GLOBAL_DOZE_PACKAGE,10208\n",
            baseline + "user,$GMS_GLOBAL_DOZE_PACKAGE,10209\n", "unknown-format\n")
        for (raw in invalidRaw) {
            val f = Fixture(raw)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().enable(1000).code)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
            assertEquals(0, f.device.adds)
            assertNull(f.document.load())
        }
        val f = Fixture()
        f.enable()
        f.device.observation = f.device.observation.copy(identity = identity().copy(owners = GmsGlobalOwnerEvidence.Unknown))
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().inspect().code)
        assertNull(f.document.decoded().transaction)
    }

    @Test fun installationBindingMappingAndEnvironmentDriftFailBeforeIntent() {
        val originals = identity()
        val invalid = listOf(originals.copy(gms = gms.copy(versionCode = 124)),
            originals.copy(gms = gms.copy(signerSha256 = listOf("e".repeat(64)))),
            originals.copy(guardian = guardian.copy(installation = guardian.installation.copy(lastUpdateTime = 301))),
            originals.copy(guardian = guardian.copy(userId = 999)),
            originals.copy(environment = originals.environment.copy(executionUid = 0)),
            originals.copy(environment = originals.environment.copy(currentUserId = 999)),
            originals.copy(environment = originals.environment.copy(fingerprint = "other")),
            originals.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "d".repeat(64), 10493)),
            originals.copy(owners = GmsGlobalOwnerEvidence.Qualified(2, "d".repeat(64), 10208)))
        for (identity in invalid) {
            val f = Fixture()
            f.enable()
            f.device.observation = f.device.observation.copy(identity = identity)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
            assertNull(f.document.decoded().transaction)
            assertEquals(0, f.device.adds)
        }
        val f = Fixture()
        f.enable()
        f.device.onProbe = { if (it == 2) f.device.observation = f.device.observation.copy(
            identity = originals.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), 10208))) }
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
        assertNull(f.document.decoded().transaction)
    }

    @Test fun offBetweenIntentAndDispatchRetainsIntentAndPreventsAdd() {
        val f = Fixture()
        f.enable()
        f.device.onProbe = { if (it == 3) f.engine().disable() }
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().apply().code)
        assertFalse(f.document.decoded().consent.enabled)
        assertEquals(DozePhase.ADD_INTENT, f.document.decoded().transaction?.phase)
        assertEquals(0, f.device.adds)
        assertEquals(0, f.device.removes)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().enable(2000).code)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
    }

    @Test fun offAfterDispatchResultPersistsWithoutReenablingAndRestoreRemainsExplicit() {
        val f = Fixture()
        f.enable()
        f.device.onAdd = { f.engine().disable() }
        val id = f.apply()
        assertFalse(f.document.decoded().consent.enabled)
        assertEquals(DozePhase.APPLIED, f.document.decoded().transaction?.phase)
        assertEquals(0, f.device.removes)
        f.restoreAllowed = false
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore(id).code)
        f.restoreAllowed = true
        assertEquals(DozeOutcomeCode.RESTORED, f.engine().restore(id).code)
        assertFalse(f.document.decoded().consent.enabled)
    }

    @Test fun offInFinalGateToActualDispatchWindowAtomicallyPreventsAdd() {
        val f = Fixture()
        f.enable()
        val finalGatePassed = CountDownLatch(1)
        val continueToAtomicDispatch = CountDownLatch(1)
        val offFinished = CountDownLatch(1)
        f.admission.beforeAddDispatch = {
            finalGatePassed.countDown()
            check(continueToAtomicDispatch.await(5, TimeUnit.SECONDS))
        }
        var applied: DozeOutcome? = null
        val applyThread = thread { applied = f.engine().apply() }
        assertTrue(finalGatePassed.await(5, TimeUnit.SECONDS))
        val previousVersion = f.admission.revocationVersion()
        val offThread = thread { f.engine().disable(); offFinished.countDown() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (f.admission.revocationVersion() == previousVersion && System.nanoTime() < deadline) Thread.yield()
            assertTrue(f.admission.revocationVersion() > previousVersion)
            assertEquals(0, f.device.adds)
            assertEquals(1L, offFinished.count)
        } finally { continueToAtomicDispatch.countDown(); applyThread.join(5000); offThread.join(5000) }
        assertFalse(applyThread.isAlive || offThread.isAlive)
        assertEquals(DozeOutcomeCode.UNCERTAIN, applied?.code)
        assertEquals(0, f.device.adds)
        assertEquals(0, f.device.removes)
        assertEquals(DozePhase.ADD_INTENT, f.document.decoded().transaction?.phase)
        assertFalse(f.document.decoded().consent.enabled)
    }

    @Test fun concurrentOffClosesGateWhileDispatchedCalleeKeepsAdmission() {
        val f = Fixture()
        f.enable()
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val offComplete = CountDownLatch(1)
        var applied: DozeOutcome? = null
        f.device.onAdd = { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        val applyThread = thread { applied = f.engine().apply() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val previousVersion = f.admission.revocationVersion()
        val offThread = thread { f.engine().disable(); offComplete.countDown() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (f.admission.revocationVersion() == previousVersion && System.nanoTime() < deadline) Thread.yield()
            assertTrue(f.admission.revocationVersion() > previousVersion)
            assertFalse(f.admission.addsAllowed(f.admission.revocationVersion()))
            assertEquals(1L, offComplete.count)
        } finally { exit.countDown(); applyThread.join(5000); offThread.join(5000) }
        assertFalse(applyThread.isAlive || offThread.isAlive)
        assertEquals(DozeOutcomeCode.APPLIED, applied?.code)
        assertFalse(f.document.decoded().consent.enabled)
    }

    @Test fun interruptedOrCorruptSchema2BlocksWithoutChangingLegacyBytes() {
        val legacyIdentity = DozeIdentity(fingerprint, 0, 2000, GMS_GLOBAL_DOZE_PACKAGE, 10208, true,
            setOf(0), -1, setOf(GMS_GLOBAL_DOZE_PACKAGE))
        val intent = DozeRecord(DozeTarget.GMS, legacyIdentity, baseline, null, DozePhase.ADD_INTENT)
        val records = listOf(byteArrayOf(1, 2), DozeJournalCodec.encode(intent),
            DozeJournalCodec.encode(intent.copy(applied = baseline + row, phase = DozePhase.APPLIED)),
            DozeJournalCodec.encode(intent.copy(applied = baseline + row, phase = DozePhase.RESTORE_INTENT)))
        for (raw in records) {
            val f = Fixture()
            f.legacy.bytes = raw.clone()
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().enable(1000).code)
            assertTrue(raw.contentEquals(assertNotNull(f.legacy.load())))
            assertNull(f.document.load())
            assertEquals(0, f.device.adds)
        }
        val f = Fixture()
        val closed = DozeJournalCodec.encode(intent.copy(applied = baseline + row, phase = DozePhase.RESTORED))
        f.legacy.bytes = closed.clone()
        f.enable()
        f.apply()
        assertTrue(closed.contentEquals(assertNotNull(f.legacy.load())))
    }

    @Test fun legacyAppearingAfterIntentBlocksDispatch() {
        val f = Fixture()
        f.enable()
        f.device.onProbe = { if (it == 3) f.legacy.bytes = byteArrayOf(3, 4) }
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().apply().code)
        assertEquals(0, f.device.adds)
        assertEquals(DozePhase.ADD_INTENT, f.document.decoded().transaction?.phase)
    }

    @Test fun corruptDocumentCasRejectionAndReadbackMismatchNeverDispatch() {
        val corrupt = Fixture()
        corrupt.document.setRaw(byteArrayOf(1))
        assertEquals(DozeOutcomeCode.BLOCKED, corrupt.engine().enable(1000).code)
        assertEquals(DozeOutcomeCode.BLOCKED, corrupt.engine().apply().code)
        assertTrue(byteArrayOf(1).contentEquals(corrupt.document.load()))
        val rejected = Fixture()
        rejected.enable()
        rejected.document.rejectCas = true
        assertEquals(DozeOutcomeCode.UNCERTAIN, rejected.engine().apply().code)
        assertNull(rejected.document.decoded().transaction)
        assertEquals(0, rejected.device.adds)
        val mismatched = Fixture()
        mismatched.enable()
        mismatched.document.onCommit = { if (it.transaction != null) mismatched.document.mismatchedReadback = true }
        assertEquals(DozeOutcomeCode.UNCERTAIN, mismatched.engine().apply().code)
        assertEquals(0, mismatched.device.adds)
    }

    @Test fun incompleteLostOrMismatchedDispatchNeverAutomaticallyInverts() {
        val transforms: List<(GmsGlobalDozeDispatchReceipt) -> GmsGlobalDozeDispatchReceipt> = listOf(
            { it.copy(completed = false) }, { it.copy(operation = GmsGlobalDozeOperation.REMOVE) },
            { it.copy(identity = it.identity.copy(environment = it.identity.environment.copy(executionUid = 0))) },
            { it.copy(identity = it.identity.copy(gms = gms.copy(appId = 10209))) })
        for (transform in transforms) {
            val f = Fixture()
            f.enable()
            f.device.receiptTransform = transform
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().apply().code)
            assertEquals(DozePhase.ADD_INTENT, f.document.decoded().transaction?.phase)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore(assertNotNull(f.document.decoded().transaction).id).code)
            assertEquals(1, f.device.adds)
            assertEquals(0, f.device.removes)
        }
        val f = Fixture()
        f.enable()
        f.device.onAdd = { error("callee acknowledgement lost after write") }
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().apply().code)
        assertEquals(0, f.device.removes)
    }

    @Test fun postDispatchExtraRowOrMappingDriftRetainsUncertainIntent() {
        for (drift in listOf<(Device) -> Unit>(
            { it.observation = it.observation.copy(rawWhitelist = it.observation.rawWhitelist + "user,com.unrelated.new,11111\n") },
            { it.observation = it.observation.copy(identity = it.observation.identity.copy(owners = GmsGlobalOwnerEvidence.Unknown)) })) {
            val f = Fixture()
            f.enable()
            f.device.onAdd = { drift(f.device) }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().apply().code)
            assertEquals(DozePhase.ADD_INTENT, f.document.decoded().transaction?.phase)
            assertEquals(0, f.device.removes)
        }
    }

    @Test fun namedRestoreRequiresCompleteAppliedEvidenceAndOriginalInstallation() {
        val f = Fixture()
        f.enable()
        val id = f.apply()
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore("wrong-transaction").code)
        val applied = f.device.observation
        f.device.observation = applied.copy(rawWhitelist = row + baseline)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore(id).code)
        f.device.observation = applied.copy(identity = applied.identity.copy(gms = gms.copy(versionCode = 124)))
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore(id).code)
        assertEquals(0, f.device.removes)
        f.device.observation = applied
        assertEquals(DozeOutcomeCode.RESTORED, f.engine().restore(id).code)
    }

    @Test fun exactBaselineObservedClosesWithoutRemoval() {
        val f = Fixture()
        f.enable()
        val id = f.apply()
        f.device.observation = f.device.observation.copy(rawWhitelist = baseline)
        assertEquals(DozeOutcomeCode.OBSERVED_RESTORED, f.engine().restore(id).code)
        assertEquals(0, f.device.removes)
        assertEquals(DozePhase.OBSERVED_RESTORED, f.document.decoded().transaction?.phase)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().apply().code)
    }

    @Test fun removeFailureOrNonExactBaselineHasNoRetry() {
        for (afterRemove in listOf<() -> Unit>({ error("lost remove acknowledgement") }, {})) {
            val f = Fixture()
            f.enable()
            val id = f.apply()
            f.device.onRemove = {
                afterRemove()
                f.device.observation = f.device.observation.copy(rawWhitelist = baseline.trimEnd())
            }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().restore(id).code)
            assertEquals(DozePhase.RESTORE_INTENT, f.document.decoded().transaction?.phase)
            assertEquals(DozeOutcomeCode.BLOCKED, f.engine().restore(id).code)
            assertEquals(1, f.device.removes)
        }
    }

    @Test fun twoEnginesShareSingleFlightAndExactCas() {
        val f = Fixture()
        f.enable()
        val start = CountDownLatch(1)
        val outcomes = arrayOfNulls<DozeOutcome>(2)
        val engines = listOf(f.engine(), f.engine())
        val workers = engines.mapIndexed { index, engine -> thread {
            check(start.await(5, TimeUnit.SECONDS)); outcomes[index] = engine.apply()
        } }
        start.countDown()
        workers.forEach { it.join(5000) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, outcomes.count { it?.code == DozeOutcomeCode.APPLIED })
        assertEquals(1, outcomes.count { it?.code == DozeOutcomeCode.BLOCKED })
        assertEquals(1, f.device.adds)
        val exact = assertNotNull(f.document.load())
        val next = f.document.decoded().let { it.copy(consent = it.consent.copy(enabled = false,
            epoch = it.consent.epoch + 1, generation = it.consent.generation + 1)) }
        assertTrue(f.document.compareAndSet(exact, GmsGlobalDozeCodec.encode(next)))
        assertFalse(f.document.compareAndSet(exact, GmsGlobalDozeCodec.encode(next)))
    }

    @Test fun legacyOperationUsesTheSameAdmissionUntilActualExit() {
        val f = Fixture()
        f.enable()
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val globalFinished = CountDownLatch(1)
        val legacyThread = thread { f.admission.serialized {
            entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)); f.legacy.bytes = byteArrayOf(4)
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        var outcome: DozeOutcome? = null
        val globalThread = thread { outcome = f.engine().apply(); globalFinished.countDown() }
        try { assertFalse(globalFinished.await(50, TimeUnit.MILLISECONDS)); assertEquals(0, f.device.adds) }
        finally { exit.countDown(); legacyThread.join(5000); globalThread.join(5000) }
        assertEquals(DozeOutcomeCode.BLOCKED, outcome?.code)
        assertEquals(0, f.device.adds)
    }
}
