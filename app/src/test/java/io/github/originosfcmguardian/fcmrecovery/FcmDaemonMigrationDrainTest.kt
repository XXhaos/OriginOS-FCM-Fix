package io.github.originosfcmguardian.fcmrecovery

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test

class FcmDaemonMigrationDrainTest {
    @Test fun consumedWithoutKnownMatchingResultCannotBeAttestedClean() {
        val saved = FcmRecoverySnapshot(consumed = true, consumedAt = 1000, consumedElapsed = 100)
        assertTrue(unsettledFcmDaemonMigrationWork(saved))
        val result = FcmRecoveryEvent(1010, 110, FcmRecoveryEventAction.REQUEST_RESULT,
            status = FcmRecoveryStatus.REQUESTED, reason = FcmRecoveryReason.COMMAND_ACCEPTED,
            operation = FcmRecoveryOperation.SERVICE_ABSENT_SEND)
        assertFalse(unsettledFcmDaemonMigrationWork(saved.copy(events = listOf(result))))
        assertTrue(unsettledFcmDaemonMigrationWork(saved.copy(events = listOf(result.copy(
            status = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.BRIDGE_UNAVAILABLE)))))
        assertTrue(unsettledFcmDaemonMigrationWork(saved.copy(events = listOf(result.copy(atMillis = 900, elapsedMillis = 90)))))
        assertTrue(unsettledFcmDaemonMigrationWork(saved.copy(events = listOf(result.copy(operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS)))))
        assertFalse(unsettledFcmDaemonMigrationWork(saved.copy(consumed = false)))
    }
}
