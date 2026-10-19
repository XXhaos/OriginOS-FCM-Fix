package io.github.originosfcmguardian.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Stable v1 wire order. Absence is explicit for EVERY role; unknown migration reads cannot be null. */
internal enum class DaemonDomainRole(val maximumBytes: Int = 256 * 1024) {
    AUTHORITY, AUTHORITY_INITIALIZED(1), AUTHORITY_STOP_PENDING(1), SELECTION,
    STOPPED_JOURNAL(8192), STOPPED_BLOCKED(1), RECOVERY_COUNTS(1024 * 1024), COUNTS_UNAVAILABLE(1),
    FCM_PROTECTION(512), FCM_PROTECTION_HALT(1), FCM_RECOVERY(32768), FCM_CONSUMED(32768),
    FCM_MCS_CONSUMED(32768), FCM_RECOVERY_HALT(1), FCM_EPISODES(1024 * 1024),
    HEARTBEAT(1024), HEARTBEAT_INITIALIZED(1), HEARTBEAT_HALT(1), HEARTBEAT_PENDING(256),
    LEGACY_BUDGET, LEGACY_BUDGET_INITIALIZED(1), INTERRUPTED_ARTIFACTS,
    HEARTBEAT_DAEMON_OWNER(1024),
    HEARTBEAT_DERIVATION(2048), CONTROL_PROPOSAL(600 * 1024),
}

/** Immutable byte-preserving aggregate; no consent is constructed or inferred by this layer. */
internal class DaemonProtectionRootSnapshot(
    val control: DaemonControlRecord,
    domains: Map<DaemonDomainRole, ByteArray?>,
    receipts: List<DaemonControlReceipt> = emptyList(),
    val halt: DaemonHaltFence? = null,
    val dataRevision: Long = 1,
) {
    private val content = domains.also {
        require(it.keys == DaemonDomainRole.entries.toSet())
        require(it.all { (role, bytes) -> bytes == null || bytes.size <= role.maximumBytes })
        require(it.values.sumOf { bytes -> bytes?.size?.toLong() ?: 0 } <= DaemonProtectionRootCodec.MAX_BYTES - 4096L)
    }.mapValues { it.value?.copyOf() }
    val receipts: List<DaemonControlReceipt> = Collections.unmodifiableList(receipts.also {
        require(it.size <= DaemonProtectionRootCodec.MAX_RECEIPTS)
    }.toList())
    init { require(content.keys == DaemonDomainRole.entries.toSet() && dataRevision > 0) }
    fun bytes(role: DaemonDomainRole): ByteArray? = content[role]?.copyOf()
    fun domains(): Map<DaemonDomainRole, ByteArray?> = content.mapValues { it.value?.copyOf() }
    fun authorityDigest(): String = digest(requireNotNull(content[DaemonDomainRole.AUTHORITY]))
    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal class DaemonRootMutation internal constructor(snapshot: DaemonProtectionRootSnapshot) {
    var control = snapshot.control
    var halt = snapshot.halt
    internal var dataRevision = snapshot.dataRevision
    private val domains = snapshot.domains().toMutableMap()
    private val receipts = snapshot.receipts.toMutableList()
    fun bytes(role: DaemonDomainRole): ByteArray? = domains[role]?.copyOf()
    fun put(role: DaemonDomainRole, bytes: ByteArray?) {
        require(bytes == null || bytes.size <= role.maximumBytes)
        domains[role] = bytes?.copyOf()
    }
    fun remember(receipt: DaemonControlReceipt) {
        val old = receipts.firstOrNull { it.request.owner == receipt.request.owner && it.request.requestId == receipt.request.requestId }
        check(old == null || old == receipt) { "Daemon request id conflict" }
        if (old == null) { receipts += receipt; while (receipts.size > DaemonProtectionRootCodec.MAX_RECEIPTS) receipts.removeAt(0) }
    }
    internal fun freeze() = DaemonProtectionRootSnapshot(control, domains, receipts, halt, dataRevision)
    internal fun restore(snapshot: DaemonProtectionRootSnapshot) {
        control = snapshot.control; halt = snapshot.halt; dataRevision = snapshot.dataRevision
        domains.clear(); domains.putAll(snapshot.domains()); receipts.clear(); receipts.addAll(snapshot.receipts)
    }
}

/** One production CONTROL record; raw per-role shell files are never consulted for execution. */
internal interface DaemonProtectionRootStorage {
    fun read(): ByteArray?
    fun inspect(): ByteArray?
    fun write(bytes: ByteArray)
    fun <T> withOwnerLease(action: () -> T): T
}
internal class ShellDaemonProtectionRootStorage(private val shell: DaemonShellStorage) : DaemonProtectionRootStorage {
    override fun read() = shell.read(DaemonRecordRole.CONTROL)
    override fun inspect() = shell.inspectForReconciliation(DaemonRecordRole.CONTROL)
    override fun write(bytes: ByteArray) = shell.write(DaemonRecordRole.CONTROL, bytes)
    override fun <T> withOwnerLease(action: () -> T) = shell.withOwnerLease(action)
}

/**
 * Single monitor + lifetime lease. Nested domain writes stage into the same aggregate transaction.
 * A lost commit/readback blocks this owner, without consulting cached or older ACTIVE records.
 * Restart reconciliation is the control adapter's job; inspect() never grants execution authority.
 */
internal class DaemonProtectionRoot(private val storage: DaemonProtectionRootStorage) {
    private val monitor = Any()
    private val staged = ThreadLocal<DaemonRootMutation?>()
    private val controlChange = ThreadLocal<Boolean>()
    private var failed = false

    fun initializeImported(snapshot: DaemonProtectionRootSnapshot) = synchronized(monitor) {
        check(!failed)
        val bytes = DaemonProtectionRootCodec.encode(snapshot)
        guarded { storage.withOwnerLease {
            check(storage.read() == null) { "Daemon root already initialized" }
            commit(bytes)
        } }
    }
    /** Production import can only install inert PREPARED data, with final trusted authentication. */
    fun initializePreparedImport(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
        check(snapshot.control.mode == DaemonControlMode.PREPARED && snapshot.control.revision == 1L &&
            snapshot.dataRevision == 1L && snapshot.halt == null && snapshot.receipts.isEmpty())
        // PREPARED cannot dispatch. Cross-service authentication must hold no root/lease monitor.
        check(validateFresh()) { "Import caller authentication failed" }
        initializeImported(snapshot)
    }

    /** Kernel-only fixed CAS: materializes already confirmed bundled authority at the SAME revision. */
    fun deriveBundledHeartbeat(expected: DaemonControlRecord, expectedDataRevision: Long,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample, bootId: String,
        observedAtElapsed: Long, nowElapsed: Long, maximumAgeMillis: Long): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(monitor) {
        check(!failed && staged.get() == null)
        require(maximumAgeMillis in 0..60_000)
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            if (before.control != expected || before.dataRevision != expectedDataRevision) return@withOwnerLease null
            val derived = DaemonHeartbeatDerivation.materialize(before, expected, sample, bootId, observedAtElapsed,
                nowElapsed, maximumAgeMillis) ?: return@withOwnerLease null
            guarded { commit(DaemonProtectionRootCodec.encode(derived.second)) }
            derived.first
        }
    }
    fun snapshot(): DaemonProtectionRootSnapshot = synchronized(monitor) {
        staged.get()?.freeze() ?: guarded { check(!failed); storage.withOwnerLease { readCommitted() } }
    }
    /** Kernel-only fixed continuation CAS; ordinary store writes cannot release this halt. */
    fun renewRevokedHeartbeat(expected: DaemonControlRecord, expectedDataRevision: Long,
        closure: DaemonHeartbeatConfirmationClosure,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample, bootId: String,
        observedAtElapsed: Long, nowElapsed: Long, maximumAgeMillis: Long): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(monitor) {
        check(!failed && staged.get() == null)
        require(maximumAgeMillis in 0..60_000)
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            if (before.control != expected || before.dataRevision != expectedDataRevision) return@withOwnerLease null
            val next = daemonHeartbeatRenewal(before, closure, sample, bootId, observedAtElapsed,
                nowElapsed, maximumAgeMillis) ?: return@withOwnerLease null
            guarded { commit(DaemonProtectionRootCodec.encode(next.second)) }
            next.first
        }
    }
    /** Kernel-only narrow boot-binding CAS; ordinary domain mutations cannot change these fields. */
    fun rebindBundledRecovery(expected: DaemonControlRecord, expectedDataRevision: Long,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample, bootId: String,
        observedAtElapsed: Long, nowElapsed: Long, maximumAgeMillis: Long): Boolean = synchronized(monitor) {
        check(!failed && staged.get() == null)
        require(maximumAgeMillis in 0..60_000)
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            if (before.control != expected || before.dataRevision != expectedDataRevision) return@withOwnerLease false
            val next = DaemonHeartbeatDerivation.rebindBundledRecovery(before, expected, sample, bootId,
                observedAtElapsed, nowElapsed, maximumAgeMillis) ?: return@withOwnerLease false
            if (next !== before) guarded { commit(DaemonProtectionRootCodec.encode(next)) }
            true
        }
    }
    /** Only trusted fresh-caller reconciliation may refresh an EXISTING unused permit at this CAS. */
    fun refreshUnusedHeartbeatPermit(expected: DaemonControlRecord, expectedDataRevision: Long,
        freshCaller: io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample, bootId: String,
        observedAtElapsed: Long, nowElapsed: Long, maximumAgeMillis: Long): Boolean = synchronized(monitor) {
        check(!failed && staged.get() == null)
        require(maximumAgeMillis in 0..60_000)
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            if (before.control != expected || before.dataRevision != expectedDataRevision) return@withOwnerLease false
            val next = DaemonHeartbeatDerivation.refreshUnusedHeartbeatPermit(before, expected, freshCaller,
                sample, bootId, observedAtElapsed, nowElapsed, maximumAgeMillis) ?: return@withOwnerLease false
            if (next !== before) guarded { commit(DaemonProtectionRootCodec.encode(next)) }
            true
        }
    }
    fun authorityDigest(): String = snapshot().authorityDigest()
    /** Exact initial ARM cleanup only; raw import validation finishes before entering this CAS. */
    fun reconcileInitialHeartbeatArm(expected: DaemonControlRecord, expectedDataRevision: Long,
        originalPreparedRaw: ByteArray, importDigest: String): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? {
        val source = DaemonInitialArmReconciliation.capture(expected, originalPreparedRaw, importDigest) ?: return null
        return reconcileInitialHeartbeatArm(expected, expectedDataRevision, source)
    }
    fun reconcileInitialHeartbeatArm(expected: DaemonControlRecord, expectedDataRevision: Long,
        source: DaemonInitialArmSource): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(monitor) {
        check(!failed && staged.get() == null)
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            if (before.control != expected || before.dataRevision != expectedDataRevision) return@withOwnerLease null
            val next = DaemonInitialArmReconciliation.project(before, source) ?: return@withOwnerLease null
            guarded { commit(DaemonProtectionRootCodec.encode(next.second)) }
            next.first
        }
    }
    fun bytes(role: DaemonDomainRole): ByteArray? = snapshot().bytes(role)
    fun inspectForReconciliation(): DaemonProtectionRootSnapshot = synchronized(monitor) {
        DaemonProtectionRootCodec.decode(requireNotNull(storage.inspect()))
    }
    fun <T> transaction(expectedDataRevision: Long? = null, action: (DaemonRootMutation) -> T): T =
        mutate(expectedDataRevision, false, action)
    /** ONLY the concrete control adapter, through the kernel, may use this operation (not engines).
     * Actor callback patches typed codec bytes directly. NEVER call a synchronized high-level
     * domain store here: background store -> root and actor root -> store would invert locks. */
    fun <T> controlTransaction(expectedDataRevision: Long, action: (DaemonRootMutation) -> T): T =
        mutate(expectedDataRevision, true, action)
    private fun <T> mutate(expectedDataRevision: Long?, control: Boolean, action: (DaemonRootMutation) -> T): T = synchronized(monitor) {
        check(!failed)
        val current = staged.get()
        if (current != null) {
            check(!control) { "Control changes require an outer CAS transaction" }
            check(expectedDataRevision == null || current.dataRevision == expectedDataRevision)
            val before = current.freeze()
            try { return@synchronized action(current) }
            catch (failure: Exception) { current.restore(before); throw failure }
        }
        storage.withOwnerLease {
            val before = guarded { readCommitted() }
            check(expectedDataRevision == null || before.dataRevision == expectedDataRevision) { "Stale daemon data revision" }
            val change = DaemonRootMutation(before)
            staged.set(change); controlChange.set(control)
            try {
                val result = action(change)
                if (!control) {
                    check(change.control == before.control && change.halt == before.halt &&
                        change.freeze().receipts == before.receipts &&
                        listOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.AUTHORITY_INITIALIZED,
                            DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.HEARTBEAT_DERIVATION).all { role ->
                            val a = change.bytes(role); val b = before.bytes(role)
                            if (a == null) b == null else b != null && a.contentEquals(b)
                        }) {
                        "Authority/control changes require explicit control transaction"
                    }
                    DaemonDomainConsentGuard.requireUnchanged(before, change.freeze())
                } else if (change.control != before.control) {
                    check(before.control.revision < Long.MAX_VALUE && change.control.owner == before.control.owner &&
                        change.control.revision == before.control.revision + 1)
                }
                if (control && DaemonDomainConsentGuard.protectedConfigurationChanged(before, change.freeze())) {
                    check(before.control.revision < Long.MAX_VALUE && change.control.revision == before.control.revision + 1) {
                        "Protected configuration change requires next kernel control revision"
                    }
                }
                if (control) {
                    val a = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)
                    val b = change.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)
                    val changed = if (a == null) b != null else b == null || !a.contentEquals(b)
                    if (changed) check(before.control.revision < Long.MAX_VALUE &&
                        change.control.revision == before.control.revision + 1) { "Native derivation permit requires next control revision" }
                }
                if (control && change.control == before.control) {
                    // Same-revision control work may only maintain the exact halt fence. Domain
                    // bookkeeping uses transaction(); configuration always takes the next revision.
                    check(change.freeze().receipts == before.receipts && DaemonDomainRole.entries.all { role ->
                        val a = change.bytes(role); val b = before.bytes(role)
                        if (a == null) b == null else b != null && a.contentEquals(b)
                    }) { "Same-revision control transaction is halt bookkeeping only" }
                }
                val bytes = DaemonProtectionRootCodec.encode(change.freeze())
                if (!bytes.contentEquals(DaemonProtectionRootCodec.encode(before))) {
                    change.dataRevision = Math.addExact(before.dataRevision, 1)
                    guarded { commit(DaemonProtectionRootCodec.encode(change.freeze())) }
                }
                result
            } finally { staged.remove(); controlChange.remove() }
        }
    }
    fun put(role: DaemonDomainRole, bytes: ByteArray?) {
        check(role != DaemonDomainRole.AUTHORITY || controlChange.get() == true) { "Authority bytes require control transaction" }
        transaction { it.put(role, bytes) }
    }
    fun inControlTransaction() = controlChange.get() == true
    private fun readCommitted() = DaemonProtectionRootCodec.decode(requireNotNull(storage.read()) { "Daemon root absent" })
    private fun commit(bytes: ByteArray) {
        storage.write(bytes)
        check(storage.read()?.contentEquals(bytes) == true) { "Daemon root readback mismatch" }
    }
    private inline fun <T> guarded(action: () -> T): T = try { action() }
        catch (failure: Exception) { failed = true; throw failure }
}

/** Canonical binary + SHA-256, bounded before allocation. Receipts contain metadata, never domain copies. */
internal object DaemonProtectionRootCodec {
    const val MAX_RECEIPTS = 64
    val MAX_BYTES = DaemonRecordRole.CONTROL.maximumBytes // Current secure CONTROL cap: 2 MiB.
    private const val MAGIC = 0x47445231
    fun encode(snapshot: DaemonProtectionRootSnapshot): ByteArray {
        validate(snapshot)
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeLong(snapshot.dataRevision); out.record(snapshot.control)
            out.writeBoolean(snapshot.halt != null)
            snapshot.halt?.let { out.request(it.request); out.writeLong(it.nextRevision) }
            out.writeInt(snapshot.receipts.size)
            for (receipt in snapshot.receipts) { out.request(receipt.request); out.record(receipt.committed) }
            out.writeInt(DaemonDomainRole.entries.size)
            for (role in DaemonDomainRole.entries) {
                val bytes = snapshot.bytes(role)
                out.writeInt(role.ordinal); out.writeInt(bytes?.size ?: -1); if (bytes != null) out.write(bytes)
            }
        } }.toByteArray()
        require(body.size + 32 <= MAX_BYTES)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    fun decode(bytes: ByteArray): DaemonProtectionRootSnapshot {
        require(bytes.size in 40..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val dataRevision = input.readLong().also { require(it > 0) }; val record = input.record()
            val halt = if (input.flag()) DaemonHaltFence(input.request(), input.readLong()) else null
            val count = input.readInt().also { require(it in 0..MAX_RECEIPTS) }
            val receipts = List(count) { DaemonControlReceipt(input.request(), input.record()) }
            require(input.readInt() == DaemonDomainRole.entries.size)
            val domains = DaemonDomainRole.entries.associateWith { role ->
                require(input.readInt() == role.ordinal)
                val size = input.readInt().also { require(it in -1..role.maximumBytes && it <= input.available()) }
                if (size == -1) null else ByteArray(size).also(input::readFully)
            }
            require(input.available() == 0)
            DaemonProtectionRootSnapshot(record, domains, receipts, halt, dataRevision).also(::validate)
        }
    }
    private fun validate(value: DaemonProtectionRootSnapshot) {
        require(value.authorityDigest() == value.control.authorityDigest) { "Control/authority digest mismatch" }
        for ((role, bytes) in value.domains()) require(bytes == null || bytes.size <= role.maximumBytes)
        require(value.receipts.size <= MAX_RECEIPTS)
        val ids = HashSet<String>()
        var lastRevision = 0L
        for (receipt in value.receipts) {
            validateRequest(receipt.request)
            require(receipt.request.owner == value.control.owner && receipt.committed.owner == value.control.owner)
            require(ids.add(receipt.request.requestId))
            require(receipt.request.expectedRevision < Long.MAX_VALUE &&
                receipt.request.expectedRevision + 1 == receipt.committed.revision &&
                receipt.committed.revision > lastRevision && receipt.committed.revision <= value.control.revision)
            lastRevision = receipt.committed.revision
        }
        value.halt?.let { validateRequest(it.request); require(it.request.owner == value.control.owner &&
            it.request.expectedRevision == value.control.revision && value.control.revision < Long.MAX_VALUE &&
            it.nextRevision == value.control.revision + 1) }
        require(value.domains().values.sumOf { it?.size?.toLong() ?: 0 } <= MAX_BYTES - 4096L)
    }
    private fun validateRequest(request: DaemonControlRequest) {
        (request.intent as? DaemonControlIntent.Prepare)?.let {
            require(it.migrationId.isNotBlank() && it.migrationId.length <= 128 && it.authorityDigest.matches(Regex("[a-f0-9]{64}")))
        }
    }
    private fun DataOutputStream.record(record: DaemonControlRecord) {
        writeUTF(record.owner.installInstance); writeLong(record.owner.ownerEpoch); writeLong(record.revision)
        writeUTF(record.authorityDigest); writeInt(record.mode.ordinal); writeUTF(record.migrationId)
    }
    private fun DataInputStream.record() = DaemonControlRecord(DaemonOwnerKey(text(), readLong()), readLong(),
        text(), DaemonControlMode.entries.getOrNull(readInt()) ?: error("Unknown mode"), text())
    private fun DataOutputStream.request(request: DaemonControlRequest) {
        writeUTF(request.requestId); writeUTF(request.owner.installInstance); writeLong(request.owner.ownerEpoch); writeLong(request.expectedRevision)
        when (val intent = request.intent) {
            is DaemonControlIntent.Prepare -> { writeInt(0); writeUTF(intent.migrationId); writeUTF(intent.authorityDigest) }
            is DaemonControlIntent.Activate -> { writeInt(1); writeUTF(intent.pointer.commitId); record(intent.pointer.record) }
            is DaemonControlIntent.SetAuthority -> { writeInt(2); writeUTF(intent.pointer.commitId); record(intent.pointer.record) }
        }
    }
    private fun DataInputStream.request(): DaemonControlRequest {
        val id = text(); val owner = DaemonOwnerKey(text(), readLong()); val revision = readLong()
        val intent = when (readInt()) {
            0 -> DaemonControlIntent.Prepare(text(), text())
            1 -> DaemonControlIntent.Activate(DaemonPointerCommitProof(text(), record()))
            2 -> DaemonControlIntent.SetAuthority(DaemonPointerCommitProof(text(), record()))
            else -> error("Unknown control intent")
        }
        return DaemonControlRequest(id, owner, revision, intent)
    }
    private fun DataInputStream.text(): String = readUTF().also { require(it.isNotBlank() && it.length <= 128) }
    private fun DataInputStream.flag() = readUnsignedByte().also { require(it in 0..1) } == 1
}
