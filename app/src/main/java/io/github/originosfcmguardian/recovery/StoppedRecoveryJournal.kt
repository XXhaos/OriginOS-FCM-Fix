package io.github.originosfcmguardian.recovery

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class StoppedRecoveryOutcome { FLAG_CLEARED, STILL_STOPPED, NO_WRITE_NEEDED, BLOCKED, UNCONFIRMED }
enum class StoppedRecoveryStage { PREPARED, CLAIMED, FINISHED, RECONCILED }
enum class StoppedRecoverySource { MANUAL, AUTOMATIC }

data class StoppedRecoveryRecord(
    val requestId: Long,
    val identity: SelectedTargetIdentity,
    val createdAtMillis: Long,
    val baselineAtMillis: Long,
    val fingerprint: String,
    val currentUser: Int,
    val baselineStopped: Boolean,
    val confirmedManualAction: Boolean,
    val acknowledgedRuntimeRisk: Boolean,
    val stage: StoppedRecoveryStage,
    val outcome: StoppedRecoveryOutcome? = null,
    val detail: String = "",
    val source: StoppedRecoverySource = StoppedRecoverySource.MANUAL,
    val consentId: String? = null,
    val consentedAtMillis: Long? = null,
    val consentVersion: Int? = null,
) {
    val requiresReconcile: Boolean get() = stage == StoppedRecoveryStage.PREPARED || stage == StoppedRecoveryStage.CLAIMED
}

data class StoppedRecoveryResult(val outcome: StoppedRecoveryOutcome, val detail: String, val record: StoppedRecoveryRecord? = null,
    /** Ephemeral command provenance; never inferred from old journal receipts or reconciliation. */
    val commandSucceeded: Boolean = false,
    /** Local admission refusal only; never encoded by the RPC or journal codecs. */
    internal val admissionBusy: Boolean = false,
    /** Process-local bind exception before any action; never encoded by RPC or journal codecs. */
    internal val bindingFailedBeforeAction: Boolean = false)

internal data class RecoveryJournalState(val claimedHighWater: Long = 0, val latest: StoppedRecoveryRecord? = null)
internal interface RecoveryJournalStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    /** Persist an uncertainty marker after ambiguous commit/readback. Never silently clear it. */
    fun markUncertain()
}

/** Latest bounded receipt plus a monotonic server-claim watermark. Never reset on rebind/restart. */
internal class StoppedRecoveryJournal(private val storage: RecoveryJournalStorage, private val lock: Any = Any()) {
    private var poisoned = false
    fun load(): RecoveryJournalState = synchronized(lock) { read() }
    fun prepare(identity: SelectedTargetIdentity, baseline: StoppedTargetObservation.Known, confirmed: Boolean): StoppedRecoveryRecord = synchronized(lock) {
        check(confirmed)
        prepare(identity, baseline, null)
    }
    fun prepareAutomatically(identity: SelectedTargetIdentity, baseline: StoppedTargetObservation.Known,
        consent: AutomaticRecoveryConsent): StoppedRecoveryRecord = synchronized(lock) {
        consent.validate()
        prepare(identity, baseline, consent)
    }
    private fun prepare(identity: SelectedTargetIdentity, baseline: StoppedTargetObservation.Known,
        consent: AutomaticRecoveryConsent?): StoppedRecoveryRecord {
        val state = read()
        check(state.latest?.requiresReconcile != true) { "先只读复检上次待确认操作" }
        check(baseline.identity == identity && baseline.stopped && baseline.hasVerifiedPackageUid)
        check(if (consent?.version == 4 || identity.packageName != "org.telegram.messenger") baseline.qualifiedRecipient else true)
        check(consent == null || consent.version == 4 || identity.packageName == "org.telegram.messenger")
        val id = Math.addExact(maxOf(state.claimedHighWater, state.latest?.requestId ?: 0), 1)
        val record = StoppedRecoveryRecord(id, identity, System.currentTimeMillis(), baseline.observedAtMillis,
            baseline.fingerprint, baseline.currentUser, true, consent == null, true, StoppedRecoveryStage.PREPARED,
            source = if (consent == null) StoppedRecoverySource.MANUAL else StoppedRecoverySource.AUTOMATIC,
            consentId = consent?.id, consentedAtMillis = consent?.consentedAtMillis, consentVersion = consent?.version)
        commit(state.copy(latest = record))
        return record
    }
    fun claim(id: Long): Boolean = synchronized(lock) {
        val state = read()
        val record = state.latest ?: return@synchronized false
        if (id <= state.claimedHighWater || record.requestId != id || record.stage != StoppedRecoveryStage.PREPARED) return@synchronized false
        commit(RecoveryJournalState(id, record.copy(stage = StoppedRecoveryStage.CLAIMED)))
        true
    }
    fun finish(id: Long, outcome: StoppedRecoveryOutcome, detail: String, reconciled: Boolean = false): StoppedRecoveryRecord = synchronized(lock) {
        val state = read()
        val current = requireNotNull(state.latest)
        check(current.requestId == id && current.requiresReconcile)
        check(outcome != StoppedRecoveryOutcome.UNCONFIRMED)
        val record = current.copy(stage = if (reconciled) StoppedRecoveryStage.RECONCILED else StoppedRecoveryStage.FINISHED,
            outcome = outcome, detail = detail.take(192))
        commit(state.copy(latest = record))
        record
    }
    private fun read(): RecoveryJournalState {
        check(!poisoned) { "恢复记录提交不确定，禁止写入" }
        return storage.read()?.let(RecoveryJournalCodec::decode) ?: RecoveryJournalState()
    }
    private fun commit(state: RecoveryJournalState) {
        val bytes = RecoveryJournalCodec.encode(state)
        try {
            storage.write(bytes)
            check(storage.read()?.contentEquals(bytes) == true) { "恢复记录写入后核验失败" }
        } catch (failure: Throwable) {
            poisoned = true
            runCatching { storage.markUncertain() }
            throw failure
        }
    }
}

internal object RecoveryJournalCodec {
    const val MAX_BYTES = 8192
    fun encode(state: RecoveryJournalState): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            val expanded = state.latest?.let { it.consentVersion == 4 || it.identity.packageName != "org.telegram.messenger" } == true
            out.writeInt(if (expanded) 3 else 2); out.writeLong(state.claimedHighWater); out.writeBoolean(state.latest != null)
            state.latest?.let { r ->
                out.writeLong(r.requestId); out.writeUTF(TargetSelectionCodec.encode(listOf(r.identity)))
                out.writeLong(r.createdAtMillis); out.writeLong(r.baselineAtMillis); out.writeUTF(r.fingerprint); out.writeInt(r.currentUser)
                out.writeBoolean(r.baselineStopped); out.writeBoolean(r.confirmedManualAction); out.writeBoolean(r.acknowledgedRuntimeRisk)
                out.writeUTF(r.stage.name); out.writeUTF(r.outcome?.name.orEmpty()); out.writeUTF(r.detail)
                out.writeUTF(r.source.name); out.writeUTF(r.consentId.orEmpty())
                out.writeLong(r.consentedAtMillis ?: 0); out.writeInt(r.consentVersion ?: 0)
            }
        }
        return output.toByteArray().also { decode(it) }
    }
    fun decode(bytes: ByteArray): RecoveryJournalState {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val schema = input.readInt(); require(schema in 1..3)
            val high = input.readLong(); require(high >= 0)
            val record = if (!input.readBoolean()) null else {
                val id = input.readLong()
                val identities = TargetSelectionCodec.decode(input.readUTF()) as? TargetSelectionDecodeResult.Valid ?: error("身份记录损坏")
                var r = StoppedRecoveryRecord(id, identities.values.single(), input.readLong(), input.readLong(), input.readUTF(), input.readInt(),
                    input.readBoolean(), input.readBoolean(), input.readBoolean(), StoppedRecoveryStage.valueOf(input.readUTF()),
                    input.readUTF().let { if (it.isEmpty()) null else StoppedRecoveryOutcome.valueOf(it) }, input.readUTF())
                if (schema >= 2) r = r.copy(source = StoppedRecoverySource.valueOf(input.readUTF()),
                    consentId = input.readUTF().ifEmpty { null },
                    consentedAtMillis = input.readLong().let { if (it == 0L) null else it },
                    consentVersion = input.readInt().let { if (it == 0) null else it })
                require(r.requestId > 0 && r.createdAtMillis > 0 && r.baselineAtMillis > 0)
                require(r.identity.signerSha256.size <= 16)
                require(if (schema < 3) r.identity.packageName == "org.telegram.messenger" else isRecoveryTargetPackage(r.identity.packageName))
                require(validRuntimeFingerprint(r.fingerprint) && r.currentUser == 0 && r.baselineStopped && r.acknowledgedRuntimeRisk)
                when (r.source) {
                    StoppedRecoverySource.MANUAL -> require(r.confirmedManualAction && r.consentId == null && r.consentedAtMillis == null && r.consentVersion == null)
                    StoppedRecoverySource.AUTOMATIC -> {
                        require(!r.confirmedManualAction)
                        require(if (schema == 3) r.consentVersion == 4 else r.consentVersion in 1..2)
                        AutomaticRecoveryConsent(requireNotNull(r.consentId), requireNotNull(r.consentedAtMillis), requireNotNull(r.consentVersion)).validate()
                    }
                }
                require(r.detail.length <= 192 && (r.requiresReconcile == (r.outcome == null)))
                require(r.outcome != StoppedRecoveryOutcome.UNCONFIRMED)
                require(if (r.stage == StoppedRecoveryStage.CLAIMED) high == id else high <= id)
                require(r.stage != StoppedRecoveryStage.PREPARED || high < id)
                r
            }
            require(input.available() == 0 && (record != null || high == 0L))
            RecoveryJournalState(high, record)
        }
    }
}

internal object AndroidRecoveryJournal {
    private val locks = ConcurrentHashMap<String, Any>()
    fun open(context: Context): StoppedRecoveryJournal {
        val base = File(context.applicationContext.noBackupFilesDir, "stopped-recovery-v1.bin")
        val atomic = AtomicFile(base)
        val uncertain = File(base.path + ".blocked")
        val storage = object : RecoveryJournalStorage {
            override fun read(): ByteArray? {
                if (uncertain.exists()) throw IOException("恢复记录提交不确定，禁止写入")
                if (File(base.path + ".bak").exists() || File(base.path + ".new").exists()) throw IOException("恢复记录未完整提交，禁止写入")
                if (!base.exists()) return null
                return atomic.openRead().use { input ->
                    val length = input.channel.size()
                    require(length in 1..RecoveryJournalCodec.MAX_BYTES.toLong())
                    val bytes = ByteArray(length.toInt())
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        require(count > 0)
                        offset += count
                    }
                    require(input.read() == -1)
                    bytes
                }
            }
            override fun write(bytes: ByteArray) {
                val stream = atomic.startWrite()
                try { stream.write(bytes); stream.flush(); stream.fd.sync(); atomic.finishWrite(stream) }
                catch (failure: Throwable) { runCatching { atomic.failWrite(stream) }; throw failure }
            }
            override fun markUncertain() {
                FileOutputStream(uncertain).use { stream -> stream.write(1); stream.flush(); stream.fd.sync() }
            }
        }
        return StoppedRecoveryJournal(storage, locks.computeIfAbsent(base.canonicalPath) { Any() })
    }
}
