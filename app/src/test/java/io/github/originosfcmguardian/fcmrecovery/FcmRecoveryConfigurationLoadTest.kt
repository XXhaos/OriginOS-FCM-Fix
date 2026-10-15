package io.github.originosfcmguardian.fcmrecovery

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FcmRecoveryConfigurationLoadTest {
    private class Storage(var value: FcmRecoverySnapshot) : FcmRecoveryStorage {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failRead = false
        var writes = 0
        override fun read(): FcmRecoverySnapshot {
            val captured = value
            val fail = failRead
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (fail) error("controlled read failure")
            return captured
        }
        override fun write(value: FcmRecoverySnapshot) { writes++; this.value = value }
        override fun markConsumed(value: FcmRecoverySnapshot) = Unit
        override fun clearConsumed() = Unit
        override fun halt() = Unit
        override fun releaseHalt() = Unit
    }
    private val runtime = FcmRecoveryRuntime
    private fun field(name: String): Any = runtime.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(runtime)
    }
    private fun enabled() = field("enabled") as AtomicBoolean
    @Suppress("UNCHECKED_CAST")
    private fun mutable() = field("mutable") as MutableStateFlow<FcmRecoveryUiState>
    private var previousEnabled = false
    private lateinit var previousState: FcmRecoveryUiState
    private val previousGenerations = mutableMapOf<String, Long>()
    private val previousCycleFields = mutableMapOf<String, Any?>()
    private fun transition(name: String, value: FcmRecoverySnapshot? = null) {
        val method = if (value == null) runtime.javaClass.getDeclaredMethod(name)
            else runtime.javaClass.getDeclaredMethod(name, FcmRecoverySnapshot::class.java)
        method.isAccessible = true
        if (value == null) method.invoke(runtime) else method.invoke(runtime, value)
    }
    @Before fun reset() {
        previousEnabled = enabled().get(); previousState = mutable().value
        listOf("revision", "configurationRevision", "loadGeneration").forEach { name ->
            (field(name) as AtomicLong).let { previousGenerations[name] = it.get(); it.set(0) }
        }
        listOf("eligible", "lastObservationKey", "lastEligibility").forEach { name ->
            runtime.javaClass.getDeclaredField(name).let {
                it.isAccessible = true; previousCycleFields[name] = it.get(runtime)
            }
        }
        enabled().set(false); mutable().value = FcmRecoveryUiState()
    }
    @After fun cleanup() {
        enabled().set(previousEnabled); mutable().value = previousState
        previousGenerations.forEach { (name, value) -> (field(name) as AtomicLong).set(value) }
        previousCycleFields.forEach { (name, value) -> runtime.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.set(runtime, value)
        } }
    }

    @Test fun durableOnLoadSurvivesRuntimeInvalidationBeforePublication() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot(configured = true, binding = "a".repeat(64)))
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            // BinderReceived/privilegeLost and master OFF share this exact cycle-invalidating path.
            // The saved child configuration is still ON; invalidation only retires execution.
            runtime.masterOff()
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertTrue("A lost read must not leave every later host poll silently disabled", enabled().get())
        assertTrue(runtime.state.value.configured)
        assertEquals(0, storage.writes) // Loading restores configuration, never execution authority.
        assertFalse(field("eligible") as Boolean)
    }

    @Test fun explicitOffSupersedesPendingOnLoad() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot(configured = true, binding = "a".repeat(64)))
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            transition("revokeConfiguration") // Exact synchronous state transition before durable OFF.
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertFalse(enabled().get()); assertFalse(runtime.state.value.configured)
    }

    @Test fun successfulConfirmationSupersedesPendingOffSnapshot() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot())
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            // The production transition is called under the runtime monitor after store.confirm succeeds.
            transition("configurationCommitted", FcmRecoverySnapshot(configured = true, binding = "b".repeat(64)))
            mutable().value = FcmRecoveryUiState(configured = true)
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertTrue(enabled().get()); assertTrue(runtime.state.value.configured)
    }

    @Test fun oldReadFailureCannotBlockNewConfirmation() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot()).also { it.failRead = true }
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            transition("configurationCommitted", FcmRecoverySnapshot(configured = true, binding = "b".repeat(64)))
            mutable().value = FcmRecoveryUiState(configured = true)
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertTrue(enabled().get()); assertFalse(runtime.state.value.storageBlocked)
    }

    @Test fun storageFailureSupersedesPendingSuccessfulRead() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot(configured = true, binding = "a".repeat(64)))
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            transition("blocked") // Includes a failed durable OFF save or another concurrent store operation.
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertFalse(enabled().get()); assertTrue(runtime.state.value.storageBlocked)
    }

    @Test fun currentReadFailureStillBlocksAfterRuntimeInvalidation() = runBlocking {
        val storage = Storage(FcmRecoverySnapshot()).also { it.failRead = true }
        val loading = runtime.loadConfiguration(FcmRecoveryStore(storage))
        try {
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            runtime.masterOff()
        } finally { storage.release.countDown() }
        withTimeout(5_000) { loading.join() }
        assertFalse(enabled().get()); assertTrue(runtime.state.value.storageBlocked)
    }

    @Test fun newerLoadSupersedesOlderSnapshotAndFailure() = runBlocking {
        for (oldFails in listOf(false, true)) {
            val older = Storage(FcmRecoverySnapshot()).also { it.failRead = oldFails }
            val first = runtime.loadConfiguration(FcmRecoveryStore(older))
            try {
                assertTrue(older.entered.await(5, TimeUnit.SECONDS))
                val event = FcmRecoveryEvent(1000, 100, FcmRecoveryEventAction.PRIVILEGE_LOST)
                val newer = Storage(FcmRecoverySnapshot(configured = true, binding = "b".repeat(64),
                    consumed = true, events = listOf(event))).also { it.release.countDown() }
                withTimeout(5_000) { runtime.loadConfiguration(FcmRecoveryStore(newer)).join() }
                assertTrue(enabled().get()); assertEquals(listOf(event), runtime.state.value.events)
                assertTrue(newer.value.consumed); assertEquals(0, newer.writes)
            } finally { older.release.countDown() }
            withTimeout(5_000) { first.join() }
            assertTrue(enabled().get()); assertFalse(runtime.state.value.storageBlocked)
            assertEquals(FcmRecoveryEventAction.PRIVILEGE_LOST, runtime.state.value.events.single().action)
        }
    }
}
