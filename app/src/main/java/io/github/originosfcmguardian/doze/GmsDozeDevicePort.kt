package io.github.originosfcmguardian.doze

import android.os.Build
import android.system.Os
import io.github.originosfcmguardian.core.doze.DozeDevicePort
import io.github.originosfcmguardian.core.doze.DozeIdentity
import io.github.originosfcmguardian.core.doze.DozeObservation
import io.github.originosfcmguardian.core.doze.DozeTarget

/** Target comes only from a concrete fixed UserService class, never from IPC. */
internal class FixedDozeDevicePort(
    private val runner: DozeCommandRunner,
    private val target: DozeTarget,
) : DozeDevicePort {
    @Volatile var writeAttempted: Boolean = false
        private set
    override fun probe(): DozeObservation {
        val currentUser = runner.small(listOf("/system/bin/am", "get-current-user")).trim()
        check(currentUser == "0") { "Active Android user is not zero" }
        val allUsers = parseAndroidUsers(runner.small(listOf("/system/bin/pm", "list", "users")))
        val projected = parsePackageProjection(runner.packageProjection(target), target)
        check(projected.profileUserIds == allUsers) { "Target package profile state is incomplete" }
        val uidOwners = parseUidOwners(
            runner.small(
                listOf(
                    "/system/bin/cmd", "package", "list", "packages",
                    "--user", "0", "--uid", projected.appId.toString(), "-U",
                ),
            ),
            projected.appId,
        )
        val enabledList = runner.small(
            listOf(
                "/system/bin/cmd", "package", "list", "packages", "--user", "0",
                "-e", "-U", target.packageName,
            ),
        )
        val enabled = verifiedEnabledList(enabledList, projected.appId, target.packageName)
        check(enabled && projected.userZeroEnabled) { "Target enabled probes disagree or target disabled" }
        val whitelist = runner.small(listOf("/system/bin/cmd", "deviceidle", "whitelist"))
        return DozeObservation(
            identity = DozeIdentity(
                fingerprint = Build.FINGERPRINT,
                userId = 0,
                executionUid = Os.getuid(),
                packageName = target.packageName,
                appId = projected.appId,
                enabled = enabled,
                installedUserIds = projected.installedUserIds,
                pccId = projected.pccId,
                uidOwnerPackages = uidOwners,
            ),
            rawWhitelist = whitelist,
        )
    }

    override fun add() {
        writeAttempted = true
        runner.small(listOf("/system/bin/cmd", "deviceidle", "whitelist", "+${target.packageName}"))
    }

    override fun remove() {
        writeAttempted = true
        runner.small(listOf("/system/bin/cmd", "deviceidle", "whitelist", "-${target.packageName}"))
    }

}

internal data class GmsPackageProjection(
    val appId: Int,
    val pccId: Int,
    val profileUserIds: Set<Int>,
    val installedUserIds: Set<Int>,
    val userZeroEnabled: Boolean,
)

private val PROFILE = Regex("^User (\\d+):.*\\binstalled=(true|false)\\b.*\\benabled=(\\d+)\\b.*$")
private val PACKAGE_UID = Regex("^package:([^\\s]+)\\s+uid:(\\d+)\\s*$")

/** Strictly validates the small projection after the full target dump reaches EOF. */
internal fun parseGmsPackageProjection(raw: String): GmsPackageProjection {
    return parsePackageProjection(raw, DozeTarget.GMS)
}

internal fun parsePackageProjection(raw: String, target: DozeTarget): GmsPackageProjection {
    val exactHeader = Regex("^Package \\[${Regex.escape(target.packageName)}\\] \\([^)]{1,32}\\):$")
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    check(lines.count { it.startsWith("Package [") } == 1 && lines.any(exactHeader::matches)) {
        "Target package header is missing or ambiguous"
    }
    val appIds = lines.filter { it.startsWith("appId=") }
    val pccIds = lines.filter { it.startsWith("pccId=") }
    check(appIds.size == 1 && pccIds.size == 1) { "Target appId/PCC identity is ambiguous" }
    val appId = appIds.single().removePrefix("appId=").toIntOrNull()
    val pccId = pccIds.single().removePrefix("pccId=").toIntOrNull()
    check(appId != null && appId in 10_000..99_999 && pccId != null) {
        "Target appId/PCC identity is invalid"
    }
    val profiles = lines.filter { it.startsWith("User ") }
    check(profiles.isNotEmpty()) { "Target profile state is missing" }
    val parsed = profiles.map { line ->
        val match = requireNotNull(PROFILE.matchEntire(line)) { "Malformed target profile state" }
        val user = match.groupValues[1].toIntOrNull()
        val enabled = match.groupValues[3].toIntOrNull()
        check(user != null && user >= 0 && enabled != null) { "Invalid target profile identity" }
        Triple(user, match.groupValues[2] == "true", enabled)
    }
    check(parsed.map { it.first }.distinct().size == parsed.size) { "Duplicate target profile state" }
    val userZero = requireNotNull(parsed.singleOrNull { it.first == 0 }) { "Target user-0 profile missing" }
    return GmsPackageProjection(
        appId = appId,
        pccId = pccId,
        profileUserIds = parsed.mapTo(linkedSetOf()) { it.first },
        installedUserIds = parsed.filter { it.second }.mapTo(linkedSetOf()) { it.first },
        userZeroEnabled = userZero.second && userZero.third in 0..1,
    )
}

private val USER_INFO = Regex("^UserInfo\\{(\\d+):.*:[0-9a-fA-F]+\\}(?: running)?$")

internal fun parseAndroidUsers(raw: String): Set<Int> {
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    check(lines.firstOrNull() == "Users:" && lines.size > 1) { "User enumeration header is invalid" }
    val users = lines.drop(1).map { line ->
        val match = requireNotNull(USER_INFO.matchEntire(line)) { "Malformed Android user row" }
        requireNotNull(match.groupValues[1].toIntOrNull()) { "Invalid Android user ID" }
    }
    check(users.size == users.distinct().size && 0 in users) { "User enumeration is ambiguous" }
    return users.toSet()
}

internal fun parseUidOwners(raw: String, expectedUid: Int): Set<String> {
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    check(lines.isNotEmpty()) { "UID ownership query returned no packages" }
    return lines.mapTo(linkedSetOf()) { line ->
        val match = requireNotNull(PACKAGE_UID.matchEntire(line)) { "Malformed UID ownership row" }
        val uid = match.groupValues[2].toIntOrNull()
        check(uid == expectedUid) { "UID ownership query reported a different UID" }
        match.groupValues[1]
    }.also { owners -> check(owners.size == lines.size) { "Duplicate UID owner row" } }
}

internal fun verifiedEnabledList(
    raw: String,
    expectedUid: Int,
    targetPackage: String = DozeTarget.GMS.packageName,
): Boolean {
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.isEmpty()) return false
    val exact = lines.map { line ->
        requireNotNull(PACKAGE_UID.matchEntire(line)) { "Malformed enabled package row" }
    }.filter { it.groupValues[1] == targetPackage }
    check(exact.size <= 1) { "Duplicate exact target enabled row" }
    return exact.singleOrNull()?.let { it.groupValues[2].toIntOrNull() == expectedUid } ?: false
}
