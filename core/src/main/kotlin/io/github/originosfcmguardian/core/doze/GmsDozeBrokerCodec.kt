package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Fixed schema for the single-broker durable port; never reads or migrates global/legacy records.
 * Absence may create only [initialState]. Enabling requires fresh explicit authority in the caller.
 * The checksum detects corruption; it does not authenticate consent, ACK, death or child cleanup.
 * The trusted adapter must validate transitions inside its cross-process CAS lock and authenticate
 * the source of enable/close/result transitions. This codec cannot establish those external facts.
 */
object GmsDozeBrokerCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x47444252
    private const val VERSION = 1
    private const val HASH_BYTES = 32
    private const val MAX_IDENTIFIER_BYTES = 128
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")

    fun initialState() = GmsDozeBrokerState(0, 0, null, true, false, 0, null, null)

    /** Big-endian numbers, byte booleans, length-prefixed ASCII IDs and explicit operation codes. */
    fun encode(state: GmsDozeBrokerState): ByteArray {
        validateState(state)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(state.revision)
            out.writeLong(state.lastBrokerGeneration)
            out.writeBoolean(state.pin != null)
            state.pin?.let { writeKey(out, it) }
            out.writeBoolean(state.brokerClosed)
            out.writeBoolean(state.preparationAllowed)
            out.writeLong(state.revokeVersion)
            writeIntent(out, state.intent)
            writeIntent(out, state.completedResult)
        }
        val body = buffer.toByteArray()
        return (body + digest(body)).also { require(it.size <= MAX_BYTES) { "Broker record oversized" } }
    }

    fun decode(bytes: ByteArray): GmsDozeBrokerState = decodeProof(bytes).state

    /** Snapshot first so the proof's exact bytes and decoded state cannot diverge by caller mutation. */
    fun decodeProof(bytes: ByteArray): GmsDozeBrokerProof {
        require(bytes.size in (HASH_BYTES + 37)..MAX_BYTES) { "Broker record size invalid" }
        val exact = bytes.clone()
        val body = exact.copyOfRange(0, exact.size - HASH_BYTES)
        require(MessageDigest.isEqual(exact.copyOfRange(body.size, exact.size), digest(body))) {
            "Broker checksum invalid"
        }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown broker schema" }
        val state = GmsDozeBrokerState(input.readLong(), input.readLong(),
            if (readBoolean(input)) readKey(input) else null,
            readBoolean(input), readBoolean(input), input.readLong(), readIntent(input), readIntent(input))
        require(input.available() == 0) { "Trailing broker record bytes" }
        validateState(state)
        require(encode(state).contentEquals(exact)) { "Noncanonical broker record" }
        return GmsDozeBrokerProof(exact, state)
    }

    fun validate(bytes: ByteArray) { decodeProof(bytes) }

    /**
     * Only finite host transitions are accepted. null means verified absence, never unknown storage.
     * Shape validation is not evidence that a trusted caller observed ACK/death/complete cleanup.
     */
    fun validateTransition(expected: ByteArray?, replacement: ByteArray) {
        val after = decode(replacement)
        if (expected == null) {
            require(after == initialState()) { "Absent broker record may initialize only OFF" }
            return
        }
        val before = decode(expected)
        require(before.revision < Long.MAX_VALUE && after.revision == before.revision + 1) {
            "Broker revision must advance exactly once"
        }
        val next = before.copy(revision = after.revision)
        val enable = !before.preparationAllowed && before.brokerClosed && before.intent == null &&
            after == next.copy(preparationAllowed = true)
        val pin = before.preparationAllowed && before.brokerClosed && before.intent == null &&
            before.lastBrokerGeneration < Long.MAX_VALUE && after.pin != null &&
            after.pin.generation == before.lastBrokerGeneration + 1 &&
            after.pin.nonce != before.pin?.nonce &&
            after == next.copy(pin = after.pin, lastBrokerGeneration = after.pin.generation, brokerClosed = false)
        val intent = after.intent
        val prepare = before.preparationAllowed && !before.brokerClosed && before.pin != null &&
            before.intent == null && intent != null && intent.key == before.pin &&
            intent.preparationRevision == after.revision && intent.revokeVersion == before.revokeVersion &&
            intent.transactionId != before.completedResult?.transactionId && after == next.copy(intent = intent)
        val disable = before.revokeVersion < Long.MAX_VALUE &&
            after == next.copy(preparationAllowed = false, revokeVersion = before.revokeVersion + 1)
        // Matching ACK is OFF-only; authenticated death also closes an ON broker. Both retain intent.
        val close = before.pin != null &&
            after == next.copy(brokerClosed = true, preparationAllowed = false)
        val result = before.intent != null &&
            after == next.copy(intent = null, completedResult = before.intent)
        require(enable || pin || prepare || disable || close || result) { "Illegal broker transition" }
    }

    private fun validateState(state: GmsDozeBrokerState) {
        require(state.revision >= 0 && state.lastBrokerGeneration in 0..state.revision &&
            state.revokeVersion in 0..state.revision) { "Invalid broker counters" }
        if (state.revision == 0L) require(state == initialState()) { "Initial broker state must be OFF" }
        val pin = state.pin
        if (pin == null) {
            require(state.lastBrokerGeneration == 0L && state.brokerClosed &&
                state.intent == null && state.completedResult == null) { "Unpinned broker state invalid" }
        } else {
            validateKey(pin)
            require(pin.generation == state.lastBrokerGeneration) { "Pin generation mismatch" }
        }
        state.intent?.let { intent ->
            validateIntent(intent, state)
            require(intent.key == pin && intent.preparationRevision <= state.revision) { "Intent pin/revision mismatch" }
            if (state.preparationAllowed) require(!state.brokerClosed && intent.revokeVersion == state.revokeVersion) {
                "Enabled intent is closed or revoked"
            }
        }
        state.completedResult?.let { result ->
            validateIntent(result, state)
            require(result.preparationRevision < state.revision) { "Result must follow its preparation" }
            if (result.key.generation == pin?.generation) require(result.key == pin) { "Result key mismatch" }
            state.intent?.let { intent ->
                require(result.preparationRevision < intent.preparationRevision &&
                    result.transactionId != intent.transactionId) { "Intent reuses completed preparation" }
            }
        }
    }

    private fun validateIntent(intent: GmsDozeBrokerIntent, state: GmsDozeBrokerState) {
        validateKey(intent.key)
        require(identifier.matches(intent.transactionId)) { "Invalid broker transaction identifier" }
        require(intent.key.generation <= state.lastBrokerGeneration &&
            intent.preparationRevision > 0 && intent.key.generation <= intent.preparationRevision &&
            intent.revokeVersion in 0..state.revokeVersion) { "Invalid retained broker preparation" }
    }

    private fun validateKey(key: GmsDozeBrokerKey) {
        require(identifier.matches(key.nonce) && key.generation > 0) { "Invalid broker key" }
    }

    private fun writeKey(out: DataOutputStream, key: GmsDozeBrokerKey) {
        writeIdentifier(out, key.nonce)
        out.writeLong(key.generation)
    }

    private fun readKey(input: DataInputStream) = GmsDozeBrokerKey(readIdentifier(input), input.readLong())

    private fun writeIntent(out: DataOutputStream, intent: GmsDozeBrokerIntent?) {
        out.writeBoolean(intent != null)
        intent ?: return
        writeKey(out, intent.key)
        writeIdentifier(out, intent.transactionId)
        out.writeByte(when (intent.operation) {
            GmsDozeBrokerOperation.LEGACY_ADD -> 1
            GmsDozeBrokerOperation.LEGACY_REMOVE -> 2
            GmsDozeBrokerOperation.GLOBAL_ADD -> 3
            GmsDozeBrokerOperation.GLOBAL_REMOVE -> 4
        })
        out.writeLong(intent.revokeVersion)
        out.writeLong(intent.preparationRevision)
    }

    private fun readIntent(input: DataInputStream): GmsDozeBrokerIntent? {
        if (!readBoolean(input)) return null
        val key = readKey(input)
        val transactionId = readIdentifier(input)
        val operation = when (input.readUnsignedByte()) {
            1 -> GmsDozeBrokerOperation.LEGACY_ADD
            2 -> GmsDozeBrokerOperation.LEGACY_REMOVE
            3 -> GmsDozeBrokerOperation.GLOBAL_ADD
            4 -> GmsDozeBrokerOperation.GLOBAL_REMOVE
            else -> error("Unknown broker operation")
        }
        return GmsDozeBrokerIntent(key, transactionId, operation, input.readLong(), input.readLong())
    }

    private fun writeIdentifier(out: DataOutputStream, value: String) {
        require(identifier.matches(value)) { "Invalid broker identifier" }
        val bytes = value.toByteArray(Charsets.US_ASCII)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readIdentifier(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_IDENTIFIER_BYTES && length <= input.available()) { "Broker identifier length invalid" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        require(bytes.all { it.toInt() in 0..127 }) { "Broker identifier is not ASCII" }
        return String(bytes, Charsets.US_ASCII).also {
            require(identifier.matches(it)) { "Invalid broker identifier" }
        }
    }

    private fun readBoolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("Noncanonical broker boolean")
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
