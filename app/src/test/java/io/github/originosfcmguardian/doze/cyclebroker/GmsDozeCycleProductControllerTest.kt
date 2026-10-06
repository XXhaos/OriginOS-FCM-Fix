package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit host-only simulations: no Android child/Binder or device-write evidence is claimed. */
class GmsDozeCycleProductControllerTest {
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val identity = GmsGlobalDozeIdentity(gms, GmsGlobalGuardianBinding(0, gms.copy(appId = 10493)),
        GmsGlobalEnvironment("fingerprint", 2000, 0), GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), gms.appId))
    private val baseline = "system,android,1000\nuser,com.other.example,11111\n"
    private val applied = baseline + "user,com.google.android.gms,10208\n"
    private val consent = GmsDozeCycleConsent("00000000-0000-0000-0000-000000000001", 1000)

    private class TestPort(initial: ByteArray? = null) : GmsDozeCycleLedgerPort {
        override val control = GmsDozeCycleControl()
        private var bytes = initial?.clone()
        @Synchronized override fun load() = bytes?.clone()
        @Synchronized override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (if (bytes == null || expected == null) bytes != null || expected != null else !bytes!!.contentEquals(expected)) return false
            GmsDozeCycleLedgerCodec.validateTransition(bytes, replacement)
            bytes = replacement.clone()
            return true
        }
    }
    private inner class Fixture(val approved: Boolean, val port: TestPort = TestPort()) : GmsDozeCycleTransport {
        @Volatile var raw = baseline
        @Volatile var starts = 0
        var binds = 0
        var destroyed = 0
        var blockPoint: String? = null
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val blockedProbe = AtomicBoolean(false)
        private val connections = ArrayList<Connection>()
        val bridge = GmsDozeCycleBusinessBridge(port, this, "fingerprint", { approved })
        fun ledger() = GmsDozeCycleLedgerCodec.decode(requireNotNull(port.load()))
        fun controller(scope: CoroutineScope, actualBridge: GmsDozeCycleBusinessBridge = bridge) =
            GmsDozeCycleProductController(actualBridge, scope, { 2000 })
        override fun bindInert(operation: GmsGlobalDozeOperation): GmsDozeCycleConnection =
            Connection(operation, "product-host-${++binds}").also(connections::add)
        override fun findOwn(ref: GmsDozeCycleAttemptRef): GmsDozeCycleConnection? =
            connections.singleOrNull { it.ref == ref }
        override fun observedDeath(ref: GmsDozeCycleAttemptRef, revokeVersion: Long) =
            connections.singleOrNull { it.ref == ref && it.dead }?.let {
                GmsDozeCycleClosure(ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, revokeVersion)
            }
        private fun barrier() { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
        private inner class Connection(val operation: GmsGlobalDozeOperation, val nonce: String) : GmsDozeCycleConnection {
            val admission = GmsDozeCycleShellAdmission(nonce, operation)
            var ref: GmsDozeCycleAttemptRef? = null
            var dead = false
            private var child: GmsDozeBrokerChild? = null
            private var cleanup: GmsDozeCycleCleanup? = null
            private var grantPreparationReserved = false
            override fun hello() = GmsManualBrokerHello(nonce, operation)
            override fun reserveGrantPreparation() { check(!dead); grantPreparationReserved = true }
            override fun pin(ref: GmsDozeCycleAttemptRef) {
                check(grantPreparationReserved && (this.ref == null || this.ref == ref)); this.ref = ref
            }
            override fun probe(): GmsGlobalDozeObservation {
                if (blockPoint == "probe" && blockedProbe.compareAndSet(false, true)) barrier()
                return GmsGlobalDozeObservation(identity, raw)
            }
            override fun activate(ticket: GmsDozeCycleTicket) { check(admission.activate(ticket)) }
            override fun start(ticket: GmsDozeCycleTicket) {
                check(admission.start(ticket) {
                    starts++
                    raw = if (operation == GmsGlobalDozeOperation.ADD) applied else baseline
                    object : GmsDozeBrokerChild {}.also { child = it }
                }.status == GmsDozeBrokerStartStatus.STARTED)
            }
            override fun awaitCleanup(ticket: GmsDozeCycleTicket): GmsDozeCycleCommandEvidence {
                if (blockPoint == "await") barrier()
                val facts = requireNotNull(admission.cleanupEvidence(requireNotNull(child), true, true, true, true, true))
                cleanup = facts
                return GmsDozeCycleCommandEvidence(ticket.ref, facts, identity, 0, true)
            }
            override fun release(ticket: GmsDozeCycleTicket) =
                requireNotNull(admission.release(requireNotNull(child), requireNotNull(cleanup), ticket))
            override fun revoke(ticket: GmsDozeCycleTicket) = requireNotNull(admission.revoke(ticket))
            override fun destroyOwnAndObserveDeath() { check(!admission.isBusy()); dead = true; destroyed++ }
        }
    }
    private fun ownerScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun await(controller: GmsDozeCycleProductController, predicate: (GmsDozeCycleUiState) -> Boolean) =
        runBlocking { withTimeout(5000) { controller.state.first { predicate(it) } } }

    @Test fun shippingGateStopsBeforeGrantWhileQualifiedFullExemptionAndFreshAbsentConfirmationRemainUsable() {
        assertFalse(GmsDozeCycleShippingWriteGate.approved())
        val fixture = Fixture(false).also { it.blockPoint = "probe" }
        val scope = ownerScope()
        try {
            val controller = fixture.controller(scope)
            controller.initialize()
            val stale = requireNotNull(await(controller) { it.canEnable }.onTarget)
            controller.confirmOn(stale)
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            controller.off() // Before initial ledger creation: synchronous core registration wins.
            await(controller) { !it.stopPending && it.lastOutcome == "OFF" }
            assertNull(fixture.port.load())
            fixture.proceed.countDown()
            val reopened = requireNotNull(await(controller) { !it.busy && it.canEnable }.onTarget)
            assertNotEquals(stale.localOffSequence, reopened.localOffSequence)
            assertFalse(fixture.port.control.isTerminal())
            controller.confirmOn(stale)
            assertEquals("BLOCKED", controller.state.value.lastOutcome)
            assertNull(fixture.port.load())
            controller.confirmOn(reopened)
            await(controller) { !it.busy && it.lastOutcome == "WRITE_NOT_APPROVED" }
            assertTrue(fixture.ledger().cycles.isEmpty())
            assertEquals(0, fixture.starts)
            assertEquals(2, fixture.destroyed)
            assertFalse(fixture.port.control.isTerminal())
        } finally { fixture.proceed.countDown(); scope.cancel() }
        for (source in listOf("system", "user")) {
            val full = Fixture(false).also { it.raw = baseline + "$source,com.google.android.gms,10208\n" }
            assertEquals(GmsDozeCycleOutcomeCode.NO_WRITE, full.bridge.applyOnce(consent).code)
            assertEquals(0, full.starts)
            assertTrue(full.ledger().cycles.isEmpty())
            assertFalse(full.port.control.isTerminal())
        }
        val owned = Fixture(true)
        assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, owned.bridge.applyOnce(consent).code)
        assertEquals(GmsDozeCycleOutcomeCode.OFF, owned.bridge.off().code)
        val closed = GmsDozeCycleBusinessBridge(owned.port, owned, "fingerprint", GmsDozeCycleShippingWriteGate::approved)
        val restoreScope = ownerScope()
        try {
            val controller = owned.controller(restoreScope, closed)
            controller.initialize()
            val target = requireNotNull(await(controller) { it.canRestore }.restoreTarget)
            val before = owned.port.load()
            controller.confirmRestore(target)
            await(controller) { !it.busy && it.lastOutcome == "WRITE_NOT_APPROVED" }
            assertArrayEquals(before, owned.port.load())
            assertNull(owned.ledger().cycles.single().remove)
            assertEquals(1, owned.starts)
            assertFalse(owned.port.control.isTerminal())
        } finally { restoreScope.cancel() }
    }

    @Test fun exactRestoreAndOnDialogTargetsRejectChangedGenerationHashAndRepeatedOffWithoutReplacingHistory() {
        val fixture = Fixture(true)
        assertEquals(GmsDozeCycleOutcomeCode.APPLIED_SETTLED, fixture.bridge.applyOnce(consent).code)
        assertEquals(GmsDozeCycleOutcomeCode.OFF, fixture.bridge.off().code)
        val original = fixture.ledger()
        val scope = ownerScope()
        try {
            val controller = fixture.controller(scope)
            controller.initialize()
            val state = await(controller) { it.canEnable && it.canRestore }
            val oldRestore = requireNotNull(state.restoreTarget)
            val oldOn = requireNotNull(state.onTarget)
            controller.confirmOn(oldOn)
            await(controller) { !it.busy && it.lastOutcome == "APPLIED_RETAINED" }
            assertEquals(3L, fixture.ledger().runningGeneration)
            assertEquals(original.automaticConsent, fixture.ledger().automaticConsent)
            assertEquals(original.binding, fixture.ledger().binding)
            assertEquals(original.cycles, fixture.ledger().cycles)
            assertEquals(1, fixture.starts)
            controller.off()
            val stopped = await(controller) { !it.stopPending && it.canRestore }
            assertEquals(4L, fixture.ledger().runningGeneration)
            assertNotEquals(oldRestore, stopped.restoreTarget)
            val unchanged = fixture.port.load()
            controller.confirmRestore(oldRestore)
            assertEquals("BLOCKED", controller.state.value.lastOutcome)
            controller.confirmOn(oldOn)
            assertEquals("BLOCKED", controller.state.value.lastOutcome)
            assertArrayEquals(unchanged, fixture.port.load())
            val beforeRepeat = requireNotNull(controller.state.value.onTarget)
            controller.off() // Durable OFF unchanged, but the exact local OFF sequence advances.
            val repeated = await(controller) { !it.stopPending && it.onTarget != null && it.onTarget != beforeRepeat }
            val repeatedTarget = requireNotNull(repeated.onTarget)
            assertEquals(beforeRepeat.revision, repeatedTarget.revision)
            assertNotEquals(beforeRepeat.localOffSequence, repeatedTarget.localOffSequence)
            controller.confirmOn(beforeRepeat)
            assertEquals("BLOCKED", controller.state.value.lastOutcome)
            assertArrayEquals(unchanged, fixture.port.load())
            // Controller uses an actual fresh later wall time for the separately named restoration.
            val restoreController = GmsDozeCycleProductController(fixture.bridge, scope, { 3000 })
            restoreController.initialize()
            val fresh = requireNotNull(await(restoreController) { it.canRestore }.restoreTarget)
            restoreController.confirmRestore(fresh)
            await(restoreController) { !it.busy && it.lastOutcome == "RESTORED" }
            assertEquals(GmsDozeCyclePhase.RESTORED, fixture.ledger().cycles.single().phase)
            assertEquals(2, fixture.starts)
            assertEquals(original.cycles.single().add, fixture.ledger().cycles.single().add)
        } finally { scope.cancel() }
    }

    @Test fun applicationOwnedSingleFlightSurvivesUiDetachAndLatePublicationCannotReplaceOffOrReplayColdIntent() {
        val fixture = Fixture(true).also { it.blockPoint = "await" }
        val owner = ownerScope()
        val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = fixture.controller(owner)
            val collection = ui.launch { controller.state.collect {} }
            controller.initialize()
            controller.confirmOn(requireNotNull(await(controller) { it.canEnable }.onTarget))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            val beforeOff = fixture.bridge.savedSnapshot()
            val interruptedBytes = fixture.port.load()
            collection.cancel()
            ui.cancel() // Detach only: the submitted owner task remains alive.
            controller.runOnce()
            assertEquals(1, fixture.starts)
            controller.off()
            assertTrue(fixture.port.control.localWritesClosed())
            await(controller) { !it.stopPending && !it.runningIntent && it.busy }
            // Deterministically exercise the private presentation race: an older ON snapshot
            // arrives after OFF already published. This is not an execution-proof injection.
            val publish = GmsDozeCycleProductController::class.java.getDeclaredMethod("publish",
                GmsDozeCycleSavedSnapshot::class.java, GmsDozeCycleOutcome::class.java,
                Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            publish.isAccessible = true
            publish.invoke(controller, beforeOff, GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.APPLIED_SETTLED), 0L, true)
            assertFalse(controller.state.value.runningIntent)
            assertFalse(controller.state.value.canApplyOnce)
            assertEquals("OFF", controller.state.value.lastOutcome)
            fixture.proceed.countDown()
            await(controller) { !it.busy && !it.stopPending }
            assertFalse(controller.state.value.runningIntent)
            assertEquals("OFF", controller.state.value.lastOutcome)
            assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, fixture.ledger().cycles.single().phase)
            assertEquals(1, fixture.starts)
            assertFalse(fixture.port.control.isTerminal())
            val cold = Fixture(true, TestPort(interruptedBytes)).also { it.raw = applied }
            val coldController = cold.controller(owner)
            coldController.initialize()
            val state = await(coldController) { it.loadState == GmsDozeCycleUiLoadState.CLEAN }
            assertFalse(state.canEnable)
            assertFalse(state.canApplyOnce)
            assertFalse(state.canRestore)
            coldController.runOnce()
            assertEquals(0, cold.binds)
            assertEquals(0, cold.starts)
            assertArrayEquals(interruptedBytes, cold.port.load())
        } finally { fixture.proceed.countDown(); ui.cancel(); owner.cancel() }
    }
}
