package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GMS_GLOBAL_OWNER_PROOF_VERSION
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeIdentity
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeObservation
import io.github.originosfcmguardian.core.doze.GmsGlobalEnvironment
import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsGlobalInstallation
import io.github.originosfcmguardian.core.doze.GmsGlobalOwnerEvidence
import java.util.Collections

/**
 * Reuses the accepted complete current getter mapping; never promotes raw Settings.appId or a
 * historical mapping into write authority. Captures the actual installations read by THIS sweep.
 * No command, storage, transport or mutation lives here. Supplied reads must remain bounded and
 * must report any uncertain getter exit; the later business engine independently checks the raw
 * full whitelist and repeated pre/post observations. This is not an atomic PMS snapshot.
 */
internal class GmsManualBrokerObservation(
    private val source: DozeEffectiveOwnerReadPort,
    private val readGuardian: () -> GmsGlobalGuardianBinding,
    private val readWhitelist: () -> String,
    private val uncertainGetterExit: () -> Boolean,
    private val expectedFingerprint: String,
    private val requireActive: () -> Unit,
) {
    fun read(): GmsGlobalDozeObservation {
        requireActive()
        val guardian = snapshotGuardian(readGuardian())
        val installations = ArrayList<DozeEffectiveGmsInstallation>()
        val environments = ArrayList<DozeEffectiveEnvironment>()
        val captured = object : DozeEffectiveOwnerReadPort by source {
            override fun gmsInstallation(): DozeEffectiveGmsInstallation? = source.gmsInstallation()?.let {
                it.copy(signers = immutable(it.signers)).also(installations::add)
            }
            override fun environment(): DozeEffectiveEnvironment = source.environment().also(environments::add)
        }
        val mapping = DozeEffectiveOwnerMapping(captured, expectedFingerprint, requireActive).read()
        check(!uncertainGetterExit() && mapping.code == DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING &&
            mapping.rejection == DozeEffectiveOwnerRejection.NONE && mapping.ownerCount == 1 &&
            mapping.processedNames == mapping.canonicalPackages && mapping.canonicalPackages > 0) {
            "Current complete unique GMS owner mapping is unavailable"
        }
        check(installations.size == 2 && installations[0] == installations[1] &&
            environments.size == 2 && environments[0] == environments[1]) { "Sweep identity evidence is incomplete" }
        val installation = installations[0]
        check(installation.uid == mapping.gmsAppId && guardian.userId == 0 &&
            guardian.installation.appId != installation.uid) { "Current installation binding differs" }
        requireActive()
        val whitelist = readWhitelist()
        check(whitelist.isNotEmpty() && whitelist.toByteArray(Charsets.UTF_8).size <= MAX_WHITELIST_BYTES) {
            "Full whitelist evidence is missing or oversized"
        }
        // The whitelist read is outside the getter sweep; bracket it with fresh identity reads.
        check(source.gmsInstallation() == installation && source.environment() == environments[0] &&
            snapshotGuardian(readGuardian()) == guardian && !uncertainGetterExit()) { "Identity drifted around whitelist read" }
        requireActive()
        return GmsGlobalDozeObservation(GmsGlobalDozeIdentity(
            GmsGlobalInstallation(installation.uid, installation.versionCode, installation.firstInstallTime,
                installation.lastUpdateTime, immutable(installation.signers)), guardian,
            GmsGlobalEnvironment(expectedFingerprint, environments[0].executionUid, environments[0].currentUser),
            GmsGlobalOwnerEvidence.Qualified(GMS_GLOBAL_OWNER_PROOF_VERSION, mapping.mappingDigest, mapping.gmsAppId),
        ), whitelist)
    }

    private fun snapshotGuardian(value: GmsGlobalGuardianBinding) = value.copy(
        installation = value.installation.copy(signerSha256 = immutable(value.installation.signerSha256)),
    )

    companion object {
        private const val MAX_WHITELIST_BYTES = 128 * 1024
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
}
