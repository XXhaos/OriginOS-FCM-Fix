package io.github.originosfcmguardian.doze

import android.os.Bundle

/** Small read-only IPC projection. A qualified mapping may still contain multiple GMS-appId owners. */
internal data class DozeEffectiveOwnerReadOnlySummary(
    val requestedAtMillis: Long,
    val finishedAtMillis: Long,
    val elapsedMillis: Long,
    val result: DozeEffectiveOwnerResult,
    val uncertainBinderExit: Boolean = false,
)
internal fun DozeEffectiveOwnerReadOnlySummary.toBundle() = Bundle().apply {
    putInt("summaryVersion", 1)
    putLong("requestedAtMillis", requestedAtMillis)
    putLong("finishedAtMillis", finishedAtMillis)
    putLong("elapsedMillis", elapsedMillis)
    putString("code", result.code.name)
    putString("rejection", result.rejection.name)
    putInt("records", result.records)
    putInt("canonicalPackages", result.canonicalPackages)
    putInt("apexPackages", result.apexPackages)
    putInt("applicationPackages", result.applicationPackages)
    putInt("processedNames", result.processedNames)
    putInt("gmsAppId", result.gmsAppId)
    putInt("ownerCount", result.ownerCount)
    putString("structureDigest", result.structureDigest)
    putString("mappingDigest", result.mappingDigest)
    putBoolean("uncertainBinderExit", uncertainBinderExit)
}
internal fun decodeEffectiveOwnerReadOnlyBundle(bundle: Bundle): DozeEffectiveOwnerReadOnlySummary {
    check(bundle.keySet() == setOf("summaryVersion", "requestedAtMillis", "finishedAtMillis", "elapsedMillis", "code", "rejection",
        "records", "canonicalPackages", "apexPackages", "applicationPackages", "processedNames", "gmsAppId", "ownerCount", "structureDigest",
        "mappingDigest", "uncertainBinderExit") && bundle.getInt("summaryVersion") == 1)
    val code = DozeEffectiveOwnerCode.entries.singleOrNull { it.name == bundle.getString("code") } ?: error("Invalid effective summary")
    val rejection = DozeEffectiveOwnerRejection.entries.singleOrNull { it.name == bundle.getString("rejection") }
        ?: error("Invalid effective rejection")
    return DozeEffectiveOwnerReadOnlySummary(bundle.getLong("requestedAtMillis"), bundle.getLong("finishedAtMillis"),
        bundle.getLong("elapsedMillis"), DozeEffectiveOwnerResult(code, rejection, bundle.getInt("records"),
            bundle.getInt("canonicalPackages"), bundle.getInt("apexPackages"), bundle.getInt("applicationPackages"),
            bundle.getInt("gmsAppId"), bundle.getInt("ownerCount"), bundle.getString("structureDigest").orEmpty(),
            bundle.getString("mappingDigest").orEmpty(), bundle.getInt("processedNames")),
        bundle.getBoolean("uncertainBinderExit")).also { check(it.isValidEffectiveSummary()) }
}
internal fun DozeEffectiveOwnerReadOnlySummary.isValidEffectiveSummary(): Boolean {
    if (requestedAtMillis <= 0 || finishedAtMillis < requestedAtMillis || elapsedMillis !in 0..40_000) return false
    val r = result
    if (r.records !in 0..DozeOwnerCandidateParser.MAX_RECORDS || r.canonicalPackages !in 0..r.records) return false
    if (r.apexPackages !in 0..r.canonicalPackages || r.applicationPackages !in 0..r.canonicalPackages ||
        r.processedNames != r.apexPackages + r.applicationPackages || r.processedNames !in 0..r.canonicalPackages) return false
    if (r.code == DozeEffectiveOwnerCode.UNKNOWN) return r.rejection != DozeEffectiveOwnerRejection.NONE &&
        r.gmsAppId == -1 && r.ownerCount == 0 &&
        r.structureDigest.isEmpty() && r.mappingDigest.isEmpty()
    return !uncertainBinderExit && r.rejection == DozeEffectiveOwnerRejection.NONE && r.canonicalPackages > 0 &&
        r.apexPackages in 0..r.canonicalPackages && r.applicationPackages > 0 &&
        r.processedNames == r.canonicalPackages && r.gmsAppId in 10_000..99_999 &&
        r.ownerCount in 1..r.applicationPackages && listOf(r.structureDigest, r.mappingDigest).all { it.matches(Regex("[0-9a-f]{64}")) }
}
