package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalState
import io.github.originosfcmguardian.recovery.RecoveryJournalStorage
import io.github.originosfcmguardian.recovery.StoppedRecoveryJournal
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProtectionStatusNotificationTest {
    private val first = SelectedTargetIdentity("org.telegram.messenger", 10123, 100, listOf("a".repeat(64)))
    private val second = first.copy(packageName = "example.notification.recipient", uid = 10124)
    private val clock = BudgetClock(14, 1000, 1000000)

    @Test fun successfulRefreshChecksAuthorizationAndDisplaysExactlyOnce() {
        var checks = 0
        var refreshes = 0
        refreshProtectionStatusNotification({ checks++; true }, { refreshes++ })
        assertEquals(1, checks)
        assertEquals(1, refreshes)
    }

    @Test fun unauthorizedOwnerSkipsAllNotificationConstructionAndDisplay() {
        var refreshes = 0
        refreshProtectionStatusNotification({ false }, { refreshes++ })
        assertEquals(0, refreshes)
    }

    @Test fun staleCapturedGrantCannotBorrowReplacementAuthorization() {
        val storage = AuthorityStorage()
        val store = ProtectionConsentStore(storage)
        val old = store.grantRecipientExplicit(first, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        store.grantRecipientExplicit(second, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        assertTrue(store.enableMaster(store.readSnapshot(), clock))
        val replacement = store.desiredLeases()
        assertFalse(store.verifyAuthorized(old))
        assertTrue(replacement.all(store::verifyAuthorized))
        var oldDisplays = 0
        var newDisplays = 0
        refreshProtectionStatusNotification({ store.verifyAuthorized(old) }, { oldDisplays++ })
        refreshProtectionStatusNotification({ replacement.all(store::verifyAuthorized) }, { newDisplays++ })
        assertEquals(0, oldDisplays)
        assertEquals(1, newDisplays)
    }

    @Test fun authorizationFailurePropagatesBeforeTheDisplayBoundary() {
        val failure = IllegalStateException("authorization fixture")
        var refreshes = 0
        val caught = assertThrows(IllegalStateException::class.java) {
            refreshProtectionStatusNotification({ throw failure }, { refreshes++ })
        }
        assertSame(failure, caught)
        assertEquals(0, refreshes)
    }

    @Test fun ordinaryDisplayFailuresPreserveBothLeasesBudgetJournalAndTicketWithoutPauseOrRetry() {
        listOf(SecurityException("notify fixture"), IllegalArgumentException("construction fixture"),
            Exception("display fixture")).forEach { failure ->
            val storage = AuthorityStorage()
            val store = ProtectionConsentStore(storage)
            store.grantRecipientExplicit(first, VERIFIED_AUTOSTART_FINGERPRINT, clock)
            store.grantRecipientExplicit(second, VERIFIED_AUTOSTART_FINGERPRINT, clock)
            assertTrue(store.enableMaster(store.readSnapshot(), clock))
            val leases = store.desiredLeases()
            assertEquals(2, leases.size)
            assertEquals(AutomaticCheckDecision.Recover, store.take(leases.first(), clock))
            val before = store.readSnapshot()!!
            val authorityBytes = storage.bytes!!.clone()
            val authorityWrites = storage.writes
            val record = StoppedRecoveryRecord(1, first, 100, 99, VERIFIED_AUTOSTART_FINGERPRINT, 0,
                true, true, true, StoppedRecoveryStage.FINISHED, StoppedRecoveryOutcome.FLAG_CLEARED)
            val journalStorage = JournalStorage(RecoveryJournalCodec.encode(RecoveryJournalState(1, record)))
            val journal = StoppedRecoveryJournal(journalStorage)
            val journalBytes = journalStorage.bytes.clone()
            val writes = AutomaticWriteGate().also { it.open() }
            val ticket = writes.ticket()
            var pauseCalls = 0
            var displayAttempts = 0
            var nextReads = 0

            try {
                refreshProtectionStatusNotification(
                    authorized = { leases.all(store::verifyAuthorized) },
                    refresh = { displayAttempts++; throw failure },
                )
                // The original caller can proceed normally, with no immediate display retry.
                assertEquals(record, journal.load().latest)
                nextReads++
            } catch (_: Exception) {
                // Matches collect's global consent retirement if a display failure escapes.
                pauseCalls++
                store.revoke()
                writes.block()
            }

            assertEquals(failure.javaClass.name, 0, pauseCalls)
            assertEquals(1, displayAttempts)
            assertEquals(1, nextReads)
            assertEquals(leases, store.desiredLeases())
            assertTrue(leases.all(store::verifyAuthorized))
            assertEquals(before, store.readSnapshot())
            assertEquals(before.budget, store.readSnapshot()!!.budget)
            assertEquals(listOf(1000L), store.readSnapshot()!!.budget.attempts)
            assertArrayEquals(authorityBytes, storage.bytes)
            assertEquals(authorityWrites, storage.writes)
            assertArrayEquals(journalBytes, journalStorage.bytes)
            assertEquals(0, journalStorage.writes)
            assertEquals(0, journalStorage.uncertainties)
            assertTrue(writes.permits(ticket))
            assertEquals(ticket, writes.ticket())
        }
    }

    @Test fun displayCancellationPropagatesUnchangedAndIsNeverRetried() {
        val failure = CancellationException("cancel fixture")
        var attempts = 0
        val caught = assertThrows(CancellationException::class.java) {
            refreshProtectionStatusNotification({ true }, { attempts++; throw failure })
        }
        assertSame(failure, caught)
        assertEquals(1, attempts)
    }

    @Test fun displayErrorPropagatesUnchangedAndIsNeverRetried() {
        val failure = AssertionError("fatal fixture")
        var attempts = 0
        val caught = assertThrows(AssertionError::class.java) {
            refreshProtectionStatusNotification({ true }, { attempts++; throw failure })
        }
        assertSame(failure, caught)
        assertEquals(1, attempts)
    }

    private class AuthorityStorage : ProtectionAuthorityStorage {
        var bytes: ByteArray? = null
        var writes = 0
        override fun <T> exclusive(action: () -> T): T = action()
        override fun read() = bytes?.let(ProtectionAuthorityCodec::decode)
        override fun write(value: ProtectionAuthoritySnapshot) {
            writes++
            bytes = ProtectionAuthorityCodec.encode(value)
        }
    }

    private class JournalStorage(var bytes: ByteArray) : RecoveryJournalStorage {
        var writes = 0
        var uncertainties = 0
        override fun read() = bytes.clone()
        override fun write(bytes: ByteArray) { writes++; this.bytes = bytes.clone() }
        override fun markUncertain() { uncertainties++ }
    }
}
