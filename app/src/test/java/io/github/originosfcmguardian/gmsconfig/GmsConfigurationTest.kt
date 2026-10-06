package io.github.originosfcmguardian.gmsconfig

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.*
import io.github.originosfcmguardian.ui.*
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GmsConfigurationTest {
    private val context = GmsConfigContext("vivo/device/build", 0, 14,
        GmsConfigIdentity(SelectedTargetIdentity(GMS_PACKAGE_NAME, 10208, 100, listOf("a".repeat(64))), 263436035, 200, true))
    private fun sample() = GmsConfigSnapshot(context, 100_000, 10_000, EvidenceStatus.CONFORMING,
        EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, GmsConfigFailure.NONE)

    @Test fun freshnessRejectsExpiryBootIdentityUnknownAndClockChanges() {
        val value = sample()
        assertTrue(value.isFresh(context, 100_001, 10_001))
        assertFalse(value.isFresh(context, 100_000 + GMS_CONFIG_INTERVAL_MILLIS, 10_000 + GMS_CONFIG_INTERVAL_MILLIS))
        assertFalse(value.isFresh(context.copy(bootCount = 15), 100_001, 10_001))
        assertFalse(value.isFresh(context.copy(identity = context.identity!!.copy(versionCode = 1)), 100_001, 10_001))
        assertFalse(value.isFresh(context.copy(identity = null), 100_001, 10_001))
        assertFalse(value.isFresh(context.copy(userId = 10), 100_001, 10_001))
        assertFalse(value.isFresh(context, 300_000, 10_001))
        assertFalse(value.isFresh(context, 99_999, 9_999))
        assertFalse(value.copy(context = context.copy(bootCount = null)).isFresh(context.copy(bootCount = null), 100_001, 10_001))
    }

    @Test fun boundedStructuredCodecRoundTripsAndRejectsDamage() {
        val value = sample().copy(runAnyInBackground = EvidenceStatus.ANOMALOUS, wakeLock = EvidenceStatus.UNKNOWN)
        val raw = GmsConfigurationCodec.encode(value)
        assertEquals(value, GmsConfigurationCodec.decode(raw))
        assertNull(GmsConfigurationCodec.decode(raw.dropLast(8)))
        assertNull(GmsConfigurationCodec.decode("x".repeat(4097)))
        assertNull(GmsConfigurationCodec.decode("not-base64"))
    }

    @Test fun settingsKeepUnknownAndAnomalySeparateAndIdentityDriftInvalidatesAll() {
        val report = DetectionReport(PrivilegedAccessState.READY_SHELL, 0, context.fingerprint,
            GmsPackageState(Presence.PRESENT, EnabledState.ENABLED, 10208, "", ""), emptyMap(),
            DozeState(emptySet(), "", EvidenceStatus.ANOMALOUS), PemState(Presence.UNKNOWN, PemListState.UNKNOWN, emptySet(), "", ""))
        val parsed = gmsConfigSnapshot(context, context, report, 100_000, 10_000)
        assertEquals(EvidenceStatus.CONFORMING, parsed.gms)
        assertEquals(EvidenceStatus.UNKNOWN, parsed.runInBackground)
        assertEquals(EvidenceStatus.ANOMALOUS, parsed.doze)
        val drift = gmsConfigSnapshot(context, context.copy(bootCount = 15), report, 100_000, 10_000)
        assertEquals(GmsConfigFailure.IDENTITY_OR_ENVIRONMENT, drift.failure)
        assertTrue(drift.settings.all { it == EvidenceStatus.UNKNOWN })
        val noPrivilege = gmsConfigSnapshot(context, context, report.copy(privilegedAccess = PrivilegedAccessState.UNAVAILABLE), 100_000, 10_000)
        assertEquals(GmsConfigFailure.UNAVAILABLE, noPrivilege.failure)
        assertTrue(noPrivilege.settings.all { it == EvidenceStatus.UNKNOWN })
    }

    @Test fun cadenceSingleFlightCancellationErrorsAndEligibilityNeverFastRetry() = runBlocking {
        var elapsed = 0L
        var calls = 0
        var cancelled = false
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cadence = GmsConfigCadence(scope, { elapsed })
        assertFalse(cadence.launchIfEligible(false) { calls++ })
        assertTrue(cadence.launchIfEligible(true) { calls++; try { awaitCancellation() } finally { cancelled = true } })
        yield()
        elapsed = GMS_CONFIG_INTERVAL_MILLIS
        assertFalse(cadence.launchIfEligible(true) { calls++ })
        cadence.cancel(); yield()
        assertTrue(cancelled)
        assertTrue(cadence.launchIfEligible(true) { calls++; error("local failure") })
        yield()
        assertFalse(cadence.launchIfEligible(true) { calls++ })
        elapsed += GMS_CONFIG_INTERVAL_MILLIS
        assertTrue(cadence.launchIfEligible(true) { calls++ })
        yield()
        assertEquals(3, calls)
        scope.cancel()
    }

    @Test fun persistedSnapshotPresentationNeverGreenWhenUnavailableStaleOrUnknown() {
        val state = HomeUiState(gmsConfigSnapshot = sample(), gmsConfigContext = context,
            shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready"))
        assertTrue(gmsBackgroundSummary(state, 100_001, 10_001).contains("最近检查未见异常"))
        assertTrue(gmsBackgroundSummary(state.copy(shizuku = ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "absent")), 100_001, 10_001).contains("未知"))
        assertTrue(gmsBackgroundSummary(state, 1_000_000, 910_000).contains("未知"))
        assertTrue(gmsBackgroundSummary(state.copy(gmsConfigContext = null), 100_001, 10_001).contains("未知"))
        assertTrue(gmsBackgroundSummary(state.copy(gmsConfigLoadFailed = true), 100_001, 10_001).contains("未知"))
        assertTrue(gmsBackgroundSummary(state.copy(gmsConfigSnapshot = sample().copy(wakeLock = EvidenceStatus.UNKNOWN)), 100_001, 10_001).contains("未知"))
    }
}
