package io.github.originosfcmguardian.fcmrecovery

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.core.gms.*
import java.io.*
import java.security.MessageDigest

enum class FcmRecoveryEventAction { OBSERVATION, CONSENT_ON, CONSENT_OFF, HOST_ENDED, PRIVILEGE_LOST, INELIGIBLE, ATTEMPT_CONSUMED, REQUEST_RESULT, READBACK, STORAGE_FAILURE, POWER_SAMPLE }
enum class FcmRecoveryEligibility { ELIGIBLE, CONSENT_OFF, HOST_UNAVAILABLE, SHELL_UNAVAILABLE, NETWORK_UNAVAILABLE }
internal enum class FcmRecoveryPreparation { READY, HOST_UNAVAILABLE, SHELL_UNAVAILABLE, NETWORK_UNAVAILABLE, READ_IN_PROGRESS, NOT_QUALIFIED, STORAGE_FAILURE }
data class FcmRecoveryEvent(val atMillis: Long, val elapsedMillis: Long, val action: FcmRecoveryEventAction,
    val service: GcmConnectionState = GcmConnectionState.UNKNOWN,
    val completeness: SourceCompleteness = SourceCompleteness.UNAVAILABLE,
    val socket: SocketObservation = SocketObservation.UNKNOWN, val eligible: Boolean = false,
    val status: FcmRecoveryStatus? = null, val reason: FcmRecoveryReason? = null,
    val eligibility: FcmRecoveryEligibility = if (eligible) FcmRecoveryEligibility.ELIGIBLE else FcmRecoveryEligibility.HOST_UNAVAILABLE,
    val observationUnknownReason: GmsUnknown? = null, val power: FcmPowerReference? = null) {
    init { require(atMillis > 0 && elapsedMillis >= 0 && (status == null) == (reason == null)) }
}
data class FcmRecoveryUiState(val configured: Boolean = false, val busy: Boolean = false,
    val storageBlocked: Boolean = false, val requiresConfirmation: Boolean = false,
    val detail: String = "尚未开启；每次服务缺失只尝试一次，不保证通知送达。",
    val events: List<FcmRecoveryEvent> = emptyList())

internal data class FcmRecoverySnapshot(val configured: Boolean = false, val binding: String? = null,
    val consumed: Boolean = false, val consumedBoot: String? = null, val consumedAt: Long = 0,
    val consumedElapsed: Long = 0, val events: List<FcmRecoveryEvent> = emptyList(), val powerSampleAtMillis: Long? = null)
internal interface FcmRecoveryStorage {
    fun read(): FcmRecoverySnapshot
    fun write(value: FcmRecoverySnapshot)
    fun markConsumed(value: FcmRecoverySnapshot)
    fun clearConsumed()
    fun halt()
    fun releaseHalt()
}
/** One app process owner. The shell process never opens this file. Markers precede dangerous changes. */
internal class FcmRecoveryStore(private val storage: FcmRecoveryStorage) {
    @Synchronized fun read() = storage.read()
    @Synchronized fun confirm(binding: String): FcmRecoverySnapshot {
        require(binding.matches(Regex("[a-f0-9]{64}")))
        val next = read().copy(configured = true, binding = binding)
        storage.write(next); storage.releaseHalt(); return read()
    }
    @Synchronized fun disable(): FcmRecoverySnapshot {
        storage.halt()
        return read().copy(configured = false).also(storage::write)
    }
    @Synchronized fun append(event: FcmRecoveryEvent): FcmRecoverySnapshot = read().let {
        it.copy(events = (it.events + event).takeLast(64)).also(storage::write)
    }
    /** Reserve before optional acquisition; persisted independently of ring eviction/host lifetime.
     * Backward wall-clock movement waits for the previous timestamp, never creates a sample loop. */
    @Synchronized fun reservePowerSample(atMillis: Long): Boolean {
        require(atMillis > 0)
        val old = read(); val previous = old.powerSampleAtMillis
        if (previous != null && (atMillis < previous || atMillis - previous < FCM_POWER_SAMPLE_INTERVAL_MILLIS)) return false
        storage.write(old.copy(powerSampleAtMillis = atMillis)); return true
    }
    @Synchronized fun consume(sample: GmsConnectionObservation.Sample): FcmRecoverySnapshot? {
        val old = read()
        if (!old.configured || old.consumed || old.binding != FcmRecoveryPolicy.binding(sample)) return null
        val next = old.copy(consumed = true, consumedBoot = sample.bootId,
            consumedAt = sample.gcmService.observedAtMillis ?: return null,
            consumedElapsed = sample.gcmService.elapsedRealtimeMillis ?: return null)
        storage.markConsumed(next) // Must be durable even if the following atomic document write tears.
        storage.write(next); return read()
    }
    @Synchronized fun rearm(sample: GmsConnectionObservation.Sample, now: Long, usableNetwork: Boolean): FcmRecoverySnapshot {
        val old = read()
        val newer = sample.gcmService.observedAtMillis?.let { it > old.consumedAt } == true &&
            (sample.bootId != old.consumedBoot || (sample.gcmService.elapsedRealtimeMillis ?: 0) > old.consumedElapsed)
        if (!old.configured || old.binding != FcmRecoveryPolicy.binding(sample) || !old.consumed || !newer ||
            !usableNetwork || !FcmRecoveryPolicy.connected(sample, now)) return old
        val next = old.copy(consumed = false)
        storage.write(next); storage.clearConsumed(); return read()
    }
}

internal object FcmRecoveryStoreCodec {
    const val MAX_BYTES = 32_768
    fun encode(value: FcmRecoverySnapshot): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x46435231); out.writeInt(2)
            out.writeBoolean(value.configured); out.writeUTF(value.binding ?: "")
            out.writeBoolean(value.consumed); out.writeUTF(value.consumedBoot ?: "")
            out.writeLong(value.consumedAt); out.writeLong(value.consumedElapsed)
            out.writeLong(value.powerSampleAtMillis ?: -1)
            require(value.events.size <= 64); out.writeInt(value.events.size)
            value.events.forEach { e ->
                out.writeLong(e.atMillis); out.writeLong(e.elapsedMillis); out.writeUTF(e.action.name)
                out.writeUTF(e.service.name); out.writeUTF(e.completeness.name); out.writeUTF(e.socket.name)
                out.writeBoolean(e.eligible); out.writeUTF(e.status?.name ?: ""); out.writeUTF(e.reason?.name ?: "")
                out.writeUTF(e.eligibility.name)
                out.writeUTF(e.observationUnknownReason?.name ?: "")
                out.writeBoolean(e.power != null)
                e.power?.let { p ->
                    out.writeInt(p.batteryPercent ?: -1); out.writeUTF(p.chargingStatus?.name ?: "")
                    out.writeUTF(p.pluggedSource?.name ?: "")
                    out.writeByte(if (p.interactive == null) -1 else if (p.interactive) 1 else 0)
                    out.writeByte(if (p.deviceIdle == null) -1 else if (p.deviceIdle) 1 else 0)
                    out.writeLong(p.mainProcessCpuMillis ?: -1); out.writeLong(p.processAgeMillis ?: -1)
                    out.writeUTF(p.processInstanceId ?: "")
                }
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): FcmRecoverySnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val data = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data), bytes.copyOfRange(data.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(data)).use { input ->
            require(input.readInt() == 0x46435231)
            val version = input.readInt(); require(version in 1..2)
            val configured = input.readBoolean(); val binding = input.readUTF().ifEmpty { null }
            require(binding == null || binding.matches(Regex("[a-f0-9]{64}"))); require(!configured || binding != null)
            val consumed = input.readBoolean(); val boot = input.readUTF().ifEmpty { null }
            val at = input.readLong(); val elapsed = input.readLong(); require(at >= 0 && elapsed >= 0)
            val powerAt = if (version >= 2) input.readLong().let { require(it == -1L || it > 0); it.takeIf { it > 0 } } else null
            require(boot == null || boot.matches(Regex("[a-f0-9-]{36}")))
            val count = input.readInt(); require(count in 0..64)
            val events = List(count) {
                val event = FcmRecoveryEvent(input.readLong(), input.readLong(), FcmRecoveryEventAction.valueOf(input.readUTF()),
                    GcmConnectionState.valueOf(input.readUTF()), SourceCompleteness.valueOf(input.readUTF()),
                    SocketObservation.valueOf(input.readUTF()), input.readBoolean(),
                    input.readUTF().ifEmpty { null }?.let(FcmRecoveryStatus::valueOf),
                    input.readUTF().ifEmpty { null }?.let(FcmRecoveryReason::valueOf),
                    FcmRecoveryEligibility.valueOf(input.readUTF()))
                if (version == 1) event else {
                    val unknown = input.readUTF().ifEmpty { null }?.let(GmsUnknown::valueOf)
                    val power = if (!input.readBoolean()) null else {
                        val percent = input.readInt().let { require(it in -1..100); it.takeIf { it >= 0 } }
                        val charging = input.readUTF().ifEmpty { null }?.let(FcmChargingStatus::valueOf)
                        val plugged = input.readUTF().ifEmpty { null }?.let(FcmPluggedSource::valueOf)
                        fun flag(): Boolean? = when (input.readByte().toInt()) { -1 -> null; 0 -> false; 1 -> true; else -> error("Invalid power flag") }
                        val interactive = flag(); val idle = flag()
                        fun duration(): Long? = input.readLong().let { require(it >= -1); it.takeIf { it >= 0 } }
                        FcmPowerReference(percent, charging, plugged, interactive, idle, duration(), duration(), input.readUTF().ifEmpty { null })
                    }
                    event.copy(observationUnknownReason = unknown, power = power)
                }
            }
            require(input.available() == 0); FcmRecoverySnapshot(configured, binding, consumed, boot, at, elapsed, events, powerAt)
        }
    }
}

internal const val FCM_POWER_SAMPLE_INTERVAL_MILLIS = 60 * 60 * 1000L

internal class AtomicFcmRecoveryStorage(context: Context) : FcmRecoveryStorage {
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "fcm-recovery-trial-v1.bin"))
    private val consumed = File(directory, "fcm-recovery-trial-consumed-v1.bin")
    private val halt = File(directory, "fcm-recovery-trial-off-v1")
    override fun read(): FcmRecoverySnapshot {
        val value = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) FcmRecoverySnapshot()
            else file.openRead().use { input ->
                val bytes = boundedBytes(input)
                FcmRecoveryStoreCodec.decode(bytes)
            }
        val marker = if (consumed.exists()) consumed.inputStream().use {
            FcmRecoveryStoreCodec.decode(boundedBytes(it))
        } else null
        return value.copy(configured = value.configured && !halt.exists(), consumed = value.consumed || marker != null,
            consumedBoot = marker?.consumedBoot ?: value.consumedBoot,
            consumedAt = marker?.consumedAt ?: value.consumedAt,
            consumedElapsed = marker?.consumedElapsed ?: value.consumedElapsed)
    }
    override fun write(value: FcmRecoverySnapshot) {
        var out: FileOutputStream? = null
        try { out = file.startWrite(); out.write(FcmRecoveryStoreCodec.encode(value)); file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
    }
    override fun markConsumed(value: FcmRecoverySnapshot) {
        check(!consumed.exists())
        FileOutputStream(consumed).use { it.write(FcmRecoveryStoreCodec.encode(value.copy(events = emptyList()))); it.fd.sync() }
    }
    override fun clearConsumed() { check(!consumed.exists() || consumed.delete()) }
    override fun halt() { FileOutputStream(halt).use { it.write(1); it.fd.sync() } }
    override fun releaseHalt() { check(!halt.exists() || halt.delete()) }
    private fun boundedBytes(input: InputStream): ByteArray {
        val bytes = ByteArray(FcmRecoveryStoreCodec.MAX_BYTES + 1)
        var count = 0
        while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
        return bytes.copyOf(count)
    }
}
