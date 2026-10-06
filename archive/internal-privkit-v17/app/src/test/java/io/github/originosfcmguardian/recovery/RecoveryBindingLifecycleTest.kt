package io.github.originosfcmguardian.recovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RecoveryBindingLifecycleTest {
    private class Port : RecoveryBindingPort<Any> {
        var ready = true
        var current = true
        var starts = 0
        var unbinds = 0
        val disposed = mutableListOf<Any>()
        lateinit var connected: (Any) -> Unit
        lateinit var disconnected: () -> Unit
        var onStart: (() -> Unit)? = null
        override fun checkReady() { check(ready) }
        override fun isCurrent() = current
        override fun start(connected: (Any) -> Unit, disconnected: () -> Unit) {
            starts++
            this.connected = connected; this.disconnected = disconnected
            onStart?.invoke()
        }
        override fun disposeEndpoint(endpoint: Any) { disposed += endpoint }
        override fun unbind() { unbinds++ }
    }

    @Test fun successfulBindHasOneCleanupAndNeverRebinds() {
        val port = Port()
        val endpoint = Any()
        port.onStart = { port.connected(endpoint) }
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, {}, { it() })
        assertSame(endpoint, binding.bind())
        binding.close(); binding.close()
        assertEquals(listOf(endpoint), port.disposed)
        assertEquals(1, port.unbinds)
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        assertEquals(1, port.starts)
    }

    @Test fun unavailableBackendNeverStartsOrFallsBack() {
        val port = Port().apply { ready = false }
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, {}, { it() })
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        binding.close()
        assertEquals(0, port.starts)
        assertEquals(0, port.unbinds)
    }

    @Test fun cancelDuringBindInvalidatesBeforeQueuedCleanupAndRejectsLateEndpoint() {
        val port = Port()
        var active = true
        val cleanup = mutableListOf<() -> Unit>()
        lateinit var binding: RecoveryBindingLifecycle<Any>
        binding = RecoveryBindingLifecycle(port, { active }, { active }, {}, { cleanup += it })
        port.onStart = { active = false; binding.close() }
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        val late = Any()
        port.connected(late)
        assertEquals(0, port.unbinds)
        cleanup.toList().forEach { it() }
        assertEquals(1, port.unbinds)
        assertEquals(listOf(late), port.disposed)
        assertThrows(IllegalStateException::class.java) { binding.bind() }
    }

    @Test fun fourSecondBindDeadlineClosesAndLateConnectionNeverPublishes() {
        val port = Port()
        var now = 0L
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, {}, { it() },
            { now.also { now += TimeUnit.SECONDS.toNanos(5) } })
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        val late = Any(); port.connected(late)
        assertEquals(listOf(late), port.disposed)
        assertEquals(1, port.unbinds)
        assertEquals(1, port.starts)
    }

    @Test fun serviceDeathInvalidatesImmediatelyAndDisposesExactEndpoint() {
        val port = Port()
        val endpoint = Any()
        var invalidated = false
        port.onStart = { port.connected(endpoint) }
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, { invalidated = true },
            { assertTrue(invalidated); it() })
        binding.bind(); port.disconnected()
        assertTrue(invalidated)
        assertEquals(listOf(endpoint), port.disposed)
        assertEquals(1, port.unbinds)
        assertThrows(IllegalStateException::class.java) { binding.bind() }
    }

    @Test fun callbackChecksOnlyLocalMemoryAndWorkerChecksFullAuthority() {
        val port = Port()
        var fullReads = 0
        var callbackReads = -1
        port.onStart = {
            val before = fullReads
            port.connected(Any())
            callbackReads = fullReads - before
        }
        val binding = RecoveryBindingLifecycle(port, { true }, { fullReads++; true }, {}, { it() })
        binding.bind()
        assertEquals(0, callbackReads)
        assertTrue(fullReads >= 3)
        binding.close()
    }

    @Test fun backendReplacementAndRevocationAfterCallbackDiscardEndpoint() {
        val port = Port()
        val endpoint = Any()
        port.onStart = { port.connected(endpoint); port.current = false }
        val binding = RecoveryBindingLifecycle(port, { true }, { port.current }, {}, { it() })
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        assertEquals(listOf(endpoint), port.disposed)
        assertEquals(1, port.unbinds)
    }

    @Test fun aSecondDifferentEndpointInvalidatesRatherThanSwitchingBackend() {
        val port = Port()
        val first = Any(); val second = Any()
        port.onStart = { port.connected(first) }
        var invalidated = false
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, { invalidated = true }, { it() })
        binding.bind(); port.connected(second)
        assertTrue(invalidated)
        assertTrue(port.disposed.containsAll(listOf(first, second)))
        assertEquals(1, port.unbinds)
    }

    @Test fun identicalDuplicateCallbackDoesNotDisposeLiveEndpoint() {
        val port = Port(); val endpoint = Any()
        port.onStart = { port.connected(endpoint); port.connected(endpoint) }
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, {}, { it() })
        assertSame(endpoint, binding.bind())
        assertTrue(port.disposed.isEmpty())
        binding.close()
        assertEquals(listOf(endpoint), port.disposed)
    }

    @Test fun bindFailureAndCleanupRejectionCannotReopenSession() {
        val port = Port().apply { onStart = { error("failed bind") } }
        val binding = RecoveryBindingLifecycle(port, { true }, { true }, {}, { error("cleanup busy") })
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        assertThrows(IllegalStateException::class.java) { binding.bind() }
        assertEquals(1, port.starts)
    }

    @Test fun dormantCandidateRejectsBeforePrivKitAndAdmissionSurvivesTimeoutUntilTeardown() {
        assertFalse(AppOwnedRecoveryPolicy.ENABLED)
        assertThrows(IllegalStateException::class.java) { AppOwnedRecoveryPolicy.requireEnabled() }
        assertThrows(IllegalStateException::class.java) { PrivKitRecoveryTransport().newBinding() }
        val admission = RecoveryCandidateAdmission()
        assertTrue(admission.acquire())
        assertTrue(admission.isHeld()) // Local deadline/cancellation must not call finishTeardown.
        assertFalse(admission.acquire())
        admission.finishTeardown()
        assertTrue(admission.acquire())
    }

    @Test fun cancellationBeforePlatformStartPreventsRegistrationEvenIfCleanupRanEarly() {
        var stopped = true
        var registrations = 0
        assertThrows(IllegalStateException::class.java) {
            completeRecoveryRegistration({ stopped }, { registrations++ }, { error("no registration to retire") })
        }
        assertEquals(0, registrations)
    }

    @Test fun cancellationDuringPlatformBindRetiresTheExactLateRegistration() {
        var stopped = false
        val requested = Any()
        var registered: Any? = null
        var retired: Any? = null
        completeRecoveryRegistration({ stopped }, { stopped = true; registered = requested }, { retired = requested })
        assertSame(requested, registered)
        assertSame(requested, retired)
    }
}
