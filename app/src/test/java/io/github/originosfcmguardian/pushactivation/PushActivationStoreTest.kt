package io.github.originosfcmguardian.pushactivation

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class PushActivationStoreTest {
    private val installation = PushActivationInstallation(10_123, 100, 100, 200, listOf("a".repeat(64)))
    private val firstId = "bf6f66f8-a14b-42fa-8540-67b00425e89f"
    private val secondId = "035a00f7-1359-4ce6-9b92-5bf6f66fc661"

    private class Memory : PushActivationStorage {
        var bytes: ByteArray? = null
        var unreadable = false
        var ambiguousWrite = false
        var loseWrite = false
        var writes = 0
        override fun read(): PushActivationSnapshot? {
            check(!unreadable)
            return bytes?.let(PushActivationCodec::decode)
        }
        override fun write(value: PushActivationSnapshot) {
            writes++
            if (!loseWrite) bytes = PushActivationCodec.encode(value)
            check(!ambiguousWrite)
        }
        override fun <T> exclusive(action: () -> T) = synchronized(this) { action() }
    }

    private fun pending(generation: Long = 1) = PushActivationSnapshot(generation, firstId, installation, 300,
        PushActivationMode.PENDING)

    @Test fun allStatesRoundTripWithoutGrantingAPendingDispatchAfterRead() {
        for ((mode, reason) in listOf(
            PushActivationMode.PENDING to PushActivationReason.NONE,
            PushActivationMode.DISPATCHING to PushActivationReason.NONE,
            PushActivationMode.BLOCKED to PushActivationReason.UNKNOWN_RESULT,
            PushActivationMode.COMPLETE to PushActivationReason.NONE,
            PushActivationMode.RETIRED to PushActivationReason.CANCELLED,
        )) {
            val value = pending().copy(mode = mode, reason = reason)
            assertEquals(value, PushActivationCodec.decode(PushActivationCodec.encode(value)))
        }
        val memory = Memory().also { it.bytes = PushActivationCodec.encode(pending().copy(mode = PushActivationMode.DISPATCHING)) }
        val store = PushActivationStore(memory)
        repeat(10) { assertEquals(PushActivationMode.DISPATCHING, store.read()?.mode) }
        assertEquals(0, memory.writes)
    }

    @Test fun installationSnapshotOwnsItsSignerListAndRejectsAmbiguousIdentities() {
        val signers = mutableListOf("a".repeat(64))
        val value = PushActivationInstallation(10_123, 100, 100, 200, signers)
        signers[0] = "b".repeat(64)
        assertEquals(listOf("a".repeat(64)), value.signerSha256)
        assertThrows(UnsupportedOperationException::class.java) { (value.signerSha256 as MutableList<String>).clear() }
        assertThrows(IllegalArgumentException::class.java) { PushActivationInstallation(100_123, 100, 100, 200, signers) }
        assertThrows(IllegalArgumentException::class.java) { PushActivationInstallation(10_123, 100, 100, 99, signers) }
        assertThrows(IllegalArgumentException::class.java) {
            PushActivationInstallation(10_123, 100, 100, 200, listOf("b".repeat(64), "a".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushActivationInstallation(10_123, 100, 100, 200, List(2) { "a".repeat(64) })
        }
    }

    @Test fun offBeforeFirstConfirmationIsADurableNegativeRecord() {
        val memory = Memory(); val store = PushActivationStore(memory)
        assertNull(store.read())
        val stopped = store.retire()
        assertEquals(PushActivationMode.RETIRED, stopped.mode)
        assertEquals(1L, stopped.generation)
        assertNull(stopped.installation)
        assertNull(stopped.ticketId)
        assertEquals(0L, stopped.confirmedAtEpochMillis)
        assertEquals(stopped, PushActivationStore(memory).read())
    }

    @Test fun offOrReplacementWinsAgainstLateDispatchAndReadbackCallbacks() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val accepted = store.begin(installation, firstId, 300)
        val dispatching = requireNotNull(store.transition(accepted, PushActivationMode.DISPATCHING))
        val stopped = store.retire()
        assertNull(store.transition(dispatching, PushActivationMode.COMPLETE))
        assertNull(store.transition(dispatching, PushActivationMode.PENDING))
        assertEquals(stopped, store.read())
        val replacement = store.begin(installation, secondId, 400)
        assertEquals(stopped.generation + 1, replacement.generation)
        assertNull(store.transition(accepted, PushActivationMode.DISPATCHING))
        assertNull(store.transition(dispatching, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT))
        assertEquals(replacement, store.read())
    }

    @Test fun uncertainDispatchIsStickyUntilANewExplicitConfirmationOrOff() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val accepted = store.begin(installation, firstId, 300)
        val dispatching = requireNotNull(store.transition(accepted, PushActivationMode.DISPATCHING))
        val blocked = requireNotNull(store.transition(dispatching, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT))
        val writes = memory.writes
        assertNull(store.transition(blocked, PushActivationMode.PENDING))
        assertNull(store.transition(blocked, PushActivationMode.DISPATCHING))
        assertEquals(writes, memory.writes)
        assertEquals(blocked, PushActivationStore(memory).read())
        assertEquals(PushActivationMode.PENDING, store.begin(installation, secondId, 400).mode)
    }

    @Test fun onlyAnExactPendingAttemptCanReturnToWaiting() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val accepted = store.begin(installation, firstId, 300)
        assertNull(store.transition(accepted, PushActivationMode.PENDING))
        val dispatching = requireNotNull(store.transition(accepted, PushActivationMode.DISPATCHING))
        assertNull(store.transition(accepted, PushActivationMode.COMPLETE))
        val waiting = requireNotNull(store.transition(dispatching, PushActivationMode.PENDING))
        assertEquals(dispatching.generation + 1, waiting.generation)
        assertEquals(firstId, waiting.ticketId)
        assertEquals(installation, waiting.installation)
        assertEquals(300L, waiting.confirmedAtEpochMillis)
    }

    @Test fun blockedAttemptCanSettleExistingConfigurationWithoutOpeningDispatch() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val accepted = store.begin(installation, firstId, 300)
        val dispatching = requireNotNull(store.transition(accepted, PushActivationMode.DISPATCHING))
        val blocked = requireNotNull(store.transition(dispatching, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT))
        val completed = requireNotNull(store.transition(blocked, PushActivationMode.COMPLETE))
        assertEquals(PushActivationMode.COMPLETE, completed.mode)
        assertEquals(PushActivationReason.NONE, completed.reason)
        assertNull(store.transition(completed, PushActivationMode.PENDING))
        assertNull(store.transition(completed, PushActivationMode.DISPATCHING))
    }

    @Test fun reusedRequestIdCannotResetAnExistingTicket() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val accepted = store.begin(installation, firstId, 300)
        assertThrows(IllegalStateException::class.java) { store.begin(installation, firstId, 400) }
        assertEquals(accepted, store.read())
        store.retire()
        assertThrows(IllegalStateException::class.java) { store.begin(installation, firstId, 500) }
    }

    @Test fun unknownReadNeverBecomesMissingAndAmbiguousWriteNeverBecomesPermissionToRetry() {
        val memory = Memory(); val store = PushActivationStore(memory)
        memory.unreadable = true
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertThrows(IllegalStateException::class.java) { store.begin(installation, firstId, 300) }
        assertEquals(0, memory.writes)
        memory.unreadable = false
        val accepted = store.begin(installation, firstId, 300)
        memory.ambiguousWrite = true
        assertThrows(IllegalStateException::class.java) { store.transition(accepted, PushActivationMode.DISPATCHING) }
        memory.ambiguousWrite = false
        assertEquals(PushActivationMode.DISPATCHING, store.read()?.mode)
        assertNull(store.transition(accepted, PushActivationMode.DISPATCHING))
    }

    @Test fun independentReadbackIsMandatoryBeforeReturningAnAcknowledgement() {
        val memory = Memory(); val store = PushActivationStore(memory)
        memory.loseWrite = true
        assertThrows(IllegalStateException::class.java) { store.begin(installation, firstId, 300) }
        assertNull(store.read())
        memory.loseWrite = false
        val accepted = store.begin(installation, firstId, 300)
        memory.loseWrite = true
        assertThrows(IllegalStateException::class.java) { store.retire() }
        assertEquals(accepted, store.read())
    }

    @Test fun codecRejectsChangedChecksumOversizeUnknownVersionAndTrailingPayload() {
        val encoded = PushActivationCodec.encode(pending())
        val changed = encoded.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { PushActivationCodec.decode(changed) }
        assertThrows(IllegalArgumentException::class.java) { PushActivationCodec.decode(ByteArray(PushActivationCodec.MAX_BYTES + 1)) }
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        val nextVersion = payload.copyOf().also { it[7] = 3 }
        assertThrows(IllegalArgumentException::class.java) { PushActivationCodec.decode(sealed(nextVersion)) }
        assertThrows(IllegalArgumentException::class.java) { PushActivationCodec.decode(sealed(payload + byteArrayOf(0))) }
        val invalidMode = payload.copyOf().also { it[16] = 127 }
        assertThrows(IllegalStateException::class.java) { PushActivationCodec.decode(sealed(invalidMode)) }
        val invalidPresence = payload.copyOf().also { it[26] = 2 }
        assertThrows(IllegalArgumentException::class.java) { PushActivationCodec.decode(sealed(invalidPresence)) }
    }

    @Test fun malformedTicketOrModeCannotBeSavedAsAnImplicitGrant() {
        assertThrows(IllegalArgumentException::class.java) { pending().copy(ticketId = firstId.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { pending().copy(ticketId = null, installation = null) }
        assertThrows(IllegalArgumentException::class.java) { pending().copy(mode = PushActivationMode.BLOCKED) }
        assertThrows(IllegalArgumentException::class.java) { pending().copy(reason = PushActivationReason.UNKNOWN_RESULT) }
        val largest = PushActivationInstallation(99_999, 100, Long.MAX_VALUE, Long.MAX_VALUE,
            (0 until 16).map { it.toString(16).padStart(64, '0') })
        assertTrue(PushActivationCodec.encode(pending().copy(installation = largest)).size <= PushActivationCodec.MAX_BYTES)
    }

    @Test fun generationNeverWrapsAndFailedOverflowDoesNotChangeDurableState() {
        val memory = Memory().also { it.bytes = PushActivationCodec.encode(pending(Long.MAX_VALUE)) }
        val store = PushActivationStore(memory)
        assertThrows(ArithmeticException::class.java) { store.retire() }
        assertThrows(ArithmeticException::class.java) { store.begin(installation, UUID.randomUUID().toString(), 400) }
        assertEquals(Long.MAX_VALUE, store.read()?.generation)
        assertEquals(0, memory.writes)
    }

    @Test fun versionOneRecordRetainsUnknownHistoryAndReadsWithoutGrantingReplay() {
        val value = pending().copy(mode = PushActivationMode.BLOCKED, reason = PushActivationReason.UNKNOWN_RESULT)
        val encoded = PushActivationCodec.encode(value)
        // V2 appends 4-byte count, 4-byte result and a one-byte diagnostic presence flag.
        val oldPayload = encoded.copyOfRange(0, encoded.size - 32 - 9).also { it[7] = 1 }
        val old = PushActivationCodec.decode(sealed(oldPayload))
        assertEquals(value, old)
        assertNull(old.recordedDispatches); assertNull(old.lastAttempt); assertNull(old.lastDispatch)
        val memory = Memory().also { it.bytes = sealed(oldPayload) }
        assertNull(PushActivationStore(memory).transition(old, PushActivationMode.PENDING))
        assertEquals(0, memory.writes)
    }

    @Test fun exactAttemptEvidencePersistsAcrossReadsButNeverOverwritesOffOrReplacement() {
        val memory = Memory(); val store = PushActivationStore(memory)
        val first = store.begin(installation, firstId, 300)
        assertEquals(0, first.recordedDispatches)
        val dispatching = requireNotNull(store.transition(first, PushActivationMode.DISPATCHING))
        val diagnostic = PushActivationAttemptDiagnostic("SAFE_RETRY", "PREPARATION", "READ_UNKNOWN", "BRIDGE_UNAVAILABLE")
        val result = requireNotNull(store.recordAttempt(dispatching, PushActivationDispatch.SAFE_RETRY, diagnostic))
        assertEquals(1, result.recordedDispatches)
        assertEquals(diagnostic, PushActivationStore(memory).read()?.lastAttempt)
        assertEquals(PushActivationDispatch.SAFE_RETRY, store.read()?.lastDispatch)
        val waiting = requireNotNull(store.transition(result, PushActivationMode.PENDING))
        val second = requireNotNull(store.transition(waiting, PushActivationMode.DISPATCHING))
        assertEquals(2, second.recordedDispatches); assertNull(second.lastAttempt); assertNull(second.lastDispatch)
        store.retire()
        assertNull(store.recordAttempt(second, PushActivationDispatch.CONFIGURED, null))
        store.begin(installation, secondId, 400)
        assertNull(store.recordAttempt(second, PushActivationDispatch.CONFIGURED, null))
        assertEquals(0, store.read()?.recordedDispatches)
    }

    private fun sealed(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
}
