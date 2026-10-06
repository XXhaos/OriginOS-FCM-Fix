package io.github.originosfcmguardian.doze

import org.junit.Assert.*
import org.junit.Test

class DozeEffectiveOwnerMappingTest {
    @Test fun realGetterMappingCanQualifyDespiteNegativeApexAndStaleHiddenGms() {
        val port = Port()
        val result = read(port)
        assertEquals(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, result.code)
        assertEquals(4, result.records)
        assertEquals(3, result.canonicalPackages)
        assertEquals(1, result.apexPackages)
        assertEquals(2, result.applicationPackages)
        assertEquals(3, result.processedNames)
        assertEquals(10208, result.gmsAppId)
        assertEquals(1, result.ownerCount)
        assertEquals(setOf(EFFECTIVE_OWNER_GMS, "ordinary.pkg"), port.applicationCalls.toSet())
        assertEquals(64, result.mappingDigest.length)
        assertEquals(2, port.inventoryReads)
        // The raw GMS Settings appId is deliberately different from the real getter UID.
        assertEquals(55555, port.first.records.first { it.source == DozeOwnerSource.ACTIVE && it.packageName == EFFECTIVE_OWNER_GMS }.appId)
    }
    @Test fun nonApexNegativeSettingsRecordIsResolvedAndCannotBeDropped() {
        val port = Port()
        port.packages["module.apex"] = DozeEffectivePackage("module.apex", false, 37, 10208)
        port.applications["module.apex"] = DozeEffectiveApplication("module.apex", 10208, -1)
        val result = read(port)
        assertEquals(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, result.code)
        assertEquals(2, result.ownerCount)
        assertEquals(0, result.apexPackages)
    }
    @Test fun noApexPathGuessOrNullApplicationExclusion() {
        val port = Port()
        port.packages["module.apex"] = DozeEffectivePackage("module.apex", false, 37, null)
        assertUnknown(port, DozeEffectiveOwnerRejection.NULL_APPLICATION)
    }
    @Test fun nullPackageInfoCannotEstablishApexExclusion() {
        val port = Port(); port.packages.remove("module.apex")
        assertUnknown(port, DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
    }
    @Test fun apexMustHaveExactActiveNameVersion() {
        for (info in listOf(DozeEffectivePackage("different.pkg", true, 37, null),
            DozeEffectivePackage("module.apex", true, 38, null))) {
            val port = Port(); port.packages["module.apex"] = info
            assertUnknown(port, DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
        }
    }
    @Test fun missingActiveVersionAndHiddenOnlyRecordsStayUnknown() {
        val noVersion = Port(); noVersion.first = inventory(noVersion.first.records.map { it.copy(versionCode = null) })
        assertUnknown(noVersion, DozeEffectiveOwnerRejection.PACKAGE_IDENTITY)
        val hidden = Port(); hidden.first = inventory(hidden.first.records.map {
            if (it.packageName == "module.apex") it.copy(source = DozeOwnerSource.HIDDEN_SYSTEM) else it
        })
        assertUnknown(hidden, DozeEffectiveOwnerRejection.MISSING_ACTIVE)
    }
    @Test fun retainedAliasesAndBothRawAndEffectivePccAmbiguityReject() {
        val retained = Port(); retained.first = inventory(retained.first.records.map { it.copy(retained = true) })
        assertUnknown(retained, DozeEffectiveOwnerRejection.RETAINED)
        val alias = Port(); alias.first = inventory(alias.first.records, listOf(DozeOwnerRename("old.pkg", "ordinary.pkg")))
        assertUnknown(alias, DozeEffectiveOwnerRejection.ALIAS)
        val compat = Port(); compat.first = inventory(compat.first.records.map { it.copy(compatName = "alternate.pkg") })
        assertUnknown(compat, DozeEffectiveOwnerRejection.ALIAS)
        val rawPcc = Port(); rawPcc.first = inventory(rawPcc.first.records.map { it.copy(pccId = 50000) })
        assertUnknown(rawPcc, DozeEffectiveOwnerRejection.PCC)
        val effectivePcc = Port(); effectivePcc.applications["ordinary.pkg"] = DozeEffectiveApplication("ordinary.pkg", 1000, 0)
        assertUnknown(effectivePcc, DozeEffectiveOwnerRejection.PCC)
    }
    @Test fun wrongNameWrongUidAndDifferentPackageVsApplicationUidReject() {
        for (app in listOf(DozeEffectiveApplication("different.pkg", 1000, -1),
            DozeEffectiveApplication("ordinary.pkg", -1, -1), DozeEffectiveApplication("ordinary.pkg", 100000, -1),
            DozeEffectiveApplication("ordinary.pkg", 2000, -1))) {
            val port = Port(); port.applications["ordinary.pkg"] = app
            assertUnknown(port, DozeEffectiveOwnerRejection.UID)
        }
    }
    @Test fun startEndInventoryVersionOrReportedProfileDriftRejects() {
        val version = Port(); version.last = inventory(version.first.records.map { it.copy(versionCode = 38) })
        assertUnknown(version, DozeEffectiveOwnerRejection.SNAPSHOT_DRIFT)
        val profile = Port(); profile.last = inventory(profile.first.records.map {
            it.copy(profiles = listOf(DozeOwnerProfile(0, true, false, 1)))
        })
        assertUnknown(profile, DozeEffectiveOwnerRejection.SNAPSHOT_DRIFT)
    }
    @Test fun gmsUidVersionSignerAndInstallTimesRemainBoundToBothEnds() {
        val changes = listOf<(DozeEffectiveGmsInstallation) -> DozeEffectiveGmsInstallation>(
            { it.copy(uid = 10209) }, { it.copy(versionCode = 39) }, { it.copy(firstInstallTime = 10) },
            { it.copy(lastUpdateTime = 20) }, { it.copy(signers = listOf("b".repeat(64))) })
        for (change in changes) {
            val port = Port(); port.afterGms = change(port.gms)
            assertUnknown(port, DozeEffectiveOwnerRejection.GMS_INSTALLATION)
        }
    }
    @Test fun invalidInitialInstallationAndGmsApexReject() {
        val malformed = Port(); malformed.gms = malformed.gms.copy(signers = listOf("not-a-certificate-digest"))
        assertUnknown(malformed, DozeEffectiveOwnerRejection.GMS_INSTALLATION)
        val apex = Port(); apex.packages[EFFECTIVE_OWNER_GMS] = apex.packages.getValue(EFFECTIVE_OWNER_GMS).copy(isApex = true)
        assertUnknown(apex, DozeEffectiveOwnerRejection.GMS_INSTALLATION)
    }
    @Test fun executionEnvironmentMustBeExactAtBothEnds() {
        val wrong = Port(); wrong.environmentValue = DozeEffectiveEnvironment("firmware", 0, 0)
        assertUnknown(wrong, DozeEffectiveOwnerRejection.ENVIRONMENT)
        val changed = Port(); changed.afterEnvironment = DozeEffectiveEnvironment("firmware", 2000, 999)
        assertUnknown(changed, DozeEffectiveOwnerRejection.ENVIRONMENT)
    }
    @Test fun typedGetterFailureExportsCountersAndNoNameOrPrivateException() {
        val port = Port(); port.failApplication = "ordinary.pkg"
        val value = read(port)
        assertEquals(DozeEffectiveOwnerRejection.PCC_FIELD_UNAVAILABLE, value.rejection)
        assertEquals(2, value.processedNames)
        assertEquals(1, value.apexPackages)
        assertEquals(1, value.applicationPackages)
        assertFalse(value.toString().contains("ordinary.pkg"))
        assertEquals("", value.mappingDigest)
        assertEquals(-1, value.gmsAppId)
    }
    @Test fun boundedInventoryReorderingIsConsistentButVersionChangesAreNot() {
        val port = Port(); port.last = inventory(port.first.records.reversed())
        assertEquals(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, read(port).code)
    }
    @Test fun exactInstallationBindingChangesDigestDespiteSamePackageUidAndVersion() {
        val old = read(Port())
        val reinstall = Port(); reinstall.gms = reinstall.gms.copy(firstInstallTime = 10)
        val updated = Port(); updated.gms = updated.gms.copy(lastUpdateTime = 20)
        val signer = Port(); signer.gms = signer.gms.copy(signers = listOf("b".repeat(64)))
        for (port in listOf(reinstall, updated, signer)) {
            val value = read(port)
            assertEquals(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, value.code)
            assertNotEquals(old.mappingDigest, value.mappingDigest)
            assertEquals(old.structureDigest, value.structureDigest)
        }
    }
    @Test fun cancellationDoesNotProducePositiveMapping() {
        val value = DozeEffectiveOwnerMapping(Port(), "firmware") { error("cancelled") }.read()
        assertEquals(DozeEffectiveOwnerCode.UNKNOWN, value.code)
        assertEquals("", value.mappingDigest)
    }

    private fun read(port: Port) = DozeEffectiveOwnerMapping(port, "firmware").read()
    private fun assertUnknown(port: Port, rejection: DozeEffectiveOwnerRejection) {
        val value = read(port)
        assertEquals(DozeEffectiveOwnerCode.UNKNOWN, value.code)
        assertEquals(rejection, value.rejection)
        assertEquals("", value.mappingDigest)
        assertEquals(-1, value.gmsAppId)
    }
    private class Port : DozeEffectiveOwnerReadPort {
        var first = inventory(listOf(record(EFFECTIVE_OWNER_GMS, 55555), record("module.apex", -1),
            record("ordinary.pkg", 1000).copy(sharedUser = "SharedUserSetting{volatile system/1000}"),
            record(EFFECTIVE_OWNER_GMS, 10208).copy(source = DozeOwnerSource.HIDDEN_SYSTEM, versionCode = 1)))
        var last: DozeOwnerCandidates? = null
        var gms = DozeEffectiveGmsInstallation(EFFECTIVE_OWNER_GMS, EFFECTIVE_OWNER_GMS, 10208, 37, 1, 2, listOf("a".repeat(64)))
        var afterGms: DozeEffectiveGmsInstallation? = null
        var environmentValue = DozeEffectiveEnvironment("firmware", 2000, 0)
        var afterEnvironment: DozeEffectiveEnvironment? = null
        var failApplication: String? = null
        var inventoryReads = 0
        private var gmsReads = 0
        private var environmentReads = 0
        val applicationCalls = ArrayList<String>()
        val packages = mutableMapOf(EFFECTIVE_OWNER_GMS to DozeEffectivePackage(EFFECTIVE_OWNER_GMS, false, 37, 10208),
            "module.apex" to DozeEffectivePackage("module.apex", true, 37, null),
            "ordinary.pkg" to DozeEffectivePackage("ordinary.pkg", false, 37, 1000))
        val applications = mutableMapOf(EFFECTIVE_OWNER_GMS to DozeEffectiveApplication(EFFECTIVE_OWNER_GMS, 10208, -1),
            "ordinary.pkg" to DozeEffectiveApplication("ordinary.pkg", 1000, -1))
        override fun environment() = if (environmentReads++ == 0) environmentValue else afterEnvironment ?: environmentValue
        override fun inventory() = if (inventoryReads++ == 0) first else last ?: first
        override fun packageInfo(canonicalName: String) = packages[canonicalName]
        override fun applicationInfo(canonicalName: String): DozeEffectiveApplication? {
            applicationCalls += canonicalName
            if (canonicalName == failApplication) throw DozeEffectiveOwnerReadException(DozeEffectiveOwnerRejection.PCC_FIELD_UNAVAILABLE)
            return applications[canonicalName]
        }
        override fun gmsInstallation() = if (gmsReads++ == 0) gms else afterGms ?: gms
    }
    companion object {
        private fun record(name: String, rawId: Int) = DozeOwnerCandidate(DozeOwnerSource.ACTIVE, name, null, rawId, -1,
            false, null, listOf(DozeOwnerProfile(0, true, false, 0)), 37)
        private fun inventory(records: List<DozeOwnerCandidate>, renames: List<DozeOwnerRename> = emptyList()) =
            DozeOwnerCandidates(records, renames, setOf(0))
    }
}
