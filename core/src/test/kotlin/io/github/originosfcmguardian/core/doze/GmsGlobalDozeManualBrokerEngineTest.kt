package io.github.originosfcmguardian.core.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** All transports/children below are explicit host test fakes, never Android or device evidence. */
class GmsGlobalDozeManualBrokerEngineTest {
    private val fingerprint = "manual-host-qualified"
    private val baseline = "system,android,1000\nuser,com.example.other,10123\n"
    private val row = "user,$GMS_GLOBAL_DOZE_PACKAGE,10208\n"
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val guardian = GmsGlobalGuardianBinding(0, GmsGlobalInstallation(10493, 60, 50, 300, listOf("c".repeat(64))))
    private fun identity() = GmsGlobalDozeIdentity(gms, guardian, GmsGlobalEnvironment(fingerprint, 2000, 0),
        GmsGlobalOwnerEvidence.Qualified(1, "d".repeat(64), gms.appId))

    private class TestSession : GmsManualSessionPort, GmsManualLockedDocuments {
        override val control = GmsManualSessionControl()
        private val lock = Any()
        var fenced = false
        private val held = ThreadLocal.withInitial { false }
        var globalBytes: ByteArray? = null
        var brokerBytes = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())
        var globalCommits = 0
        var brokerCommits = 0
        var rejectGlobal = -1
        var rejectBroker = -1
        var throwGlobal = -1
        var throwBroker = -1
        override val global = object : GmsGlobalDozeDocumentPort {
            override fun load() = globalBytes?.clone()
            override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
                globalCommits++
                if (globalCommits == throwGlobal) error("Test ambiguous global commit")
                if (globalCommits == rejectGlobal) return false
                if (!(globalBytes?.contentEquals(expected ?: byteArrayOf()) ?: (expected == null))) return false
                GmsGlobalDozeCodec.validateTransition(expected, replacement)
                globalBytes = replacement.clone()
                return true
            }
        }
        override val broker = object : GmsDozeBrokerV2DurablePort {
            override fun readExact() = GmsDozeBrokerV2Codec.decodeProof(brokerBytes)
            override fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerV2State): ByteArray? {
                brokerCommits++
                if (brokerCommits == throwBroker) error("Test ambiguous broker commit")
                if (brokerCommits == rejectBroker || !brokerBytes.contentEquals(expectedBytes)) return null
                val bytes = GmsDozeBrokerV2Codec.encode(replacement)
                GmsDozeBrokerV2Codec.validateTransition(expectedBytes, bytes)
                brokerBytes = bytes
                return bytes.clone()
            }
            override fun fenceUncertainCommit() { fenceUncertainSession() }
        }
        override fun <T> withStorage(action: (GmsManualLockedDocuments) -> T): T = synchronized(lock) {
            check(!fenced)
            held.set(true)
            try { action(this) } catch (failure: Exception) {
                fenceUncertainSession()
                throw failure
            } finally { held.set(false) }
        }
        override fun fenceUncertainSession() { fenced = true; control.closeAdds() }
        fun checkRpcOutsideStorage() { check(!held.get()) { "RPC/probe/await under storage lock" } }
        fun document() = globalBytes?.let(GmsGlobalDozeCodec::decode)
        fun state() = GmsDozeBrokerV2Codec.decode(brokerBytes)
    }

    private inner class Fixture : GmsManualBrokerTransport {
        val session = TestSession()
        var currentIdentity = identity()
        var raw = baseline
        var probes = 0
        var starts = 0
        var releases = 0
        var deaths = 0
        var inertBindings = 0
        var onProbe: (Int) -> Unit = {}
        var beforeStart: () -> Unit = {}
        var duringAwait: () -> Unit = {}
        var afterStart: () -> Unit = {}
        var receiptTransform: (GmsManualBrokerCommandResult) -> GmsManualBrokerCommandResult = { it }
        var failRevoke = false
        var failRelease = false
        var failDeath = false
        var deathOnFailedRevoke = false
        val connections = mutableListOf<TestConnection>()
        val legacy = object : DozeJournalPort {
            var bytes: ByteArray? = null
            override fun load() = bytes?.clone()
            override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean = error("Legacy must be read only")
        }
        fun engine() = GmsGlobalDozeManualBrokerEngine(session, this, legacy, fingerprint)
        override fun bindInert(operation: GmsGlobalDozeOperation): GmsManualBrokerConnection {
            session.checkRpcOutsideStorage()
            inertBindings++
            return TestConnection(operation, "test_nonce_$inertBindings").also(connections::add)
        }
        override fun findOwn(key: GmsDozeBrokerKey): GmsManualBrokerConnection? {
            session.checkRpcOutsideStorage()
            return connections.singleOrNull { it.key == key }
        }
        override fun observedDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath? {
            session.checkRpcOutsideStorage()
            return connections.singleOrNull { it.key == key && it.dead }?.let { GmsDozeBrokerDeath(key) }
        }
        inner class TestConnection(val operation: GmsGlobalDozeOperation, val nonce: String) : GmsManualBrokerConnection {
            val key = GmsDozeBrokerKey(nonce, if (operation == GmsGlobalDozeOperation.ADD) 1 else 2)
            var shell: GmsDozeBrokerV2Admission? = null
            var child: GmsDozeBrokerChild? = null
            var cleanup: GmsDozeBrokerV2Cleanup? = null
            var dead = false
            override fun hello(): GmsManualBrokerHello { session.checkRpcOutsideStorage(); return GmsManualBrokerHello(nonce, operation) }
            override fun probe(): GmsGlobalDozeObservation {
                session.checkRpcOutsideStorage()
                onProbe(++probes)
                return GmsGlobalDozeObservation(currentIdentity, raw)
            }
            override fun activate(proof: GmsDozeBrokerV2Proof): Boolean {
                session.checkRpcOutsideStorage()
                val attempt = requireNotNull(if (operation == GmsGlobalDozeOperation.ADD) proof.state.add else proof.state.restore)
                return GmsDozeBrokerV2Admission(key, attempt.grant).also { shell = it }.activate(proof)
            }
            override fun start(proof: GmsDozeBrokerV2Proof): Boolean {
                session.checkRpcOutsideStorage()
                beforeStart()
                val result = assertNotNull(shell).start(proof) {
                    assertEquals(operation, it)
                    starts++
                    raw = if (it == GmsGlobalDozeOperation.ADD) baseline + row else baseline
                    afterStart()
                    object : GmsDozeBrokerChild {}.also { actual -> child = actual }
                }
                return result.status == GmsDozeBrokerStartStatus.STARTED
            }
            override fun awaitCleanup(preparation: GmsDozeBrokerV2Preparation): GmsManualBrokerCommandResult {
                session.checkRpcOutsideStorage()
                duringAwait()
                val facts = assertNotNull(shell).cleanupEvidence(assertNotNull(child), true, true, true, true, true)
                val receipt = receiptTransform(GmsManualBrokerCommandResult(preparation, currentIdentity,
                    assertNotNull(facts), 0, true))
                cleanup = receipt.cleanup
                return receipt
            }
            override fun revoke(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerRevokeAck {
                session.checkRpcOutsideStorage()
                assertFalse(assertNotNull(session.document()).consent.enabled)
                val version = assertNotNull(proof.state.add).revokeVersion
                if (failRevoke) { if (deathOnFailedRevoke) dead = true; error("Test missing revoke ACK") }
                return shell?.revoke(key, version) ?: GmsDozeBrokerRevokeAck(key, version)
            }
            override fun release(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerV2ReleaseAck {
                session.checkRpcOutsideStorage()
                if (failRelease) error("Test missing release ACK")
                releases++
                return assertNotNull(assertNotNull(shell).release(assertNotNull(child), assertNotNull(cleanup), proof))
            }
            override fun destroyOwnAndObserveDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath {
                session.checkRpcOutsideStorage()
                assertEquals(this.key, key)
                if (failDeath) error("Test missing actual Binder death")
                if (!dead) deaths++
                dead = true
                return GmsDozeBrokerDeath(key)
            }
        }
    }

    @Test fun `manual ADD verifies exact delta and never claims an existing membership`() {
        val f = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, f.engine().applyOnce(1000).code)
        assertEquals(baseline + row, f.session.document()?.transaction?.applied)
        assertEquals(1, f.starts)
        assertTrue(assertNotNull(f.session.state().add).releaseAcknowledged)
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(2000).code)
        assertEquals(1, f.starts)
        for (existing in listOf(row, "system,$GMS_GLOBAL_DOZE_PACKAGE,10208\n", "system-excidle,$GMS_GLOBAL_DOZE_PACKAGE,10208\n")) {
            val known = Fixture().also { it.raw = baseline + existing }
            assertEquals(DozeOutcomeCode.NO_WRITE, known.engine().applyOnce(1000).code)
            assertEquals(0, known.starts)
            assertNull(known.session.document())
            assertNull(known.session.state().add)
        }
        val wrongDelta = Fixture().also { it.afterStart = { it.raw += "user,com.example.extra,10555\n" } }
        assertEquals(DozeOutcomeCode.UNCERTAIN, wrongDelta.engine().applyOnce(1000).code)
        assertEquals(DozePhase.ADD_INTENT, wrongDelta.session.document()?.transaction?.phase)
        assertEquals(0, wrongDelta.releases)
        val drift = Fixture().also { it.onProbe = { count -> if (count == 2) it.raw += "user,com.example.extra,10555\n" } }
        assertEquals(DozeOutcomeCode.UNCERTAIN, drift.engine().applyOnce(1000).code)
        assertEquals(0, drift.starts)
        val unknownOwners = Fixture().also { it.currentIdentity = it.currentIdentity.copy(owners = GmsGlobalOwnerEvidence.Unknown) }
        assertEquals(DozeOutcomeCode.UNCERTAIN, unknownOwners.engine().applyOnce(1000).code)
    }

    @Test fun `OFF closes before start and never waits for business or holds storage during RPC`() {
        val before = Fixture()
        before.beforeStart = { assertEquals(DozeOutcomeCode.NO_WRITE, before.engine().off().code) }
        assertEquals(DozeOutcomeCode.UNCERTAIN, before.engine().applyOnce(1000).code)
        assertEquals(0, before.starts)
        assertFalse(assertNotNull(before.session.document()).consent.enabled)
        assertEquals(DozePhase.ADD_INTENT, before.session.document()?.transaction?.phase)
        assertFalse(before.session.fenced)
        val f = Fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var outcome: DozeOutcome? = null
        f.duringAwait = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        val worker = thread { outcome = f.engine().applyOnce(1000) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().applyOnce(2000).code)
        assertEquals(DozeOutcomeCode.NO_WRITE, f.engine().off().code)
        assertEquals(1, f.starts)
        assertFalse(assertNotNull(f.session.document()).consent.enabled)
        assertFalse(assertNotNull(f.session.state().add).releaseAcknowledged)
        finish.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(DozeOutcomeCode.APPLIED, outcome?.code)
        assertFalse(assertNotNull(f.session.document()).consent.enabled)
        assertTrue(assertNotNull(f.session.state().add).actualDeathObserved)
        assertEquals(DozeOutcomeCode.BLOCKED, f.engine().applyOnce(3000).code)
        val preflight = Fixture()
        preflight.onProbe = { count -> if (count == 3) assertEquals(DozeOutcomeCode.NO_WRITE, preflight.engine().off().code) }
        assertEquals(DozeOutcomeCode.UNCERTAIN, preflight.engine().applyOnce(1000).code)
        assertFalse(preflight.session.fenced)
        assertEquals(DozeOutcomeCode.NO_WRITE, preflight.engine().off().code)
    }

    @Test fun `restore requires OFF named fresh confirmation and original retired ADD preserving consent`() {
        val f = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, f.engine().applyOnce(1000).code)
        val tx = assertNotNull(f.session.document()?.transaction)
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().restoreOnce(tx.id, 2000).code)
        assertFalse(f.session.fenced)
        assertEquals(DozeOutcomeCode.NO_WRITE, f.engine().off().code)
        val offConsent = assertNotNull(f.session.document()).consent
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().restoreOnce("wrong", 2000).code)
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().restoreOnce(tx.id, 1000).code)
        f.currentIdentity = f.currentIdentity.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), gms.appId))
        assertEquals(DozeOutcomeCode.RESTORED, f.engine().restoreOnce(tx.id, 2000).code)
        val closed = assertNotNull(f.session.document())
        assertEquals(offConsent.copy(generation = closed.consent.generation), closed.consent)
        assertEquals(tx.id, closed.transaction?.id)
        assertEquals(tx.epoch, closed.transaction?.epoch)
        assertEquals(baseline, f.raw)
        val state = f.session.state()
        assertNotEquals(state.add?.pin?.nonce, state.restore?.pin?.nonce)
        assertEquals(2L, state.restore?.pin?.generation)
        assertNotEquals(state.add?.grant?.identity?.owners, state.restore?.grant?.identity?.owners)
        assertTrue(assertNotNull(state.restore).actualDeathObserved)
        assertEquals(2, f.starts)
        val observed = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, observed.engine().applyOnce(1000).code)
        assertEquals(DozeOutcomeCode.NO_WRITE, observed.engine().off().code)
        observed.raw = baseline
        assertEquals(DozeOutcomeCode.OBSERVED_RESTORED, observed.engine().restoreOnce(
            assertNotNull(observed.session.document()?.transaction).id, 2000).code)
        assertEquals(1, observed.starts)
        assertNull(observed.session.state().restore)
    }

    @Test fun `every business and broker CAS gap retains authority and blocks another write`() {
        for (globalCommit in 1..3) {
            val f = Fixture().also { it.session.rejectGlobal = globalCommit }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(1000).code)
            val writes = f.starts
            f.engine().applyOnce(2000)
            assertEquals(writes, f.starts)
            assertFalse(f.session.control.addsLocallyAllowed())
            assertEquals(if (globalCommit <= 2) DozeOutcomeCode.UNCERTAIN else DozeOutcomeCode.NO_WRITE, f.engine().off().code)
        }
        for (brokerCommit in 1..5) {
            val f = Fixture().also { it.session.rejectBroker = brokerCommit }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(1000).code)
            assertNotNull(f.session.document()?.transaction)
            val writes = f.starts
            f.engine().applyOnce(2000)
            assertEquals(writes, f.starts)
            assertNull(f.session.state().restore)
            assertFalse(f.session.fenced)
        }
        for (globalCommit in 1..3) {
            val f = Fixture().also { it.session.throwGlobal = globalCommit }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(1000).code)
            assertTrue(f.session.fenced)
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().off().code)
        }
        val f = Fixture().also { it.session.throwBroker = 4 }
        assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(1000).code)
        assertEquals(DozePhase.APPLIED, f.session.document()?.transaction?.phase)
        assertTrue(f.session.fenced)
        for (gap in 1..2) {
            val restore = Fixture()
            assertEquals(DozeOutcomeCode.APPLIED, restore.engine().applyOnce(1000).code)
            assertEquals(DozeOutcomeCode.NO_WRITE, restore.engine().off().code)
            restore.session.rejectGlobal = restore.session.globalCommits + gap
            val id = assertNotNull(restore.session.document()?.transaction).id
            assertEquals(DozeOutcomeCode.UNCERTAIN, restore.engine().restoreOnce(id, 2000).code)
            val starts = restore.starts
            restore.engine().restoreOnce(id, 3000)
            assertEquals(starts, restore.starts)
        }
        for (gap in 1..5) {
            val restore = Fixture()
            assertEquals(DozeOutcomeCode.APPLIED, restore.engine().applyOnce(1000).code)
            assertEquals(DozeOutcomeCode.NO_WRITE, restore.engine().off().code)
            restore.session.rejectBroker = restore.session.brokerCommits + gap
            val id = assertNotNull(restore.session.document()?.transaction).id
            assertEquals(DozeOutcomeCode.UNCERTAIN, restore.engine().restoreOnce(id, 2000).code)
            val starts = restore.starts
            restore.engine().restoreOnce(id, 3000)
            assertEquals(starts, restore.starts)
            assertFalse(assertNotNull(restore.session.document()).consent.enabled)
        }
    }

    @Test fun `missing command cleanup result release or actual death blocks the next operation`() {
        val transforms: List<(GmsManualBrokerCommandResult) -> GmsManualBrokerCommandResult> = listOf(
            { it.copy(exitCode = null) }, { it.copy(exitCode = 1) }, { it.copy(commandOutputValid = false) },
            { it.copy(cleanup = it.cleanup.copy(childExited = false)) },
            { it.copy(cleanup = it.cleanup.copy(stdoutEofAndDrained = false)) },
            { it.copy(cleanup = it.cleanup.copy(stderrEofAndDrained = false)) },
            { it.copy(cleanup = it.cleanup.copy(collectorsTerminated = false)) },
            { it.copy(cleanup = it.cleanup.copy(handlesClosed = false)) },
            { it.copy(identity = it.identity.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), gms.appId))) },
        )
        for (transform in transforms) {
            val f = Fixture().also { it.receiptTransform = transform }
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().applyOnce(1000).code)
            assertEquals(DozePhase.ADD_INTENT, f.session.document()?.transaction?.phase)
            assertEquals(DozeOutcomeCode.NO_WRITE, f.engine().off().code)
            assertEquals(DozeOutcomeCode.UNCERTAIN, f.engine().restoreOnce(assertNotNull(f.session.document()?.transaction).id, 2000).code)
            assertEquals(1, f.starts)
            assertEquals(0, f.deaths)
        }
        val release = Fixture().also { it.failRelease = true }
        assertEquals(DozeOutcomeCode.UNCERTAIN, release.engine().applyOnce(1000).code)
        assertNotNull(release.session.state().add?.result)
        assertFalse(assertNotNull(release.session.state().add).releaseAcknowledged)
        assertEquals(DozeOutcomeCode.NO_WRITE, release.engine().off().code)
        assertEquals(DozeOutcomeCode.UNCERTAIN, release.engine().restoreOnce(assertNotNull(release.session.document()?.transaction).id, 2000).code)
        val death = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, death.engine().applyOnce(1000).code)
        death.failDeath = true
        assertEquals(DozeOutcomeCode.UNCERTAIN, death.engine().off().code)
        assertFalse(assertNotNull(death.session.state().add).actualDeathObserved)
        assertEquals(DozeOutcomeCode.UNCERTAIN, death.engine().restoreOnce(assertNotNull(death.session.document()?.transaction).id, 2000).code)
        val revoke = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, revoke.engine().applyOnce(1000).code)
        revoke.failRevoke = true
        assertEquals(DozeOutcomeCode.UNCERTAIN, revoke.engine().off().code)
        assertNull(revoke.session.state().add?.acknowledgedRevoke)
        val actualDeath = Fixture()
        assertEquals(DozeOutcomeCode.APPLIED, actualDeath.engine().applyOnce(1000).code)
        actualDeath.failRevoke = true
        actualDeath.deathOnFailedRevoke = true
        assertEquals(DozeOutcomeCode.NO_WRITE, actualDeath.engine().off().code)
        assertTrue(assertNotNull(actualDeath.session.state().add).actualDeathObserved)
    }
}
