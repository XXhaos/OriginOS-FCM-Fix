package io.github.originosfcmguardian.autostart

import org.junit.Assert.assertEquals
import org.junit.Test

class StoppedRecoveryPolicyTest {
    private val target = SelectedTargetIdentity("org.telegram.messenger", 10434, 1000, listOf("a".repeat(64)))
    private val request = StoppedRecoveryRequest(1, target)
    private val valid = StoppedRecoveryProbe(
        userConfirmedRecovery = true,
        selectedIdentity = target,
        fingerprint = VERIFIED_AUTOSTART_FINGERPRINT,
        currentUser = 0,
        authorizedShell = true,
        serviceUid = 2000,
        observedIdentity = target,
        exclusiveUidOwner = true,
        stopped = true,
    )

    private fun assertBlocked(reason: RecoveryBlockReason, probe: StoppedRecoveryProbe) {
        assertEquals(StoppedRecoveryPreflight.Blocked(reason), preflightStoppedRecovery(request, probe))
    }

    @Test fun validStoppedTargetNeedsServerRecheckWhileUnstoppedTargetNeedsNoWrite() {
        assertEquals(StoppedRecoveryPreflight.ReadyForServerRecheck, preflightStoppedRecovery(request, valid))
        assertEquals(StoppedRecoveryPreflight.NoWriteNeeded, preflightStoppedRecovery(request, valid.copy(stopped = false)))
    }

    @Test fun missingCurrentUserActionAndRemovedOrReplacedSelectionBlock() {
        assertBlocked(RecoveryBlockReason.USER_ACTION_REQUIRED, valid.copy(userConfirmedRecovery = false))
        assertBlocked(RecoveryBlockReason.TARGET_NOT_SELECTED, valid.copy(selectedIdentity = null))
        assertBlocked(RecoveryBlockReason.TARGET_NOT_SELECTED,
            valid.copy(selectedIdentity = target.copy(firstInstallTime = 2000)))
    }

    @Test fun unknownBuildAndUserBlockEvenIfFlagIsTrue() {
        for (fingerprint in listOf(null, "new firmware")) {
            assertBlocked(RecoveryBlockReason.UNSUPPORTED_BUILD, valid.copy(fingerprint = fingerprint))
        }
        for (user in listOf(null, 10, 666, 999)) {
            assertBlocked(RecoveryBlockReason.WRONG_USER, valid.copy(currentUser = user))
        }
    }

    @Test fun unprivilegedRootAndUnknownServicesNeverPassShellGate() {
        assertBlocked(RecoveryBlockReason.SHELL_UNAVAILABLE, valid.copy(authorizedShell = false))
        for (uid in listOf(null, 0, 1000, 10434)) {
            assertBlocked(RecoveryBlockReason.SHELL_UNAVAILABLE, valid.copy(serviceUid = uid))
        }
    }

    @Test fun freshIdentityChangesOrMissingReadsBlock() {
        assertBlocked(RecoveryBlockReason.IDENTITY_UNKNOWN, valid.copy(observedIdentity = null))
        for (replacement in listOf(target.copy(uid = 10435), target.copy(firstInstallTime = 2000),
            target.copy(signerSha256 = listOf("b".repeat(64))), target.copy(packageName = "example.other"))) {
            assertBlocked(RecoveryBlockReason.IDENTITY_CHANGED, valid.copy(observedIdentity = replacement))
        }
    }

    @Test fun sharedOrUnknownUidOwnershipAndUnknownFlagBlock() {
        assertBlocked(RecoveryBlockReason.UID_OWNERSHIP_UNKNOWN, valid.copy(exclusiveUidOwner = null))
        assertBlocked(RecoveryBlockReason.UID_NOT_EXCLUSIVE, valid.copy(exclusiveUidOwner = false))
        assertBlocked(RecoveryBlockReason.STOPPED_UNKNOWN, valid.copy(stopped = null))
    }

    @Test fun invalidRequestAndUnverifiedTargetCannotProceed() {
        for (id in listOf(0L, -1L)) {
            assertEquals(StoppedRecoveryPreflight.Blocked(RecoveryBlockReason.INVALID_REQUEST),
                preflightStoppedRecovery(request.copy(requestId = id), valid))
        }
        val other = target.copy(packageName = "example.other")
        assertEquals(StoppedRecoveryPreflight.Blocked(RecoveryBlockReason.UNSUPPORTED_TARGET),
            preflightStoppedRecovery(request.copy(expectedIdentity = other),
                valid.copy(selectedIdentity = other, observedIdentity = other)))
    }

    @Test fun authoritativeReadbackDistinguishesFalseTrueAndMissingValue() {
        assertEquals(StoppedRecoveryReadback.FLAG_CLEARED, classifyStoppedRecoveryReadback(request, target, false))
        assertEquals(StoppedRecoveryReadback.STILL_STOPPED, classifyStoppedRecoveryReadback(request, target, true))
        assertEquals(StoppedRecoveryReadback.UNCONFIRMED, classifyStoppedRecoveryReadback(request, target, null))
        assertEquals(StoppedRecoveryReadback.UNCONFIRMED, classifyStoppedRecoveryReadback(request, null, false))
    }

    @Test fun replacedIdentityCannotInheritConfirmedFalseReadback() {
        for (replacement in listOf(target.copy(uid = 10435), target.copy(firstInstallTime = 2000),
            target.copy(signerSha256 = listOf("b".repeat(64))))) {
            assertEquals(StoppedRecoveryReadback.UNCONFIRMED,
                classifyStoppedRecoveryReadback(request, replacement, false))
        }
    }
}
