package io.github.originosfcmguardian.core.doze

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host-only exact-byte adapter and simulated child receipts; not Android authority/device evidence. */
private class CycleTicketHostPort : GmsDozeCycleLedgerPort {
    override val control = GmsDozeCycleControl()
    var bytes: ByteArray? = null
    override fun load() = bytes?.clone()
    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
        if (!(bytes?.contentEquals(expected ?: byteArrayOf()) ?: (expected == null))) return false
        GmsDozeCycleLedgerCodec.validateTransition(expected, replacement)
        bytes = replacement.clone()
        return true
    }
}

private class CycleTicketHostFixture {
    val data = V2Fixture()
    val port = CycleTicketHostPort()
    val coordinator = GmsDozeCycleCoordinator(port, "v2-host")
    val child = object : GmsDozeBrokerChild {}
    lateinit var grant: GmsDozeCycleGrant
    lateinit var shell: GmsDozeCycleShellAdmission
    lateinit var activation: GmsDozeCycleTicket
    lateinit var start: GmsDozeCycleTicket
    fun prepare(nonce: String = "cycle-ticket-host") {
        assertNotNull(coordinator.initializeExplicitConsent(GmsDozeCycleConsent(UUID.randomUUID().toString(), 1000), data.identity, true))
        grant = assertNotNull(coordinator.prepareAdd(GmsGlobalDozeObservation(data.identity, data.baseline), nonce))
        activation = ticket()
        shell = GmsDozeCycleShellAdmission(nonce, GmsGlobalDozeOperation.ADD)
    }
    fun ticket() = GmsDozeCycleTicketCodec.fromCommittedLedger(assertNotNull(port.load()), grant.ref)
    fun startChild() {
        assertTrue(shell.activate(activation))
        assertNotNull(coordinator.recordStartIntent(grant.ref))
        start = ticket()
        assertTrue(coordinator.reserveOutboundStart(grant.ref))
        assertEquals(GmsDozeBrokerStartStatus.STARTED, shell.start(start) {
            assertEquals(GmsGlobalDozeOperation.ADD, it); child
        }.status)
    }
    fun cleanup() = assertNotNull(shell.cleanupEvidence(child, true, true, true, true, true))
    fun recordResult(cleanup: GmsDozeCycleCleanup = cleanup()): GmsDozeCycleTicket {
        assertNotNull(coordinator.recordResult(GmsDozeCycleResult(grant.ref, cleanup, 0, true, data.identity, data.applied)))
        return ticket()
    }
    fun retire() {
        val result = recordResult()
        assertNotNull(coordinator.recordReleaseAck(assertNotNull(shell.release(child, cleanup(), result))))
        assertNotNull(coordinator.requestRevoke(grant.ref))
        assertNotNull(coordinator.recordClosure(assertNotNull(shell.revoke(ticket()))))
    }
}

class GmsDozeCycleTicketTest {
    @Test fun `current ticket is exact bounded immutable separate schema and named REMOVE factory`() {
        val f = CycleTicketHostFixture(); f.prepare()
        val ledgerBytes = assertNotNull(f.port.load())
        val untouched = ledgerBytes.clone()
        val ticket = GmsDozeCycleTicketCodec.fromCommittedLedger(ledgerBytes, f.grant.ref)
        assertContentEquals(untouched, ledgerBytes)
        assertEquals(GmsDozeCycleLedgerCodec.sha256(untouched), ticket.ledgerSha256)
        assertEquals(f.grant.ref, ticket.ref)
        val bytes = GmsDozeCycleTicketCodec.encode(ticket)
        assertTrue(bytes.size <= GmsDozeCycleTicketCodec.MAX_BYTES)
        assertEquals(ticket, GmsDozeCycleTicketCodec.decode(bytes))
        assertContentEquals(bytes, GmsDozeCycleTicketCodec.encode(GmsDozeCycleTicketCodec.decode(bytes)))
        ledgerBytes.fill(0); bytes.fill(0)
        assertEquals(f.grant, ticket.grant)
        assertFails { (ticket.grant.identity.gms.signerSha256 as MutableList<String>).clear() }
        assertFails { GmsDozeCycleTicketCodec.fromCommittedLedger(untouched, f.grant.ref.copy(transactionId = UUID.randomUUID().toString())) }
        for (old in listOf(untouched, GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState()),
            ByteArray(GmsDozeCycleTicketCodec.MAX_BYTES + 1))) assertFails { GmsDozeCycleTicketCodec.decode(old) }
        val corrupt = GmsDozeCycleTicketCodec.encode(ticket).also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertFails { GmsDozeCycleTicketCodec.decode(corrupt) }

        f.startChild(); f.retire()
        val off = assertNotNull(f.coordinator.persistOff())
        val cycle = off.cycles.single()
        val named = GmsDozeCycleRestoreConsent(UUID.randomUUID().toString(), off.ledgerId, cycle.cycleId,
            cycle.transactionId, cycle.transactionEpoch, 2000)
        val remove = assertNotNull(f.coordinator.prepareRemove(named, GmsGlobalDozeObservation(f.data.identity, f.data.applied), "ticket-remove"))
        val removeTicket = GmsDozeCycleTicketCodec.fromCommittedLedger(assertNotNull(f.port.load()), remove.ref)
        assertFalse(removeTicket.runningIntent)
        assertEquals(named, removeTicket.restoreConsent)
        assertEquals(GmsDozeBrokerV2Codec.rawSha256(f.data.applied), removeTicket.grant.appliedSha256)
        assertFails { GmsDozeCycleTicketCodec.fromCommittedLedger(assertNotNull(f.port.load()), f.grant.ref) }
        assertFails { GmsDozeCycleTicketCodec.encode(removeTicket.copy(restoreConsent = null)) }
        assertFails { GmsDozeCycleTicketCodec.encode(removeTicket.copy(restoreConsent = named.copy(transactionId = UUID.randomUUID().toString()))) }
    }

    @Test fun `outbound exact token is one use and callee revoke start registration share short gate`() {
        val f = CycleTicketHostFixture(); f.prepare()
        assertNotNull(f.coordinator.recordStartIntent(f.grant.ref))
        val sibling = GmsDozeCycleCoordinator(f.port, "v2-host")
        assertFalse(sibling.reserveOutboundStart(f.grant.ref.copy(key = f.grant.ref.key.copy(generation = 99))))
        assertFalse(sibling.reserveOutboundStart(f.grant.ref.copy(operation = GmsGlobalDozeOperation.REMOVE)))
        assertTrue(sibling.reserveOutboundStart(f.grant.ref))
        assertFalse(f.coordinator.reserveOutboundStart(f.grant.ref))
        assertFalse(f.coordinator.admitActualStart(f.grant.ref) { error("Outbound token reused locally") })
        assertNotNull(sibling.persistOff()) // No App gate is held while the adapter would perform RPC.
        assertNotNull(sibling.requestRevoke(f.grant.ref))
        assertNotNull(f.shell.revoke(f.ticket())) // Revoke-first binds, but does not authorize activation.
        assertFalse(f.shell.activate(f.activation))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, f.shell.start(f.ticket()) { error("Revoke-first started") }.status)

        val race = CycleTicketHostFixture(); race.prepare("registered-host")
        assertTrue(race.shell.activate(race.activation))
        assertNotNull(race.coordinator.recordStartIntent(race.grant.ref))
        val start = race.ticket()
        assertTrue(race.coordinator.reserveOutboundStart(race.grant.ref))
        assertNotNull(race.coordinator.requestRevoke(race.grant.ref))
        val revoke = race.ticket()
        val entered = CountDownLatch(1); val allowRegistration = CountDownLatch(1); val revokeDone = CountDownLatch(1)
        var started: GmsDozeBrokerStartResult? = null
        var closure: GmsDozeCycleClosure? = null
        val starter = thread {
            started = race.shell.start(start) {
                entered.countDown(); check(allowRegistration.await(2, TimeUnit.SECONDS)); race.child
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val revoker = thread { closure = race.shell.revoke(revoke); revokeDone.countDown() }
        assertFalse(revokeDone.await(20, TimeUnit.MILLISECONDS))
        allowRegistration.countDown(); starter.join(2000); revoker.join(2000)
        assertEquals(GmsDozeBrokerStartStatus.STARTED, assertNotNull(started).status)
        assertEquals(race.grant.ref, assertNotNull(closure).ref)
        assertTrue(race.shell.isBusy())
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, race.shell.start(start) { error("Second start") }.status)

        val uncertain = CycleTicketHostFixture(); uncertain.prepare("uncertain-host")
        assertTrue(uncertain.shell.activate(uncertain.activation))
        assertNotNull(uncertain.coordinator.recordStartIntent(uncertain.grant.ref))
        assertEquals(GmsDozeBrokerStartStatus.UNCERTAIN, uncertain.shell.start(uncertain.ticket()) { error("Unknown child registration") }.status)
        assertTrue(uncertain.shell.isBusy())
        assertNull(uncertain.shell.cleanupEvidence(uncertain.child, true, true, true, true, true))
    }

    @Test fun `real fake cleanup result release accepts both historical OFF revoke delivery orders never reopens`() {
        for (revokeDeliveredFirst in listOf(false, true)) {
            val f = CycleTicketHostFixture(); f.prepare("release-order-$revokeDeliveredFirst"); f.startChild()
            val cleanup = f.cleanup()
            val result: GmsDozeCycleTicket
            if (revokeDeliveredFirst) {
                result = f.recordResult(cleanup) // Historical result version zero.
                assertNotNull(f.coordinator.persistOff())
                assertNotNull(f.coordinator.requestRevoke(f.grant.ref))
                assertNotNull(f.coordinator.recordClosure(assertNotNull(f.shell.revoke(f.ticket()))))
            } else {
                assertNotNull(f.coordinator.persistOff())
                assertNotNull(f.coordinator.requestRevoke(f.grant.ref))
                result = f.recordResult(cleanup) // Durable result version one, callee still version zero.
            }
            assertEquals(if (revokeDeliveredFirst) 0L else 1L, result.requestedRevoke)
            val summary = assertNotNull(result.resultSummary)
            assertEquals(GmsDozeBrokerV2Codec.rawSha256(f.data.applied), summary.rawWhitelistSha256)
            assertFails { GmsDozeCycleTicketCodec.encode(result.copy(resultSummary = summary.copy(cleanup = cleanup.copy(handlesClosed = false)))) }
            assertNull(f.shell.release(f.child, cleanup.copy(stderrEofAndDrained = false), result))
            assertNull(f.shell.release(object : GmsDozeBrokerChild {}, cleanup, result))
            assertNull(f.shell.release(f.child, cleanup, result.copy(grant = result.grant.copy(ref = result.ref.copy(key = GmsDozeBrokerKey("wrong-child", 1))))))
            assertTrue(f.shell.isBusy())
            val release = assertNotNull(f.shell.release(f.child, cleanup, result))
            assertEquals(f.grant.ref, release.ref)
            assertFalse(f.shell.isBusy())
            assertNotNull(f.coordinator.recordReleaseAck(release))
            if (!revokeDeliveredFirst) assertNotNull(f.coordinator.recordClosure(assertNotNull(f.shell.revoke(f.ticket()))))
            val settled = GmsDozeCycleLedgerCodec.decode(assertNotNull(f.port.load()))
            assertFalse(settled.runningIntent)
            assertEquals(GmsDozeCyclePhase.APPLIED_SETTLED, settled.cycles.single().phase)
            assertNull(f.shell.release(f.child, cleanup, result))
            assertFalse(f.shell.activate(f.activation))
            assertEquals(GmsDozeBrokerStartStatus.REJECTED, f.shell.start(f.start) { error("Completed child reopened") }.status)
            assertFalse(f.coordinator.reserveOutboundStart(f.grant.ref))
        }
    }
}
