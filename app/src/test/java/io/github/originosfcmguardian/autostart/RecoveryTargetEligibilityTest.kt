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
        listOf(qualified.copy(installed = false), qualified.copy(enabled = false), qualified.copy(system = true),
            qualified.copy(updatedSystem = true), qualified.copy(persistent = true)).forEach { assertFalse(it.eligible) }
    }
}
