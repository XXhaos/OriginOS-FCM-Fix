package io.github.originosfcmguardian.doze.ofd

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.doze.DozeBindLifecycle
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class DozeOfdReadOnlyResult(
    val code: String,
    val elapsedMillis: Long,
    val cleanupConfirmed: Boolean,
    val appHeldShellBusy: Boolean = false,
    val duplicateANonExclusive: Boolean = false,
    val retainedBAfterExportClose: Boolean = false,
    val shellHeldAppBusy: Boolean = false,
    val inoMismatchRejected: Boolean = false,
    val serviceDeathObserved: Boolean = false,
    val appReacquiredAfterRelease: Boolean = false,
    val postDeathAppLock: Boolean = false,
    val shellHeldAfterAppBClosed: Boolean = false,
    val diagnostic: DozeOfdFailureSnapshot = DozeOfdFailureSnapshot(),
    val shellProbe: DozeOfdAppHeldProbeSnapshot = DozeOfdAppHeldProbeSnapshot(),
) {
    val writerPermission = false
    val inodeReplacement = "NOT_RUN"
    val productionAdmission = "NOT_QUALIFIED"
}

/** One explicit finite fixture. No subprocess, policy API, automatic caller or replay. */
internal class DozeOfdReadOnlyClient(private val context: Context) {
    fun run(expectedGuardianAppUID: Int): DozeOfdReadOnlyResult {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(40_000)
        val operations = deadline - TimeUnit.MILLISECONDS.toNanos(6_000)
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        if (!gate.enter()) return DozeOfdReadOnlyResult("ENTRANCE_CLOSED", elapsed(), false,
            diagnostic = DozeOfdFailureSnapshot(DozeOfdFixtureStage.GATE_TERMINAL,
                reason = DozeOfdFailureReason.ENTRANCE_CLOSED))
        val diagnostic = DozeOfdFixtureDiagnostic()
        var binding: Binding? = null
        var pair: GuardianOfdScratch.ScratchPair? = null
        val scratchOwnership = DozeOfdResourceHandoff<GuardianOfdScratch.ScratchPair>()
        var appBusy = false
        var dupShared = false
        var retained = false
        var shellBusy = false
        var mismatch = false
        var reacquired = false
        var postDeathLock = false
        var heldAfterBClose = false
        var cleanup = false
        var passed = false
        var shellProbe = DozeOfdAppHeldProbeSnapshot()
        try {
            diagnostic.stage(DozeOfdFixtureStage.APP_IDENTITY)
            check(Looper.myLooper() != Looper.getMainLooper())
            gate.rpc(operations) { DozeOfdIdentity.app(context, expectedGuardianAppUID) }
            diagnostic.stage(DozeOfdFixtureStage.SHIZUKU_READY)
            check(gate.rpc(operations) { Shizuku.pingBinder() && Shizuku.getUid() == 2_000 &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED })
            diagnostic.stage(DozeOfdFixtureStage.APP_NATIVE_LOAD)
            gate.rpc(operations) {
                scratchOwnership.produce {
                    diagnostic.expectNative(DozeOfdFixtureStage.APP_NATIVE_LOAD) { GuardianOfdNative.loadForApp(context) }
                    diagnostic.stage(DozeOfdFixtureStage.SCRATCH_SETUP)
                    GuardianOfdScratch.openScratchPair(context)
                }
            }
            // The ownership slot survives a lost/timed-out Future result; this is only a borrow.
            val scratch = scratchOwnership.borrow()
            pair = scratch
            path(scratch, diagnostic)
            val altered = GuardianOfdNative.Identity(scratch.identity.dev, scratch.identity.ino + 1,
                expectedGuardianAppUID, scratch.identity.mode)
            diagnostic.expectNative(DozeOfdFixtureStage.INO_MISMATCH_PROBE, GuardianOfdNative.Status.IDENTITY_MISMATCH) {
                GuardianOfdNative.tryLock(scratch.a, altered)
            }
            mismatch = true
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_LOCK) { GuardianOfdNative.tryLock(scratch.a, scratch.identity) }
            val ownBinding = Binding(context.applicationContext)
            binding = ownBinding
            diagnostic.stage(DozeOfdFixtureStage.SHELL_BIND)
            val service = ownBinding.bind(operations)
            val lifetime = Binder()
            diagnostic.stage(DozeOfdFixtureStage.SHELL_SETUP)
            gate.rpc(operations) {
                // Action owns export duplicates until real RPC return, even after public timeout.
                scratchOwnership.withBorrow { leased ->
                    ParcelFileDescriptor.dup(leased.b.fileDescriptor).use { exportB ->
                        ParcelFileDescriptor.dup(leased.a.fileDescriptor).use { negativeA ->
                            reply(service.setup(exportB, negativeA, expectedGuardianAppUID,
                                leased.identity.dev, leased.identity.ino, lifetime), "SETUP")
                        }
                    }
                }
            }
            path(scratch, diagnostic)
            diagnostic.stage(DozeOfdFixtureStage.APP_HELD_PROBE)
            shellProbe = gate.rpc(operations) { decodeAppHeldProbe(service.appHeldProbe()) }
            check(shellProbe.code == "APP_HELD_BUSY_DUP_A_SHARED")
            appBusy = true
            dupShared = true
            // The setup/export wrapper is closed; keep the App B duplicate for the held-lock close probe.
            path(scratch, diagnostic)
            diagnostic.stage(DozeOfdFixtureStage.EXPORTED_B_CLOSE_PROBE)
            gate.rpc(operations) { reply(service.exportedBClosedProbe(), "RETAINED_B_BUSY") }
            retained = true
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_UNLOCK) { GuardianOfdNative.unlock(scratch.a, scratch.identity) }
            path(scratch, diagnostic)
            diagnostic.stage(DozeOfdFixtureStage.SHELL_B_ACQUIRE)
            gate.rpc(operations) { reply(service.acquireB(), "SHELL_B_HELD") }
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_BUSY, GuardianOfdNative.Status.BUSY) {
                GuardianOfdNative.tryLock(scratch.a, scratch.identity)
            }
            shellBusy = true
            diagnostic.stage(DozeOfdFixtureStage.APP_B_CLOSE)
            scratch.b.close()
            path(scratch, diagnostic)
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_BUSY_AFTER_B_CLOSE, GuardianOfdNative.Status.BUSY) {
                GuardianOfdNative.tryLock(scratch.a, scratch.identity)
            }
            heldAfterBClose = true
            diagnostic.stage(DozeOfdFixtureStage.SHELL_B_RELEASE)
            gate.rpc(operations) { reply(service.releaseAndCloseB(), "SHELL_OWNED_FD_CLOSED") }
            path(scratch, diagnostic)
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_REACQUIRE) { GuardianOfdNative.tryLock(scratch.a, scratch.identity) }
            diagnostic.expectNative(DozeOfdFixtureStage.APP_A_REACQUIRE) { GuardianOfdNative.unlock(scratch.a, scratch.identity) }
            reacquired = true
            check(DozeOfdFixtureGate.remaining(operations) > 0)
            passed = true
        } catch (failure: Throwable) {
            diagnostic.failure(failure)
            gate.markUnknown()
        } finally {
            diagnostic.stage(DozeOfdFixtureStage.SERVICE_CLEANUP)
            val deathObserved = binding?.close(deadline) ?: (binding == null)
            // A reply, Future cancellation, or lock release cannot prove callee cleanup.
            // This fixture has no child; require own-service Binder death AND all real call exits.
            cleanup = deathObserved
            if (!deathObserved) diagnostic.unconfirmed(DozeOfdFixtureStage.SERVICE_CLEANUP)
            if (passed && deathObserved) {
                runCatching {
                    val scratch = requireNotNull(pair)
                    path(scratch, diagnostic)
                    diagnostic.expectNative(DozeOfdFixtureStage.POST_DEATH_APP_LOCK) { GuardianOfdNative.tryLock(scratch.a, scratch.identity) }
                    diagnostic.expectNative(DozeOfdFixtureStage.POST_DEATH_APP_LOCK) { GuardianOfdNative.unlock(scratch.a, scratch.identity) }
                    postDeathLock = true
                }.onFailure { diagnostic.failure(it); cleanup = false }
            }
            diagnostic.stage(DozeOfdFixtureStage.RESOURCE_CLEANUP)
            cleanup = scratchOwnership.close() && cleanup
            cleanup = gate.finish(cleanup)
            if (!cleanup) diagnostic.unconfirmed(DozeOfdFixtureStage.RESOURCE_CLEANUP)
        }
        val died = binding?.deathObserved() == true
        if (gate.isUnknown()) diagnostic.unconfirmed(DozeOfdFixtureStage.GATE_TERMINAL, DozeOfdFailureReason.TERMINAL_GATE)
        return DozeOfdReadOnlyResult(if (passed && cleanup && !gate.isUnknown()) "PASS_BASIC_ONLY" else "UNKNOWN",
            elapsed(), cleanup, appBusy, dupShared, retained, shellBusy, mismatch, died, reacquired,
            postDeathLock, heldAfterBClose, diagnostic.snapshot(), shellProbe)
    }

    private fun path(pair: GuardianOfdScratch.ScratchPair, diagnostic: DozeOfdFixtureDiagnostic) {
        diagnostic.expectNative(DozeOfdFixtureStage.PATH_VALIDATION) { pair.verifyPath() }
    }

    private class Binding(context: Context) {
        private val token = UUID.randomUUID().toString().replace("-", "").take(16)
        private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DozeOfdReadOnlyUserService::class.java.name))
            .daemon(false).tag("guardian-ofd-basic-v1-$token").processNameSuffix("guardian_ofd_basic_$token")
            .debuggable(true).version(BuildConfig.VERSION_CODE)
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val closing = AtomicBoolean(false)
        private val remote = AtomicReference<IDozeOfdReadOnly?>()
        private val death = IBinder.DeathRecipient {
            if (!closing.get()) gate.markUnknown()
            died.countDown()
            connected.countDown()
        }
        private val lifecycle: DozeBindLifecycle = DozeBindLifecycle { Shizuku.unbindUserService(args, connection, true) }
        private val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    binder.linkToDeath(death, 0)
                    remote.set(IDozeOfdReadOnly.Stub.asInterface(binder))
                    if (closing.get()) lifecycle.close()
                } catch (_: Throwable) { gate.markUnknown() }
                finally { connected.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                // Disconnect callback alone is not proof of remote death.
                if (!closing.get()) gate.markUnknown()
                connected.countDown()
            }
        }
        fun bind(deadline: Long): IDozeOfdReadOnly {
            lifecycle.beforeDispatch()
            gate.rpc(deadline) { Shizuku.bindUserService(args, connection); lifecycle.afterReturn() }
            check(connected.await(minOf(6_000, DozeOfdFixtureGate.remaining(deadline)), TimeUnit.MILLISECONDS))
            check(!gate.isUnknown() && !closing.get())
            return requireNotNull(remote.get())
        }
        fun close(deadline: Long): Boolean {
            closing.set(true)
            val cleanupDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_000))
            val service = remote.get()
            val lanes = Executors.newFixedThreadPool(2) { task -> Thread(task, "guardian-ofd-cleanup").apply { isDaemon = true } }
            try {
                lanes.submit { gate.observe { runCatching { service?.destroy() } } }
                lanes.submit { gate.observe { runCatching { lifecycle.close() } } }
                lanes.shutdown()
                val exited = lanes.awaitTermination(DozeOfdFixtureGate.remaining(cleanupDeadline), TimeUnit.MILLISECONDS)
                val death = died.await(DozeOfdFixtureGate.remaining(cleanupDeadline), TimeUnit.MILLISECONDS)
                return exited && death
            } catch (_: Throwable) { return false }
            finally {
                if (deathObserved()) service?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
                lanes.shutdownNow()
            }
        }
        fun deathObserved() = died.count == 0L
    }

    companion object { private val gate = DozeOfdFixtureGate() }
}

private fun reply(value: Bundle?, code: String) {
    val result = requireNotNull(value)
    check(result.keySet() == setOf("schema", "code", "shellUID", "writerPermission"))
    check(result.getString("schema") == "guardian-ofd-basic-v1" && result.getString("code") == code &&
        result.getInt("shellUID") == 2_000 && !result.getBoolean("writerPermission"))
}
