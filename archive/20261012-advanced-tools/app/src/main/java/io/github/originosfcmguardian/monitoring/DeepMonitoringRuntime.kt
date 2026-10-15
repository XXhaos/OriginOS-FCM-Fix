package io.github.originosfcmguardian.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MonitorRunPhase { IDLE, STARTING, RUNNING, STOPPING }

data class DeepMonitoringUiState(
    val phase: MonitorRunPhase = MonitorRunPhase.IDLE,
    val session: MonitorSession? = null,
    val historyCorrupt: Boolean = false,
    val message: String = "未开启。仅在手动排障时观察所选应用，不会自动恢复监控。",
) {
    val busy: Boolean get() = phase != MonitorRunPhase.IDLE
}

/** Process-local reservation only. Nothing here survives a killed app or starts a service. */
internal class MonitorRunGate {
    var id: String? = null
        private set
    var claimed: Boolean = false
        private set
    var revision: Long = 0
        private set

    fun reserve(value: String): Boolean {
        if (id != null) return false
        id = value
        claimed = false
        revision++
        return true
    }

    fun claim(value: String): Boolean {
        if (id != value || claimed) return false
        claimed = true
        return true
    }

    fun finish(value: String): Boolean {
        if (id != value) return false
        id = null
        claimed = false
        revision++
        return true
    }
}

/** All transitions are fenced by a session id; a late service cannot overwrite a newer session. */
object DeepMonitoringRuntime {
    private val gate = MonitorRunGate()
    private var stopAction: (() -> Unit)? = null
    private val mutable = MutableStateFlow(DeepMonitoringUiState())
    val state: StateFlow<DeepMonitoringUiState> = mutable.asStateFlow()

    @Synchronized internal fun reserve(session: MonitorSession): Boolean {
        if (!gate.reserve(session.id)) return false
        mutable.value = DeepMonitoringUiState(MonitorRunPhase.STARTING, session, message = "正在准备手动监控…")
        return true
    }

    @Synchronized internal fun claim(id: String, onStop: () -> Unit): MonitorSession? {
        if (!gate.claim(id)) return null
        stopAction = onStop
        return mutable.value.session
    }

    @Synchronized internal fun update(id: String, session: MonitorSession, message: String) {
        if (gate.id != id || mutable.value.phase == MonitorRunPhase.STOPPING) return
        mutable.value = DeepMonitoringUiState(MonitorRunPhase.RUNNING, session, message = message)
    }

    @Synchronized internal fun finish(id: String, session: MonitorSession?, message: String) {
        if (!gate.finish(id)) return
        stopAction = null
        mutable.value = DeepMonitoringUiState(session = session, message = message)
    }

    fun stop(expectedId: String? = null) {
        val action = synchronized(this) {
            val id = gate.id ?: return
            if (expectedId != null && expectedId != id) return
            mutable.value = mutable.value.copy(phase = MonitorRunPhase.STOPPING, message = "正在停止并清理读取进程…")
            stopAction ?: run {
                // The service has not claimed this request. Revoke it before a late start arrives.
                finish(id, mutable.value.session?.copy(status = MonitorSessionStatus.STOPPED), "已取消启动")
                return
            }
        }
        action()
    }

    @Synchronized internal fun revision(): Long = gate.revision

    @Synchronized internal fun showHistory(revision: Long, loaded: MonitorSessionLoadResult) {
        if (gate.id != null || gate.revision != revision) return
        mutable.value = when (loaded) {
            MonitorSessionLoadResult.Empty -> DeepMonitoringUiState()
            MonitorSessionLoadResult.Corrupt -> DeepMonitoringUiState(
                historyCorrupt = true, message = "监控记录损坏；未覆盖。请先显式清除这份监控记录。",
            )
            is MonitorSessionLoadResult.Available -> {
                val interrupted = loaded.session.status == MonitorSessionStatus.RUNNING
                val session = if (interrupted) loaded.session.copy(status = MonitorSessionStatus.INTERRUPTED) else loaded.session
                DeepMonitoringUiState(session = session, message = if (interrupted) {
                    "上次监控未正常结束，记录可能不完整；不会自动继续。"
                } else "已保留最近一次监控；当前没有运行监控。")
            }
        }
    }

    /** Called on IO. Serialize explicit clear with reservations; never erase a running session. */
    @Synchronized internal fun clear(store: MonitorSessionStore): Boolean {
        if (gate.id != null) return false
        if (!store.clear()) return false
        mutable.value = DeepMonitoringUiState(message = "已清除最近一次监控记录")
        // Fence any history refresh that began before this explicit clear.
        gate.reserve("clear")
        gate.finish("clear")
        return true
    }
}
