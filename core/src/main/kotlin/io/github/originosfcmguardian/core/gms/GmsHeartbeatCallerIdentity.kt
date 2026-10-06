package io.github.originosfcmguardian.core.gms

data class GmsHeartbeatCallerIdentity(val uid: Int, val pid: Int, val startTicks: Long, val kernelBootId: String) {
    init {
        require(uid in 10_000..99_999 && pid in 1..4_194_304 && startTicks > 0)
        require(kernelBootId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
    }
}

enum class GmsHeartbeatCallerReason { CURRENT_ONLY, OLD_INSTANCE_GONE, OLD_INSTANCE_ALIVE, UNKNOWN, BOOT_CHANGED }
data class GmsHeartbeatCallerQualification(val current: GmsHeartbeatCallerIdentity?, val reason: GmsHeartbeatCallerReason)

/** Canonical, bounded IPC/persistence data; no paths, commands or caller assertions become authority. */
object GmsHeartbeatCallerCodec {
    const val BUNDLE_KEY = "gms_heartbeat_caller_v1"
    fun encodeIdentity(value: GmsHeartbeatCallerIdentity): String =
        "${value.uid},${value.pid},${value.startTicks},${value.kernelBootId}"
    fun decodeIdentity(raw: String?): GmsHeartbeatCallerIdentity? {
        if (raw == null || raw.length !in 1..96) return null
        val fields = raw.split(',')
        if (fields.size != 4 || fields.take(3).any { !it.matches(Regex("[1-9][0-9]*")) }) return null
        return runCatching {
            GmsHeartbeatCallerIdentity(fields[0].toInt(), fields[1].toInt(), fields[2].toLong(), fields[3])
        }.getOrNull()?.takeIf { encodeIdentity(it) == raw }
    }
    fun encode(requestId: Long, value: GmsHeartbeatCallerQualification): String =
        "1|$requestId|${value.reason.name}|${value.current?.let(::encodeIdentity) ?: "-"}"
    fun decode(raw: String?, requestId: Long): GmsHeartbeatCallerQualification {
        fun unknown() = GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
        if (requestId <= 0 || raw == null || raw.length !in 1..192) return unknown()
        val fields = raw.split('|')
        if (fields.size != 4 || fields[0] != "1" || fields[1] != requestId.toString()) return unknown()
        val reason = GmsHeartbeatCallerReason.entries.firstOrNull { it.name == fields[2] } ?: return unknown()
        val current = if (fields[3] == "-") null else decodeIdentity(fields[3]) ?: return unknown()
        if (current == null && reason != GmsHeartbeatCallerReason.UNKNOWN) return unknown()
        return GmsHeartbeatCallerQualification(current, reason)
    }
}

/** Linux stat field 22; comm may contain spaces and ')' so split only after its final delimiter. */
object GmsHeartbeatCallerStatParser {
    const val MAX_BYTES = 4096
    fun startTicks(raw: String, expectedPid: Int): Long? {
        if (expectedPid !in 1..4_194_304 || raw.length !in 1..MAX_BYTES || !raw.endsWith('\n') ||
            raw.dropLast(1).any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        val prefix = "$expectedPid ("
        if (!raw.startsWith(prefix)) return null
        val end = raw.lastIndexOf(") ")
        if (end < prefix.length) return null
        val fields = raw.substring(end + 2).dropLast(1).split(' ')
        if (fields.size !in 50..64 || !fields[0].matches(Regex("[RSDZTWtXxKPI]")) ||
            fields.drop(1).any { !it.matches(Regex("-?[0-9]+")) }) return null
        val ticks = fields[19]
        if (!ticks.matches(Regex("[1-9][0-9]*"))) return null
        return ticks.toLongOrNull()
    }
}
