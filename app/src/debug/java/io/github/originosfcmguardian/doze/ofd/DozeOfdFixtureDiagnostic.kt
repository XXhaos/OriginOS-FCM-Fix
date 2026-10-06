package io.github.originosfcmguardian.doze.ofd

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal enum class DozeOfdFixtureStage {
    NONE, APP_IDENTITY, SHIZUKU_READY, APP_NATIVE_LOAD, SCRATCH_SETUP, PATH_VALIDATION,
    INO_MISMATCH_PROBE, APP_A_LOCK, SHELL_BIND, SHELL_SETUP, APP_HELD_PROBE,
    EXPORTED_B_CLOSE_PROBE, APP_A_UNLOCK, SHELL_B_ACQUIRE, APP_A_BUSY, APP_B_CLOSE,
    APP_A_BUSY_AFTER_B_CLOSE, SHELL_B_RELEASE, APP_A_REACQUIRE, SERVICE_CLEANUP,
    POST_DEATH_APP_LOCK, RESOURCE_CLEANUP, GATE_TERMINAL,
}
internal enum class DozeOfdFailureClass { NONE, CHECK, REFLECTION, IO, SECURITY, LINKAGE, TIMEOUT, INTERRUPTED, OTHER }
internal enum class DozeOfdFailureReason { NONE, EXCEPTION, NATIVE_STATUS, CLEANUP_UNCONFIRMED, TERMINAL_GATE, ENTRANCE_CLOSED }

/** Only fixed enums and an actual native errno cross the instrumentation boundary. */
internal data class DozeOfdFailureSnapshot(
    val stage: DozeOfdFixtureStage = DozeOfdFixtureStage.NONE,
    val failureClass: DozeOfdFailureClass = DozeOfdFailureClass.NONE,
    val reason: DozeOfdFailureReason = DozeOfdFailureReason.NONE,
    val nativeStatus: GuardianOfdNative.Status? = null,
    val nativeErrno: Int = 0,
)

internal class DozeOfdFixtureDiagnostic {
    private val progress = AtomicReference(DozeOfdFailureSnapshot())
    private val firstFailure = AtomicReference<DozeOfdFailureSnapshot?>()

    fun stage(stage: DozeOfdFixtureStage) { progress.set(DozeOfdFailureSnapshot(stage)) }

    fun recordNative(stage: DozeOfdFixtureStage, status: GuardianOfdNative.Status, errno: Int) {
        progress.updateAndGet { prior ->
            if (prior.stage == stage) prior.copy(nativeStatus = status, nativeErrno = errno) else prior
        }
    }

    fun expectNative(stage: DozeOfdFixtureStage, expected: GuardianOfdNative.Status = GuardianOfdNative.Status.OK,
        action: () -> GuardianOfdNative.Result) {
        this.stage(stage)
        val result = action()
        recordNative(stage, result.status, result.errno)
        if (result.status != expected) throw NativeStatusFailure()
    }

    fun failure(failure: Throwable) {
        var cause = failure
        repeat(4) {
            if (cause is ExecutionException || cause is InvocationTargetException) cause = cause.cause ?: cause
        }
        val kind = when (cause) {
            is SecurityException -> DozeOfdFailureClass.SECURITY
            is TimeoutException -> DozeOfdFailureClass.TIMEOUT
            is InterruptedException -> DozeOfdFailureClass.INTERRUPTED
            is ReflectiveOperationException -> DozeOfdFailureClass.REFLECTION
            is IOException -> DozeOfdFailureClass.IO
            is LinkageError -> DozeOfdFailureClass.LINKAGE
            is IllegalStateException, is IllegalArgumentException -> DozeOfdFailureClass.CHECK
            else -> DozeOfdFailureClass.OTHER
        }
        val reason = if (cause is NativeStatusFailure) DozeOfdFailureReason.NATIVE_STATUS else DozeOfdFailureReason.EXCEPTION
        firstFailure.compareAndSet(null, progress.get().copy(failureClass = kind, reason = reason))
    }

    fun unconfirmed(stage: DozeOfdFixtureStage, reason: DozeOfdFailureReason = DozeOfdFailureReason.CLEANUP_UNCONFIRMED) {
        firstFailure.compareAndSet(null, DozeOfdFailureSnapshot(stage, reason = reason))
    }

    fun snapshot(): DozeOfdFailureSnapshot = firstFailure.get() ?: DozeOfdFailureSnapshot()

    private class NativeStatusFailure : IllegalStateException()
}
