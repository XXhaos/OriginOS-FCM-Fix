package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test

class LegacyNegativeAdmissionTest {
    @Test fun ownershipFlipAfterMatchedNotificationForwardsOnlyOneNegativeOutsideTheLocalBoundary() {
        val monitor = Any()
        var local = 0
        var forwarded = 0
        val route = synchronized(monitor) {
            // The matching old session was captured at revision 4; migration now retired it.
            legacyNegativeAdmission(true, "old", 4, 5, null, null, "old").also {
                if (it == LegacyNegativeAdmission.LOCAL) local++
            }
        }
        if (route == LegacyNegativeAdmission.REMOTE) {
            assertFalse(Thread.holdsLock(monitor))
            forwarded++
        }
        assertEquals(0, local)
        assertEquals(1, forwarded)
    }

    @Test fun replacementLocalSessionCannotBeStoppedByAnOldMatchedNotification() {
        assertEquals(LegacyNegativeAdmission.STALE,
            legacyNegativeAdmission(false, "old", 4, 5, null, "replacement"))
        assertEquals(LegacyNegativeAdmission.STALE,
            legacyNegativeAdmission(false, "old", 4, 5, null, "old"))
        assertEquals(LegacyNegativeAdmission.STALE,
            legacyNegativeAdmission(false, "old", 4, 4, null, "replacement"))
        assertEquals(LegacyNegativeAdmission.LOCAL,
            legacyNegativeAdmission(false, "old", 4, 4, null, "old"))
        assertEquals(LegacyNegativeAdmission.LOCAL,
            legacyNegativeAdmission(false, "old", 4, 4, "old", null))
        // Replacement B won before migration: a previously matched A cannot stop B's daemon owner.
        assertEquals(LegacyNegativeAdmission.STALE,
            legacyNegativeAdmission(true, "old", 4, 6, null, null, "replacement"))
        assertEquals(LegacyNegativeAdmission.STALE,
            legacyNegativeAdmission(true, "old", 4, 5, null, "replacement"))
        assertEquals(LegacyNegativeAdmission.REMOTE,
            legacyNegativeAdmission(true, "old", 4, 4, null, "old"))
    }

    @Test fun unscopedMasterOffKeepsItsIntentAcrossTheOwnershipBoundary() {
        assertEquals(LegacyNegativeAdmission.LOCAL,
            legacyNegativeAdmission(false, null, null, 5, null, null))
        assertEquals(LegacyNegativeAdmission.REMOTE,
            legacyNegativeAdmission(true, null, null, 5, null, null))
    }

    @Test fun onlyTheExactOriginalRetiredNotificationCanForwardAfterMigrationClearedTheGrant() {
        assertTrue(matchesLegacyNotificationStop("original", null, null, true, "original"))
        assertFalse(matchesLegacyNotificationStop("stale", null, null, true, "original"))
        assertFalse(matchesLegacyNotificationStop("original", null, null, true, null))
        assertFalse(matchesLegacyNotificationStop("original", null, "replacement", false, "original"))
        assertTrue(matchesLegacyNotificationStop("replacement", null, "replacement", false, "original"))
    }
}
