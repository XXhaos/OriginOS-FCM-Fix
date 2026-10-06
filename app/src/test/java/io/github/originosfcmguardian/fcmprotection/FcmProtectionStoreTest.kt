package io.github.originosfcmguardian.fcmprotection

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test

class FcmProtectionStoreTest {
    private class Storage : FcmProtectionStorage {
        var value = FcmProtectionConsent(); var halted = false; var failed = false
        override fun read() = value.copy(configured = value.configured && !halted)
        override fun write(value: FcmProtectionConsent) { check(!failed); this.value = value }
        override fun halt() { halted = true }
        override fun releaseHalt() { halted = false }
    }
    private fun sample(state: GcmConnectionState = GcmConnectionState.SERVICE_ABSENT,
        boot: String = "12345678-1234-1234-1234-123456789abc") = GmsConnectionObservation.Sample(
        1, 10208, FcmRecoveryPolicy.VERSION, 100, 900, FcmRecoveryPolicy.SIGNER, boot, 1000, 100,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false), true,
        emptyList(), GcmServiceSource(state, SourceCompleteness.COMPLETE, 1000, 100, 0))
    @Test fun `legacy branch preferences cannot create combined consent`() {
        val store = FcmProtectionStore(Storage())
        assertFalse(store.read().configured); assertNull(store.read().epoch)
        val on = store.confirm(sample())
        assertTrue(on.configured); assertNotNull(on.epoch); assertFalse(on.recoveryPrepared)
        assertNotEquals(on.epoch, store.confirm(sample()).epoch)
    }
    @Test fun `absent enable commits authority then readiness without changing installation on connection`() {
        val storage = Storage(); val store = FcmProtectionStore(storage)
        val on = store.confirm(sample())
        assertTrue(on.accepts(sample(), true))
        assertFalse(FcmProtectionStore(storage).read().recoveryPrepared)
        assertFalse(on.admits(sample(), true)); assertFalse(on.admits(sample(), false))
        val prepared = store.recoveryPrepared(on.epoch!!)
        assertTrue(prepared.recoveryPrepared)
        assertTrue(prepared.admits(sample(), true))
        assertFalse(prepared.admits(sample(), false, "22345678-1234-1234-1234-123456789abc"))
        assertTrue(prepared.accepts(sample(GcmConnectionState.REPORTED_CONNECTED), false))
        assertTrue(prepared.accepts(sample(GcmConnectionState.REPORTED_CONNECTED), true))
        assertEquals(prepared, FcmProtectionCodec.decode(FcmProtectionCodec.encode(prepared)))
    }
    @Test fun `OFF marker survives failed document write and rejects late preparation`() {
        val storage = Storage(); val store = FcmProtectionStore(storage); val on = store.confirm(sample())
        storage.failed = true
        assertThrows(IllegalStateException::class.java) { store.disable() }
        storage.failed = false
        assertFalse(FcmProtectionStore(storage).read().configured)
        assertThrows(IllegalStateException::class.java) { store.recoveryPrepared(on.epoch!!) }
    }
    @Test fun `new boot leaves maintenance installation scope but recovery requires explicit reconfirmation`() {
        val store = FcmProtectionStore(Storage()); val on = store.confirm(sample())
        val afterBoot = sample(boot = "22345678-1234-1234-1234-123456789abc")
        assertTrue(on.accepts(afterBoot, false)); assertFalse(on.accepts(afterBoot, true))
        assertFalse(on.accepts(afterBoot.copy(versionCode = FcmRecoveryPolicy.VERSION + 1), false))
        assertFalse(on.accepts(afterBoot.copy(lastUpdateTime = 901), false))
    }
    @Test fun `codec is bounded and corruption never restores combined ON`() {
        val store = FcmProtectionStore(Storage()); val encoded = FcmProtectionCodec.encode(store.confirm(sample()))
        assertTrue(encoded.size <= FcmProtectionCodec.MAX_BYTES)
        encoded[0] = 0
        assertThrows(IllegalArgumentException::class.java) { FcmProtectionCodec.decode(encoded) }
    }
}
