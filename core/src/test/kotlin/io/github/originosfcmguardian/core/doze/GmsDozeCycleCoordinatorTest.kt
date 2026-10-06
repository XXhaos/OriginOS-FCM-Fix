package io.github.originosfcmguardian.core.doze

import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exact-byte host fake only. Neither simulated lifecycle receipts nor these tests are device evidence. */
private class CycleHostPort : GmsDozeCycleLedgerPort {
    override val control = GmsDozeCycleControl()
    var bytes: ByteArray? = null
    var rejectNext = false
    var throwAfterCommit = false
    var loadCount = 0
    var beforeCas: (() -> Unit)? = null
    override fun load(): ByteArray? { loadCount++; return bytes?.clone() }
    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
        val callback = beforeCas; beforeCas = null; callback?.invoke()
        if (rejectNext) { rejectNext = false; return false }
        if (!(bytes?.contentEquals(expected ?: byteArrayOf()) ?: (expected == null))) return false
        GmsDozeCycleLedgerCodec.validateTransition(expected, replacement)
        bytes = replacement.clone()
        if (throwAfterCommit) throw IllegalStateException("Host simulated uncertain commit")
        return true
    }
    fun ledger() = GmsDozeCycleLedgerCodec.decode(assertNotNull(bytes))
}

private class CycleHostFixture {
    private val identities = V2Fixture()
    val baseline = identities.baseline
    val applied = identities.applied
    val identity = identities.identity
    val port = CycleHostPort()
    val coordinator = GmsDozeCycleCoordinator(port, "v2-host")
    var starts = 0
    fun initialize() = assertNotNull(coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000), identity, true))
    fun baselineObservation() = GmsGlobalDozeObservation(identity, baseline)
    fun appliedObservation() = GmsGlobalDozeObservation(identity, applied)
    fun prepare(nonce: String = "host-add") = assertNotNull(coordinator.prepareAdd(baselineObservation(), nonce))
    fun start(grant: GmsDozeCycleGrant) {
        assertNotNull(coordinator.recordStartIntent(grant.ref))
        assertTrue(coordinator.admitActualStart(grant.ref) { assertEquals(grant.ref.operation, it); starts++ })
    }
    fun result(grant: GmsDozeCycleGrant, raw: String) = GmsDozeCycleResult(grant.ref,
        GmsDozeCycleCleanup(grant.ref, true, true, true, true, true), 0, true, grant.identity, raw)
    fun finish(grant: GmsDozeCycleGrant, raw: String): GmsDozeCycleLedger {
        assertNotNull(coordinator.recordResult(result(grant, raw)))
        assertNotNull(coordinator.recordReleaseAck(GmsDozeCycleReleaseAck(grant.ref)))
        val revoke = assertNotNull(coordinator.requestRevoke(grant.ref))
        val attempt = if (grant.ref.operation == GmsGlobalDozeOperation.ADD) revoke.cycles.last().add else revoke.cycles.last().remove!!
        return assertNotNull(coordinator.recordClosure(GmsDozeCycleClosure(grant.ref,
            GmsDozeCycleClosureKind.REVOKE_ACK, attempt.requestedRevoke)))
    }
    fun settled(): Pair<GmsDozeCycleGrant, GmsDozeCycleLedger> {
        initialize(); val grant = prepare(); start(grant); return grant to finish(grant, applied)
    }
    fun restoreConsent(ledger: GmsDozeCycleLedger = port.ledger()): GmsDozeCycleRestoreConsent {
        val cycle = ledger.cycles.last()
        return GmsDozeCycleRestoreConsent(UUID.randomUUID().toString(), ledger.ledgerId, cycle.cycleId,
            cycle.transactionId, cycle.transactionEpoch, 2000)
    }
}

class GmsDozeCycleCoordinatorTest {
    @Test fun `new explicit consent and preexisting exemption never convert old authority into ownership`() {
        val f = CycleHostFixture()
        assertNull(f.coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000), f.identity, false))
        assertNull(f.port.bytes)
        f.initialize()
        for (source in listOf("system", "user")) {
            val observation = f.baselineObservation().copy(rawWhitelist = f.baseline + "$source,com.google.android.gms,10208\n")
            assertEquals(GmsDozeCycleDecisionKind.NO_WRITE, f.coordinator.inspectFresh(observation).kind)
            assertNull(f.coordinator.prepareAdd(observation, "no-grant-$source"))
        }
        assertTrue(f.port.ledger().cycles.isEmpty())
        assertEquals(GmsDozeCycleDecisionKind.BLOCKED, f.coordinator.inspectFresh(f.baselineObservation().copy(
            rawWhitelist = f.baseline + "system-excidle,com.google.android.gms,10208\n")).kind)
        val invalidConsent = CycleHostFixture()
        assertNull(invalidConsent.coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000, version = 0),
            invalidConsent.identity, true))
        assertNull(invalidConsent.port.bytes)
        val old = CycleHostFixture()
        old.port.bytes = V2Fixture().bytes(DozePhase.APPLIED)
        val unchanged = assertNotNull(old.port.bytes).clone()
        assertNull(old.coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000), old.identity, true))
        assertContentEquals(unchanged, old.port.bytes)
    }

    @Test fun `successful ADD actively retires while ON and reconstructed clean APPLIED never replays`() {
        val f = CycleHostFixture()
        val (grant, settled) = f.settled()
        assertTrue(settled.runningIntent)
        assertEquals(1L, settled.runningGeneration)
        assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, settled.cycles.last().phase)
        assertNotNull(settled.cycles.last().add.result)
        assertNotNull(settled.cycles.last().add.releaseAck)
        assertNotNull(settled.cycles.last().add.closure)
        val reconstructed = GmsDozeCycleCoordinator(f.port, "v2-host")
        val refreshed = f.appliedObservation().copy(identity = f.identity.copy(owners =
            GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), f.identity.gms.appId)))
        assertEquals(GmsDozeCycleDecisionKind.APPLIED_RETAINED, reconstructed.inspectFresh(refreshed).kind)
        assertNull(reconstructed.prepareAdd(refreshed, "replacement"))
        assertNull(reconstructed.recordStartIntent(grant.ref))
        assertFalse(reconstructed.admitActualStart(grant.ref) { error("Replayed completed ADD") })
        assertFalse(f.coordinator.admitActualStart(grant.ref) { error("Reopened retired grant") })
        assertEquals(1, f.starts)
    }

    @Test fun `natural exact baseline retires separately before a distinct qualified cycle grant`() {
        val f = CycleHostFixture()
        val (originalGrant, settled) = f.settled()
        val reconstructed = GmsDozeCycleCoordinator(f.port, "v2-host")
        assertEquals(GmsDozeCycleDecisionKind.NATURAL_BASELINE_AVAILABLE, reconstructed.inspectFresh(f.baselineObservation()).kind)
        assertNull(reconstructed.prepareAdd(f.baselineObservation(), "too-early"))
        val observed = assertNotNull(reconstructed.observeNaturalRestoration(f.baselineObservation()))
        assertEquals(settled.revision + 1, observed.revision)
        assertTrue(observed.runningIntent)
        assertEquals(GmsDozeCyclePhase.OBSERVED_RESTORED, observed.cycles.last().phase)
        assertNull(observed.cycles.last().remove)
        assertNull(observed.cycles.last().restoreConsent)
        assertEquals(settled.cycles.last().add, observed.cycles.last().add)
        val nextOwner = GmsDozeCycleCoordinator(f.port, "v2-host")
        val fresh = f.baselineObservation().copy(identity = f.identity.copy(owners =
            GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), f.identity.gms.appId)))
        val next = assertNotNull(nextOwner.prepareAdd(fresh, "host-new-cycle"))
        assertNotEquals(originalGrant.ref.cycleId, next.ref.cycleId)
        assertNotEquals(originalGrant.ref.transactionId, next.ref.transactionId)
        assertNotEquals(originalGrant.ref.grantId, next.ref.grantId)
        assertNotEquals(originalGrant.ref.key.nonce, next.ref.key.nonce)
        assertEquals(2L, next.ref.transactionEpoch)
        assertEquals(2L, next.ref.key.generation)
        assertEquals(observed.revision + 1, f.port.ledger().revision)
        assertEquals(observed.cycles.single(), f.port.ledger().cycles.first())
        assertNotNull(nextOwner.recordStartIntent(next.ref))
        assertTrue(nextOwner.admitActualStart(next.ref) { assertEquals(GmsGlobalDozeOperation.ADD, it) })

        val drift = CycleHostFixture(); drift.settled()
        val otherRaw = drift.baselineObservation().copy(rawWhitelist = drift.baseline + "user,com.unrelated,11234\n")
        assertNull(drift.coordinator.observeNaturalRestoration(otherRaw))
        assertNull(drift.coordinator.prepareAdd(otherRaw, "drift"))
        val missing = CycleHostFixture(); val (_, missingLedger) = missing.settled()
        val old = missingLedger.cycles.single()
        missing.port.bytes = GmsDozeCycleLedgerCodec.encode(missingLedger.copy(cycles = listOf(old.copy(
            add = old.add.copy(closure = null), phase = GmsDozeCyclePhase.APPLIED_PENDING))))
        val missingOwner = GmsDozeCycleCoordinator(missing.port, "v2-host")
        assertNull(missingOwner.observeNaturalRestoration(missing.baselineObservation()))
        assertNull(missingOwner.prepareAdd(missing.baselineObservation(), "missing-retirement"))
    }

    @Test fun `OFF closes exact actual-start tokens and late results preserve current OFF`() {
        val f = CycleHostFixture(); f.initialize(); val grant = f.prepare()
        assertNotNull(f.coordinator.recordStartIntent(grant.ref))
        val tampered = listOf(grant.ref.copy(operation = GmsGlobalDozeOperation.REMOVE),
            grant.ref.copy(key = GmsDozeBrokerKey("other", grant.ref.key.generation)),
            grant.ref.copy(key = grant.ref.key.copy(generation = 99)),
            grant.ref.copy(transactionId = UUID.randomUUID().toString()), grant.ref.copy(transactionEpoch = 99),
            grant.ref.copy(cycleId = UUID.randomUUID().toString()), grant.ref.copy(ledgerId = UUID.randomUUID().toString()))
        for (ref in tampered) assertFalse(f.coordinator.admitActualStart(ref) { error("Tampered actual-start tuple admitted") })
        assertTrue(f.coordinator.admitActualStart(grant.ref) { f.starts++ })
        val off = assertNotNull(f.coordinator.persistOff())
        assertFalse(off.runningIntent)
        assertEquals(2L, off.runningGeneration)
        val late = f.finish(grant, f.applied)
        assertFalse(late.runningIntent)
        assertEquals(off.runningGeneration, late.runningGeneration)
        assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, late.cycles.last().phase)
        assertNull(f.coordinator.prepareAdd(f.baselineObservation(), "after-off"))
        assertFalse(f.coordinator.admitActualStart(grant.ref) { error("Late result reopened start") })

        val beforeStart = CycleHostFixture(); beforeStart.initialize(); val waiting = beforeStart.prepare()
        assertNotNull(beforeStart.coordinator.recordStartIntent(waiting.ref))
        val sibling = GmsDozeCycleCoordinator(beforeStart.port, "v2-host")
        assertNotNull(sibling.persistOff())
        assertFalse(beforeStart.coordinator.admitActualStart(waiting.ref) { error("OFF lost start boundary") })
        for (ref in tampered) assertFalse(beforeStart.coordinator.admitActualStart(ref) { error("Tampered tuple bypassed OFF") })
        val beforePrepare = CycleHostFixture(); beforePrepare.initialize()
        beforePrepare.port.beforeCas = { assertNotNull(beforePrepare.coordinator.persistOff()) }
        assertNull(beforePrepare.coordinator.prepareAdd(beforePrepare.baselineObservation(), "racing-prepare"))
        assertFalse(beforePrepare.port.ledger().runningIntent)
        assertTrue(beforePrepare.port.ledger().cycles.isEmpty())
        val rejectedOff = CycleHostFixture(); rejectedOff.initialize(); val prepared = rejectedOff.prepare()
        rejectedOff.port.rejectNext = true
        assertNull(rejectedOff.coordinator.persistOff())
        assertTrue(rejectedOff.coordinator.localWritesClosed())
        assertTrue(rejectedOff.port.ledger().runningIntent)
        assertNull(rejectedOff.coordinator.recordStartIntent(prepared.ref))

        // Sibling OFF closes admission immediately, but cannot interleave a result CAS/readback.
        val concurrent = CycleHostFixture(); concurrent.initialize(); val completing = concurrent.prepare(); concurrent.start(completing)
        val concurrentSibling = GmsDozeCycleCoordinator(concurrent.port, "v2-host")
        var offThread: Thread? = null
        var concurrentOff: GmsDozeCycleLedger? = null
        concurrent.port.beforeCas = {
            offThread = thread { concurrentOff = concurrentSibling.persistOff() }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!concurrent.coordinator.localWritesClosed() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(concurrent.coordinator.localWritesClosed())
        }
        val committedResult = assertNotNull(concurrent.coordinator.recordResult(concurrent.result(completing, concurrent.applied)))
        assertTrue(committedResult.runningIntent)
        assertNotNull(offThread).join(2000)
        assertFalse(assertNotNull(concurrentOff).runningIntent)
        assertFalse(concurrent.coordinator.isTerminal())
        assertNotNull(concurrent.port.ledger().cycles.single().add.result)
        assertFalse(concurrent.port.ledger().runningIntent)
    }

    @Test fun `REMOVE requires OFF plus one fresh named transaction consent and consumes it once`() {
        val f = CycleHostFixture(); val (_, settled) = f.settled()
        val consent = f.restoreConsent(settled)
        assertNull(f.coordinator.prepareRemove(consent, f.appliedObservation(), "remove-on"))
        val off = assertNotNull(f.coordinator.persistOff())
        assertNull(off.cycles.last().remove)
        assertNull(off.cycles.last().restoreConsent)
        for (wrong in listOf(consent.copy(transactionId = UUID.randomUUID().toString()),
            consent.copy(transactionEpoch = 99), consent.copy(cycleId = UUID.randomUUID().toString()),
            consent.copy(ledgerId = UUID.randomUUID().toString()), consent.copy(confirmedAtMillis = 1000)))
            assertNull(f.coordinator.prepareRemove(wrong, f.appliedObservation(), "wrong-remove"))
        val remove = assertNotNull(f.coordinator.prepareRemove(consent, f.appliedObservation(), "host-remove"))
        assertEquals(consent, f.port.ledger().cycles.last().restoreConsent)
        assertNull(f.coordinator.prepareRemove(consent, f.appliedObservation(), "reuse-remove"))
        f.start(remove)
        val restored = f.finish(remove, f.baseline)
        assertEquals(GmsDozeCyclePhase.RESTORED, restored.cycles.last().phase)
        assertFalse(restored.runningIntent)
        assertEquals(settled.cycles.single().baseline, restored.cycles.last().baseline)
        assertEquals(settled.cycles.single().applied, restored.cycles.last().applied)
        assertNull(f.coordinator.prepareAdd(f.baselineObservation(), "restore-off"))
        assertNull(f.coordinator.prepareRemove(consent, f.appliedObservation(), "old-tx"))
    }

    @Test fun `intent gaps death alone uncertain CAS capacity and corrupt old bytes never replay`() {
        val f = CycleHostFixture(); f.initialize(); val grant = f.prepare()
        // A reconstructed process has retained bytes but a fresh owner with no old start tokens.
        val reconstructedPort = CycleHostPort().also { it.bytes = assertNotNull(f.port.bytes).clone() }
        val reconstructed = GmsDozeCycleCoordinator(reconstructedPort, "v2-host")
        assertEquals(GmsDozeCycleDecisionKind.BLOCKED, reconstructed.inspectFresh(f.baselineObservation()).kind)
        assertNull(reconstructed.recordStartIntent(grant.ref))
        assertFalse(reconstructed.admitActualStart(grant.ref) { error("Unfinished intent replayed") })
        assertNotNull(f.coordinator.recordClosure(GmsDozeCycleClosure(grant.ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, 0)))
        assertNull(f.coordinator.observeNaturalRestoration(f.baselineObservation()))

        val gap = CycleHostFixture(); gap.initialize(); val pending = gap.prepare(); gap.start(pending)
        assertNotNull(gap.coordinator.recordResult(gap.result(pending, gap.applied)))
        assertNotNull(gap.coordinator.recordClosure(GmsDozeCycleClosure(pending.ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, 0)))
        val gapOwner = GmsDozeCycleCoordinator(gap.port, "v2-host")
        assertEquals(GmsDozeCycleDecisionKind.BLOCKED, gapOwner.inspectFresh(gap.appliedObservation()).kind)
        assertNull(gapOwner.observeNaturalRestoration(gap.baselineObservation()))

        val uncertain = CycleHostFixture(); uncertain.initialize()
        val loads = uncertain.port.loadCount
        uncertain.port.throwAfterCommit = true
        assertNull(uncertain.coordinator.prepareAdd(uncertain.baselineObservation(), "uncertain"))
        assertEquals(loads + 1, uncertain.port.loadCount)
        assertTrue(uncertain.coordinator.isTerminal())
        assertTrue(uncertain.coordinator.localWritesClosed())
        assertNull(uncertain.coordinator.prepareAdd(uncertain.baselineObservation(), "retry"))
        assertEquals(GmsDozeCycleDecisionKind.BLOCKED, uncertain.coordinator.inspectFresh(uncertain.baselineObservation()).kind)
        assertEquals(loads + 1, uncertain.port.loadCount)
        assertEquals(GmsDozeCycleDecisionKind.BLOCKED, GmsDozeCycleCoordinator(uncertain.port, "v2-host").inspectFresh(uncertain.baselineObservation()).kind)

        val full = CycleHostFixture(); val (_, template) = full.settled()
        full.port.bytes = GmsDozeCycleLedgerCodec.encode(atCapacity(template))
        val original = assertNotNull(full.port.bytes).clone()
        assertNull(GmsDozeCycleCoordinator(full.port, "v2-host").prepareAdd(full.baselineObservation(), "over-capacity"))
        assertContentEquals(original, full.port.bytes)
        assertEquals(GmsDozeCycleLedgerCodec.MAX_CYCLES, full.port.ledger().cycles.size)
        for (bytes in listOf(original.clone().also { it[10] = (it[10].toInt() xor 1).toByte() },
            GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState()), V2Fixture().bytes(DozePhase.APPLIED))) {
            val badPort = CycleHostPort().also { it.bytes = bytes }
            assertEquals(GmsDozeCycleDecisionKind.BLOCKED, GmsDozeCycleCoordinator(badPort, "v2-host").inspectFresh(full.baselineObservation()).kind)
            assertContentEquals(bytes, badPort.bytes)
        }
    }

    @Test fun `codec keeps exact history unique tuples strict revisions and cleanup shape`() {
        val f = CycleHostFixture(); val (_, settled) = f.settled()
        val exact = GmsDozeCycleLedgerCodec.encode(settled)
        val decoded = GmsDozeCycleLedgerCodec.decode(exact)
        assertEquals(settled, decoded)
        assertContentEquals(exact, GmsDozeCycleLedgerCodec.encode(decoded))
        exact.fill(0)
        assertEquals(settled, decoded)
        assertFails { (decoded.cycles as MutableList<GmsDozeCycleRecord>).clear() }
        val before = assertNotNull(f.port.bytes).clone()
        val cycle = settled.cycles.single()
        assertFails { GmsDozeCycleLedgerCodec.validateTransition(before, GmsDozeCycleLedgerCodec.encode(settled.copy(revision = settled.revision + 2,
            runningIntent = false, runningGeneration = 2))) }
        val result = assertNotNull(cycle.add.result)
        val incomplete = cycle.copy(add = cycle.add.copy(result = result.copy(cleanup = result.cleanup.copy(collectorsTerminated = false))))
        assertFails { GmsDozeCycleLedgerCodec.encode(settled.copy(cycles = listOf(incomplete))) }
        val wrongRef = cycle.copy(add = cycle.add.copy(result = result.copy(ref = cycle.add.grant.ref.copy(operation = GmsGlobalDozeOperation.REMOVE))))
        assertFails { GmsDozeCycleLedgerCodec.encode(settled.copy(cycles = listOf(wrongRef))) }
        assertFails { GmsDozeCycleLedgerCodec.validateTransition(before, GmsDozeCycleLedgerCodec.encode(settled.copy(revision = settled.revision + 1,
            cycles = emptyList()))) }
        val observed = assertNotNull(f.coordinator.observeNaturalRestoration(f.baselineObservation()))
        val nonceReuseOwner = GmsDozeCycleCoordinator(f.port, "v2-host")
        assertNull(nonceReuseOwner.prepareAdd(f.baselineObservation(), cycle.add.grant.ref.key.nonce))
        assertEquals(observed, f.port.ledger())
    }

    @Test fun `completion headroom refuses a fitting small intent without poisoning or trimming history`() {
        val f = CycleHostFixture(); val (_, template) = f.settled()
        val baseline = (0 until 3200).joinToString("") { "system,com.capacity.p${it.toString().padStart(4, '0')},1000\n" }
        val applied = baseline + "user,com.google.android.gms,10208\n"
        val history = atCapacity(template).cycles.take(13).map { cycle ->
            cycle.copy(baseline = baseline, applied = applied, add = cycle.add.copy(
                grant = cycle.add.grant.copy(baselineSha256 = GmsDozeBrokerV2Codec.rawSha256(baseline)),
                result = cycle.add.result!!.copy(rawWhitelist = applied)))
        }
        val ledger = template.copy(revision = 1000, cycles = history)
        f.port.bytes = GmsDozeCycleLedgerCodec.encode(ledger)
        val ref = GmsDozeCycleAttemptRef(ledger.ledgerId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 14,
            UUID.randomUUID().toString(), GmsGlobalDozeOperation.ADD, GmsDozeBrokerKey("boundary-add", 14))
        val grant = GmsDozeCycleGrant(ref, f.identity, GmsDozeBrokerV2Codec.rawSha256(baseline), null)
        val intent = GmsDozeCycleRecord(ref.cycleId, ref.transactionId, ref.transactionEpoch, baseline, null,
            GmsDozeCyclePhase.ADD_INTENT, GmsDozeCycleAttempt(grant))
        // The tempting bare intent fits, but its maximal legal completion and future REMOVE do not.
        assertTrue(GmsDozeCycleLedgerCodec.encode(ledger.copy(revision = 1001, cycles = history + intent)).size < GmsDozeCycleLedgerCodec.MAX_BYTES)
        val exact = assertNotNull(f.port.bytes).clone()
        val owner = GmsDozeCycleCoordinator(f.port, "v2-host")
        assertNull(owner.prepareAdd(GmsGlobalDozeObservation(f.identity, baseline), "boundary-add"))
        assertFalse(owner.isTerminal())
        assertFalse(owner.localWritesClosed())
        assertContentEquals(exact, f.port.bytes)
        assertEquals(13, f.port.ledger().cycles.size)
        assertFalse(owner.admitActualStart(ref) { error("Capacity refusal admitted actual start") })
        assertNotNull(owner.persistOff())
        val target = assertNotNull(owner.captureOffTarget())
        assertNotNull(owner.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            target, target.runningGeneration + 1), GmsGlobalDozeObservation(f.identity, baseline), owner.captureLocalConsentSequence()))
        assertNull(owner.prepareAdd(GmsGlobalDozeObservation(f.identity, baseline), "boundary-after-header"))
        assertFalse(owner.isTerminal())
        assertEquals(history, f.port.ledger().cycles)
    }

    @Test fun `fresh explicit same ledger reenable retains original consent complete restored history and no old grants`() {
        val f = CycleHostFixture(); val (add, applied) = f.settled()
        assertNotNull(f.coordinator.persistOff())
        val remove = assertNotNull(f.coordinator.prepareRemove(f.restoreConsent(), f.appliedObservation(), "reenable-old-remove"))
        f.start(remove)
        val restored = f.finish(remove, f.baseline)
        val target = assertNotNull(f.coordinator.captureOffTarget())
        val confirmation = GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 3000, target, target.runningGeneration + 1)
        val enabled = assertNotNull(f.coordinator.reenableExplicit(confirmation, f.baselineObservation(), f.coordinator.captureLocalConsentSequence()))
        assertEquals(restored.ledgerId, enabled.ledgerId)
        assertEquals(applied.automaticConsent, enabled.automaticConsent)
        assertEquals(restored.binding, enabled.binding)
        assertEquals(restored.cycles, enabled.cycles)
        assertEquals(3L, enabled.runningGeneration)
        assertEquals(restored.revision + 1, enabled.revision)
        assertEquals(confirmation, enabled.latestReenable)
        assertFalse(f.coordinator.localWritesClosed())
        for (old in listOf(add.ref, remove.ref)) {
            assertNull(f.coordinator.recordStartIntent(old))
            assertFalse(f.coordinator.reserveOutboundStart(old))
            assertFalse(f.coordinator.admitActualStart(old) { error("Prior generation grant reopened") })
        }
        val next = assertNotNull(f.coordinator.prepareAdd(f.baselineObservation(), "reenable-new-cycle"))
        assertNotEquals(add.ref.grantId, next.ref.grantId)
        assertEquals(3L, next.ref.key.generation)
        assertEquals(restored.cycles.single(), f.port.ledger().cycles.first())
        // Historical named REMOVE at 2000 is still valid under new confirmation at 3000.
        assertEquals(2000L, f.port.ledger().cycles.first().restoreConsent!!.confirmedAtMillis)
        f.start(next); f.finish(next, f.applied); assertNotNull(f.coordinator.persistOff())
        assertNull(f.coordinator.prepareRemove(f.restoreConsent().copy(confirmedAtMillis = 3000), f.appliedObservation(), "stale-new-restore"))
        assertNotNull(f.coordinator.prepareRemove(f.restoreConsent().copy(confirmedAtMillis = 4000), f.appliedObservation(), "fresh-new-restore"))

        val retained = CycleHostFixture(); retained.settled(); assertNotNull(retained.coordinator.persistOff())
        val retainedTarget = assertNotNull(retained.coordinator.captureOffTarget())
        val oldCycle = retained.port.ledger().cycles.single()
        assertNotNull(retained.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            retainedTarget, retainedTarget.runningGeneration + 1), retained.appliedObservation(), retained.coordinator.captureLocalConsentSequence()))
        assertEquals(GmsDozeCycleDecisionKind.APPLIED_RETAINED, retained.coordinator.inspectFresh(retained.appliedObservation()).kind)
        assertNull(retained.coordinator.prepareAdd(retained.appliedObservation(), "retained-no-write"))
        assertEquals(oldCycle, retained.port.ledger().cycles.single())

        val preexisting = CycleHostFixture(); preexisting.initialize(); assertNotNull(preexisting.coordinator.persistOff())
        val preTarget = assertNotNull(preexisting.coordinator.captureOffTarget())
        val full = preexisting.baselineObservation().copy(rawWhitelist = preexisting.baseline + "system,com.google.android.gms,10208\n")
        assertNotNull(preexisting.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            preTarget, preTarget.runningGeneration + 1), full, preexisting.coordinator.captureLocalConsentSequence()))
        assertEquals(GmsDozeCycleDecisionKind.NO_WRITE, preexisting.coordinator.inspectFresh(full).kind)
        assertTrue(preexisting.port.ledger().cycles.isEmpty())
    }

    @Test fun `reenable rejects stale unfinished prior schema terminal drift and never overwrites racing OFF`() {
        val stale = CycleHostFixture(); stale.settled(); assertNotNull(stale.coordinator.persistOff())
        val target = assertNotNull(stale.coordinator.captureOffTarget())
        val confirmation = GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000, target, target.runningGeneration + 1)
        val exact = assertNotNull(stale.port.bytes).clone()
        assertNull(stale.coordinator.reenableExplicit(confirmation, stale.appliedObservation(), null))
        for (bad in listOf(confirmation.copy(target = target.copy(revision = target.revision - 1)),
            confirmation.copy(target = target.copy(sha256 = "f".repeat(64))),
            confirmation.copy(target = target.copy(runningGeneration = target.runningGeneration - 1)),
            confirmation.copy(toRunningGeneration = target.runningGeneration), confirmation.copy(confirmedAtMillis = 1000), confirmation.copy(version = 0),
            confirmation.copy(confirmationId = stale.port.ledger().cycles.single().add.grant.ref.grantId)))
            assertNull(stale.coordinator.reenableExplicit(bad, stale.appliedObservation(), stale.coordinator.captureLocalConsentSequence()))
        assertContentEquals(exact, stale.port.bytes)
        val capturedSequence = assertNotNull(stale.coordinator.captureLocalConsentSequence())
        val queuedOff = assertNotNull(stale.coordinator.beginOff())
        assertNull(stale.coordinator.captureOffTarget())
        assertNull(stale.coordinator.reenableExplicit(confirmation, stale.appliedObservation(), capturedSequence))
        assertNotNull(stale.coordinator.persistOff(queuedOff))
        assertNull(stale.coordinator.persistOff(queuedOff))
        // Repeated OFF kept the disk target unchanged, but invalidated the captured dialog sequence.
        assertContentEquals(exact, stale.port.bytes)
        assertNull(stale.coordinator.reenableExplicit(confirmation, stale.appliedObservation(), capturedSequence))
        assertNull(stale.coordinator.reenableExplicit(confirmation, stale.appliedObservation().copy(rawWhitelist = stale.applied + "user,com.raw.drift,11999\n"), stale.coordinator.captureLocalConsentSequence()))
        assertContentEquals(exact, stale.port.bytes)

        val unfinished = CycleHostFixture(); unfinished.initialize(); val old = unfinished.prepare(); assertNotNull(unfinished.coordinator.persistOff())
        val unfinishedTarget = assertNotNull(unfinished.coordinator.captureOffTarget())
        assertNull(unfinished.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            unfinishedTarget, unfinishedTarget.runningGeneration + 1), unfinished.baselineObservation(), unfinished.coordinator.captureLocalConsentSequence()))
        assertFalse(unfinished.coordinator.reserveOutboundStart(old.ref))
        val missing = CycleHostFixture(); missing.initialize(); val pending = missing.prepare(); missing.start(pending)
        assertNotNull(missing.coordinator.recordResult(missing.result(pending, missing.applied)))
        assertNotNull(missing.coordinator.recordReleaseAck(GmsDozeCycleReleaseAck(pending.ref)))
        assertNotNull(missing.coordinator.persistOff())
        val missingTarget = assertNotNull(missing.coordinator.captureOffTarget())
        assertNull(missing.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            missingTarget, missingTarget.runningGeneration + 1), missing.appliedObservation(), missing.coordinator.captureLocalConsentSequence()))
        stale.port.control.failClosed()
        assertNull(stale.coordinator.reenableExplicit(confirmation, stale.appliedObservation(), capturedSequence))
        assertTrue(stale.coordinator.isTerminal())
        val drift = CycleHostFixture(); drift.initialize(); assertNotNull(drift.coordinator.persistOff())
        val driftTarget = assertNotNull(drift.coordinator.captureOffTarget())
        assertNull(drift.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            driftTarget, driftTarget.runningGeneration + 1), drift.baselineObservation().copy(identity = drift.identity.copy(gms = drift.identity.gms.copy(versionCode = 999))), drift.coordinator.captureLocalConsentSequence()))
        val oldSchema = exact.clone()
        java.nio.ByteBuffer.wrap(oldSchema).putInt(4, 1)
        val body = oldSchema.copyOfRange(0, oldSchema.size - 32)
        java.security.MessageDigest.getInstance("SHA-256").digest(body).copyInto(oldSchema, body.size)
        assertFails { GmsDozeCycleLedgerCodec.decode(oldSchema) }

        val race = CycleHostFixture(); race.settled(); assertNotNull(race.coordinator.persistOff())
        val raceTarget = assertNotNull(race.coordinator.captureOffTarget())
        var offThread: Thread? = null; var offResult: GmsDozeCycleLedger? = null
        race.port.beforeCas = {
            offThread = thread { offResult = GmsDozeCycleCoordinator(race.port, "v2-host").persistOff() }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (synchronized(race.port.control.monitor) { race.port.control.outstandingOff == 0 } && System.nanoTime() < deadline) Thread.yield()
            assertTrue(synchronized(race.port.control.monitor) { race.port.control.outstandingOff > 0 })
        }
        val enabled = assertNotNull(race.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 2000,
            raceTarget, raceTarget.runningGeneration + 1), race.appliedObservation(), race.coordinator.captureLocalConsentSequence()))
        assertEquals(3L, enabled.runningGeneration)
        assertTrue(race.coordinator.localWritesClosed())
        assertNotNull(offThread).join(2000)
        assertFalse(assertNotNull(offResult).runningIntent)
        assertEquals(4L, race.port.ledger().runningGeneration)
        assertNull(race.coordinator.prepareAdd(race.baselineObservation(), "race-never-open"))

        val absent = CycleHostFixture()
        val oldAbsentSequence = assertNotNull(absent.coordinator.captureLocalConsentSequence())
        val absentOff = assertNotNull(absent.coordinator.beginOff())
        assertNull(absent.coordinator.captureLocalConsentSequence())
        assertNull(absent.coordinator.persistOff(absentOff)) // Confirmed absence; closure retained locally.
        val newConsent = GmsDozeCycleConsent(UUID.randomUUID().toString(), 2000)
        assertNull(absent.coordinator.initializeExplicitConsent(newConsent, absent.identity, true))
        assertNull(absent.coordinator.initializeExplicitConsent(newConsent, absent.identity, true, oldAbsentSequence))
        val freshAbsentSequence = assertNotNull(absent.coordinator.captureLocalConsentSequence())
        assertNotNull(absent.coordinator.initializeExplicitConsent(newConsent, absent.identity, true, freshAbsentSequence))
        assertFalse(absent.coordinator.localWritesClosed())
        assertTrue(absent.port.ledger().cycles.isEmpty())
    }

    @Test fun `independent automatic confirmation binds exact current snapshot and full consent not future document hash`() {
        val f = CycleHostFixture(); val (_, settled) = f.settled()
        assertNull(f.coordinator.captureAutomaticScope()) // Original finite enable is not automatic permission.
        val target = assertNotNull(f.coordinator.captureAutomaticTarget())
        val consent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 2000, target)
        val sequence = assertNotNull(f.coordinator.captureLocalConsentSequence())
        val exact = assertNotNull(f.port.bytes).clone()
        for (bad in listOf(consent.copy(target = target.copy(revision = target.revision - 1)),
            consent.copy(target = target.copy(sha256 = "f".repeat(64))), consent.copy(target = target.copy(runningGeneration = 99)),
            consent.copy(confirmedAtMillis = 1000), consent.copy(version = 0), consent.copy(confirmationId = settled.automaticConsent.consentId)))
            assertNull(f.coordinator.confirmAutomaticExplicit(bad, f.appliedObservation(), sequence))
        assertContentEquals(exact, f.port.bytes)
        val confirmed = assertNotNull(f.coordinator.confirmAutomaticExplicit(consent, f.appliedObservation(), sequence))
        assertEquals(settled.automaticConsent, confirmed.automaticConsent)
        assertEquals(settled.binding, confirmed.binding)
        assertEquals(settled.cycles, confirmed.cycles)
        assertEquals(settled.runningGeneration, confirmed.runningGeneration)
        assertEquals(settled.revision + 1, confirmed.revision)
        val oldScope = assertNotNull(f.coordinator.captureAutomaticScope())
        assertEquals(consent, oldScope.consent)
        assertEquals(consent.confirmationId, oldScope.confirmationId)
        assertNull(f.coordinator.confirmAutomaticExplicit(consent.copy(confirmationId = UUID.randomUUID().toString(), confirmedAtMillis = 3000),
            f.appliedObservation(), sequence)) // Dialog's old hash cannot authorize a second CAS.
        val newer = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 3000, assertNotNull(f.coordinator.captureAutomaticTarget()))
        assertNotNull(f.coordinator.confirmAutomaticExplicit(newer, f.appliedObservation(), sequence))
        val scope = assertNotNull(f.coordinator.captureAutomaticScope())
        assertNull(f.coordinator.prepareAutomaticAdd(scope, f.appliedObservation(), "auto-retain-no-write"))
        assertNotNull(f.coordinator.observeNaturalRestoration(f.baselineObservation()))
        assertEquals(scope, f.coordinator.captureAutomaticScope()) // Later legitimate CAS changes the document hash.
        assertNotEquals(scope.consent.target.sha256, GmsDozeCycleLedgerCodec.sha256(assertNotNull(f.port.bytes)))
        assertNull(f.coordinator.prepareAutomaticAdd(oldScope, f.baselineObservation(), "old-full-consent"))
        assertNull(f.coordinator.prepareAutomaticAdd(scope.copy(consent = scope.consent.copy(confirmedAtMillis = 3001)),
            f.baselineObservation(), "same-id-gen-tampered-consent"))
        val next = assertNotNull(f.coordinator.prepareAutomaticAdd(scope, f.baselineObservation(), "explicit-auto-new-cycle"))
        assertEquals(GmsGlobalDozeOperation.ADD, next.ref.operation)
        assertNull(f.coordinator.captureAutomaticScope()) // Its own incomplete attempt is not a clean reconstruction.
        f.start(next); f.finish(next, f.applied)
        assertEquals(scope, f.coordinator.captureAutomaticScope())
        assertEquals(newer, f.port.ledger().automaticSessionConsent)
    }

    @Test fun `automatic entry never initializes reenable or REMOVE and OFF generations cannot revive old automatic consent`() {
        val absent = CycleHostFixture()
        val fakeConsent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 2000,
            GmsDozeCycleAutomaticTarget(UUID.randomUUID().toString(), 1, "a".repeat(64), 1))
        assertNull(absent.coordinator.captureAutomaticTarget())
        assertNull(absent.coordinator.captureAutomaticScope())
        assertNull(absent.coordinator.confirmAutomaticExplicit(fakeConsent, absent.baselineObservation(), 0))
        assertNull(absent.coordinator.prepareAutomaticAdd(GmsDozeCycleAutomaticScope(fakeConsent.target.ledgerId, fakeConsent),
            absent.baselineObservation(), "absent-auto"))
        assertNull(absent.port.bytes)
        for (source in listOf("system", "user")) {
            val preexisting = CycleHostFixture(); preexisting.initialize()
            val full = preexisting.baselineObservation().copy(rawWhitelist = preexisting.baseline + "$source,com.google.android.gms,10208\n")
            val consent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 2000, assertNotNull(preexisting.coordinator.captureAutomaticTarget()))
            assertNotNull(preexisting.coordinator.confirmAutomaticExplicit(consent, full, assertNotNull(preexisting.coordinator.captureLocalConsentSequence())))
            assertEquals(GmsDozeCycleDecisionKind.NO_WRITE, preexisting.coordinator.inspectFresh(full).kind)
            assertNull(preexisting.coordinator.prepareAutomaticAdd(assertNotNull(preexisting.coordinator.captureAutomaticScope()), full, "preexisting-auto-$source"))
            assertTrue(preexisting.port.ledger().cycles.isEmpty())
        }
        val f = CycleHostFixture(); f.initialize()
        val consent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 2000, assertNotNull(f.coordinator.captureAutomaticTarget()))
        val sequence = assertNotNull(f.coordinator.captureLocalConsentSequence())
        assertNotNull(f.coordinator.confirmAutomaticExplicit(consent, f.baselineObservation(), sequence))
        val oldScope = assertNotNull(f.coordinator.captureAutomaticScope())
        val grant = assertNotNull(f.coordinator.prepareAutomaticAdd(oldScope, f.baselineObservation(), "auto-generation-one"))
        f.start(grant); f.finish(grant, f.applied)
        val captured = assertNotNull(f.coordinator.captureAutomaticTarget())
        val queued = assertNotNull(f.coordinator.beginOff())
        assertNull(f.coordinator.confirmAutomaticExplicit(GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 3000, captured), f.appliedObservation(), sequence))
        assertNotNull(f.coordinator.persistOff(queued))
        assertEquals(consent, f.port.ledger().automaticSessionConsent)
        assertNull(f.coordinator.captureAutomaticScope())
        assertNull(f.coordinator.prepareAutomaticAdd(oldScope, f.baselineObservation(), "off-auto-never-reenable"))
        val off = assertNotNull(f.coordinator.captureOffTarget())
        assertNotNull(f.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(), 3000, off,
            off.runningGeneration + 1), f.appliedObservation(), f.coordinator.captureLocalConsentSequence()))
        assertEquals(consent, f.port.ledger().automaticSessionConsent) // Historical field retained, not revived.
        assertNull(f.coordinator.captureAutomaticScope())
        assertNull(f.coordinator.prepareAutomaticAdd(oldScope, f.baselineObservation(), "manual-on-old-auto"))
        val freshConsent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 4000, assertNotNull(f.coordinator.captureAutomaticTarget()))
        assertNotNull(f.coordinator.confirmAutomaticExplicit(freshConsent, f.appliedObservation(), assertNotNull(f.coordinator.captureLocalConsentSequence())))
        val scope = assertNotNull(f.coordinator.captureAutomaticScope())
        assertEquals(3L, scope.runningGeneration)
        assertNotNull(f.coordinator.observeNaturalRestoration(f.baselineObservation()))
        val next = assertNotNull(f.coordinator.prepareAutomaticAdd(scope, f.baselineObservation(), "auto-generation-three"))
        val beforeClose = assertNotNull(f.port.bytes).clone()
        f.coordinator.closeGrantNow(next.ref)
        assertContentEquals(beforeClose, f.port.bytes) // Short exact closure is neither persistence nor global OFF.
        assertTrue(f.port.ledger().runningIntent)
        assertNull(f.coordinator.recordStartIntent(next.ref))
        assertFalse(f.coordinator.reserveOutboundStart(next.ref))
        assertNotNull(f.coordinator.requestRevoke(next.ref))
        assertNull(f.coordinator.captureAutomaticTarget())
        assertNull(f.coordinator.captureAutomaticScope())
        assertNull(f.coordinator.confirmAutomaticExplicit(freshConsent.copy(confirmationId = UUID.randomUUID().toString(), confirmedAtMillis = 5000),
            f.baselineObservation(), assertNotNull(f.coordinator.captureLocalConsentSequence())))
        assertNull(f.port.ledger().cycles.last().remove)
        assertNull(f.port.ledger().cycles.last().restoreConsent)
    }

    @Test fun `schema3 automatic header is bounded completion capacity reserved and old formats remain unchanged blocked`() {
        val f = CycleHostFixture(); val (_, template) = f.settled()
        val baseline = (0 until 3200).joinToString("") { "system,com.capacity.p${it.toString().padStart(4, '0')},1000\n" }
        val applied = baseline + "user,com.google.android.gms,10208\n"
        val history = atCapacity(template).cycles.take(13).map { cycle -> cycle.copy(baseline = baseline, applied = applied,
            add = cycle.add.copy(grant = cycle.add.grant.copy(baselineSha256 = GmsDozeBrokerV2Codec.rawSha256(baseline)),
                result = cycle.add.result!!.copy(rawWhitelist = applied))) }
        f.port.bytes = GmsDozeCycleLedgerCodec.encode(template.copy(revision = 1000, cycles = history))
        val before = assertNotNull(f.port.bytes).clone()
        val owner = GmsDozeCycleCoordinator(f.port, "v2-host")
        val consent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), 2000, assertNotNull(owner.captureAutomaticTarget()))
        assertNotNull(owner.confirmAutomaticExplicit(consent, GmsGlobalDozeObservation(f.identity, baseline), assertNotNull(owner.captureLocalConsentSequence())))
        val after = assertNotNull(f.port.bytes).clone()
        assertTrue(after.size - before.size in 1..256)
        val scope = assertNotNull(owner.captureAutomaticScope())
        assertNull(owner.prepareAutomaticAdd(scope, GmsGlobalDozeObservation(f.identity, baseline), "auto-capacity-refused"))
        assertContentEquals(after, f.port.bytes)
        assertFalse(owner.isTerminal())
        assertFalse(owner.localWritesClosed())
        assertEquals(history, f.port.ledger().cycles)
        for (version in listOf(1, 2)) {
            val old = after.clone()
            java.nio.ByteBuffer.wrap(old).putInt(4, version)
            val body = old.copyOfRange(0, old.size - 32)
            java.security.MessageDigest.getInstance("SHA-256").digest(body).copyInto(old, body.size)
            val oldPort = CycleHostPort().also { it.bytes = old.clone() }
            val oldOwner = GmsDozeCycleCoordinator(oldPort, "v2-host")
            assertNull(oldOwner.captureAutomaticScope())
            assertNull(oldOwner.prepareAutomaticAdd(scope, GmsGlobalDozeObservation(f.identity, baseline), "legacy-$version"))
            assertContentEquals(old, oldPort.bytes)
        }
    }

    /** Constructed host history for a capacity predicate, not evidence of 64 real child executions. */
    private fun atCapacity(template: GmsDozeCycleLedger): GmsDozeCycleLedger {
        val original = template.cycles.single()
        val cycles = List(GmsDozeCycleLedgerCodec.MAX_CYCLES) { index ->
            val ref = original.add.grant.ref.copy(cycleId = UUID.randomUUID().toString(), transactionId = UUID.randomUUID().toString(),
                transactionEpoch = index.toLong() + 1, grantId = UUID.randomUUID().toString(), key = GmsDozeBrokerKey("capacity-$index", index.toLong() + 1))
            val result = original.add.result!!
            val attempt = original.add.copy(grant = original.add.grant.copy(ref = ref),
                result = result.copy(ref = ref, cleanup = result.cleanup.copy(ref = ref)),
                releaseAck = GmsDozeCycleReleaseAck(ref), closure = original.add.closure!!.copy(ref = ref))
            original.copy(cycleId = ref.cycleId, transactionId = ref.transactionId, transactionEpoch = ref.transactionEpoch,
                add = attempt, phase = GmsDozeCyclePhase.OBSERVED_RESTORED)
        }
        return template.copy(revision = 1000, cycles = cycles)
    }
}
