package io.github.originosfcmguardian.fcm

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRegistrationWaitTest {
    @Test
    fun completedTokenResultIsPreserved() = runBlocking {
        val result = RegistrationResult.Success(SensitiveToken("test-owned-token"))
        assertSame(result, awaitRegistrationResult(1_000) { result })
    }

    @Test
    fun waitingOnUncompletedTransportHasUnknownOutcomeAtDeadline() = runBlocking {
        val transport = CompletableDeferred<RegistrationResult>()
        assertSame(RegistrationResult.TimedOut, awaitRegistrationResult(10) { transport.await() })
        // A cancelled waiter must not turn the underlying SDK-style task into a failed task.
        val lateSuccess = RegistrationResult.Success(SensitiveToken("test-late-token"))
        transport.complete(lateSuccess)
        assertSame(lateSuccess, transport.await())
    }

    @Test
    fun completedSdkIoFailureDoesNotBecomeWaitTimeout() = runBlocking {
        assertSame(
            RegistrationResult.SdkIoFailure,
            awaitRegistrationResult(1_000) { sanitizeRegistrationFailure(IOException("private detail")) },
        )
    }

    @Test
    fun callerCancellationIsNotReportedAsTimeoutOrSdkFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val transport = CompletableDeferred<RegistrationResult>()
        var reported: RegistrationResult? = null
        val caller = launch {
            reported = awaitRegistrationResult(5_000) {
                started.complete(Unit)
                transport.await()
            }
        }
        started.await()
        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertNull(reported)
        assertFalse(transport.isCancelled)
    }
}
