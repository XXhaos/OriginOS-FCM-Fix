package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import org.junit.Assert.*
import org.junit.Test

class DaemonPointerAttestationTest {
    private val owner = RootFixtures.owner
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10435, 100, listOf("c".repeat(64))), 79, 200)
    private val caller = DaemonCallerEvidence(10435, installation)
    private val before = RootFixtures.snapshot().control
    private fun plan(patch: Map<DaemonDomainRole, ByteArray?> = emptyMap()) = DaemonControlTransitionPlan(
        "replace", before, before.copy(revision = 2), DaemonControlTransition.SET_AUTHORITY, patch)

    @Test fun randomChallengesAreBoundedOneUseAndReconciliationCannotAuthorizeATransition() {
        val registry = DaemonPointerAttestationRegistry(owner, installation, 2)
        val first = registry.issue(before, null)
        val second = registry.issue(before, null)
        assertNotEquals(first.nonce, second.nonce)
        assertTrue(first.nonce.length >= 32)
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(first, first.pointer)))
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(second, second.pointer)))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(second, second.pointer)))
        assertNull(registry.transition(second.pointer))
        assertTrue(registry.consumeReconciliation(second.pointer))
        assertFalse(registry.consumeReconciliation(second.pointer))
        // A newer issue evicts obsolete entries; abandonment cannot exhaust the owner process.
        val bounded = DaemonPointerAttestationRegistry(owner, installation, 1)
        var previous = bounded.issue(before, null)
        repeat(40) {
            val fresh = bounded.issue(before, null)
            assertNotEquals(previous.nonce, fresh.nonce)
            assertFalse(bounded.attest(caller, DaemonAppPointerAttestation(previous, previous.pointer)))
            bounded.cancel(previous)
            assertTrue(bounded.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
            previous = fresh
        }
        assertTrue(bounded.consumeReconciliation(previous.pointer))
        repeat(40) {
            bounded.issue(before, null)
            bounded.invalidateCurrent()
        }
        val current = bounded.issue(before, null)
        assertTrue(bounded.attest(caller, DaemonAppPointerAttestation(current, current.pointer)))
    }

    @Test fun mutablePatchInputsCannotChangeThePlanOrItsAuthenticatedChallenge() {
        val bytes = byteArrayOf(1)
        val input = mutableMapOf<DaemonDomainRole, ByteArray?>(DaemonDomainRole.SELECTION to bytes)
        val proposal = plan(input)
        val digest = proposal.patchDigest
        bytes[0] = 9; input.clear()
        assertArrayEquals(byteArrayOf(1), proposal.patch()[DaemonDomainRole.SELECTION])
        proposal.patch()[DaemonDomainRole.SELECTION]!![0] = 5
        assertEquals(digest, proposal.patchDigest)
        assertArrayEquals(byteArrayOf(1), proposal.patch()[DaemonDomainRole.SELECTION])
        val registry = DaemonPointerAttestationRegistry(owner, installation)
        val challenge = registry.issue(proposal.next, proposal)
        assertEquals(digest, challenge.patchDigest)
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertSame(proposal, registry.transition(challenge.pointer))
        assertFalse(registry.consumeReconciliation(challenge.pointer))
        assertSame(proposal, registry.consumeTransition(challenge.pointer))
        assertNull(registry.transition(challenge.pointer))
        assertNull(registry.consumeTransition(challenge.pointer))
    }

    @Test fun authenticatedAttestationCannotSubstituteRecordEpochMigrationRevisionOrDigest() {
        val alternatives = listOf(before.copy(owner = owner.copy(ownerEpoch = owner.ownerEpoch + 1)),
            before.copy(migrationId = "another-migration"), before.copy(revision = 2), before.copy(authorityDigest = "f".repeat(64)))
        for (record in alternatives) {
            val registry = DaemonPointerAttestationRegistry(owner, installation)
            val challenge = registry.issue(before, null)
            assertFalse(registry.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer.copy(record = record))))
            assertFalse(registry.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        }
    }

    @Test fun ownerRestartNeverAcceptsAChallengeOrVerifiedProofFromThePreviousRegistry() {
        val original = DaemonPointerAttestationRegistry(owner, installation)
        val proposal = plan()
        val challenge = original.issue(proposal.next, proposal)
        assertTrue(original.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        val restarted = DaemonPointerAttestationRegistry(owner, installation)
        assertFalse(restarted.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertNull(restarted.transition(challenge.pointer))
        assertFalse(restarted.consumeReconciliation(challenge.pointer))
    }

    @Test fun newerProposalOrLocalInvalidationNeverLeavesAnOlderAttestedProofUsable() {
        val registry = DaemonPointerAttestationRegistry(owner, installation)
        val proposal = plan()
        val old = registry.issue(proposal.next, proposal)
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        val fresh = registry.issue(proposal.next, proposal)
        assertNull(registry.transition(old.pointer))
        assertNull(registry.consumeTransition(old.pointer))
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        registry.invalidateCurrent()
        assertNull(registry.transition(fresh.pointer))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
    }

    @Test fun newerTransitionSupersedesAcceptedReconciliationAndCancellationCannotRestoreIt() {
        val registry = DaemonPointerAttestationRegistry(owner, installation, 2)
        val old = registry.issue(before, null)
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        val proposal = plan()
        val fresh = registry.issue(proposal.next, proposal)
        assertFalse(registry.consumeReconciliation(old.pointer))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        registry.cancel(fresh)
        assertNull(registry.transition(fresh.pointer))
        assertFalse(registry.consumeReconciliation(old.pointer))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        // Freeing a slot and issuing another challenge must not revive either retained proof.
        val newest = registry.issue(before, null)
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(newest, newest.pointer)))
        assertFalse(registry.consumeReconciliation(old.pointer))
        assertNull(registry.consumeTransition(fresh.pointer))
        assertTrue(registry.consumeReconciliation(newest.pointer))
    }

    @Test fun newerReconciliationSupersedesAcceptedTransitionEvenWhenTheNewChallengeIsBurned() {
        val registry = DaemonPointerAttestationRegistry(owner, installation)
        val proposal = plan()
        val old = registry.issue(proposal.next, proposal)
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        val fresh = registry.issue(before, null)
        val corrupted = fresh.copy(patchDigest = "0".repeat(64))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(corrupted, corrupted.pointer)))
        assertFalse(registry.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        assertNull(registry.transition(old.pointer))
        assertNull(registry.consumeTransition(old.pointer))
        registry.cancel(fresh)
        assertNull(registry.transition(old.pointer))
    }

    @Test fun cancellingAnObsoleteOrSubstitutedChallengeDoesNotInvalidateTheCurrentOne() {
        val registry = DaemonPointerAttestationRegistry(owner, installation)
        val old = registry.issue(before, null)
        val fresh = registry.issue(before, null)
        registry.cancel(old)
        registry.cancel(fresh.copy(patchDigest = "0".repeat(64)))
        assertTrue(registry.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        assertTrue(registry.consumeReconciliation(fresh.pointer))
    }

    @Test fun challengeCannotBindAProposalToAnotherRecord() {
        val registry = DaemonPointerAttestationRegistry(owner, installation)
        RootFixtures.fails { registry.issue(before, plan()) }
    }
}
