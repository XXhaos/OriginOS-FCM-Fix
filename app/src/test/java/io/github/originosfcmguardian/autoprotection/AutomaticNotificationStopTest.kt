package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test

class AutomaticNotificationStopTest {
    private class Runtime(var remote: Boolean = false, var pending: String? = null,
        var active: String? = null, var retired: String? = null) {
        var revision = 1L
        var token = 1L
        var localStops = 0
        var remoteStops = 0
        var cancellations = 0

        fun notification(id: String?, issuedToken: Long? = token, afterMatch: () -> Unit = {}) {
            dispatchAutomaticNotificationStop(id, issuedToken) { expected, capturedToken ->
                if (!matchesLegacyNotificationStop(expected, pending, active, remote, retired))
                    return@dispatchAutomaticNotificationStop
                val capturedRevision = revision
                afterMatch()
                if (capturedToken != token) return@dispatchAutomaticNotificationStop
                cancellations++
                when (legacyNegativeAdmission(remote, expected, capturedRevision, revision, pending, active, retired)) {
                    LegacyNegativeAdmission.LOCAL -> localStops++
                    LegacyNegativeAdmission.REMOTE -> remoteStops++
                    LegacyNegativeAdmission.STALE -> Unit
                }
            }
        }
    }

    @Test fun missingOrStaleServiceStopNeverBecomesUnscopedRemoteOff() {
        val remote = Runtime(remote = true, retired = "original")
        for (session in listOf(null, "", "stale")) remote.notification(session)
        remote.notification("original", issuedToken = null)
        assertEquals(0, remote.remoteStops)
        assertEquals(0, remote.cancellations)
        // A new App process has no original local notification authority to inherit.
        Runtime(remote = true).also {
            it.notification("original")
            assertEquals(0, it.remoteStops)
        }
    }

    @Test fun exactCurrentLocalAndOriginalMigrationNotificationKeepTheirScopedStop() {
        val local = Runtime(active = "current")
        local.notification("current")
        assertEquals(1, local.localStops)
        assertEquals(0, local.remoteStops)
        val migrating = Runtime(active = "current")
        migrating.notification("current") {
            migrating.remote = true; migrating.retired = "current"
            migrating.active = null; migrating.revision++
        }
        assertEquals(0, migrating.localStops)
        assertEquals(1, migrating.remoteStops)
    }

    @Test fun replacementBeforeDeliveryOrDuringAdmissionRejectsOldNotification() {
        val replacement = Runtime(active = "replacement")
        replacement.notification("old")
        assertEquals(0, replacement.localStops)
        val racing = Runtime(active = "old")
        racing.notification("old") { racing.active = "replacement"; racing.revision++; racing.token++ }
        assertEquals(0, racing.localStops)
        assertEquals(0, racing.remoteStops)
        assertEquals(0, racing.cancellations)
        val newerOn = Runtime(remote = true, retired = "original")
        newerOn.token = 2
        newerOn.notification("original", issuedToken = 1)
        assertEquals(0, newerOn.remoteStops)
        assertEquals(0, newerOn.cancellations)
    }

    @Test fun updatingAnOldNotificationCannotAttachAReplacementOnToken() {
        val original = automaticNotificationStopToken(null, "original") { 4 }
        assertEquals("original" to 4L, original)
        assertEquals(original, automaticNotificationStopToken(original, "original") { error("Old token must remain bound") })
        assertEquals("replacement" to 5L, automaticNotificationStopToken(original, "replacement") { 5 })
    }

    @Test fun explicitUserMasterOffRemainsUnscopedAcrossLocalAndRemoteOwnership() {
        assertEquals(LegacyNegativeAdmission.LOCAL,
            legacyNegativeAdmission(false, null, null, 9, null, "replacement"))
        assertEquals(LegacyNegativeAdmission.REMOTE,
            legacyNegativeAdmission(true, null, null, 9, null, null, "retired"))
    }
}
