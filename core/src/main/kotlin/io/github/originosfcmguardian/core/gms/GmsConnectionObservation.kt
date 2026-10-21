package io.github.originosfcmguardian.core.gms

import java.io.InputStream
import java.io.IOException

enum class SocketObservation { OBSERVED, NO_MATCH, UNKNOWN }
enum class SourceCompleteness { COMPLETE, PARTIAL, DENIED, UNAVAILABLE, MALFORMED, TRUNCATED, TIMED_OUT, CANCELLED }
enum class GcmConnectionState { REPORTED_CONNECTED, REPORTED_DISCONNECTED, UNKNOWN, SERVICE_ABSENT }

/** GMS's own report, independent of socket evidence and never a delivery/health verdict. */
data class GcmServiceSource(
    val state: GcmConnectionState, val completeness: SourceCompleteness,
    val observedAtMillis: Long? = null, val elapsedRealtimeMillis: Long? = null, val userId: Int? = null,
    /** GMS Diagnostics' current-session duration at sampling, not a monotonic start timestamp. */
    val connectedDurationMillis: Long? = null,
) {
    init {
        require((observedAtMillis == null) == (elapsedRealtimeMillis == null))
        require((observedAtMillis == null) == (userId == null))
        require(observedAtMillis == null || observedAtMillis > 0)
        require(elapsedRealtimeMillis == null || elapsedRealtimeMillis >= 0)
        require(userId == null || userId == 0)
        require(state == GcmConnectionState.UNKNOWN || completeness == SourceCompleteness.COMPLETE)
        require(connectedDurationMillis == null || connectedDurationMillis >= 0 &&
            state == GcmConnectionState.REPORTED_CONNECTED && completeness == SourceCompleteness.COMPLETE)
    }
    companion object {
        fun unavailable() = GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.UNAVAILABLE)
    }
}
enum class GmsUnknown { INVALID_REQUEST, SHELL_UNAVAILABLE, WRONG_USER, IDENTITY_UNKNOWN, IDENTITY_CHANGED,
    SHARED_UID, CAPABILITY_UNAVAILABLE, TIMED_OUT, CANCELLED, BUSY, BRIDGE_UNAVAILABLE, MALFORMED_RESPONSE }

/** No addresses, inode, process or unrelated UID data leave the parser. */
data class SocketSource(val completeness: SourceCompleteness, val matched: Boolean) {
    init { require(!matched || completeness in setOf(SourceCompleteness.COMPLETE, SourceCompleteness.PARTIAL)) }
}

data class ReconnectReceiver(val name: String, val exported: Boolean, val enabled: Boolean, val permission: String?) {
    init {
        require(name.length in 1..256 && name.matches(Regex("[A-Za-z0-9_.$]+")))
        require(permission == null || permission.length in 1..256 && permission.matches(Regex("[A-Za-z0-9_.$]+")))
    }
}

sealed interface GmsConnectionObservation {
    val requestId: Long
    data class Unknown(override val requestId: Long, val reason: GmsUnknown) : GmsConnectionObservation
    data class Sample(
        override val requestId: Long, val uid: Int, val versionCode: Long, val firstInstallTime: Long,
        val lastUpdateTime: Long, val signerSha256: String, val bootId: String,
        val observedAtMillis: Long, val elapsedRealtimeMillis: Long,
        val tcp: SocketSource, val tcp6: SocketSource,
        val receiverInspectionComplete: Boolean, val receivers: List<ReconnectReceiver>,
        val gcmService: GcmServiceSource = GcmServiceSource.unavailable(),
    ) : GmsConnectionObservation {
        init {
            require(requestId > 0 && uid in 10_000..99_999 && versionCode >= 0)
            require(firstInstallTime > 0 && lastUpdateTime > 0 && observedAtMillis > 0 && elapsedRealtimeMillis >= 0)
            require(signerSha256.matches(Regex("[a-f0-9]{64}")))
            require(bootId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            require(receivers.size <= 16 && receivers.map { it.name }.distinct().size == receivers.size)
            require(receiverInspectionComplete || receivers.isEmpty())
            require(gcmService.state == GcmConnectionState.UNKNOWN || gcmService.observedAtMillis != null)
        }
        val observation: SocketObservation get() = when {
            tcp.matched || tcp6.matched -> SocketObservation.OBSERVED
            tcp.completeness == SourceCompleteness.COMPLETE && tcp6.completeness == SourceCompleteness.COMPLETE -> SocketObservation.NO_MATCH
            else -> SocketObservation.UNKNOWN
        }
        fun isFresh(nowElapsed: Long): Boolean = nowElapsed >= elapsedRealtimeMillis && nowElapsed - elapsedRealtimeMillis <= 30_000
    }
}

/** Reads bounded bytes directly; even a denied/truncated table cannot become negative evidence. */
object GmsSocketParser {
    const val MAX_BYTES = 256 * 1024
    const val MAX_LINES = 2048
    const val MAX_LINE_BYTES = 1024
    private val whitespace = Regex("\\s+")
    private val ipv4Endpoint = Regex("[A-Fa-f0-9]{8}:[A-Fa-f0-9]{4}")
    private val ipv6Endpoint = Regex("[A-Fa-f0-9]{32}:[A-Fa-f0-9]{4}")
    private val slot = Regex("[0-9]+:")
    private val state = Regex("[A-Fa-f0-9]{2}")
    private val queues = Regex("[A-Fa-f0-9]{8}:[A-Fa-f0-9]{8}")
    private val timer = Regex("[A-Fa-f0-9]{2}:[A-Fa-f0-9]{8}")
    private val retransmits = Regex("[A-Fa-f0-9]{8}")
    fun parse(input: InputStream, ipv6: Boolean, uid: Int, interrupted: () -> Boolean = { false }): SocketSource {
        require(uid in 10_000..99_999)
        val endpoint = if (ipv6) ipv6Endpoint else ipv4Endpoint
        var bytes = 0; var lines = 0; var header = false; var malformed = false; var matched = false
        val line = StringBuilder()
        fun result(completeness: SourceCompleteness): SocketSource = SocketSource(
            if (matched && completeness != SourceCompleteness.COMPLETE) SourceCompleteness.PARTIAL else completeness, matched,
        )
        fun consume(): Boolean {
            lines++
            if (lines > MAX_LINES) return false
            val fields = line.toString().trim().split(whitespace); line.setLength(0)
            if (!header) {
                header = fields.size == 12 && fields.take(2) == listOf("sl", "local_address") &&
                    fields[2] == (if (ipv6) "remote_address" else "rem_address") && fields[3] == "st" &&
                    fields.drop(4).take(8) == listOf("tx_queue", "rx_queue", "tr", "tm->when", "retrnsmt", "uid", "timeout", "inode")
                if (!header) malformed = true
                return true
            }
            val valid = fields.size >= 10 && slot.matches(fields[0]) && endpoint.matches(fields[1]) && endpoint.matches(fields[2]) &&
                state.matches(fields[3]) && queues.matches(fields[4]) &&
                timer.matches(fields[5]) && retransmits.matches(fields[6]) &&
                fields[7].toIntOrNull()?.let { it >= 0 } == true && fields[8].toLongOrNull()?.let { it >= 0 } == true &&
                fields[9].toLongOrNull()?.let { it >= 0 } == true
            if (!valid) malformed = true
            else if (fields[7].toInt() == uid && fields[3].equals("01", true) && fields[2].substringAfter(':').toInt(16) in 5228..5230) matched = true
            return true
        }
        while (true) {
            if (interrupted()) return result(SourceCompleteness.PARTIAL)
            val byte = try { input.read() } catch (_: IOException) {
                return result(SourceCompleteness.UNAVAILABLE)
            } catch (_: SecurityException) {
                return result(SourceCompleteness.DENIED)
            }
            if (byte < 0) break
            if (++bytes > MAX_BYTES) return result(SourceCompleteness.TRUNCATED)
            if (byte == 10) {
                if (!consume()) return result(SourceCompleteness.TRUNCATED)
                // Do not reinterpret rows following an unrecognized header as trusted data.
                if (lines == 1 && !header) return result(SourceCompleteness.MALFORMED)
            }
            else {
                if (byte !in 32..126 && byte != 9 && byte != 13) return result(SourceCompleteness.MALFORMED)
                if (line.length >= MAX_LINE_BYTES) return result(SourceCompleteness.TRUNCATED)
                if (byte != 13) line.append(byte.toChar())
            }
        }
        // Missing final newline can be a torn/truncated row; discard that row.
        if (line.isNotEmpty()) return result(SourceCompleteness.TRUNCATED)
        return result(if (!header || malformed) SourceCompleteness.MALFORMED else SourceCompleteness.COMPLETE)
    }
}

/** Strict small wire contract: no raw proc rows or arbitrary error text. */
object GmsObservationCodec {
    const val BUNDLE_KEY = "gms_connection_observation_v1"
    fun encode(value: GmsConnectionObservation): String = when (value) {
        is GmsConnectionObservation.Unknown -> "2|${value.requestId}|U|${value.reason}"
        is GmsConnectionObservation.Sample -> (listOf(if (value.gcmService.connectedDurationMillis == null) "2" else "3", value.requestId, "S", value.uid, value.versionCode,
            value.firstInstallTime, value.lastUpdateTime, value.signerSha256, value.bootId, value.observedAtMillis,
            value.elapsedRealtimeMillis, value.tcp.completeness, flag(value.tcp.matched), value.tcp6.completeness,
            flag(value.tcp6.matched), flag(value.receiverInspectionComplete), value.receivers.joinToString(";") {
                "${it.name},${flag(it.exported)},${flag(it.enabled)},${it.permission ?: ""}"
            }, value.gcmService.state, value.gcmService.completeness, value.gcmService.observedAtMillis ?: "",
            value.gcmService.elapsedRealtimeMillis ?: "", value.gcmService.userId ?: "") +
            if (value.gcmService.connectedDurationMillis == null) emptyList() else listOf(value.gcmService.connectedDurationMillis)).joinToString("|")
    }
    fun decode(raw: String?, expectedRequestId: Long): GmsConnectionObservation = runCatching {
        require(expectedRequestId > 0 && raw != null && raw.length <= 12_000)
        val f = raw.split('|')
        require(f.size >= 4 && f[0] in setOf("1", "2", "3") && f[1] == expectedRequestId.toString())
        if (f[2] == "U") {
            require(f.size == 4); GmsConnectionObservation.Unknown(expectedRequestId, GmsUnknown.valueOf(f[3]))
        } else {
            require(f.size == (when (f[0]) { "1" -> 17; "2" -> 22; else -> 23 }) && f[2] == "S")
            val receivers = if (f[16].isEmpty()) emptyList() else f[16].split(';').map {
                val r = it.split(','); require(r.size == 4)
                ReconnectReceiver(r[0], boolean(r[1]), boolean(r[2]), r[3].ifEmpty { null })
            }
            GmsConnectionObservation.Sample(expectedRequestId, f[3].toInt(), f[4].toLong(), f[5].toLong(), f[6].toLong(),
                f[7], f[8], f[9].toLong(), f[10].toLong(), SocketSource(SourceCompleteness.valueOf(f[11]), boolean(f[12])),
                SocketSource(SourceCompleteness.valueOf(f[13]), boolean(f[14])), boolean(f[15]), receivers,
                if (f[0] == "1") GcmServiceSource.unavailable() else GcmServiceSource(
                    GcmConnectionState.valueOf(f[17]), SourceCompleteness.valueOf(f[18]),
                    f[19].ifEmpty { null }?.toLong(), f[20].ifEmpty { null }?.toLong(), f[21].ifEmpty { null }?.toInt(),
                    if (f[0] == "3") f[22].toLong() else null))
        }
    }.getOrElse { GmsConnectionObservation.Unknown(expectedRequestId, GmsUnknown.MALFORMED_RESPONSE) }
    private fun flag(value: Boolean) = if (value) "1" else "0"
    private fun boolean(value: String) = when (value) { "1" -> true; "0" -> false; else -> error("flag") }
}
