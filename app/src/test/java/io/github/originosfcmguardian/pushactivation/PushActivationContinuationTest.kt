package io.github.originosfcmguardian.pushactivation

import io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Production controller/store with synthetic readiness and typed backend results; no Android IO. */
class PushActivationContinuationTest {
    private val installation = PushActivationInstallation(10_123, 100, 103, 200, listOf("a".repeat(64)))
    private data class Epoch(val host: String, val session: Long?)
    private val firstHost = Epoch("first", 7)

    private class Memory : PushActivationStorage {
        private var bytes: ByteArray? = null
        var afterWrite: ((PushActivationSnapshot) -> Unit)? = null
        var failNextAttemptReceipt = false
        override fun read(): PushActivationSnapshot? = synchronized(this) { bytes?.let(PushActivationCodec::decode) }
        override fun write(value: PushActivationSnapshot) {
            synchronized(this) {
                if (failNextAttemptReceipt && value.mode == PushActivationMode.DISPATCHING && value.lastDispatch != null) {
                    failNextAttemptReceipt = false
                    throw IOException("Synthetic receipt write failure")
                }
                bytes = PushActivationCodec.encode(value)
                afterWrite?.invoke(value)
            }
        }
        override fun <T> exclusive(action: () -> T): T = synchronized(this) { action() }
    }

    private inner class Port : PushActivationPort {
        @Volatile var principal = installation
        @Volatile var conditions = PushActivationReadiness(firstHost, masterOn = true, ready = true)
        @Volatile var result = PushActivationDispatch.SAFE_RETRY
        @Volatile var diagnostic: PushActivationAttemptDiagnostic? =
            PushActivationAttemptDiagnostic("SAFE_RETRY", "PREPARATION", "OBSERVATION_UNAVAILABLE", "TIMED_OUT")
        var handler: (suspend (() -> Boolean) -> PushActivationDispatch)? = null
        val dispatches = AtomicInteger()
        val masterStarts = AtomicInteger()
        override fun installation() = principal
        override fun readiness() = conditions
        override fun nowMillis() = 300L
        override fun startMaster() { masterStarts.incrementAndGet() }
        override fun attemptDiagnostic() = diagnostic
        override suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch {
            if (!admit { dispatches.incrementAndGet() }) return PushActivationDispatch.UNKNOWN
            check(current())
            return handler?.invoke(current) ?: result
        }
    }

    private class Owner : AutoCloseable {
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        override fun close() { job.cancel() }
    }

    private suspend fun until(check: () -> Boolean) = withTimeout(2_000) { while (!check()) yield() }

    @Test fun onlyTheExplicitTypedRemoteProofPermitsSafeContinuation() {
        assertEquals(PushActivationDispatch.SAFE_RETRY,
            remoteActivationResult(DaemonFcmCommandOutcome.SAFE_RETRY, configured = false))
        assertEquals(PushActivationDispatch.CONFIGURED,
            remoteActivationResult(DaemonFcmCommandOutcome.APPLIED, configured = true))
        for (outcome in DaemonFcmCommandOutcome.entries.filterNot { it == DaemonFcmCommandOutcome.SAFE_RETRY }) {
            assertEquals(outcome.name, PushActivationDispatch.UNKNOWN, remoteActivationResult(outcome, configured = false))
        }
        for (outcome in listOf(DaemonFcmCommandOutcome.UNKNOWN, DaemonFcmCommandOutcome.REJECTED,
            DaemonFcmCommandOutcome.FAILED)) {
            assertEquals(outcome.name, PushActivationDispatch.UNKNOWN, remoteActivationResult(outcome, configured = true))
        }
    }

    @Test fun safeRetryHasExactlyOneSameHostContinuationAndCanCompleteWithoutAnotherForeground() = runBlocking {
        val memory = Memory(); val port = Port()
        port.handler = {
            assertEquals(PushActivationMode.DISPATCHING, memory.read()?.mode)
            assertEquals(port.dispatches.get(), memory.read()?.recordedDispatches)
            if (port.dispatches.get() == 1) PushActivationDispatch.SAFE_RETRY else {
                port.conditions = port.conditions.copy(configured = true)
                port.diagnostic = PushActivationAttemptDiagnostic("APPLIED", "NONE")
                PushActivationDispatch.CONFIGURED
            }
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { memory.read()?.mode == PushActivationMode.COMPLETE }
            assertEquals(2, port.dispatches.get())
            assertEquals(2, controller.state.value.dispatchesThisProcess)
            assertEquals(2, memory.read()?.recordedDispatches)
            assertEquals(PushActivationDispatch.CONFIGURED, memory.read()?.lastDispatch)
            assertFalse(controller.state.value.pending)
            repeat(100) { controller.changed() }
            assertEquals(2, port.dispatches.get())
            assertEquals(0, port.masterStarts.get())
        }
    }

    @Test fun twoSafeResultsExhaustTheBudgetDespiteOwnBusyUnavailableAndRestoredSameHostEvents() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { port.dispatches.get() == 2 && memory.read()?.mode == PushActivationMode.PENDING }
            val afterTwo = memory.read()
            repeat(100) {
                // Models our own busy/unavailable projection: the observed session disappears,
                // then the exact same actual host becomes ready again. It is not a new epoch.
                port.conditions = port.conditions.copy(epoch = firstHost.copy(session = null), ready = false)
                controller.changed()
                port.conditions = port.conditions.copy(epoch = firstHost, ready = true)
                controller.changed()
            }
            repeat(1_000) { controller.changed() }
            assertEquals(2, port.dispatches.get())
            assertEquals(afterTwo, memory.read())
            assertTrue(controller.state.value.pending)
            assertFalse(controller.state.value.blocked)
            assertEquals(PushActivationDispatch.SAFE_RETRY, controller.state.value.lastDispatch)
        }
    }

    @Test fun aGenuinelyNewReadyHostAndAnActualForegroundEachGetOnlyOneMoreBoundedBatch() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { port.dispatches.get() == 2 && memory.read()?.mode == PushActivationMode.PENDING }
            port.conditions = port.conditions.copy(epoch = Epoch("second", 8))
            controller.changed()
            until { port.dispatches.get() == 4 && memory.read()?.mode == PushActivationMode.PENDING }
            repeat(100) { controller.changed() }
            assertEquals(4, port.dispatches.get())
            controller.onForeground()
            until { port.dispatches.get() == 6 && memory.read()?.mode == PushActivationMode.PENDING }
            repeat(100) { controller.changed() }
            assertEquals(6, port.dispatches.get())
            assertEquals(6, controller.state.value.recordedDispatches)
        }
    }

    @Test fun offAfterTheSafeResultIsDurableWinsBeforeTheContinuationCanBeAdmitted() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            memory.afterWrite = { value ->
                if (value.mode == PushActivationMode.PENDING && value.lastDispatch == PushActivationDispatch.SAFE_RETRY) {
                    memory.afterWrite = null
                    controller.cancelExplicit()
                }
            }
            assertTrue(controller.confirmExplicit())
            until { memory.read()?.mode == PushActivationMode.RETIRED }
            assertEquals(1, port.dispatches.get())
            controller.onForeground()
            port.conditions = port.conditions.copy(epoch = Epoch("second", 8))
            repeat(100) { controller.changed() }
            assertEquals(1, port.dispatches.get())
            assertEquals(PushActivationMode.RETIRED, memory.read()?.mode)
            assertFalse(controller.state.value.pending)
            assertFalse(controller.state.value.busy)
        }
    }

    @Test fun unknownResultStaysBlockedAcrossReadinessForegroundAndOwnVersionUpdate() = runBlocking {
        val memory = Memory(); val port = Port().also {
            it.result = PushActivationDispatch.UNKNOWN
            it.diagnostic = PushActivationAttemptDiagnostic("UNKNOWN", "TIMEOUT")
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { memory.read()?.mode == PushActivationMode.BLOCKED }
            repeat(20) {
                port.conditions = port.conditions.copy(epoch = Epoch("new-$it", it + 20L))
                controller.onForeground()
            }
            assertEquals(1, port.dispatches.get())
            assertEquals(PushActivationReason.UNKNOWN_RESULT, controller.state.value.reason)
        }
        val restoredPort = Port().also {
            it.principal = PushActivationInstallation(installation.uid, installation.firstInstallTime, 104, 400,
                installation.signerSha256)
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), restoredPort, owner.scope, safeRetryDelayMillis = 0)
            controller.initialize()
            until { controller.state.value.blocked }
            controller.onForeground(); repeat(100) { controller.changed() }
            assertEquals(0, restoredPort.dispatches.get())
            assertEquals(PushActivationOrigin.RESTORED, controller.state.value.origin)
            assertEquals(103L, controller.state.value.confirmedVersionCode)
            assertEquals(300L, controller.state.value.confirmedAtEpochMillis)
            assertEquals(1, controller.state.value.recordedDispatches)
            assertEquals(PushActivationDispatch.UNKNOWN, controller.state.value.lastDispatch)
            assertEquals(PushActivationAttemptDiagnostic("UNKNOWN", "TIMEOUT"), controller.state.value.lastAttempt)
        }
    }

    @Test fun attemptReceiptSurvivesCodecAndProcessRecreationWithoutInventingNewAdmissions() = runBlocking {
        val memory = Memory(); val port = Port()
        val receipt = PushActivationAttemptDiagnostic("APPLIED", "NONE")
        port.handler = {
            port.conditions = port.conditions.copy(configured = true)
            port.diagnostic = receipt
            PushActivationDispatch.CONFIGURED
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { memory.read()?.mode == PushActivationMode.COMPLETE }
            assertEquals(PushActivationOrigin.EXPLICIT_THIS_PROCESS, controller.state.value.origin)
            assertEquals(1, controller.state.value.recordedDispatches)
            assertEquals(receipt, controller.state.value.lastAttempt)
        }
        val captured = requireNotNull(memory.read())
        assertEquals(captured, PushActivationCodec.decode(PushActivationCodec.encode(captured)))
        val restoredPort = Port().also { it.conditions = it.conditions.copy(configured = true) }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), restoredPort, owner.scope, safeRetryDelayMillis = 0)
            controller.initialize()
            until { controller.state.value.origin == PushActivationOrigin.RESTORED }
            assertEquals(0, restoredPort.dispatches.get())
            assertEquals(0, controller.state.value.dispatchesThisProcess)
            assertEquals(1, controller.state.value.recordedDispatches)
            assertEquals(PushActivationDispatch.CONFIGURED, controller.state.value.lastDispatch)
            assertEquals(receipt, controller.state.value.lastAttempt)
            assertEquals(captured, memory.read())
        }
    }

    @Test fun failureToPersistTheSafeReceiptCannotAuthorizeAnotherAttempt() = runBlocking {
        val memory = Memory().also { it.failNextAttemptReceipt = true }; val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope, safeRetryDelayMillis = 0)
            assertTrue(controller.confirmExplicit())
            until { controller.state.value.storageFailed }
            repeat(100) { controller.changed() }
            controller.onForeground()
            assertEquals(1, port.dispatches.get())
            assertTrue(controller.state.value.blocked)
            assertEquals(PushActivationReason.STORAGE_UNAVAILABLE, controller.state.value.reason)
            assertEquals(PushActivationMode.BLOCKED, memory.read()?.mode)
        }
    }

    @Test fun coldDispatchingContinuesOnlyWithTheDurablyRecordedSafeProof() = runBlocking {
        for (proof in listOf(PushActivationDispatch.SAFE_RETRY, PushActivationDispatch.SAFE_READ_FAILURE,
            PushActivationDispatch.UNKNOWN, null)) {
            val memory = Memory(); val store = PushActivationStore(memory)
            val pending = store.begin(installation, "2a081761-86a8-4b4a-a2c9-37434948104f", 300)
            val dispatching = requireNotNull(store.transition(pending, PushActivationMode.DISPATCHING))
            if (proof != null) store.recordAttempt(dispatching, proof, null)
            val port = Port().also { candidate ->
                candidate.handler = {
                    candidate.conditions = candidate.conditions.copy(configured = true)
                    candidate.diagnostic = PushActivationAttemptDiagnostic("APPLIED", "NONE")
                    PushActivationDispatch.CONFIGURED
                }
            }
            Owner().use { owner ->
                val controller = PushActivationController(store, port, owner.scope, safeRetryDelayMillis = 0)
                controller.initialize()
                if (proof in setOf(PushActivationDispatch.SAFE_RETRY, PushActivationDispatch.SAFE_READ_FAILURE)) {
                    until { memory.read()?.mode == PushActivationMode.COMPLETE }
                    assertEquals(proof.toString(), 1, port.dispatches.get())
                    assertEquals(2, memory.read()?.recordedDispatches)
                    assertEquals(PushActivationOrigin.RESTORED, controller.state.value.origin)
                } else {
                    until { controller.state.value.blocked }
                    controller.onForeground(); repeat(100) { controller.changed() }
                    assertEquals(proof.toString(), 0, port.dispatches.get())
                    assertEquals(PushActivationReason.UNKNOWN_RESULT, controller.state.value.reason)
                    assertEquals(1, memory.read()?.recordedDispatches)
                }
            }
        }
    }

    @Test fun aColdSavedSafeProofCannotRestartAKnownOffMaster() = runBlocking {
        val memory = Memory(); val store = PushActivationStore(memory)
        val pending = store.begin(installation, "2a081761-86a8-4b4a-a2c9-37434948104f", 300)
        val dispatching = requireNotNull(store.transition(pending, PushActivationMode.DISPATCHING))
        store.recordAttempt(dispatching, PushActivationDispatch.SAFE_RETRY,
            PushActivationAttemptDiagnostic("SAFE_RETRY", "PREPARATION"))
        val port = Port().also { it.conditions = PushActivationReadiness(firstHost,
            masterOn = false, ready = false, canStartMaster = true, masterKnown = true) }
        Owner().use { owner ->
            val controller = PushActivationController(store, port, owner.scope, safeRetryDelayMillis = 0)
            controller.initialize()
            until { controller.state.value.blocked }
            assertEquals(PushActivationReason.MASTER_OFF, controller.state.value.reason)
            assertEquals(0, port.masterStarts.get())
            assertEquals(0, port.dispatches.get())
            assertEquals(1, memory.read()?.recordedDispatches)
        }
    }
}
