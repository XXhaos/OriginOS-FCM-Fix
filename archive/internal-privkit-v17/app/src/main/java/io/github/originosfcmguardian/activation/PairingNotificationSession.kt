package io.github.originosfcmguardian.activation

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Revocation never waits for controller/preflight I/O or its monitor. */
internal class PairingOperationToken {
    private val live = AtomicBoolean(true)
    val isLive: Boolean get() = live.get()
    fun revoke() { live.set(false) }
    fun ensureLive() { if (!isLive) throw CancellationException("Pairing session revoked") }
    fun <T> readWhileLive(read: () -> T): T {
        ensureLive()
        val result = read()
        ensureLive() // A cancellation queued behind blocking preflight cannot admit dispatch.
        return result
    }
}

internal const val PAIRING_SESSION_MILLIS = 120_000L
internal const val PAIRING_INPUT_MILLIS = 60_000L
internal const val PAIRING_OPERATION_MILLIS = 40_000L
internal const val PAIRING_CLEANUP_RESERVE_MILLIS = 20_000L

internal enum class PairingNotificationPhase { RESERVED, DISCOVERING, WAITING, WORKING, TERMINAL }
internal data class PairingReplyClaim(val port: Int, val operationBudgetMillis: Long)

/** Only nonce, phase, deadline and discovered port; never stores a reply or pairing secret. */
internal class PairingNotificationSession(val identity: String, private val started: Long) {
    val operationToken = PairingOperationToken()
    private var live = true
    private var port: Int? = null
    private var currentPhase = PairingNotificationPhase.RESERVED
    val phase: PairingNotificationPhase get() = synchronized(this) { currentPhase }
    @Synchronized fun matches(candidate: String?): Boolean = live && candidate == identity
    @Synchronized fun remainingTotal(now: Long): Long = remaining(now, PAIRING_SESSION_MILLIS)
    @Synchronized fun remainingInput(now: Long): Long = remaining(now, PAIRING_INPUT_MILLIS)
    private fun remaining(now: Long, limit: Long): Long = if (!live || now < started) 0 else (limit - (now - started)).coerceAtLeast(0)

    @Synchronized fun start(candidate: String?, now: Long): Boolean {
        if (!matches(candidate) || currentPhase != PairingNotificationPhase.RESERVED || remainingInput(now) == 0L) return false
        currentPhase = PairingNotificationPhase.DISCOVERING
        return true
    }

    @Synchronized fun discovered(value: Int, now: Long): Boolean {
        if (!live || currentPhase != PairingNotificationPhase.DISCOVERING || value !in 1..65535 || remainingInput(now) == 0L) return false
        port = value
        currentPhase = PairingNotificationPhase.WAITING
        return true
    }

    /** Claim before reading RemoteInput. Invalid data also consumes this sole reply opportunity. */
    @Synchronized fun claimReply(candidate: String?, now: Long): PairingReplyClaim? {
        if (!matches(candidate) || currentPhase != PairingNotificationPhase.WAITING || remainingInput(now) == 0L) return null
        val budget = minOf(PAIRING_OPERATION_MILLIS, remainingTotal(now) - PAIRING_CLEANUP_RESERVE_MILLIS)
        if (budget <= 0) return null
        val found = port ?: return null
        currentPhase = PairingNotificationPhase.WORKING
        port = null
        return PairingReplyClaim(found, budget)
    }

    fun close() {
        operationToken.revoke()
        synchronized(this) { live = false; port = null; currentPhase = PairingNotificationPhase.TERMINAL }
    }
}

/** The same gate used by explicit start(), so reserve and duplicate rejection are synchronous. */
internal class PairingNotificationGate {
    private var current: PairingNotificationSession? = null
    @Synchronized fun reserve(identity: String, now: Long): PairingNotificationSession? {
        if (current != null) return null
        return PairingNotificationSession(identity, now).also { current = it }
    }
    @Synchronized fun find(identity: String?): PairingNotificationSession? = current?.takeIf { it.matches(identity) }
    @Synchronized fun owns(session: PairingNotificationSession): Boolean = current === session
    @Synchronized fun release(session: PairingNotificationSession): Boolean {
        session.close()
        if (current !== session) return false
        current = null
        return true
    }
    @Synchronized fun active(): PairingNotificationSession? = current
}

internal data class PairingNotificationPlan(val ongoing: Boolean, val autoCancel: Boolean, val allowReply: Boolean)
internal fun pairingNotificationPlan(phase: PairingNotificationPhase): PairingNotificationPlan =
    PairingNotificationPlan(ongoing = phase != PairingNotificationPhase.TERMINAL,
        autoCancel = phase == PairingNotificationPhase.TERMINAL, allowReply = phase == PairingNotificationPhase.WAITING)

internal fun validNotificationPairingCode(value: CharSequence?): Boolean =
    value != null && value.length == 6 && value.all { it in '0'..'9' }

/** Called under the controller lock: a revoked reservation never admits a late reply. */
internal fun notificationPairingOwnerAllows(reserved: String?, incoming: String?): Boolean = reserved == incoming

internal fun pairingNotificationResult(status: InternalActivationStatus): String {
    if (status.paired && !status.needsManualCleanup && status.failure == null && status.cleanupFailure == null)
        return "Guardian 配对成功。可回到应用启动并核验内置服务。"
    val primary = status.failure?.let { "${it.guidance} [${it.code}]" } ?: "Guardian 配对未完成。请重新主动开始通知配对。"
    val cleanup = status.cleanupFailure?.let { " 清理诊断：[${it.code}]。" }.orEmpty()
    val manual = if (status.needsManualCleanup) " 请在系统开发者选项手动关闭无线调试，再回应用确认。" else ""
    return primary + cleanup + manual
}
