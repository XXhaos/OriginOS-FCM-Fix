package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.autostart.lowerHexDigest
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal const val GMS_OBSERVATION_PACKAGE = "com.google.android.gms"
internal const val GMS_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT"
internal data class GmsProbeEnvironment(val currentUser: Int, val serviceUid: Int, val bootId: String)
internal data class GmsPackageIdentity(
    val packageName: String, val applicationPackageName: String, val uid: Int,
    val versionCode: Long, val firstInstallTime: Long, val lastUpdateTime: Long, val signerSha256: String,
    /** Transient PackageManager evidence only; never serialized, persisted or supplied by IPC. */
    val signingCertificateHistorySha256: List<String>? = null,
    val hasMultipleSigners: Boolean = false,
)
internal data class GmsReceiverInspection(val complete: Boolean, val receivers: List<ReconnectReceiver>)
internal interface GmsConnectionReadPort {
    fun environment(): GmsProbeEnvironment
    fun gmsPackage(): GmsPackageIdentity?
    fun packagesForUid(uid: Int): List<String>?
    fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean): SocketSource
    fun gcmService(interrupted: () -> Boolean): GcmServiceSource = GcmServiceSource.unavailable()
    fun reconnectReceivers(): GmsReceiverInspection
    fun elapsedRealtime(): Long
    fun recoverySenderQualified(): Boolean = false
    /** Allows a bounded request; absent export metadata is not proof of export or recovery. */
    fun disconnectedMcsAttemptAllowed(): Boolean = false
    fun usableInternet(): Boolean = false
}

internal fun gmsSignerDigest(certificates: List<ByteArray>): String {
    require(certificates.size in 1..16 && certificates.all { it.size in 1..65536 })
    val hashes = certificates.map { lowerHexDigest(MessageDigest.getInstance("SHA-256").digest(it)) }.sorted()
    require(hashes.distinct().size == hashes.size)
    // One certificate retains its normal SHA; multisigner identity hashes the canonical set.
    return if (hashes.size == 1) hashes.single() else lowerHexDigest(MessageDigest.getInstance("SHA-256")
        .digest(hashes.joinToString(",").toByteArray(Charsets.US_ASCII)))
}

/** Android's single-signer proof-of-rotation history, bounded before hashing certificates. */
internal fun gmsSigningHistoryDigests(certificates: List<ByteArray>): List<String>? {
    if (certificates.size !in 1..32 || certificates.any { it.size !in 1..65536 }) return null
    val hashes = certificates.map { gmsSignerDigest(listOf(it)) }
    return hashes.takeIf { it.distinct().size == it.size }
}

internal fun validGmsPackageIdentity(value: GmsPackageIdentity?): GmsPackageIdentity? = value?.takeIf {
    it.packageName == GMS_OBSERVATION_PACKAGE && it.applicationPackageName == GMS_OBSERVATION_PACKAGE &&
        it.uid in 10_000..99_999 && it.versionCode >= 0 && it.firstInstallTime > 0 && it.lastUpdateTime > 0 &&
        it.signerSha256.matches(Regex("[a-f0-9]{64}"))
}

/** A canonical authority pin, not an alternative current-signer identity. */
internal fun gmsGoogleTrustAnchor(identity: GmsPackageIdentity): String? {
    if (identity.hasMultipleSigners) return null
    if (identity.signerSha256 == FcmRecoveryPolicy.SIGNER) return FcmRecoveryPolicy.SIGNER
    val history = identity.signingCertificateHistorySha256 ?: return null
    if (history.size !in 1..32 || history.any { !it.matches(Regex("[a-f0-9]{64}")) } ||
        history.distinct().size != history.size || identity.signerSha256 !in history ||
        FcmRecoveryPolicy.SIGNER !in history) return null
    return FcmRecoveryPolicy.SIGNER
}

/** The sample's canonical pin must be backed by a fresh, otherwise exact installation identity. */
internal fun gmsIdentityMatchesSample(identity: GmsPackageIdentity?, sample: GmsConnectionObservation.Sample): Boolean {
    val current = validGmsPackageIdentity(identity) ?: return false
    val signerMatches = if (sample.signerSha256 == FcmRecoveryPolicy.SIGNER)
        gmsGoogleTrustAnchor(current) == FcmRecoveryPolicy.SIGNER
    else (gmsGoogleTrustAnchor(current) ?: current.signerSha256) == sample.signerSha256
    return current.uid == sample.uid && current.versionCode == sample.versionCode &&
        current.firstInstallTime == sample.firstInstallTime && current.lastUpdateTime == sample.lastUpdateTime &&
        signerMatches
}

internal fun qualifiedGmsMcsIdentity(identity: GmsPackageIdentity?, serviceUid: Int, currentUser: Int,
    owners: List<String>?): Boolean = validGmsPackageIdentity(identity)?.let {
    serviceUid == 2000 && currentUser == 0 && owners == listOf(GMS_OBSERVATION_PACKAGE) &&
        gmsGoogleTrustAnchor(it) == FcmRecoveryPolicy.SIGNER
} == true

internal fun observeGmsConnection(
    requestId: Long, port: GmsConnectionReadPort, interruption: () -> GmsUnknown? = { null },
    nowMillis: () -> Long = System::currentTimeMillis,
): GmsConnectionObservation {
    fun unknown(reason: GmsUnknown) = GmsConnectionObservation.Unknown(requestId, reason)
    if (requestId <= 0) return unknown(GmsUnknown.INVALID_REQUEST)
    return try {
        interruption()?.let { return unknown(it) }
        val environment = port.environment()
        if (environment.serviceUid != 2000) return unknown(GmsUnknown.SHELL_UNAVAILABLE)
        if (environment.currentUser != 0) return unknown(GmsUnknown.WRONG_USER)
        val before = validGmsPackageIdentity(port.gmsPackage()) ?: return unknown(GmsUnknown.IDENTITY_UNKNOWN)
        interruption()?.let { return unknown(it) }
        if (port.packagesForUid(before.uid) != listOf(GMS_OBSERVATION_PACKAGE)) return unknown(GmsUnknown.SHARED_UID)
        val sampledAt = nowMillis()
        val sampledElapsed = port.elapsedRealtime()
        val tcp = port.sockets(false, before.uid) { interruption() != null }
        interruption()?.let { return unknown(it) }
        val tcp6 = port.sockets(true, before.uid) { interruption() != null }
        interruption()?.let { return unknown(it) }
        val serviceAt = nowMillis()
        val serviceElapsed = port.elapsedRealtime()
        val gcmService = runCatching { port.gcmService { interruption() != null } }
            .getOrElse { GcmServiceSource.unavailable() }
            .copy(observedAtMillis = serviceAt, elapsedRealtimeMillis = serviceElapsed, userId = environment.currentUser)
        interruption()?.let { return unknown(it) }
        val receiver = runCatching { port.reconnectReceivers() }.getOrElse { GmsReceiverInspection(false, emptyList()) }
        interruption()?.let { return unknown(it) }
        val after = validGmsPackageIdentity(port.gmsPackage()) ?: return unknown(GmsUnknown.IDENTITY_UNKNOWN)
        if (before != after || port.environment() != environment) return unknown(GmsUnknown.IDENTITY_CHANGED)
        if (port.packagesForUid(after.uid) != listOf(GMS_OBSERVATION_PACKAGE)) return unknown(GmsUnknown.SHARED_UID)
        // Recheck environment after the final ownership Binder call as well.
        if (port.environment() != environment) return unknown(GmsUnknown.IDENTITY_CHANGED)
        interruption()?.let { return unknown(it) }
        // Compare raw current signer and PM evidence before deriving the wire-compatible authority pin.
        GmsConnectionObservation.Sample(requestId, after.uid, after.versionCode, after.firstInstallTime,
            after.lastUpdateTime, gmsGoogleTrustAnchor(after) ?: after.signerSha256, environment.bootId, sampledAt, sampledElapsed,
            tcp, tcp6, receiver.complete, receiver.receivers, gcmService)
    } catch (_: Throwable) { unknown(interruption() ?: GmsUnknown.CAPABILITY_UNAVAILABLE) }
}

/** One worker, no queue, bounded caller latency even when a system Binder read is stuck. */
internal class GmsConnectionProbeRunner(
    private val portFactory: () -> GmsConnectionReadPort, private val budgetMillis: Long = 8_000,
    keepAliveMillis: Long = 30_000,
    threadFactory: ThreadFactory = ThreadFactory { task ->
        Thread(task, "guardian-gms-connection-read").apply { isDaemon = true }
    },
) {
    init { require(budgetMillis in 1..8_000); require(keepAliveMillis > 0) }
    private val worker = ThreadPoolExecutor(0, 1, keepAliveMillis, TimeUnit.MILLISECONDS, SynchronousQueue(),
        threadFactory, ThreadPoolExecutor.AbortPolicy())
    private val activity = PrivilegedWorkerActivity()
    fun isQuiescent(): Boolean = activity.isQuiescent()
    fun read(requestId: Long, wasCancelled: () -> Boolean): GmsConnectionObservation {
        fun unknown(reason: GmsUnknown) = GmsConnectionObservation.Unknown(requestId, reason)
        if (requestId <= 0) return unknown(GmsUnknown.INVALID_REQUEST)
        val abandoned = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun interruption(): GmsUnknown? = when {
            abandoned.get() || wasCancelled() -> GmsUnknown.CANCELLED
            System.nanoTime() >= deadline -> GmsUnknown.TIMED_OUT
            else -> null
        }
        interruption()?.let { return unknown(it) }
        val settlement = activity.track()
        val future = try { worker.submit<GmsConnectionObservation> {
            if (!settlement.workerEntered()) return@submit unknown(GmsUnknown.CANCELLED)
            try { observeGmsConnection(requestId, portFactory(), ::interruption) }
            catch (_: Throwable) { unknown(interruption() ?: GmsUnknown.CAPABILITY_UNAVAILABLE) }
            finally { settlement.workerEnded(false, null) }
        } } catch (_: RejectedExecutionException) { settlement.abandonQueued(); return unknown(GmsUnknown.BUSY) }
        try {
            while (true) {
                interruption()?.let { return unknown(it) }
                try {
                    val value = future.get(minOf((deadline - System.nanoTime()).coerceAtLeast(1),
                        TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)
                    return interruption()?.let(::unknown) ?: value
                } catch (_: TimeoutException) { }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return unknown(GmsUnknown.CANCELLED) }
        catch (_: Throwable) { return unknown(interruption() ?: GmsUnknown.CAPABILITY_UNAVAILABLE) }
        finally { abandoned.set(true); settlement.abandonQueued(); future.cancel(true) }
    }
    fun close() { worker.shutdownNow() }
}
