package io.github.originosfcmguardian.doze

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import io.github.originosfcmguardian.core.doze.DozeOutcome
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import io.github.originosfcmguardian.core.doze.DozeTarget
import io.github.originosfcmguardian.core.doze.DozeTransactionEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Separate short-lived shell service. Its public IPC has no target, UID, command, or write-method
 * argument. The fixed GMS command port is usable only after an app-owned durable journal callback
 * is accepted. This boundary trusts Guardian's own app code; it does not claim protection from
 * malicious code running under the same app UID.
 */
abstract class FixedDozeUserService(private val target: DozeTarget) : IGmsDozeTransaction.Stub() {
    private val callLock = Any()
    private val running = AtomicReference<Process?>()
    private val cancelled = AtomicBoolean(false)
    private val expiry = DozeServiceExpiry(Os.getuid(), ::terminateShellProcess)

    override fun getServiceUid(): Int = Os.getuid()

    override fun inspect(journal: IDozeJournalCallback?): Bundle = execute(Mode.INSPECT, journal)
    override fun apply(journal: IDozeJournalCallback?): Bundle = execute(Mode.APPLY, journal)
    override fun restore(journal: IDozeJournalCallback?): Bundle = execute(Mode.RESTORE, journal)

    private fun execute(mode: Mode, journal: IDozeJournalCallback?): Bundle {
        // Capture before callbacks or other outbound Binder calls can alter the calling identity.
        val callerUid = Binder.getCallingUid()
        return synchronized(callLock) {
            cancelled.set(false)
            val started = System.nanoTime()
            val deadline = started + TOTAL_BUDGET_MILLIS * 1_000_000L
            val callback = journal ?: return@synchronized blocked("Durable journal callback is required")
            val callbackBinder = callback.asBinder()
            val died = IBinder.DeathRecipient {
                cancelled.set(true)
                running.getAndSet(null)?.destroyForcibly()
            }
            val remainingMillis = {
                ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
            }
            val boundedCallback = BoundedDozeCallback(cancelled, remainingMillis) {
                running.getAndSet(null)?.destroyForcibly()
            }
            val localActive = { boundedCallback.isLocallyActive() }
            val checkedActive = {
                localActive() && boundedCallback.call { callback.isSessionActive }
            }
            var port: FixedDozeDevicePort? = null
            try {
                check(getServiceUid() == SHELL_UID) { "Doze service must run as shell UID 2000" }
                check(Build.FINGERPRINT == KNOWN_FINGERPRINT) { "Unknown device fingerprint" }
                check(callerUid in 10_000..99_999) { "Caller is not a user-0 app UID" }
                callbackBinder.linkToDeath(died, 0)
                check(checkedActive()) { "Journal callback is inactive" }
                val runner = DozeCommandRunner(
                    mayContinue = localActive,
                    remainingMillis = remainingMillis,
                    onProcess = { running.set(it) },
                )
                val owners = parseUidOwners(
                    runner.small(
                        listOf(
                            "/system/bin/cmd", "package", "list", "packages", "--user", "0",
                            "--uid", callerUid.toString(), "-U",
                        ),
                    ),
                    callerUid,
                )
                check(owners == setOf(GUARDIAN_PACKAGE)) { "Caller is not fresh exact Guardian UID" }

                port = FixedDozeDevicePort(runner, target)
                val durable = object : DozeJournalPort {
                    override fun load(): ByteArray? {
                        check(checkedActive()) { "Journal callback closed before read" }
                        val bytes = boundedCallback.call { callback.load() }
                        check(localActive()) { "Journal callback closed after read" }
                        return bytes
                    }

                    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
                        check(checkedActive()) { "Journal callback closed before commit" }
                        val committed = boundedCallback.call { callback.compareAndSet(expected, replacement) }
                        check(localActive()) { "Journal callback closed after commit" }
                        return committed
                    }
                }
                val engine = DozeTransactionEngine(
                    device = port,
                    journal = durable,
                    expectedFingerprint = KNOWN_FINGERPRINT,
                    target = target,
                    mayContinue = checkedActive,
                )
                val outcome = when (mode) {
                    Mode.INSPECT -> engine.inspect()
                    Mode.APPLY -> engine.apply()
                    Mode.RESTORE -> engine.restore()
                }
                outcome.asBundle()
            } catch (failure: Throwable) {
                DozeOutcome(
                    code = if (port?.writeAttempted == true) DozeOutcomeCode.UNCERTAIN else DozeOutcomeCode.BLOCKED,
                    detail = boundedFailureDetail(failure),
                ).asBundle()
            } finally {
                cancelled.set(true)
                running.getAndSet(null)?.destroyForcibly()
                runCatching { callbackBinder.unlinkToDeath(died, 0) }
            }
        }
    }

    override fun destroy() {
        expiry.terminateNow()
    }

    private fun terminateShellProcess() {
        cancelled.set(true)
        running.getAndSet(null)?.destroyForcibly()
        System.exit(0)
    }

    private fun blocked(detail: String): Bundle =
        DozeOutcome(DozeOutcomeCode.BLOCKED, detail = detail).asBundle()

    private fun boundedFailureDetail(failure: Throwable): String {
        val cause = if (failure is ExceptionInInitializerError) failure.cause else null
        if (cause != null) {
            val firstLine = cause.message?.lineSequence()?.firstOrNull().orEmpty().take(96)
            return "${failure.javaClass.simpleName}: ${cause.javaClass.simpleName}: $firstLine".take(192)
        }
        return failure.message?.lineSequence()?.firstOrNull()?.take(192)
            ?: failure.javaClass.simpleName
    }

    private fun DozeOutcome.asBundle(): Bundle = Bundle().apply {
        putString("code", code.name)
        putString("phase", phase?.name)
        putString("detail", detail.take(192))
    }

    private enum class Mode { INSPECT, APPLY, RESTORE }

    private companion object {
        const val SHELL_UID = 2_000
        const val GUARDIAN_PACKAGE = "io.github.originosfcmguardian"
        const val TOTAL_BUDGET_MILLIS = 40_000L
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}

/** Release entry point stays permanently fixed to GMS. */
class GmsDozeUserService : FixedDozeUserService {
    constructor() : super(DozeTarget.GMS)

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(DozeTarget.GMS)
}
