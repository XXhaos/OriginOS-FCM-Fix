package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class DaemonMigrationIntent(val owner: DaemonOwnerKey, val migrationId: String) {
    init {
        require(uuid4(owner.installInstance) && uuid4(migrationId))
    }
}

internal data class DaemonRemotePointer(val intent: DaemonMigrationIntent,
    val committed: DaemonPointerCommitProof, val importDigest: String) {
    init {
        require(committed.record.owner == intent.owner && committed.record.migrationId == intent.migrationId)
        require(importDigest.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Durable negative intent only. This cannot contain ON, a raw plan, or replacement consent. */
internal data class DaemonAppStopIntent(val id: String, val intent: DaemonMigrationIntent,
    val command: DaemonAppCommand) {
    init {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        require(command.disabling)
        require((command.kind == DaemonWire.TARGET_OFF) == (command.target != null))
    }
}

internal enum class DaemonMigrationFile { INTENT, POINTER, PENDING, STOP }
internal interface DaemonMigrationFilePort {
    /** Unknown, oversize, interrupted .bak/.new or unreadable storage throws; absence alone is null. */
    fun read(file: DaemonMigrationFile): ByteArray?
    /** Durable atomic write, parent directory sync and exact independent readback are required. */
    fun write(file: DaemonMigrationFile, bytes: ByteArray)
    fun remove(file: DaemonMigrationFile)
    fun <T> exclusive(action: () -> T): T
}

internal sealed interface DaemonMigrationRead {
    /** The ONLY state in which the old App-owned executor may be used. */
    data object Local : DaemonMigrationRead
    data class Migrating(val intent: DaemonMigrationIntent) : DaemonMigrationRead
    data class Remote(val pointer: DaemonRemotePointer) : DaemonMigrationRead
    data object Blocked : DaemonMigrationRead
}

/** Diagnostic recovery material only. Even an intact Pending candidate grants no execution. */
internal sealed interface DaemonMigrationRecoveryRead {
    data class Pending(val intent: DaemonMigrationIntent, val pointer: DaemonRemotePointer?,
        val candidate: DaemonRemotePointer, val intentDigest: String, val pointerDigest: String?,
        val pendingDigest: String) : DaemonMigrationRecoveryRead
    data object NotPending : DaemonMigrationRecoveryRead
    data object Blocked : DaemonMigrationRecoveryRead
}

/**
 * Permanent App-private migration barrier. No marker or failed pointer write can fall back to the
 * old authority. PENDING is written before pointer replacement, so AtomicFile rollback cannot
 * silently resurrect an older ACTIVE pointer after a failed OFF/configuration commit.
 */
internal class DaemonMigrationPointerStore(private val files: DaemonMigrationFilePort) {
    fun read(): DaemonMigrationRead = try { files.exclusive { readStrict() } }
        catch (_: Exception) { DaemonMigrationRead.Blocked }
    fun localExecutionAllowed(): Boolean = read() == DaemonMigrationRead.Local

    /** Unknown storage is an error, never equivalent to an absent OFF request. */
    fun readStopIntent(): DaemonAppStopIntent? = files.exclusive {
        inspectRecoveryStrict() // Validate all artifacts, including any exact interrupted pointer.
        files.read(DaemonMigrationFile.STOP)?.let(DaemonMigrationPointerCodec::decodeStopIntent)
    }

    /** Persist BEFORE attempting a disabling RPC. One exact pending OFF may be retried idempotently. */
    fun saveStopIntent(value: DaemonAppStopIntent): Boolean = try { files.exclusive {
        val before = readStrict()
        val intent = when (before) {
            is DaemonMigrationRead.Remote -> before.pointer.intent
            is DaemonMigrationRead.Migrating -> before.intent
            else -> error("Durable migration intent required for OFF")
        }
        check(intent == value.intent)
        val bytes = DaemonMigrationPointerCodec.encodeStopIntent(value)
        val current = files.read(DaemonMigrationFile.STOP)
        if (current != null) {
            check(current.contentEquals(bytes)) { "A different OFF intent is already pending" }
        } else files.write(DaemonMigrationFile.STOP, bytes)
        check(files.read(DaemonMigrationFile.STOP)?.contentEquals(bytes) == true)
        check(readStrict() == before)
        true
    } } catch (_: Exception) { false }

    /** Clear only the exact request whose disabling transition has been acknowledged/read back. */
    fun clearStopIntent(expected: DaemonAppStopIntent): Boolean = try { files.exclusive {
        val remote = readStrict() as? DaemonMigrationRead.Remote ?: error("Remote pointer required for OFF clear")
        check(remote.pointer.intent == expected.intent)
        val bytes = DaemonMigrationPointerCodec.encodeStopIntent(expected)
        check(files.read(DaemonMigrationFile.STOP)?.contentEquals(bytes) == true) { "OFF intent changed or absent" }
        files.remove(DaemonMigrationFile.STOP)
        check(files.read(DaemonMigrationFile.STOP) == null)
        check(readStrict() == remote)
        true
    } } catch (_: Exception) { false }

    /**
     * Expose only validated exact artifacts for asking the host to resume its proposal or reconcile
     * its current record. This never clears PENDING and never selects the older pointer as a fallback.
     */
    fun inspectForRecovery(): DaemonMigrationRecoveryRead = try { files.exclusive { inspectRecoveryStrict() } }
        catch (_: Exception) { DaemonMigrationRecoveryRead.Blocked }

    /**
     * The caller obtains a FRESH host challenge after resumeStaged/current-pointer reconciliation.
     * The host registry authenticates that nonce when it receives the returned attestation; this
     * App-private store cannot authenticate the host itself. Only the exact staged candidate record
     * can be committed, with its original intent/import digest and the newly issued challenge nonce.
     */
    fun recoverPending(expected: DaemonMigrationRecoveryRead.Pending,
        challenge: DaemonPointerChallenge): DaemonAppPointerAttestation? = try {
        val value = files.exclusive {
            val current = inspectRecoveryStrict() as? DaemonMigrationRecoveryRead.Pending
                ?: error("No intact pending pointer")
            check(current == expected) { "Pending pointer artifacts changed" }
            check(challenge.pointer.record == current.candidate.committed.record &&
                challenge.nonce == challenge.pointer.commitId &&
                challenge.pointer.commitId != current.candidate.committed.commitId &&
                challenge.pointer.commitId != current.pointer?.committed?.commitId) { "Fresh exact host challenge required" }
            val replacement = current.candidate.copy(committed = challenge.pointer)
            persistPointer(replacement)
            replacement
        }
        if (read() == DaemonMigrationRead.Remote(value)) DaemonAppPointerAttestation(challenge, challenge.pointer) else null
    } catch (_: Exception) { null }

    fun begin(intent: DaemonMigrationIntent): Boolean = try { files.exclusive {
        when (val before = readStrict()) {
            DaemonMigrationRead.Local -> {
                val bytes = DaemonMigrationPointerCodec.encodeIntent(intent)
                files.write(DaemonMigrationFile.INTENT, bytes)
                check(files.read(DaemonMigrationFile.INTENT)?.contentEquals(bytes) == true)
                check(readStrict() == DaemonMigrationRead.Migrating(intent))
                true
            }
            is DaemonMigrationRead.Migrating -> before.intent == intent
            is DaemonMigrationRead.Remote -> before.pointer.intent == intent
            DaemonMigrationRead.Blocked -> false
        }
    } } catch (_: Exception) { false }

    /** Called after the daemon has closed admission and issued this exact immutable challenge. */
    private fun commitPointer(value: DaemonRemotePointer): Boolean = try { files.exclusive {
        val before = readStrict()
        val prior = when (before) {
            is DaemonMigrationRead.Migrating -> { check(before.intent == value.intent); null }
            is DaemonMigrationRead.Remote -> {
                check(before.pointer.intent == value.intent && before.pointer.importDigest == value.importDigest)
                before.pointer
            }
            else -> error("Migration is not ready for a pointer commit")
        }
        prior?.let {
            val old = it.committed.record; val next = value.committed.record
            check(next == old || old.revision < Long.MAX_VALUE && next.revision == old.revision + 1)
        }
        persistPointer(value)
        true
    } } catch (_: Exception) { false }

    /** Commit/readback and attest the original challenge, not a reconstructed or caller-edited one. */
    fun commit(intent: DaemonMigrationIntent, importDigest: String,
        challenge: DaemonPointerChallenge): DaemonAppPointerAttestation? {
        val value = DaemonRemotePointer(intent, challenge.pointer, importDigest)
        return if (commitPointer(value) && read() == DaemonMigrationRead.Remote(value))
            DaemonAppPointerAttestation(challenge, challenge.pointer) else null
    }

    /** Same durable ordering for ordinary commits and explicitly challenged pending recovery. */
    private fun persistPointer(value: DaemonRemotePointer) {
        val bytes = DaemonMigrationPointerCodec.encodePointer(value)
        files.write(DaemonMigrationFile.PENDING, bytes)
        check(files.read(DaemonMigrationFile.PENDING)?.contentEquals(bytes) == true)
        files.write(DaemonMigrationFile.POINTER, bytes)
        check(files.read(DaemonMigrationFile.POINTER)?.contentEquals(bytes) == true)
        files.remove(DaemonMigrationFile.PENDING)
        check(readStrict() == DaemonMigrationRead.Remote(value))
    }

    private fun inspectRecoveryStrict(): DaemonMigrationRecoveryRead {
        val marker = files.read(DaemonMigrationFile.INTENT)
        val pointerBytes = files.read(DaemonMigrationFile.POINTER)
        val pendingBytes = files.read(DaemonMigrationFile.PENDING)
        val stopBytes = files.read(DaemonMigrationFile.STOP)
        if (marker == null) {
            check(pointerBytes == null && pendingBytes == null && stopBytes == null)
            return DaemonMigrationRecoveryRead.NotPending
        }
        val intent = DaemonMigrationPointerCodec.decodeIntent(marker)
        val pointer = pointerBytes?.let(DaemonMigrationPointerCodec::decodePointer)
        check(pointer == null || pointer.intent == intent)
        validateStop(stopBytes, intent, pointer)
        if (pendingBytes == null) return DaemonMigrationRecoveryRead.NotPending
        val candidate = DaemonMigrationPointerCodec.decodePointer(pendingBytes)
        check(candidate.intent == intent)
        pointer?.let {
            check(it.importDigest == candidate.importDigest)
            val old = it.committed.record; val next = candidate.committed.record
            check(next == old || old.revision < Long.MAX_VALUE && next.revision == old.revision + 1) {
                "Pending pointer record sequence changed"
            }
        }
        return DaemonMigrationRecoveryRead.Pending(intent, pointer, candidate, digest(marker),
            pointerBytes?.let(::digest), digest(pendingBytes))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validateStop(bytes: ByteArray?, intent: DaemonMigrationIntent, pointer: DaemonRemotePointer?) {
        if (bytes == null) return
        val stop = DaemonMigrationPointerCodec.decodeStopIntent(bytes)
        check(stop.intent == intent && (pointer == null || pointer.intent == stop.intent)) { "Orphaned or mismatched OFF intent" }
    }

    /** Only explicit fresh host-challenged recovery may resolve PENDING. */
    private fun readStrict(): DaemonMigrationRead {
        val marker = files.read(DaemonMigrationFile.INTENT)
        val pointer = files.read(DaemonMigrationFile.POINTER)
        val pending = files.read(DaemonMigrationFile.PENDING)
        val stop = files.read(DaemonMigrationFile.STOP)
        check(pending == null) { "An interrupted pointer commit requires reconciliation" }
        if (marker == null) {
            check(pointer == null && stop == null)
            return DaemonMigrationRead.Local
        }
        val intent = DaemonMigrationPointerCodec.decodeIntent(marker)
        if (pointer == null) {
            validateStop(stop, intent, null)
            return DaemonMigrationRead.Migrating(intent)
        }
        val saved = DaemonMigrationPointerCodec.decodePointer(pointer)
        check(saved.intent == intent)
        validateStop(stop, intent, saved)
        return DaemonMigrationRead.Remote(saved)
    }
}

internal object DaemonMigrationPointerCodec {
    const val MAX_BYTES = 2048
    private const val INTENT_MAGIC = 0x474d4931
    private const val POINTER_MAGIC = 0x474d5031
    private const val STOP_MAGIC = 0x474d5331
    fun encodeIntent(value: DaemonMigrationIntent): ByteArray = encode { writeInt(INTENT_MAGIC); intent(value) }
    fun decodeIntent(bytes: ByteArray): DaemonMigrationIntent = decode(bytes) {
        require(readInt() == INTENT_MAGIC); intent()
    }
    fun encodePointer(value: DaemonRemotePointer): ByteArray = encode {
        writeInt(POINTER_MAGIC); intent(value.intent)
        writeUTF(value.committed.commitId)
        val record = value.committed.record
        writeLong(record.revision); writeUTF(record.authorityDigest); writeByte(record.mode.ordinal)
        writeUTF(value.importDigest)
    }
    fun decodePointer(bytes: ByteArray): DaemonRemotePointer = decode(bytes) {
        require(readInt() == POINTER_MAGIC)
        val intent = intent(); val commit = text()
        val record = DaemonControlRecord(intent.owner, readLong(), text(),
            DaemonControlMode.entries.getOrNull(readUnsignedByte()) ?: error("Unknown mode"), intent.migrationId)
        DaemonRemotePointer(intent, DaemonPointerCommitProof(commit, record), text())
    }
    fun encodeStopIntent(value: DaemonAppStopIntent): ByteArray = encode {
        writeInt(STOP_MAGIC); writeInt(1); writeUTF(value.id); intent(value.intent)
        writeInt(value.command.kind)
        val target = value.command.target?.let { it.copy(signerSha256 = it.signerSha256.toList()) }
        writeByte(if (target == null) 0 else 1)
        target?.let {
            require(it.signerSha256.size in 1..16)
            writeUTF(it.packageName); writeInt(it.uid); writeLong(it.firstInstallTime)
            writeInt(it.signerSha256.size); it.signerSha256.forEach(::writeUTF)
        }
    }
    fun decodeStopIntent(bytes: ByteArray): DaemonAppStopIntent = decode(bytes) {
        require(readInt() == STOP_MAGIC && readInt() == 1)
        val id = text(); val intent = intent(); val kind = readInt()
        val hasTarget = readUnsignedByte().also { require(it in 0..1) } == 1
        val target = if (!hasTarget) null else {
            val packageName = readUTF().also { require(it.length in 1..255) }
            val uid = readInt(); val installed = readLong()
            val count = readInt().also { require(it in 1..16) }
            val signers = java.util.Collections.unmodifiableList(List(count) { text() })
            SelectedTargetIdentity(packageName, uid, installed, signers)
        }
        DaemonAppStopIntent(id, intent, DaemonAppCommand(kind, target))
    }
    private fun encode(action: DataOutputStream.() -> Unit): ByteArray {
        val body = ByteArrayOutputStream().also { DataOutputStream(it).use(action) }.toByteArray()
        require(body.size + 32 <= MAX_BYTES)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    private fun <T> decode(bytes: ByteArray, action: DataInputStream.() -> T): T {
        require(bytes.size in 36..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input -> action(input).also { require(input.available() == 0) } }
    }
    private fun DataOutputStream.intent(value: DaemonMigrationIntent) {
        writeUTF(value.owner.installInstance); writeLong(value.owner.ownerEpoch); writeUTF(value.migrationId)
    }
    private fun DataInputStream.intent() = DaemonMigrationIntent(DaemonOwnerKey(text(), readLong()), text())
    private fun DataInputStream.text() = readUTF().also { require(it.isNotBlank() && it.length <= 128) }
}

private fun uuid4(value: String): Boolean = runCatching {
    val uuid = UUID.fromString(value)
    uuid.toString() == value && uuid.version() == 4 && uuid.variant() == 2
}.getOrDefault(false)
