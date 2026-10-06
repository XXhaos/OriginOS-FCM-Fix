package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real App controller/bridge/core host simulations, never Android Binder/child/device proof. */
class GmsDozeCycleAutomaticControllerTest {
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val identity = GmsGlobalDozeIdentity(gms, GmsGlobalGuardianBinding(0, gms.copy(appId = 10493)),
        GmsGlobalEnvironment("fingerprint", 2000, 0), GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), gms.appId))
    private val baseline = "system,android,1000\nuser,com.other.example,11111\n"
    private val applied = baseline + "user,com.google.android.gms,10208\n"

    private class TestPort(initial: ByteArray? = null) : GmsDozeCycleLedgerPort {
        override val control = GmsDozeCycleControl()
        private var bytes = initial?.clone()
        var afterGrantCas: (() -> Unit)? = null
        private val grantBarrier = AtomicBoolean()
        @Synchronized override fun load() = bytes?.clone()
        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (if (bytes == null || expected == null) bytes != null || expected != null else !bytes!!.contentEquals(expected)) return false
            GmsDozeCycleLedgerCodec.validateTransition(bytes, replacement)
            bytes = replacement.clone()
            val last = GmsDozeCycleLedgerCodec.decode(replacement).cycles.lastOrNull()
            if (last?.phase == GmsDozeCyclePhase.ADD_INTENT && !last.add.startIntent && grantBarrier.compareAndSet(false, true))
                afterGrantCas?.invoke()
            return true
        }
    }
    private inner class Fixture(val approved: Boolean = true, val port: TestPort = TestPort(), automatic: Boolean = true,
        initialize: Boolean = true) : GmsDozeCycleTransport {
        val coordinator = GmsDozeCycleCoordinator(port, "fingerprint")
        val elapsed = AtomicLong(100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bridge = GmsDozeCycleBusinessBridge(port, this, "fingerprint", { approved })
        val controller = GmsDozeCycleAutomaticController(bridge, scope, elapsed::get, { 4000 })
        val binds = AtomicInteger()
        val probes = AtomicInteger()
        val starts = AtomicInteger()
        val destroyed = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val barrierOnce = AtomicBoolean()
        @Volatile var blockAt: String? = null
        @Volatile var raw = baseline
        private val connections = mutableListOf<Connection>()
        init {
            if (initialize) {
                check(coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000), identity, true) != null)
                if (automatic) check(coordinator.confirmAutomaticExplicit(GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(),
                    2000, requireNotNull(coordinator.captureAutomaticTarget())), GmsGlobalDozeObservation(identity, raw),
                    requireNotNull(coordinator.captureLocalConsentSequence())) != null)
            }
        }
        fun ledger() = GmsDozeCycleLedgerCodec.decode(requireNotNull(port.load()))
        fun startOwner(id: String = "host", generation: Long = 0) {
            controller.initialize()
            await { it.loaded }
            controller.attachHost(id)
            controller.hostEligible(id, generation) { true }
        }
        fun due() { elapsed.set(requireNotNull(controller.state.value.nextAttemptAtElapsedMillis)) }
        fun hint(id: String = "host", generation: Long = 0) = controller.hint(id, generation) { true }
        fun await(predicate: (GmsDozeCycleAutomaticUiState) -> Boolean) =
            runBlocking { withTimeout(5000) { controller.state.first { predicate(it) } } }
        fun barrier(point: String) {
            if (blockAt == point && barrierOnce.compareAndSet(false, true)) {
                entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
            }
        }
        override fun bindInert(operation: GmsGlobalDozeOperation): GmsDozeCycleConnection =
            Connection(operation, "auto-host-${binds.incrementAndGet()}").also { synchronized(connections) { connections += it } }
        override fun findOwn(ref: GmsDozeCycleAttemptRef) = synchronized(connections) { connections.singleOrNull { it.ref == ref } }
        override fun observedDeath(ref: GmsDozeCycleAttemptRef, revokeVersion: Long) =
            findOwn(ref)?.takeIf { it.dead }?.let { GmsDozeCycleClosure(ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, revokeVersion) }
        inner class Connection(val operation: GmsGlobalDozeOperation, val nonce: String) : GmsDozeCycleConnection {
            val admission = GmsDozeCycleShellAdmission(nonce, operation)
            var ref: GmsDozeCycleAttemptRef? = null
            var dead = false
            private var reserved = false
            private var child: GmsDozeBrokerChild? = null
            private var cleanup: GmsDozeCycleCleanup? = null
            override fun hello() = GmsManualBrokerHello(nonce, operation)
            override fun reserveGrantPreparation() { check(!dead); reserved = true }
            override fun pin(ref: GmsDozeCycleAttemptRef) { check(reserved && (this.ref == null || this.ref == ref)); this.ref = ref }
            override fun probe(): GmsGlobalDozeObservation { probes.incrementAndGet(); barrier("probe"); return GmsGlobalDozeObservation(identity, raw) }
            override fun activate(ticket: GmsDozeCycleTicket) { check(admission.activate(ticket)) }
            override fun activateAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): Boolean {
                if (!lease.checkEligible()) return false
                return admission.activate(ticket)
            }
            override fun start(ticket: GmsDozeCycleTicket) {
                check(admission.start(ticket) {
                    starts.incrementAndGet(); raw = if (operation == GmsGlobalDozeOperation.ADD) applied else baseline
                    object : GmsDozeBrokerChild {}.also { child = it }
                }.status == GmsDozeBrokerStartStatus.STARTED)
            }
            override fun startAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): GmsDozeCycleAutomaticDispatch {
                barrier("dispatch")
                if (!lease.checkEligible() || !lease.admitDispatch()) return GmsDozeCycleAutomaticDispatch.NOT_DISPATCHED
                val result = admission.start(ticket) {
                    starts.incrementAndGet(); raw = applied
                    object : GmsDozeBrokerChild {}.also { child = it }
                }
                return when (result.status) {
                    GmsDozeBrokerStartStatus.STARTED -> GmsDozeCycleAutomaticDispatch.STARTED
                    GmsDozeBrokerStartStatus.REJECTED -> GmsDozeCycleAutomaticDispatch.REJECTED
                    else -> error("Host simulated unknown start")
                }
            }
            override fun awaitCleanup(ticket: GmsDozeCycleTicket): GmsDozeCycleCommandEvidence {
                barrier("await")
                val facts = requireNotNull(admission.cleanupEvidence(requireNotNull(child), true, true, true, true, true)).also { cleanup = it }
                return GmsDozeCycleCommandEvidence(ticket.ref, facts, identity, 0, true)
            }
            override fun release(ticket: GmsDozeCycleTicket) = requireNotNull(admission.release(requireNotNull(child), requireNotNull(cleanup), ticket))
            override fun revoke(ticket: GmsDozeCycleTicket) = requireNotNull(admission.revoke(ticket))
            override fun destroyOwnAndObserveDeath() { check(!admission.isBusy()); dead = true; destroyed.incrementAndGet() }
        }
    }

    @Test fun actualHostCadenceChargesOnlyAfterSharedSingleflightAndNeverResetsOnReconnectOrCatchesUp() {
        val fixture = Fixture(false).also { it.raw = applied }
        try {
            fixture.controller.initialize()
            fixture.await { it.loaded }
            assertNull(fixture.controller.state.value.nextAttemptAtElapsedMillis)
            fixture.elapsed.set(1_000_000) // Cold saved consent without actual host consumed no wait.
            fixture.controller.attachHost("host")
            fixture.controller.hostEligible("host", 0) { true } // Generation zero is legitimate.
            val coldDeadline = requireNotNull(fixture.controller.state.value.nextAttemptAtElapsedMillis)
            assertEquals(fixture.elapsed.get() + GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS, coldDeadline)
            fixture.hint(); assertEquals(0, fixture.binds.get())
            fixture.controller.hostLost("host", 0)
            fixture.controller.hostEligible("host", 1) { false }
            fixture.elapsed.set(coldDeadline)
            fixture.controller.hint("host", 1) { false }
            assertEquals(0, fixture.binds.get())
            fixture.controller.hostEligible("host", 1) { true }
            assertEquals(coldDeadline, fixture.controller.state.value.nextAttemptAtElapsedMillis)

            // An actual finite UI job holds the SAME business bridge, so auto does not charge.
            fixture.blockAt = "probe"
            val manual = fixture.scope.async { fixture.bridge.applyOnce() }
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            fixture.hint("host", 1)
            fixture.await { !it.busy && it.lastOutcome == "BLOCKED" }
            assertEquals(coldDeadline, fixture.controller.state.value.nextAttemptAtElapsedMillis)
            fixture.proceed.countDown()
            assertEquals(GmsDozeCycleOutcomeCode.NO_WRITE, runBlocking { manual.await() }.code)
            fixture.hint("host", 1)
            fixture.await { !it.busy && it.lastOutcome == "NO_WRITE" }
            assertEquals(coldDeadline + GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS,
                fixture.controller.state.value.nextAttemptAtElapsedMillis)
            assertEquals(0, fixture.starts.get())

            fixture.controller.hostEnded("host")
            fixture.controller.attachHost("replacement")
            fixture.controller.hostEligible("replacement", 0) { true }
            val retainedDeadline = fixture.controller.state.value.nextAttemptAtElapsedMillis
            fixture.controller.hostLost("host", 1) // Stale host cannot affect replacement.
            assertTrue(fixture.controller.state.value.eligible)
            assertEquals(retainedDeadline, fixture.controller.state.value.nextAttemptAtElapsedMillis)
            fixture.elapsed.addAndGet(10 * GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS)
            fixture.raw = baseline
            fixture.hint("replacement", 0)
            fixture.await { !it.busy && it.lastOutcome == "WRITE_NOT_APPROVED" }
            assertEquals(fixture.elapsed.get() + GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS,
                fixture.controller.state.value.nextAttemptAtElapsedMillis)
            assertTrue(fixture.ledger().cycles.isEmpty())
            assertFalse(fixture.port.control.isTerminal())
            val count = fixture.probes.get()
            fixture.hint("replacement", 0); assertEquals(count, fixture.probes.get())
        } finally { fixture.scope.cancel() }
    }

    @Test fun lossBeforeLatePinAndActualDispatchBlocksWithoutFakeCompletionWhileStartedWorkSettlesAndNewCycleIsDistinct() {
        for (point in listOf("cas", "dispatch", "await")) {
            val fixture = Fixture()
            try {
                fixture.startOwner()
                if (point == "cas") fixture.port.afterGrantCas = {
                    fixture.entered.countDown(); check(fixture.proceed.await(5, TimeUnit.SECONDS))
                } else fixture.blockAt = point
                fixture.due(); fixture.hint()
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
                fixture.controller.hostLost("host", 0)
                fixture.controller.hostEnded("host") // FGS end does not cancel application settlement.
                fixture.proceed.countDown()
                fixture.await { !it.busy && it.lastOutcome != null }
                val cycle = fixture.ledger().cycles.last()
                assertTrue(fixture.ledger().runningIntent)
                assertFalse(fixture.port.control.isTerminal())
                assertFalse(fixture.port.control.localWritesClosed()) // Unrelated finite UI not globally OFF.
                assertNotNull(cycle.add.closure)
                if (point != "await") {
                    assertEquals(0, fixture.starts.get())
                    assertEquals(GmsDozeCyclePhase.ADD_INTENT, cycle.phase)
                    assertNull(cycle.add.result); assertNull(cycle.add.releaseAck)
                    assertEquals(point == "dispatch", cycle.add.startIntent)
                    val cold = Fixture(port = TestPort(fixture.port.load()), initialize = false)
                    try { cold.startOwner("cold"); assertTrue(cold.controller.state.value.blocked); cold.hint("cold"); assertEquals(0, cold.binds.get()) }
                    finally { cold.scope.cancel() }
                } else {
                    assertEquals(1, fixture.starts.get())
                    assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, cycle.phase)
                    assertTrue(requireNotNull(cycle.add.result).cleanup.complete())
                    assertNotNull(cycle.add.releaseAck)
                    fixture.controller.attachHost("replacement")
                    fixture.controller.hostEligible("replacement", 0) { true }
                    fixture.controller.hostLost("host", 0)
                    fixture.raw = baseline // Natural exact baseline, never a REMOVE action.
                    fixture.due(); fixture.hint("replacement", 0)
                    fixture.await { !it.busy && it.lastOutcome == "APPLIED_SETTLED" }
                    assertEquals(2, fixture.starts.get())
                    val cycles = fixture.ledger().cycles
                    assertEquals(GmsDozeCyclePhase.OBSERVED_RESTORED, cycles[0].phase)
                    assertNotEquals(cycles[0].add.grant.ref, cycles[1].add.grant.ref)
                }
            } finally { fixture.proceed.countDown(); fixture.scope.cancel() }
        }
        val fixture = Fixture().also { it.blockAt = "await" }
        try {
            fixture.startOwner(); fixture.due()
            val manual = fixture.scope.async { fixture.bridge.applyOnce() }
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            fixture.controller.hostLost("host", 0)
            fixture.proceed.countDown()
            assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, runBlocking { manual.await() }.code)
            assertTrue(fixture.ledger().runningIntent)
        } finally { fixture.proceed.countDown(); fixture.scope.cancel() }
    }

    @Test fun separateExactConsentColdCleanPolicyAndSynchronousGmsOffPreventImplicitRearm() {
        // Finite UI can already know CLEAN ON while the automatic owner's first read has not
        // happened. The exact shared local OFF boundary survives a fast OFF -> explicit ON.
        val beforeInitialRead = Fixture(automatic = false)
        try {
            assertFalse(beforeInitialRead.controller.state.value.loaded)
            beforeInitialRead.controller.gmsOffNow()
            beforeInitialRead.controller.refresh()
            beforeInitialRead.await { it.loaded && it.blocked }
            assertNull(beforeInitialRead.controller.state.value.confirmationTarget) // No product OFF yet.
            assertEquals(GmsDozeCycleOutcomeCode.OFF,
                beforeInitialRead.bridge.off(beforeInitialRead.bridge.closeAddsNow()).code)
            val target = requireNotNull(beforeInitialRead.coordinator.captureOffTarget())
            assertNotNull(beforeInitialRead.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(
                UUID.randomUUID().toString(), 3000, target, target.runningGeneration + 1),
                GmsGlobalDozeObservation(identity, baseline), beforeInitialRead.coordinator.captureLocalConsentSequence()))
            // No intermediate OFF refresh: strict current ON plus advanced core OFF sequence.
            beforeInitialRead.controller.refresh()
            beforeInitialRead.await { it.loaded && !it.blocked && it.confirmationTarget != null }
            assertFalse(beforeInitialRead.controller.state.value.configured)
            assertEquals(0, beforeInitialRead.binds.get())
        } finally { beforeInitialRead.scope.cancel() }
        val finiteOnly = Fixture(automatic = false)
        try {
            finiteOnly.startOwner()
            assertFalse(finiteOnly.controller.state.value.configured)
            finiteOnly.elapsed.addAndGet(10 * GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS)
            finiteOnly.hint(); assertEquals(0, finiteOnly.binds.get())
            val target = requireNotNull(finiteOnly.controller.state.value.confirmationTarget)
            finiteOnly.controller.gmsOffNow()
            finiteOnly.controller.refresh()
            finiteOnly.await { it.loaded && !it.busy }
            finiteOnly.controller.confirm(target)
            finiteOnly.hint()
            assertEquals(0, finiteOnly.binds.get()) // Tiny close-before-product-OFF gap cannot rearm.
            assertNull(finiteOnly.ledger().automaticSessionConsent)
            finiteOnly.bridge.off(finiteOnly.bridge.closeAddsNow())
            finiteOnly.controller.refresh(); finiteOnly.await { it.loaded && it.blocked }
            val offTarget = requireNotNull(finiteOnly.coordinator.captureOffTarget())
            assertNotNull(finiteOnly.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(),
                3000, offTarget, offTarget.runningGeneration + 1), GmsGlobalDozeObservation(identity, baseline),
                finiteOnly.coordinator.captureLocalConsentSequence()))
            finiteOnly.controller.refresh()
            finiteOnly.await { !it.blocked && it.confirmationTarget != null }
            val freshTarget = requireNotNull(finiteOnly.controller.state.value.confirmationTarget)
            assertNotEquals(target, freshTarget)
            finiteOnly.controller.confirm(target); assertEquals(0, finiteOnly.binds.get())
            finiteOnly.controller.confirm(freshTarget)
            finiteOnly.await { !it.busy && it.configured }
            assertEquals(finiteOnly.elapsed.get() + GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS,
                finiteOnly.controller.state.value.nextAttemptAtElapsedMillis)
            assertTrue(finiteOnly.ledger().cycles.isEmpty())
            val cold = Fixture(port = TestPort(finiteOnly.port.load()), initialize = false)
            try {
                cold.startOwner("fresh-host")
                assertTrue(cold.controller.state.value.configured)
                assertEquals(cold.elapsed.get() + GmsDozeCycleAutomaticController.MIN_INTERVAL_MILLIS,
                    cold.controller.state.value.nextAttemptAtElapsedMillis)
                cold.hint("fresh-host"); assertEquals(0, cold.binds.get())
            } finally { cold.scope.cancel() }
            val off = finiteOnly.bridge.off(finiteOnly.bridge.closeAddsNow())
            assertEquals(GmsDozeCycleOutcomeCode.OFF, off.code)
            val target2 = requireNotNull(finiteOnly.coordinator.captureOffTarget())
            assertNotNull(finiteOnly.coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(UUID.randomUUID().toString(),
                5000, target2, target2.runningGeneration + 1), GmsGlobalDozeObservation(identity, baseline),
                finiteOnly.coordinator.captureLocalConsentSequence()))
            assertNotNull(finiteOnly.ledger().automaticSessionConsent) // Provenance retained, not authority.
            val invalidGeneration = Fixture(port = TestPort(finiteOnly.port.load()), initialize = false)
            try { invalidGeneration.startOwner("new-host"); assertFalse(invalidGeneration.controller.state.value.configured); invalidGeneration.hint("new-host"); assertEquals(0, invalidGeneration.binds.get()) }
            finally { invalidGeneration.scope.cancel() }
        } finally { finiteOnly.scope.cancel() }
    }
}
