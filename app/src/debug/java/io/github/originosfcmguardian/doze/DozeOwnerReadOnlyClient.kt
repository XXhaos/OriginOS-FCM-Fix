package io.github.originosfcmguardian.doze

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import io.github.originosfcmguardian.BuildConfig
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class DozeOwnerReadOnlyClientResult(val summary: DozeOwnerReadOnlySummary, val cleanupCompleted: Boolean)
internal data class DozeEffectiveOwnerReadOnlyClientResult(val summary: DozeEffectiveOwnerReadOnlySummary, val cleanupCompleted: Boolean)

/** Opt-in harness caller only. A shared 40s deadline includes binding, read and cleanup. */
internal class DozeOwnerReadOnlyClient(private val context: Context) {
    fun readEffectiveOwners(): DozeEffectiveOwnerReadOnlyClientResult {
        val requested = System.currentTimeMillis()
        val started = System.nanoTime()
        val deadline = started + 40_000L * 1_000_000L
        val operationDeadline = deadline - 2_000L * 1_000_000L
        fun rejected(reason: DozeEffectiveOwnerRejection) = DozeEffectiveOwnerReadOnlySummary(requested,
            System.currentTimeMillis(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0),
            DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.UNKNOWN, reason))
        if (!readGate.tryEnter()) return DozeEffectiveOwnerReadOnlyClientResult(rejected(DozeEffectiveOwnerRejection.BUSY), true)
        val active = AtomicBoolean(true)
        var binding: Binding? = null
        var summary = rejected(DozeEffectiveOwnerRejection.READ_FAILED)
        var cleanup = true
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            check(BuildConfig.DEBUG && Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
            check(context.packageName == GUARDIAN_DOZE_READ_PACKAGE && Process.myUid() in 10_000..99_999)
            check(readGate.bounded(operationDeadline) {
                Shizuku.pingBinder() && Shizuku.getUid() == 2_000 &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            })
            binding = Binding(context.applicationContext, active)
            val service = binding.bind(operationDeadline)
            val result = readGate.bounded(operationDeadline) { service.readEffectiveOwners(Binder()) }
            check(active.get() && remaining(operationDeadline) > 0)
            if (result?.getBoolean("uncertainBinderExit") == true) readGate.markTerminal()
            summary = decodeEffectiveOwnerReadOnlyBundle(requireNotNull(result))
            if (summary.uncertainBinderExit) readGate.markTerminal()
            check(summary.requestedAtMillis >= requested && summary.finishedAtMillis <= System.currentTimeMillis())
        } catch (failure: Throwable) {
            if (failure is InterruptedException || remaining(operationDeadline) <= 0) readGate.markTerminal()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            summary = rejected(if (remaining(deadline) <= 2_000) DozeEffectiveOwnerRejection.DEADLINE else DozeEffectiveOwnerRejection.READ_FAILED)
        } finally {
            active.set(false)
            cleanup = binding?.close(deadline) ?: true
            readGate.finish(cleanup)
        }
        return DozeEffectiveOwnerReadOnlyClientResult(summary, cleanup)
    }

    fun read(): DozeOwnerReadOnlyClientResult {
        val requested = System.currentTimeMillis()
        val started = System.nanoTime()
        val deadline = started + 40_000L * 1_000_000L
        val operationDeadline = deadline - 2_000L * 1_000_000L
        fun rejected(code: DozeOwnerReadOnlyCode) = DozeOwnerReadOnlySummary(requested,
            System.currentTimeMillis(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0), code)
        if (!readGate.tryEnter()) return DozeOwnerReadOnlyClientResult(rejected(DozeOwnerReadOnlyCode.BUSY), true)
        val active = AtomicBoolean(true)
        var binding: Binding? = null
        var summary = rejected(DozeOwnerReadOnlyCode.REJECTED)
        var cleanup = true
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            check(BuildConfig.DEBUG && Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
            check(context.packageName == GUARDIAN_DOZE_READ_PACKAGE && Process.myUid() in 10_000..99_999)
            check(readGate.bounded(operationDeadline) {
                Shizuku.pingBinder() && Shizuku.getUid() == 2_000 &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            })
            binding = Binding(context.applicationContext, active)
            val service = binding.bind(operationDeadline)
            val lifetime = Binder()
            val result = readGate.bounded(operationDeadline) { service.read(lifetime) }
            check(active.get() && remaining(operationDeadline) > 0)
            summary = decodeOwnerReadOnlyBundle(requireNotNull(result))
            check(summary.requestedAtMillis >= requested && summary.finishedAtMillis <= System.currentTimeMillis())
        } catch (failure: Throwable) {
            if (failure is InterruptedException || remaining(operationDeadline) <= 0) readGate.markTerminal()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            summary = rejected(if (remaining(deadline) <= 2_000) DozeOwnerReadOnlyCode.DEADLINE else DozeOwnerReadOnlyCode.REJECTED)
        } finally {
            active.set(false)
            cleanup = binding?.close(deadline) ?: true
            readGate.finish(cleanup)
        }
        return DozeOwnerReadOnlyClientResult(summary, cleanup)
    }

    private class Binding(context: Context, private val active: AtomicBoolean) {
        private val token = UUID.randomUUID().toString().replace("-", "").take(16)
        private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DozeOwnerReadOnlyUserService::class.java.name))
            .daemon(false).tag("guardian-owner-read-only-v1-$token")
            .processNameSuffix("guardian_owner_read_only_$token").debuggable(true).version(BuildConfig.VERSION_CODE)
        private val connected = CountDownLatch(1)
        private val remote = AtomicReference<IDozeOwnerReadOnly?>()
        private val cleanupStarted = AtomicBoolean(false)
        private val cleanupTasks = AtomicReference<List<Future<*>>?>()
        private val cleanupPublished = CountDownLatch(1)
        private val cleanupFailed = AtomicBoolean(false)
        private val cleanupExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "guardian-owner-read-cleanup").apply { isDaemon = true }
        }
        private val lifecycle = DozeBindLifecycle { Shizuku.unbindUserService(args, connection, true) }
        private val death = IBinder.DeathRecipient { active.set(false); connected.countDown(); requestClose() }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!active.get()) { requestClose(); return }
                try {
                    binder.linkToDeath(death, 0)
                    val service = IDozeOwnerReadOnly.Stub.asInterface(binder)
                    remote.set(service)
                    if (!active.get()) {
                        remote.compareAndSet(service, null)
                        runCatching { binder.unlinkToDeath(death, 0) }
                        requestClose()
                    }
                } catch (_: Throwable) { active.set(false); requestClose() }
                finally { connected.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                active.set(false)
                connected.countDown()
                requestClose()
            }
        }

        fun bind(deadline: Long): IDozeOwnerReadOnly {
            val bindDeadline = minOf(deadline, System.nanoTime() + 5_000L * 1_000_000L)
            lifecycle.beforeDispatch()
            readGate.bounded(bindDeadline) {
                Shizuku.bindUserService(args, connection)
                // Shared lifecycle closes a late successful dispatch even after public timeout.
                lifecycle.afterReturn()
            }
            val arrived = connected.await(remaining(bindDeadline), TimeUnit.MILLISECONDS)
            if (!arrived) readGate.markTerminal()
            check(arrived && active.get())
            return requireNotNull(remote.get())
        }

        private fun requestClose() {
            active.set(false)
            if (!cleanupStarted.compareAndSet(false, true)) return
            val service = remote.getAndSet(null)
            // Independent lanes: a blocked cancel IPC cannot prevent unbind/removal dispatch.
            try {
                val cancel = cleanupExecutor.submit { readGate.trackCleanup { if (service != null) service.cancel() } }
                val unbind = cleanupExecutor.submit {
                    readGate.trackCleanup {
                        service?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
                        lifecycle.close()
                    }
                }
                cleanupTasks.set(listOf(cancel, unbind))
            } catch (_: Throwable) { cleanupFailed.set(true) }
            finally {
                cleanupPublished.countDown()
                cleanupExecutor.shutdown()
            }
        }

        fun close(deadline: Long): Boolean {
            requestClose()
            return try {
                // requestClose may have begun on a connection/death callback; publication is bounded.
                check(cleanupPublished.await(remaining(deadline), TimeUnit.MILLISECONDS) && !cleanupFailed.get())
                for (future in requireNotNull(cleanupTasks.get())) future.get(remaining(deadline), TimeUnit.MILLISECONDS)
                true
            } catch (_: Throwable) { false }
            finally { cleanupExecutor.shutdownNow() }
        }
    }

    private companion object {
        val readGate = DozeOwnerReadOnlyGate()
        fun remaining(deadline: Long): Long = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
    }
}

private fun decodeOwnerReadOnlyBundle(bundle: Bundle): DozeOwnerReadOnlySummary {
    val keys = setOf("requestedAtMillis", "finishedAtMillis", "elapsedMillis", "code", "parsedComplete", "inventory",
        "activeRecords", "hiddenSystemRecords", "retainedRecords", "canonicalPackages", "renameRows", "reportedUsers", "normalizedSha256", "normalizedSchema")
    check(bundle.keySet() == keys && bundle.getString("inventory") == "UNKNOWN" &&
        bundle.getString("normalizedSchema") == "guardian-doze-read-only-v2")
    val code = DozeOwnerReadOnlyCode.entries.singleOrNull { it.name == bundle.getString("code") } ?: error("Invalid debug read result")
    check(bundle.getBoolean("parsedComplete") == (code == DozeOwnerReadOnlyCode.PARSED))
    return DozeOwnerReadOnlySummary(bundle.getLong("requestedAtMillis"), bundle.getLong("finishedAtMillis"),
        bundle.getLong("elapsedMillis"), code, bundle.getInt("activeRecords"), bundle.getInt("hiddenSystemRecords"),
        bundle.getInt("retainedRecords"), bundle.getInt("canonicalPackages"), bundle.getInt("renameRows"),
        bundle.getInt("reportedUsers"), bundle.getString("normalizedSha256").orEmpty()).also { check(it.isValidReadOnlySummary()) }
}
