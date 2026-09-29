package io.github.originosfcmguardian.core

import kotlinx.coroutines.CancellationException

class ReadOnlyDetector(private val executor: PrivilegedExecutor) {
    suspend fun detect(): DetectionReport {
        val available = try {
            executor.isAvailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        if (!available) return unavailableReport(PrivilegedAccessState.UNAVAILABLE, "Executor unavailable")

        val uid = try {
            executor.getUid()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val access = when (uid) {
            2_000 -> PrivilegedAccessState.READY_SHELL
            0 -> PrivilegedAccessState.ROOT_REJECTED
            null -> PrivilegedAccessState.UNKNOWN
            else -> PrivilegedAccessState.NON_SHELL_REJECTED
        }
        if (access != PrivilegedAccessState.READY_SHELL) {
            return unavailableReport(access, "Only shell UID 2000 is accepted")
        }

        val currentUserResult = safeExec(SafeCommand.GetCurrentUser)
        val currentUser = parseSingleInt(currentUserResult)
        if (currentUser != 0) {
            val reason = if (currentUser == null) {
                "Current Android user could not be established"
            } else {
                "Current Android user is not user 0"
            }
            return unavailableReport(access, reason, currentUser)
        }

        val fingerprintResult = safeExec(SafeCommand.GetBuildFingerprint)
        val fingerprint = parseFingerprint(fingerprintResult)
        val installedResult = safeExec(SafeCommand.GetGmsPackage)
        val enabledResult = safeExec(SafeCommand.GetGmsEnabledPackage)
        val gms = GmsPackageParser.parse(installedResult, enabledResult)

        val dozeResult = safeExec(SafeCommand.GetDeviceIdleWhitelist)
        var doze = DeviceIdleParser.parse(dozeResult)
        if (gms.appId != null && doze.memberships.any { it.appId != gms.appId }) {
            doze = doze.copy(
                status = EvidenceStatus.UNKNOWN,
                problems = doze.problems + "GMS package and DeviceIdle appIds conflict",
            )
        }

        val pem = PemParser.parse(
            listResult = safeExec(SafeCommand.GetVivoPemList),
            pathResult = safeExec(SafeCommand.GetVivoPemPackagePath),
        )

        val appOps = gms.appId?.let { appId ->
            AppOp.entries.associateWith { operation ->
                val packageState = AppOpsParser.parse(
                    scope = AppOpScope.Package(),
                    operation = operation,
                    result = safeExec(SafeCommand.GetGmsAppOpByPackage(operation)),
                )
                val uidState = AppOpsParser.parse(
                    scope = AppOpScope.Uid(appId),
                    operation = operation,
                    result = safeExec(SafeCommand.GetAppOpByUid(appId, operation)),
                )
                combine(operation, packageState, uidState)
            }
        }.orEmpty()

        val problems = buildList {
            if (fingerprint == null) add("Build fingerprint is unavailable or malformed")
            addAll(gms.problems)
            addAll(appOps.values.flatMap { it.problems })
            addAll(doze.problems)
            addAll(pem.problems)
        }.distinct()

        return DetectionReport(
            privilegedAccess = access,
            currentUser = currentUser,
            buildFingerprint = fingerprint,
            gms = gms,
            appOps = appOps,
            doze = doze,
            pem = pem,
            problems = problems,
        )
    }

    private suspend fun safeExec(command: SafeCommand): CommandResult = try {
        executor.exec(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
            CommandResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "Executor failed",
            )
    }

    private fun parseSingleInt(result: CommandResult): Int? {
        if (result.command != SafeCommand.GetCurrentUser || result.transportProblems().isNotEmpty()) {
            return null
        }
        return result.stdout.trim().takeIf { it.matches(Regex("\\d+")) }?.toIntOrNull()
    }

    private fun parseFingerprint(result: CommandResult): String? {
        if (result.command != SafeCommand.GetBuildFingerprint || result.transportProblems().isNotEmpty()) {
            return null
        }
        val value = result.stdout.trim()
        return value.takeIf {
            it.isNotEmpty() && !it.contains('\n') && it.count { character -> character == '/' } >= 2
        }
    }

    private fun combine(
        operation: AppOp,
        packageState: AppOpState,
        uidState: AppOpState,
    ): AppOpCheck {
        val problems = mutableListOf<String>()
        problems += packageState.problems.map { "Package scope: $it" }
        problems += uidState.problems.map { "UID scope: $it" }
        val packageMode = packageState.effectiveReportedMode()
        val uidMode = uidState.effectiveReportedMode()
        if (packageMode != null && uidMode != null && packageMode != uidMode) {
            problems += "Package and UID scopes report conflicting modes"
        }

        val status = when {
            problems.isNotEmpty() -> EvidenceStatus.UNKNOWN
            packageState.status == EvidenceStatus.UNKNOWN || uidState.status == EvidenceStatus.UNKNOWN ->
                EvidenceStatus.UNKNOWN
            packageState.status == EvidenceStatus.CONFORMING &&
                uidState.status == EvidenceStatus.CONFORMING -> EvidenceStatus.CONFORMING
            else -> EvidenceStatus.ANOMALOUS
        }
        return AppOpCheck(operation, packageState, uidState, status, problems.distinct())
    }

    private fun AppOpState.effectiveReportedMode(): AppOpMode? = reportedMode ?: reportedDefault

    private fun unavailableReport(
        access: PrivilegedAccessState,
        reason: String,
        currentUser: Int? = null,
    ): DetectionReport = DetectionReport(
        privilegedAccess = access,
        currentUser = currentUser,
        buildFingerprint = null,
        gms = GmsPackageState(
            presence = Presence.UNKNOWN,
            enabled = EnabledState.UNKNOWN,
            appId = null,
            rawInstalledOutput = "",
            rawEnabledOutput = "",
            problems = listOf(reason),
        ),
        appOps = emptyMap(),
        doze = DozeState(
            memberships = emptySet(),
            rawOutput = "",
            status = EvidenceStatus.UNKNOWN,
            problems = listOf(reason),
        ),
        pem = PemState(
            packagePresence = Presence.UNKNOWN,
            listState = PemListState.UNKNOWN,
            entries = emptySet(),
            rawPackageOutput = "",
            rawListOutput = "",
            problems = listOf(reason),
        ),
        problems = listOf(reason),
    )
}
