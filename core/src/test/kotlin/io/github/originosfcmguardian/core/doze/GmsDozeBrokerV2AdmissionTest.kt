package io.github.originosfcmguardian.core.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test-only exact CAS; intentionally no production memory port. */
internal class V2MemoryPort : GmsDozeBrokerV2DurablePort {
    private var bytes = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())
    var rejectCas = false
    var wrongReadback = false
    var fenced = false
    override fun readExact(): GmsDozeBrokerV2Proof {
        check(!fenced)
        return GmsDozeBrokerV2Codec.decodeProof(if (wrongReadback) GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState()) else bytes)
    }
    override fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerV2State): ByteArray? {
        check(!fenced)
        if (rejectCas || !expectedBytes.contentEquals(bytes)) return null
        val next = GmsDozeBrokerV2Codec.encode(replacement)
        GmsDozeBrokerV2Codec.validateTransition(bytes, next)
        bytes = next.clone()
        return next.clone()
    }
    override fun fenceUncertainCommit() { fenced = true }
    fun set(state: GmsDozeBrokerV2State) { bytes = GmsDozeBrokerV2Codec.encode(state) }
}

internal class V2Fixture {
    val port = V2MemoryPort()
    val coordinator = GmsDozeBrokerV2Coordinator(port)
    val baseline = "system,android,1000\nuser,com.example.other,10123\n"
    val applied = baseline + "user,com.google.android.gms,10208\n"
    val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    val guardian = GmsGlobalGuardianBinding(0, GmsGlobalInstallation(10493, 60, 50, 300, listOf("c".repeat(64))))
    val identity = GmsGlobalDozeIdentity(gms, guardian, GmsGlobalEnvironment("v2-host", 2000, 0),
        GmsGlobalOwnerEvidence.Qualified(1, "d".repeat(64), gms.appId))
    val transactionId = "12345678-1234-1234-1234-123456789abc"
    val addKey = GmsDozeBrokerKey("fresh-add", 1)
    val removeKey = GmsDozeBrokerKey("fresh-remove", 2)

    fun document(phase: DozePhase, enabled: Boolean = phase == DozePhase.ADD_INTENT || phase == DozePhase.APPLIED,
        txId: String = transactionId): GmsGlobalDozeDocument = GmsGlobalDozeDocument(
        GmsGlobalDozeConsent(enabled, if (enabled) 1 else 2, phase.ordinal.toLong() + 3, 1000,
            gms, guardian, "v2-host"),
        GmsGlobalDozeTransaction(txId, 1, gms, 1, baseline, if (phase == DozePhase.ADD_INTENT) null else applied, phase))
    fun bytes(phase: DozePhase, enabled: Boolean = phase == DozePhase.ADD_INTENT || phase == DozePhase.APPLIED) =
        GmsGlobalDozeCodec.encode(document(phase, enabled))
    fun add(): GmsDozeBrokerV2Attempt = assertNotNull(assertNotNull(coordinator.issueAddGrant(identity, bytes(DozePhase.ADD_INTENT))).state.add)
    fun preparedAdd(): GmsDozeBrokerV2Attempt {
        val grant = add().grant
        assertNotNull(coordinator.pin(grant.grantId, addKey.nonce))
        return assertNotNull(assertNotNull(coordinator.prepare(addKey, grant.grantId)).state.add)
    }
    fun cleanup(prep: GmsDozeBrokerV2Preparation) = GmsDozeBrokerV2Cleanup(prep, true, true, true, true, true)
    fun result(prep: GmsDozeBrokerV2Preparation, phase: DozePhase) = GmsDozeBrokerV2Result(prep,
        GmsDozeBrokerV2Codec.sha256(bytes(phase)), GmsDozeBrokerV2Codec.rawSha256(if (phase == DozePhase.APPLIED) applied else baseline))
    fun finishAdd(prepared: GmsDozeBrokerV2Attempt): GmsDozeBrokerV2Attempt {
        val prep = assertNotNull(prepared.preparation)
        return assertNotNull(assertNotNull(coordinator.commitResult(cleanup(prep), result(prep, DozePhase.APPLIED), bytes(DozePhase.APPLIED))).state.add)
    }
    fun retireAdd(): GmsDozeBrokerV2Attempt {
        val done = finishAdd(preparedAdd())
        assertNotNull(coordinator.requestRevoke(addKey))
        assertNotNull(coordinator.acceptRevokeAck(GmsDozeBrokerRevokeAck(addKey, 1)))
        assertNotNull(coordinator.acceptReleaseAck(GmsDozeBrokerV2ReleaseAck(addKey, done.grant.grantId,
            assertNotNull(done.preparation).preparationRevision)))
        return assertNotNull(assertNotNull(coordinator.acceptDeath(GmsDozeBrokerDeath(addKey))).state.add)
    }
}

class GmsDozeBrokerV2AdmissionTest {
    @Test fun `shell constructor freezes caller GMS and Guardian signer lists`() {
        val f = V2Fixture()
        val grant = f.add().grant
        val gmsSigners = grant.identity.gms.signerSha256.toMutableList()
        val guardianSigners = grant.identity.guardian.installation.signerSha256.toMutableList()
        val callerGrant = grant.copy(identity = grant.identity.copy(
            gms = grant.identity.gms.copy(signerSha256 = gmsSigners),
            guardian = grant.identity.guardian.copy(installation = grant.identity.guardian.installation.copy(signerSha256 = guardianSigners))))
        val shell = GmsDozeBrokerV2Admission(f.addKey, callerGrant)
        gmsSigners[0] = "e".repeat(64)
        guardianSigners.clear()
        assertEquals(grant, shell.grant)
        assertFails { (shell.grant.identity.gms.signerSha256 as MutableList<String>).clear() }
        assertFails { (shell.grant.identity.guardian.installation.signerSha256 as MutableList<String>).clear() }
    }

    @Test fun `one ADD slot pin and preparation are never replayed or replaced`() {
        val f = V2Fixture()
        val prepared = f.preparedAdd()
        val id = prepared.grant.grantId
        assertNull(f.coordinator.issueAddGrant(f.identity, f.bytes(DozePhase.ADD_INTENT)))
        assertNull(f.coordinator.pin(id, "another"))
        assertNull(f.coordinator.prepare(f.addKey, id))
        assertNull(f.coordinator.prepare(GmsDozeBrokerKey("wrong", 1), id))
        assertNull(f.coordinator.acceptDeath(GmsDozeBrokerDeath(GmsDozeBrokerKey("wrong", 1))))
        assertEquals(prepared, f.port.readExact().state.add)
    }

    @Test fun `revocation advances preserves result and rejects stale ACK without reopening`() {
        val f = V2Fixture()
        val prepared = f.preparedAdd()
        assertNotNull(f.coordinator.requestRevoke(f.addKey))
        val result = f.finishAdd(prepared)
        assertNotNull(f.coordinator.acceptRevokeAck(GmsDozeBrokerRevokeAck(f.addKey, 1)))
        assertNotNull(f.coordinator.requestRevoke(f.addKey))
        assertNull(f.coordinator.acceptRevokeAck(GmsDozeBrokerRevokeAck(f.addKey, 1)))
        assertNotNull(f.coordinator.acceptRevokeAck(GmsDozeBrokerRevokeAck(f.addKey, 2)))
        assertNull(f.coordinator.prepare(f.addKey, prepared.grant.grantId))
        assertNull(f.coordinator.pin(prepared.grant.grantId, "retry"))
        val retained = assertNotNull(f.port.readExact().state.add)
        assertEquals(2L, retained.revokeVersion)
        assertEquals(result.result, retained.result)
        assertEquals(prepared.preparation, retained.preparation)
    }

    @Test fun `each missing retirement fact blocks REMOVE`() {
        val source = V2Fixture()
        val fullyRetired = source.retireAdd()
        val variants = listOf(
            fullyRetired.copy(result = null, releaseAcknowledged = false),
            fullyRetired.copy(releaseAcknowledged = false),
            fullyRetired.copy(actualDeathObserved = false),
            fullyRetired.copy(revokeVersion = 0, acknowledgedRevoke = null),
        )
        for (attempt in variants) {
            val f = V2Fixture()
            f.port.set(GmsDozeBrokerV2State(20, attempt, null))
            assertNull(f.coordinator.issueRestoreGrant(f.identity, f.bytes(DozePhase.RESTORE_INTENT)))
        }
        // Real linked death substitutes for an ACK; actual death itself remains mandatory.
        source.port.set(source.port.readExact().state.copy(add = fullyRetired.copy(acknowledgedRevoke = null)))
        assertNotNull(source.coordinator.issueRestoreGrant(source.identity, source.bytes(DozePhase.RESTORE_INTENT)))
    }

    @Test fun `OFF restore binds same transaction identity and raw APPLIED and admits only REMOVE`() {
        val f = V2Fixture()
        val add = f.retireAdd()
        assertNull(f.coordinator.issueRestoreGrant(f.identity, f.bytes(DozePhase.RESTORE_INTENT, true)))
        assertNull(f.coordinator.issueRestoreGrant(f.identity,
            GmsGlobalDozeCodec.encode(f.document(DozePhase.RESTORE_INTENT, false, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))))
        assertNull(f.coordinator.issueRestoreGrant(f.identity.copy(environment = f.identity.environment.copy(fingerprint = "other")),
            f.bytes(DozePhase.RESTORE_INTENT)))
        assertNull(f.coordinator.issueRestoreGrant(f.identity.copy(guardian = f.identity.guardian.copy(
            installation = f.identity.guardian.installation.copy(versionCode = 61))), f.bytes(DozePhase.RESTORE_INTENT)))
        val freshIdentity = f.identity.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), f.gms.appId))
        val granted = assertNotNull(f.coordinator.issueRestoreGrant(freshIdentity, f.bytes(DozePhase.RESTORE_INTENT)))
        val restore = assertNotNull(granted.state.restore)
        assertEquals(add.result?.appliedSha256, restore.grant.appliedSha256)
        assertNull(f.coordinator.issueAddGrant(f.identity, f.bytes(DozePhase.ADD_INTENT)))
        assertNull(f.coordinator.pin(restore.grant.grantId, f.addKey.nonce))
        val pinned = assertNotNull(f.coordinator.pin(restore.grant.grantId, f.removeKey.nonce))
        val shell = GmsDozeBrokerV2Admission(f.removeKey, restore.grant)
        assertTrue(shell.activate(pinned))
        val prepared = assertNotNull(f.coordinator.prepare(f.removeKey, restore.grant.grantId))
        var operation: GmsGlobalDozeOperation? = null
        val child = object : GmsDozeBrokerChild {}
        assertEquals(GmsDozeBrokerStartStatus.STARTED, shell.start(prepared) { operation = it; child }.status)
        assertEquals(GmsGlobalDozeOperation.REMOVE, operation)
        val cleanup = assertNotNull(shell.cleanupEvidence(child, true, true, true, true, true))
        val result = f.result(cleanup.preparation, DozePhase.RESTORED)
        assertEquals(restore.grant.baselineSha256, result.appliedSha256)
        val committed = assertNotNull(f.coordinator.commitResult(cleanup, result, f.bytes(DozePhase.RESTORED)))
        val ack = assertNotNull(shell.release(child, cleanup, committed))
        assertNotNull(f.coordinator.acceptReleaseAck(ack))
        assertNull(f.coordinator.issueRestoreGrant(freshIdentity, f.bytes(DozePhase.RESTORE_INTENT)))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, shell.start(prepared) { error("replayed") }.status)
        assertEquals(add, f.port.readExact().state.add)
    }

    @Test fun `cleanup and exact matching business document are mandatory for result`() {
        val f = V2Fixture()
        val prep = assertNotNull(f.preparedAdd().preparation)
        val cleanup = f.cleanup(prep)
        val result = f.result(prep, DozePhase.APPLIED)
        for (incomplete in listOf(cleanup.copy(childExited = false), cleanup.copy(stdoutEofAndDrained = false),
            cleanup.copy(stderrEofAndDrained = false), cleanup.copy(collectorsTerminated = false), cleanup.copy(handlesClosed = false)))
            assertNull(f.coordinator.commitResult(incomplete, result, f.bytes(DozePhase.APPLIED)))
        assertNull(f.coordinator.commitResult(cleanup, result, f.bytes(DozePhase.ADD_INTENT)))
        assertNull(f.coordinator.commitResult(cleanup, result.copy(appliedSha256 = "a".repeat(64)), f.bytes(DozePhase.APPLIED)))
        assertNull(f.coordinator.acceptReleaseAck(GmsDozeBrokerV2ReleaseAck(f.addKey, prep.grantId, prep.preparationRevision)))
        assertNotNull(f.coordinator.commitResult(cleanup, result, f.bytes(DozePhase.APPLIED)))
        assertNull(f.coordinator.commitResult(cleanup, result, f.bytes(DozePhase.APPLIED)))
        assertNull(f.coordinator.acceptReleaseAck(GmsDozeBrokerV2ReleaseAck(f.addKey, prep.grantId, prep.preparationRevision + 1)))
    }

    @Test fun `inert activation once revoke before start and uncertain start retain closure`() {
        val f = V2Fixture()
        val grant = f.add().grant
        val pinned = assertNotNull(f.coordinator.pin(grant.grantId, f.addKey.nonce))
        val shell = GmsDozeBrokerV2Admission(f.addKey, grant)
        val prepared = assertNotNull(f.coordinator.prepare(f.addKey, grant.grantId))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, shell.start(prepared) { error("inert") }.status)
        assertTrue(shell.activate(pinned))
        assertFalse(shell.activate(pinned))
        assertEquals(GmsDozeBrokerStartStatus.UNCERTAIN, shell.start(prepared) { error("start uncertain") }.status)
        assertTrue(shell.isBusy())
        assertNotNull(shell.revoke(f.addKey, 1))
        assertTrue(shell.isBusy())
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, shell.start(prepared) { error("revoked") }.status)
        val other = GmsDozeBrokerV2Admission(f.addKey, grant)
        assertTrue(other.activate(pinned))
        assertNotNull(other.revoke(f.addKey, 1))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, other.start(prepared) { error("revoked before start") }.status)
    }

    @Test fun `completed child releases across both OFF revoke delivery orders without reopening`() {
        for (durableRevokeBeforeResult in listOf(true, false)) {
            val f = V2Fixture()
            val grant = f.add().grant
            val pinned = assertNotNull(f.coordinator.pin(grant.grantId, f.addKey.nonce))
            val shell = GmsDozeBrokerV2Admission(f.addKey, grant)
            assertTrue(shell.activate(pinned))
            val prepared = assertNotNull(f.coordinator.prepare(f.addKey, grant.grantId))
            val child = object : GmsDozeBrokerChild {}
            assertEquals(GmsDozeBrokerStartStatus.STARTED, shell.start(prepared) { child }.status)
            val cleanup = assertNotNull(shell.cleanupEvidence(child, true, true, true, true, true))

            if (durableRevokeBeforeResult) assertNotNull(f.coordinator.requestRevoke(f.addKey))
            val businessBytes = f.bytes(DozePhase.APPLIED, enabled = !durableRevokeBeforeResult)
            val result = GmsDozeBrokerV2Result(cleanup.preparation,
                GmsDozeBrokerV2Codec.sha256(businessBytes), GmsDozeBrokerV2Codec.rawSha256(f.applied))
            val committed = assertNotNull(f.coordinator.commitResult(cleanup, result, businessBytes))
            assertEquals(if (durableRevokeBeforeResult) 1L else 0L, assertNotNull(committed.state.add).revokeVersion)

            if (!durableRevokeBeforeResult) {
                assertNotNull(f.coordinator.requestRevoke(f.addKey))
                assertNotNull(shell.revoke(f.addKey, 1))
            }
            // Either durable proof version 1 reaches shell version 0 before revoke delivery,
            // or the retained version 0 result proof reaches shell version 1 after delivery.
            val release = assertNotNull(shell.release(child, cleanup, committed))
            assertFalse(shell.isBusy())
            assertNotNull(f.coordinator.acceptReleaseAck(release))
            assertFalse(shell.activate(pinned))
            assertEquals(GmsDozeBrokerStartStatus.REJECTED, shell.start(prepared) { error("released grant reopened") }.status)
            if (durableRevokeBeforeResult) assertNotNull(shell.revoke(f.addKey, 1))
            assertFalse(shell.isBusy())
            assertFalse(shell.activate(pinned))
            assertEquals(GmsDozeBrokerStartStatus.REJECTED, shell.start(prepared) { error("revoked grant reopened") }.status)
        }
    }

    @Test fun `actual start registration and revocation share one monitor`() {
        val f = V2Fixture()
        val grant = f.add().grant
        val pinned = assertNotNull(f.coordinator.pin(grant.grantId, f.addKey.nonce))
        val shell = GmsDozeBrokerV2Admission(f.addKey, grant)
        assertTrue(shell.activate(pinned))
        val prepared = assertNotNull(f.coordinator.prepare(f.addKey, grant.grantId))
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val revokeAttempted = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val child = object : GmsDozeBrokerChild {}
        val starter = thread { shell.start(prepared) { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); child } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val revoker = thread { revokeAttempted.countDown(); shell.revoke(f.addKey, 1); revoked.countDown() }
        assertTrue(revokeAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(revoked.await(100, TimeUnit.MILLISECONDS))
        finish.countDown()
        starter.join(5000)
        revoker.join(5000)
        assertEquals(0L, revoked.count)
        assertTrue(shell.isBusy())
        assertNotNull(shell.cleanupEvidence(child, true, true, true, true, true))
    }

    @Test fun `exact CAS rejection preserves slot and uncertain readback durably fences`() {
        val f = V2Fixture()
        f.port.rejectCas = true
        assertNull(f.coordinator.issueAddGrant(f.identity, f.bytes(DozePhase.ADD_INTENT)))
        assertEquals(GmsDozeBrokerV2Codec.initialState(), f.port.readExact().state)
        f.port.rejectCas = false
        f.port.wrongReadback = true
        assertNull(f.coordinator.issueAddGrant(f.identity, f.bytes(DozePhase.ADD_INTENT)))
        assertTrue(f.port.fenced)
    }
}
