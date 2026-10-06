package io.github.originosfcmguardian.core.doze

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GmsGlobalDozeCodecTest {
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64), "b".repeat(64)))
    private val guardian = GmsGlobalGuardianBinding(0, gms.copy(appId = 10493))
    private val consent = GmsGlobalDozeConsent(true, 1, 1, 1000, gms, guardian, "fingerprint")
    private val baseline = "system,android,1000\nuser,com.example.other,10123\n"
    private val row = "user,$GMS_GLOBAL_DOZE_PACKAGE,10208\n"
    private val intent = GmsGlobalDozeTransaction("00000000-0000-0000-0000-000000000001", 1, gms, 1,
        baseline, null, DozePhase.ADD_INTENT)
    private fun document(transaction: GmsGlobalDozeTransaction? = null, generation: Long = 1) =
        GmsGlobalDozeDocument(consent.copy(generation = generation), transaction)
    private fun bytes(value: GmsGlobalDozeDocument) = GmsGlobalDozeCodec.encode(value)

    @Test fun exactRoundTripPreservesAllInstallationsConsentAndRawWhitelistBytes() {
        val states = listOf(document(), document(intent, 2), document(intent.copy(applied = row + baseline,
            phase = DozePhase.APPLIED), 3))
        for (state in states) {
            val encoded = bytes(state)
            assertEquals(state, GmsGlobalDozeCodec.decode(encoded))
            assertTrue(encoded.contentEquals(bytes(GmsGlobalDozeCodec.decode(encoded))))
        }
    }

    @Test fun checksumLengthTruncationUnknownSchemaTrailingDataAndBooleanAreRejected() {
        val good = bytes(document())
        assertFails { GmsGlobalDozeCodec.decode(good.clone().also { it[10] = (it[10].toInt() xor 1).toByte() }) }
        assertFails { GmsGlobalDozeCodec.decode(good.copyOf(good.size - 1)) }
        assertFails { GmsGlobalDozeCodec.decode(ByteArray(GmsGlobalDozeCodec.MAX_BYTES + 1)) }
        fun hashed(body: ByteArray) = body + MessageDigest.getInstance("SHA-256").digest(body)
        val body = good.copyOfRange(0, good.size - 32)
        assertFails { GmsGlobalDozeCodec.decode(hashed(body.clone().also { it[7] = 2 })) }
        assertFails { GmsGlobalDozeCodec.decode(hashed(body.clone().also { it[8] = 2 })) }
        assertFails { GmsGlobalDozeCodec.decode(hashed(body + byteArrayOf(0))) }
    }

    @Test fun signerListsAreFullCanonicalIdentitiesAndNeverSilentlySorted() {
        for (signers in listOf(emptyList(), listOf("abcd"), listOf("A".repeat(64)),
            listOf("b".repeat(64), "a".repeat(64)), listOf("a".repeat(64), "a".repeat(64)))) {
            assertFails { bytes(document().copy(consent = consent.copy(gms = gms.copy(signerSha256 = signers)))) }
        }
        assertFails { bytes(document().copy(consent = consent.copy(gms = gms.copy(lastUpdateTime = 99)))) }
        assertFails { bytes(document().copy(consent = consent.copy(guardian = guardian.copy(userId = 999)))) }
        assertFails { bytes(document().copy(consent = consent.copy(gms = gms.copy(appId = 9999)))) }
    }

    @Test fun onlyExplicitFirstConsentAndMonotonicExactRevisionsAreAllowed() {
        val initial = bytes(document())
        GmsGlobalDozeCodec.validateTransition(null, initial)
        assertFails { GmsGlobalDozeCodec.validateTransition(null, bytes(document(intent))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(null, bytes(document().copy(consent = consent.copy(enabled = false)))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(initial, bytes(document(intent, 3))) }
        GmsGlobalDozeCodec.validateTransition(initial, bytes(document(intent, 2)))
        assertFails { GmsGlobalDozeCodec.validateTransition(initial, bytes(document(intent.copy(epoch = 2), 2))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(initial, bytes(document(intent.copy(
            phase = DozePhase.APPLIED, applied = baseline + row), 2))) }
    }

    @Test fun offPreservesIntentAndLateAppliedMayFollowOffWithoutReenabling() {
        val before = document(intent, 2)
        val off = before.copy(consent = before.consent.copy(enabled = false, epoch = 2, generation = 3))
        GmsGlobalDozeCodec.validateTransition(bytes(before), bytes(off))
        val applied = off.copy(consent = off.consent.copy(generation = 4),
            transaction = intent.copy(applied = baseline + row, phase = DozePhase.APPLIED))
        GmsGlobalDozeCodec.validateTransition(bytes(off), bytes(applied))
        assertEquals(false, GmsGlobalDozeCodec.decode(bytes(applied)).consent.enabled)
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(before), bytes(off.copy(transaction = null))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(off), bytes(applied.copy(consent = applied.consent.copy(enabled = true)))) }
    }

    @Test fun transactionIdentityBaselineAndAppliedEvidenceAreImmutable() {
        val old = document(intent, 2)
        val applied = document(intent.copy(applied = baseline + row, phase = DozePhase.APPLIED), 3)
        GmsGlobalDozeCodec.validateTransition(bytes(old), bytes(applied))
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(old), bytes(applied.copy(transaction =
            applied.transaction!!.copy(id = "00000000-0000-0000-0000-000000000002")))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(old), bytes(applied.copy(transaction =
            applied.transaction!!.copy(baseline = baseline.trimEnd())))) }
        val restore = applied.copy(consent = consent.copy(generation = 4), transaction = applied.transaction!!.copy(phase = DozePhase.RESTORE_INTENT))
        GmsGlobalDozeCodec.validateTransition(bytes(applied), bytes(restore))
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(applied), bytes(restore.copy(transaction =
            restore.transaction!!.copy(applied = row + baseline)))) }
        val closed = restore.copy(consent = consent.copy(generation = 5), transaction = restore.transaction!!.copy(phase = DozePhase.RESTORED))
        GmsGlobalDozeCodec.validateTransition(bytes(restore), bytes(closed))
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(closed), bytes(closed.copy(consent = consent.copy(generation = 6)))) }
        assertFails { GmsGlobalDozeCodec.validateTransition(bytes(closed), bytes(closed.copy(consent = consent.copy(epoch = 2,
            generation = 6, confirmedAtMillis = 2000)))) }
    }

    @Test fun unknownProofAndNonExactDeltasNeverBecomeDurableOwnership() {
        assertFails { bytes(document(intent.copy(ownerProofVersion = 2), 2)) }
        assertFails { bytes(document(intent.copy(baseline = baseline + row), 2)) }
        assertFails { bytes(document(intent.copy(applied = baseline + "system,$GMS_GLOBAL_DOZE_PACKAGE,10208\n",
            phase = DozePhase.APPLIED), 3)) }
        assertFails { bytes(document(intent.copy(applied = baseline + row + "user,com.other.new,11111\n",
            phase = DozePhase.APPLIED), 3)) }
        assertFails { bytes(document(intent.copy(applied = null, phase = DozePhase.APPLIED), 3)) }
    }
}
