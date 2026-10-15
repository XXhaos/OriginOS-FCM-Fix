package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import org.junit.Assert.*
import org.junit.Test

class PushProtectionPresentationTest {
    private val ready = HomeUiState(recoveryFirmwareSupported = true,
        targetSelectionLoadState = HomeSourceLoadState.LOADED,
        stoppedRecovery = StoppedRecoveryUiState(loaded = true))

    private fun sample(elapsed: Long = 1_000, sourceElapsed: Long = elapsed,
        connection: GcmConnectionState = GcmConnectionState.REPORTED_CONNECTED, duration: Long? = 3_661_000) =
        GmsConnectionObservation.Sample(requestId = 1, uid = 10208, versionCode = 1,
            firstInstallTime = 1, lastUpdateTime = 1, signerSha256 = "a".repeat(64),
            bootId = "11111111-2222-3333-4444-555555555555", observedAtMillis = 2_000,
            elapsedRealtimeMillis = elapsed, tcp = SocketSource(SourceCompleteness.COMPLETE, true),
            tcp6 = SocketSource(SourceCompleteness.COMPLETE, true), receiverInspectionComplete = false,
            receivers = emptyList(), gcmService = GcmServiceSource(connection, SourceCompleteness.COMPLETE,
                observedAtMillis = 2_000, elapsedRealtimeMillis = sourceElapsed, userId = 0,
                connectedDurationMillis = duration.takeIf { connection == GcmConnectionState.REPORTED_CONNECTED }))

    @Test fun freshConnectedClockAdvancesLocallyOnlyWithinSourceFreshness() {
        val selection = FcmInlineSelection(sample(), busy = false)
        assertEquals("01:01:01", pushConnectionClock(selection, 1_000).duration)
        assertEquals("01:01:02", pushConnectionClock(selection, 2_000).duration)
        assertEquals("01:01:31", pushConnectionClock(selection, 31_000).duration)
        assertNull(pushConnectionClock(selection, 31_001).duration)
        assertNull(pushConnectionClock(selection, 999).duration)
        assertFalse(pushConnectionClock(selection, 31_001).message!!.contains("更新于"))
    }

    @Test fun neitherFreshOuterNorFreshServiceCanRescueItsStaleCounterpart() {
        listOf(sample(elapsed = 31_001, sourceElapsed = 1_000),
            sample(elapsed = 1_000, sourceElapsed = 31_001)).forEach {
            assertNull(pushConnectionClock(FcmInlineSelection(it, busy = false), 31_001).duration)
        }
    }

    @Test fun networkUncertaintyRemovesTheClockImmediately() {
        val selection = FcmInlineSelection(sample(), busy = false)
        listOf(selection.copy(networkInvalid = true),
            selection.copy(networkTrackingUnavailable = true)).forEach {
            assertNull(pushConnectionClock(it, 2_000).duration)
        }
    }

    @Test fun pendingRefreshRetainsOnlyFreshNetworkValidCompletedEvidence() {
        val direct = GmsConnectionUiState(readAttempted = true, busy = true, observation = sample(),
            readCompletedAtElapsedMillis = 1_000, networkKey = "network", networkTrackingAvailable = true)
        val selection = selectPushConnection(direct, null)
        assertFalse(selection.busy)
        assertEquals("01:01:02", pushConnectionClock(selection, 2_000).duration)
        assertNull(pushConnectionClock(selection, 31_001).duration)
        listOf(direct.copy(networkChanged = true), direct.copy(networkTrackingAvailable = false),
            direct.copy(networkKey = "unknown")).forEach {
            assertNull(pushConnectionClock(selectPushConnection(it, null), 2_000).duration)
        }
    }

    @Test fun failedDirectReadCannotFallBackToOlderAutomaticClock() {
        val failed = GmsConnectionObservation.Unknown(2, GmsUnknown.TIMED_OUT)
        val direct = GmsConnectionUiState(readAttempted = true, observation = failed,
            readCompletedAtElapsedMillis = 2_000, networkKey = "network", networkTrackingAvailable = true)
        val clock = pushConnectionClock(selectPushConnection(direct, sample()), 2_000)
        assertNull(clock.duration)
        assertEquals("FCM 连接暂不可读取。", clock.message)
    }

    @Test fun incompleteDisconnectedAbsentOrUnknownDurationNeverInventsAClock() {
        val connected = sample()
        listOf(sample(connection = GcmConnectionState.REPORTED_DISCONNECTED),
            sample(connection = GcmConnectionState.SERVICE_ABSENT), sample(duration = null),
            connected.copy(gcmService = GcmServiceSource.unavailable()),
            connected.copy(gcmService = GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.PARTIAL,
                observedAtMillis = 2_000, elapsedRealtimeMillis = 1_000, userId = 0))).forEach {
            assertNull(pushConnectionClock(FcmInlineSelection(it, busy = false), 2_000).duration)
        }
        assertNull(pushConnectionClock(FcmInlineSelection(sample(duration = Long.MAX_VALUE), busy = false), 2_000).duration)
    }

    @Test fun offRemainsReachableDuringWaitFailureAndAnInFlightCombinedCommand() {
        val on = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        listOf(on.copy(pushProtectionBusy = true),
            on.copy(automaticProtection = on.automaticProtection.copy(busy = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForPrivilege = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForReconciliation = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForService = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(revocationSaveFailed = true)),
            on.copy(fcmProtection = FcmProtectionUiState(storageBlocked = true))).forEach { state ->
            assertTrue(pushProtectionToggleEnabled(state))
            var disabled = 0
            dispatchPushProtectionToggle(state, false, { fail("OFF must not request ON") }, { disabled++ })
            assertEquals(1, disabled)
        }
    }

    @Test fun rejectedOrDuplicateOnNeverDispatchesOffOrAnotherOn() {
        val blocked = listOf(ready.copy(pushProtectionBusy = true),
            ready.copy(automaticProtection = AutomaticProtectionState(busy = true)),
            ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true)),
            ready.copy(fcmProtection = FcmProtectionUiState(busy = true)),
            ready.copy(fcmProtection = FcmProtectionUiState(maintenance = FcmMaintenanceState.STOP_PENDING)))
        blocked.forEach { state ->
            dispatchPushProtectionToggle(state, true, { fail("Blocked ON") }, { fail("Rejected ON is not OFF") })
        }
        var enabled = 0
        dispatchPushProtectionToggle(ready, true, { enabled++ }, { fail("ON must not request OFF") })
        assertEquals(1, enabled)
        assertTrue(ready.automaticProtection.configuredIdentities.isEmpty())
    }

    @Test fun completionActionIsOnlyForMissingOrExplicitlyUnconfirmedFcmConsent() {
        val on = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        assertTrue(canCompletePushProtection(on))
        assertTrue(canCompletePushProtection(on.copy(fcmProtection = FcmProtectionUiState(
            configured = true, requiresConfirmation = true))))
        assertFalse(canCompletePushProtection(ready))
        assertFalse(canCompletePushProtection(on.copy(pushProtectionBusy = true)))
        assertFalse(canCompletePushProtection(on.copy(fcmProtection = FcmProtectionUiState(storageBlocked = true))))
        assertFalse(canCompletePushProtection(on.copy(fcmProtection = FcmProtectionUiState(
            configured = true, maintenance = FcmMaintenanceState.WAITING_HOST))))
        assertEquals("等待 Shizuku", pushProtectionStatus(on.copy(
            automaticProtection = on.automaticProtection.copy(waitingForPrivilege = true))))
    }
}
