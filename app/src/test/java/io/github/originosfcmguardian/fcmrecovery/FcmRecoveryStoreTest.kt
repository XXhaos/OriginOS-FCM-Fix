package io.github.originosfcmguardian.fcmrecovery

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class FcmRecoveryStoreTest {
    private class Storage : FcmRecoveryStorage {
        var value = FcmRecoverySnapshot()
        var marker: FcmRecoverySnapshot? = null
        var mcsMarker: FcmRecoverySnapshot? = null
        var halted = false
        var failWrite = false
        override fun read() = value.copy(configured = value.configured && !halted,
            consumed = value.consumed || marker != null, consumedBoot = marker?.consumedBoot ?: value.consumedBoot,
            consumedAt = marker?.consumedAt ?: value.consumedAt, consumedElapsed = marker?.consumedElapsed ?: value.consumedElapsed,
            mcsConsumed = value.mcsConsumed || mcsMarker != null,
            mcsConsumedBoot = mcsMarker?.mcsConsumedBoot ?: value.mcsConsumedBoot,
            mcsConsumedAt = mcsMarker?.mcsConsumedAt ?: value.mcsConsumedAt,
            mcsConsumedElapsed = mcsMarker?.mcsConsumedElapsed ?: value.mcsConsumedElapsed)
        override fun write(value: FcmRecoverySnapshot) { if (failWrite) error("interrupted storage"); this.value = value }
        override fun markConsumed(value: FcmRecoverySnapshot) { marker = value }
        override fun clearConsumed() { marker = null }
        override fun markMcsConsumed(value: FcmRecoverySnapshot) { mcsMarker = value }
        override fun clearMcsConsumed() { mcsMarker = null }
        override fun halt() { halted = true }
        override fun releaseHalt() { halted = false }
    }
    private fun sample(state: GcmConnectionState = GcmConnectionState.SERVICE_ABSENT, at: Long = 1000, elapsed: Long = 100,
        complete: SourceCompleteness = SourceCompleteness.COMPLETE, socket: Boolean = true) =
        GmsConnectionObservation.Sample(1, 10208, FcmRecoveryPolicy.VERSION, 100, 900, FcmRecoveryPolicy.SIGNER,
            "12345678-1234-1234-1234-123456789abc", at, elapsed,
            SocketSource(SourceCompleteness.COMPLETE, socket), SocketSource(SourceCompleteness.COMPLETE, false),
            true, emptyList(), GcmServiceSource(state, complete, at, elapsed, 0))
    @Test fun `independent consent defaults off and consumes once across host recreation and OFF ON`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage); val missing = sample()
        assertNull(store.consume(missing))
        store.confirm(FcmRecoveryPolicy.binding(missing))
        assertNotNull(store.consume(missing)); assertNull(store.consume(missing))
        val recreated = FcmRecoveryStore(storage)
        assertNull(recreated.consume(missing))
        recreated.disable(); recreated.confirm(FcmRecoveryPolicy.binding(missing))
        assertNull(recreated.consume(missing))
    }
    @Test fun `crash before document write retains durable consumption and timeout results cannot replay`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage); val missing = sample()
        store.confirm(FcmRecoveryPolicy.binding(missing)); storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.consume(missing) }
        storage.failWrite = false
        val recreated = FcmRecoveryStore(storage)
        assertTrue(recreated.read().consumed); assertNull(recreated.consume(missing))
        recreated.append(FcmRecoveryEvent(1010, 110, FcmRecoveryEventAction.REQUEST_RESULT,
            status = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.TIMED_OUT))
        assertNull(recreated.consume(sample(at = 2000, elapsed = 200)))
    }
    @Test fun `only newer full connected owner socket with usable network rearms`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage); val missing = sample()
        store.confirm(FcmRecoveryPolicy.binding(missing)); store.consume(missing)
        val connected = sample(GcmConnectionState.REPORTED_CONNECTED, 2000, 200)
        listOf(sample(GcmConnectionState.REPORTED_DISCONNECTED, 2000, 200),
            sample(GcmConnectionState.UNKNOWN, 2000, 200, SourceCompleteness.MALFORMED),
            sample(GcmConnectionState.REPORTED_CONNECTED),
            sample(GcmConnectionState.REPORTED_CONNECTED, 2000, 200, socket = false),
            connected.copy(versionCode = FcmRecoveryPolicy.VERSION + 1)).forEach {
            assertTrue(store.rearm(it, 200, true).consumed)
        }
        assertTrue(store.rearm(connected, 200, false).consumed)
        assertFalse(store.rearm(connected, 200, true).consumed)
        assertNotNull(store.consume(sample(at = 3000, elapsed = 300)))
    }
    @Test fun `synchronous OFF barrier survives failed save and atomic backup ON`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage)
        store.confirm(FcmRecoveryPolicy.binding(sample())); storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.disable() }
        storage.failWrite = false
        assertFalse(FcmRecoveryStore(storage).read().configured)
        assertNull(FcmRecoveryStore(storage).consume(sample()))
    }
    @Test fun `bounded private metadata codec rejects damage and contains no raw content`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage)
        repeat(90) { store.append(FcmRecoveryEvent(it + 1L, it.toLong(), FcmRecoveryEventAction.OBSERVATION)) }
        assertEquals(64, store.read().events.size)
        val encoded = FcmRecoveryStoreCodec.encode(store.read())
        assertEquals(store.read(), FcmRecoveryStoreCodec.decode(encoded))
        encoded[0] = 0
        assertThrows(IllegalArgumentException::class.java) { FcmRecoveryStoreCodec.decode(encoded) }
    }
    @Test fun `connected heartbeat policy stays separate from exact absence recovery`() {
        assertNotNull(GmsHeartbeatPolicy.rejection(sample(), 100))
        assertNull(GmsHeartbeatPolicy.rejection(sample(GcmConnectionState.REPORTED_CONNECTED), 100))
        assertFalse(FcmRecoveryPolicy.absent(sample(GcmConnectionState.REPORTED_DISCONNECTED), 100))
        assertFalse(FcmRecoveryPolicy.absent(sample(GcmConnectionState.UNKNOWN, complete = SourceCompleteness.MALFORMED), 100))
    }
    @Test fun `MCS marker survives torn document restart OFF ON and oscillation without touching SEND marker`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage)
        val dis = sample(GcmConnectionState.REPORTED_DISCONNECTED, socket = false).copy(versionCode = FcmRecoveryPolicy.MCS_VERSION)
        store.confirm(FcmRecoveryPolicy.binding(dis))
        val operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.consume(dis, operation) }
        storage.failWrite = false
        val resumed = FcmRecoveryStore(storage)
        assertTrue(resumed.read().mcsConsumed); assertFalse(resumed.read().consumed); assertNull(storage.marker)
        resumed.disable(); resumed.confirm(FcmRecoveryPolicy.binding(dis))
        assertNull(resumed.consume(dis, operation))
        val absent = dis.copy(gcmService = dis.gcmService.copy(state = GcmConnectionState.SERVICE_ABSENT))
        assertNotNull(resumed.consume(absent))
        val absenceBytes = FcmRecoveryStoreCodec.encode(storage.marker!!.copy(events = emptyList()), legacyMarker = true)
        for (state in listOf(GcmConnectionState.SERVICE_ABSENT, GcmConnectionState.REPORTED_DISCONNECTED)) {
            val changed = dis.copy(observedAtMillis = 2000, elapsedRealtimeMillis = 200,
                gcmService = GcmServiceSource(state, SourceCompleteness.COMPLETE, 2000, 200, 0))
            val saved = resumed.rearm(changed, 200, true)
            assertTrue(saved.consumed); assertTrue(saved.mcsConsumed)
            assertArrayEquals(absenceBytes, FcmRecoveryStoreCodec.encode(storage.marker!!.copy(events = emptyList()), legacyMarker = true))
        }
        val connected = dis.copy(observedAtMillis = 2000, elapsedRealtimeMillis = 200,
            tcp = SocketSource(SourceCompleteness.COMPLETE, true),
            gcmService = GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 2000, 200, 0))
        assertTrue(resumed.rearm(connected, 200, false).mcsConsumed)
        assertTrue(resumed.rearm(connected.copy(tcp = SocketSource(SourceCompleteness.COMPLETE, false)), 200, true).mcsConsumed)
        assertTrue(resumed.rearm(connected, 200, true, false).mcsConsumed)
        assertFalse(resumed.rearm(connected, 200, true, true).mcsConsumed)
        assertNull(storage.mcsMarker)
        assertNotNull(resumed.consume(dis.copy(gcmService = dis.gcmService.copy(observedAtMillis = 3000, elapsedRealtimeMillis = 300)), operation))
    }
    @Test fun `legacy version two stores absence and power unchanged with no fabricated MCS operation`() {
        val event = FcmRecoveryEvent(1000, 100, FcmRecoveryEventAction.REQUEST_RESULT,
            status = FcmRecoveryStatus.REQUESTED, reason = FcmRecoveryReason.COMMAND_ACCEPTED)
        val old = FcmRecoverySnapshot(true, "a".repeat(64), true, "12345678-1234-1234-1234-123456789abc", 1000, 100,
            events = listOf(event), powerSampleAtMillis = 1000)
        assertEquals(old, FcmRecoveryStoreCodec.decode(FcmRecoveryStoreCodec.encode(old, legacyMarker = true)))
        assertEquals(old, FcmRecoveryStoreCodec.decode(FcmRecoveryStoreCodec.encode(old)))
    }
    @Test fun `hourly reservation survives recreation eviction and OFF ON and blocks backward clock`() {
        val storage = Storage(); val store = FcmRecoveryStore(storage)
        val start = 10_000L
        assertTrue(store.reservePowerSample(start))
        // Even failed optional acquisition leaves this reservation consumed.
        val recreated = FcmRecoveryStore(storage)
        repeat(70) { recreated.append(FcmRecoveryEvent(it + 1L, 0, FcmRecoveryEventAction.OBSERVATION)) }
        recreated.disable(); recreated.confirm(FcmRecoveryPolicy.binding(sample()))
        assertFalse(recreated.reservePowerSample(start - 1))
        assertFalse(recreated.reservePowerSample(start + FCM_POWER_SAMPLE_INTERVAL_MILLIS - 1))
        assertTrue(recreated.reservePowerSample(start + FCM_POWER_SAMPLE_INTERVAL_MILLIS))
        assertEquals(start + FCM_POWER_SAMPLE_INTERVAL_MILLIS, recreated.read().powerSampleAtMillis)
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) {
            recreated.reservePowerSample(start + 2 * FCM_POWER_SAMPLE_INTERVAL_MILLIS)
        }
    }
    @Test fun `full power ring roundtrips within bounded codec and null remains unknown`() {
        val power = FcmPowerReference(100, FcmChargingStatus.NOT_CHARGING, FcmPluggedSource.WIRELESS,
            false, true, Long.MAX_VALUE, Long.MAX_VALUE, "12345678-1234-1234-1234-123456789abc")
        val event = FcmRecoveryEvent(Long.MAX_VALUE, Long.MAX_VALUE, FcmRecoveryEventAction.REQUEST_RESULT,
            GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.UNAVAILABLE, SocketObservation.UNKNOWN,
            false, FcmRecoveryStatus.NOT_SENT, FcmRecoveryReason.IDENTITY_CHANGED,
            FcmRecoveryEligibility.NETWORK_UNAVAILABLE, GmsUnknown.CAPABILITY_UNAVAILABLE, power)
        val value = FcmRecoverySnapshot(events = List(64) { event }, powerSampleAtMillis = Long.MAX_VALUE)
        val encoded = FcmRecoveryStoreCodec.encode(value)
        assertTrue(encoded.size <= FcmRecoveryStoreCodec.MAX_BYTES)
        assertEquals(value, FcmRecoveryStoreCodec.decode(encoded))
        val missing = value.copy(events = listOf(event.copy(power = FcmPowerReference(), observationUnknownReason = null)))
        assertEquals(missing, FcmRecoveryStoreCodec.decode(FcmRecoveryStoreCodec.encode(missing)))
    }
    @Test fun `codec version one history has no fabricated power or unknown reason`() {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x46435231); out.writeInt(1)
            out.writeBoolean(false); out.writeUTF(""); out.writeBoolean(false); out.writeUTF("")
            out.writeLong(0); out.writeLong(0); out.writeInt(1)
            out.writeLong(100); out.writeLong(10); out.writeUTF("OBSERVATION")
            out.writeUTF("UNKNOWN"); out.writeUTF("UNAVAILABLE"); out.writeUTF("UNKNOWN")
            out.writeBoolean(false); out.writeUTF(""); out.writeUTF(""); out.writeUTF("HOST_UNAVAILABLE")
        } }.toByteArray()
        val old = FcmRecoveryStoreCodec.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        assertNull(old.powerSampleAtMillis); assertNull(old.events.single().power)
        assertNull(old.events.single().observationUnknownReason)
        assertEquals(old, FcmRecoveryStoreCodec.decode(FcmRecoveryStoreCodec.encode(old)))
    }
}
