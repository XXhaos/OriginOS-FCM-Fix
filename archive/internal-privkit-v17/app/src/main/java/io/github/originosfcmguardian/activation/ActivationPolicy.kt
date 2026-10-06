package io.github.originosfcmguardian.activation

import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import kotlinx.coroutines.Job

internal data class ActivationPreflight(
    val fingerprint: String,
    val userId: Int,
    val unlocked: Boolean,
    val usbDebugging: Int,
    val wifiTransport: Boolean,
)

internal object ActivationPolicy {
    const val MAX_LEASE_MILLIS = 90_000L
    fun port(text: String): Int? {
        if (text.isBlank()) return null
        require(text.all { it in '0'..'9' } && text.length <= 5) { "端口须为 1–65535" }
        return text.toInt().also { require(it in 1..65535) { "端口须为 1–65535" } }
    }
    fun pairingCode(text: String): Boolean = text.length == 6 && text.all { it in '0'..'9' }
    fun preflightFailure(input: ActivationPreflight, wirelessRequired: Boolean = true): String? = when {
        input.fingerprint != VERIFIED_AUTOSTART_FINGERPRINT -> "当前固件未验证，未启动或修改无线调试。"
        input.userId != 0 -> "仅支持主用户 user0，未启动或修改无线调试。"
        !input.unlocked -> "请先解锁主用户，再进行内置启动。"
        wirelessRequired && input.usbDebugging != 1 -> "请先在开发者选项开启 USB 调试。"
        wirelessRequired && !input.wifiTransport -> "请先连接 Wi-Fi，再配对或启动。"
        else -> null
    }
    fun cleanupAllowed(lease: WirelessLease, owner: String, boot: Int, now: Long): Boolean =
        lease.owner == owner && lease.boot == boot && boot >= 0 && lease.confirmed &&
            now >= lease.started && now - lease.started <= MAX_LEASE_MILLIS
}

internal data class WirelessLease(val owner: String, val boot: Int, val started: Long, val confirmed: Boolean)

/** A cancelled generation can never publish READY, even if transport returns late. */
internal class ActivationGeneration {
    private var generation = 0L
    private var active: Long? = null
    @Synchronized fun begin(): Long? {
        if (active != null || generation == Long.MAX_VALUE) return null
        return (++generation).also { active = it }
    }
    @Synchronized fun accepts(ticket: Long): Boolean = active == ticket && generation == ticket
    @Synchronized fun cancel() { if (generation < Long.MAX_VALUE) generation++; active = null }
    @Synchronized fun finish(ticket: Long) { if (active == ticket) active = null }
    fun finishOnCompletion(worker: Job, ticket: Long, terminalize: () -> Unit) {
        worker.invokeOnCompletion {
            finish(ticket)
            terminalize()
        }
    }
}
