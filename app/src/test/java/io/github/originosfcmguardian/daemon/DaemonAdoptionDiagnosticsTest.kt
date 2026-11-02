package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonAdoptionDiagnosticsTest {
    @Test fun failedPreflightIsRetainedForExportWithoutStartingAnotherAttempt() {
        val journal = DaemonAdoptionJournal()
        assertEquals(DaemonAdoptionStatus.NOT_ATTEMPTED, journal.snapshot().status)
        journal.begin(100)
        journal.failed(DaemonAdoptionStage.EXIT_HISTORY, DaemonAdoptionReason.EXIT_HISTORY_UNAVAILABLE, 110)
        val failed = journal.snapshot()
        repeat(100) { assertEquals(failed, journal.snapshot()) }
        assertEquals(1L, failed.attemptCount)
        assertEquals(100L, failed.lastAttemptAtEpochMillis)
        assertEquals(110L, failed.lastOutcomeAtEpochMillis)
        assertEquals(DaemonAdoptionStage.EXIT_HISTORY, failed.stage)
        assertEquals(DaemonAdoptionReason.EXIT_HISTORY_UNAVAILABLE, failed.reason)
        journal.begin(200)
        journal.adopted(220)
        assertEquals(DaemonAdoptionStatus.ADOPTED, journal.snapshot().status)
        assertEquals(2L, journal.snapshot().attemptCount)
    }

    @Test fun remotePreflightCanReportOnlyFixedReadOnlyFailureCategories() {
        val exit = requireNotNull(daemonPreflightFailure("EXIT_HISTORY", "EXIT_HISTORY_UNAVAILABLE"))
        assertEquals(DaemonAdoptionStage.EXIT_HISTORY, exit.stage)
        assertEquals(DaemonAdoptionReason.EXIT_HISTORY_UNAVAILABLE, exit.reason)
        assertNotNull(daemonPreflightFailure("AUTHENTICATION", "PERMISSION_UNAVAILABLE"))
        assertNull(daemonPreflightFailure("HANDOFF", "HANDOFF_UNAVAILABLE"))
        assertNull(daemonPreflightFailure("AUTHENTICATION", "NONE"))
        assertNull(daemonPreflightFailure("EXIT_HISTORY", "PERMISSION_DENIED"))
        assertNull(daemonPreflightFailure("arbitrary payload", "arbitrary payload"))
        assertNull(daemonPreflightFailure(null, null))
    }

    @Test fun migrationFailureRetainsTheActualDrainPhaseInsteadOfGenericHandoff() {
        val journal = DaemonAdoptionJournal()
        journal.begin(100)
        journal.progress(DaemonAdoptionStage.HANDOFF_PREPARE)
        journal.progress(DaemonAdoptionStage.DRAIN_HEARTBEAT)
        val phase = journal.snapshot().stage
        journal.failed(phase, daemonAdoptionStageFailureReason(phase), 110)
        assertEquals(DaemonAdoptionStage.DRAIN_HEARTBEAT, journal.snapshot().stage)
        assertEquals(DaemonAdoptionReason.DRAIN_UNCONFIRMED, journal.snapshot().reason)
        assertEquals(1L, journal.snapshot().attemptCount)
        journal.progress(DaemonAdoptionStage.BOOTSTRAP)
        journal.failed(DaemonAdoptionStage.BOOTSTRAP, DaemonAdoptionReason.OWNER_CONFLICT, 120)
        assertEquals(DaemonAdoptionReason.OWNER_CONFLICT, journal.snapshot().reason)
        journal.progress(DaemonAdoptionStage.REFRESH)
        journal.adopted(130)
        assertEquals(DaemonAdoptionStatus.ADOPTED, journal.snapshot().status)
        assertEquals(1L, journal.snapshot().attemptCount) // Same exact migration, no recreated consent.
    }
}
