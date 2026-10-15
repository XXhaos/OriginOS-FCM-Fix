package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DaemonExistingDiagnosticsReadTest {
    @Test fun missingOrDeadCachedEndpointCannotDispatchReportRead() = runBlocking {
        for (cached in listOf(null, "dead")) {
            var reads = 0
            try {
                readExistingDaemonDiagnostics({ cached }, { false }) { reads++; "unexpected" }
                fail("Unavailable endpoint must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(0, reads)
        }
    }

    @Test fun liveCachedEndpointIsReadExactlyOnce() = runBlocking {
        var cacheReads = 0
        var dispatches = 0
        val response = readExistingDaemonDiagnostics({ cacheReads++; "attached" }, { it == "attached" }) {
            dispatches++; assertEquals("attached", it); "snapshot"
        }
        assertEquals("snapshot", response)
        assertEquals(1, cacheReads)
        assertEquals(1, dispatches)
    }

    @Test fun failedReadCannotRetryOrSelectAnotherEndpoint() = runBlocking {
        var cacheReads = 0
        var dispatches = 0
        val failure = IllegalStateException("Read failed")
        try {
            readExistingDaemonDiagnostics({ cacheReads++; "attached" }, { true }) {
                dispatches++; throw failure
            }
            fail("Expected read failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(1, cacheReads)
        assertEquals(1, dispatches)
    }

    @Test fun cancellationPropagatesWithoutAnotherRead() = runBlocking {
        var dispatches = 0
        val cancellation = CancellationException("Report cancelled")
        try {
            readExistingDaemonDiagnostics({ "attached" }, { true }) { dispatches++; throw cancellation }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(1, dispatches)
    }
}
