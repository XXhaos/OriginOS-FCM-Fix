package io.github.originosfcmguardian.core.doze

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DozeTransactionEngineTest {
    private val fingerprint = "reviewed-fingerprint"
    private val target = DozeTarget.OWNED_DEBUG_TEST
    private val baseline = "system,android,1000\nuser,com.example.other,10123\n"
    private val row = "user,${target.packageName},10493\n"

    private fun identity(
        target: DozeTarget = this.target,
        users: Set<Int> = setOf(0),
        pccId: Int = -1,
    ) = DozeIdentity(
        fingerprint, 0, 2000, target.packageName,
        if (target == DozeTarget.GMS) 10208 else 10493,
        true, users, pccId, setOf(target.packageName),
    )

    private class MemoryJournal : DozeJournalPort {
        private var bytes: ByteArray? = null
        var rejectCas = false
        var mismatchAfterCommit = false
        var commits = 0

        @Synchronized override fun load(): ByteArray? {
            val value = bytes?.clone()
            if (mismatchAfterCommit && commits > 0) return byteArrayOf(1, 2, 3)
            return value
        }

        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (rejectCas || !same(bytes, expected)) return false
            DozeJournalCodec.validateTransition(expected, replacement)
            bytes = replacement.clone()
            commits++
            return true
        }

        @Synchronized fun setRaw(raw: ByteArray) { bytes = raw.clone() }

        private fun same(a: ByteArray?, b: ByteArray?): Boolean = when {
            a == null -> b == null
            b == null -> false
            else -> a.contentEquals(b)
        }
    }

    private class FakeDevice(var observation: DozeObservation) : DozeDevicePort {
        var probes = 0
        var adds = 0
        var removes = 0
        var afterProbe: ((Int) -> Unit)? = null
        var failAddAfterWrite = false
        var failRemoveAfterWrite = false

        override fun probe(): DozeObservation {
            probes++
            afterProbe?.invoke(probes)
            return observation
        }

        override fun add() {
            adds++
            observation = observation.copy(rawWhitelist = observation.rawWhitelist +
                "user,${observation.identity.packageName},${observation.identity.appId}\n")
            if (failAddAfterWrite) error("simulated lost add acknowledgement")
        }

        override fun remove() {
            removes++
            observation = observation.copy(rawWhitelist = observation.rawWhitelist.lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("user,${observation.identity.packageName},") }
                .joinToString("\n", postfix = "\n"))
            if (failRemoveAfterWrite) error("simulated lost remove acknowledgement")
        }
    }

    private fun engine(device: FakeDevice, journal: MemoryJournal, mayContinue: () -> Boolean = { true }) =
        DozeTransactionEngine(device, journal, fingerprint, target, mayContinue)

    @Test fun codecRoundTripForIntent() {
        val record = DozeRecord(target, identity(), baseline, null, DozePhase.ADD_INTENT)
        assertEquals(record, DozeJournalCodec.decode(DozeJournalCodec.encode(record)))
    }

    @Test fun applyAndRestoreAcrossEngineReconstructionPreserveExactBaseline() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.APPLIED, engine(device, journal).apply().code)
        assertEquals(baseline + row, device.observation.rawWhitelist)
        assertEquals(DozePhase.APPLIED, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
        assertEquals(DozeOutcomeCode.RESTORED, engine(device, journal).restore().code)
        assertEquals(baseline, device.observation.rawWhitelist)
        assertEquals(DozePhase.RESTORED, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
        assertEquals(1, device.adds)
        assertEquals(1, device.removes)
        assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).apply().code)
    }

    @Test fun healthyMultiProfileGmsIsNoWriteWithoutJournal() {
        val gms = DozeTarget.GMS
        val raw = baseline + "system-excidle,${gms.packageName},10208\n" +
            "system,${gms.packageName},10208\nuser,${gms.packageName},10208\n"
        val device = FakeDevice(DozeObservation(identity(gms, setOf(0, 999)), raw))
        val journal = MemoryJournal()
        val outcome = DozeTransactionEngine(device, journal, fingerprint).apply()
        assertEquals(DozeOutcomeCode.NO_WRITE, outcome.code)
        assertEquals(0, device.adds)
        assertEquals(null, journal.load())
    }

    @Test fun absentMultiProfileGmsBlocksBeforeJournal() {
        val device = FakeDevice(DozeObservation(identity(DozeTarget.GMS, setOf(0, 999)), baseline))
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.BLOCKED, DozeTransactionEngine(device, journal, fingerprint).apply().code)
        assertEquals(0, device.adds)
        assertEquals(null, journal.load())
    }

    @Test fun exceptIdleOnlyAndMalformedOrConflictingListsBlock() {
        val outputs = listOf(
            baseline + "system-excidle,${target.packageName},10493\n",
            baseline + "unknown,${target.packageName},10493\n",
            baseline + "user,${target.packageName},10493\nuser,${target.packageName},10493\n",
            baseline + "system,android,1001\n",
            "",
            "x".repeat(StrictWhitelist.MAX_RAW_BYTES + 1),
        )
        for (raw in outputs) {
            val device = FakeDevice(DozeObservation(identity(), raw))
            val journal = MemoryJournal()
            assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).apply().code)
            assertEquals(0, device.adds)
            assertEquals(null, journal.load())
        }
    }

    @Test fun identityOrListDriftAfterIntentNeverDispatches() {
        for (drift in listOf<(FakeDevice) -> Unit>(
            { it.observation = it.observation.copy(identity = it.observation.identity.copy(pccId = 5)) },
            { it.observation = it.observation.copy(rawWhitelist = it.observation.rawWhitelist + "user,com.example.new,10124\n") },
        )) {
            val device = FakeDevice(DozeObservation(identity(), baseline))
            device.afterProbe = { count -> if (count == 3) drift(device) }
            val journal = MemoryJournal()
            assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, journal).apply().code)
            assertEquals(0, device.adds)
            assertEquals(DozePhase.ADD_INTENT, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
            assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).restore().code)
        }
    }

    @Test fun failedAddAndMatchingCurrentStateDoNotCreateOwnershipOrRemove() {
        val device = FakeDevice(DozeObservation(identity(), baseline)).also { it.failAddAfterWrite = true }
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, journal).apply().code)
        assertEquals(baseline + row, device.observation.rawWhitelist)
        assertEquals(DozePhase.ADD_INTENT, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
        assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).restore().code)
        assertEquals(0, device.removes)
    }

    @Test fun lostCommitReadbackOrCasRejectionCannotDispatch() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val rejected = MemoryJournal().also { it.rejectCas = true }
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, rejected).apply().code)
        assertEquals(null, rejected.load())
        val mismatched = MemoryJournal().also { it.mismatchAfterCommit = true }
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, mismatched).apply().code)
        assertEquals(0, device.adds)
    }

    @Test fun cancellationAfterDurableIntentBlocksBothWrites() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        var checks = 0
        assertEquals(DozeOutcomeCode.UNCERTAIN,
            engine(device, journal) { ++checks < 3 }.apply().code)
        assertEquals(0, device.adds)
        assertEquals(DozePhase.ADD_INTENT, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
        assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).restore().code)

        val otherDevice = FakeDevice(DozeObservation(identity(), baseline))
        val otherJournal = MemoryJournal()
        assertEquals(DozeOutcomeCode.APPLIED, engine(otherDevice, otherJournal).apply().code)
        var restoreChecks = 0
        assertEquals(DozeOutcomeCode.UNCERTAIN,
            engine(otherDevice, otherJournal) { ++restoreChecks < 2 }.restore().code)
        assertEquals(0, otherDevice.removes)
        assertEquals(DozePhase.RESTORE_INTENT,
            DozeJournalCodec.decode(assertNotNull(otherJournal.load())).phase)
    }

    @Test fun readbackOrRestoreDriftKeepsIntentWithoutInverse() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        device.afterProbe = { count ->
            if (count == 4) device.observation = device.observation.copy(
                rawWhitelist = device.observation.rawWhitelist + "user,com.example.new,10124\n")
        }
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, journal).apply().code)
        assertEquals(0, device.removes)
        assertEquals(DozePhase.ADD_INTENT, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)

        val otherDevice = FakeDevice(DozeObservation(identity(), baseline))
        val otherJournal = MemoryJournal()
        assertEquals(DozeOutcomeCode.APPLIED, engine(otherDevice, otherJournal).apply().code)
        otherDevice.afterProbe = { count ->
            if (count == 7) otherDevice.observation = otherDevice.observation.copy(
                identity = otherDevice.observation.identity.copy(pccId = 5))
        }
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(otherDevice, otherJournal).restore().code)
        assertEquals(0, otherDevice.removes)
        assertEquals(DozePhase.RESTORE_INTENT,
            DozeJournalCodec.decode(assertNotNull(otherJournal.load())).phase)
    }

    @Test fun restoreFailureRetainsIntentAndNeverRetries() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.APPLIED, engine(device, journal).apply().code)
        device.failRemoveAfterWrite = true
        assertEquals(DozeOutcomeCode.UNCERTAIN, engine(device, journal).restore().code)
        assertEquals(DozePhase.RESTORE_INTENT, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
        assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).restore().code)
        assertEquals(1, device.removes)
    }

    @Test fun alreadyBaselineSettlesObservedWithoutRemoval() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        assertEquals(DozeOutcomeCode.APPLIED, engine(device, journal).apply().code)
        device.observation = device.observation.copy(rawWhitelist = baseline)
        val result = engine(device, journal).restore()
        assertEquals(DozeOutcomeCode.OBSERVED_RESTORED, result.code)
        assertEquals(0, device.removes)
        assertEquals(DozePhase.OBSERVED_RESTORED, DozeJournalCodec.decode(assertNotNull(journal.load())).phase)
    }

    @Test fun corruptedOrUnknownJournalPreservedAndBlocks() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        val unknown = byteArrayOf(1, 2, 3, 4)
        journal.setRaw(unknown)
        assertEquals(DozeOutcomeCode.BLOCKED, engine(device, journal).apply().code)
        assertTrue(assertNotNull(journal.load()).contentEquals(unknown))
        assertEquals(0, device.adds)
        assertFailsWith<IllegalArgumentException> { DozeJournalCodec.validate(unknown) }
        val valid = DozeJournalCodec.encode(DozeRecord(target, identity(), baseline, null, DozePhase.ADD_INTENT))
        valid[12] = (valid[12].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { DozeJournalCodec.validate(valid) }
    }

    @Test fun codecRefusesForgedOrIllegalTransitions() {
        val intent = DozeJournalCodec.encode(DozeRecord(target, identity(), baseline, null, DozePhase.ADD_INTENT))
        val applied = DozeJournalCodec.encode(DozeRecord(target, identity(), baseline,
            baseline + row, DozePhase.APPLIED))
        assertFailsWith<IllegalArgumentException> { DozeJournalCodec.validateTransition(null, applied) }
        assertFailsWith<IllegalArgumentException> { DozeJournalCodec.validateTransition(applied, applied) }
        val changed = DozeJournalCodec.encode(DozeRecord(target, identity().copy(appId = 10494),
            baseline, baseline + "user,${target.packageName},10494\n", DozePhase.APPLIED))
        assertFailsWith<IllegalArgumentException> { DozeJournalCodec.validateTransition(intent, changed) }
    }

    @Test fun concurrentEnginesProduceAtMostOneIntentAndOneAdd() {
        val device = FakeDevice(DozeObservation(identity(), baseline))
        val journal = MemoryJournal()
        val start = CountDownLatch(1)
        val results = mutableListOf<DozeOutcomeCode>()
        val workers = List(2) {
            thread {
                start.await()
                val outcome = engine(device, journal).apply()
                synchronized(results) { results += outcome.code }
            }
        }
        start.countDown()
        workers.forEach { assertTrue(it.joinAndDone(5_000)) }
        assertEquals(2, results.size)
        assertTrue(device.adds <= 1)
        assertTrue(journal.commits <= 2)
    }

    private fun Thread.joinAndDone(timeoutMillis: Long): Boolean {
        join(timeoutMillis)
        return !isAlive
    }
}
