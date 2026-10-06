package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Explicit host simulations only. They exercise the real bridge/core, not Android child evidence. */
class GmsDozeCycleBusinessBridgeTest {
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val identity = GmsGlobalDozeIdentity(gms, GmsGlobalGuardianBinding(0, gms.copy(appId = 10493)),
        GmsGlobalEnvironment("fingerprint", 2000, 0), GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), gms.appId))
    private val baseline = "system,android,1000\nuser,com.other.example,11111\n"
    private val applied = baseline + "user,com.google.android.gms,10208\n"
    private val consent = GmsDozeCycleConsent("00000000-0000-0000-0000-000000000001", 1000)

    private class TestPort(initial: ByteArray? = null) : GmsDozeCycleLedgerPort {
        override val control = GmsDozeCycleControl()
        private var bytes = initial?.clone()
        val revisions = mutableListOf<GmsDozeCycleLedger>()
        @Synchronized override fun load() = bytes?.clone()
        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (if (bytes == null || expected == null) bytes != null || expected != null else !bytes!!.contentEquals(expected)) return false
            GmsDozeCycleLedgerCodec.validateTransition(bytes, replacement)
            bytes = replacement.clone()
            revisions += GmsDozeCycleLedgerCodec.decode(replacement)
            return true
        }
    }
    private inner class Fixture(val port: TestPort = TestPort()) : GmsDozeCycleTransport {
        @Volatile var raw = baseline
        var starts = 0
        var binds = 0
        var missingStdout = false
        var blockAt: String? = null
        val entered = CountDownLatch(1)
        val continueChild = CountDownLatch(1)
        val connections = ArrayList<Connection>()
        val bridge = GmsDozeCycleBusinessBridge(port, this, "fingerprint", { true })
        fun ledger() = GmsDozeCycleLedgerCodec.decode(requireNotNull(port.load()))
        override fun bindInert(operation: GmsGlobalDozeOperation): GmsDozeCycleConnection =
            Connection(operation, "host-cycle-${++binds}").also(connections::add)
        override fun findOwn(ref: GmsDozeCycleAttemptRef): GmsDozeCycleConnection? =
            connections.singleOrNull { it.nonce == ref.key.nonce && it.operation == ref.operation }?.also { it.pin(ref) }
        override fun observedDeath(ref: GmsDozeCycleAttemptRef, revokeVersion: Long) =
            connections.singleOrNull { it.reference == ref && it.dead }?.let {
                GmsDozeCycleClosure(ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, revokeVersion)
            }
        private fun barrier(point: String) {
            if (blockAt == point) { entered.countDown(); check(continueChild.await(5, TimeUnit.SECONDS)) }
        }
        inner class Connection(val operation: GmsGlobalDozeOperation, val nonce: String) : GmsDozeCycleConnection {
            val admission = GmsDozeCycleShellAdmission(nonce, operation)
            var reference: GmsDozeCycleAttemptRef? = null
            var dead = false
            private var child: GmsDozeBrokerChild? = null
            private var cleanup: GmsDozeCycleCleanup? = null
            var historicalRelease: GmsDozeCycleTicket? = null
            private val revokeCalls = AtomicInteger()
            private var grantPreparationReserved = false
            override fun hello() = GmsManualBrokerHello(nonce, operation)
            override fun reserveGrantPreparation() { check(!dead); grantPreparationReserved = true }
            override fun pin(ref: GmsDozeCycleAttemptRef) {
                check(grantPreparationReserved)
                check(ref.operation == operation && ref.key.nonce == nonce && (reference == null || reference == ref))
                reference = ref
            }
            override fun probe() = GmsGlobalDozeObservation(identity, raw)
            override fun activate(ticket: GmsDozeCycleTicket) { check(admission.activate(ticket)) }
            override fun start(ticket: GmsDozeCycleTicket) {
                val result = admission.start(ticket) {
                    starts++
                    raw = if (operation == GmsGlobalDozeOperation.ADD) applied else baseline
                    object : GmsDozeBrokerChild {}.also { child = it }
                }
                check(result.status == GmsDozeBrokerStartStatus.STARTED)
            }
            override fun awaitCleanup(ticket: GmsDozeCycleTicket): GmsDozeCycleCommandEvidence {
                barrier("await")
                val facts = requireNotNull(admission.cleanupEvidence(requireNotNull(child), true, !missingStdout, true,
                    !missingStdout, !missingStdout)).also { cleanup = it }
                return GmsDozeCycleCommandEvidence(ticket.ref, facts, identity, 0, true)
            }
            override fun release(ticket: GmsDozeCycleTicket): GmsDozeCycleReleaseAck {
                historicalRelease = ticket
                barrier("release")
                return requireNotNull(admission.release(requireNotNull(child), requireNotNull(cleanup), ticket))
            }
            override fun revoke(ticket: GmsDozeCycleTicket): GmsDozeCycleClosure {
                if (revokeCalls.incrementAndGet() == 1) {
                    if (blockAt == "revoke-death") {
                        barrier("revoke-death")
                        check(!admission.isBusy())
                        dead = true // Explicit host own-death event after the earlier exact ACK.
                        error("Late revoke crossed own retirement")
                    }
                    barrier("revoke")
                }
                return requireNotNull(admission.revoke(ticket))
            }
            override fun destroyOwnAndObserveDeath() {
                check(!admission.isBusy())
                dead = true // Host fixture event, not production Binder proof.
            }
        }
    }

    @Test fun actualBridgeSettlesTwoDistinctCyclesAndNamedOffRestorationWithoutHistoryReplacement() {
        val fixture = Fixture()
        val first = fixture.bridge.applyOnce(consent)
        assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, first.code)
        val cycle1 = fixture.ledger().cycles.single()
        assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, cycle1.phase)
        assertTrue(fixture.ledger().runningIntent)
        assertTrue(fixture.connections.single().dead)
        assertEquals(1, fixture.starts)
        // Natural external restoration is an input observation, never a fixture REMOVE command.
        fixture.raw = baseline
        val second = fixture.bridge.applyOnce()
        assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, second.code)
        val two = fixture.ledger()
        assertEquals(2, two.cycles.size)
        assertEquals(GmsDozeCyclePhase.OBSERVED_RESTORED, two.cycles[0].phase)
        assertEquals(cycle1.copy(phase = GmsDozeCyclePhase.OBSERVED_RESTORED), two.cycles[0])
        assertNotEquals(first.ref, second.ref)
        assertNotEquals(first.ref!!.key.nonce, second.ref!!.key.nonce)
        assertEquals(2L, second.ref.key.generation)
        assertEquals(2, fixture.starts)
        val retire = fixture.port.revisions.indexOfFirst { it.cycles.size == 1 && it.cycles[0].phase == GmsDozeCyclePhase.OBSERVED_RESTORED }
        val append = fixture.port.revisions.indexOfFirst { it.cycles.size == 2 }
        assertTrue(retire >= 0 && append > retire)
        assertEquals(GmsDozeCycleOutcomeCode.OFF, fixture.bridge.off().code)
        val last = fixture.ledger().cycles.last()
        val restoreConsent = GmsDozeCycleRestoreConsent("00000000-0000-0000-0000-000000000099", two.ledgerId,
            last.cycleId, last.transactionId, last.transactionEpoch, 1001)
        assertEquals(GmsDozeCycleOutcomeCode.RESTORED, fixture.bridge.restoreOnce(restoreConsent).code)
        val restored = fixture.ledger()
        assertEquals(GmsDozeCyclePhase.RESTORED, restored.cycles.last().phase)
        assertEquals(3L, restored.cycles.last().remove!!.grant.ref.key.generation)
        assertEquals(baseline, fixture.raw)
        val count = fixture.binds
        assertEquals(GmsDozeCycleOutcomeCode.BLOCKED, fixture.bridge.applyOnce().code)
        assertEquals(count, fixture.binds)
        val existing = Fixture().also { it.raw = applied }
        assertEquals(GmsDozeCycleOutcomeCode.NO_WRITE, existing.bridge.applyOnce(consent).code)
        assertEquals(0, existing.starts)
        assertTrue(existing.ledger().cycles.isEmpty())
    }

    @Test fun offRunsWhileChildWaitOrHistoricReleaseIsBlockedAndLateSettlementPreservesOff() {
        for (point in listOf("await", "release", "revoke", "revoke-death")) {
            val fixture = Fixture().also { it.blockAt = point }
            val outcome = AtomicReference<GmsDozeCycleOutcome?>()
            val worker = thread(name = "cycle-bridge-host-$point") { outcome.set(fixture.bridge.applyOnce(consent)) }
            try {
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
                assertEquals(GmsDozeCycleOutcomeCode.OFF, fixture.bridge.off().code)
                val off = fixture.ledger()
                assertFalse(off.runningIntent)
                assertEquals(2L, off.runningGeneration)
                assertNotNull(off.cycles.single().add.closure)
                // Settlement's first revoke can be held outside the ledger gate while OFF
                // reuses and acknowledges exactly that same version, without a v2 request.
                assertEquals(1L, off.cycles.single().add.requestedRevoke)
                assertEquals(1, fixture.starts)
                if (point == "release") assertTrue(fixture.connections.single().historicalRelease!!.runningIntent)
            } finally { fixture.continueChild.countDown(); worker.join(5000) }
            assertFalse(worker.isAlive)
            assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, outcome.get()?.code)
            assertFalse(fixture.ledger().runningIntent)
            assertEquals(2L, fixture.ledger().runningGeneration)
            assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, fixture.ledger().cycles.single().phase)
            assertTrue(fixture.connections.single().dead)
            assertFalse(fixture.port.control.isTerminal())
            if (point == "revoke-death") assertEquals(GmsDozeCycleClosureKind.REVOKE_ACK,
                fixture.ledger().cycles.single().add.closure!!.kind) // Preserve the earlier immutable ACK.
            assertEquals(GmsDozeCycleOutcomeCode.BLOCKED, fixture.bridge.applyOnce().code)
        }
        assertTrue(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(true, false, false, true, true))
        assertFalse(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(false, false, false, true, true))
        assertFalse(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(true, true, false, true, true))
        assertFalse(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(true, false, true, true, true))
        assertFalse(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(true, false, false, false, true))
        assertFalse(GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(true, false, false, true, false))
    }

    @Test fun missingRealCleanupFactKeepsIntentAndReconstructedOwnerNeverCreatesReplacement() {
        val fixture = Fixture().also { it.missingStdout = true }
        assertEquals(GmsDozeCycleOutcomeCode.UNCERTAIN, fixture.bridge.applyOnce(consent).code)
        assertTrue(fixture.port.control.isTerminal())
        val interrupted = fixture.ledger().cycles.single()
        assertEquals(GmsDozeCyclePhase.ADD_INTENT, interrupted.phase)
        assertTrue(interrupted.add.startIntent)
        assertNull(interrupted.add.result)
        assertNull(interrupted.add.releaseAck)
        assertTrue(fixture.connections.single().admission.isBusy())
        val restart = Fixture(TestPort(fixture.port.load())).also { it.raw = applied }
        assertEquals(GmsDozeCycleOutcomeCode.BLOCKED, restart.bridge.applyOnce().code)
        assertEquals(0, restart.binds)
        assertEquals(0, restart.starts)
        assertArrayEquals(fixture.port.load(), restart.port.load())
        val base = mapOf<String, Any?>("schema" to 3, "nonce" to "n", "code" to "ACK")
        GmsDozeCycleReply.validate(base, "n", "ACK")
        assertThrows(Exception::class.java) { GmsDozeCycleReply.validate(base + ("schema" to 2), "n", "ACK") }
        assertThrows(Exception::class.java) { GmsDozeCycleReply.validate(base + ("schema" to 3L), "n", "ACK") }
        assertThrows(Exception::class.java) { GmsDozeCycleReply.validate(base + ("extra" to true), "n", "ACK") }
        assertThrows(Exception::class.java) { GmsDozeCycleReply.validate(base, "other-nonce", "ACK") }
    }
}
