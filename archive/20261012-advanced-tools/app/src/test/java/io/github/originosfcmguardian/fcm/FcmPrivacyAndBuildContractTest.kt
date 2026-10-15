package io.github.originosfcmguardian.fcm

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPrivacyAndBuildContractTest {
    @Test
    fun tokenAndRegistrationResultStringFormsNeverRevealSecret() {
        val raw = "secret-token-value"
        val token = SensitiveToken(raw)
        val success = RegistrationResult.Success(token)

        assertFalse(token.toString().contains(raw))
        assertFalse(success.toString().contains(raw))
        assertTrue(token.toString().contains("redacted"))
    }

    @Test
    fun failureClassificationNeverIncludesExceptionMessage() {
        val malicious = "contains-sensitive-token-and-server-body"
        val network = sanitizeRegistrationFailure(IOException(malicious))
        val generic = sanitizeRegistrationFailure(IllegalStateException(malicious))

        assertTrue(network === RegistrationResult.SdkIoFailure)
        assertTrue(generic === RegistrationResult.Failed)
        assertFalse(network.toString().contains(malicious))
        assertFalse(generic.toString().contains(malicious))
    }

    @Test
    fun manifestDisablesAutomaticCollectionAndDeclaresPrivateReceiver() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("firebase_messaging_auto_init_enabled"))
        assertTrue(manifest.contains("firebase_analytics_collection_enabled"))
        assertTrue(Regex("firebase_messaging_auto_init_enabled[\\s\\S]{0,120}android:value=\"false\"").containsMatchIn(manifest))
        assertTrue(Regex("firebase_analytics_collection_enabled[\\s\\S]{0,120}android:value=\"false\"").containsMatchIn(manifest))
        assertTrue(Regex("GuardianFirebaseMessagingService[\\s\\S]{0,120}android:exported=\"false\"").containsMatchIn(manifest))
    }

    @Test
    fun firebasePluginIsConditionalAndUnconfiguredBuildFlagExists() {
        val gradle = projectFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("file(\"google-services.json\").isFile"))
        assertTrue(gradle.contains("if (hasGoogleServicesConfig)"))
        assertTrue(gradle.contains("FIREBASE_CONFIG_INCLUDED"))
        assertTrue(gradle.contains("firebase-messaging"))
    }

    private fun projectFile(relative: String): File {
        val local = File(relative)
        if (local.isFile) return local
        return File("app", relative).also { require(it.isFile) { "Missing test input: $relative" } }
    }
}
