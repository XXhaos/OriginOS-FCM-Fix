package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class WorkModePresentationTest {
    @Test fun unavailableStatesExposeInstallationStartupAndDirectAuthorizationActions() {
        assertEquals("获取 Shizuku", shizukuWorkModePresentation(ShizukuStatusSnapshot(ShizukuStatus.NOT_INSTALLED, "")).actionLabel)
        assertEquals("打开 Shizuku", shizukuWorkModePresentation(ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "")).actionLabel)
        assertEquals("Shizuku 授权", shizukuWorkModePresentation(ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "")).actionLabel)
    }

    @Test fun blockedAuthorizationGoesToManagerAndReadyDoesNotEnableProtection() {
        assertEquals("打开 Shizuku", shizukuWorkModePresentation(ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "", true)).actionLabel)
        val ready = shizukuWorkModePresentation(ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, ""))
        assertNull(ready.actionLabel)
        assertEquals("已授权 · shell 就绪", ready.statusLabel)
        assertTrue(ready.summary.contains("保护仍需单独开启"))
        assertEquals("工作方式：外置 Shizuku", CURRENT_RECOVERY_BACKEND_LABEL)
    }

    @Test fun rootAndErrorAreActionableButNeverPresentedAsReady() {
        for (status in listOf(ShizukuStatus.READY_ROOT, ShizukuStatus.ERROR)) {
            val presentation = shizukuWorkModePresentation(ShizukuStatusSnapshot(status, ""))
            assertEquals("打开 Shizuku", presentation.actionLabel)
            assertFalse(presentation.statusLabel.contains("就绪"))
        }
    }
}
