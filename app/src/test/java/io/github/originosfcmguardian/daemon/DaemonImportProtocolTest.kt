package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DaemonImportProtocolTest {
    private val owner = RootFixtures.owner
    private val caller = DaemonCallerEvidence(10435, DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10435, 100, listOf("c".repeat(64))), 79, 200))
    private fun prepared(extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()): DaemonProtectionRootSnapshot {
        val original = RootFixtures.snapshot(extras = extras)
        return DaemonProtectionRootSnapshot(original.control.copy(mode = DaemonControlMode.PREPARED), original.domains())
    }
    private inner class Port : DaemonImportPort {
        var authenticated = true
        var authentications = 0
        var beforeWrite: () -> Unit = {}
        var saved: DaemonProtectionRootSnapshot? = null
        var writes = 0
        var verifications = 0
        override fun authenticateFresh(caller: DaemonCallerEvidence): Boolean {
            authentications++
            return authenticated && caller == this@DaemonImportProtocolTest.caller
        }
        override fun initializeImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
            beforeWrite()
            check(validateFresh())
            writes++
            saved = snapshot
        }
        override fun verifyImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
            beforeWrite()
            check(validateFresh())
            check(DaemonPreparedImportVerifier.matches(checkNotNull(saved), snapshot))
            verifications++
        }
    }
    private fun upload(protocol: DaemonImportProtocol, bytes: ByteArray): String {
        val session = protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes))
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + DaemonImportProtocol.MAX_CHUNK_BYTES, bytes.size)
            assertEquals(end, protocol.appendImport(caller, session, offset, bytes.copyOfRange(offset, end)))
            offset = end
        }
        return session
    }

    @Test fun uploadIsStagedAndPreservesRawDomainsButCannotForgeNativeHeartbeatOwner() {
        val snapshot = prepared(mapOf(DaemonDomainRole.RECOVERY_COUNTS to ByteArray(150_000) { (it % 251).toByte() },
            DaemonDomainRole.HEARTBEAT_DAEMON_OWNER to byteArrayOf(7),
            DaemonDomainRole.HEARTBEAT_DERIVATION to byteArrayOf(8), DaemonDomainRole.STOPPED_BLOCKED to byteArrayOf()))
        val bytes = DaemonProtectionRootCodec.encode(snapshot)
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        val session = upload(protocol, bytes)
        assertEquals(0, port.writes)
        assertEquals(snapshot.control, protocol.finishImport(caller, session))
        assertEquals(1, port.writes)
        assertNull(port.saved!!.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER))
        assertNull(port.saved!!.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))
        for (role in DaemonDomainRole.entries.filter {
            it !in setOf(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, DaemonDomainRole.HEARTBEAT_DERIVATION) })
            assertArrayEquals(snapshot.bytes(role), port.saved!!.bytes(role))
        assertEquals(DaemonDomainRole.entries.toSet(), port.saved!!.domains().keys)
        assertEquals(DaemonControlMode.PREPARED, port.saved!!.control.mode)
        assertEquals(snapshot.control, protocol.finishImport(caller, session))
        assertEquals(session, protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)))
        assertEquals(bytes.size, protocol.importOffset(caller, session))
        assertEquals(1, port.writes)
        assertEquals(1, port.verifications)
    }

    @Test fun chunkOffsetSizeAndSessionOwnerAreExactAndChunksAreCopied() {
        val bytes = DaemonProtectionRootCodec.encode(prepared())
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        val session = protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes))
        assertEquals(session, protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)))
        assertEquals(0, protocol.importOffset(caller, session))
        RootFixtures.fails { protocol.beginImport(caller, bytes.size + 1, DaemonProtectionRootSnapshot.digest(bytes)) }
        RootFixtures.fails { protocol.beginImport(caller, bytes.size, "f".repeat(64)) }
        RootFixtures.fails { protocol.importOffset(caller, "other") }
        RootFixtures.fails { protocol.appendImport(caller, "other", 0, bytes) }
        RootFixtures.fails { protocol.appendImport(caller.copy(realUid = 10436), session, 0, bytes) }
        RootFixtures.fails { protocol.appendImport(caller, session, 1, bytes) }
        RootFixtures.fails { protocol.appendImport(caller, session, 0, byteArrayOf()) }
        RootFixtures.fails { protocol.appendImport(caller, session, 0, ByteArray(65537)) }
        RootFixtures.fails { protocol.appendImport(caller, session, 0, ByteArray(bytes.size + 1)) }
        RootFixtures.fails { protocol.finishImport(caller, session) }
        val chunk = bytes.copyOf(); protocol.appendImport(caller, session, 0, chunk); chunk.fill(0)
        assertEquals(bytes.size, protocol.importOffset(caller, session))
        RootFixtures.fails { protocol.appendImport(caller, session, 0, bytes) }
        protocol.finishImport(caller, session)
        assertEquals(1, port.writes)
    }

    @Test fun authenticationPrecedesFinishAndIsRepeatedAtTheDurableWriteBoundary() {
        for (revokeAtWrite in listOf(false, true)) {
            val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
            val bytes = DaemonProtectionRootCodec.encode(prepared())
            val session = upload(protocol, bytes)
            if (revokeAtWrite) port.beforeWrite = { port.authenticated = false }
            else port.authenticated = false
            RootFixtures.fails { protocol.finishImport(caller, session) }
            RootFixtures.fails { protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)) }
            assertEquals(0, port.writes)
            assertNull(port.saved)
        }
    }

    @Test fun invalidDigestCorruptionWrongOwnerMigrationAndNoninitialControlNeverInitialize() {
        val before = prepared()
        val invalid = listOf(
            DaemonProtectionRootSnapshot(before.control.copy(owner = owner.copy(ownerEpoch = 2)), before.domains()),
            DaemonProtectionRootSnapshot(before.control.copy(migrationId = "other"), before.domains()),
            DaemonProtectionRootSnapshot(before.control.copy(mode = DaemonControlMode.ACTIVE), before.domains()),
            DaemonProtectionRootSnapshot(before.control.copy(mode = DaemonControlMode.OFF), before.domains()),
            DaemonProtectionRootSnapshot(before.control.copy(revision = 2), before.domains()),
            DaemonProtectionRootSnapshot(before.control, before.domains(), dataRevision = 2),
        ).map(DaemonProtectionRootCodec::encode) + listOf(
            DaemonProtectionRootCodec.encode(before).apply { this[10] = (this[10].toInt() xor 1).toByte() },
            DaemonProtectionRootCodec.encode(before).let { it.copyOf(it.size - 1) })
        for (bytes in invalid) {
            val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
            val session = upload(protocol, bytes)
            RootFixtures.fails { protocol.finishImport(caller, session) }
            assertEquals(0, port.writes)
            RootFixtures.fails { protocol.finishImport(caller, session) }
        }
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        val bytes = DaemonProtectionRootCodec.encode(before)
        val session = protocol.beginImport(caller, bytes.size, "0".repeat(64))
        protocol.appendImport(caller, session, 0, bytes)
        RootFixtures.fails { protocol.finishImport(caller, session) }
        assertEquals(0, port.writes)
    }

    @Test fun invalidBeginCannotAllocateOrConsumeTheSingleSession() {
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        for (size in listOf(-1, 0, 39, DaemonProtectionRootCodec.MAX_BYTES + 1))
            RootFixtures.fails { protocol.beginImport(caller, size, "a".repeat(64)) }
        for (digest in listOf("", "a".repeat(63), "A".repeat(64)))
            RootFixtures.fails { protocol.beginImport(caller, 100, digest) }
        port.authenticated = false
        RootFixtures.fails { protocol.beginImport(caller, 100, "a".repeat(64)) }
        port.authenticated = true
        upload(protocol, DaemonProtectionRootCodec.encode(prepared())).let { protocol.finishImport(caller, it) }
        assertEquals(1, port.writes)
    }

    @Test fun blockedInitializationDoesNotHoldTheStagingMonitorOrPermitAnotherFinish() {
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        val bytes = DaemonProtectionRootCodec.encode(prepared())
        val session = upload(protocol, bytes)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val rejected = CountDownLatch(1)
        port.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var finishFailure: Throwable? = null
        val worker = Thread { try { protocol.finishImport(caller, session) } catch (failure: Throwable) { finishFailure = failure } }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            RootFixtures.fails { protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)) }
            RootFixtures.fails { protocol.importOffset(caller, session) }
            val contender = Thread {
                try { protocol.finishImport(caller, session); fail("Upload replayed") }
                catch (_: IllegalStateException) { rejected.countDown() }
            }
            contender.start()
            assertTrue("Staging lock held across import IO", rejected.await(1, TimeUnit.SECONDS))
            contender.join(1000)
        } finally { release.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive)
        assertNull(finishFailure)
        assertEquals(1, port.writes)
    }

    @Test fun sessionPrincipalRetainsAnImmutableCopyOfSystemSignerEvidence() {
        val signers = mutableListOf("c".repeat(64))
        val mutableCaller = caller.copy(installation = caller.installation.copy(identity =
            caller.installation.identity.copy(signerSha256 = signers)))
        val port = object : DaemonImportPort {
            override fun authenticateFresh(caller: DaemonCallerEvidence) = true
            override fun initializeImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) = Unit
        }
        val protocol = DaemonImportProtocol(owner, "migration", port)
        val bytes = DaemonProtectionRootCodec.encode(prepared())
        val session = protocol.beginImport(mutableCaller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes))
        signers[0] = "d".repeat(64)
        RootFixtures.fails { protocol.appendImport(mutableCaller, session, 0, bytes) }
        assertEquals(bytes.size, protocol.appendImport(caller, session, 0, bytes))
    }

    @Test fun exactPreparedReverificationRetainsOnlyAnExactActivateProposalAndIgnoresDataRevision() {
        val expected = prepared()
        val plan = DaemonControlTransitionPlan("activate", expected.control,
            expected.control.copy(revision = 2, mode = DaemonControlMode.ACTIVE), DaemonControlTransition.ACTIVATE)
        val domains = expected.domains() + (DaemonDomainRole.CONTROL_PROPOSAL to DaemonControlProposalCodec.encode(plan))
        val existing = DaemonProtectionRootSnapshot(expected.control, domains, dataRevision = 5)
        assertTrue(DaemonPreparedImportVerifier.matches(existing, expected))
        assertTrue(DaemonPreparedImportVerifier.matches(expected, expected))
        for (mode in listOf(DaemonControlMode.ACTIVE, DaemonControlMode.OFF))
            assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(
                expected.control.copy(mode = mode), domains), expected))
        assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(
            expected.control.copy(revision = 2), domains), expected))
        for (role in listOf(DaemonDomainRole.SELECTION, DaemonDomainRole.RECOVERY_COUNTS,
            DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, DaemonDomainRole.HEARTBEAT_DERIVATION))
            assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(expected.control,
                domains + (role to byteArrayOf(1))), expected))
        val off = DaemonControlTransitionPlan("off", expected.control,
            expected.control.copy(revision = 2, mode = DaemonControlMode.OFF), DaemonControlTransition.SET_AUTHORITY)
        assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(expected.control,
            domains + (DaemonDomainRole.CONTROL_PROPOSAL to DaemonControlProposalCodec.encode(off))), expected))
        assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(expected.control,
            domains + (DaemonDomainRole.CONTROL_PROPOSAL to byteArrayOf(1))), expected))
        val halt = DaemonHaltFence(plan.request(DaemonPointerCommitProof("pointer", plan.next)), 2)
        assertFalse(DaemonPreparedImportVerifier.matches(DaemonProtectionRootSnapshot(expected.control, domains, halt = halt), expected))
    }

    @Test fun lostFinishReplyRechecksCurrentPreparedBytesAndRejectsChangedOrUnauthenticatedState() {
        val port = Port(); val protocol = DaemonImportProtocol(owner, "migration", port)
        val bytes = DaemonProtectionRootCodec.encode(prepared())
        val session = upload(protocol, bytes)
        protocol.finishImport(caller, session)
        port.saved = prepared(mapOf(DaemonDomainRole.SELECTION to byteArrayOf(1)))
        RootFixtures.fails { protocol.finishImport(caller, session) }
        assertEquals(1, port.writes)
        RootFixtures.fails { protocol.finishImport(caller, session) }
        RootFixtures.fails { protocol.beginImport(caller, bytes.size, DaemonProtectionRootSnapshot.digest(bytes)) }
    }
}
