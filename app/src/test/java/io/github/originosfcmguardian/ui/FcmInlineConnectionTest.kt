package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import org.junit.Assert.*
import org.junit.Test

class FcmInlineConnectionTest {
    private fun sample(elapsed: Long = 900, sourceElapsed: Long = elapsed,
        state: GcmConnectionState = GcmConnectionState.REPORTED_CONNECTED,
        duration: Long = 3_661_000) = GmsConnectionObservation.Sample(
        requestId = elapsed + 1, uid = 10208, versionCode = 1,
        firstInstallTime = 1, lastUpdateTime = 1, signerSha256 = "a".repeat(64),
        bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 2_000, elapsedRealtimeMillis = elapsed,
        tcp = SocketSource(SourceCompleteness.COMPLETE, true),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, true),
        receiverInspectionComplete = false, receivers = emptyList(),
        gcmService = GcmServiceSource(state, SourceCompleteness.COMPLETE,
            observedAtMillis = 2_000, elapsedRealtimeMillis = sourceElapsed, userId = 0,
            connectedDurationMillis = duration.takeIf { state == GcmConnectionState.REPORTED_CONNECTED }))

    private fun manual(observation: GmsConnectionObservation? = sample(), completed: Long? = 1_000,
        baseline: GmsConnectionObservation? = null) = GmsConnectionUiState(
        readAttempted = true, observation = observation, readCompletedAtElapsedMillis = completed,
        automaticObservationAtReadCompletion = baseline,
        networkKey = "41:true:false:false:true", networkTrackingAvailable = true)

    private fun status(manual: GmsConnectionUiState, automatic: GmsConnectionObservation?,
        now: Long = 1_300) = fcmInlineStatus(selectFcmInlineConnection(manual, automatic), now)

    @Test fun defaultStateUsesExistingAutomaticObservation() {
        val automatic = sample()
        val selection = selectFcmInlineConnection(GmsConnectionUiState(), automatic)
        assertSame(automatic, selection.observation)
        assertFalse(selection.busy)
        assertTrue(fcmInlineStatus(selection, 1_000).text.startsWith("🟢 01:01:01"))
    }

    @Test fun explicitReadShowsReadingInsteadOfEitherOldPositive() {
        val selection = selectFcmInlineConnection(manual().copy(busy = true), sample(800))
        assertTrue(selection.busy)
        assertNull(selection.observation)
        assertEquals("⚪ 读取中…", fcmInlineStatus(selection, 1_000).text)
    }

    @Test fun manualKnownResultWinsOverOlderAndEqualAutomaticSamples() {
        val direct = sample(state = GcmConnectionState.REPORTED_DISCONNECTED)
        listOf(sample(800), sample(1_000)).forEach { automatic ->
            assertSame(direct, selectFcmInlineConnection(manual(direct), automatic).observation)
            assertTrue(status(manual(direct), automatic).text.startsWith("🔴 未连接"))
        }
    }

    @Test fun manualUnknownCannotFallBackToOlderAutomaticPositive() {
        val failed = GmsConnectionObservation.Unknown(7, GmsUnknown.BRIDGE_UNAVAILABLE)
        assertSame(failed, selectFcmInlineConnection(manual(failed), sample(800)).observation)
        assertEquals("🟡 无法读取", status(manual(failed), sample(800)).text)
    }

    @Test fun genuinelyNewerAutomaticSampleSupersedesTheManualRead() {
        val automatic = sample(1_200, duration = 60_000)
        val selection = selectFcmInlineConnection(manual(), automatic)
        assertSame(automatic, selection.observation)
        assertTrue(fcmInlineStatus(selection, 1_300).text.startsWith("🟢 00:01:00"))
    }

    @Test fun laterAutomaticFailureCannotRestoreAnOlderManualPositive() {
        val baseline = sample(800)
        val state = manual(baseline = baseline)
        assertSame(baseline, state.automaticObservationAtReadCompletion)
        val newer = sample(1_200)
        assertSame(newer, selectFcmInlineConnection(state, newer).observation)
        val failure = GmsConnectionObservation.Unknown(8, GmsUnknown.TIMED_OUT)
        assertSame(failure, selectFcmInlineConnection(state, failure).observation)
        assertEquals("🟡 无法读取", status(state, failure).text)
    }

    @Test fun equalValueFailureFromANewAttemptStillSuppressesOldManualPositive() {
        val baseline = GmsConnectionObservation.Unknown(8, GmsUnknown.TIMED_OUT)
        val laterFailure = baseline.copy()
        assertEquals(baseline, laterFailure)
        assertNotSame(baseline, laterFailure)
        val state = manual(baseline = baseline)
        assertSame(laterFailure, selectFcmInlineConnection(state, laterFailure).observation)
        assertEquals("🟡 无法读取", status(state, laterFailure).text)
    }

    @Test fun unchangedAutomaticFailureAtCompletionDoesNotMaskManualSuccess() {
        val baseline = GmsConnectionObservation.Unknown(8, GmsUnknown.TIMED_OUT)
        val direct = sample()
        val state = manual(direct, baseline = baseline)
        assertSame(direct, selectFcmInlineConnection(state, baseline).observation)
        assertTrue(status(state, baseline).text.startsWith("🟢 01:01:01"))
    }

    @Test fun newerOuterTimestampCannotHideOlderServiceEvidenceOrViceVersa() {
        val direct = sample(state = GcmConnectionState.REPORTED_DISCONNECTED)
        listOf(sample(1_200, sourceElapsed = 800), sample(800, sourceElapsed = 1_200)).forEach { automatic ->
            assertSame(direct, selectFcmInlineConnection(manual(direct), automatic).observation)
            assertTrue(status(manual(direct), automatic).text.startsWith("🔴 未连接"))
        }
    }

    @Test fun manualNetworkGuardsApplyEvenWhenNewerAutomaticSampleIsSelected() {
        val guardedStates = listOf(
            manual().copy(networkChanged = true),
            manual().copy(networkTrackingAvailable = false),
            manual().copy(networkKey = "unknown"),
        )
        guardedStates.forEach { state ->
            listOf(sample(800), sample(1_200)).forEach { automatic ->
                val selection = selectFcmInlineConnection(state, automatic)
                if (automatic.elapsedRealtimeMillis > 1_000) assertSame(automatic, selection.observation)
                val rendered = fcmInlineStatus(selection, 1_300)
                assertFalse(rendered.text.startsWith("🟢"))
                assertFalse(rendered.text.contains("01:01:01"))
            }
        }
    }

    @Test fun uncompletedManualAttemptAndStaleSamplesNeverShowOlderPositive() {
        val selection = selectFcmInlineConnection(manual(observation = null, completed = null), sample(800))
        assertNull(selection.observation)
        assertEquals("⚪ 尚未读取", fcmInlineStatus(selection, 1_000).text)
        assertTrue(status(manual(), sample(800), now = 30_901).text.startsWith("⚪ 状态已过期"))
        val automaticOnly = selectFcmInlineConnection(GmsConnectionUiState(), sample(900))
        assertTrue(fcmInlineStatus(automaticOnly, 30_901).text.startsWith("⚪ 状态已过期"))
    }

    @Test fun selectedSampleDurationStaysFixedWhileItsUpdateAgeAdvances() {
        val selection = selectFcmInlineConnection(manual(), sample(800))
        listOf(1_000L, 2_000L, 30_900L).forEach { now ->
            assertTrue(fcmInlineStatus(selection, now).text.startsWith("🟢 01:01:01 · "))
        }
        assertNotEquals(fcmInlineStatus(selection, 1_000).text, fcmInlineStatus(selection, 2_000).text)
        assertTrue(fcmInlineStatus(selection, 30_901).text.startsWith("⚪ 状态已过期"))
    }
}
