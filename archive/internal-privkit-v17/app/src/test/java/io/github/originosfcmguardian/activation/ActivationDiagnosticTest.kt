package io.github.originosfcmguardian.activation

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ActivationDiagnosticTest {
    private val stage = ActivationStage.PAIRING_REQUEST

    @Test fun coroutineAndSocketTimeoutRemainDistinctFromExplicitCancellation() {
        val timeout = requireNotNull(runCatching { runBlocking { withTimeout(1) { delay(20) } } }.exceptionOrNull())
        assertEquals(ActivationFailureKind.TIMEOUT, activationDiagnostic(stage, timeout).kind)
        assertEquals(ActivationFailureKind.TIMEOUT, activationDiagnostic(stage, Exception("wrapper", timeout)).kind)
        assertEquals(ActivationFailureKind.TIMEOUT, activationDiagnostic(stage, SocketTimeoutException()).kind)
        assertEquals(ActivationFailureKind.CANCELLED, activationDiagnostic(stage, CancellationException()).kind)
    }

    @Test fun nestedFailuresUseFixedFamiliesWithoutGuessingWrongPairingCode() {
        val cases = listOf(
            UnsatisfiedLinkError("private") to ActivationFailureKind.INCOMPATIBLE,
            SecurityException("private") to ActivationFailureKind.SECURITY,
            SSLException("private") to ActivationFailureKind.TLS,
            ConnectException("private") to ActivationFailureKind.CONNECT,
            IOException("private") to ActivationFailureKind.IO,
        )
        for ((failure, expected) in cases) {
            val result = activationDiagnostic(stage, Exception("wrapper", Exception("nested", failure)))
            assertEquals(expected, result.kind)
            assertFalse(result.guidance.contains("配对码错误"))
        }
        assertEquals(ActivationFailureKind.CONNECT,
            activationDiagnostic(stage, IOException("wrapper", ConnectException("private"))).kind)
        assertEquals(ActivationFailureKind.TLS,
            activationDiagnostic(stage, IOException("wrapper", SSLException("private"))).kind)
        val localIo = activationDiagnostic(ActivationStage.CLEANUP, IOException("private"))
        assertEquals(ActivationFailureKind.IO, localIo.kind)
        assertFalse(localIo.guidance.contains("网络"))
    }

    @Test fun unknownSecretPayloadAndCyclicCauseChainNeverEscapeOrLoop() {
        val secret = "code=012345 port=54321 key=PRIVATE protocol=stdout-secret"
        val first = Exception(secret)
        val second = Exception(secret)
        first.initCause(second)
        second.initCause(first)
        val result = activationDiagnostic(stage, first)
        assertEquals(ActivationFailureKind.UNKNOWN, result.kind)
        val visible = "${result.code} ${result.guidance} $result"
        for (value in listOf(secret, "012345", "54321", "PRIVATE", "stdout-secret")) assertFalse(visible.contains(value))
    }

    @Test fun cleanupFailureCannotReplaceOriginalFailureStageOrFamily() {
        val operation = ActivationDiagnostic(ActivationStage.PAIRING_REQUEST, ActivationFailureKind.TLS)
        val cleanup = ActivationDiagnostic(ActivationStage.CLEANUP, ActivationFailureKind.SECURITY)
        assertSame(operation, primaryActivationDiagnostic(operation, cleanup))
        assertSame(cleanup, primaryActivationDiagnostic(null, cleanup))
        val status = InternalActivationStatus(needsManualCleanup = true,
            failure = primaryActivationDiagnostic(operation, cleanup), cleanupFailure = cleanup)
        assertFalse(status.ready)
        assertEquals(ActivationStage.PAIRING_REQUEST, status.failure?.stage)
        assertEquals(ActivationFailureKind.SECURITY, status.cleanupFailure?.kind)
    }

    @Test fun staleOperationCannotPublishDiagnosticOverNewerOrFinishedOperation() {
        assertTrue(activationDiagnosticBelongsToOperation(7, 7))
        assertFalse(activationDiagnosticBelongsToOperation(8, 7))
        assertFalse(activationDiagnosticBelongsToOperation(null, 7))
    }
}
