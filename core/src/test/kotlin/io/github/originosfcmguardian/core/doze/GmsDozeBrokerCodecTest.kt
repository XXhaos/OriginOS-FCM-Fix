package io.github.originosfcmguardian.core.doze

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GmsDozeBrokerCodecTest {
    private val initial = GmsDozeBrokerCodec.initialState()
    private val enabled = initial.copy(revision = 1, preparationAllowed = true)
    private val key = GmsDozeBrokerKey("broker", 1)
    private val pinned = enabled.copy(revision = 2, lastBrokerGeneration = 1, pin = key, brokerClosed = false)
    private val intent = GmsDozeBrokerIntent(key, "tx", GmsDozeBrokerOperation.GLOBAL_ADD, 0, 3)
    private val prepared = pinned.copy(revision = 3, intent = intent)
    private val off = prepared.copy(revision = 4, preparationAllowed = false, revokeVersion = 1)
    private val closed = off.copy(revision = 5, brokerClosed = true)
    private val result = closed.copy(revision = 6, intent = null, completedResult = intent)
    private fun bytes(state: GmsDozeBrokerState) = GmsDozeBrokerCodec.encode(state)
    private fun body(state: GmsDozeBrokerState) = bytes(state).dropLast(32).toByteArray()
    private fun hashed(value: ByteArray) = value + MessageDigest.getInstance("SHA-256").digest(value)
    private fun legal(before: GmsDozeBrokerState?, after: GmsDozeBrokerState) =
        GmsDozeBrokerCodec.validateTransition(before?.let(::bytes), bytes(after))
    private fun illegal(before: GmsDozeBrokerState?, after: GmsDozeBrokerState) {
        assertFails { legal(before, after) }
    }
    private fun rejectBody(value: ByteArray) { assertFails { GmsDozeBrokerCodec.decode(hashed(value)) } }

    @Test fun absenceInitializesOnlyTheExactOffState() {
        assertEquals(GmsDozeBrokerState(0, 0, null, true, false, 0, null, null), initial)
        assertFalse(initial.preparationAllowed)
        assertEquals(69, bytes(initial).size)
        assertEquals(0x47444252, ByteBuffer.wrap(bytes(initial)).int)
        assertEquals(1, ByteBuffer.wrap(bytes(initial), 4, 4).int)
        legal(null, initial)
        for (state in listOf(enabled, pinned, prepared, off, closed, result)) illegal(null, state)
        assertFails { bytes(initial.copy(preparationAllowed = true)) }
        assertFails { GmsDozeBrokerCodec.decode(ByteArray(0)) }
    }

    @Test fun canonicalRoundTripCoversAllOperationsAndRetainedStates() {
        val replacement = result.copy(revision = 8, preparationAllowed = true, brokerClosed = false,
            pin = GmsDozeBrokerKey("fresh", 2), lastBrokerGeneration = 2)
        val states = listOf(initial, enabled, pinned, prepared, off, closed, result, replacement) +
            GmsDozeBrokerOperation.entries.flatMap { operation ->
                val current = prepared.copy(intent = intent.copy(operation = operation))
                listOf(current, current.copy(revision = 4, preparationAllowed = false, revokeVersion = 1),
                    current.copy(revision = 4, intent = null, completedResult = current.intent))
            }
        for (state in states) {
            val encoded = bytes(state)
            assertEquals(state, GmsDozeBrokerCodec.decode(encoded))
            assertTrue(encoded.contentEquals(bytes(GmsDozeBrokerCodec.decode(encoded))))
            GmsDozeBrokerCodec.validate(encoded)
        }
        val longKey = key.copy(nonce = "A".repeat(128))
        val large = prepared.copy(pin = longKey, intent = intent.copy(key = longKey, transactionId = "_".repeat(128)))
        assertEquals(large, GmsDozeBrokerCodec.decode(bytes(large)))
        assertTrue(bytes(large).size < GmsDozeBrokerCodec.MAX_BYTES)
    }

    @Test fun validatedProofKeepsExactBytesIndependentOfCallerAndReturnedArrays() {
        val input = bytes(prepared)
        val expected = input.clone()
        val proof = GmsDozeBrokerCodec.decodeProof(input)
        input.fill(0)
        proof.exactBytes().fill(0)
        assertEquals(prepared, proof.state)
        assertTrue(expected.contentEquals(proof.exactBytes()))
        assertEquals(proof.state, GmsDozeBrokerCodec.decode(proof.exactBytes()))
    }

    @Test fun corruptionAllTruncationsAndOversizedRecordsFailClosed() {
        val encoded = bytes(prepared)
        for (length in encoded.indices) assertFails { GmsDozeBrokerCodec.decode(encoded.copyOf(length)) }
        for (index in encoded.indices) {
            assertFails { GmsDozeBrokerCodec.decode(encoded.clone().also { it[index] = (it[index].toInt() xor 1).toByte() }) }
        }
        assertFails { GmsDozeBrokerCodec.decode(ByteArray(GmsDozeBrokerCodec.MAX_BYTES + 1)) }
    }

    @Test fun unknownMagicSchemaAndTrailingBodyBytesAreRejectedEvenWithValidChecksum() {
        val raw = body(initial)
        rejectBody(raw.clone().also { it[0] = 0 })
        rejectBody(raw.clone().also { it[7] = 2 })
        rejectBody(raw + byteArrayOf(0))
        rejectBody(raw + byteArrayOf(1, 2, 3))
    }

    @Test fun everyBooleanHasOnlyCanonicalZeroAndOneEncoding() {
        val empty = body(initial)
        // pin presence, closed, preparation allowed, intent presence, result presence.
        for (offset in listOf(24, 25, 26, 35, 36)) {
            for (invalid in listOf(2, 127, 255)) rejectBody(empty.clone().also { it[offset] = invalid.toByte() })
        }
        val occupied = body(prepared)
        for (offset in listOf(24, 43, 44, 53, 95)) {
            rejectBody(occupied.clone().also { it[offset] = 2 })
        }
    }

    @Test fun identifierLengthsCharactersAndNonAsciiEncodingsAreRejected() {
        val raw = body(pinned)
        for (length in listOf(-1, 0, 129, Int.MAX_VALUE)) {
            rejectBody(raw.clone().also { ByteBuffer.wrap(it).putInt(25, length) })
        }
        for (value in listOf(0, 32, 47, 46, 128, 192, 255)) {
            rejectBody(raw.clone().also { it[29] = value.toByte() })
        }
        for (bad in listOf("", " ", "broker/other", "é", "\uD800", "a".repeat(129))) {
            assertFails { bytes(pinned.copy(pin = key.copy(nonce = bad))) }
            assertFails { bytes(prepared.copy(intent = intent.copy(transactionId = bad))) }
        }
    }

    @Test fun operationCodesAreFixedAndUnknownCodesFailClosed() {
        val raw = body(prepared)
        for (code in listOf(0, 5, 127, 255)) rejectBody(raw.clone().also { it[78] = code.toByte() })
        for ((operation, code) in listOf(GmsDozeBrokerOperation.LEGACY_ADD to 1,
            GmsDozeBrokerOperation.LEGACY_REMOVE to 2, GmsDozeBrokerOperation.GLOBAL_ADD to 3,
            GmsDozeBrokerOperation.GLOBAL_REMOVE to 4)) {
            assertEquals(code, body(prepared.copy(intent = intent.copy(operation = operation)))[78].toInt())
        }
    }

    @Test fun negativeOverflowedCountersAndInvalidStateCombinationsFailClosed() {
        for (offset in listOf(8, 16, 27)) {
            rejectBody(body(initial).also { ByteBuffer.wrap(it).putLong(offset, -1) })
        }
        // These mutations remain correctly hashed but violate decoded state invariants.
        rejectBody(body(initial).also { it[25] = 0 })
        rejectBody(body(initial).also { it[26] = 1 })
        rejectBody(body(pinned).also { ByteBuffer.wrap(it).putLong(35, 0) })
        rejectBody(body(pinned).also { ByteBuffer.wrap(it).putLong(16, 2) })
        val invalid = listOf(initial.copy(revision = -1), enabled.copy(lastBrokerGeneration = 1),
            enabled.copy(brokerClosed = false), pinned.copy(pin = key.copy(generation = 0)),
            pinned.copy(lastBrokerGeneration = 2), pinned.copy(revokeVersion = 3),
            prepared.copy(intent = intent.copy(key = key.copy(nonce = "other"))),
            prepared.copy(intent = intent.copy(preparationRevision = 0)),
            prepared.copy(intent = intent.copy(preparationRevision = 4)),
            prepared.copy(intent = intent.copy(revokeVersion = 1)), prepared.copy(brokerClosed = true),
            result.copy(completedResult = intent.copy(preparationRevision = 6)),
            result.copy(completedResult = intent.copy(key = key.copy(nonce = "other"))),
            result.copy(completedResult = intent.copy(key = key.copy(generation = 2))),
            prepared.copy(completedResult = intent))
        for (state in invalid) assertFails { bytes(state) }
    }

    @Test fun legalMatrixRetainsIntentThroughOffAckDeathAndMatchingResult() {
        legal(initial, enabled)
        legal(enabled, pinned)
        legal(pinned, prepared)
        legal(prepared, off)
        legal(off, closed)
        legal(closed, result)
        legal(closed, closed.copy(revision = 6)) // Repeated matching close is allowed by the host.
        val death = prepared.copy(revision = 4, brokerClosed = true, preparationAllowed = false)
        legal(prepared, death)
        legal(death, death.copy(revision = 5, intent = null, completedResult = intent))
        legal(prepared, prepared.copy(revision = 4, intent = null, completedResult = intent))
        val reenabling = result.copy(revision = 7, preparationAllowed = true)
        legal(result, reenabling)
        val repin = reenabling.copy(revision = 8, pin = GmsDozeBrokerKey("fresh", 2),
            lastBrokerGeneration = 2, brokerClosed = false)
        legal(reenabling, repin)
        val second = intent.copy(key = repin.pin!!, transactionId = "next", revokeVersion = 1, preparationRevision = 9)
        legal(repin, repin.copy(revision = 9, intent = second))
        assertEquals(intent, GmsDozeBrokerCodec.decode(bytes(repin)).completedResult)
        legal(initial, initial.copy(revision = 1, revokeVersion = 1))
        legal(off, off.copy(revision = 5, revokeVersion = 2))
    }

    @Test fun exactRevisionAndCounterOverflowCannotWrapOrSkip() {
        for (revision in listOf(0L, 1L, 3L, Long.MAX_VALUE)) illegal(enabled, pinned.copy(revision = revision))
        val exhausted = result.copy(revision = Long.MAX_VALUE)
        illegal(exhausted, exhausted)
        illegal(exhausted, exhausted.copy(revision = 0))
        val revokeExhausted = exhausted.copy(revokeVersion = Long.MAX_VALUE)
        illegal(revokeExhausted, revokeExhausted.copy(revokeVersion = 0))
        val generationExhausted = exhausted.copy(lastBrokerGeneration = Long.MAX_VALUE,
            pin = key.copy(generation = Long.MAX_VALUE))
        illegal(generationExhausted, generationExhausted.copy(pin = key.copy(nonce = "fresh", generation = 1),
            lastBrokerGeneration = 1))
    }

    @Test fun enableAndPinCannotBypassClosedOffOrRetainedIntent() {
        illegal(off, off.copy(revision = 5, preparationAllowed = true))
        illegal(closed, closed.copy(revision = 6, preparationAllowed = true))
        illegal(pinned, pinned.copy(revision = 3, preparationAllowed = true))
        illegal(off, off.copy(revision = 5, intent = null, brokerClosed = true, preparationAllowed = true))
        val closedIdle = result.copy(revision = 7, preparationAllowed = true)
        illegal(result, closedIdle.copy(pin = GmsDozeBrokerKey("fresh", 2), lastBrokerGeneration = 2, brokerClosed = false))
        for (newKey in listOf(key, key.copy(generation = 2), GmsDozeBrokerKey("fresh", 3))) {
            illegal(closedIdle, closedIdle.copy(revision = 8, pin = newKey,
                lastBrokerGeneration = newKey.generation, brokerClosed = false))
        }
        illegal(pinned, pinned.copy(revision = 3, pin = GmsDozeBrokerKey("fresh", 2), lastBrokerGeneration = 2))
    }

    @Test fun preparationIdentityAndCompletedResultCannotBeForgedOrReplaced() {
        for (bad in listOf(intent.copy(preparationRevision = 2), intent.copy(key = key.copy(nonce = "other")),
            intent.copy(revokeVersion = 1))) illegal(pinned, prepared.copy(intent = bad))
        illegal(off, off.copy(revision = 5, intent = intent.copy(transactionId = "replacement", preparationRevision = 5)))
        illegal(prepared, prepared.copy(revision = 4, intent = intent.copy(operation = GmsDozeBrokerOperation.GLOBAL_REMOVE)))
        illegal(prepared, prepared.copy(revision = 4, intent = null))
        illegal(prepared, prepared.copy(revision = 4, intent = null, completedResult = intent.copy(transactionId = "fake")))
        illegal(result, result.copy(revision = 7, completedResult = null))
        val onlineResult = prepared.copy(revision = 4, intent = null, completedResult = intent)
        val repeated = intent.copy(preparationRevision = 5)
        illegal(onlineResult, onlineResult.copy(revision = 5, intent = repeated))
    }

    @Test fun disableAndClosureCannotMutateRetainedFieldsOrReopenAuthority() {
        illegal(prepared, off.copy(intent = null))
        illegal(prepared, off.copy(brokerClosed = true)) // OFF persistence and ACK/death are separate.
        illegal(prepared, off.copy(revokeVersion = 0))
        illegal(prepared, off.copy(revokeVersion = 2))
        illegal(off, closed.copy(intent = null))
        illegal(off, closed.copy(revokeVersion = 2))
        illegal(off, closed.copy(pin = key.copy(nonce = "other")))
        illegal(closed, closed.copy(revision = 6, brokerClosed = false))
        illegal(result, result.copy(revision = 7, brokerClosed = false))
        illegal(initial, initial.copy(revision = 1)) // Unpinned no-op is not authenticated closure.
    }
}
