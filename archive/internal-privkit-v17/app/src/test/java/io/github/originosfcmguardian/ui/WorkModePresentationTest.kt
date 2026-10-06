package io.github.originosfcmguardian.ui

import org.junit.Assert.*
import org.junit.Test

class WorkModePresentationTest {
    @Test fun internalIdentityReadyNeverClaimsInternalProtection() {
        val presentation = internalConnectionPresentation(false, true, false)
        assertEquals("内置身份已核验", presentation.headline)
        assertEquals("当前自动保护仍使用外部 Shizuku", presentation.protectionNotice)
        assertEquals("当前保护方式：外部 Shizuku", CURRENT_RECOVERY_BACKEND_LABEL)
    }

    @Test fun actionAvailabilityRespectsBusyCleanupAndNotificationSession() {
        val idle = internalConnectionPresentation(false, false, false)
        assertTrue(idle.canPair)
        assertTrue(idle.canStart)
        for (blocked in listOf(
            internalConnectionPresentation(true, true, false),
            internalConnectionPresentation(false, true, true),
            internalConnectionPresentation(false, true, false, pairingActive = true),
        )) {
            assertFalse(blocked.canPair)
            assertFalse(blocked.canStart)
        }
    }

}
