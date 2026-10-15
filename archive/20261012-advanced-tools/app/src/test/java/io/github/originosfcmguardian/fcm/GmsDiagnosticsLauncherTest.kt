package io.github.originosfcmguardian.fcm

import org.junit.Assert.*
import org.junit.Test

class GmsDiagnosticsLauncherTest {
    @Test fun successAttemptsOnlyTheFixedGoogleActivityOnce() {
        val calls = mutableListOf<Pair<String, String>>()
        assertTrue(attemptGmsDiagnostics { packageName, activity -> calls += packageName to activity })
        assertEquals(listOf("com.google.android.gms" to "com.google.android.gms.gcm.GcmDiagnostics"), calls)
    }

    @Test fun securityFailureHasNoFallbackOrExceptionDetails() {
        var calls = 0
        assertFalse(attemptGmsDiagnostics { _, _ -> calls++; throw SecurityException("private fixture detail") })
        assertEquals(1, calls)
        assertFalse(GMS_DIAGNOSTICS_UNAVAILABLE.contains("private fixture detail"))
    }

    @Test fun runtimeLaunchFailureDoesNotRetry() {
        var calls = 0
        assertFalse(attemptGmsDiagnostics { _, _ -> calls++; throw IllegalStateException("private fixture detail") })
        assertEquals(1, calls)
    }
}
