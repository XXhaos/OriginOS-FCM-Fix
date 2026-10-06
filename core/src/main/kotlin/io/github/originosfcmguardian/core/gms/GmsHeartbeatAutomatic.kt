package io.github.originosfcmguardian.core.gms

import java.security.MessageDigest

const val GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS = 900_000L
data class GmsAutoSession(val id: String, val generation: Long, val bindingDigest: String) {
    init {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        require(generation > 0 && bindingDigest.matches(Regex("[a-f0-9]{64}")))
    }
}
enum class GmsAutoArmReason { ARMED, INVALID_REQUEST, BUSY, NOT_ELIGIBLE, IDENTITY_CHANGED,
    CALLER_UNAVAILABLE, LIFETIME_ENDED, TIMED_OUT, BRIDGE_UNAVAILABLE, MALFORMED_RESPONSE }
data class GmsAutoArmResult(val session: GmsAutoSession?, val reason: GmsAutoArmReason) {
    init { require((session != null) == (reason == GmsAutoArmReason.ARMED)) }
}
fun gmsHeartbeatBindingDigest(sample: GmsConnectionObservation.Sample): String = MessageDigest.getInstance("SHA-256")
    .digest(listOf("gms-heartbeat-binding-v1", sample.uid, sample.versionCode, sample.firstInstallTime,
        sample.lastUpdateTime, sample.signerSha256).joinToString("\n").toByteArray(Charsets.US_ASCII))
    .joinToString("") { "%02x".format(it.toInt() and 255) }

object GmsAutoArmCodec {
    const val BUNDLE_KEY = "gms_auto_arm_v1"
    fun encode(requestId: Long, result: GmsAutoArmResult) = listOf("1", requestId, result.reason,
        result.session?.id ?: "", result.session?.generation ?: "", result.session?.bindingDigest ?: "").joinToString("|")
    fun decode(raw: String?, requestId: Long): GmsAutoArmResult = runCatching {
        require(requestId > 0 && raw != null && raw.length <= 256)
        val f = raw.split('|'); require(f.size == 6 && f[0] == "1" && f[1] == requestId.toString())
        val reason = GmsAutoArmReason.valueOf(f[2])
        val session = if (reason == GmsAutoArmReason.ARMED) GmsAutoSession(f[3], f[4].toLong(), f[5])
        else { require(f.drop(3).all { it.isEmpty() }); null }
        GmsAutoArmResult(session, reason)
    }.getOrElse { GmsAutoArmResult(null, GmsAutoArmReason.MALFORMED_RESPONSE) }
}
