package io.github.originosfcmguardian.fcmprotection

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.core.gms.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** New bundled authority only. Legacy branch preferences cannot create this record. */
internal data class FcmProtectionConsent(val configured: Boolean = false, val epoch: String? = null,
    val installationBinding: String? = null, val recoveryBinding: String? = null, val recoveryPrepared: Boolean = false) {
    init {
        require(epoch == null || UUID.fromString(epoch).toString() == epoch)
        require(installationBinding == null || installationBinding.matches(Regex("[a-f0-9]{64}")))
        require(recoveryBinding == null || recoveryBinding.matches(Regex("[a-f0-9]{64}")))
        require(!configured || epoch != null && installationBinding != null && recoveryBinding != null)
    }
    // The heartbeat child's consent epoch is exactly this epoch, not an inferred legacy epoch.
    fun accepts(sample: GmsConnectionObservation.Sample, recovery: Boolean) = configured &&
        sample.versionCode == FcmRecoveryPolicy.VERSION && sample.signerSha256 == FcmRecoveryPolicy.SIGNER &&
        installationBinding == gmsHeartbeatBindingDigest(sample) &&
        (!recovery || recoveryBinding == FcmRecoveryPolicy.binding(sample))
    fun admits(sample: GmsConnectionObservation.Sample?, recovery: Boolean, childEpoch: String? = epoch) =
        configured && recoveryPrepared && (recovery || childEpoch == epoch) &&
            (sample == null || accepts(sample, recovery))
}
internal interface FcmProtectionStorage {
    fun read(): FcmProtectionConsent
    fun write(value: FcmProtectionConsent)
    fun halt()
    fun releaseHalt()
}
internal class FcmProtectionStore(private val storage: FcmProtectionStorage) {
    @Synchronized fun read() = storage.read()
    @Synchronized fun confirm(sample: GmsConnectionObservation.Sample): FcmProtectionConsent {
        val value = FcmProtectionConsent(true, UUID.randomUUID().toString(),
            gmsHeartbeatBindingDigest(sample), FcmRecoveryPolicy.binding(sample))
        storage.write(value); storage.releaseHalt(); return read()
    }
    @Synchronized fun disable(): FcmProtectionConsent {
        storage.halt() // Must precede accepting OFF, including AtomicFile backup restoration.
        return read().copy(configured = false).also(storage::write)
    }
    @Synchronized fun recoveryPrepared(epoch: String): FcmProtectionConsent = read().let {
        check(it.configured && it.epoch == epoch)
        it.copy(recoveryPrepared = true).also(storage::write)
    }
}
internal object FcmProtectionCodec {
    const val MAX_BYTES = 512
    fun encode(value: FcmProtectionConsent): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(0x46435031); it.writeInt(1); it.writeBoolean(value.configured)
            it.writeUTF(value.epoch ?: ""); it.writeUTF(value.installationBinding ?: ""); it.writeUTF(value.recoveryBinding ?: "")
            it.writeBoolean(value.recoveryPrepared)
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): FcmProtectionConsent {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == 0x46435031 && it.readInt() == 1)
            val value = FcmProtectionConsent(it.readBoolean(), it.readUTF().ifEmpty { null },
                it.readUTF().ifEmpty { null }, it.readUTF().ifEmpty { null }, it.readBoolean())
            require(it.available() == 0); value
        }
    }
}
internal class AtomicFcmProtectionStorage(context: Context) : FcmProtectionStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "fcm-protection-v1.bin"))
    private val off = File(context.noBackupFilesDir, "fcm-protection-off-v1")
    override fun read(): FcmProtectionConsent {
        val value = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) FcmProtectionConsent()
            else file.openRead().use { input ->
                val buffer = ByteArray(FcmProtectionCodec.MAX_BYTES + 1); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                FcmProtectionCodec.decode(buffer.copyOf(count))
            }
        return value.copy(configured = value.configured && !off.exists())
    }
    override fun write(value: FcmProtectionConsent) {
        var out: FileOutputStream? = null
        try { out = file.startWrite(); out.write(FcmProtectionCodec.encode(value)); file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
    }
    override fun halt() { FileOutputStream(off).use { it.write(1); it.fd.sync() } }
    override fun releaseHalt() { check(!off.exists() || off.delete()) }
}
