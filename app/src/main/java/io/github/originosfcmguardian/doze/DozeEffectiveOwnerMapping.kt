package io.github.originosfcmguardian.doze

import java.security.MessageDigest

internal const val EFFECTIVE_OWNER_GMS = "com.google.android.gms"
internal enum class DozeEffectiveOwnerCode { QUALIFIED_CURRENT_MAPPING, UNKNOWN }
internal enum class DozeEffectiveOwnerRejection {
    NONE, BUSY, CANCELLED, DEADLINE, READ_FAILED, ENVIRONMENT, INVENTORY, ALIAS,
    RETAINED, MISSING_ACTIVE, PACKAGE_IDENTITY, NULL_APPLICATION, UID, PCC,
    GMS_INSTALLATION, SNAPSHOT_DRIFT, IN_FLIGHT, REFLECTION_UNAVAILABLE,
    PACKAGE_READ, APPLICATION_READ, GMS_READ, ENVIRONMENT_READ, PCC_FIELD_UNAVAILABLE,
}
internal class DozeEffectiveOwnerReadException(val rejection: DozeEffectiveOwnerRejection) : IllegalStateException()
internal data class DozeEffectiveEnvironment(val fingerprint: String, val executionUid: Int, val currentUser: Int)
internal data class DozeEffectivePackage(
    val packageName: String, val isApex: Boolean, val versionCode: Long, val applicationUid: Int?,
)
internal data class DozeEffectiveApplication(val packageName: String, val uid: Int, val pccUid: Int)
internal data class DozeEffectiveGmsInstallation(
    val packageName: String, val applicationPackageName: String, val uid: Int, val versionCode: Long,
    val firstInstallTime: Long, val lastUpdateTime: Long, val signers: List<String>,
)
internal interface DozeEffectiveOwnerReadPort {
    fun environment(): DozeEffectiveEnvironment
    fun inventory(): DozeOwnerCandidates
    fun packageInfo(canonicalName: String): DozeEffectivePackage?
    fun applicationInfo(canonicalName: String): DozeEffectiveApplication?
    fun gmsInstallation(): DozeEffectiveGmsInstallation?
}
internal data class DozeEffectiveOwnerResult(
    val code: DozeEffectiveOwnerCode, val rejection: DozeEffectiveOwnerRejection,
    val records: Int = 0, val canonicalPackages: Int = 0, val apexPackages: Int = 0,
    val applicationPackages: Int = 0, val gmsAppId: Int = -1, val ownerCount: Int = 0,
    val structureDigest: String = "", val mappingDigest: String = "",
    val processedNames: Int = 0,
)

/** Current getter mapping only, not an atomic PMS snapshot, write ticket or global consent. */
internal class DozeEffectiveOwnerMapping(
    private val port: DozeEffectiveOwnerReadPort,
    private val expectedFingerprint: String,
    private val requireActive: () -> Unit = {},
) {
    fun read(): DozeEffectiveOwnerResult {
        var records = 0
        var canonical = 0
        var apexCount = 0
        var applicationCount = 0
        var processed = 0
        fun unknown(reason: DozeEffectiveOwnerRejection) = DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.UNKNOWN,
            reason, records, canonical, apexCount, applicationCount, processedNames = processed)
        fun reject(reason: DozeEffectiveOwnerRejection): Nothing = throw Rejected(reason)
        try {
            requireActive()
            val environment = port.environment()
            if (environment != DozeEffectiveEnvironment(expectedFingerprint, 2_000, 0)) reject(DozeEffectiveOwnerRejection.ENVIRONMENT)
            val installation = port.gmsInstallation() ?: reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
            if (!validInstallation(installation)) reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
            val first = port.inventory()
            records = first.records.size
            val groups = first.records.groupBy { it.packageName }.toSortedMap()
            canonical = groups.size
            if (records !in 1..DozeOwnerCandidateParser.MAX_RECORDS || canonical !in 1..records)
                reject(DozeEffectiveOwnerRejection.INVENTORY)
            if (first.renames.isNotEmpty() || first.records.any { it.compatName != null }) reject(DozeEffectiveOwnerRejection.ALIAS)
            // Raw Settings IDs are never used to select or exclude an ordinary owner.
            if (first.records.any { it.retained }) reject(DozeEffectiveOwnerRejection.RETAINED)
            if (first.records.any { it.pccId >= 0 }) reject(DozeEffectiveOwnerRejection.PCC)
            val rows = ArrayList<List<String>>()
            val resolved = LinkedHashMap<String, Int>()
            for ((name, sourceRecords) in groups) {
                requireActive()
                val active = sourceRecords.singleOrNull { it.source == DozeOwnerSource.ACTIVE }
                    ?: reject(DozeEffectiveOwnerRejection.MISSING_ACTIVE)
                val version = active.versionCode ?: reject(DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
                val info = port.packageInfo(name) ?: reject(DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
                if (info.packageName != name || info.versionCode != version) reject(DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
                if (info.isApex) {
                    if (name == EFFECTIVE_OWNER_GMS) reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
                    apexCount++
                    rows += listOf(name, "APEX_EXCLUDED_BY_TYPE", version.toString())
                } else {
                    val application = port.applicationInfo(name) ?: reject(DozeEffectiveOwnerRejection.NULL_APPLICATION)
                    if (application.packageName != name || application.uid !in 0..99_999 || info.applicationUid != application.uid)
                        reject(DozeEffectiveOwnerRejection.UID)
                    if (application.pccUid != -1) reject(DozeEffectiveOwnerRejection.PCC)
                    resolved[name] = application.uid
                    applicationCount++
                    rows += listOf(name, "APPLICATION", version.toString(), application.uid.toString(), application.pccUid.toString())
                }
                processed++
            }
            val gmsRecord = groups[EFFECTIVE_OWNER_GMS]?.singleOrNull { it.source == DozeOwnerSource.ACTIVE }
                ?: reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
            if (resolved[EFFECTIVE_OWNER_GMS] != installation.uid || gmsRecord.versionCode != installation.versionCode ||
                gmsRecord.profiles.singleOrNull { it.userId == 0 }?.let { it.installed && !it.hidden && it.enabled in 0..1 } != true)
                reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
            val last = port.inventory()
            requireActive()
            if (dozeOwnerStructureDigest(first) != dozeOwnerStructureDigest(last)) reject(DozeEffectiveOwnerRejection.SNAPSHOT_DRIFT)
            if (port.gmsInstallation() != installation) reject(DozeEffectiveOwnerRejection.GMS_INSTALLATION)
            if (port.environment() != environment) reject(DozeEffectiveOwnerRejection.ENVIRONMENT)
            requireActive()
            val owners = resolved.filterValues { it == installation.uid }.size
            val binding = listOf(
                listOf("guardian-effective-owner-current-v1"),
                listOf(environment.fingerprint, environment.executionUid.toString(), environment.currentUser.toString()),
                listOf(installation.packageName, installation.applicationPackageName, installation.uid.toString(),
                    installation.versionCode.toString(), installation.firstInstallTime.toString(), installation.lastUpdateTime.toString()),
                installation.signers,
            )
            return DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, DozeEffectiveOwnerRejection.NONE,
                records, canonical, apexCount, resolved.size, installation.uid, owners,
                dozeOwnerStructureDigest(first), effectiveDigest(binding + rows), processed)
        } catch (failure: Rejected) {
            return unknown(failure.reason)
        } catch (failure: DozeEffectiveOwnerReadException) {
            return unknown(failure.rejection)
        } catch (_: java.util.concurrent.TimeoutException) {
            return unknown(DozeEffectiveOwnerRejection.DEADLINE)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            return unknown(DozeEffectiveOwnerRejection.CANCELLED)
        } catch (_: Exception) {
            return unknown(DozeEffectiveOwnerRejection.READ_FAILED)
        }
    }

    private fun validInstallation(value: DozeEffectiveGmsInstallation): Boolean = value.packageName == EFFECTIVE_OWNER_GMS &&
        value.applicationPackageName == EFFECTIVE_OWNER_GMS && value.uid in 10_000..99_999 && value.versionCode >= 0 &&
        value.firstInstallTime > 0 && value.lastUpdateTime > 0 && value.signers.size in 1..8 &&
        value.signers == value.signers.distinct().sorted() && value.signers.all { it.matches(Regex("[0-9a-f]{64}")) }
    private class Rejected(val reason: DozeEffectiveOwnerRejection) : IllegalStateException()
}

/** Includes the nullable version; volatile SharedUserSetting object identity is not persisted. */
internal fun dozeOwnerStructureDigest(candidates: DozeOwnerCandidates): String {
    val rows = ArrayList<List<String>>()
    rows += listOf("guardian-doze-read-only-v2")
    for (user in candidates.expectedUserIds.sorted()) rows += listOf("user:$user")
    for (record in candidates.records.sortedWith(compareBy({ it.source.name }, { it.packageName }, { it.displayName }))) {
        rows += listOf(record.source.name, record.displayName, record.compatName.orEmpty(), record.appId.toString(),
            record.pccId.toString(), record.retained.toString(), (record.sharedUser != null).toString(), record.versionCode?.toString() ?: "null")
        for (profile in record.profiles.sortedBy { it.userId }) rows += listOf("${profile.userId}:${profile.installed}:${profile.hidden}:${profile.enabled}")
    }
    for (rename in candidates.renames.sortedWith(compareBy({ it.from }, { it.to }))) rows += listOf(rename.from, rename.to)
    return effectiveDigest(rows)
}

private fun effectiveDigest(rows: List<List<String>>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (row in rows) for (field in row) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
