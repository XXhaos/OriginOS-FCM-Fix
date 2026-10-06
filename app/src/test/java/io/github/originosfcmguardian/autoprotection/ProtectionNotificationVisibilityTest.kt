package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionNotificationVisibilityTest {
    @Test fun allowedAndBlockedSystemReadsStayDistinct() {
        assertEquals(ProtectionNotificationVisibility.ALLOWED, readProtectionNotificationVisibility { true })
        assertEquals(ProtectionNotificationVisibility.BLOCKED, readProtectionNotificationVisibility { false })
    }

    @Test fun failedReadDoesNotClaimSystemNotificationsAreBlocked() {
        assertEquals(ProtectionNotificationVisibility.UNKNOWN, readProtectionNotificationVisibility {
            throw SecurityException("Notification settings unavailable")
        })
        assertEquals(ProtectionNotificationVisibility.UNKNOWN, readProtectionNotificationVisibility {
            throw IllegalStateException("Notification service unavailable")
        })
    }
}
