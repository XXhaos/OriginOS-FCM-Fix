package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class DaemonAppStopResolutionTest {
    private val intent = DaemonMigrationIntent(RootFixtures.owner, "67d39891-65b4-48d2-bba8-ec3df1b7f950")
    private val stop = DaemonAppStopIntent("5177d12d-7c6d-41d9-bcc2-ef2eb3f4d50d", intent, DaemonAppCommand(DaemonWire.MASTER_OFF))
    private fun root(active: Boolean): DaemonProtectionRootSnapshot {
        val authority = ProtectionAuthoritySnapshot(1, if (active) ProtectionConsentMode.ACTIVE else ProtectionConsentMode.USER_PAUSED,
            null, null, BudgetSnapshot(BudgetClock(1, 1000, 1000), emptyList(), null), schemaVersion = 3,
            masterEnabledAtMillis = 1000.takeIf { active }?.toLong())
        val root = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority))
        return DaemonProtectionRootSnapshot(root.control.copy(migrationId = intent.migrationId,
            mode = if (active) DaemonControlMode.ACTIVE else DaemonControlMode.OFF), root.domains())
    }
    private fun proposal(root: DaemonProtectionRootSnapshot, id: String, command: DaemonProtectionCommand) =
        requireNotNull(DaemonControlCommandPlanner { _, _ -> DaemonControlTargetValidation.MATCH }.plan(root, root.control,
            id, command, DaemonControlCommandContext(BudgetClock(1, 1001, 1001), VERIFIED_AUTOSTART_FINGERPRINT, 1001, true)))
    private fun stage(root: DaemonProtectionRootSnapshot, plan: DaemonControlTransitionPlan) =
        DaemonProtectionRootSnapshot(root.control, root.domains() +
            (DaemonDomainRole.CONTROL_PROPOSAL to DaemonControlProposalCodec.encode(plan)))

    @Test fun explicitOffCannotBeSatisfiedByAnOlderActiveRecord() {
        assertFalse(daemonStopSatisfied(root(true), stop))
        assertTrue(daemonStopSatisfied(root(false), stop))
    }
    @Test fun onlyExactStoppingProposalCanBeResumed() {
        val current = root(true)
        val plan = proposal(current, stop.id, DaemonProtectionCommand.MasterOff)
        assertArrayEquals(DaemonControlProposalCodec.encode(plan),
            DaemonControlProposalCodec.encode(requireNotNull(daemonStopProposal(stage(current, plan), stop))))
        assertTrue(daemonStopSatisfied(projectDaemonControl(current, plan), stop))
        RootFixtures.fails { daemonStopProposal(stage(current,
            proposal(current, "different-request", DaemonProtectionCommand.MasterOff)), stop) }
    }
    @Test fun currentlyOffWithAnOlderOnProposalMustNotClearTheStopGate() {
        val current = root(false)
        assertTrue(daemonStopSatisfied(current, stop))
        val on = proposal(current, "old-enable", DaemonProtectionCommand.MasterOn)
        RootFixtures.fails { daemonStopProposal(stage(current, on), stop) }
    }
}
