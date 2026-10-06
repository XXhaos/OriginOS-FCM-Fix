package io.github.originosfcmguardian.doze.ofd

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import io.github.originosfcmguardian.doze.DozeServiceExpiry
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated shell service: one ordered scratch-only fixture and no child processes. */
class DozeOfdReadOnlyUserService : IDozeOfdReadOnly.Stub {
    private val closed = AtomicBoolean(false)
    private var caller = -1
    private var stage = 0
    private var b: ParcelFileDescriptor? = null
    private var dupA: ParcelFileDescriptor? = null
    private var identity: GuardianOfdNative.Identity? = null
    private var lifetime: IBinder? = null
    private val clientDeath = IBinder.DeathRecipient { expiry.terminateNow() }
    private val expiry = DozeServiceExpiry(Process.myUid(), ::terminate)

    constructor()
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    @Synchronized
    override fun setup(independentB: ParcelFileDescriptor?, duplicateA: ParcelFileDescriptor?,
        expectedGuardianAppUID: Int, expectedDev: Long, expectedIno: Long, clientLifetime: IBinder?): Bundle {
        try {
            check(stage == 0 && !closed.get())
            val uid = Binder.getCallingUid()
            DozeOfdIdentity.shell(expectedGuardianAppUID, uid)
            check(GuardianOfdNative.loadForShell(expectedGuardianAppUID).status == GuardianOfdNative.Status.OK)
            val first = requireNotNull(independentB)
            val negative = requireNotNull(duplicateA)
            requireScratchDescriptor(first)
            requireScratchDescriptor(negative)
            val checked = GuardianOfdNative.identity(first, expectedGuardianAppUID)
            check(checked.result.status == GuardianOfdNative.Status.OK)
            val value = requireNotNull(checked.identity)
            check(value.dev == expectedDev && value.ino == expectedIno)
            val negativeIdentity = GuardianOfdNative.identity(negative, expectedGuardianAppUID)
            check(negativeIdentity.result.status == GuardianOfdNative.Status.OK &&
                value.sameFile(negativeIdentity.identity))
            caller = uid
            identity = value
            lifetime = requireNotNull(clientLifetime).also { it.linkToDeath(clientDeath, 0) }
            b = ParcelFileDescriptor.dup(first.fileDescriptor)
            dupA = ParcelFileDescriptor.dup(negative.fileDescriptor)
            // Success requires actual inbound close; errors cannot claim the close fixture passed.
            first.close()
            negative.close()
            check(!closed.get())
            stage = 1
            return reply("SETUP")
        } catch (_: Throwable) {
            closeOwned()
            return reply("UNKNOWN")
        } finally {
            // Inbound/export close is only an FD action, never a Parcel cleanup barrier.
            runCatching { independentB?.close() }
            runCatching { duplicateA?.close() }
        }
    }

    @Synchronized override fun appHeldProbe(): Bundle {
        var probeStage = DozeOfdAppHeldProbeStage.STEP_GATE
        var bResult: GuardianOfdNative.Result? = null
        var dupResult: GuardianOfdNative.Result? = null
        fun projection(code: String, kind: DozeOfdFailureClass) = reply(code).attachAppHeldProbe(
            DozeOfdAppHeldProbeSnapshot(code, probeStage, kind, bResult?.status, bResult?.errno ?: 0,
                dupResult?.status, dupResult?.errno ?: 0))
        return try {
            check(!closed.get() && caller == Binder.getCallingUid() && stage == 1)
            probeStage = DozeOfdAppHeldProbeStage.IDENTITY
            DozeOfdIdentity.shell(caller, Binder.getCallingUid())
            probeStage = DozeOfdAppHeldProbeStage.FD_PATH
            requireScratchDescriptor(requireNotNull(b))
            probeStage = DozeOfdAppHeldProbeStage.B_TRY_LOCK
            val bAttempt = GuardianOfdNative.tryLock(requireNotNull(b), requireNotNull(identity))
            bResult = bAttempt
            // Retain the result and run the already specified dupA negative control once.
            // EACCES on the contended B alone cannot distinguish denial from exclusion.
            probeStage = DozeOfdAppHeldProbeStage.DUP_A_TRY_LOCK
            val dupAttempt = GuardianOfdNative.tryLock(requireNotNull(dupA), requireNotNull(identity))
            dupResult = dupAttempt
            probeStage = DozeOfdAppHeldProbeStage.DUP_A_CLOSE
            // Unlocking dupA would also unlock App A. Only close this duplicate.
            requireNotNull(dupA).close()
            dupA = null
            probeStage = DozeOfdAppHeldProbeStage.FINAL_GATE
            check(!closed.get())
            check(bAttempt.status == GuardianOfdNative.Status.BUSY && bAttempt.errno == OsConstants.EAGAIN &&
                dupAttempt.status == GuardianOfdNative.Status.OK && dupAttempt.errno == 0)
            stage = 2
            projection("APP_HELD_BUSY_DUP_A_SHARED", DozeOfdFailureClass.NONE)
        } catch (failure: Throwable) {
            val kind = appHeldProbeFailureClass(failure)
            closeOwned()
            projection("UNKNOWN", kind)
        }
    }

    @Synchronized override fun exportedBClosedProbe(): Bundle = step(2, 3) {
        check(GuardianOfdNative.tryLock(requireNotNull(b), requireNotNull(identity)).status == GuardianOfdNative.Status.BUSY)
        "RETAINED_B_BUSY"
    }

    @Synchronized override fun acquireB(): Bundle = step(3, 4) {
        check(GuardianOfdNative.tryLock(requireNotNull(b), requireNotNull(identity)).status == GuardianOfdNative.Status.OK)
        "SHELL_B_HELD"
    }

    @Synchronized override fun releaseAndCloseB(): Bundle = step(4, 5) {
        check(GuardianOfdNative.unlock(requireNotNull(b), requireNotNull(identity)).status == GuardianOfdNative.Status.OK)
        requireNotNull(b).close()
        b = null
        "SHELL_OWNED_FD_CLOSED"
    }

    private fun step(expectedStage: Int, nextStage: Int, action: () -> String): Bundle {
        return try {
            check(!closed.get() && caller == Binder.getCallingUid() && stage == expectedStage)
            DozeOfdIdentity.shell(caller, Binder.getCallingUid())
            requireScratchDescriptor(requireNotNull(b))
            val value = action()
            check(!closed.get())
            stage = nextStage
            reply(value)
        } catch (_: Throwable) { closeOwned(); reply("UNKNOWN") }
    }

    override fun destroy() {
        if (Process.myUid() == 2_000 && (Binder.getCallingUid() == 2_000 || Binder.getCallingUid() == caller)) {
            expiry.terminateNow()
        }
    }

    private fun reply(code: String) = Bundle().apply {
        putString("schema", "guardian-ofd-basic-v1")
        putString("code", code)
        putInt("shellUID", Process.myUid())
        putBoolean("writerPermission", false)
    }

    private fun requireScratchDescriptor(fd: ParcelFileDescriptor) {
        // Read our own descriptor link, without traversing the App's private directory.
        // Exact fixed aliases only; '(deleted)', a renamed inode or any other App file fails closed.
        val suffix = "/$OFD_PACKAGE/files/guardian-ofd-qualification/stable.lock"
        val path = Os.readlink("/proc/self/fd/${fd.fd}")
        check(path == "/data/user/0$suffix" || path == "/data/data$suffix")
    }

    @Synchronized private fun closeOwned() {
        closed.set(true)
        runCatching { b?.close() }
        runCatching { dupA?.close() }
        b = null
        dupA = null
        lifetime?.let { runCatching { it.unlinkToDeath(clientDeath, 0) } }
        lifetime = null
    }

    private fun terminate() {
        // Exit itself is independent of a blocked Binder method or monitor. No child exists.
        closed.set(true)
        System.exit(0)
    }
}
