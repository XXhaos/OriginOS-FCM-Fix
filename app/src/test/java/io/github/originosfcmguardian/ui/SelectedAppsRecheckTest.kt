package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.RECIPIENT_READ_PARALLELISM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SelectedAppsRecheckTest {
    private val first = SelectedTargetIdentity("example.first", 10123, 1, listOf("a".repeat(64)))
    private val second = first.copy(packageName = "example.second", uid = 10124)
    private val third = first.copy(packageName = "example.third", uid = 10125)
    private val ready = HomeUiState(visibleAppsLoadState = HomeSourceLoadState.LOADED,
        visibleApps = listOf(first, second, third).map { VisibleProtectionApp(it.packageName, it.packageName, it, true) },
        selectedTargets = listOf(SelectedTargetUiState(third, "Historical attention only")),
        automaticProtection = AutomaticProtectionState(masterEnabled = false, targets = listOf(first, second).map {
            AutomaticProtectionTargetState(it, savedAuthorization = true) }))

    @Test fun allSavedChoicesRegardlessOfMasterOrAttentionListAreIncluded() {
        assertEquals(listOf(first, second), selectedAppsRecheckTargets(ready))
        assertTrue(canRecheckSelectedApps(ready))
        assertFalse(canRecheckSelectedApps(ready.copy(selectedAppsRecheckRunning = true)))
        assertFalse(canRecheckSelectedApps(ready.copy(targetReadInFlight = setOf(third))))
    }

    @Test fun missingReinstalledOrUnavailableChoicesAreNeverReadUsingOldIdentity() {
        val replacement = ready.copy(visibleApps = ready.visibleApps.map {
            if (it.identity == first) it.copy(identity = first.copy(firstInstallTime = 2)) else it })
        assertEquals(listOf(second), selectedAppsRecheckTargets(replacement))
        assertTrue(selectedAppsRecheckTargets(ready.copy(automaticProtection = ready.automaticProtection.copy(configurationAvailable = false))).isEmpty())
        assertTrue(selectedAppsRecheckTargets(ready.copy(visibleAppsLoadState = HomeSourceLoadState.LOADING)).isEmpty())
    }

    @Test fun all28AreCoveredWithinConfiguredParallelBound() = runBlocking {
        var active = 0
        var peak = 0
        val completed = mutableListOf<Int>()
        recheckSelectedAppsInChunks((1..28).toList()) { target ->
            active++
            peak = maxOf(peak, active)
            try { delay(5); completed += target } finally { active-- }
        }
        assertEquals(RECIPIENT_READ_PARALLELISM, peak)
        assertEquals((1..28).toList(), completed.sorted())
        assertEquals(0, active)
    }

    @Test fun cancellingBatchDoesNotStartLaterChunksAndReleasesChildren() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var active = 0
        val visited = mutableListOf<Int>()
        val job = launch {
            recheckSelectedAppsInChunks((1..28).toList()) {
                visited += it
                active++
                if (active == RECIPIENT_READ_PARALLELISM) started.complete(Unit)
                try { delay(10_000) } finally { active-- }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals((1..RECIPIENT_READ_PARALLELISM).toList(), visited)
        assertEquals(0, active)
    }
}
