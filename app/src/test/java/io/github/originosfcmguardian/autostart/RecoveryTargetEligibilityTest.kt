package io.github.originosfcmguardian.autostart

import org.junit.Assert.*
import org.junit.Test

class RecoveryTargetEligibilityTest {
    @Test fun enabledOverrideQualifiesManifestDisabledLauncherAlias() {
        assertTrue(effectiveRecoveryComponentEnabled(false, 1))
        assertTrue(qualifiedRecoveryLauncher("org.telegram.messenger", 10434, "org.telegram.messenger", 10434,
            false, true, true) { 1 })
    }

    @Test fun defaultUsesManifestAndUnavailableManifestCannotDefaultToEnabled() {
        assertTrue(effectiveRecoveryComponentEnabled(true, 0))
        assertFalse(effectiveRecoveryComponentEnabled(false, 0))
        assertFalse(effectiveRecoveryComponentEnabled(null, 0))
    }

    @Test fun disabledUnknownAndMissingOverrideNeverQualifyEvenEnabledManifest() {
        for (setting in listOf(null, -1, 2, 3, 4, 5, Int.MAX_VALUE)) {
            assertFalse(effectiveRecoveryComponentEnabled(true, setting))
            assertFalse(effectiveRecoveryComponentEnabled(false, setting))
        }
    }

    @Test fun enabledOverrideCannotBypassExportPackageUidOrApplicationGuards() {
        assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.other", 10434, false, true, true) { 1 })
        assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10435, false, true, true) { 1 })
        assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", null, false, true, true) { 1 })
        for (flag in listOf(false, null)) {
            assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10434, false, flag, true) { 1 })
            assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10434, false, true, flag) { 1 })
        }
    }

    @Test fun failedComponentStateReadBlocksAndDoesNotFallBackToManifest() {
        assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10434, true, true, true) { error("Unavailable read") })
        assertFalse(qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10434, true, true, true) { null })
    }

    @Test fun componentEnabledOverrideDoesNotReplacePackageQualification() {
        val launcher = qualifiedRecoveryLauncher("com.example.app", 10434, "com.example.app", 10434, false, true, true) { 1 }
        val qualified = RecoveryTargetQualifications(true, true, false, false, false, launcher)
        assertTrue(qualified.eligible)
        listOf(qualified.copy(installed = false), qualified.copy(enabled = false),
            qualified.copy(persistent = true)).forEach { assertFalse(it.eligible) }
    }

    @Test fun ordinaryUserApplicationDoesNotRequireLauncherEvidence() {
        val qualified = RecoveryTargetQualifications(true, true, false, false, false)
        assertTrue(qualified.eligible)
        assertTrue(qualified.copy(enabledExportedLauncher = false).eligible)
        assertTrue(qualified.copy(enabledExportedLauncher = true).eligible)
    }

    @Test fun everyRequiredPackageQualificationRemainsFailClosed() {
        val qualified = RecoveryTargetQualifications(true, true, false, false, false)
        listOf(qualified.copy(installed = null), qualified.copy(installed = false),
            qualified.copy(enabled = null), qualified.copy(enabled = false),
            qualified.copy(system = null), qualified.copy(updatedSystem = null),
            qualified.copy(persistent = null), qualified.copy(persistent = true)).forEach {
            assertFalse(it.eligible)
        }
    }

    @Test fun googlePackagePrefixDoesNotReplaceActualPackageQualification() {
        assertTrue(isRecoveryTargetPackage("com.google.android.apps.maps"))
        assertTrue(isRecoveryTargetPackage("com.google.android.keep"))
        assertTrue(isRecoveryTargetPackage("com.google.android.gms"))
        assertFalse(isRecoveryTargetPackage("com.android.vending"))
        assertFalse(isRecoveryTargetPackage("moe.shizuku.privileged.api"))
    }

    @Test fun knownSystemFlagCombinationsQualifyOnlyWithSoleUidOwnership() {
        for (packageName in listOf("com.example.system", "com.google.android.gms")) {
            for (system in listOf(false, true)) for (updatedSystem in listOf(false, true)) {
                val flags = RecoveryTargetQualifications(true, true, system, updatedSystem, false)
                assertTrue(flags.eligible)
                assertTrue(qualifiedRecoveryTargetUid(packageName, flags, listOf(packageName)))
                assertEquals(!system && !updatedSystem,
                    qualifiedRecoveryTargetUid(packageName, flags, listOf(packageName, "com.example.shared")))
                for (invalid in listOf(flags.copy(installed = false), flags.copy(installed = null),
                    flags.copy(enabled = false), flags.copy(enabled = null), flags.copy(system = null),
                    flags.copy(updatedSystem = null), flags.copy(persistent = true), flags.copy(persistent = null))) {
                    assertFalse(qualifiedRecoveryTargetUid(packageName, invalid, listOf(packageName)))
                }
            }
        }
    }

    @Test fun qualificationNeverReplacesBoundedValidUidMembership() {
        val packageName = "com.example.system"
        for (flags in listOf(RecoveryTargetQualifications(true, true, false, false, false),
            RecoveryTargetQualifications(true, true, true, true, false))) {
            for (owners in listOf(null, emptyList(), listOf("com.example.other"),
                listOf(packageName, packageName), listOf(packageName, "invalid;package"),
                listOf(packageName) + (1..64).map { "com.example.member$it" })) {
                assertFalse(qualifiedRecoveryTargetUid(packageName, flags, owners))
            }
        }
    }
}
