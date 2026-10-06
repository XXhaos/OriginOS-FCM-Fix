package io.github.originosfcmguardian.doze

internal enum class DozeOwnerReadOnlyCode { PARSED, REJECTED, BUSY, CANCELLED, DEADLINE }

/** The only read result sent over IPC: no names, raw output or failure details. */
internal data class DozeOwnerReadOnlySummary(
    val requestedAtMillis: Long,
    val finishedAtMillis: Long,
    val elapsedMillis: Long,
    val code: DozeOwnerReadOnlyCode,
    val activeRecords: Int = 0,
    val hiddenSystemRecords: Int = 0,
    val retainedRecords: Int = 0,
    val canonicalPackages: Int = 0,
    val renameRows: Int = 0,
    val reportedUsers: Int = 0,
    val normalizedSha256: String = "",
) {
    val inventory: String get() = "UNKNOWN"
    val parsedComplete: Boolean get() = code == DozeOwnerReadOnlyCode.PARSED
    val normalizedSchema: String get() = "guardian-doze-read-only-v2"
}

/** Canonical structured digest; all lists are bounded by the main candidate parser. */
internal fun summarizeDozeOwnerReadOnly(
    candidates: DozeOwnerCandidates,
    requestedAtMillis: Long,
    finishedAtMillis: Long,
    elapsedMillis: Long,
): DozeOwnerReadOnlySummary {
    return DozeOwnerReadOnlySummary(requestedAtMillis, finishedAtMillis, elapsedMillis, DozeOwnerReadOnlyCode.PARSED,
        candidates.records.count { it.source == DozeOwnerSource.ACTIVE },
        candidates.records.count { it.source == DozeOwnerSource.HIDDEN_SYSTEM },
        candidates.records.count { it.retained }, candidates.records.map { it.packageName }.distinct().size,
        candidates.renames.size, candidates.expectedUserIds.size,
        dozeOwnerStructureDigest(candidates))
}

/** Caller identity check only, never a global owner qualification. */
internal fun reportedGuardianUidIsExact(raw: String, callerUid: Int): Boolean {
    if (callerUid !in 10_000..99_999) return false
    val row = Regex("package:([A-Za-z][A-Za-z0-9_.]{0,254}) uid:([0-9]+)")
    val names = HashSet<String>()
    var guardianSeen = false
    for (line in raw.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
        val match = row.matchEntire(line) ?: return false
        val uid = match.groupValues[2].toIntOrNull() ?: return false
        val packageName = match.groupValues[1]
        if (uid !in 0..99_999 || !names.add(packageName) || names.size > DozeOwnerCandidateParser.MAX_RECORDS) return false
        if (packageName == GUARDIAN_DOZE_READ_PACKAGE) {
            if (uid != callerUid) return false
            guardianSeen = true
        } else if (uid == callerUid) return false
    }
    return guardianSeen
}

internal const val GUARDIAN_DOZE_READ_PACKAGE = "io.github.originosfcmguardian"
internal const val DOZE_READ_ONLY_FINGERPRINT =
    "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"

internal fun DozeOwnerReadOnlySummary.isValidReadOnlySummary(): Boolean {
    if (requestedAtMillis <= 0 || finishedAtMillis < requestedAtMillis || elapsedMillis !in 0..40_000) return false
    if (code != DozeOwnerReadOnlyCode.PARSED) return activeRecords == 0 && hiddenSystemRecords == 0 &&
        retainedRecords == 0 && canonicalPackages == 0 && renameRows == 0 && reportedUsers == 0 && normalizedSha256.isEmpty()
    val total = activeRecords.toLong() + hiddenSystemRecords
    return activeRecords in 1..DozeOwnerCandidateParser.MAX_RECORDS && hiddenSystemRecords >= 0 &&
        total <= DozeOwnerCandidateParser.MAX_RECORDS && retainedRecords in 0..total && canonicalPackages in 1..total &&
        renameRows in 0..DozeOwnerCandidateParser.MAX_RENAMES && reportedUsers in 1..DozeOwnerCandidateParser.MAX_USERS &&
        Regex("[0-9a-f]{64}").matches(normalizedSha256)
}
