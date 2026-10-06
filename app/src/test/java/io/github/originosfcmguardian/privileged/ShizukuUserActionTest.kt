package io.github.originosfcmguardian.privileged

import org.junit.Assert.*
import org.junit.Test

class ShizukuUserActionTest {
    @Test fun shellBinderWithoutPermissionRequestsPermissionOnlyOnExplicitAction() {
        val status = classifyShizukuStatus(true, true, 2000, false)
        assertEquals(ShizukuStatus.PERMISSION_REQUIRED, status)
        assertEquals(ShizukuUserAction.REQUEST_PERMISSION, shizukuUserAction(ShizukuStatusSnapshot(status, "")))
    }

    @Test fun dontAskAgainRoutesToManagerInsteadOfAnotherPermissionRequest() {
        assertEquals(ShizukuUserAction.OPEN_MANAGER,
            shizukuUserAction(ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "", true)))
    }

    @Test fun binderLossAndUnsupportedIdentitiesCannotBecomeAuthorizedReady() {
        assertEquals(ShizukuStatus.NOT_RUNNING, classifyShizukuStatus(true, false, 2000, true))
        assertEquals(ShizukuStatus.READY_ROOT, classifyShizukuStatus(true, true, 0, true))
        assertEquals(ShizukuStatus.ERROR, classifyShizukuStatus(true, true, 1234, true))
        for (status in listOf(ShizukuStatus.NOT_RUNNING, ShizukuStatus.READY_ROOT, ShizukuStatus.ERROR))
            assertEquals(ShizukuUserAction.OPEN_MANAGER, shizukuUserAction(ShizukuStatusSnapshot(status, "")))
    }

    @Test fun grantedShellRequiresNoAuthorizationRetryAndMissingManagerGetsOfficialDownload() {
        assertEquals(ShizukuStatus.READY_SHELL, classifyShizukuStatus(true, true, 2000, true))
        assertEquals(ShizukuUserAction.REFRESH, shizukuUserAction(ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "")))
        assertEquals(ShizukuUserAction.DOWNLOAD, shizukuUserAction(ShizukuStatusSnapshot(ShizukuStatus.NOT_INSTALLED, "")))
    }
}
