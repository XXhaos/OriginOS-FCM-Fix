package io.github.originosfcmguardian.daemon

import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Host-only boundary. Neither caller evidence nor these callbacks are decoded from App input. */
internal interface DaemonImportPort {
    /** Fresh Binder principal, exact installation and current privilege validation. Unknown is false. */
    fun authenticateFresh(caller: DaemonCallerEvidence): Boolean

    /**
     * Delegate to root.initializePreparedImport(snapshot, validateFresh). All fresh authentication
     * IO must run outside staging, root, kernel and port locks.
     * This operation only installs PREPARED data; it must not activate or start an engine.
     */
    fun initializeImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean)

    /** Read-only exact PREPARED recheck after a completed upload; never initialize a second time. */
    fun verifyImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
        error("Import reverification unavailable")
    }
}

/** A staged proposal is retained, but no native proof, receipt, halt or changed domain is reusable. */
internal object DaemonPreparedImportVerifier {
    fun matches(existing: DaemonProtectionRootSnapshot, expected: DaemonProtectionRootSnapshot): Boolean {
        fun initial(value: DaemonProtectionRootSnapshot) = value.control.mode == DaemonControlMode.PREPARED &&
            value.control.revision == 1L && value.halt == null && value.receipts.isEmpty() &&
            value.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER) == null &&
            value.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION) == null
        if (!initial(existing) || !initial(expected) || existing.control != expected.control) return false
        val proposal = existing.bytes(DaemonDomainRole.CONTROL_PROPOSAL)
        if (proposal != null && !runCatching {
            val plan = DaemonControlProposalCodec.decode(proposal)
            plan.transition == DaemonControlTransition.ACTIVATE && plan.expected == existing.control &&
                plan.next == existing.control.copy(revision = 2, mode = DaemonControlMode.ACTIVE) && plan.patch().isEmpty() &&
                plan.nativeHeartbeatDerivation()?.let {
                    val permit = DaemonHeartbeatDerivationPermitCodec.decode(it)
                    permit.record == plan.next && !permit.used &&
                        permit.baselineDigest == DaemonHeartbeatDerivation.baselineDigest(expected)
                } != false
        }.getOrDefault(false)) return false
        return DaemonDomainRole.entries.filter { it != DaemonDomainRole.CONTROL_PROPOSAL }.all { role ->
            val a = existing.bytes(role); val b = expected.bytes(role)
            if (a == null) b == null else b != null && a.contentEquals(b)
        }
    }
}

/**
 * One bounded upload per host instance. Chunks have no storage or execution effect. Finish burns
 * the upload before decoding/IO, so a failed or ambiguous initialization cannot replay it. Successful
 * completion permits only a read-only exact PREPARED recheck. The host captures/authenticates caller
 * evidence outside this object's monitor on EVERY Binder entry.
 */
internal class DaemonImportProtocol(
    private val owner: DaemonOwnerKey,
    private val migrationId: String,
    private val port: DaemonImportPort,
) {
    private enum class State { OPEN, FINISHING, COMPLETE, FAILED }
    private class Upload(val id: String, val caller: DaemonCallerEvidence,
        val digest: String, val size: Int, var bytes: ByteArray? = ByteArray(size), var offset: Int = 0,
        var state: State = State.OPEN, var prepared: DaemonProtectionRootSnapshot? = null)
    private val lock = Any()
    private var upload: Upload? = null

    init { require(migrationId.isNotBlank() && migrationId.length <= 128) }

    fun beginImport(caller: DaemonCallerEvidence, size: Int, sha256: String): String {
        check(port.authenticateFresh(caller)) { "Import caller authentication failed" }
        require(size in 40..DaemonProtectionRootCodec.MAX_BYTES)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        // SelectedTargetIdentity's signer list may originate in a mutable system-read collection.
        val identity = caller.installation.identity.copy(signerSha256 =
            Collections.unmodifiableList(caller.installation.identity.signerSha256.toList()))
        val captured = caller.copy(installation = caller.installation.copy(identity = identity))
        return synchronized(lock) {
            upload?.let { current ->
                check(current.caller == captured && current.digest == sha256 && current.size == size &&
                    current.state in setOf(State.OPEN, State.COMPLETE)) { "Import already begun" }
                return@synchronized current.id
            }
            val next = Upload(UUID.randomUUID().toString(), captured, sha256, size)
            upload = next
            next.id
        }
    }

    fun importOffset(caller: DaemonCallerEvidence, sessionId: String): Int {
        check(port.authenticateFresh(caller)) { "Import caller authentication failed" }
        return synchronized(lock) {
            val current = checkedUpload(caller, sessionId)
            check(current.state in setOf(State.OPEN, State.COMPLETE)) { "Import already consumed" }
            current.offset
        }
    }

    fun appendImport(caller: DaemonCallerEvidence, sessionId: String,
        offset: Int, chunk: ByteArray): Int {
        check(port.authenticateFresh(caller)) { "Import caller authentication failed" }
        return synchronized(lock) {
            val current = checkedUpload(caller, sessionId)
            check(current.state == State.OPEN) { "Import already consumed" }
            require(chunk.size in 1..MAX_CHUNK_BYTES)
            require(offset == current.offset && chunk.size <= current.size - current.offset)
            chunk.copyInto(checkNotNull(current.bytes), current.offset)
            current.offset += chunk.size
            current.offset
        }
    }

    fun finishImport(caller: DaemonCallerEvidence, sessionId: String): DaemonControlRecord {
        // Authentication precedes checksum, allocation and decoding of the complete upload.
        check(port.authenticateFresh(caller)) { "Import caller authentication failed" }
        val (current, reverify) = synchronized(lock) {
            val current = checkedUpload(caller, sessionId)
            check(current.state in setOf(State.OPEN, State.COMPLETE)) { "Import already consumed" }
            check(current.offset == current.size) { "Incomplete import" }
            val completed = current.state == State.COMPLETE
            current.state = State.FINISHING
            current to completed
        }
        try {
            if (reverify) {
                val prepared = checkNotNull(current.prepared)
                var validated = false
                port.verifyImported(prepared) { port.authenticateFresh(current.caller).also { validated = it } }
                check(validated) { "Import port omitted final authentication" }
                synchronized(lock) { current.state = State.COMPLETE }
                return prepared.control
            }
            val bytes = checkNotNull(current.bytes)
            require(DaemonProtectionRootSnapshot.digest(bytes) == current.digest) { "Import checksum mismatch" }
            val decoded = DaemonProtectionRootCodec.decode(bytes)
            require(MessageDigest.isEqual(bytes, DaemonProtectionRootCodec.encode(decoded))) { "Noncanonical import" }
            require(decoded.control.owner == owner && decoded.control.migrationId == migrationId)
            require(decoded.control.mode == DaemonControlMode.PREPARED && decoded.control.revision == 1L &&
                decoded.dataRevision == 1L && decoded.receipts.isEmpty() && decoded.halt == null) { "Import must be initial PREPARED data" }
            // App-owned legacy/native proof is never transferable into this daemon incarnation.
            val domains = decoded.domains().toMutableMap()
            domains[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER] = null
            domains[DaemonDomainRole.HEARTBEAT_DERIVATION] = null
            domains[DaemonDomainRole.CONTROL_PROPOSAL] = null
            val prepared = DaemonProtectionRootSnapshot(decoded.control, domains)
            var validated = false
            port.initializeImported(prepared) {
                port.authenticateFresh(current.caller).also { validated = it }
            }
            check(validated) { "Import port omitted final authentication" }
            synchronized(lock) { current.prepared = prepared; current.state = State.COMPLETE }
            return prepared.control
        } catch (failure: Exception) {
            synchronized(lock) { current.state = State.FAILED }
            throw failure
        } finally {
            current.bytes?.fill(0)
            current.bytes = null
        }
    }

    private fun checkedUpload(caller: DaemonCallerEvidence, sessionId: String): Upload {
        val current = checkNotNull(upload) { "No import upload" }
        check(current.id == sessionId && current.caller == caller) { "Import session owner mismatch" }
        return current
    }

    companion object { const val MAX_CHUNK_BYTES = 64 * 1024 }
}
