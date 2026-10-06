package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class ConfirmedTargetRetirementTest {
    private val first = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val other = SelectedTargetIdentity("example.recipient", 10435, 101, listOf("b".repeat(64)))
    private val clock = BudgetClock(14, 600_000, 1_600_000)

    private class Storage : ProtectionAuthorityStorage {
        var bytes: ByteArray? = null
        var failRead = false
        var writes = 0
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot? {
            check(!failRead)
            return bytes?.let(ProtectionAuthorityCodec::decode)
        }
        override fun write(value: ProtectionAuthoritySnapshot) {
            bytes = ProtectionAuthorityCodec.encode(value)
            writes++
            check(read() == value)
        }
    }
    private class Fixture(val storage: Storage, val store: ProtectionConsentStore,
        val input: StartupEligibilityInputs)

    private fun fixture(): Fixture {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        store.grantRecipientExplicit(first, VERIFIED_AUTOSTART_FINGERPRINT, clock.copy(elapsed = 0, epoch = 1_000_000))
        store.grantRecipientExplicit(other, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        assertTrue(store.enableMaster(store.readSnapshot(), clock))
        val leases = store.desiredLeases()
        store.take(leases.first(), clock)
        val snapshot = requireNotNull(store.readSnapshot())
        return Fixture(storage, store, StartupEligibilityInputs(
            authorityReadSuccessful = true, authority = snapshot, userUnlocked = true,
            exitHistory = StartupExitHistoryStatus.CLEAR, fingerprint = VERIFIED_AUTOSTART_FINGERPRINT,
            currentUser = 0, journal = RestartJournalStatus.CLEAR,
            budget = AutomaticCheckDecision.Cooldown(300_000),
            privilege = RestartPrivilegeEvidence(true, true, 2000, 0, true),
            targets = leases.map { StartupTargetEvidence(it, it.consent.identity, it.consent.identity, true) },
        ))
    }
    private fun changed(input: StartupEligibilityInputs): StartupEligibilityInputs = input.copy(
        targets = input.targets!!.map { if (it.lease.consent.identity == first)
            it.copy(freshIdentity = first.copy(firstInstallTime = 102), qualified = false) else it })

    @Test fun changedIdentityRetiresOnlyOldTargetAndPreservesSurvivorAndBudget() {
        val f = fixture()
        val old = f.store.desiredLeases()
        val before = f.store.readSnapshot()!!
        val invalid = confirmedInvalidTargetLeases(changed(f.input), true)
        assertEquals(listOf(old.single { it.consent.identity == first }), invalid)
        assertTrue(f.store.revokeConfirmedTargets(old, invalid))
        val after = f.store.readSnapshot()!!
        assertEquals(before.targetConsents, after.targetConsents)
        assertEquals(listOf(old.single { it.consent.identity == first }.consent.id), after.invalidatedConsentIds)
        assertEquals(before.budget, after.budget)
        assertTrue(old.none { f.store.verifyAuthorized(it) })
        assertTrue(f.store.verifyAuthorized(f.store.desiredLeases().single()))
        assertEquals(before.generation + 1, after.generation)
    }

    @Test fun successfullyReadSelectionWithoutOldIdentityIsConfirmedButUnknownReadIsNot() {
        val f = fixture()
        val missing = f.input.copy(targets = f.input.targets!!.map {
            if (it.lease.consent.identity == first) it.copy(selectedIdentity = null) else it })
        assertEquals(listOf(f.store.desiredLeases().single { it.consent.identity == first }),
            confirmedInvalidTargetLeases(missing, true))
        assertTrue(confirmedInvalidTargetLeases(missing, false).isEmpty())
    }

    @Test fun nullIdentityUnknownQualificationAndNormalUnchangedIdentityDoNotRetire() {
        val f = fixture()
        for (evidence in listOf(
            f.input,
            f.input.copy(targets = f.input.targets!!.map { it.copy(freshIdentity = null) }),
            f.input.copy(targets = f.input.targets.map { it.copy(qualified = false) }),
            changed(f.input).copy(targets = changed(f.input).targets!!.map {
                if (it.lease.consent.identity == other) it.copy(freshIdentity = null) else it }),
        )) assertTrue(confirmedInvalidTargetLeases(evidence, true).isEmpty())
    }

    @Test fun sharedGuardsAndPendingJournalNeverPermitTargetRetirement() {
        val f = fixture()
        val value = changed(f.input)
        for (evidence in listOf(
            value.copy(authorityReadSuccessful = false), value.copy(userUnlocked = false),
            value.copy(exitHistory = StartupExitHistoryStatus.USER_STOPPED),
            value.copy(exitHistory = StartupExitHistoryStatus.UNKNOWN), value.copy(fingerprint = "different"),
            value.copy(currentUser = 999), value.copy(privilege = RestartPrivilegeEvidence()),
            value.copy(journal = RestartJournalStatus.PENDING), value.copy(journal = RestartJournalStatus.CORRUPT),
            value.copy(journal = RestartJournalStatus.UNKNOWN), value.copy(budget = AutomaticCheckDecision.Pause("unknown")),
            value.copy(targets = value.targets!!.drop(1)),
            value.copy(targets = value.targets.map { it.copy(lease = it.lease.copy(rootGeneration = 1)) }),
        )) assertTrue(confirmedInvalidTargetLeases(evidence, true).isEmpty())
    }

    @Test fun staleRootOrSameIdentityReconfirmationCannotRevokeFreshConsent() {
        val f = fixture()
        val old = f.store.desiredLeases()
        val oldFirst = old.single { it.consent.identity == first }
        val fresh = f.store.grantRecipientExplicit(first, VERIFIED_AUTOSTART_FINGERPRINT, clock.copy(epoch = clock.epoch + 1))
        val bytes = f.storage.bytes!!.clone()
        assertFalse(f.store.revokeConfirmedTargets(old, listOf(oldFirst)))
        assertTrue(f.store.verifyAuthorized(fresh))
        assertArrayEquals(bytes, f.storage.bytes)
    }

    @Test fun emptyForgedAndDuplicateRetirementSetsDoNotWrite() {
        val f = fixture()
        val expected = f.store.desiredLeases()
        val before = f.storage.bytes!!.clone()
        for (invalid in listOf(emptyList(), listOf(expected.first(), expected.first()),
            listOf(expected.first().copy(rootGeneration = 1)))) {
            assertFalse(f.store.revokeConfirmedTargets(expected, invalid))
            assertArrayEquals(before, f.storage.bytes)
        }
        assertFalse(f.store.revokeConfirmedTargets(expected.reversed(), listOf(expected.first())))
        assertArrayEquals(before, f.storage.bytes)
    }

    @Test fun invalidatingAllConfirmedTargetsKeepsChoicesWithoutResettingDebtOrRegranting() {
        val f = fixture()
        val before = f.store.readSnapshot()!!
        val targets = f.store.desiredLeases()
        assertTrue(f.store.revokeConfirmedTargets(targets, targets))
        assertEquals(ProtectionConsentMode.ACTIVE, f.store.readSnapshot()!!.mode)
        assertEquals(before.targetConsents, f.store.configuredConsents())
        assertEquals(targets.map { it.consent.id }.sorted(), f.store.readSnapshot()!!.invalidatedConsentIds)
        assertEquals(before.budget, f.store.readSnapshot()!!.budget)
        assertTrue(f.store.desiredLeases().isEmpty())
        assertFalse(f.store.revokeConfirmedTargets(targets, targets))
    }

    @Test fun unreadableAuthorityDoesNotBecomeTargetAbsenceOrOverwriteEvidence() {
        val f = fixture()
        val expected = f.store.desiredLeases()
        val bytes = f.storage.bytes!!.clone()
        f.storage.failRead = true
        assertThrows(IllegalStateException::class.java) { f.store.revokeConfirmedTargets(expected, expected) }
        assertArrayEquals(bytes, f.storage.bytes)
    }

    @Test fun foregroundHandoffHoldsOnlyOldOwnerAndCompletionIsOneShot() {
        val handoff = TargetRetirementForegroundHandoff()
        handoff.begin("old")
        assertTrue(handoff.holds("old"))
        assertFalse(handoff.holds("other"))
        assertFalse(handoff.finish("other"))
        assertTrue(handoff.holds("old"))
        assertTrue(handoff.finish("old"))
        assertFalse(handoff.finish("old"))
        assertFalse(handoff.holds("old"))
    }

    @Test fun replacementOrDestroyInvalidatesOldForegroundCompletion() {
        val handoff = TargetRetirementForegroundHandoff()
        handoff.begin("old")
        handoff.clear() // Same production clear used by new session and service destruction.
        handoff.begin("new")
        assertFalse(handoff.finish("old"))
        assertTrue(handoff.holds("new"))
        handoff.clear()
        assertFalse(handoff.finish("new"))
    }

    @Test fun clearedRuntimeReservationCannotStopServiceDuringForegroundHandoff() {
        val handoff = TargetRetirementForegroundHandoff()
        handoff.begin("old")
        // Authority IO clears pendingStart before its prepared callback reaches main.
        assertTrue(handoff.pending)
        assertFalse(handoff.mayStopService(runtimeHasSession = false))
        assertFalse(handoff.mayStopService(runtimeHasSession = true))
        assertTrue(handoff.finish("old"))
        assertFalse(handoff.pending)
        assertTrue(handoff.mayStopService(runtimeHasSession = false))
        assertFalse(handoff.mayStopService(runtimeHasSession = true))
    }

    @Test fun rejectedSupersedingStartEndsIdleHandoffWithoutTouchingSavedSurvivor() {
        val f = fixture()
        assertTrue(f.store.revokeConfirmedTargets(f.store.desiredLeases(),
            confirmedInvalidTargetLeases(changed(f.input), true)))
        val survivorBytes = f.storage.bytes!!.clone()
        val foreground = TargetRetirementForegroundHandoff().apply { begin("old") }
        val idle = AutomaticServiceRetirementHandoff()
        var stopped = false
        var pendingStart = true
        idle.attach { foreground.clear(); stopped = true }
        // Old prepared callback is obsolete; new START's ordinary rejection closes only idle service.
        assertFalse(idle.finish(1, 2, hasSession = pendingStart))
        ProtectionStartAdmission().run(
            isCurrent = { pendingStart }, preflight = { "new target rejected" },
            admit = { fail("ordinary rejection must not admit"); false },
            commit = { fail("ordinary rejection must not grant") },
            reject = {
                pendingStart = false
                idle.finish(2, 2, hasSession = pendingStart)
            },
            failClosed = { fail("ordinary rejection must not suspend survivors") },
            obsolete = { fail("current request must not be obsolete") },
        )
        assertTrue(stopped)
        assertFalse(foreground.pending)
        assertFalse(foreground.finish("old"))
        assertArrayEquals(survivorBytes, f.storage.bytes)
    }

    @Test fun obsoleteColdProbeCannotSuspendTargetsBeforeQueuedExactRemoval() {
        // Same admission seam is checked after initial probe and after the post-prune fresh probe.
        for (stage in listOf("initial probe", "post-prune probe")) {
            val f = fixture()
            val observed = f.store.desiredLeases()
            val before = f.storage.bytes!!.clone()
            val current = isCurrentStartupRestore(1, 2, "restore", "remove-target",
                blocked = false, saveFailed = false)
            if (current) f.store.suspendLease(observed.first(), ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
            assertFalse(stage, current)
            assertArrayEquals(stage, before, f.storage.bytes)
            assertTrue(f.store.revokeTarget(first)) // Next FIFO operation is still target-only.
            assertEquals(listOf(other), f.store.desiredLeases().map { it.consent.identity })
        }
        assertTrue(isCurrentStartupRestore(1, 1, "restore", "restore", false, false))
        assertFalse(isCurrentStartupRestore(1, 1, "restore", "restore", true, false))
        assertFalse(isCurrentStartupRestore(1, 1, "restore", "restore", false, true))
    }
}
