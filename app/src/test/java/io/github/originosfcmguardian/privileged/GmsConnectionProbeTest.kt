package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GmsConnectionProbeTest {
    private val identity = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
        10234, 99, 123, 456, "a".repeat(64))
    private val environment = GmsProbeEnvironment(0, 2000, "12345678-1234-1234-1234-123456789abc")
    private inner class Port : GmsConnectionReadPort {
        var currentIdentity = identity
        var currentEnvironment = environment
        var owners: List<String>? = listOf(GMS_OBSERVATION_PACKAGE)
        var afterSockets: () -> Unit = {}
        var socketCalls = 0
        var inspection = GmsReceiverInspection(true, emptyList())
        var selfReport = GcmServiceSource.unavailable()
        var afterSelfReport: () -> Unit = {}
        override fun environment() = currentEnvironment
        override fun gmsPackage() = currentIdentity
        override fun packagesForUid(uid: Int) = owners
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean): SocketSource {
            socketCalls++
            if (ipv6) afterSockets()
            return SocketSource(SourceCompleteness.COMPLETE, !ipv6)
        }
        override fun reconnectReceivers() = inspection
        override fun gcmService(interrupted: () -> Boolean): GcmServiceSource {
            afterSelfReport()
            return selfReport
        }
        override fun elapsedRealtime() = 900L
    }
    private fun observe(port: Port, interruption: () -> GmsUnknown? = { null }) =
        observeGmsConnection(1, port, interruption) { 1000 }
    private fun reason(value: GmsConnectionObservation) = (value as GmsConnectionObservation.Unknown).reason
    @Test fun fixedIdentityProducesSampleWithoutAnyRawNetworkFields() {
        val result = observe(Port()) as GmsConnectionObservation.Sample
        assertEquals(SocketObservation.OBSERVED, result.observation)
        assertEquals(identity.uid, result.uid)
        assertEquals(environment.bootId, result.bootId)
        assertEquals(1000L, result.observedAtMillis)
        assertEquals(900L, result.elapsedRealtimeMillis)
        assertEquals(result, GmsObservationCodec.decode(GmsObservationCodec.encode(result), 1))
    }
    @Test fun packageIdentityDriftDiscardsPositiveSockets() {
        val mutations = listOf<(GmsPackageIdentity) -> GmsPackageIdentity>(
            { it.copy(uid = 10444) }, { it.copy(versionCode = 100) }, { it.copy(firstInstallTime = 999) },
            { it.copy(lastUpdateTime = 999) }, { it.copy(signerSha256 = "b".repeat(64)) })
        mutations.forEach { mutate ->
            val port = Port(); port.afterSockets = { port.currentIdentity = mutate(identity) }
            assertEquals(GmsUnknown.IDENTITY_CHANGED, reason(observe(port)))
        }
    }
    @Test fun bootUserAndShellDriftDiscardsPositiveSockets() {
        listOf(environment.copy(currentUser = 10), environment.copy(serviceUid = 0),
            environment.copy(bootId = "87654321-1234-1234-1234-123456789abc")).forEach { drift ->
            val port = Port(); port.afterSockets = { port.currentEnvironment = drift }
            assertEquals(GmsUnknown.IDENTITY_CHANGED, reason(observe(port)))
        }
    }
    @Test fun ambiguousOwnershipBeforeOrAfterSamplingCannotProducePositive() {
        listOf(null, emptyList(), listOf(GMS_OBSERVATION_PACKAGE, "other.pkg"), listOf("other.pkg"),
            listOf(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE)).forEach { owners ->
            val before = Port(); before.owners = owners
            assertEquals(GmsUnknown.SHARED_UID, reason(observe(before))); assertEquals(0, before.socketCalls)
            val after = Port(); after.afterSockets = { after.owners = owners }
            assertEquals(GmsUnknown.SHARED_UID, reason(observe(after)))
        }
    }
    @Test fun wrongPackageUserAndShellFailBeforeSources() {
        val wrongPackage = Port(); wrongPackage.currentIdentity = identity.copy(packageName = "other.pkg")
        assertEquals(GmsUnknown.IDENTITY_UNKNOWN, reason(observe(wrongPackage)))
        val wrongUser = Port(); wrongUser.currentEnvironment = environment.copy(currentUser = 10)
        assertEquals(GmsUnknown.WRONG_USER, reason(observe(wrongUser)))
        val wrongShell = Port(); wrongShell.currentEnvironment = environment.copy(serviceUid = 0)
        assertEquals(GmsUnknown.SHELL_UNAVAILABLE, reason(observe(wrongShell)))
    }
    @Test fun timeoutOrCancellationAfterMatchReturnsEntireUnknown() {
        listOf(GmsUnknown.CANCELLED, GmsUnknown.TIMED_OUT).forEach { cause ->
            val port = Port(); var interrupted: GmsUnknown? = null
            port.afterSockets = { interrupted = cause }
            assertEquals(cause, reason(observe(port) { interrupted }))
        }
    }
    @Test fun receiverMetadataIsIndependentOfSocketEvidenceAndHasNoSendPath() {
        val port = Port(); port.inspection = GmsReceiverInspection(false, emptyList())
        val unknownReceiver = observe(port) as GmsConnectionObservation.Sample
        assertEquals(SocketObservation.OBSERVED, unknownReceiver.observation)
        assertFalse(unknownReceiver.receiverInspectionComplete)
        port.inspection = GmsReceiverInspection(true, listOf(ReconnectReceiver("com.google.android.gms.Receiver", false, false, "android.permission.DUMP")))
        val metadata = observe(port) as GmsConnectionObservation.Sample
        assertFalse(metadata.receivers.single().enabled); assertFalse(metadata.receivers.single().exported)
        assertEquals("android.permission.DUMP", metadata.receivers.single().permission)
    }
    @Test fun canonicalSignerSetIsBoundedAndOrderIndependent() {
        val a = byteArrayOf(1); val b = byteArrayOf(2)
        assertEquals(gmsSignerDigest(listOf(a, b)), gmsSignerDigest(listOf(b, a)))
        assertNotEquals(gmsSignerDigest(listOf(a)), gmsSignerDigest(listOf(a, b)))
        assertThrows(IllegalArgumentException::class.java) { gmsSignerDigest(listOf(a, a)) }
        assertThrows(IllegalArgumentException::class.java) { gmsSignerDigest(listOf(ByteArray(65537))) }
    }
    @Test fun gcmSelfReportStaysIndependentOfTcpAndCannotSurviveIdentityDrift() {
        val port = Port()
        port.selfReport = GcmServiceSource(GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.COMPLETE)
        val sample = observe(port) as GmsConnectionObservation.Sample
        assertEquals(SocketObservation.OBSERVED, sample.observation)
        assertEquals(GcmConnectionState.REPORTED_DISCONNECTED, sample.gcmService.state)
        assertEquals(0, sample.gcmService.userId)
        assertEquals(1000L, sample.gcmService.observedAtMillis)
        port.afterSelfReport = { port.currentIdentity = identity.copy(versionCode = 100) }
        assertEquals(GmsUnknown.IDENTITY_CHANGED, reason(observe(port)))
    }
    @Test fun boundedChildTimeoutDoesNotEraseSeparateTcpEvidence() {
        val port = Port()
        port.selfReport = GcmServiceParser.unknown(SourceCompleteness.TIMED_OUT)
        val sample = observe(port) as GmsConnectionObservation.Sample
        assertEquals(SocketObservation.OBSERVED, sample.observation)
        assertEquals(GcmConnectionState.UNKNOWN, sample.gcmService.state)
        assertEquals(SourceCompleteness.TIMED_OUT, sample.gcmService.completeness)
    }
    @Test fun stuckSystemReadTimesOutReturnsBusyAndCannotPublishLateSample() {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val port = Port()
        val runner = GmsConnectionProbeRunner({
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            port
        }, 100)
        try {
            assertEquals(GmsUnknown.TIMED_OUT, reason(runner.read(1) { false }))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(runner.isQuiescent())
            assertEquals(GmsUnknown.BUSY, reason(runner.read(2) { false }))
            release.countDown()
        } finally { release.countDown(); runner.close() }
    }
}
