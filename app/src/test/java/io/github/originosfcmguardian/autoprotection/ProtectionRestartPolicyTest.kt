package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionRestartPolicyTest {
    private val ready = ProtectionRestartInputs(
        consent = RestartConsentStatus.VALID,
        userUnlocked = true,
        suspension = RestartSuspensionStatus.NONE,
        identity = RestartIdentityStatus.VERIFIED,
        journal = RestartJournalStatus.CLEAR,
        budget = RestartBudgetStatus.READY,
        privilege = RestartPrivilegeEvidence(true, true, 2000, 0, true),
        service = RestartServiceEvidence(RestartServicePhase.ACTIVE, true),
    )

    @Test fun defaultAndLegacyWithoutDurableConsentRemainOffEvenWithLiveService() {
        assertEquals(ProtectionRestartDecision(false, ProtectionRestartStatus.OFF),
            protectionRestartDecision(ProtectionRestartInputs()))
        listOf(RestartConsentStatus.ABSENT, RestartConsentStatus.DISABLED).forEach { consent ->
            val decision = protectionRestartDecision(ready.copy(consent = consent))
            assertEquals(consent.name, ProtectionRestartStatus.OFF, decision.status)
            assertFalse(decision.desiredEnabled)
        }
    }

    @Test fun desireAloneCannotStartOrDeclareActive() {
        val decision = protectionRestartDecision(ProtectionRestartInputs(consent = RestartConsentStatus.VALID))
        assertTrue(decision.desiredEnabled)
        assertEquals(ProtectionRestartStatus.WAITING_UNLOCK, decision.status)
        assertEquals(ProtectionRestartStatus.BLOCKED_STORAGE,
            protectionRestartDecision(ProtectionRestartInputs(consent = RestartConsentStatus.VALID,
                userUnlocked = true)).status)
    }

    @Test fun consentFailuresDoNotInheritEarlierRuntimeReadiness() {
        checkCases(listOf(
            "revoked" to (ready.copy(consent = RestartConsentStatus.REVOKED) to ProtectionRestartStatus.STOPPED_BY_USER),
            "corrupt" to (ready.copy(consent = RestartConsentStatus.CORRUPT) to ProtectionRestartStatus.BLOCKED_STORAGE),
            "unknown" to (ready.copy(consent = RestartConsentStatus.UNKNOWN) to ProtectionRestartStatus.BLOCKED_STORAGE),
        ))
        listOf(RestartConsentStatus.REVOKED, RestartConsentStatus.CORRUPT, RestartConsentStatus.UNKNOWN).forEach {
            assertFalse(protectionRestartDecision(ready.copy(consent = it)).desiredEnabled)
        }
    }

    @Test fun newerStopOrPrivilegeRevocationSuspendsRestartWithoutErasingDesire() {
        listOf(RestartSuspensionStatus.USER_STOP, RestartSuspensionStatus.SYSTEM_STOP,
            RestartSuspensionStatus.PERMISSION_REVOKED).forEach { suspension ->
            val decision = protectionRestartDecision(ready.copy(suspension = suspension, userUnlocked = false))
            assertEquals(suspension.name, ProtectionRestartStatus.STOPPED_BY_USER, decision.status)
            assertTrue(decision.desiredEnabled)
        }
    }

    @Test fun lockedOrUnknownUnlockStateWaitsBeforeCredentialStorageChecks() {
        listOf(false, null).forEach { unlocked ->
            val input = ready.copy(userUnlocked = unlocked, suspension = RestartSuspensionStatus.UNKNOWN,
                journal = RestartJournalStatus.UNKNOWN)
            assertEquals(ProtectionRestartStatus.WAITING_UNLOCK, protectionRestartDecision(input).status)
        }
    }

    @Test fun unknownOrUnsafePersistedReadinessBlocks() {
        val cases = mutableListOf<Pair<String, Pair<ProtectionRestartInputs, ProtectionRestartStatus>>>()
        cases += "unknown stop read" to (ready.copy(suspension = RestartSuspensionStatus.UNKNOWN) to
            ProtectionRestartStatus.BLOCKED_STORAGE)
        RestartJournalStatus.entries.filter { it != RestartJournalStatus.CLEAR }.forEach {
            cases += "journal $it" to (ready.copy(journal = it) to ProtectionRestartStatus.BLOCKED_RECONCILIATION)
        }
        RestartIdentityStatus.entries.filter { it != RestartIdentityStatus.VERIFIED }.forEach {
            cases += "identity $it" to (ready.copy(identity = it) to ProtectionRestartStatus.BLOCKED_IDENTITY)
        }
        RestartBudgetStatus.entries.filter { it != RestartBudgetStatus.READY }.forEach {
            cases += "budget $it" to (ready.copy(budget = it) to ProtectionRestartStatus.BLOCKED_BUDGET)
        }
        checkCases(cases)
    }

    @Test fun deadMissingStaleUnauthorizedRootOrWrongUserConnectionCannotPass() {
        val connection = ready.privilege
        val connections = listOf(
            RestartPrivilegeEvidence(),
            connection.copy(binderAlive = false), connection.copy(binderAlive = null),
            connection.copy(permissionGranted = false), connection.copy(permissionGranted = null),
            connection.copy(uid = null), connection.copy(uid = 0), connection.copy(uid = 1000),
            connection.copy(uid = 102000), connection.copy(currentUser = null), connection.copy(currentUser = 10),
            connection.copy(freshForCurrentConnection = false),
        )
        connections.forEach { evidence ->
            assertEquals(evidence.toString(), ProtectionRestartStatus.WAITING_PRIVILEGE,
                protectionRestartDecision(ready.copy(privilege = evidence)).status)
        }
    }

    @Test fun runtimeUnknownFailedOrStaleNeverDisplaysStartingOrActive() {
        listOf(
            RestartServiceEvidence(),
            RestartServiceEvidence(RestartServicePhase.FAILED, true),
            RestartServiceEvidence(RestartServicePhase.UNKNOWN, true),
            RestartServiceEvidence(RestartServicePhase.START_REQUESTED, false),
            RestartServiceEvidence(RestartServicePhase.ACTIVE, false),
        ).forEach { evidence ->
            assertEquals(evidence.toString(), ProtectionRestartStatus.BLOCKED_CONFIGURATION,
                protectionRestartDecision(ready.copy(service = evidence)).status)
        }
    }

    @Test fun readyUnstartedRequestedAndActiveAreDistinct() {
        checkCases(listOf(
            "not started" to (ready.copy(service = RestartServiceEvidence(RestartServicePhase.NOT_STARTED)) to
                ProtectionRestartStatus.READY_TO_START),
            "requested" to (ready.copy(service = RestartServiceEvidence(RestartServicePhase.START_REQUESTED, true)) to
                ProtectionRestartStatus.STARTING),
            "active" to (ready to ProtectionRestartStatus.ACTIVE),
        ))
    }

    @Test fun blockersPrecedeOldActiveServiceEvidence() {
        var input = ready.copy(userUnlocked = false, suspension = RestartSuspensionStatus.SYSTEM_STOP,
            journal = RestartJournalStatus.PENDING, identity = RestartIdentityStatus.CHANGED,
            budget = RestartBudgetStatus.EXHAUSTED, privilege = RestartPrivilegeEvidence())
        assertEquals(ProtectionRestartStatus.OFF,
            protectionRestartDecision(input.copy(consent = RestartConsentStatus.ABSENT)).status)
        assertEquals(ProtectionRestartStatus.STOPPED_BY_USER, protectionRestartDecision(input).status)
        input = input.copy(suspension = RestartSuspensionStatus.NONE)
        assertEquals(ProtectionRestartStatus.WAITING_UNLOCK, protectionRestartDecision(input).status)
        input = input.copy(userUnlocked = true)
        assertEquals(ProtectionRestartStatus.BLOCKED_RECONCILIATION, protectionRestartDecision(input).status)
        input = input.copy(journal = RestartJournalStatus.CLEAR)
        assertEquals(ProtectionRestartStatus.BLOCKED_IDENTITY, protectionRestartDecision(input).status)
        input = input.copy(identity = RestartIdentityStatus.VERIFIED)
        assertEquals(ProtectionRestartStatus.BLOCKED_BUDGET, protectionRestartDecision(input).status)
        input = input.copy(budget = RestartBudgetStatus.READY)
        assertEquals(ProtectionRestartStatus.WAITING_PRIVILEGE, protectionRestartDecision(input).status)
    }

    private fun checkCases(cases: List<Pair<String, Pair<ProtectionRestartInputs, ProtectionRestartStatus>>>) {
        cases.forEach { (label, case) ->
            assertEquals(label, case.second, protectionRestartDecision(case.first).status)
        }
    }
}
