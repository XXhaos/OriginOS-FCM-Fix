package io.github.originosfcmguardian.privileged

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.system.Os
import androidx.annotation.Keep
import java.security.MessageDigest

/**
 * Debug APK only. This service cannot select a package, user, command or executable.
 * Its one write experiment is guarded by a fresh shell/user/build/package check and
 * by an in-memory complete whitelist baseline whose target is absent in every source.
 */
class OwnedDozeHarnessUserService : IOwnedDozeHarness.Stub {
    private val lock = Any()
    private val runner = RemoteCommandRunner(timeoutMillis = RUN_TIMEOUT_MS, maxCombinedOutputBytes = 64 * 1024)
    @Volatile private var running: Process? = null
    private var baseline: OwnedDozeSnapshot? = null
    private var baselineRaw: String? = null
    private var baselineUid: Int? = null
    private var addDispatched = false

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getServiceUid(): Int = Os.getuid()

    override fun probe(expectedUid: Int, expectedFingerprint: String?): Bundle = synchronized(lock) {
        guarded(expectedUid, expectedFingerprint, PROBE_BUDGET_MS) { deadline ->
            val current = readSnapshot(deadline)
            if (baseline == null) {
                requireOwnedTargetAbsent(current)
                baseline = current
                baselineRaw = current.raw
                baselineUid = expectedUid
            } else {
                check(baselineUid == expectedUid) { "UID changed during experiment" }
            }
            result("PROBED", current, includeBaseline = true)
        }
    }

    override fun add(expectedUid: Int, expectedFingerprint: String?): Bundle = synchronized(lock) {
        guarded(expectedUid, expectedFingerprint, WRITE_BUDGET_MS) { deadline ->
            val before = readSnapshot(deadline)
            val original = requireNotNull(baseline) { "No baseline probe" }
            check(baselineUid == expectedUid && !addDispatched) { "Add already attempted or UID changed" }
            requireOwnedTargetAbsent(original)
            check(before.entries == original.entries) { "Full whitelist drift before add" }
            addDispatched = true // Set before process dispatch; an uncertain write still needs guarded cleanup.
            runChecked(
                listOf(DEVICEIDLE, "deviceidle", "whitelist", "+$TARGET_PACKAGE"),
                deadline,
            )
            val after = readSnapshot(deadline)
            val expected = expectedOwnedAddition(original, expectedUid)
            check(after.entries == expected) { "Add read-back differs from one exact user entry" }
            result("ADDED", after)
        }
    }

    override fun remove(expectedUid: Int, expectedFingerprint: String?): Bundle = synchronized(lock) {
        guarded(expectedUid, expectedFingerprint, WRITE_BUDGET_MS) { deadline ->
            val original = requireNotNull(baseline) { "No baseline probe" }
            check(baselineUid == expectedUid && addDispatched) {
                "Cleanup requires this service's dispatched add and original absent baseline"
            }
            requireOwnedTargetAbsent(original)
            val before = readSnapshot(deadline)
            when (ownedCleanupDecision(original, before, expectedUid, addDispatched)) {
                OwnedCleanupDecision.ALREADY_RESTORED -> result("RESTORED", before)
                OwnedCleanupDecision.REMOVE_EXACT_USER_ENTRY -> {
                    runChecked(
                        listOf(DEVICEIDLE, "deviceidle", "whitelist", "-$TARGET_PACKAGE"),
                        deadline,
                    )
                    val after = readSnapshot(deadline)
                    check(after.entries == original.entries) { "Full whitelist differs from baseline after remove" }
                    result("RESTORED", after)
                }
                OwnedCleanupDecision.REFUSE -> error("Cleanup refused: unexpected membership or unrelated drift")
            }
        }
    }

    override fun destroy() {
        running?.destroyForcibly()
        running = null
        System.exit(0)
    }

    private fun guarded(
        expectedUid: Int,
        expectedFingerprint: String?,
        budgetMs: Long,
        block: (Long) -> Bundle,
    ): Bundle = try {
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        check(getServiceUid() == SHELL_UID) { "Service requires shell UID 2000" }
        check(expectedFingerprint == KNOWN_FINGERPRINT && Build.FINGERPRINT == KNOWN_FINGERPRINT) {
            "Unexpected device fingerprint"
        }
        check(expectedUid in 10_000..99_999) { "Expected UID is not a user-0 application UID" }
        check(runChecked(listOf("/system/bin/am", "get-current-user"), deadline).trim() == "0") {
            "Current user is not zero"
        }
        val packages = runChecked(
            listOf("/system/bin/pm", "list", "packages", "-U", "--user", "0", "--uid", expectedUid.toString()),
            deadline,
        )
        check(verifiedOwnedUid(packages, expectedUid)) { "Owned package UID is not fresh and unique" }
        val packageDump = runChecked(
            listOf("/system/bin/dumpsys", "package", TARGET_PACKAGE),
            deadline,
        )
        check(verifiedOwnedPackagePcc(packageDump, expectedUid)) {
            "Owned package appId/PCC/profile identity is not exact"
        }
        block(deadline)
    } catch (failure: Throwable) {
        error(failure.message?.take(160) ?: failure.javaClass.simpleName)
    }

    private fun readSnapshot(deadline: Long): OwnedDozeSnapshot =
        parseOwnedDozeWhitelist(
            runChecked(listOf(DEVICEIDLE, "deviceidle", "whitelist"), deadline),
        )

    private fun runChecked(argv: List<String>, deadline: Long): String {
        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(RUN_TIMEOUT_MS)
        check(remainingMs > 0) { "Harness call budget exhausted" }
        val outcome = runner.run(
            argv = argv,
            timeoutMillis = remainingMs,
            wasCancelled = { false },
            onProcessStarted = { running = it },
        )
        running = null
        check(!outcome.timedOut && !outcome.outputTruncated && outcome.exitCode == 0 && outcome.stderr.isBlank()) {
            "Command transport, exit, stderr or output bound failed"
        }
        return outcome.stdout
    }

    private fun result(status: String, snapshot: OwnedDozeSnapshot, includeBaseline: Boolean = false) =
        Bundle().apply {
            putBoolean("ok", true)
            putString("status", status)
            putString("sha256", snapshot.sha256)
            putInt("count", snapshot.entries.size)
            putInt("targetCount", snapshot.entries.count { it.packageName == TARGET_PACKAGE })
            if (includeBaseline) putString("baselineRaw", baselineRaw)
        }

    private fun error(message: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("status", message)
        putBoolean("addDispatched", addDispatched)
    }

    private companion object {
        const val DEVICEIDLE = "/system/bin/cmd"
        const val SHELL_UID = 2_000
        const val RUN_TIMEOUT_MS = 4_000L
        const val PROBE_BUDGET_MS = 10_000L
        const val WRITE_BUDGET_MS = 20_000L
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}

internal const val TARGET_PACKAGE = "io.github.originosfcmguardian.test"

internal data class OwnedDozeEntry(val source: String, val packageName: String, val appId: Int)

internal data class OwnedDozeSnapshot(
    val entries: Set<OwnedDozeEntry>,
    val raw: String,
) {
    val sha256: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private val MEMBERSHIP_LINE = Regex("^(system-excidle|system|user),([a-zA-Z0-9_.]+),(\\d+)$")
private val PACKAGE_UID_LINE = Regex("^package:([^\\s]+)\\s+uid:(\\d+)\\s*$")

/** Strict complete-list parser; unknown sources, malformed rows and duplicate rows fail closed. */
internal fun parseOwnedDozeWhitelist(raw: String): OwnedDozeSnapshot {
    val entries = linkedSetOf<OwnedDozeEntry>()
    raw.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
        val match = requireNotNull(MEMBERSHIP_LINE.matchEntire(line)) { "Malformed whitelist row" }
        val appId = match.groupValues[3].toIntOrNull()
        require(appId != null && appId in 0..99_999) { "Invalid whitelist appId" }
        require(entries.add(OwnedDozeEntry(match.groupValues[1], match.groupValues[2], appId))) {
            "Duplicate whitelist row"
        }
    }
    require(entries.isNotEmpty()) { "Empty whitelist" }
    return OwnedDozeSnapshot(entries, raw)
}

internal fun verifiedOwnedUid(raw: String, expectedUid: Int): Boolean {
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.isEmpty()) return false
    val parsed = lines.map { line ->
        val match = PACKAGE_UID_LINE.matchEntire(line) ?: return false
        val uid = match.groupValues[2].toIntOrNull() ?: return false
        match.groupValues[1] to uid
    }
    return parsed.count { it.first == TARGET_PACKAGE && it.second == expectedUid } == 1 &&
        parsed.count { it.second == expectedUid } == 1
}

/** Only the exact scoped owned package dump with an explicit pccId=-1 is accepted. */
internal fun verifiedOwnedPackagePcc(raw: String, expectedUid: Int): Boolean {
    val lines = raw.lineSequence().map(String::trim).toList()
    val header = "Package [$TARGET_PACKAGE]"
    val packageHeaders = lines.filter { it.startsWith("Package [") }
    val appIds = lines.filter { it.startsWith("appId=") }
    val pccIds = lines.filter { it.startsWith("pccId=") }
    val profileLines = lines.filter { it.startsWith("User ") && "installed=" in it }
    val profiles = profileLines.map { line ->
        val match = PROFILE_LINE.matchEntire(line) ?: return false
        match.groupValues[1].toIntOrNull() to (match.groupValues[2] == "true")
    }
    return packageHeaders.size == 1 &&
        packageHeaders.single().startsWith("$header ") &&
        appIds == listOf("appId=$expectedUid") &&
        pccIds == listOf("pccId=-1") &&
        profiles.isNotEmpty() &&
        profiles.map { it.first }.distinct().size == profiles.size &&
        profiles.singleOrNull { it.first == 0 }?.second == true &&
        profiles.all { (user, installed) -> user == 0 || !installed }
}

private val PROFILE_LINE = Regex("^User (\\d+):.*\\binstalled=(true|false)\\b.*$")

internal fun requireOwnedTargetAbsent(snapshot: OwnedDozeSnapshot) {
    require(snapshot.entries.none { it.packageName == TARGET_PACKAGE }) {
        "Owned target already appears in a whitelist source"
    }
}

internal fun expectedOwnedAddition(original: OwnedDozeSnapshot, uid: Int): Set<OwnedDozeEntry> =
    original.entries + OwnedDozeEntry("user", TARGET_PACKAGE, uid)

internal enum class OwnedCleanupDecision { REMOVE_EXACT_USER_ENTRY, ALREADY_RESTORED, REFUSE }

internal fun ownedCleanupDecision(
    original: OwnedDozeSnapshot,
    current: OwnedDozeSnapshot,
    uid: Int,
    addDispatched: Boolean,
): OwnedCleanupDecision {
    if (!addDispatched || original.entries.any { it.packageName == TARGET_PACKAGE }) {
        return OwnedCleanupDecision.REFUSE
    }
    return when (current.entries) {
        original.entries -> OwnedCleanupDecision.ALREADY_RESTORED
        expectedOwnedAddition(original, uid) -> OwnedCleanupDecision.REMOVE_EXACT_USER_ENTRY
        else -> OwnedCleanupDecision.REFUSE
    }
}
