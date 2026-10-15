package io.github.originosfcmguardian.fcm

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmReceiptContextTest {
    @Test
    fun internetCapabilityWithoutValidationDoesNotClaimConnectivity() {
        assertEquals(NetworkAvailability.UNKNOWN, classifyCallbackNetwork(true, false))
    }

    @Test
    fun validatedInternetNetworkIsAvailableAtCallback() {
        assertEquals(NetworkAvailability.AVAILABLE, classifyCallbackNetwork(true, true))
    }

    @Test
    fun noInternetCapabilityIsUnavailableRegardlessOfValidation() {
        assertEquals(NetworkAvailability.UNAVAILABLE, classifyCallbackNetwork(false, false))
        assertEquals(NetworkAvailability.UNAVAILABLE, classifyCallbackNetwork(false, true))
    }
}
