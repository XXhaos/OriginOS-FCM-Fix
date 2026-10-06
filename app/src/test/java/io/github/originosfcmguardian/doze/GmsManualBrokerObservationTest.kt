package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsGlobalInstallation
import io.github.originosfcmguardian.core.doze.GmsGlobalOwnerEvidence
import org.junit.Assert.*
import org.junit.Test

class GmsManualBrokerObservationTest {
    @Test fun usesTheSameSweepInstallationAndKeepsFullRawWhitelist() {
        val port = Port()
        val raw = "system,ordinary.pkg,1000\n"
        val result = reader(port, whitelist = { raw }).read()
        assertEquals(raw, result.rawWhitelist)
        assertEquals(port.gms.uid, result.identity.gms.appId)
        assertEquals(port.gms.versionCode, result.identity.gms.versionCode)
        assertEquals(3, port.gmsReads) // sweep start/end, then whitelist end bracket
        assertEquals(2, port.inventoryReads)
        assertEquals(1, (result.identity.owners as GmsGlobalOwnerEvidence.Qualified).proofVersion)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.identity.gms.signerSha256 as MutableList<String>).add("b".repeat(64))
        }
    }

    @Test fun nonUniqueAndUnknownMappingsNeverBecomeAWriteObservation() {
        val shared = Port().apply { ordinaryUid = 10208 }
        assertThrows(IllegalStateException::class.java) { reader(shared).read() }
        val missing = Port().apply { missingApplication = true }
        assertThrows(IllegalStateException::class.java) { reader(missing).read() }
        assertThrows(IllegalStateException::class.java) { reader(Port(), uncertain = { true }).read() }
    }

    @Test fun whitelistBracketingRejectsGmsAndEnvironmentDrift() {
        for (environment in listOf(false, true)) {
            val port = Port()
            assertThrows(IllegalStateException::class.java) {
                reader(port, whitelist = {
                    if (environment) port.environmentValue = port.environmentValue.copy(currentUser = 999)
                    else port.gms = port.gms.copy(lastUpdateTime = 3)
                    "system,ordinary.pkg,1000\n"
                }).read()
            }
        }
    }

    @Test fun guardianDriftAndMissingOrOversizedWhitelistReject() {
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            reader(Port(), guardian = {
                guardian().let { if (reads++ == 0) it else it.copy(installation = it.installation.copy(lastUpdateTime = 3)) }
            }).read()
        }
        for (raw in listOf("", "x".repeat(128 * 1024 + 1))) {
            assertThrows(IllegalStateException::class.java) { reader(Port(), whitelist = { raw }).read() }
        }
    }

    @Test fun cancellationAndLateUncertainGetterCannotReturnPositiveObservation() {
        assertThrows(IllegalStateException::class.java) { reader(Port(), active = { error("cancelled") }).read() }
        var uncertain = false
        assertThrows(IllegalStateException::class.java) {
            reader(Port(), uncertain = { uncertain }, whitelist = {
                uncertain = true
                "system,ordinary.pkg,1000\n"
            }).read()
        }
    }

    private class Port : DozeEffectiveOwnerReadPort {
        var ordinaryUid = 1000
        var missingApplication = false
        var gmsReads = 0
        var inventoryReads = 0
        var gms = DozeEffectiveGmsInstallation(EFFECTIVE_OWNER_GMS, EFFECTIVE_OWNER_GMS,
            10208, 37, 1, 2, listOf("a".repeat(64)))
        var environmentValue = DozeEffectiveEnvironment("firmware", 2000, 0)
        override fun environment() = environmentValue
        override fun inventory(): DozeOwnerCandidates {
            inventoryReads++
            return DozeOwnerCandidates(listOf(EFFECTIVE_OWNER_GMS, "ordinary.pkg").map { name ->
                DozeOwnerCandidate(DozeOwnerSource.ACTIVE, name, null, -1, -1, false, null,
                    listOf(DozeOwnerProfile(0, true, false, 0)), 37)
            }, emptyList(), setOf(0))
        }
        override fun packageInfo(canonicalName: String) = DozeEffectivePackage(canonicalName, false, 37,
            if (canonicalName == EFFECTIVE_OWNER_GMS) gms.uid else ordinaryUid)
        override fun applicationInfo(canonicalName: String): DozeEffectiveApplication? =
            if (missingApplication) null else DozeEffectiveApplication(canonicalName,
                if (canonicalName == EFFECTIVE_OWNER_GMS) gms.uid else ordinaryUid, -1)
        override fun gmsInstallation() = gms.also { gmsReads++ }
    }

    private fun reader(port: Port, whitelist: () -> String = { "system,ordinary.pkg,1000\n" },
        guardian: () -> GmsGlobalGuardianBinding = ::guardian, uncertain: () -> Boolean = { false },
        active: () -> Unit = {}) = GmsManualBrokerObservation(port, guardian, whitelist, uncertain, "firmware", active)

    private fun guardian() = GmsGlobalGuardianBinding(0, GmsGlobalInstallation(10362, 57, 1, 2, listOf("b".repeat(64))))
}
