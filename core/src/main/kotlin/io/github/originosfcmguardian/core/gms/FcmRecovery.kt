package io.github.originosfcmguardian.core.gms

import java.security.MessageDigest

/** Runtime observation qualification. Sender capability is independently rechecked in shell. */
object FcmRecoveryPolicy {
    /** Historical device evidence/fixtures only; never an admission allowlist. */
    const val VERSION = 263635035L
    const val SIGNER = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"
    const val COMPONENT = "com.google.android.gms.gcm.GcmSenderProxy"
    const val ACTION = "com.google.android.gcm.intent.SEND"
    /** Historical device evidence/fixtures only; never an admission allowlist. */
    const val MCS_VERSION = 263737035L
    const val MCS_ACTION = "com.google.android.intent.action.MCS_HEARTBEAT"
    fun qualified(sample: GmsConnectionObservation.Sample, now: Long): Boolean {
        val s = sample.gcmService
        val elapsed = s.elapsedRealtimeMillis ?: return false
        return sample.signerSha256 == SIGNER && sample.isFresh(now) &&
            s.userId == 0 && s.completeness == SourceCompleteness.COMPLETE &&
            s.state != GcmConnectionState.UNKNOWN &&
            now >= elapsed && now - elapsed <= 30_000
    }
    fun absent(sample: GmsConnectionObservation.Sample, now: Long) = qualified(sample, now) &&
        sample.gcmService.state == GcmConnectionState.SERVICE_ABSENT
    fun disconnected(sample: GmsConnectionObservation.Sample, now: Long) = qualified(sample, now) &&
        sample.gcmService.state == GcmConnectionState.REPORTED_DISCONNECTED &&
        sample.observation == SocketObservation.NO_MATCH &&
        sample.tcp.completeness == SourceCompleteness.COMPLETE && sample.tcp6.completeness == SourceCompleteness.COMPLETE
    fun connected(sample: GmsConnectionObservation.Sample, now: Long) = qualified(sample, now) &&
        sample.gcmService.state == GcmConnectionState.REPORTED_CONNECTED &&
        sample.observation == SocketObservation.OBSERVED &&
        sample.tcp.completeness == SourceCompleteness.COMPLETE && sample.tcp6.completeness == SourceCompleteness.COMPLETE
    /** Includes boot: a changed boot conservatively requires a fresh explicit trial confirmation. */
    fun binding(sample: GmsConnectionObservation.Sample): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(listOf(sample.uid, sample.versionCode,
            sample.firstInstallTime, sample.lastUpdateTime, sample.signerSha256, sample.bootId)
            .joinToString("|").toByteArray(Charsets.US_ASCII))
        // Preserve persisted bindings exactly; only replace per-byte Formatter allocations.
        val encoded = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val byte = digest[index].toInt() and 255
            encoded[index * 2] = HEX[byte ushr 4]
            encoded[index * 2 + 1] = HEX[byte and 15]
        }
        return String(encoded)
    }
    private const val HEX = "0123456789abcdef"
}

enum class FcmRecoveryStatus { NOT_SENT, REQUESTED, UNKNOWN }
/** Append-only wire values; absence and present-disconnected requests never share consumption. */
enum class FcmRecoveryOperation { SERVICE_ABSENT_SEND, PRESENT_DISCONNECTED_MCS }
enum class FcmRecoveryReason { COMMAND_ACCEPTED, NOT_ABSENT, IDENTITY_CHANGED, TARGET_UNQUALIFIED,
    AUTHORITY_LOST, BUSY, TIMED_OUT, CANCELLED, COMMAND_FAILED, BRIDGE_UNAVAILABLE, INVALID_REQUEST, NOT_DISCONNECTED }
data class FcmRecoveryResult(val requestId: Long, val status: FcmRecoveryStatus, val reason: FcmRecoveryReason,
    val operation: FcmRecoveryOperation = FcmRecoveryOperation.SERVICE_ABSENT_SEND) {
    init { require((status == FcmRecoveryStatus.REQUESTED) == (reason == FcmRecoveryReason.COMMAND_ACCEPTED)) }
}
object FcmRecoveryCodec {
    const val KEY = "fcm_recovery_trial_v1"
    fun encode(value: FcmRecoveryResult) = if (value.operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND)
        "1|${value.requestId}|${value.status}|${value.reason}" else "2|${value.requestId}|${value.status}|${value.reason}|${value.operation}"
    fun decode(raw: String?, requestId: Long, operation: FcmRecoveryOperation = FcmRecoveryOperation.SERVICE_ABSENT_SEND): FcmRecoveryResult = runCatching {
        require(raw != null && raw.length <= 160 && requestId > 0)
        val f = raw.split('|'); require((f.size == 4 && f[0] == "1" || f.size == 5 && f[0] == "2") && f[1] == requestId.toString())
        val actual = if (f.size == 4) FcmRecoveryOperation.SERVICE_ABSENT_SEND else FcmRecoveryOperation.valueOf(f[4])
        require(actual == operation)
        FcmRecoveryResult(requestId, FcmRecoveryStatus.valueOf(f[2]), FcmRecoveryReason.valueOf(f[3]), actual)
    }.getOrElse { FcmRecoveryResult(requestId, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.BRIDGE_UNAVAILABLE, operation) }
}
