package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.daemon.DaemonUiSnapshot
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardianRemoteTileTest {
    private fun snapshot(ready: Boolean = false, master: Boolean = true) = DaemonUiSnapshot(
        AutomaticProtectionState(enabled = ready, masterEnabled = master, configurationAvailable = true,
            detail = if (master) "等待保护服务" else "自动保护未开启"),
        FcmProtectionUiState(), emptyList(), RecoveryCountsUiState(), true, ready)

    @Test fun remoteEmissionRendersItsOwnAutomaticStateBeforeTheLocalMirrorIsPublished() {
        val oldLocalMirror = AutomaticProtectionState(enabled = false)
        val collected = snapshot(ready = true)
        assertFalse(guardianTilePresentation(oldLocalMirror, true).active) // The old mixed-read race.
        assertTrue(guardianRemoteTilePresentation(collected).active)
        assertTrue(guardianRemoteTilePresentation(collected).available)
        assertEquals("检测已开启", guardianRemoteTilePresentation(collected).subtitle)
        assertEquals(collected.automatic, snapshot(ready = true).automatic)
    }

    @Test fun remoteReadyNeverOverridesOffPendingOrMissingCoherentSnapshot() {
        assertFalse(guardianRemoteTilePresentation(null).active)
        assertFalse(guardianRemoteTilePresentation(snapshot()).active)
        val ready = snapshot(ready = true)
        assertFalse(guardianRemoteTilePresentation(ready.copy(automatic = ready.automatic.copy(enabled = false))).active)
        assertFalse(guardianRemoteTilePresentation(ready.copy(automatic = ready.automatic.copy(waitingForReconciliation = true))).active)
        assertFalse(guardianRemoteTilePresentation(ready.copy(automatic = ready.automatic.copy(revocationSaveFailed = true))).active)
    }

    @Test fun coldProjectionIsUnknownUntilTheCoherentLiveSnapshotArrives() {
        val synchronizing = guardianRemoteTilePresentation(null)
        assertFalse(synchronizing.active)
        assertFalse(synchronizing.available)
        assertEquals("正在同步保护状态", synchronizing.subtitle)
        val live = guardianRemoteTilePresentation(snapshot(ready = true))
        assertTrue(live.available)
        assertTrue(live.active)
    }

    @Test fun knownMasterOffIsInactiveWhileMissingHostOrConfigurationIsUnavailable() {
        val off = guardianRemoteTilePresentation(snapshot(master = false))
        assertTrue(off.available)
        assertFalse(off.active)
        assertEquals("自动保护未开启", off.subtitle)
        val waiting = guardianRemoteTilePresentation(snapshot())
        assertFalse(waiting.available)
        assertFalse(waiting.active)
        assertEquals("等待保护服务", waiting.subtitle)
        val ready = snapshot(ready = true)
        for (unknown in listOf(ready.copy(configurationAvailable = false),
            ready.copy(automatic = ready.automatic.copy(configurationAvailable = false)))) {
            val presentation = guardianRemoteTilePresentation(unknown)
            assertFalse(presentation.available)
            assertFalse(presentation.active)
            assertEquals("保护配置暂不可用", presentation.subtitle)
        }
        // Master OFF must win even if an inconsistent old ready flag is supplied.
        val inconsistentOff = guardianRemoteTilePresentation(snapshot(ready = true, master = false))
        assertFalse(inconsistentOff.active)
        assertEquals("自动保护未开启", inconsistentOff.subtitle)
    }

    @Test fun pendingStopAndReconciliationRemainUnavailableWithTheirActualExplanation() {
        val ready = snapshot(ready = true)
        val pending = guardianRemoteTilePresentation(ready.copy(automatic = ready.automatic.copy(
            waitingForReconciliation = true)))
        assertFalse(pending.available)
        assertFalse(pending.active)
        assertEquals("等待只读复检，当前未执行保护", pending.subtitle)
        val failedStop = guardianRemoteTilePresentation(ready.copy(automatic = ready.automatic.copy(
            masterEnabled = false, revocationSaveFailed = true)))
        assertFalse(failedStop.available)
        assertFalse(failedStop.active)
        assertEquals("关闭结果待确认", failedStop.subtitle)
    }

    @Test fun coldHostGetsFourReadOnlyOpportunitiesAcrossTheFirstThirtySecondPoll() = runBlocking {
        var elapsed = 0L
        val reads = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        var last: DaemonUiSnapshot? = null
        refreshRemoteGuardianTile({ true }, { true }, {
            reads += elapsed
            snapshot(ready = elapsed >= 35_000).also { last = it }
        }, { waits += it; elapsed += it })
        assertEquals(listOf(0L, 1000L, 5000L, 35000L), reads)
        assertEquals(listOf(1000L, 4000L, 30000L), waits)
        assertTrue(guardianRemoteTilePresentation(last).active)
    }

    @Test fun anUnreadyHostCannotExtendTheListeningRefreshIntoAnUnboundedTimer() = runBlocking {
        var reads = 0
        var waits = 0
        refreshRemoteGuardianTile({ true }, { true }, { reads++; snapshot() }, { waits++ })
        assertEquals(4, reads)
        assertEquals(3, waits)
    }

    @Test fun closingThePanelStopsReadsAndTheNextOpenStillGetsAnImmediateRead() = runBlocking {
        var listening = true
        var reads = 0
        refreshRemoteGuardianTile({ listening }, { true }, { reads++; snapshot() }, { listening = false })
        assertEquals(1, reads)
        listening = true
        refreshRemoteGuardianTile({ listening }, { true }, { reads++; snapshot(ready = true) }, { fail("No delayed read needed") })
        assertEquals(2, reads)
    }

    @Test fun permissionAbsenceOrLossNeverQueuesMoreReads() = runBlocking {
        var permitted = false
        var reads = 0
        refreshRemoteGuardianTile({ true }, { permitted }, { reads++; snapshot() }, { fail("No timer without permission") })
        assertEquals(0, reads)
        permitted = true
        refreshRemoteGuardianTile({ true }, { permitted }, { reads++; snapshot() }, { permitted = false })
        assertEquals(1, reads)
    }

    @Test fun explicitOffOrUnsettledStopStopsAfterTheFreshImmediateReadWithoutEnablingAnything() = runBlocking {
        val waiting = snapshot()
        for (result in listOf(snapshot(master = false), waiting.copy(automatic = waiting.automatic.copy(
            waitingForReconciliation = true)), waiting.copy(automatic = waiting.automatic.copy(revocationSaveFailed = true)))) {
            var reads = 0
            refreshRemoteGuardianTile({ true }, { true }, { reads++; result }, { fail("No timer after OFF/stop") })
            assertEquals(1, reads)
            assertFalse(result.automatic.enabled)
        }
    }

    @Test fun swallowedFacadeCancellationStillCannotStartAnotherTileRefresh() = runBlocking {
        var reads = 0
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshRemoteGuardianTile({ true }, { true }, {
                reads++
                currentCoroutineContext().cancel()
                snapshot() // Models the facade returning after catching cancellation.
            }, { fail("Cancelled job must not schedule a delay") })
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertEquals(1, reads)
    }
}
