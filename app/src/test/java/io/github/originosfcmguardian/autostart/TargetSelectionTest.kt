package io.github.originosfcmguardian.autostart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetSelectionTest {
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
    fun malformedDuplicateAndOversizedSelectionFailClosed() {
        assertEquals(TargetSelectionDecodeResult.Corrupt, TargetSelectionCodec.decode("bad|row"))
        assertEquals(
            TargetSelectionDecodeResult.Corrupt,
            TargetSelectionCodec.decode(
                TargetSelectionCodec.encode(listOf(identity)) + "\n" +
                    TargetSelectionCodec.encode(listOf(identity)),
            ),
        )
        val oversized = (0..MAX_SELECTED_TARGETS).joinToString("\n") { index ->
            "app.example.p$index|${10_000 + index}|${1_700_000_000_000 + index}|${"b".repeat(64)}"
        }
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
