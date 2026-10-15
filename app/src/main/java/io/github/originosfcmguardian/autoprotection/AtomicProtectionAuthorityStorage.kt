package io.github.originosfcmguardian.autoprotection

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder
import java.security.MessageDigest

/** Consent and budget share one commit, so a surviving grant can never acquire a fresh budget. */
internal class AtomicProtectionAuthorityStorage(context: Context) : ProtectionAuthorityStorage {
    companion object {
        // This fixed authority file has one local monitor across all storage instances. JVM
        // FileLock overlap throws instead of waiting, so serialize local transactions first.
        private val localMonitor = Any()
    }
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "automatic-protection-authority-v1.bin"))
    private val lockFile = File(directory, "automatic-protection-authority-v1.lock")
    private val initialized = File(directory, "automatic-protection-authority-v1.initialized")
    private val retiring = File(directory, "automatic-protection-authority-v1.stop-pending")

    override fun <T> exclusive(action: () -> T): T = synchronized(localMonitor) {
        RandomAccessFile(lockFile, "rw").use { handle ->
            val lock = handle.channel.tryLock() ?: error("Protection authority owner busy")
            lock.use { action() }
        }
    }

    override fun read(): ProtectionAuthoritySnapshot? = readCommitted(false)

    private fun readCommitted(ignoreRetirement: Boolean): ProtectionAuthoritySnapshot? {
        val bytes = try {
            file.openRead().use { input ->
                readBoundedAuthorityBytes(input, ProtectionAuthorityCodec.MAX_BYTES)
            }
        } catch (missing: FileNotFoundException) {
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists() && !initialized.exists() && !retiring.exists()) { "Protection authority missing after initialization" }
            // An old budget is never silently replaced with an empty budget or converted to consent.
            check(listOf(".bin", ".bin.bak", ".bin.new", ".initialized").none {
                File(directory, "automatic-attempt-budget-v1$it").exists()
            }) { "Legacy attempt budget cannot be reset automatically" }
            return null
        }
        check(initialized.isFile && initialized.length() == 1L && initialized.inputStream().use { it.read() } == 1)
        val value = ProtectionAuthorityCodec.decode(bytes)
        if (!ignoreRetirement && retiring.exists()) {
            check(retiring.isFile && retiring.length() == 1L && retiring.inputStream().use { it.read() } == 1)
            // Interrupted retirement can leave the old ACTIVE AtomicFile committed. Preserve its
            // exact budget, but never allow that token to restore after a process recreation.
            return authorityWithRetirementMarker(value)
        }
        return value
    }

    override fun write(value: ProtectionAuthoritySnapshot) {
        // A pre-existing byte1 marker has unknown intent (OFF, removal or replacement). Only
        // this invocation's own successfully read-back commit may remove a newly created marker.
        check(!retiring.exists()) { "Interrupted authority operation blocks ordinary writes" }
        check(!value.storageBlocked)
        val bytes = ProtectionAuthorityCodec.encode(value)
        val prior = readCommitted(true)
        // The marker precedes the first commit. Interrupted initialization cannot look like first use.
        if (!initialized.exists()) {
            FileOutputStream(initialized).use { it.write(1); it.fd.sync() }
            syncDirectory()
        }
        if (requiresAuthorityRetirementMarker(prior, value) && !retiring.exists()) {
            FileOutputStream(retiring).use { it.write(1); it.fd.sync() }
            syncDirectory()
        }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            syncDirectory()
        } catch (failed: Exception) {
            file.failWrite(output)
            throw failed
        }
        check(!File(file.baseFile.path + ".new").exists() && readCommitted(true) == value) { "Protection authority read-back failed" }
        // A marker created by this write is acknowledged only after exact committed read-back.
        if (retiring.exists()) {
            check(retiring.delete())
            syncDirectory()
        }
        check(read() == value) { "Protection authority retirement read-back failed" }
    }

    private fun syncDirectory() {
        // O_DIRECTORY is not exposed by the public Android SDK; this path is already the
        // app-provided directory, opened read-only solely for the directory durability barrier.
        check(directory.isDirectory)
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}

/** A partial active-to-active removal/replacement must never restore the old target set. */
internal fun requiresAuthorityRetirementMarker(prior: ProtectionAuthoritySnapshot?, next: ProtectionAuthoritySnapshot): Boolean =
    next.mode != ProtectionConsentMode.ACTIVE || prior?.consents()?.any { it !in next.consents() } == true ||
        prior?.invalidatedConsentIds?.let { !next.invalidatedConsentIds.containsAll(it) } == true ||
        next.invalidatedConsentIds.any { it !in prior?.invalidatedConsentIds.orEmpty() }

internal fun authorityWithRetirementMarker(value: ProtectionAuthoritySnapshot): ProtectionAuthoritySnapshot =
    value.configuredSchema3().copy(mode = ProtectionConsentMode.SUSPENDED,
        suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE, storageBlocked = true)

/** Schema1/2 bytes are unchanged. Schema3 is written only by a meaningful configuration/master change. */
internal object ProtectionAuthorityCodec {
    const val MAX_BYTES = 256 * 1024
    private const val LEGACY_MAX_BYTES = 8192
    private const val MAGIC = 0x46434D41

    fun encode(value: ProtectionAuthoritySnapshot): ByteArray {
        require(!value.storageBlocked) { "Storage-blocked projection cannot be committed" }
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(value.schemaVersion)
                output.writeLong(value.generation)
                output.writeByte(value.mode.ordinal)
                output.writeInt(value.suspensionReason?.ordinal ?: -1)
                if (value.schemaVersion == 1) {
                    output.writeBoolean(value.token != null)
                    value.token?.let { output.consent(TargetConsent.Legacy(it)) }
                } else {
                    output.writeInt(value.targetConsents.size)
                    value.targetConsents.forEach { consent ->
                        output.writeByte(if (consent is TargetConsent.Legacy) 0 else 1)
                        output.consent(consent)
                    }
                }
                if (value.schemaVersion == 3) {
                    output.writeLong(value.masterEnabledAtMillis ?: 0)
                    output.writeInt(value.invalidatedConsentIds.size)
                    value.invalidatedConsentIds.forEach { output.string(it, 36) }
                }
                val budget = BudgetSnapshotCodec.encode(value.budget)
                output.writeInt(budget.size)
                output.write(budget)
            }
        }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also {
            require(it.size <= if (value.schemaVersion == 1) LEGACY_MAX_BYTES else MAX_BYTES)
        }
    }

    private fun DataOutputStream.consent(value: TargetConsent) {
        when (value) {
            is TargetConsent.Legacy -> value.value.let { token ->
                writeInt(token.schemaVersion); writeInt(token.policyVersion); writeInt(token.userId)
            }
            is TargetConsent.Recipient -> { writeInt(1); writeInt(value.value.policyVersion); writeInt(value.value.userId) }
        }
        string(value.id, 36); writeLong(value.generation); writeLong(value.consentedAtMillis)
        string(value.identity.packageName, 256); writeInt(value.identity.uid); writeLong(value.identity.firstInstallTime)
        writeInt(value.identity.signerSha256.size)
        value.identity.signerSha256.forEach { string(it, 64) }
        string(value.fingerprint, 2048)
    }

    fun decode(bytes: ByteArray): ProtectionAuthoritySnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(bytes.size - 32, bytes.size)))
        // Decoder state belongs to this decode only; reset before every fresh string.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC)
            val schemaVersion = input.readInt()
            require(schemaVersion in 1..3 && (schemaVersion != 1 || bytes.size <= LEGACY_MAX_BYTES))
            val generation = input.readLong()
            val mode = ProtectionConsentMode.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown consent mode")
            val reasonOrdinal = input.readInt()
            val reason = if (reasonOrdinal == -1) null else ProtectionSuspensionReason.entries.getOrNull(reasonOrdinal) ?: error("Unknown suspension reason")
            val targets = if (schemaVersion == 1) {
                val present = input.readUnsignedByte(); require(present in 0..1)
                if (present == 1) listOf(input.consent(0, decoder)) else emptyList()
            } else {
                val count = input.readInt()
                // Every entry needs substantially more than one byte; cap allocation by wire size.
                require(count in 0..(payload.size / 100))
                List(count) { input.consent(input.readUnsignedByte(), decoder) }
            }
            val masterEnabledAt = if (schemaVersion == 3) input.readLong().also { require(it >= 0) }.takeIf { it != 0L } else null
            val invalidated = if (schemaVersion == 3) {
                val count = input.readInt()
                require(count in 0..targets.size)
                java.util.Collections.unmodifiableList(List(count) { input.string(36, decoder) })
            } else emptyList()
            val budgetSize = input.readInt()
            require(budgetSize in 1..256)
            val budgetBytes = ByteArray(budgetSize).also(input::readFully)
            val budget = BudgetSnapshotCodec.decode(budgetBytes)
            require(input.available() == 0)
            ProtectionAuthoritySnapshot(generation, mode,
                if (schemaVersion == 1) (targets.singleOrNull() as? TargetConsent.Legacy)?.value else null,
                reason, budget, if (schemaVersion >= 2) java.util.Collections.unmodifiableList(targets) else emptyList(), schemaVersion,
                masterEnabledAt, invalidated)
        }
    }

    private fun DataInputStream.consent(kind: Int, decoder: CharsetDecoder): TargetConsent {
                require(kind in 0..1)
                val schema = readInt()
                val policy = readInt()
                val user = readInt()
                val id = string(36, decoder)
                val tokenGeneration = readLong()
                val time = readLong()
                val packageName = string(256, decoder)
                val uid = readInt()
                val installed = readLong()
                val signerCount = readInt()
                require(signerCount in 1..16)
                val signers = java.util.Collections.unmodifiableList(List(signerCount) { string(64, decoder) })
                val fingerprint = string(2048, decoder)
                val identity = SelectedTargetIdentity(packageName, uid, installed, signers)
                return if (kind == 0) TargetConsent.Legacy(DurableConsentToken(id, tokenGeneration, time, identity, fingerprint, schema, policy, user))
                else {
                    require(schema == 1)
                    TargetConsent.Recipient(RecipientConsentToken(id, tokenGeneration, time, identity, fingerprint, policy, user))
                }
    }

    private fun DataOutputStream.string(value: String, maximum: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maximum)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.string(maximum: Int, decoder: CharsetDecoder): String {
        val size = readInt()
        require(size in 1..maximum)
        val bytes = ByteArray(size).also(this::readFully)
        return decoder.reset().decode(ByteBuffer.wrap(bytes)).toString()
    }
}
