package io.github.originosfcmguardian.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateInstallPolicyTest {
    @Test fun `first request opens source settings only after verified download`() {
        val decision = updateInstallDecision(apkValid = true, permissionGranted = false, allowPermissionRequest = true)
        assertEquals(AppUpdatePhase.READY, decision.phase)
        assertEquals(UpdateInstallLaunch.SOURCE_PERMISSION, decision.launch)
    }

    @Test fun `denied permission return stays ready and does not loop back to settings or installer`() {
        val decision = updateInstallDecision(apkValid = true, permissionGranted = false, allowPermissionRequest = false)
        assertEquals(AppUpdatePhase.READY, decision.phase)
        assertEquals(UpdateInstallLaunch.NONE, decision.launch)
        assertNotNull(decision.message)
    }

    @Test fun `granted permission return launches installer after fresh APK check`() {
        val decision = updateInstallDecision(apkValid = true, permissionGranted = true, allowPermissionRequest = false)
        assertEquals(AppUpdatePhase.INSTALLING, decision.phase)
        assertEquals(UpdateInstallLaunch.INSTALLER, decision.launch)
    }

    @Test fun `missing or stale cached APK always offers redownload regardless of source permission`() {
        listOf(true, false).forEach { permission ->
            listOf(true, false).forEach { mayAsk ->
                val decision = updateInstallDecision(apkValid = false, permissionGranted = permission,
                    allowPermissionRequest = mayAsk)
                assertEquals(AppUpdatePhase.AVAILABLE, decision.phase)
                assertEquals(UpdateInstallLaunch.NONE, decision.launch)
            }
        }
    }

    @Test fun `installer cancellation becomes retryable without claiming update installed`() {
        val state = updateInstallerReturned(AppUpdateUiState(phase = AppUpdatePhase.INSTALLING))
        assertEquals(AppUpdatePhase.READY, state.phase)
        assertNull(state.launch)
        assertNotNull(state.message)
    }

    @Test fun `late installer callback cannot overwrite later check or download`() {
        AppUpdatePhase.entries.filter { it != AppUpdatePhase.INSTALLING }.forEach { phase ->
            val state = AppUpdateUiState(phase = phase, percent = 71)
            assertSame(state, updateInstallerReturned(state))
        }
    }
}
