package io.github.originosfcmguardian.daemon

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity
import io.github.originosfcmguardian.privileged.GmsHeartbeatCallerProbeRunner
import io.github.originosfcmguardian.privileged.SystemDaemonOwnerEnvironmentReadPort
import io.github.originosfcmguardian.privileged.SystemGmsHeartbeatCallerReadPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable daemon(true) Shizuku entry point. Binder identity is captured before clearCallingIdentity;
 * every operation independently checks the current Guardian principal and runtime permission.
 * There is no permission cache, App lifetime callback, shell/path argument or raw control patch.
 */
@Keep
class ProtectionDaemonService : IProtectionDaemon.Stub {
    constructor()
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    private val creating = AtomicBoolean()
    private val destroyed = AtomicBoolean()
    private val teardownFailureReported = AtomicBoolean()
    private val drain = DaemonServiceDrain()
    private val callers = GmsHeartbeatCallerProbeRunner(::SystemGmsHeartbeatCallerReadPort)
    @Volatile private var host: DaemonProtectionHost? = null
    @Volatile private var installedOwner: DaemonOwnerKey? = null
    @Volatile private var installedMigration: String? = null

    override fun getServiceUid(): Int = Os.getuid()

    override fun transact(operation: Int, request: Bundle?): Bundle {
        val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
        if (!drain.enter()) return DaemonWire.failure("UNAVAILABLE")
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!destroyed.get() && getServiceUid() == 2000)
            val caller = authenticate(uid)
            val data = requireNotNull(request)
            when (operation) {
                DaemonWire.PREFLIGHT -> {
                    DaemonWire.keys(data)
                    // Authentication and UID checks above are read-only. No shell directory,
                    // import, control record or protection consent is created by preflight.
                    check(SystemDaemonOwnerEnvironmentReadPort().guardianExitHistory(caller.realUid) != null)
                    DaemonWire.success { putInt("uid", getServiceUid()) }
                }
                DaemonWire.BOOTSTRAP -> {
                    DaemonWire.keys(data, "instance", "epoch", "migration")
                    val intent = DaemonMigrationIntent(DaemonOwnerKey(DaemonWire.text(data, "instance"),
                        DaemonWire.long(data, "epoch")), DaemonWire.text(data, "migration"))
                    bootstrap(caller, intent)
                }
                else -> operate(operation, data, caller, pid)
            }
        } catch (failure: IllegalArgumentException) { recordFailure(operation, failure); DaemonWire.failure("INVALID_REQUEST") }
        catch (failure: Exception) { recordFailure(operation, failure); DaemonWire.failure("UNAVAILABLE") }
        finally { try { Binder.restoreCallingIdentity(identity) } finally { drain.leave() } }
    }

    /** Fixed operation/class/source locations only; never log payloads, paths, IDs or credentials. */
    private fun recordFailure(operation: Int, failure: Exception) {
        val locations = failure.stackTrace.take(4).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        val errno = (failure as? android.system.ErrnoException)?.errno
        android.util.Log.w("GuardianDaemon", "operation=$operation failure=${failure.javaClass.name} errno=$errno locations=$locations")
    }

    private fun authenticate(uid: Int): DaemonCallerEvidence {
        require(uid in 10_000..99_999)
        val port = SystemDaemonOwnerEnvironmentReadPort()
        val before = port.environment()
        check(before.shellUid == 2000 && before.currentUser == 0 && before.userUnlocked)
        val installed = requireNotNull(port.guardianPackage())
        check(installed.installed && installed.enabled && !installed.stopped && installed.requestsRuntimePermission &&
            installed.installation.identity.uid == uid && port.packagesForUid(uid) == listOf(GUARDIAN_PACKAGE_NAME) &&
            port.shizukuRuntimePermission(uid) == true)
        check(port.guardianPackage() == installed && port.environment() == before)
        return DaemonCallerEvidence(uid, installed.installation)
    }

    private fun historical(caller: DaemonCallerEvidence, pid: Int): GmsHeartbeatCallerIdentity =
        requireNotNull(callers.read(caller.realUid, pid, null, destroyed::get).current)

    private fun bootstrap(caller: DaemonCallerEvidence, intent: DaemonMigrationIntent): Bundle {
        host?.takeUnless { it.isFullyReleased() }?.let {
            check(installedOwner == intent.owner && installedMigration == intent.migrationId)
            return DaemonWire.status(it.status(caller))
        }
        if (!creating.compareAndSet(false, true)) return DaemonWire.failure("BUSY")
        try {
            check(!destroyed.get())
            host?.takeUnless { it.isFullyReleased() }?.let {
                check(installedOwner == intent.owner && installedMigration == intent.migrationId)
                return DaemonWire.status(it.status(caller))
            }
            val environment = SystemDaemonOwnerEnvironmentReadPort().environment()
            // New UUID directories are created only here, after real authenticated bootstrap.
            val shell = DaemonShellStorage.provisionAuthenticated(intent.owner.installInstance)
            var retained = false
            try {
                check(!destroyed.get())
                val root = DaemonProtectionRoot(ShellDaemonProtectionRootStorage(shell))
                val next = DaemonProtectionHost(intent.owner, caller.installation, environment.bootId, intent.migrationId,
                    root, ownerLease = { start -> shell.withOwnerLease { start() }; true },
                    releaseLease = { shell.close() },
                    diagnostics = runCatching { DaemonDiagnosticsRuntime.create(intent.owner.installInstance, environment.bootId) }.getOrNull())
                // Creation does IO outside the gate. Publish even a raced closed host so its
                // workers and lease remain visible to destroy's actual-drain check.
                val admitted = drain.publishHost(next) {
                    installedOwner = intent.owner; installedMigration = intent.migrationId; host = next
                }
                retained = true
                if (!admitted) { next.close(); error("Daemon destroyed during bootstrap") }
                return DaemonWire.status(next.status(caller))
            } finally { if (!retained) shell.close() }
        } finally { creating.set(false) }
    }

    private fun operate(operation: Int, data: Bundle, caller: DaemonCallerEvidence, pid: Int): Bundle {
        val current = checkNotNull(host)
        return when (operation) {
            DaemonWire.STATUS -> { DaemonWire.keys(data); DaemonWire.status(current.status(caller)) }
            DaemonWire.READ_DIAGNOSTICS -> { DaemonWire.keys(data); DaemonWire.diagnostics(current.diagnostics(caller)) }
            DaemonWire.READ_GMS -> {
                DaemonWire.keys(data, "id")
                val observation = current.readGms(caller, DaemonWire.long(data, "id"))
                DaemonWire.success { putString("gms", io.github.originosfcmguardian.core.gms.GmsObservationCodec.encode(observation)) }
            }
            DaemonWire.BEGIN_IMPORT -> {
                DaemonWire.keys(data, "size", "sha256")
                val token = current.beginImport(caller, DaemonWire.int(data, "size"), DaemonWire.text(data, "sha256"))
                DaemonWire.success { putString("token", token) }
            }
            DaemonWire.APPEND_IMPORT -> {
                DaemonWire.keys(data, "token", "offset", "bytes")
                val offset = current.appendImport(caller, DaemonWire.text(data, "token"), DaemonWire.int(data, "offset"), DaemonWire.chunk(data, "bytes"))
                DaemonWire.success { putInt("offset", offset) }
            }
            DaemonWire.IMPORT_OFFSET -> {
                DaemonWire.keys(data, "token")
                val offset = current.importOffset(caller, DaemonWire.text(data, "token"))
                DaemonWire.success { putInt("offset", offset) }
            }
            DaemonWire.FINISH_IMPORT -> {
                DaemonWire.keys(data, "token")
                val record = current.finishImport(caller, DaemonWire.text(data, "token"), historical(caller, pid))
                DaemonWire.success { putBundle("record", DaemonWire.record(record)) }
            }
            DaemonWire.STAGE -> {
                DaemonWire.keys(data, "expected", "id", "command", "target", "package")
                val expected = DaemonWire.record(DaemonWire.nested(data, "expected"))
                val id = DaemonWire.text(data, "id")
                val command = DaemonWire.int(data, "command")
                val challenge = if (command == DaemonWire.FCM_ON) current.stageFcmOn(caller, expected, id, historical(caller, pid))
                else current.stage(caller, expected, id, when (command) {
                    DaemonWire.ACTIVATE -> DaemonProtectionCommand.Activate
                    DaemonWire.MASTER_ON -> DaemonProtectionCommand.MasterOn
                    DaemonWire.MASTER_OFF -> DaemonProtectionCommand.MasterOff
                    DaemonWire.TARGET_ON -> DaemonProtectionCommand.TargetOn(DaemonWire.target(DaemonWire.text(data, "target", 4096)))
                    DaemonWire.TARGET_OFF -> DaemonProtectionCommand.TargetOff(DaemonWire.text(data, "package"),
                        data.getString("target")?.let(DaemonWire::target))
                    DaemonWire.FCM_OFF -> DaemonProtectionCommand.FcmOff
                    else -> error("Unknown command")
                }, if (command == DaemonWire.ACTIVATE) historical(caller, pid) else null)
                controlChallenge(current, caller, challenge)
            }
            DaemonWire.CURRENT_POINTER -> {
                DaemonWire.keys(data); controlChallenge(current, caller, current.currentPointerChallenge(caller))
            }
            DaemonWire.RESUME_STAGED -> {
                DaemonWire.keys(data, "record")
                controlChallenge(current, caller, current.resumeStaged(caller, DaemonWire.record(DaemonWire.nested(data, "record"))))
            }
            DaemonWire.ATTEST_APPLY -> {
                DaemonWire.keys(data, "challenge", "request", "drainedImport", "drainedHeartbeat")
                val challenge = DaemonWire.challenge(DaemonWire.nested(data, "challenge"))
                check(data.containsKey("drainedImport") == data.containsKey("drainedHeartbeat"))
                if (data.containsKey("drainedImport")) {
                    check(current.attestMigrationDrain(caller, challenge,
                        DaemonWire.text(data, "drainedImport"), DaemonWire.text(data, "drainedHeartbeat"),
                        historical(caller, pid)))
                }
                check(current.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
                val request = DaemonWire.request(DaemonWire.nested(data, "request"))
                DaemonWire.reply(current.apply(caller, request))
            }
            DaemonWire.RECONCILE -> {
                DaemonWire.keys(data, "challenge", "id")
                val challenge = DaemonWire.challenge(DaemonWire.nested(data, "challenge"))
                check(current.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
                check(current.reconcile(caller, DaemonReconcileProof(challenge.pointer, DaemonWire.text(data, "id"))) {
                    historical(caller, pid)
                })
                DaemonWire.status(current.status(caller))
            }
            DaemonWire.RECONCILE_INITIAL_ARM -> {
                DaemonWire.keys(data, "expected", "dataRevision", "frozen", "sha256")
                check(current.reconcileInitialHeartbeatArm(caller,
                    DaemonWire.record(DaemonWire.nested(data, "expected")),
                    DaemonWire.long(data, "dataRevision"), DaemonWire.chunk(data, "frozen"),
                    DaemonWire.text(data, "sha256", 64)))
                DaemonWire.success()
            }
            DaemonWire.QUERY -> {
                DaemonWire.keys(data, "request"); DaemonWire.reply(current.query(caller, DaemonWire.request(DaemonWire.nested(data, "request"))))
            }
            DaemonWire.BEGIN_SNAPSHOT -> {
                DaemonWire.keys(data); val header = current.beginSnapshot(caller)
                DaemonWire.success {
                    putString("token", header.token); putInt("size", header.size); putString("sha256", header.sha256)
                    putLong("dataRevision", header.dataRevision); putBundle("record", DaemonWire.record(header.control))
                }
            }
            DaemonWire.SNAPSHOT_CHUNK -> {
                DaemonWire.keys(data, "token", "dataRevision", "offset", "size")
                val bytes = current.snapshotChunk(caller, DaemonWire.text(data, "token"), DaemonWire.long(data, "dataRevision"),
                    DaemonWire.int(data, "offset"), DaemonWire.int(data, "size"))
                DaemonWire.success { putByteArray("bytes", bytes) }
            }
            DaemonWire.END_SNAPSHOT -> {
                DaemonWire.keys(data, "token"); current.endSnapshot(caller, DaemonWire.text(data, "token")); DaemonWire.success()
            }
            else -> error("Unknown operation")
        }
    }

    private fun controlChallenge(current: DaemonProtectionHost, caller: DaemonCallerEvidence, challenge: DaemonPointerChallenge?): Bundle {
        if (challenge == null) return DaemonWire.failure("NOT_READY")
        val request = if (challenge.purpose == DaemonPointerPurpose.TRANSITION) current.requestForChallenge(caller, challenge) else null
        if (challenge.purpose == DaemonPointerPurpose.TRANSITION && request == null) return DaemonWire.failure("NOT_READY")
        return DaemonWire.success {
            putBundle("challenge", DaemonWire.challenge(challenge)); request?.let { putBundle("request", DaemonWire.request(it)) }
        }
    }

    override fun destroy() {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && runCatching { authenticate(uid) }.isFailure) return
        val first = drain.closeAdmission()
        destroyed.set(true)
        cleanupDestroyed { callers.close() }
        cleanupDestroyed { host?.close() }
        if (!first) return
        // Only explicit destroy arms this teardown-only watcher. App Binder disconnection
        // never calls destroy here, and an uncertain worker has no timeout-based kill path.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                val current = host
                cleanupDestroyed { current?.close() } // Actual-worker reap; never releases a busy lease.
                if (drain.claimExit(creating.get(), callers::isQuiescent, current?.isFullyReleased() != false, current)) {
                    // Protection and its authority lease have already drained. Give the independent
                    // END record one bounded teardown opportunity; stuck diagnostics cannot hold exit.
                    val diagnosticDeadline = android.os.SystemClock.elapsedRealtime() + 2_000
                    while (current?.isDiagnosticsFinished() == false && android.os.SystemClock.elapsedRealtime() < diagnosticDeadline) delay(50)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@launch
                }
                delay(50)
            }
        }
    }
    private fun cleanupDestroyed(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            if (teardownFailureReported.compareAndSet(false, true)) recordFailure(-1, failure)
        }
    }
}
