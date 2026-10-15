package io.github.originosfcmguardian.autoprotection

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryRuntime
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.util.UUID

internal data class ProtectionProcStatus(val rssKiB: Long? = null, val threads: Long? = null)

/** Exact bounded fields only; absent, duplicate, malformed and overflow values are unavailable. */
internal fun parseProtectionProcStatus(text: String): ProtectionProcStatus {
    require(text.length <= 16_384)
    fun field(name: String, unit: String): Long? {
        val lines = text.lineSequence().filter { it.startsWith("$name:") }.toList()
        if (lines.size != 1) return null
        return Regex("^$name:\\s+([0-9]{1,19})$unit\\s*$").matchEntire(lines.single())
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }
    return ProtectionProcStatus(field("VmRSS", "\\s+kB"), field("Threads", ""))
}

internal class ProtectionResourceSampler(private val context: Context) {
    private val processInstance = UUID.randomUUID().toString()
    private val startedElapsed = Process.getStartElapsedRealtime()
    private val startedEpoch = (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedElapsed)).coerceAtLeast(1)

    /** Called only by the existing diagnostics worker, never the automatic protection caller. */
    fun capture(pollIterations: Long): ProtectionResourceSample {
        val proc = runCatching {
            File("/proc/self/status").inputStream().use { input ->
                val bytes = ByteArray(16_385)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    check(count > 0); length += count
                }
                require(length <= 16_384)
                parseProtectionProcStatus(String(bytes, 0, length, Charsets.US_ASCII))
            }
        }.getOrNull()
        val heap = runCatching { Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) to it.maxMemory() } }.getOrNull()
        val memory = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        }.getOrNull()
        val automatic = AutomaticProtectionRuntime.state.value
        val fcm = FcmProtectionRuntime.state.value
        val heartbeat = GmsAutoHeartbeatRuntime.state.value
        // This is the actual latest Sample, including repeated same-state captures. The event ring
        // intentionally deduplicates state changes and cannot establish observation recency.
        val observation = FcmRecoveryRuntime.latestObservation.value as? GmsConnectionObservation.Sample
        val action = FcmRecoveryRuntime.state.value.events.lastOrNull {
            it.action == FcmRecoveryEventAction.REQUEST_RESULT || it.action == FcmRecoveryEventAction.READBACK
        }
        val reader = AutomaticTargetReader.diagnosticGauges()
        val epoch = System.currentTimeMillis().coerceAtLeast(1)
        val elapsed = SystemClock.elapsedRealtime()
        return ProtectionResourceSample(processInstance, Process.myPid(), startedEpoch, startedElapsed, epoch, elapsed,
            versionCode = BuildConfig.VERSION_CODE.toLong(), rssKiB = proc?.rssKiB, threads = proc?.threads,
            heapUsedBytes = heap?.first?.takeIf { it >= 0 }, heapMaxBytes = heap?.second?.takeIf { it > 0 },
            cpuTimeMillis = runCatching { Process.getElapsedCpuTime().takeIf { it >= 0 } }.getOrNull(),
            nativeAllocatedBytes = runCatching { Debug.getNativeHeapAllocatedSize().takeIf { it >= 0 } }.getOrNull(),
            systemAvailableBytes = memory?.availMem?.takeIf { it >= 0 }, systemThresholdBytes = memory?.threshold?.takeIf { it >= 0 },
            systemLowMemory = memory?.lowMemory,
            interactive = runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrNull(),
            selectedCount = if (automatic.configurationAvailable) automatic.targets.count { it.configuredSelection } else null,
            automaticBusy = automatic.busy,
            automaticLastCheckedAtMillis = automatic.lastCheckedAtMillis, pollIterations = pollIterations,
            readerActive = reader.actualActive, readerPoolSize = reader.poolSize,
            readerTimeouts = reader.timeouts, readerRejections = reader.rejections,
            fcmObservationAtMillis = observation?.observedAtMillis, fcmObservationElapsedMillis = observation?.elapsedRealtimeMillis,
            fcmService = observation?.gcmService?.state?.name, fcmCompleteness = observation?.gcmService?.completeness?.name,
            fcmSocket = observation?.observation?.name, fcmConfigured = fcm.configured, recoveryReady = fcm.recoveryReady,
            maintenance = fcm.maintenance.name, heartbeatConfigured = heartbeat.configured, heartbeatActive = heartbeat.active,
            heartbeatPaused = heartbeat.paused, heartbeatStopPending = heartbeat.stopPending,
            heartbeatResumeBlocked = heartbeat.resumeBlocked, heartbeatRequiresConfirmation = heartbeat.requiresConfirmation,
            heartbeatLastStatus = heartbeat.lastStatus?.name, recoveryAction = action?.action?.name, recoveryActionAtMillis = action?.atMillis)
    }
}

/** Independent sidecar. Never overwrite corrupted bytes or share lifecycle/authority state. */
internal class AtomicProtectionResourceStorage(directory: File) {
    private val file = AtomicFile(File(directory, "protection-resource-v1.bin"))
    fun read(): ProtectionResourceSnapshot = readProtectionResourceStorage({ file.openRead() }) {
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()
    }
    fun write(value: ProtectionResourceSnapshot) {
        val bytes = ProtectionResourceCodec.encode(value)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}

internal class ProtectionResourceCorruption(cause: Exception) : IOException(cause)

/** Shared production read path is pure JVM-testable; malformed artifacts are never rewritten. */
internal fun readProtectionResourceStorage(openRead: () -> InputStream, hasArtifacts: () -> Boolean): ProtectionResourceSnapshot {
    val input = try { openRead() } catch (missing: FileNotFoundException) {
        check(!hasArtifacts())
        return ProtectionResourceSnapshot()
    }
    return input.use {
        val bytes = ByteArray(ProtectionResourceCodec.MAX_BYTES + 1)
        var length = 0
        while (length < bytes.size) {
            val count = it.read(bytes, length, bytes.size - length)
            if (count < 0) break
            check(count > 0); length += count
        }
        try {
            require(length <= ProtectionResourceCodec.MAX_BYTES)
            ProtectionResourceCodec.decode(bytes.copyOf(length))
        } catch (error: Exception) { throw ProtectionResourceCorruption(error) }
    }
}
