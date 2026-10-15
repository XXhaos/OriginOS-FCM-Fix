package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.privileged.PrivilegedExecutionSettlement
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DaemonProcessExecutionFenceTest {
    private class Fixture {
        private val key = DaemonOwnerKey("test-instance", 1)
        private val principal = DaemonGuardianInstallation(SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME,
            10238, 100, listOf("a".repeat(64))), 79, 200)
        private val record = DaemonControlRecord(key, 1, "b".repeat(64), DaemonControlMode.ACTIVE, "migration")
        private val commitProof = DaemonPointerCommitProof("commit", record)
        private val port = object : DaemonControlPort {
            override fun read() = DaemonControlRead(record)
            override fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt? = null
            override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = false
            override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? = null
            override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead = error("unused")
            override fun reconcile(proof: DaemonReconcileProof) = if (proof.pointer == commitProof) read() else null
            override fun withOwnerLease(start: () -> Unit): Boolean { start(); return true }
        }
        val owner = DaemonOwnerControl(key, principal, port, DaemonPrerequisitePort { ticket ->
            DaemonWriteEvidence(ticket, 100, 200, true)
        }, { 100 })
        val ticket: DaemonWriteTicket
        val execution: DaemonProcessExecutionFence
        init {
            check(owner.reconcile(DaemonCallerEvidence(principal.identity.uid, principal), DaemonReconcileProof(commitProof, "reconcile")))
            ticket = checkNotNull(owner.admit("fcm:missing"))
            execution = DaemonProcessExecutionFence(owner, ticket)
        }
    }
    private class Child(var living: Boolean) : Process() {
        override fun isAlive() = living
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue() = 0
        override fun waitFor() = 0
        override fun destroy() {}
    }

    @Test fun callerReturnEvenWithAnExitedChildIsNotActualWorkerSettlement() {
        val f = Fixture(); val child = Child(false)
        assertTrue(f.execution.prepare()); assertSame(child, f.execution.start { child })
        assertFalse(f.execution.completeWhenSafe()); assertNull(f.owner.admit("next"))
        f.execution.workerSettled(PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED)
        assertTrue(f.execution.completeWhenSafe()); assertTrue(f.execution.isQuiescent())
        assertNotNull(f.owner.admit("next"))
    }

    @Test fun unknownAfterAsyncKillRetainsExactChildForExistingPollReaping() {
        val f = Fixture(); val child = Child(true)
        assertTrue(f.execution.prepare()); assertSame(child, f.execution.start { child })
        f.execution.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
        assertFalse(f.execution.completeWhenSafe()); assertNull(f.owner.admit("next"))
        child.living = false
        assertTrue(f.execution.completeWhenSafe()); assertTrue(f.execution.completeWhenSafe())
        val next = f.owner.admit("next")
        assertNotNull(next)
        assertTrue(f.execution.completeWhenSafe()) // An old duplicate cannot release the new ticket.
        assertNull(f.owner.admit("must-stay-busy"))
    }

    @Test fun ambiguousLaunchWithoutProcessHandleNeverReleasesFlight() {
        val f = Fixture()
        assertTrue(f.execution.prepare())
        assertNull(f.execution.start { throw IllegalStateException("ambiguous start") })
        f.execution.workerSettled(PrivilegedExecutionSettlement.UNKNOWN)
        assertFalse(f.execution.completeWhenSafe()); assertFalse(f.execution.isQuiescent())
        assertNull(f.owner.admit("next"))
    }

    @Test fun neverStartedWorkerSettlesWithoutReplayingPreparedPermit() {
        val f = Fixture()
        assertTrue(f.execution.prepare())
        f.execution.workerSettled(PrivilegedExecutionSettlement.NOT_STARTED)
        assertTrue(f.execution.completeWhenSafe())
        assertNull(f.execution.start { error("No launch after completion") })
        assertNotNull(f.owner.admit("next"))
    }
}
