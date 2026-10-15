package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.GmsHeartbeatReason
import io.github.originosfcmguardian.core.gms.GmsHeartbeatResult
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class HeartbeatPause { RECONFIRM, HOST_LOST, UNKNOWN_RESULT, LEFTOVER_PENDING, OFF_PENDING, STORAGE_FAILURE, WAITING_HOST }
internal enum class HeartbeatResumeBlock { COLD_RECONSTRUCTION, UNKNOWN_RESULT, LEFTOVER_PENDING, IDENTITY_CHANGED, SECURITY_FAILURE, STORAGE_FAILURE, UNCERTAIN_OWNER }
internal data class HeartbeatDurableConsent(val version: Int, val epoch: String, val userId: Int, val bindingDigest: String) {
    // v2 permits same-boot cold continuation; v3 additionally permits a freshly
    // qualified new boot. Only a new explicit confirmation creates v3.
    init { require(version in 1..3 && canonicalUuid(epoch) && userId == 0 && bindingDigest.matches(Regex("[a-f0-9]{64}"))) }
}

/** Preference/evidence only. No stored field can recreate a remote automatic grant. */
internal data class HeartbeatSnapshot(
    val generation: Long,
    val configured: Boolean,
    val pause: HeartbeatPause? = null,
    val pendingAttempt: String? = null,
    val bindingDigest: String? = null,
    val remoteSessionId: String? = null,
    val lastStatus: GmsHeartbeatStatus? = null,
    val lastReason: GmsHeartbeatReason? = null,
    val schemaVersion: Int = 1,
    val durableConsent: HeartbeatDurableConsent? = null,
    val resumeBlock: HeartbeatResumeBlock? = null,
    val lastResultEpoch: String? = null,
    val callerIdentity: GmsHeartbeatCallerIdentity? = null,
    val pendingArm: String? = null,
) {
    init {
        require(schemaVersion in 1..3)
        require(schemaVersion >= 2 || durableConsent == null && resumeBlock == null && lastResultEpoch == null)
        require(schemaVersion >= 2 || pause != HeartbeatPause.WAITING_HOST)
        require(schemaVersion == 3 || callerIdentity == null && pendingArm == null)
        require((durableConsent?.version ?: 0) < 2 || schemaVersion == 3 && callerIdentity != null)
        require(pendingArm == null || canonicalUuid(pendingArm))
        require(lastResultEpoch == null || canonicalUuid(lastResultEpoch))
        require(durableConsent == null || configured && durableConsent.bindingDigest == bindingDigest)
        require(generation > 0)
        require(pendingAttempt == null || canonicalUuid(pendingAttempt))
        require(remoteSessionId == null || canonicalUuid(remoteSessionId))
        require(bindingDigest == null || bindingDigest.matches(Regex("[a-f0-9]{64}")))
        require(pendingAttempt == null || bindingDigest != null && remoteSessionId != null)
        require((lastStatus == null) == (lastReason == null))
        if (lastStatus != null) GmsHeartbeatResult(1, lastStatus, requireNotNull(lastReason))
    }
}

internal fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun HeartbeatSnapshot.currentEpochUnknown(): Boolean = lastStatus == GmsHeartbeatStatus.UNKNOWN &&
    durableConsent != null && lastResultEpoch == durableConsent.epoch

internal interface HeartbeatStorage {
    fun <T> exclusive(action: () -> T): T
    /** Null only for genuine first use; partial loss/corruption throws. */
    fun read(): HeartbeatSnapshot?
    fun write(value: HeartbeatSnapshot)
    /** Must be checked before AtomicFile is allowed to recover a backup. */
    fun coldBarrier(): HeartbeatResumeBlock? = null
    fun halt() {}
    fun pending(value: HeartbeatPending) {}
    fun clearPending(value: HeartbeatPending) {}
    /** Only a committed fresh explicit consent may release old barriers. */
    fun releaseForExplicitConsent() {}
    fun unsettledPending(): Boolean = false
}

internal enum class HeartbeatPendingKind { ARM, HEARTBEAT }
internal data class HeartbeatPending(val kind: HeartbeatPendingKind, val epoch: String, val generation: Long, val operationId: String) {
    init { require(canonicalUuid(epoch) && generation > 0 && canonicalUuid(operationId)) }
}

internal object HeartbeatPendingCodec {
    const val MAX_BYTES = 256
    fun encode(value: HeartbeatPending): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(0x474D5350); it.writeInt(1); it.writeInt(value.kind.ordinal)
            it.writeUTF(value.epoch); it.writeLong(value.generation); it.writeUTF(value.operationId)
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): HeartbeatPending {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == 0x474D5350 && it.readInt() == 1)
            val kind = HeartbeatPendingKind.entries.getOrNull(it.readInt()) ?: error("Unknown pending kind")
            val value = HeartbeatPending(kind, it.readUTF(), it.readLong(), it.readUTF())
            require(it.available() == 0); value
        }
    }
}

internal class GmsAutoHeartbeatStore(private val storage: HeartbeatStorage) {
    fun read(): HeartbeatSnapshot? = storage.exclusive { storage.read() }
    /** Inspection only: migration must not let AtomicFile repair an interrupted old owner. */
    fun readForDaemonMigration(): HeartbeatSnapshot? = storage.exclusive {
        check(storage.coldBarrier() == null && !storage.unsettledPending())
        storage.read().also { check(it?.pendingArm == null && it?.pendingAttempt == null) }
    }
    fun unsettledOperation(): Boolean = storage.exclusive {
        storage.unsettledPending() || storage.read()?.let { it.pendingAttempt != null || it.pendingArm != null } == true
    }
    fun persistHalt() = storage.exclusive { storage.halt() }

    /** Reconstruction pauses preference without erasing an uncertain attempt or OFF acknowledgement. */
    fun reconstruct(): HeartbeatSnapshot? = storage.exclusive {
        val barrier = storage.coldBarrier()
        val old = storage.read() ?: return@exclusive null
        if (!old.configured || old.pause == HeartbeatPause.OFF_PENDING) return@exclusive old
        if (old.schemaVersion == 3 && old.durableConsent?.version in 2..3 && old.callerIdentity != null &&
            barrier == null && old.resumeBlock == null && old.pendingArm == null && old.pendingAttempt == null && !old.currentEpochUnknown()) {
            return@exclusive old.copy(generation = Math.addExact(old.generation, 1), pause = HeartbeatPause.WAITING_HOST,
                remoteSessionId = null).also(storage::write)
        }
        val next = old.copy(generation = Math.addExact(old.generation, 1),
            pause = if (old.pendingAttempt != null) HeartbeatPause.LEFTOVER_PENDING else HeartbeatPause.RECONFIRM,
            lastStatus = if (old.pendingAttempt != null) GmsHeartbeatStatus.UNKNOWN else old.lastStatus,
            lastReason = if (old.pendingAttempt != null) GmsHeartbeatReason.BRIDGE_UNAVAILABLE else old.lastReason)
            .let { if (it.schemaVersion >= 2) it.copy(resumeBlock = it.resumeBlock ?: barrier ?: if (it.pendingAttempt != null || it.pendingArm != null)
                HeartbeatResumeBlock.LEFTOVER_PENDING else HeartbeatResumeBlock.COLD_RECONSTRUCTION) else it }
        storage.write(next)
        next
    }

    fun prepareConfirmation(expected: HeartbeatSnapshot?, digest: String, durable: Boolean = false,
        caller: GmsHeartbeatCallerIdentity? = null, bundledEpoch: String? = null): HeartbeatSnapshot? = storage.exclusive {
        require(bundledEpoch == null || durable && canonicalUuid(bundledEpoch))
        if (durable && caller != null) storage.halt()
        val old = storage.read()
        if (old != expected || old?.pause == HeartbeatPause.OFF_PENDING) return@exclusive null
        if (caller != null && (storage.unsettledPending() || old?.pendingAttempt != null || old?.pendingArm != null)) return@exclusive null
        val next = HeartbeatSnapshot(Math.addExact(old?.generation ?: 0, 1), true,
            HeartbeatPause.RECONFIRM, bindingDigest = digest,
            lastStatus = if (old?.pendingAttempt != null) GmsHeartbeatStatus.UNKNOWN else old?.lastStatus,
            lastReason = if (old?.pendingAttempt != null) GmsHeartbeatReason.BRIDGE_UNAVAILABLE else old?.lastReason,
            schemaVersion = if (durable && caller != null) 3 else if (durable) 2 else old?.schemaVersion ?: 1,
            durableConsent = if (durable) HeartbeatDurableConsent(if (caller != null) 3 else 1, bundledEpoch ?: UUID.randomUUID().toString(), 0, digest) else null,
            lastResultEpoch = old?.durableConsent?.epoch ?: old?.lastResultEpoch,
            callerIdentity = if (durable) caller else null)
        storage.write(next)
        next
    }

    fun releaseExplicitConsent(value: HeartbeatSnapshot) = storage.exclusive {
        check(storage.read() == value && value.schemaVersion == 3 && value.durableConsent?.version in 2..3 &&
            value.pause == null && value.remoteSessionId != null && value.pendingArm == null && value.pendingAttempt == null &&
            value.resumeBlock == null && !value.currentEpochUnknown())
        storage.releaseForExplicitConsent()
    }

    /** Controller supplies either original-endpoint closure or separately qualified cold old-caller-gone proof. */
    fun prepareResume(expected: HeartbeatSnapshot, epoch: String, caller: GmsHeartbeatCallerIdentity? = null): HeartbeatSnapshot? = storage.exclusive {
        val old = storage.read() ?: return@exclusive null
        if (old != expected || old.durableConsent?.epoch != epoch || !old.configured || old.resumeBlock != null ||
            old.pause != HeartbeatPause.WAITING_HOST || old.pendingAttempt != null || old.remoteSessionId != null ||
            old.pendingArm != null || old.currentEpochUnknown()) return@exclusive null
        if (old.schemaVersion == 3 && (caller == null || storage.coldBarrier() != null)) return@exclusive null
        old.copy(generation = Math.addExact(old.generation, 1), pause = HeartbeatPause.RECONFIRM,
            callerIdentity = caller ?: old.callerIdentity).also(storage::write)
    }

    fun block(generation: Long, reason: HeartbeatResumeBlock): HeartbeatSnapshot? = update(generation) {
        storage.halt() // Sticky refusal must survive AtomicFile rollback of an older clean ON snapshot.
        it.copy(schemaVersion = maxOf(2, it.schemaVersion), resumeBlock = it.resumeBlock ?: reason)
    }

    fun abortUnarmedResume(generation: Long): HeartbeatSnapshot? = update(generation) {
        if (it.durableConsent == null || it.resumeBlock != null || it.pause != HeartbeatPause.RECONFIRM ||
            it.pendingAttempt != null || it.pendingArm != null || it.remoteSessionId != null) null else it.copy(pause = HeartbeatPause.WAITING_HOST)
    }

    fun armed(generation: Long, sessionId: String): HeartbeatSnapshot? = update(generation) {
        if (!it.configured || it.pause != HeartbeatPause.RECONFIRM || it.pendingAttempt != null) null
        else it.copy(pause = null, remoteSessionId = sessionId, pendingArm = null)
    }

    fun beginArm(generation: Long, operationId: String): HeartbeatSnapshot? = update(generation) {
        if (it.schemaVersion != 3) it else {
            if (!it.configured || it.pause != HeartbeatPause.RECONFIRM || it.pendingArm != null) return@update null
            storage.pending(HeartbeatPending(HeartbeatPendingKind.ARM, requireNotNull(it.durableConsent).epoch, generation, operationId))
            it.copy(pendingArm = operationId)
        }
    }

    fun settleArm(value: HeartbeatSnapshot, operationId: String) = storage.exclusive {
        if (value.schemaVersion == 3) storage.clearPending(HeartbeatPending(HeartbeatPendingKind.ARM,
            requireNotNull(value.durableConsent).epoch, value.generation, operationId))
    }
    fun armRejected(generation: Long): HeartbeatSnapshot? = update(generation) {
        it.copy(pendingArm = null, pause = HeartbeatPause.RECONFIRM)
    }

    fun beginAttempt(generation: Long, attempt: String): HeartbeatSnapshot? = update(generation) {
        if (!it.configured || it.pause != null || it.pendingAttempt != null || it.remoteSessionId == null) null
        else {
            if (it.schemaVersion == 3) storage.pending(HeartbeatPending(HeartbeatPendingKind.HEARTBEAT,
                requireNotNull(it.durableConsent).epoch, generation, attempt))
            it.copy(pendingAttempt = attempt)
        }
    }

    /** A late result is an inert no-op after OFF or another confirmation/root change. */
    fun complete(generation: Long, attempt: String, result: GmsHeartbeatResult): HeartbeatSnapshot? = update(generation) {
        if (!it.configured || it.pause != null || it.pendingAttempt != attempt) null
        else it.copy(pendingAttempt = if (it.schemaVersion == 3 && result.status == GmsHeartbeatStatus.UNKNOWN) attempt else null, lastStatus = result.status, lastReason = result.reason,
            lastResultEpoch = it.durableConsent?.epoch,
            pause = if (result.status == GmsHeartbeatStatus.UNKNOWN) HeartbeatPause.UNKNOWN_RESULT else null,
            resumeBlock = if (it.schemaVersion >= 2 && result.status == GmsHeartbeatStatus.UNKNOWN)
                it.resumeBlock ?: HeartbeatResumeBlock.UNKNOWN_RESULT else it.resumeBlock)
    }

    fun settleAttempt(value: HeartbeatSnapshot, attempt: String, result: GmsHeartbeatResult) = storage.exclusive {
        if (value.schemaVersion == 3 && result.status != GmsHeartbeatStatus.UNKNOWN) storage.clearPending(
            HeartbeatPending(HeartbeatPendingKind.HEARTBEAT, requireNotNull(value.durableConsent).epoch, value.generation, attempt))
    }

    fun pause(generation: Long, reason: HeartbeatPause): HeartbeatSnapshot? = update(generation) {
        it.copy(pause = reason,
            resumeBlock = if (it.schemaVersion >= 2 && (it.pendingAttempt != null || it.pendingArm != null)) it.resumeBlock ?: HeartbeatResumeBlock.LEFTOVER_PENDING else it.resumeBlock,
            lastStatus = if (it.pendingAttempt != null) GmsHeartbeatStatus.UNKNOWN else it.lastStatus,
            lastReason = if (it.pendingAttempt != null) GmsHeartbeatReason.CANCELLED else it.lastReason)
    }

    fun manualUnknown(generation: Long): HeartbeatSnapshot? = update(generation) {
        it.copy(pause = HeartbeatPause.UNKNOWN_RESULT, lastStatus = GmsHeartbeatStatus.UNKNOWN,
            lastReason = GmsHeartbeatReason.BRIDGE_UNAVAILABLE,
            lastResultEpoch = it.durableConsent?.epoch,
            resumeBlock = if (it.schemaVersion >= 2) it.resumeBlock ?: HeartbeatResumeBlock.UNKNOWN_RESULT else null)
    }

    fun off(stopPending: Boolean): HeartbeatSnapshot = storage.exclusive {
        storage.halt()
        val old = storage.read()
        val next = (old ?: HeartbeatSnapshot(1, false)).copy(
            generation = Math.addExact(old?.generation ?: 0, 1), configured = false,
            durableConsent = null,
            resumeBlock = if ((old?.schemaVersion ?: 1) >= 2 && (old?.pendingAttempt != null || old?.pendingArm != null)) old?.resumeBlock ?: HeartbeatResumeBlock.LEFTOVER_PENDING else old?.resumeBlock,
            pause = if (stopPending) HeartbeatPause.OFF_PENDING else null,
            lastStatus = if (old?.pendingAttempt != null) GmsHeartbeatStatus.UNKNOWN else old?.lastStatus,
            lastReason = if (old?.pendingAttempt != null) GmsHeartbeatReason.CANCELLED else old?.lastReason)
        storage.write(next)
        next
    }

    /** Only the retained endpoint's confirmed revoke/death may acknowledge OFF_PENDING. */
    fun acknowledgeStop(generation: Long, clean: Boolean = false): HeartbeatSnapshot? = update(generation) {
        if (it.pause != HeartbeatPause.OFF_PENDING) null
        else it.copy(pause = if (!it.configured) null else if (clean && it.durableConsent != null && it.resumeBlock == null &&
            it.pendingAttempt == null && !it.currentEpochUnknown()) HeartbeatPause.WAITING_HOST else HeartbeatPause.RECONFIRM,
            pendingAttempt = if (it.schemaVersion == 3) it.pendingAttempt else null,
            remoteSessionId = if (it.schemaVersion == 3 && it.pendingAttempt != null) it.remoteSessionId else null)
    }

    private fun update(generation: Long, change: (HeartbeatSnapshot) -> HeartbeatSnapshot?): HeartbeatSnapshot? = storage.exclusive {
        val old = storage.read() ?: return@exclusive null
        if (old.generation != generation) return@exclusive null
        val next = change(old) ?: return@exclusive null
        storage.write(next)
        next
    }
}

internal object HeartbeatSnapshotCodec {
    const val MAX_BYTES = 1024
    fun encode(value: HeartbeatSnapshot): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x474D5348); out.writeInt(value.schemaVersion); out.writeLong(value.generation); out.writeBoolean(value.configured)
            out.writeInt(value.pause?.ordinal ?: -1)
            out.writeUTF(value.pendingAttempt.orEmpty()); out.writeUTF(value.bindingDigest.orEmpty()); out.writeUTF(value.remoteSessionId.orEmpty())
            out.writeInt(value.lastStatus?.ordinal ?: -1); out.writeInt(value.lastReason?.ordinal ?: -1)
            if (value.schemaVersion >= 2) {
                out.writeBoolean(value.durableConsent != null)
                value.durableConsent?.let { out.writeInt(it.version); out.writeUTF(it.epoch); out.writeInt(it.userId); out.writeUTF(it.bindingDigest) }
                out.writeInt(value.resumeBlock?.ordinal ?: -1)
                out.writeUTF(value.lastResultEpoch.orEmpty())
            }
            if (value.schemaVersion == 3) {
                out.writeBoolean(value.callerIdentity != null)
                value.callerIdentity?.let { out.writeInt(it.uid); out.writeInt(it.pid); out.writeLong(it.startTicks); out.writeUTF(it.kernelBootId) }
                out.writeUTF(value.pendingArm.orEmpty())
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): HeartbeatSnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x474D5348)
            val schema = input.readInt().also { require(it in 1..3) }
            val generation = input.readLong()
            val configuredByte = input.readUnsignedByte().also { require(it in 0..1) }
            val pause = input.readInt().let { if (it == -1) null else HeartbeatPause.entries.getOrNull(it) ?: error("Unknown heartbeat pause") }
            val pending = input.readUTF().ifEmpty { null }; val digest = input.readUTF().ifEmpty { null }; val session = input.readUTF().ifEmpty { null }
            val status = input.readInt().let { if (it == -1) null else GmsHeartbeatStatus.entries.getOrNull(it) ?: error("Unknown heartbeat status") }
            val reason = input.readInt().let { if (it == -1) null else GmsHeartbeatReason.entries.getOrNull(it) ?: error("Unknown heartbeat reason") }
            val consent = if (schema >= 2) {
                val present = input.readUnsignedByte().also { require(it in 0..1) }
                if (present == 1) HeartbeatDurableConsent(input.readInt(), input.readUTF(), input.readInt(), input.readUTF()) else null
            } else null
            val block = if (schema >= 2) input.readInt().let { if (it == -1) null else HeartbeatResumeBlock.entries.getOrNull(it) ?: error("Unknown resume block") } else null
            val resultEpoch = if (schema >= 2) input.readUTF().ifEmpty { null } else null
            val caller = if (schema == 3) {
                val present = input.readUnsignedByte().also { require(it in 0..1) }
                if (present == 1) GmsHeartbeatCallerIdentity(input.readInt(), input.readInt(), input.readLong(), input.readUTF()) else null
            } else null
            val pendingArm = if (schema == 3) input.readUTF().ifEmpty { null } else null
            require(input.available() == 0)
            HeartbeatSnapshot(generation, configuredByte == 1, pause, pending, digest, session, status, reason, schema, consent, block, resultEpoch, caller, pendingArm)
        }
    }
}
