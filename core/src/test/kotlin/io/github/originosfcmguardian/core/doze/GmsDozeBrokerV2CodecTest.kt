package io.github.originosfcmguardian.core.doze

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class GmsDozeBrokerV2CodecTest {
    @Test fun `V1 and V2 strictly reject each others bytes and preserve V1 format`() {
        val legacy = GmsDozeBrokerCodec.encode(GmsDozeBrokerCodec.initialState())
        val current = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())
        assertEquals(1, ByteBuffer.wrap(legacy).getInt(4))
        assertEquals(2, ByteBuffer.wrap(current).getInt(4))
        assertFails { GmsDozeBrokerCodec.decode(current) }
        assertFails { GmsDozeBrokerV2Codec.decode(legacy) }
        assertContentEquals(legacy, GmsDozeBrokerCodec.encode(GmsDozeBrokerCodec.decode(legacy)))
        assertFails { GmsDozeBrokerV2Codec.validateTransition(legacy, current) }
        assertFails { GmsDozeBrokerCodec.validateTransition(current, legacy) }
    }

    @Test fun `canonical bytes cloned validated and identity lists cannot be mutated`() {
        val f = V2Fixture()
        f.preparedAdd()
        val exact = f.port.readExact().exactBytes()
        val snapshot = exact.clone()
        val proof = GmsDozeBrokerV2Codec.decodeProof(exact)
        exact.fill(0)
        proof.exactBytes().fill(0)
        assertContentEquals(snapshot, proof.exactBytes())
        assertEquals(proof.state, GmsDozeBrokerV2Codec.decode(snapshot))
        val signers = assertNotNull(proof.state.add).grant.identity.gms.signerSha256
        assertFails { (signers as MutableList<String>)[0] = "f".repeat(64) }
        val badChecksum = snapshot.clone().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFails { GmsDozeBrokerV2Codec.decode(badChecksum) }
        assertFails { GmsDozeBrokerV2Codec.decode(snapshot.copyOf(snapshot.size - 1)) }
        assertFails { GmsDozeBrokerV2Codec.decode(rechecksum(snapshot.copyOf(snapshot.size + 1))) }
    }

    @Test fun `raw hashes preserve whitespace row ordering and complete UTF8`() {
        val f = V2Fixture()
        assertNotEquals(GmsDozeBrokerV2Codec.rawSha256(f.baseline), GmsDozeBrokerV2Codec.rawSha256(f.baseline.trimEnd()))
        assertNotEquals(GmsDozeBrokerV2Codec.rawSha256(f.baseline),
            GmsDozeBrokerV2Codec.rawSha256(f.baseline.lines().reversed().joinToString("\n")))
        assertEquals(GmsDozeBrokerV2Codec.sha256("完整\n".toByteArray(Charsets.UTF_8)), GmsDozeBrokerV2Codec.rawSha256("完整\n"))
        assertFails { GmsDozeBrokerV2Codec.rawSha256("\uD800") }
    }

    @Test fun `malformed canonical fields unknown owners and legacy operation code are rejected`() {
        val f = V2Fixture()
        val grant = f.add().grant
        val state = f.port.readExact().state
        fun reject(value: GmsDozeBrokerV2Grant) = assertFails {
            GmsDozeBrokerV2Codec.encode(state.copy(add = GmsDozeBrokerV2Attempt(value)))
        }
        reject(grant.copy(grantId = grant.grantId.uppercase()))
        reject(grant.copy(globalTransactionId = "not-uuid"))
        reject(grant.copy(baselineSha256 = "A".repeat(64)))
        reject(grant.copy(appliedSha256 = "a".repeat(64)))
        reject(grant.copy(identity = grant.identity.copy(owners = GmsGlobalOwnerEvidence.Unknown)))
        reject(grant.copy(identity = grant.identity.copy(environment = grant.identity.environment.copy(executionUid = 0))))
        val bytes = GmsDozeBrokerV2Codec.encode(state)
        // Prefix is 16-byte header + present byte + int-length UUID (36), then fixed operation byte.
        val unknownOperation = bytes.clone().also { it[57] = 3 }
        assertFails { GmsDozeBrokerV2Codec.decode(rechecksum(unknownOperation)) }
        val badBoolean = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState()).also { it[16] = 2 }
        assertFails { GmsDozeBrokerV2Codec.decode(rechecksum(badBoolean)) }
    }

    @Test fun `transition validation forbids revision jumps replacement removal and combined effects`() {
        val f = V2Fixture()
        f.preparedAdd()
        val before = f.port.readExact()
        val state = before.state
        val attempt = assertNotNull(state.add)
        fun reject(after: GmsDozeBrokerV2State) = assertFails {
            GmsDozeBrokerV2Codec.validateTransition(before.exactBytes(), GmsDozeBrokerV2Codec.encode(after))
        }
        reject(state.copy(revision = state.revision + 2, add = attempt.copy(revokeVersion = 1)))
        reject(state.copy(revision = state.revision + 1, add = attempt.copy(pin = GmsDozeBrokerKey("replacement", 1), preparation = null)))
        reject(state.copy(revision = state.revision + 1, add = attempt.copy(revokeVersion = 1, acknowledgedRevoke = 1)))
        reject(state.copy(revision = state.revision + 1, add = attempt.copy(grant = attempt.grant.copy(businessIntentSha256 = "f".repeat(64)))))
        assertFails { GmsDozeBrokerV2Codec.validateTransition(before.exactBytes(), GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())) }
        assertFails { GmsDozeBrokerV2Codec.validateTransition(null, before.exactBytes()) }
        val revoke = GmsDozeBrokerV2Codec.encode(state.copy(revision = state.revision + 1, add = attempt.copy(revokeVersion = 1)))
        GmsDozeBrokerV2Codec.validateTransition(before.exactBytes(), revoke)
    }

    @Test fun `restore cannot substitute raw delta transaction epoch or installation identity`() {
        val f = V2Fixture()
        val add = f.retireAdd()
        val proof = assertNotNull(f.coordinator.issueRestoreGrant(f.identity, f.bytes(DozePhase.RESTORE_INTENT)))
        val restore = assertNotNull(proof.state.restore)
        for (grant in listOf(
            restore.grant.copy(transactionEpoch = 2),
            restore.grant.copy(baselineSha256 = "a".repeat(64)),
            restore.grant.copy(appliedSha256 = "a".repeat(64)),
            restore.grant.copy(identity = restore.grant.identity.copy(gms = f.gms.copy(versionCode = 124))),
            restore.grant.copy(grantId = add.grant.grantId),
        )) assertFails { GmsDozeBrokerV2Codec.encode(proof.state.copy(restore = restore.copy(grant = grant))) }
    }

    private fun rechecksum(bytes: ByteArray): ByteArray {
        val body = bytes.copyOfRange(0, bytes.size - 32)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
}
