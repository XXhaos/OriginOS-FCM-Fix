package io.github.originosfcmguardian.activation

import org.junit.Assert.*
import org.junit.Test
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ActivationPolicyTest {
    @Test fun cancellationBeforeLazyDispatchReleasesGenerationAndTerminalizes() {
        val generations = ActivationGeneration()
        val ticket = requireNotNull(generations.begin())
        var entered = false
        var terminalized = false
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val worker = scope.launch(start = CoroutineStart.LAZY) { entered = true }
        generations.finishOnCompletion(worker, ticket) { terminalized = true }
        generations.cancel()
        worker.cancel()
        assertFalse(entered)
        assertTrue(terminalized)
        assertTrue(worker.isCompleted)
        assertFalse(generations.accepts(ticket))
        assertNotNull(generations.begin())
        scope.coroutineContext[Job]?.cancel()
    }
    @Test fun liveServerReadOnlyReuseDoesNotRequireWirelessButStillRequiresSupportedUnlockedUser() {
        val input = ActivationPreflight(VERIFIED_AUTOSTART_FINGERPRINT, 0, true, 0, false)
        assertNotNull(ActivationPolicy.preflightFailure(input))
        assertNull(ActivationPolicy.preflightFailure(input, wirelessRequired = false))
        assertNotNull(ActivationPolicy.preflightFailure(input.copy(userId = 10), wirelessRequired = false))
        assertNotNull(ActivationPolicy.preflightFailure(input.copy(unlocked = false), wirelessRequired = false))
        assertNotNull(ActivationPolicy.preflightFailure(input.copy(fingerprint = "unsupported"), wirelessRequired = false))
    }
    @Test fun portsAreSeparateOptionalAndStrict() {
        assertNull(ActivationPolicy.port(""))
        assertEquals(12345, ActivationPolicy.port("12345"))
        for (bad in listOf("0", "65536", "-1", "1;id", "localhost:1234", " 1234", "١٢٣٤")) {
            assertTrue(runCatching { ActivationPolicy.port(bad) }.isFailure)
        }
        assertTrue(ActivationPolicy.pairingCode("012345"))
        assertFalse(ActivationPolicy.pairingCode("12345"))
    }
    @Test fun staleOrUncertainLeasesNeverAuthorizeCleanup() {
        val lease = WirelessLease("current", 2, 100, true)
        assertTrue(ActivationPolicy.cleanupAllowed(lease, "current", 2, 101))
        assertFalse(ActivationPolicy.cleanupAllowed(lease, "other-process", 2, 101))
        assertFalse(ActivationPolicy.cleanupAllowed(lease, "current", 3, 101))
        assertFalse(ActivationPolicy.cleanupAllowed(lease, "current", 2, 99))
        assertFalse(ActivationPolicy.cleanupAllowed(lease, "current", 2, 100_000))
        assertFalse(ActivationPolicy.cleanupAllowed(lease.copy(confirmed = false), "current", 2, 101))
    }
    @Test fun cancelledAndOldGenerationsCannotPublishReady() {
        val gate = ActivationGeneration()
        val first = requireNotNull(gate.begin())
        assertNull(gate.begin())
        gate.cancel()
        val second = requireNotNull(gate.begin())
        assertFalse(gate.accepts(first))
        gate.finish(first)
        assertTrue(gate.accepts(second))
        gate.finish(second)
        assertFalse(gate.accepts(second))
    }
}
