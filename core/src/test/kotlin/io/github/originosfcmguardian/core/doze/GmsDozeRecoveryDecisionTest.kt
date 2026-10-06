package io.github.originosfcmguardian.core.doze

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Host-only fake snapshots and lifecycle facts; none of these tests is device evidence. */
class GmsDozeRecoveryDecisionTest {
    private data class HostSnapshot(
        val global: ByteArray? = null,
        val broker: ByteArray? = null,
        val fence: ByteArray? = null,
        val legacy: ByteArray? = null,
        val observation: GmsGlobalDozeObservation?,
        val certain: Boolean = true,
        val fingerprint: String = "v2-host",
    )

    private fun decide(snapshot: HostSnapshot) = GmsDozeRecoveryDecider.decide(snapshot.fingerprint,
        snapshot.certain, snapshot.global, snapshot.broker, snapshot.fence, snapshot.legacy, snapshot.observation)

    private fun assertKind(expected: GmsDozeRecoveryKind, snapshot: HostSnapshot): GmsDozeRecoveryDecision =
        decide(snapshot).also {
            assertEquals(expected, it.kind)
            assertFalse(it.nextCycleAllowed)
            assertTrue(it.reason.isNotBlank())
        }

    private fun completedApplied(f: V2Fixture = V2Fixture()): HostSnapshot {
        val finished = f.finishAdd(f.preparedAdd())
        val prep = assertNotNull(finished.preparation)
        assertNotNull(f.coordinator.acceptReleaseAck(GmsDozeBrokerV2ReleaseAck(f.addKey, prep.grantId, prep.preparationRevision)))
        return HostSnapshot(f.bytes(DozePhase.APPLIED), f.port.readExact().exactBytes(), ByteArray(32) { 7 },
            observation = GmsGlobalDozeObservation(f.identity, f.applied))
    }

    @Test fun `confirmed absence qualifies existing SYSTEM or USER membership but grants no ownership`() {
        val f = V2Fixture()
        for (source in listOf("system", "user")) {
            val observation = GmsGlobalDozeObservation(f.identity,
                f.baseline + "$source,com.google.android.gms,${f.gms.appId}\n")
            assertKind(GmsDozeRecoveryKind.PREEXISTING_FULL_EXEMPTION, HostSnapshot(observation = observation))
        }
        val full = GmsGlobalDozeObservation(f.identity, f.applied)
        val rejected = listOf(
            HostSnapshot(observation = full.copy(rawWhitelist = f.baseline + "system-excidle,com.google.android.gms,10208\n")),
            HostSnapshot(observation = full.copy(rawWhitelist = f.baseline)),
            HostSnapshot(observation = null),
            HostSnapshot(observation = full.copy(identity = f.identity.copy(owners = GmsGlobalOwnerEvidence.Unknown))),
            HostSnapshot(observation = full.copy(rawWhitelist = f.baseline + "system,com.google.android.gms,10209\n")),
            HostSnapshot(observation = full, certain = false),
            HostSnapshot(observation = full, broker = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())),
            HostSnapshot(observation = full, fence = ByteArray(32)),
        )
        for (snapshot in rejected) assertKind(GmsDozeRecoveryKind.BLOCKED, snapshot)
    }

    @Test fun `complete APPLIED is retained after normal OFF despite historical document hash change`() {
        val f = V2Fixture()
        val on = completedApplied(f)
        assertKind(GmsDozeRecoveryKind.APPLIED_RETAINED, on)
        val before = GmsGlobalDozeCodec.decode(assertNotNull(on.global))
        val offDocument = before.copy(consent = before.consent.copy(enabled = false,
            epoch = before.consent.epoch + 1, generation = before.consent.generation + 1))
        val offBytes = GmsGlobalDozeCodec.encode(offDocument)
        GmsGlobalDozeCodec.validateTransition(on.global, offBytes)
        val add = assertNotNull(GmsDozeBrokerV2Codec.decode(assertNotNull(on.broker)).add)
        val historical = assertNotNull(add.result).businessResultSha256
        assertEquals(historical, GmsDozeBrokerV2Codec.sha256(assertNotNull(on.global)))
        assertNotEquals(historical, GmsDozeBrokerV2Codec.sha256(offBytes))
        val freshQualified = f.identity.copy(owners = GmsGlobalOwnerEvidence.Qualified(1, "e".repeat(64), f.gms.appId))
        val off = on.copy(global = offBytes, observation = GmsGlobalDozeObservation(freshQualified, f.applied))
        assertKind(GmsDozeRecoveryKind.APPLIED_RETAINED, off)
        assertEquals(false, GmsGlobalDozeCodec.decode(offBytes).consent.enabled)
        assertEquals(add.result, GmsDozeBrokerV2Codec.decode(assertNotNull(off.broker)).add?.result)
    }

    @Test fun `intents commit gaps corruption absence and identity or raw mismatches block`() {
        val f = V2Fixture()
        val complete = completedApplied(f)
        val brokerState = GmsDozeBrokerV2Codec.decode(assertNotNull(complete.broker))
        val add = assertNotNull(brokerState.add)
        val current = assertNotNull(complete.observation)
        fun brokerWith(attempt: GmsDozeBrokerV2Attempt) = GmsDozeBrokerV2Codec.encode(brokerState.copy(add = attempt))
        fun corrupt(bytes: ByteArray) = bytes.clone().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val wrongTransaction = f.document(DozePhase.APPLIED).copy(transaction =
            f.document(DozePhase.APPLIED).transaction!!.copy(id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        val reversed = f.applied.trimEnd().lines().reversed().joinToString("\n", postfix = "\n")
        val rejected = listOf(
            complete.copy(global = f.bytes(DozePhase.ADD_INTENT)),
            complete.copy(global = f.bytes(DozePhase.RESTORE_INTENT)),
            complete.copy(global = null),
            complete.copy(broker = null),
            complete.copy(fence = null),
            complete.copy(fence = ByteArray(31)),
            complete.copy(fence = ByteArray(33)),
            complete.copy(global = corrupt(assertNotNull(complete.global))),
            complete.copy(broker = corrupt(assertNotNull(complete.broker))),
            complete.copy(broker = brokerWith(add.copy(result = null, releaseAcknowledged = false))),
            complete.copy(broker = brokerWith(add.copy(releaseAcknowledged = false))),
            complete.copy(global = GmsGlobalDozeCodec.encode(wrongTransaction)),
            complete.copy(broker = brokerWith(add.copy(grant = add.grant.copy(transactionEpoch = 2)))),
            complete.copy(broker = brokerWith(add.copy(grant = add.grant.copy(baselineSha256 = "f".repeat(64))))),
            complete.copy(broker = brokerWith(add.copy(result = add.result!!.copy(appliedSha256 = "f".repeat(64))))),
            complete.copy(observation = current.copy(rawWhitelist = reversed)),
            complete.copy(observation = current.copy(rawWhitelist = f.baseline)),
            complete.copy(observation = current.copy(rawWhitelist = "bad whitelist")),
            complete.copy(observation = current.copy(identity = f.identity.copy(gms = f.gms.copy(versionCode = 124)))),
            complete.copy(observation = current.copy(identity = f.identity.copy(guardian = f.guardian.copy(
                installation = f.guardian.installation.copy(signerSha256 = listOf("b".repeat(64))))))),
            complete.copy(observation = current.copy(identity = f.identity.copy(environment = f.identity.environment.copy(executionUid = 0)))),
            complete.copy(observation = current.copy(identity = f.identity.copy(environment = f.identity.environment.copy(currentUserId = 10)))),
            complete.copy(observation = current.copy(identity = f.identity.copy(owners = GmsGlobalOwnerEvidence.Unknown))),
            complete.copy(observation = null),
            complete.copy(fingerprint = "different-build"),
            complete.copy(certain = false),
        )
        for (snapshot in rejected) assertKind(GmsDozeRecoveryKind.BLOCKED, snapshot)
    }

    @Test fun `complete terminal facts death and reconstruction never open a cycle or mutate input bytes`() {
        val f = V2Fixture()
        f.retireAdd()
        val issued = assertNotNull(f.coordinator.issueRestoreGrant(f.identity, f.bytes(DozePhase.RESTORE_INTENT)))
        val grant = assertNotNull(issued.state.restore).grant
        assertNotNull(f.coordinator.pin(grant.grantId, f.removeKey.nonce))
        val prepared = assertNotNull(f.coordinator.prepare(f.removeKey, grant.grantId))
        val prep = assertNotNull(prepared.state.restore?.preparation)
        assertNotNull(f.coordinator.commitResult(f.cleanup(prep), f.result(prep, DozePhase.RESTORED), f.bytes(DozePhase.RESTORED)))
        assertNotNull(f.coordinator.acceptReleaseAck(GmsDozeBrokerV2ReleaseAck(f.removeKey, prep.grantId, prep.preparationRevision)))
        assertNotNull(f.coordinator.acceptDeath(GmsDozeBrokerDeath(f.removeKey)))
        val terminal = HostSnapshot(f.bytes(DozePhase.RESTORED), f.port.readExact().exactBytes(), ByteArray(32) { 8 },
            legacy = legacy(DozePhase.RESTORED), observation = GmsGlobalDozeObservation(f.identity, f.baseline))
        assertKind(GmsDozeRecoveryKind.BLOCKED, terminal)
        // No process-local permit is taken as input; reconstruction can only repeat a read-only decision.
        assertKind(GmsDozeRecoveryKind.BLOCKED, terminal.copy(global = terminal.global?.clone(), broker = terminal.broker?.clone()))

        val observed = V2Fixture()
        observed.retireAdd()
        val observedTerminal = terminal.copy(global = observed.bytes(DozePhase.OBSERVED_RESTORED),
            broker = observed.port.readExact().exactBytes(), legacy = legacy(DozePhase.OBSERVED_RESTORED),
            observation = GmsGlobalDozeObservation(observed.identity, observed.baseline))
        assertKind(GmsDozeRecoveryKind.BLOCKED, observedTerminal)

        val pending = V2Fixture()
        pending.preparedAdd()
        assertNotNull(pending.coordinator.requestRevoke(pending.addKey))
        assertNotNull(pending.coordinator.acceptDeath(GmsDozeBrokerDeath(pending.addKey)))
        assertKind(GmsDozeRecoveryKind.BLOCKED, terminal.copy(global = pending.bytes(DozePhase.APPLIED),
            broker = pending.port.readExact().exactBytes(), observation = GmsGlobalDozeObservation(pending.identity, pending.applied)))

        val arrays = listOfNotNull(terminal.global, terminal.broker, terminal.fence, terminal.legacy)
        val originals = arrays.map { it.clone() }
        val decision = decide(terminal)
        for (index in arrays.indices) assertContentEquals(originals[index], arrays[index])
        arrays.forEach { it.fill(0) }
        assertEquals(GmsDozeRecoveryKind.BLOCKED, decision.kind)
        assertFalse(decision.nextCycleAllowed)
    }

    @Test fun `only absent or settled valid legacy journals qualify`() {
        val f = V2Fixture()
        val full = HostSnapshot(observation = GmsGlobalDozeObservation(f.identity, f.applied))
        for (phase in listOf(DozePhase.RESTORED, DozePhase.OBSERVED_RESTORED))
            assertKind(GmsDozeRecoveryKind.PREEXISTING_FULL_EXEMPTION, full.copy(legacy = legacy(phase)))
        for (phase in listOf(DozePhase.ADD_INTENT, DozePhase.APPLIED, DozePhase.RESTORE_INTENT))
            assertKind(GmsDozeRecoveryKind.BLOCKED, full.copy(legacy = legacy(phase)))
        assertKind(GmsDozeRecoveryKind.BLOCKED, full.copy(legacy = byteArrayOf(1, 2, 3)))
    }

    private fun legacy(phase: DozePhase): ByteArray {
        val identity = DozeIdentity("legacy-host", 0, 2000, GMS_GLOBAL_DOZE_PACKAGE, 10208,
            true, setOf(0), -1, setOf(GMS_GLOBAL_DOZE_PACKAGE))
        val baseline = "system,android,1000\n"
        return DozeJournalCodec.encode(DozeRecord(DozeTarget.GMS, identity, baseline,
            if (phase == DozePhase.ADD_INTENT) null else baseline + "user,com.google.android.gms,10208\n", phase))
    }
}
