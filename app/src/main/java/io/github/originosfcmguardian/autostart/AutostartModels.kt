package io.github.originosfcmguardian.autostart

import io.github.originosfcmguardian.core.GuardianAutostartRead

const val VERIFIED_AUTOSTART_FINGERPRINT =
    "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"

enum class AutostartUiStatus {
    NOT_CHECKED,
    CHECKING,
    ALLOWED,
    NEEDS_USER_ACTION,
    UNKNOWN,
    UNAVAILABLE,
}

data class GuardianAutostartUiState(
    val status: AutostartUiStatus = AutostartUiStatus.NOT_CHECKED,
    val evidence: GuardianAutostartRead? = null,
    val detail: String = "尚未检查 Guardian 自启动状态",
)

/** One-shot lifecycle gate: a return is valid only after a successful launch and actual pause. */
internal class AutostartReturnTracker {
    private enum class Phase { IDLE, AWAITING_DEPARTURE, AWAITING_RETURN }

    private var phase = Phase.IDLE

    @Synchronized
    fun markSuccessfulLaunch() {
        phase = Phase.AWAITING_DEPARTURE
    }

    @Synchronized
    fun markDeparture() {
        if (phase == Phase.AWAITING_DEPARTURE) phase = Phase.AWAITING_RETURN
    }

    @Synchronized
    fun consumeReturn(): Boolean {
        if (phase != Phase.AWAITING_RETURN) return false
        phase = Phase.IDLE
        return true
    }

    @Synchronized
    fun clear() {
        phase = Phase.IDLE
    }
}
