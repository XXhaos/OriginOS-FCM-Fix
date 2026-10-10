package io.github.originosfcmguardian.autoprotection

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Diagnostic-only FIFO. No main-thread disk/Binder IO, timers, restart or authority decisions. */
internal object ProtectionLifecycleDiagnostics {
    private val dropped = AtomicLong(0)
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "guardian-lifecycle-journal").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var storage: AtomicProtectionLifecycleStorage? = null
    @Volatile private var writeFailed = false

    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val app = context.applicationContext
        storage = AtomicProtectionLifecycleStorage(app.noBackupFilesDir)
        val start = event(ProtectionLifecycleEventKind.PROCESS_STARTED)
        enqueue {
            val current = requireNotNull(storage)
            val prior = current.read()
            val exits = runCatching {
                val now = System.currentTimeMillis()
                app.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 64)
                    .filter { it.packageUid == Process.myUid() && it.processName == app.packageName &&
                        it.timestamp > 0 && it.timestamp <= now && it.pid > 0 && it.reason in 0..100 }
                    .map { ProtectionLifecycleEvent(it.timestamp, 0, it.pid,
                        ProtectionLifecycleEventKind.SYSTEM_PROCESS_EXIT, systemExitReason = it.reason,
                        systemExitStatus = it.status) }
            }.getOrNull()
            val begun = beginProtectionProcess(mergeProtectionSystemExits(prior, exits), start)
            val pendingDrops = dropped.get()
            current.write(begun.copy(droppedEvents = Math.addExact(begun.droppedEvents, pendingDrops)))
            dropped.addAndGet(-pendingDrops)
        }
    }

    fun record(kind: ProtectionLifecycleEventKind, reason: ProtectionLifecycleReason = ProtectionLifecycleReason.NONE) {
        if (storage == null) return
        val captured = event(kind, reason)
        enqueue {
            val current = requireNotNull(storage)
            val pendingDrops = dropped.get()
            current.write(appendProtectionLifecycle(current.read(), captured, pendingDrops))
            dropped.addAndGet(-pendingDrops)
        }
    }

    fun recordFailure(kind: ProtectionLifecycleEventKind, error: Throwable) = record(kind, when (error) {
        is SecurityException -> ProtectionLifecycleReason.SECURITY_ERROR
        is IOException -> ProtectionLifecycleReason.IO_ERROR
        is TimeoutException -> ProtectionLifecycleReason.TIMEOUT
        is IllegalStateException -> ProtectionLifecycleReason.STATE_ERROR
        else -> ProtectionLifecycleReason.OTHER_EXCEPTION
    })

    fun recordPause(detail: String) = record(ProtectionLifecycleEventKind.RUNTIME_PAUSED, when {
        "Shizuku" in detail || "权限" in detail -> ProtectionLifecycleReason.PRIVILEGE
        "前台服务" in detail -> ProtectionLifecycleReason.FOREGROUND_SERVICE
        "身份" in detail || "应用变化" in detail -> ProtectionLifecycleReason.IDENTITY
        "记录" in detail || "回执" in detail || "复检" in detail -> ProtectionLifecycleReason.JOURNAL
        "授权" in detail -> ProtectionLifecycleReason.AUTHORITY
        "恢复" in detail -> ProtectionLifecycleReason.RECOVERY_RESULT
        else -> ProtectionLifecycleReason.UNKNOWN
    })

    /** IO caller only; FIFO barrier includes events already queued when export is requested. */
    fun readSnapshot(): ProtectionLifecycleSnapshot {
        val result = CompletableFuture<ProtectionLifecycleSnapshot>()
        try {
            worker.execute {
                result.complete(runCatching {
                    val current = requireNotNull(storage)
                    current.read().let { value -> value.copy(droppedEvents = Math.addExact(value.droppedEvents, dropped.get()),
                        availability = if (writeFailed) ProtectionLifecycleAvailability.UNAVAILABLE else value.availability) }
                }.getOrElse { error -> ProtectionLifecycleSnapshot(availability =
                    if (error is IllegalArgumentException) ProtectionLifecycleAvailability.CORRUPT else ProtectionLifecycleAvailability.UNAVAILABLE,
                    droppedEvents = dropped.get()) })
            }
            return result.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return ProtectionLifecycleSnapshot(availability = ProtectionLifecycleAvailability.UNAVAILABLE, droppedEvents = dropped.get())
        }
    }

    private fun event(kind: ProtectionLifecycleEventKind, reason: ProtectionLifecycleReason = ProtectionLifecycleReason.NONE) =
        ProtectionLifecycleEvent(System.currentTimeMillis().coerceAtLeast(1), SystemClock.elapsedRealtime(), Process.myPid(), kind, reason)

    private fun enqueue(action: () -> Unit) {
        try {
            worker.execute {
                try { action() } catch (_: Exception) { writeFailed = true; dropped.incrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }
}

/** A malformed journal is preserved, not replaced by a misleading empty/healthy record. */
internal class AtomicProtectionLifecycleStorage(directory: File) {
    private val file = AtomicFile(File(directory, "protection-lifecycle-v1.bin"))
    fun read(): ProtectionLifecycleSnapshot {
        val input = try { file.openRead() } catch (missing: FileNotFoundException) {
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists())
            return ProtectionLifecycleSnapshot()
        }
        return input.use {
            val bytes = ByteArray(ProtectionLifecycleCodec.MAX_BYTES + 1)
            var length = 0
            while (length < bytes.size) {
                val count = it.read(bytes, length, bytes.size - length)
                if (count < 0) break
                require(count > 0); length += count
            }
            require(length <= ProtectionLifecycleCodec.MAX_BYTES)
            ProtectionLifecycleCodec.decode(bytes.copyOf(length))
        }
    }
    fun write(value: ProtectionLifecycleSnapshot) {
        val bytes = ProtectionLifecycleCodec.encode(value)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
