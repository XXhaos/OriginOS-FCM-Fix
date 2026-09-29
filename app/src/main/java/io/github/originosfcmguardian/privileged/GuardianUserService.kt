package io.github.originosfcmguardian.privileged

import android.content.Context
import android.os.Bundle
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.AutostartUnknownReason
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.GuardianAutostartParser
import io.github.originosfcmguardian.core.TargetAutostartParser
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.SafeCommandFactory
import io.github.originosfcmguardian.core.isUserZeroAppId
import io.github.originosfcmguardian.core.isUserZeroTargetAppUid
import io.github.originosfcmguardian.core.trace.ProcessTraceCodec
import io.github.originosfcmguardian.core.trace.ProcessTraceParser
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived Shizuku UserService. The app sends only an allowlisted operation ID
 * and at most one typed UID or package argument; this process independently reconstructs
 * and validates argv before launching a fixed executable without a shell.
 */
class GuardianUserService : IGuardianUserService.Stub {
    private val runner = RemoteCommandRunner()
    private val running = ConcurrentHashMap<Long, Process>()
    private val activeRequests = ConcurrentHashMap.newKeySet<Long>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<Long>()

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getServiceUid(): Int = Os.getuid()

    override fun execute(
        requestId: Long,
        operationId: String?,
        uidArgument: Int,
        packageArgument: String?,
    ): Bundle {
        if (!isAcceptedServiceUid(getServiceUid())) {
            return errorResult(
                IpcContract.EXIT_PERMISSION_DENIED,
                "UserService requires shell UID 2000; root and other identities are rejected",
            )
        }
        if (requestId <= 0 || operationId.isNullOrBlank()) {
            return errorResult(IpcContract.EXIT_INVALID_REQUEST, "Invalid IPC request")
        }
        val uid = when (uidArgument) {
            IpcContract.NO_UID_ARGUMENT -> null
            else -> uidArgument
        }
        val command = SafeCommandFactory.fromIpc(operationId, uid, packageArgument)
            ?: return errorResult(IpcContract.EXIT_INVALID_REQUEST, "Operation is not allowlisted")
        val argv = fixedSystemExecutable(command.argv())
            ?: return errorResult(IpcContract.EXIT_INVALID_REQUEST, "Executable is not allowlisted")

        if (!activeRequests.add(requestId)) {
            return errorResult(IpcContract.EXIT_INVALID_REQUEST, "Duplicate request ID")
        }
        val deadlineNanos = System.nanoTime() + COMMAND_BUDGET_MILLIS * NANOS_PER_MILLISECOND
        try {
            if (requestId in cancelledRequests) {
                return errorResult(IpcContract.EXIT_CANCELLED, "Command cancelled")
            }
            if (command is SafeCommand.GetAppOpByUid) {
                val verificationError = verifyFreshGmsUid(
                    requestedUid = command.uid,
                    requestId = requestId,
                    deadlineNanos = deadlineNanos,
                )
                if (verificationError != null) return verificationError
            }
            var verifiedGuardianUid: Int? = null
            if (command == SafeCommand.GetGuardianAutostartRow) {
                val verification = verifyFreshGuardianUid(
                    requestId = requestId,
                    deadlineNanos = deadlineNanos,
                )
                if (verification.error != null) return verification.error
                verifiedGuardianUid = verification.uid
            }
            var verifiedTargetUid: Int? = null
            if (command is SafeCommand.GetTargetAutostartRow) {
                val verification = verifyFreshSelectedTargetUid(
                    packageName = command.packageName,
                    requestId = requestId,
                    deadlineNanos = deadlineNanos,
                )
                if (verification.error != null) return verification.error
                verifiedTargetUid = verification.uid
            }
            if (command is SafeCommand.GetTargetProcessEvents) {
                val verification = verifyFreshExclusiveSelectedTargetUid(
                    packageName = command.packageName,
                    requestedUid = command.uid,
                    requestId = requestId,
                    deadlineNanos = deadlineNanos,
                )
                if (verification.error != null) return verification.error
            }
            val remainingMillis = remainingMillis(deadlineNanos)
            if (remainingMillis <= 0) {
                return timedOutResult(
                    if (command is SafeCommand.GetTargetProcessEvents) {
                        "Process trace timed out"
                    } else {
                        "Command budget exhausted during validation"
                    },
                )
            }
            val result = runner.run(
                argv = argv,
                timeoutMillis = remainingMillis,
                wasCancelled = { requestId in cancelledRequests },
                onProcessStarted = { process ->
                    running[requestId] = process
                    if (requestId in cancelledRequests) terminateProcess(process)
                },
            )
            if (command is SafeCommand.GetTargetProcessEvents) {
                val structured = structureTargetProcessTrace(command, result)
                if (!structured.isSuccessfulTraceTransport()) {
                    return remoteResult(structured)
                }
                val verification = verifyFreshExclusiveSelectedTargetUid(
                    packageName = command.packageName,
                    requestedUid = command.uid,
                    requestId = requestId,
                    deadlineNanos = deadlineNanos,
                )
                if (verification.error != null) return verification.error
                return remoteResult(structured)
            }
            if (
                command == SafeCommand.GetGuardianAutostartRow &&
                result.exitCode == 0 &&
                !result.timedOut &&
                !result.outputTruncated &&
                result.stderr.isBlank()
            ) {
                val parsed = GuardianAutostartParser.parse(
                    CommandResult(command, 0, result.stdout, ""),
                    requireNotNull(verifiedGuardianUid),
                )
                if (parsed.unknownReason in setOf(
                        AutostartUnknownReason.PACKAGE_MISMATCH,
                        AutostartUnknownReason.UID_MISMATCH,
                    )
                ) {
                    return errorResult(
                        IpcContract.EXIT_PERMISSION_DENIED,
                        "Autostart row does not match the fresh exact Guardian identity",
                    )
                }
            }
            if (
                command is SafeCommand.GetTargetAutostartRow &&
                result.exitCode == 0 &&
                !result.timedOut &&
                !result.outputTruncated &&
                result.stderr.isBlank()
            ) {
                val parsed = TargetAutostartParser.parse(
                    CommandResult(command, 0, result.stdout, ""),
                    command.packageName,
                    requireNotNull(verifiedTargetUid),
                )
                if (parsed.unknownReason in setOf(
                        AutostartUnknownReason.PACKAGE_MISMATCH,
                        AutostartUnknownReason.UID_MISMATCH,
                    )
                ) {
                    return errorResult(
                        IpcContract.EXIT_PERMISSION_DENIED,
                        "Autostart row does not match the fresh exact selected-target identity",
                    )
                }
            }
            return remoteResult(result)
        } catch (error: Throwable) {
            if (command is SafeCommand.GetTargetProcessEvents) {
                return errorResult(IpcContract.EXIT_BRIDGE_ERROR, TRACE_UNAVAILABLE)
            }
            return errorResult(
                IpcContract.EXIT_BRIDGE_ERROR,
                error.message?.take(256) ?: error.javaClass.simpleName,
            )
        } finally {
            running.remove(requestId)
            activeRequests.remove(requestId)
            cancelledRequests.remove(requestId)
        }
    }

    override fun cancel(requestId: Long) {
        cancelledRequests.add(requestId)
        running.remove(requestId)?.let(::terminateProcess)
    }

    override fun destroy() {
        running.values.forEach(::terminateProcess)
        running.clear()
        activeRequests.clear()
        cancelledRequests.clear()
        System.exit(0)
    }

    private fun errorResult(exitCode: Int, message: String) = Bundle().apply {
        putInt(IpcContract.KEY_EXIT_CODE, exitCode)
        putString(IpcContract.KEY_STDOUT, "")
        putString(IpcContract.KEY_STDERR, message)
        putBoolean(IpcContract.KEY_TIMED_OUT, false)
        putBoolean(IpcContract.KEY_OUTPUT_TRUNCATED, false)
    }

    private fun remoteResult(result: RemoteRunResult) = Bundle().apply {
        putInt(IpcContract.KEY_EXIT_CODE, result.exitCode)
        putString(IpcContract.KEY_STDOUT, result.stdout)
        putString(IpcContract.KEY_STDERR, result.stderr)
        putBoolean(IpcContract.KEY_TIMED_OUT, result.timedOut)
        putBoolean(IpcContract.KEY_OUTPUT_TRUNCATED, result.outputTruncated)
    }

    private fun verifyFreshGmsUid(
        requestedUid: Int,
        requestId: Long,
        deadlineNanos: Long,
    ): Bundle? {
        val remainingMillis = remainingMillis(deadlineNanos)
        if (remainingMillis <= 0) return timedOutResult("GMS UID validation budget exhausted")
        val command = SafeCommand.GetGmsPackage
        val argv = fixedSystemExecutable(command.argv())
            ?: return errorResult(IpcContract.EXIT_INVALID_REQUEST, "Validation executable is not allowlisted")
        val result = runner.run(
            argv = argv,
            timeoutMillis = remainingMillis,
            wasCancelled = { requestId in cancelledRequests },
            onProcessStarted = { process ->
                running[requestId] = process
                if (requestId in cancelledRequests) terminateProcess(process)
            },
        )
        if (requestId in cancelledRequests || result.exitCode == IpcContract.EXIT_CANCELLED) {
            return errorResult(IpcContract.EXIT_CANCELLED, "Command cancelled")
        }
        if (result.timedOut) return timedOutResult("GMS UID validation timed out")
        if (result.outputTruncated || result.exitCode != 0 || result.stderr.isNotBlank()) {
            return errorResult(
                IpcContract.EXIT_PERMISSION_DENIED,
                "Fresh exact GMS UID validation failed",
            )
        }
        val exactUid = verifiedExactGmsUid(result.stdout)
        if (exactUid != requestedUid) {
            return errorResult(
                IpcContract.EXIT_PERMISSION_DENIED,
                "Requested UID does not match the fresh exact GMS package UID",
            )
        }
        return null
    }

    private fun verifyFreshGuardianUid(
        requestId: Long,
        deadlineNanos: Long,
    ): FreshUidValidation {
        val remainingMillis = remainingMillis(deadlineNanos)
        if (remainingMillis <= 0) {
            return FreshUidValidation(error = timedOutResult("Guardian UID validation budget exhausted"))
        }
        val command = SafeCommand.GetGuardianPackage
        val argv = fixedSystemExecutable(command.argv())
            ?: return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_INVALID_REQUEST, "Validation executable is not allowlisted"),
            )
        val result = runner.run(
            argv = argv,
            timeoutMillis = remainingMillis,
            wasCancelled = { requestId in cancelledRequests },
            onProcessStarted = { process ->
                running[requestId] = process
                if (requestId in cancelledRequests) terminateProcess(process)
            },
        )
        if (requestId in cancelledRequests || result.exitCode == IpcContract.EXIT_CANCELLED) {
            return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_CANCELLED, "Command cancelled"),
            )
        }
        if (result.timedOut) {
            return FreshUidValidation(error = timedOutResult("Guardian UID validation timed out"))
        }
        if (result.outputTruncated || result.exitCode != 0 || result.stderr.isNotBlank()) {
            return FreshUidValidation(
                error = errorResult(
                    IpcContract.EXIT_PERMISSION_DENIED,
                    "Fresh exact Guardian UID validation failed",
                ),
            )
        }
        val uid = verifiedExactGuardianUid(result.stdout)
        if (uid == null) {
            return FreshUidValidation(
                error = errorResult(
                    IpcContract.EXIT_PERMISSION_DENIED,
                    "Fresh exact Guardian package UID is unavailable or ambiguous",
                ),
            )
        }
        return FreshUidValidation(uid = uid)
    }

    private fun verifyFreshSelectedTargetUid(
        packageName: String,
        requestId: Long,
        deadlineNanos: Long,
    ): FreshUidValidation {
        val remainingMillis = remainingMillis(deadlineNanos)
        if (remainingMillis <= 0) {
            return FreshUidValidation(error = timedOutResult("Target UID validation budget exhausted"))
        }
        val command = SafeCommand.GetTargetPackage(packageName)
        val argv = fixedSystemExecutable(command.argv())
            ?: return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_INVALID_REQUEST, "Validation executable is not allowlisted"),
            )
        val result = runner.run(
            argv = argv,
            timeoutMillis = remainingMillis,
            wasCancelled = { requestId in cancelledRequests },
            onProcessStarted = { process ->
                running[requestId] = process
                if (requestId in cancelledRequests) terminateProcess(process)
            },
        )
        if (requestId in cancelledRequests || result.exitCode == IpcContract.EXIT_CANCELLED) {
            return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_CANCELLED, "Command cancelled"),
            )
        }
        if (result.timedOut) {
            return FreshUidValidation(error = timedOutResult("Target UID validation timed out"))
        }
        if (result.outputTruncated || result.exitCode != 0 || result.stderr.isNotBlank()) {
            return FreshUidValidation(
                error = errorResult(
                    IpcContract.EXIT_PERMISSION_DENIED,
                    "Fresh exact selected-target UID validation failed",
                ),
            )
        }
        val uid = verifiedExactTargetUid(result.stdout, packageName)
        if (uid == null) {
            return FreshUidValidation(
                error = errorResult(
                    IpcContract.EXIT_PERMISSION_DENIED,
                    "Fresh exact selected-target UID is unavailable or ambiguous",
                ),
            )
        }
        return FreshUidValidation(uid = uid)
    }

    private fun verifyFreshExclusiveSelectedTargetUid(
        packageName: String,
        requestedUid: Int,
        requestId: Long,
        deadlineNanos: Long,
    ): FreshUidValidation {
        val remainingMillis = remainingMillis(deadlineNanos)
        if (remainingMillis <= 0) {
            return FreshUidValidation(error = timedOutResult("Target identity validation timed out"))
        }
        val command = SafeCommand.GetTargetPackage(packageName)
        val argv = fixedSystemExecutable(command.uidOwnersArgv(requestedUid))
            ?: return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_INVALID_REQUEST, "Target identity validation failed"),
            )
        val result = runner.run(
            argv = argv,
            timeoutMillis = remainingMillis,
            wasCancelled = { requestId in cancelledRequests },
            onProcessStarted = { process ->
                running[requestId] = process
                if (requestId in cancelledRequests) terminateProcess(process)
            },
        )
        if (requestId in cancelledRequests || result.exitCode == IpcContract.EXIT_CANCELLED) {
            return FreshUidValidation(
                error = errorResult(IpcContract.EXIT_CANCELLED, "Process trace cancelled"),
            )
        }
        if (result.timedOut) {
            return FreshUidValidation(error = timedOutResult("Target identity validation timed out"))
        }
        if (
            result.outputTruncated || result.exitCode != 0 || result.stderr.isNotBlank() ||
            !verifiedExclusiveTargetUidOwner(result.stdout, packageName, requestedUid)
        ) {
            return FreshUidValidation(
                error = errorResult(
                    IpcContract.EXIT_PERMISSION_DENIED,
                    "Fresh exclusive selected-target identity validation failed",
                ),
            )
        }
        return FreshUidValidation(uid = requestedUid)
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

    private fun timedOutResult(message: String) = Bundle().apply {
        putInt(IpcContract.KEY_EXIT_CODE, IpcContract.EXIT_TIMED_OUT)
        putString(IpcContract.KEY_STDOUT, "")
        putString(IpcContract.KEY_STDERR, message)
        putBoolean(IpcContract.KEY_TIMED_OUT, true)
        putBoolean(IpcContract.KEY_OUTPUT_TRUNCATED, false)
    }

    private companion object {
        const val COMMAND_BUDGET_MILLIS = 8_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val TRACE_UNAVAILABLE = "Process trace unavailable"
    }

    private data class FreshUidValidation(
        val uid: Int? = null,
        val error: Bundle? = null,
    )
}

internal fun isAcceptedServiceUid(uid: Int): Boolean = uid == 2_000

private val GMS_PACKAGE_LINE = Regex("^package:([^\\s]+)\\s+uid:(\\d+)\\s*$")

internal fun verifiedExactGmsUid(output: String): Int? {
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val exactMatches = mutableListOf<Int>()
    lines.forEach { line ->
        val match = GMS_PACKAGE_LINE.matchEntire(line) ?: return null
        val packageName = match.groupValues[1]
        val uid = match.groupValues[2].toIntOrNull()?.takeIf(::isUserZeroAppId) ?: return null
        if (packageName == GMS_PACKAGE_NAME) exactMatches += uid
    }
    return exactMatches.singleOrNull()
}

internal fun verifiedExactGuardianUid(output: String): Int? =
    verifiedExactPackageUid(output, GUARDIAN_PACKAGE_NAME)

internal fun verifiedExactPackageUid(output: String, expectedPackage: String): Int? {
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val exactMatches = mutableListOf<Int>()
    lines.forEach { line ->
        val match = GMS_PACKAGE_LINE.matchEntire(line) ?: return null
        val packageName = match.groupValues[1]
        val uid = match.groupValues[2].toIntOrNull()?.takeIf(::isUserZeroAppId) ?: return null
        if (packageName == expectedPackage) exactMatches += uid
    }
    return exactMatches.singleOrNull()
}

internal fun verifiedExactTargetUid(output: String, expectedPackage: String): Int? =
    verifiedExactPackageUid(output, expectedPackage)?.takeIf(::isUserZeroTargetAppUid)

internal fun verifiedExclusiveTargetUidOwner(
    output: String,
    expectedPackage: String,
    expectedUid: Int,
): Boolean {
    if (!isUserZeroTargetAppUid(expectedUid)) return false
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.size != 1) return false
    val match = GMS_PACKAGE_LINE.matchEntire(lines.single()) ?: return false
    return match.groupValues[1] == expectedPackage &&
        match.groupValues[2].toIntOrNull() == expectedUid
}

internal fun structureTargetProcessTrace(
    command: SafeCommand.GetTargetProcessEvents,
    result: RemoteRunResult,
): RemoteRunResult {
    if (result.exitCode == IpcContract.EXIT_CANCELLED) {
        return RemoteRunResult(
            exitCode = IpcContract.EXIT_CANCELLED,
            stdout = "",
            stderr = "Process trace cancelled",
            timedOut = false,
            outputTruncated = false,
        )
    }
    if (result.timedOut || result.exitCode == IpcContract.EXIT_TIMED_OUT) {
        return RemoteRunResult(
            exitCode = IpcContract.EXIT_TIMED_OUT,
            stdout = "",
            stderr = "Process trace timed out",
            timedOut = true,
            outputTruncated = false,
        )
    }
    if (result.outputTruncated || result.exitCode != 0 || result.stderr.isNotBlank()) {
        return RemoteRunResult(
            exitCode = IpcContract.EXIT_BRIDGE_ERROR,
            stdout = "",
            stderr = "Process trace unavailable",
            timedOut = false,
            outputTruncated = false,
        )
    }
    val target = ProcessTraceTarget(command.packageName, command.uid)
    val payload = ProcessTraceCodec.encode(ProcessTraceParser.parse(result.stdout, target))
    return RemoteRunResult(
        exitCode = 0,
        stdout = payload,
        stderr = "",
        timedOut = false,
        outputTruncated = false,
    )
}

private fun RemoteRunResult.isSuccessfulTraceTransport(): Boolean =
    exitCode == 0 && !timedOut && !outputTruncated && stderr.isBlank()

internal fun fixedSystemExecutable(argv: List<String>): List<String>? {
    if (argv.isEmpty()) return null
    val executable = when (argv.first()) {
        "am" -> "/system/bin/am"
        "cmd" -> "/system/bin/cmd"
        "content" -> "/system/bin/content"
        "getprop" -> "/system/bin/getprop"
        "id" -> "/system/bin/id"
        "logcat" -> "/system/bin/logcat"
        "pm" -> "/system/bin/pm"
        "settings" -> "/system/bin/settings"
        else -> return null
    }
    return listOf(executable) + argv.drop(1)
}
