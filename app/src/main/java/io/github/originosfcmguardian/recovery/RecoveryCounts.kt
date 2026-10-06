package io.github.originosfcmguardian.recovery

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TargetRecoveryCounts(
    val today: Int = 0,
    val week: Int = 0,
    // Post-commit asynchronous receipts can be lost on process death, so these are lower bounds.
    val todayComplete: Boolean = false,
    val weekComplete: Boolean = false,
)

data class RecoveryCountsUiState(
    val available: Boolean = false,
    val byIdentity: Map<SelectedTargetIdentity, TargetRecoveryCounts> = emptyMap(),
    val sinceMillis: Long? = null,
    val detail: String = "统计加载中",
    val calculatedAtMillis: Long? = null,
    val zoneId: String? = null,
)

internal data class RecoveryCountReceipt(
    val requestId: Long,
    val identity: SelectedTargetIdentity,
    val confirmedAtMillis: Long,
)

/** Only a new successful command plus verified, durably finished result creates a receipt. */
internal fun confirmedRecoveryReceipt(result: StoppedRecoveryResult, confirmedAtMillis: Long): RecoveryCountReceipt? {
    val record = result.record ?: return null
    if (!result.commandSucceeded || result.outcome != StoppedRecoveryOutcome.FLAG_CLEARED ||
        record.stage != StoppedRecoveryStage.FINISHED || record.outcome != StoppedRecoveryOutcome.FLAG_CLEARED ||
        !record.baselineStopped || record.requestId <= 0 || confirmedAtMillis <= 0 ||
        confirmedAtMillis < record.createdAtMillis) return null
    return RecoveryCountReceipt(record.requestId, record.identity.copy(signerSha256 = record.identity.signerSha256.toList()),
        confirmedAtMillis)
}

internal data class RecoveryCountsLedger(
    val sinceMillis: Long,
    val requestHighWater: Long = 0,
    val lastObservedAtMillis: Long = sinceMillis,
    val clockUncertain: Boolean = false,
    val receipts: List<RecoveryCountReceipt> = emptyList(),
)

internal interface RecoveryCountsStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun markUnavailable()
}

/** Independent observational storage: failure never touches the recovery authorization journal. */
internal class RecoveryCountsStore(private val storage: RecoveryCountsStorage, private val lock: Any = Any()) {
    private var unavailable = false

    fun record(receipt: RecoveryCountReceipt): Boolean = synchronized(lock) {
        if (unavailable) return@synchronized false
        try {
            require(receipt.requestId > 0 && receipt.confirmedAtMillis > 0)
            val state = read(receipt.confirmedAtMillis)
            // Requests are submitted by the single-flight controller to one FIFO writer. The
            // persistent watermark also excludes old/repeated completions after capacity pruning.
            if (receipt.requestId <= state.requestHighWater) return@synchronized true
            val next = state.copy(requestHighWater = receipt.requestId,
                lastObservedAtMillis = maxOf(state.lastObservedAtMillis, receipt.confirmedAtMillis),
                // A UI snapshot can legitimately run after confirmation but before this FIFO task.
                clockUncertain = state.clockUncertain ||
                    (state.receipts.lastOrNull()?.confirmedAtMillis?.let { receipt.confirmedAtMillis < it } == true),
                receipts = (state.receipts + receipt).takeLast(RecoveryCountsCodec.MAX_RECEIPTS))
            commit(next)
            true
        } catch (_: Exception) { fail(); false }
    }

    fun snapshot(identities: List<SelectedTargetIdentity>, nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()): RecoveryCountsUiState = synchronized(lock) {
        if (unavailable) return@synchronized unavailableState()
        try {
            require(nowMillis > 0)
            val previous = read(nowMillis)
            val state = previous.copy(lastObservedAtMillis = maxOf(previous.lastObservedAtMillis, nowMillis),
                clockUncertain = previous.clockUncertain || nowMillis < previous.lastObservedAtMillis)
            if (state != previous) commit(state)
            val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val counts = identities.distinct().associateWith { identity ->
                val matching = state.receipts.filter { it.identity == identity && it.confirmedAtMillis <= nowMillis }
                TargetRecoveryCounts(today = matching.count { it.confirmedAtMillis >= today },
                    week = matching.count { it.confirmedAtMillis >= week })
            }
            RecoveryCountsUiState(true, counts, state.sinceMillis,
                if (state.clockUncertain || state.receipts.any { it.confirmedAtMillis > nowMillis })
                    "时间发生变化；仅显示已保存且不在未来的确认次数，可能少计"
                else "从启用统计起累计已保存确认，可能少计；最多保留最近512条，非通知送达次数",
                calculatedAtMillis = nowMillis, zoneId = zone.id)
        } catch (_: Exception) { fail(); unavailableState() }
    }

    fun markUnavailable() = synchronized(lock) { fail() }

    private fun read(nowMillis: Long): RecoveryCountsLedger = storage.read()?.let(RecoveryCountsCodec::decode)
        ?: RecoveryCountsLedger(nowMillis).also(::commit)

    private fun commit(state: RecoveryCountsLedger) {
        val bytes = RecoveryCountsCodec.encode(state)
        storage.write(bytes)
        check(storage.read()?.contentEquals(bytes) == true)
    }

    private fun fail() {
        unavailable = true
        runCatching { storage.markUnavailable() }
    }

    private fun unavailableState() = RecoveryCountsUiState(detail = "统计记录不可用或不完整；恢复操作不受影响")
}

internal object RecoveryCountsCodec {
    const val MAX_RECEIPTS = 512
    const val MAX_BYTES = 1024 * 1024

    fun encode(state: RecoveryCountsLedger): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeLong(state.sinceMillis); out.writeLong(state.requestHighWater)
            out.writeLong(state.lastObservedAtMillis); out.writeBoolean(state.clockUncertain)
            out.writeInt(state.receipts.size)
            state.receipts.forEach { receipt ->
                out.writeLong(receipt.requestId)
                out.writeUTF(TargetSelectionCodec.encode(listOf(receipt.identity)))
                out.writeLong(receipt.confirmedAtMillis)
            }
        }
        return buffer.toByteArray().also { decode(it) }
    }

    fun decode(bytes: ByteArray): RecoveryCountsLedger {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val since = input.readLong(); val high = input.readLong(); val observed = input.readLong()
            val uncertain = input.readBoolean(); val size = input.readInt()
            require(since > 0 && high >= 0 && observed >= since && size in 0..MAX_RECEIPTS)
            val receipts = List(size) {
                val request = input.readLong()
                val selected = TargetSelectionCodec.decode(input.readUTF()) as? TargetSelectionDecodeResult.Valid
                    ?: error("invalid statistics identity")
                val identity = selected.values.single()
                require(identity.signerSha256.size <= 16)
                val at = input.readLong()
                require(request in 1..high && at > 0 && at <= observed)
                RecoveryCountReceipt(request, identity, at)
            }
            require(receipts.zipWithNext().all { (before, after) -> before.requestId < after.requestId })
            require(input.available() == 0)
            RecoveryCountsLedger(since, high, observed, uncertain, receipts)
        }
    }
}

/** One bounded FIFO for optional receipts. Recovery submits without waiting or taking a disk lock. */
internal object AndroidRecoveryCounts {
    private class Backend(val store: RecoveryCountsStore) {
        val failed = AtomicBoolean(false)
    }
    private val backends = ConcurrentHashMap<String, Backend>()
    private val failedSubmission = AtomicBoolean(false)
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "guardian-recovery-counts").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    private fun backend(context: Context): Backend {
        val base = File(context.applicationContext.noBackupFilesDir, "confirmed-recovery-counts-v1.bin")
        return backends.computeIfAbsent(base.absolutePath) {
            val atomic = AtomicFile(base)
            val blocked = File(base.path + ".unavailable")
            Backend(RecoveryCountsStore(object : RecoveryCountsStorage {
                override fun read(): ByteArray? {
                    check(!blocked.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
                    if (!base.exists()) return null
                    return atomic.openRead().use { stream ->
                        val size = stream.channel.size()
                        require(size in 1..RecoveryCountsCodec.MAX_BYTES.toLong())
                        val bytes = ByteArray(size.toInt())
                        DataInputStream(stream).readFully(bytes)
                        require(stream.read() == -1)
                        bytes
                    }
                }
                override fun write(bytes: ByteArray) {
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); stream.flush(); stream.fd.sync(); atomic.finishWrite(stream) }
                    catch (failure: Exception) { runCatching { atomic.failWrite(stream) }; throw failure }
                }
                override fun markUnavailable() {
                    FileOutputStream(blocked).use { stream -> stream.write(1); stream.flush(); stream.fd.sync() }
                }
            }))
        }
    }

    fun submit(context: Context, receipt: RecoveryCountReceipt) {
        // Even Context.noBackupFilesDir may ensure a directory on disk: resolve it on the writer.
        try {
            writer.execute {
                runCatching {
                    val backend = backend(context)
                    if (failedSubmission.get() || backend.failed.get()) backend.store.markUnavailable()
                    else if (!backend.store.record(receipt)) backend.failed.set(true)
                }.onFailure { failedSubmission.set(true) }
                revision.value = revision.value + 1
            }
        } catch (_: Exception) {
            failedSubmission.set(true)
            revision.value = revision.value + 1
            // An already queued task or a later IO snapshot persists the independent failure marker.
        }
    }

    fun snapshot(context: Context, identities: List<SelectedTargetIdentity>): RecoveryCountsUiState {
        val backend = backend(context)
        if (failedSubmission.get() || backend.failed.get()) backend.store.markUnavailable()
        return backend.store.snapshot(identities)
    }
}
