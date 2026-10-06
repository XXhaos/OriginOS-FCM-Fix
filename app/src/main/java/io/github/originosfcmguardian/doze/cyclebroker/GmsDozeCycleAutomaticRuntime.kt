package io.github.originosfcmguardian.doze.cyclebroker

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One application owner; FGS/UI detach never cancels dispatched child settlement. */
internal object GmsDozeCycleAutomaticRuntime {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val mutable = MutableStateFlow(GmsDozeCycleAutomaticUiState())
    val state: StateFlow<GmsDozeCycleAutomaticUiState> = mutable.asStateFlow()
    private var owner: GmsDozeCycleAutomaticController? = null
    private var canonical: String? = null
    private val failureGate = GmsDozeCycleAutomaticRuntimeFailureGate(mutable) {
        val current = synchronized(monitor) { owner }
        current?.suspendAfterRuntimeFailure()
    }

    private fun get(context: Context): GmsDozeCycleAutomaticController = synchronized(monitor) {
        val app = context.applicationContext
        val path = app.noBackupFilesDir.canonicalPath
        check(canonical == null || canonical == path)
        owner ?: GmsDozeCycleAutomaticController(GmsDozeCycleBusinessBridge.get(app), applicationScope,
            SystemClock::elapsedRealtime, System::currentTimeMillis,
            { GmsDozeCycleProductController.get(app).refresh() }).also {
            canonical = path; owner = it
            val controller = it
            applicationScope.launch { controller.state.collect { value -> failureGate.publish(value) } }
        }
    }
    fun initialize(context: Context) = failureGate.invoke { get(context).initialize() }
    fun refresh(context: Context) = failureGate.invoke { val current = get(context); current.initialize(); current.refresh() }
    fun attachHost(context: Context, id: String) = failureGate.invoke { val current = get(context); current.initialize(); current.attachHost(id) }
    fun hostEligible(context: Context, id: String, connectionGeneration: Long, eligible: () -> Boolean) =
        failureGate.invoke { get(context).hostEligible(id, connectionGeneration, eligible) }
    fun hint(context: Context, id: String, generation: Long, eligible: () -> Boolean) = failureGate.invoke { get(context).hint(id, generation, eligible) }
    fun confirm(context: Context, target: GmsDozeCycleAutomaticConfirmationTarget) = failureGate.invoke { get(context).confirm(target) }
    fun hostLost(id: String, generation: Long? = null) = failureGate.invoke {
        val current = synchronized(monitor) { owner }
        current?.hostLost(id, generation)
    }
    fun hostEnded(id: String) = failureGate.invoke { val current = synchronized(monitor) { owner }; current?.hostEnded(id) }
    fun gmsOffNow() = failureGate.invoke { val current = synchronized(monitor) { owner }; current?.gmsOffNow() }
}

/** The actual facade boundary, separated only so host tests do not construct Android Context. */
internal class GmsDozeCycleAutomaticRuntimeFailureGate(
    private val state: MutableStateFlow<GmsDozeCycleAutomaticUiState>,
    private val suspendOwnAutomatic: () -> Unit,
) {
    private val monitor = Any()
    private var failed = false

    fun invoke(action: () -> Unit) {
        if (synchronized(monitor) { failed }) return
        // Initialization/getters/UI/service work NEVER runs under this publication monitor.
        try {
            action()
            // Another already-entered facade may have constructed its owner after the first
            // failure saw no owner. Close that late partial owner before returning as well.
            if (synchronized(monitor) { failed }) suspendSafely()
        } catch (_: Exception) {
            synchronized(monitor) {
                if (!failed) {
                    failed = true
                    state.value = state.value.copy(loaded = true, blocked = true, eligible = false,
                        confirmationTarget = null, lastOutcome = "BLOCKED_RUNTIME",
                        detail = "自动电池保护组件异常，已阻断新自动操作；已保存配置未被关闭")
                }
            }
            suspendSafely()
        }
    }

    private fun suspendSafely() {
        try { suspendOwnAutomatic() } catch (_: Exception) {
            // Still blocked. Do not turn failed suspension into global OFF or a retry loop.
        }
    }

    fun publish(snapshot: GmsDozeCycleAutomaticUiState) = synchronized(monitor) {
        if (!failed) state.value = snapshot
        else state.value = state.value.copy(busy = snapshot.busy) // Actual settlement may finish.
    }
}
