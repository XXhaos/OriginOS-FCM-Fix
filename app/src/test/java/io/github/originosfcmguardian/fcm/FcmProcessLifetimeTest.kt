package io.github.originosfcmguardian.fcm

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmProcessLifetimeTest {
    @Test
    fun coherentStartProducesProcessAgeAtCallback() {
        val receipt = receipt(processStartedAtElapsedMillis = 125)

        assertEquals(375L, receipt.processAgeAtCallbackMillis)
    }

    @Test
    fun missingStartRemainsUnknownRatherThanZeroAge() {
        val receipt = receipt(processStartedAtElapsedMillis = null)

        assertEquals(null, receipt.processAgeAtCallbackMillis)
    }

    @Test
    fun captureRejectsNegativeOrFutureStartButAcceptsBounds() {
        assertEquals(null, coherentProcessStartedAtElapsedMillis(-1, 500))
        assertEquals(null, coherentProcessStartedAtElapsedMillis(501, 500))
        assertEquals(0L, coherentProcessStartedAtElapsedMillis(0, 500))
        assertEquals(500L, coherentProcessStartedAtElapsedMillis(500, 500))
    }

    private fun receipt(processStartedAtElapsedMillis: Long?) = FcmReceipt(
        testId = "lifetime",
        sentAtEpochMillis = 1_000,
        receivedAtEpochMillis = 1_500,
        receivedAtElapsedMillis = 500,
        messagePriority = MessagePriority.NORMAL,
        deviceInteractive = ObservedBoolean.UNKNOWN,
        deviceIdleMode = ObservedBoolean.UNKNOWN,
        networkAvailability = NetworkAvailability.UNKNOWN,
        processStartedAtElapsedMillis = processStartedAtElapsedMillis,
    )
}
