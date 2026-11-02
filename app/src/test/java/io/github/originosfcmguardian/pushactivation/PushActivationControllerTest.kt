package io.github.originosfcmguardian.pushactivation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class PushActivationControllerTest {
    private val installation = PushActivationInstallation(10_123, 100, 99, 200, listOf("a".repeat(64)))

    private class Memory : PushActivationStorage {
        var bytes: ByteArray? = null
        var fail = false
        var afterWrite: ((PushActivationSnapshot) -> Unit)? = null
        override fun read(): PushActivationSnapshot? { check(!fail); return bytes?.let(PushActivationCodec::decode) }
        override fun write(value: PushActivationSnapshot) {
            check(!fail); bytes = PushActivationCodec.encode(value); afterWrite?.invoke(value)
        }
        override fun <T> exclusive(action: () -> T): T = synchronized(this) { action() }
    }

    private inner class Port : PushActivationPort {
        var principal = installation
        var conditions = PushActivationReadiness("host-1")
        var masterStarts = 0
        var dispatches = 0
        var result = PushActivationDispatch.SAFE_READ_FAILURE
        var handler: (suspend (() -> Boolean) -> PushActivationDispatch)? = null
        override fun installation() = principal
        override fun readiness() = conditions
        override fun startMaster() { masterStarts++ }
        override fun nowMillis() = 300L
        override suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch {
            if (!admit { dispatches++ }) return PushActivationDispatch.UNKNOWN
            return handler?.invoke(current) ?: result
        }
    }

    private class Owner : AutoCloseable {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        override fun close() { job.cancel() }
    }

    private class Queued : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private suspend fun until(check: () -> Boolean) = withTimeout(2_000) { while (!check()) yield() }

    @Test fun savedMasterOnWithoutExplicitTicketNeverCreatesFcmConsent() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("host", true, true) }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            controller.initialize(); repeat(100) { controller.changed() }
            yield()
            assertEquals(0, port.dispatches); assertEquals(0, port.masterStarts)
            assertNull(memory.read()); assertEquals(PushActivationUiState(), controller.state.value)
        }
    }

    @Test fun explicitPendingStartsMasterAndContinuesOnActualHostWithoutAScreenOrTimer() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("waiting", canStartMaster = true) }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit())
            until { port.masterStarts == 1 }
            assertTrue(controller.state.value.pending); assertEquals(0, port.dispatches)
            repeat(100) { controller.changed() }; yield()
            assertEquals(1, port.masterStarts)
            port.conditions = PushActivationReadiness("actual-session", masterOn = true, ready = true)
            port.handler = {
                assertEquals(PushActivationMode.DISPATCHING, memory.read()?.mode)
                port.conditions = port.conditions.copy(configured = true)
                PushActivationDispatch.CONFIGURED
            }
            controller.changed()
            until { memory.read()?.mode == PushActivationMode.COMPLETE }
            assertEquals(1, port.dispatches); assertFalse(controller.state.value.pending)
        }
    }

    @Test fun pendingSurvivesProcessRestartAndOwnVersionUpdateButNotReinstallation() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val first = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(first.confirmExplicit()); until { memory.read()?.mode == PushActivationMode.PENDING }
        }
        port.principal = PushActivationInstallation(installation.uid, installation.firstInstallTime, 100, 500, installation.signerSha256)
        port.conditions = PushActivationReadiness("saved-master", masterOn = true)
        Owner().use { owner ->
            val restored = PushActivationController(PushActivationStore(memory), port, owner.scope)
            restored.initialize(); until { restored.state.value.pending }
            assertEquals(0, port.dispatches)
            port.principal = PushActivationInstallation(installation.uid, 600, 100, 600, installation.signerSha256)
            restored.changed(); until { restored.state.value.blocked }
            assertEquals(PushActivationReason.IDENTITY_CHANGED, memory.read()?.reason)
            assertEquals(0, port.dispatches)
        }
    }

    @Test fun safeReadFailureWaitsForANewEpochOrForegroundAndCannotHotLoop() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("host", true, true) }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit()); until { port.dispatches == 1 }
            until { memory.read()?.mode == PushActivationMode.PENDING }
            repeat(1_000) { controller.changed() }; yield()
            assertEquals(1, port.dispatches)
            controller.onForeground(); until { port.dispatches == 2 }
            port.conditions = port.conditions.copy(epoch = "new-host")
            controller.changed(); until { port.dispatches == 3 }
            assertTrue(controller.state.value.pending)
        }
    }

    @Test fun coldDispatchingAndUnknownResultNeverReplayEvenOnNewEpochOrForeground() = runBlocking {
        val memory = Memory(); val store = PushActivationStore(memory)
        val pending = store.begin(installation, "bf6f66f8-a14b-42fa-8540-67b00425e89f", 300)
        store.transition(pending, PushActivationMode.DISPATCHING)
        val port = Port().also { it.conditions = PushActivationReadiness("host", true, true) }
        Owner().use { owner ->
            val restored = PushActivationController(store, port, owner.scope)
            restored.initialize(); until { restored.state.value.blocked }
            repeat(100) { restored.onForeground() }; yield()
            assertEquals(0, port.dispatches)
            assertEquals(PushActivationReason.UNKNOWN_RESULT, memory.read()?.reason)
            port.conditions = port.conditions.copy(configured = true)
            restored.changed(); until { memory.read()?.mode == PushActivationMode.COMPLETE }
            assertEquals(0, port.dispatches)
        }
    }

    @Test fun offRetiresImmediatelyAndLateSuccessCannotReplaceTheDurableTombstone() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("host", true, true) }
        val entered = CompletableDeferred<Unit>(); val late = CompletableDeferred<Unit>()
        port.handler = { current ->
            withContext(NonCancellable) {
                entered.complete(Unit); late.await()
                assertFalse(current())
                port.conditions = port.conditions.copy(configured = true)
                PushActivationDispatch.CONFIGURED
            }
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit()); entered.await()
            controller.cancelExplicit()
            assertFalse(controller.state.value.pending); assertFalse(controller.state.value.busy)
            until { memory.read()?.mode == PushActivationMode.RETIRED }
            late.complete(Unit); yield()
            assertEquals(PushActivationMode.RETIRED, memory.read()?.mode)
            assertFalse(controller.state.value.pending)
        }
    }

    @Test fun pendingBeforeMasterOnWaitsButAnObservedMasterOffRetiresIt() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit()); yield()
            assertTrue(controller.state.value.pending)
            port.conditions = PushActivationReadiness("host", masterOn = true)
            controller.changed(); yield()
            port.conditions = port.conditions.copy(masterOn = false)
            controller.changed(); until { memory.read()?.mode == PushActivationMode.RETIRED }
            assertEquals(0, port.dispatches)
        }
    }

    @Test fun explicitPermissionRevocationBlocksAndLaterPermissionDoesNotResumeOldTicket() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit())
            controller.privilegeRevoked(); until { memory.read()?.mode == PushActivationMode.BLOCKED }
            assertEquals(PushActivationReason.PRIVILEGE_REVOKED, memory.read()?.reason)
            port.conditions = PushActivationReadiness("new-permission", true, true)
            controller.onForeground(); yield()
            assertEquals(0, port.dispatches); assertTrue(controller.state.value.blocked)
        }
    }

    @Test fun storageFailureCannotStartMasterOrDispatchAndOffNeverWaitsForStorage() = runBlocking {
        val memory = Memory().also { it.fail = true }; val port = Port().also {
            it.conditions = PushActivationReadiness("host", canStartMaster = true)
        }
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertFalse(controller.confirmExplicit())
            assertTrue(controller.state.value.blocked); assertTrue(controller.state.value.storageFailed)
            controller.onForeground(); yield()
            assertEquals(0, port.masterStarts); assertEquals(0, port.dispatches)
            controller.cancelExplicit(); assertFalse(controller.state.value.pending)
        }
    }

    @Test fun offAfterOnAdmissionButBeforeSaveSchedulingCannotCreateALatePendingTicket() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("host", canStartMaster = true) }
        val dispatcher = Queued(); val job = SupervisorJob()
        try {
            val controller = PushActivationController(PushActivationStore(memory), port, CoroutineScope(job + dispatcher))
            val saved = controller.confirmExplicitAsync()
            controller.cancelExplicit()
            dispatcher.drain()
            assertFalse(saved.await())
            assertEquals(PushActivationMode.RETIRED, memory.read()?.mode)
            assertEquals(0, port.masterStarts); assertEquals(0, port.dispatches)
            assertFalse(controller.state.value.pending)
        } finally { job.cancel(); dispatcher.drain() }
    }

    @Test fun screenAwaitCancellationDoesNotCancelTheProcessOwnedSaveOrContinuation() = runBlocking {
        val memory = Memory(); val port = Port(); val dispatcher = Queued(); val job = SupervisorJob()
        try {
            val controller = PushActivationController(PushActivationStore(memory), port, CoroutineScope(job + dispatcher))
            val screen = launch(start = CoroutineStart.UNDISPATCHED) { controller.confirmExplicit() }
            screen.cancelAndJoin()
            dispatcher.drain()
            assertEquals(PushActivationMode.PENDING, memory.read()?.mode)
            assertTrue(controller.state.value.pending)
        } finally { job.cancel(); dispatcher.drain() }
    }

    @Test fun permissionRevocationAfterPendingWriteBeforePublicationCannotLeaveAReplayableTicket() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            memory.afterWrite = { if (it.mode == PushActivationMode.PENDING) controller.privilegeRevoked() }
            assertFalse(controller.confirmExplicit())
            until { memory.read()?.mode == PushActivationMode.BLOCKED }
            assertEquals(PushActivationReason.PRIVILEGE_REVOKED, memory.read()?.reason)
            assertEquals(0, port.masterStarts); assertEquals(0, port.dispatches)
        }
    }

    @Test fun unknownStartupAndMigrationReadinessNeverBecomesOffOrStorageFailure() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit())
            port.conditions = PushActivationReadiness("local-host", masterOn = true)
            controller.changed(); yield()
            port.conditions = PushActivationReadiness("migration-unavailable", masterKnown = false)
            controller.changed(); yield()
            assertEquals(PushActivationMode.PENDING, memory.read()?.mode)
            assertTrue(controller.state.value.pending); assertFalse(controller.state.value.blocked)
            assertEquals(0, port.dispatches); assertEquals(0, port.masterStarts)
        }
    }

    @Test fun coldPendingAfterOffBeforeAsyncRetirementRunsCannotReenableMaster() = runBlocking {
        val memory = Memory(); val store = PushActivationStore(memory)
        store.begin(installation, "bf6f66f8-a14b-42fa-8540-67b00425e89f", 300)
        val dispatcher = Queued(); val oldJob = SupervisorJob(); val port = Port()
        val old = PushActivationController(store, port, CoroutineScope(oldJob + dispatcher))
        old.cancelExplicit() // Process dies before this queued durable tombstone can run.
        oldJob.cancel()
        port.conditions = PushActivationReadiness("actual-off", canStartMaster = true)
        Owner().use { owner ->
            val restored = PushActivationController(store, port, owner.scope)
            restored.initialize(); until { restored.state.value.blocked }
            assertEquals(PushActivationReason.MASTER_OFF, memory.read()?.reason)
            assertEquals(0, port.masterStarts); assertEquals(0, port.dispatches)
        }
    }

    @Test fun failedRetirementLeavingOldPendingCannotReenableAfterProcessRestart() = runBlocking {
        val memory = Memory(); val store = PushActivationStore(memory); val port = Port()
        Owner().use { owner ->
            val first = PushActivationController(store, port, owner.scope)
            assertTrue(first.confirmExplicit())
            memory.fail = true
            first.cancelExplicit()
            until { first.state.value.storageFailed }
        }
        memory.fail = false
        port.conditions = PushActivationReadiness("actual-off", canStartMaster = true)
        Owner().use { owner ->
            val restored = PushActivationController(store, port, owner.scope)
            restored.initialize(); until { restored.state.value.blocked }
            assertEquals(PushActivationReason.MASTER_OFF, memory.read()?.reason)
            assertEquals(0, port.masterStarts); assertEquals(0, port.dispatches)
        }
    }

    @Test fun replacingAWorkerBeforeItsFirstInstructionDoesNotStrandTheNewExplicitOn() = runBlocking {
        val memory = Memory(); val port = Port().also { it.conditions = PushActivationReadiness("host", canStartMaster = true) }
        val dispatcher = Queued(); val job = SupervisorJob()
        try {
            val controller = PushActivationController(PushActivationStore(memory), port, CoroutineScope(job + dispatcher))
            controller.initialize() // LAZY worker's body is queued but has not run.
            val saved = controller.confirmExplicitAsync()
            dispatcher.drain()
            assertTrue(saved.await())
            assertEquals(1, port.masterStarts); assertTrue(controller.state.value.pending)
        } finally { job.cancel(); dispatcher.drain() }
    }

    @Test fun staleNotificationTokenCannotCancelANewerExplicitOnButCurrentTokenCan() = runBlocking {
        val memory = Memory(); val port = Port()
        Owner().use { owner ->
            val controller = PushActivationController(PushActivationStore(memory), port, owner.scope)
            assertTrue(controller.confirmExplicit())
            val historical = controller.cancellationToken()
            assertTrue(controller.confirmExplicit())
            val newer = memory.read()
            assertFalse(controller.cancelExplicit(historical))
            assertEquals(newer, memory.read()); assertTrue(controller.state.value.pending)
            assertTrue(controller.cancelExplicit(controller.cancellationToken()))
            until { memory.read()?.mode == PushActivationMode.RETIRED }
            assertFalse(controller.state.value.pending)
        }
    }

    @Test fun migrationBeforeAuthorityWriteMayWaitButAWriteBoundaryCannotBeGuessedSafe() {
        assertEquals(PushActivationDispatch.SAFE_READ_FAILURE,
            localActivationResult(false, authorityWriteStarted = false, storageBlocked = false, noAuthority = true))
        assertEquals(PushActivationDispatch.UNKNOWN,
            localActivationResult(false, authorityWriteStarted = true, storageBlocked = false, noAuthority = true))
        assertEquals(PushActivationDispatch.UNKNOWN,
            localActivationResult(false, authorityWriteStarted = false, storageBlocked = false, noAuthority = false))
        assertEquals(PushActivationDispatch.STORAGE_FAILURE,
            localActivationResult(false, authorityWriteStarted = false, storageBlocked = true, noAuthority = true))
        assertEquals(PushActivationDispatch.CONFIGURED,
            localActivationResult(true, authorityWriteStarted = true, storageBlocked = false, noAuthority = false))
    }
}
