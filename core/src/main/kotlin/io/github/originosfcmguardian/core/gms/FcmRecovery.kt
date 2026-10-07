package io.github.originosfcmguardian.core.gms

import java.security.MessageDigest

/** Runtime observation qualification. Sender capability is independently rechecked in shell. */
object FcmRecoveryPolicy {
    /** Historical device evidence/fixtures only; never an admission allowlist. */
    const val VERSION = 263635035L
    const val SIGNER = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"
    const val COMPONENT = "com.google.android.gms.gcm.GcmSenderProxy"
    const val ACTION = "com.google.android.gcm.intent.SEND"
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
    fun connected(sample: GmsConnectionObservation.Sample, now: Long) = qualified(sample, now) &&
        sample.gcmService.state == GcmConnectionState.REPORTED_CONNECTED &&
        sample.observation == SocketObservation.OBSERVED &&
        sample.tcp.completeness == SourceCompleteness.COMPLETE && sample.tcp6.completeness == SourceCompleteness.COMPLETE
    /** Includes boot: a changed boot conservatively requires a fresh explicit trial confirmation. */
    fun binding(sample: GmsConnectionObservation.Sample): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(sample.uid, sample.versionCode, sample.firstInstallTime, sample.lastUpdateTime,
            sample.signerSha256, sample.bootId).joinToString("|").toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

enum class FcmRecoveryStatus { NOT_SENT, REQUESTED, UNKNOWN }
enum class FcmRecoveryReason { COMMAND_ACCEPTED, NOT_ABSENT, IDENTITY_CHANGED, TARGET_UNQUALIFIED,
    AUTHORITY_LOST, BUSY, TIMED_OUT, CANCELLED, COMMAND_FAILED, BRIDGE_UNAVAILABLE, INVALID_REQUEST }
data class FcmRecoveryResult(val requestId: Long, val status: FcmRecoveryStatus, val reason: FcmRecoveryReason) {
    init { require((status == FcmRecoveryStatus.REQUESTED) == (reason == FcmRecoveryReason.COMMAND_ACCEPTED)) }
}
object FcmRecoveryCodec {
    const val KEY = "fcm_recovery_trial_v1"
    fun encode(value: FcmRecoveryResult) = "1|${value.requestId}|${value.status}|${value.reason}"
    fun decode(raw: String?, requestId: Long): FcmRecoveryResult = runCatching {
        require(raw != null && raw.length <= 160 && requestId > 0)
        val f = raw.split('|'); require(f.size == 4 && f[0] == "1" && f[1] == requestId.toString())
        FcmRecoveryResult(requestId, FcmRecoveryStatus.valueOf(f[2]), FcmRecoveryReason.valueOf(f[3]))
    }.getOrElse { FcmRecoveryResult(requestId, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.BRIDGE_UNAVAILABLE) }
}
