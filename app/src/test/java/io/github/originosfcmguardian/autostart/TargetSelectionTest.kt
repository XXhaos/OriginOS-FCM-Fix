package io.github.originosfcmguardian.autostart

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class TargetSelectionTest {
    @Test fun lowercaseHexPreservesEveryUnsignedByteAndSignerValidation() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }, lowerHexDigest(bytes))
        assertEquals("", lowerHexDigest(byteArrayOf()))
        assertEquals("000f107f80ff", lowerHexDigest(byteArrayOf(0, 15, 16, 127, 128.toByte(), 255.toByte())))
        for (bad in listOf("A".repeat(64), "a".repeat(63), "a".repeat(65), "a".repeat(63) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { identity.copy(signerSha256 = listOf(bad)) }
            assertEquals(identity, identity.copy())
        }
    }

    @Test fun signerDigestsStaySortedAndRejectDuplicateOrInvalidFreshEvidence() {
        val certificates = listOf(byteArrayOf(0, 127, 128.toByte(), 255.toByte()), byteArrayOf(1, 2, 3))
        val expected = certificates.map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } }.sorted()
        val selected = targetPackageIdentity(identity.packageName, identity.packageName, identity.packageName,
            identity.uid, identity.firstInstallTime, listOf(certificates[1], certificates[0]))
        assertEquals(identity.copy(signerSha256 = expected), selected)
        assertNull(targetPackageIdentity(identity.packageName, identity.packageName, identity.packageName,
            identity.uid, identity.firstInstallTime, listOf(certificates[1], certificates[0], certificates[1])))
        assertNull(targetPackageIdentity(identity.packageName, "different.package", identity.packageName,
            identity.uid, identity.firstInstallTime, certificates))
    }

    @Test
    fun currentUidMembershipAllowsSharedTargetButRejectsIncompleteOrMalformedEvidence() {
        val target = "com.google.android.calendar"
        assertTrue(validTargetUidMembers(target, listOf(target)))
        assertTrue(validTargetUidMembers(target, listOf(target, "com.google.android.syncadapters.calendar")))
        assertTrue(validTargetUidMembers(target, listOf(target) + (1..63).map { "com.example.member$it" }))
        listOf(null, emptyList(), listOf("com.example.other"), listOf(target, target),
            listOf(target, "invalid;name"), listOf(target) + (1..64).map { "com.example.member$it" }).forEach {
            assertFalse(validTargetUidMembers(target, it))
        }
    }

    private val identity = SelectedTargetIdentity(
        packageName = "org.telegram.messenger",
        uid = 10_434,
        firstInstallTime = 1_700_000_000_000,
        signerSha256 = listOf("a".repeat(64)),
    )

    @Test
    fun identityCodecRoundTripsStableFieldsWithoutLabel() {
        val encoded = TargetSelectionCodec.encode(listOf(identity))

        assertEquals(
            TargetSelectionDecodeResult.Valid(listOf(identity)),
            TargetSelectionCodec.decode(encoded),
        )
        assertTrue("Telegram" !in encoded)
    }

    @Test
    fun moreThanTenIdentitiesRoundTripWithoutTruncation() {
        val identities = (0 until 12).map(::identityAt)

        val encoded = TargetSelectionCodec.encode(identities)

        assertEquals(
            TargetSelectionDecodeResult.Valid(identities.sortedBy { it.packageName }),
            TargetSelectionCodec.decode(encoded),
        )
    }

    @Test
    fun storeAcceptsMoreThanTenIdentitiesWithoutDroppingEarlierSelections() {
        val preferences = FakeTargetSelectionPreferences("")
        val store = TargetSelectionStore(preferences)
        val identities = (0 until 12).map(::identityAt)

        identities.forEach { selected -> assertTrue(store.replace(selected)) }

        assertEquals(identities.sortedBy { it.packageName }, store.load())
    }

    @Test
    fun malformedDuplicateAndByteOversizedSelectionFailClosed() {
        assertEquals(TargetSelectionDecodeResult.Corrupt, TargetSelectionCodec.decode("bad|row"))
        assertEquals(
            TargetSelectionDecodeResult.Corrupt,
            TargetSelectionCodec.decode(
                TargetSelectionCodec.encode(listOf(identity)) + "\n" +
                    TargetSelectionCodec.encode(listOf(identity)),
            ),
        )
        val oversized = "x".repeat(MAX_TARGET_SELECTION_BYTES + 1)
        assertEquals(TargetSelectionDecodeResult.Corrupt, TargetSelectionCodec.decode(oversized))
    }

    @Test
    fun corruptStoreRefusesLoadReplaceAndRemoveWithoutChangingRawBytes() {
        val preferences = FakeTargetSelectionPreferences("corrupt|bytes")
        val store = TargetSelectionStore(preferences)

        assertThrows(CorruptTargetSelectionException::class.java) { store.load() }
        assertThrows(CorruptTargetSelectionException::class.java) { store.replace(identity) }
        assertThrows(CorruptTargetSelectionException::class.java) { store.remove(identity.packageName) }
        assertEquals("corrupt|bytes", preferences.raw)
        assertEquals(0, preferences.writeCount)

        assertTrue(store.clearCorruptSelection())
        assertEquals("", preferences.raw)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun uidInstallTimeOrSignerChangeInvalidatesStoredIdentity() {
        assertEquals(TargetIdentityStatus.MATCH, identityStatus(identity, identity.copy()))
        assertEquals(TargetIdentityStatus.MISSING, identityStatus(identity, null))
        assertEquals(TargetIdentityStatus.CHANGED, identityStatus(identity, identity.copy(uid = 10_435)))
        assertEquals(
            TargetIdentityStatus.CHANGED,
            identityStatus(identity, identity.copy(firstInstallTime = identity.firstInstallTime + 1)),
        )
        assertEquals(
            TargetIdentityStatus.CHANGED,
            identityStatus(identity, identity.copy(signerSha256 = listOf("c".repeat(64)))),
        )
    }

    @Test
    fun targetReturnCapturesOneIdentityAndConsumesOnlyOnceAfterDeparture() {
        val tracker = TargetAutostartReturnTracker()
        tracker.markSuccessfulLaunch(identity)
        assertNull(tracker.consumeReturn())
        tracker.markDeparture()
        assertEquals(identity, tracker.consumeReturn())
        assertNull(tracker.consumeReturn())
    }

    private fun identityAt(index: Int) = SelectedTargetIdentity(
        packageName = "app.example.p$index",
        uid = 10_000 + index,
        firstInstallTime = 1_700_000_000_000 + index,
        signerSha256 = listOf("b".repeat(64)),
    )

    @Test
    fun preinstalledAndUpdatedSystemAppsAreHiddenEvenWhenInstalledInData() {
        val installed = ApplicationInfo.FLAG_INSTALLED
        assertTrue(isUserInstalledApplication(installed))
        assertFalse(isPreinstalledApplication(installed))
        for (preinstalled in listOf(ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) {
            assertTrue(isPreinstalledApplication(installed or preinstalled))
            assertFalse(isUserInstalledApplication(installed or preinstalled))
        }
        assertFalse(isUserInstalledApplication(0))
    }

    @Test
    fun visibilityDoesNotMistakeStoppedOrPersistentUserAppsForPreinstalledApps() {
        for (otherFlags in listOf(ApplicationInfo.FLAG_STOPPED, ApplicationInfo.FLAG_PERSISTENT)) {
            assertTrue(isUserInstalledApplication(ApplicationInfo.FLAG_INSTALLED or otherFlags))
            assertFalse(isPreinstalledApplication(ApplicationInfo.FLAG_INSTALLED or otherFlags))
        }
    }

    private class FakeTargetSelectionPreferences(initial: String) : TargetSelectionPreferences {
        var raw: String = initial
        var writeCount: Int = 0

        override fun read(): String = raw

        override fun write(value: String): Boolean {
            writeCount += 1
            raw = value
            return true
        }

        override fun clear(): Boolean {
            raw = ""
            return true
        }
    }
}
