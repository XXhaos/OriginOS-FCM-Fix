package io.github.originosfcmguardian.autoprotection

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Debug APK only. Manifest DUMP permission restricts invocation to privileged callers such as shell. */
class DebugReadBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !isOrderedBroadcast) return
        if (intent.extras?.isEmpty == false) {
            resultCode = Activity.RESULT_CANCELED
            resultData = failure("unexpected_extras").toString()
            return
        }
        if (!DebugReadBenchmark.running.compareAndSet(false, true)) {
            resultCode = Activity.RESULT_CANCELED
            resultData = failure("busy_or_aborted_until_process_restart").toString()
            return
        }
        val pending = goAsync()
        DebugReadBenchmark.scope.launch {
            var successful = false
            val output = try {
                // Foreground ordered broadcasts have a short platform lifetime; goAsync does not
                // extend it. Leave headroom to return/finish instead of consuming a 20s allowance.
                withTimeoutOrNull(8_000) {
                    DebugReadBenchmark.measure(context.applicationContext)
                }?.also { successful = true } ?: failure("global_timeout")
            } catch (_: CancellationException) {
                failure("cancelled")
            } catch (aborted: BenchmarkAbort) {
                failure(aborted.reason)
            } catch (_: Exception) {
                failure("failed")
            }
            try {
                pending.setResultCode(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                pending.setResultData(output.toString())
            } finally {
                // A timeout can leave uninterruptible Binder work in this one pool. Never allocate a
                // replacement or admit another run after an abort; restarting the app resets the latch.
                if (successful) DebugReadBenchmark.running.set(false)
                pending.finish()
            }
        }
    }

    private fun failure(reason: String) = JSONObject().put("status", reason).put("failureCount", 1)

    companion object { const val ACTION = "io.github.originosfcmguardian.DEBUG_BENCHMARK_READS" }
}

private class BenchmarkAbort(val reason: String) : RuntimeException()

/** One process-wide benchmark pool, separate from the production read pools. */
private object DebugReadBenchmark {
    val running = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reader by lazy { BoundedAutomaticTargetRead(maxParallel = 8) }
    private val orders = listOf(listOf(1, 2, 4, 8), listOf(8, 4, 2, 1), listOf(2, 8, 1, 4))
    private enum class Mode { STOPPED, QUALIFIED_STOPPED }

    suspend fun measure(context: Context): JSONObject {
        val baseline = AutomaticProtectionRuntime.state.value
        val targets = baseline.configuredIdentities.toList()
        if (!baseline.configurationAvailable || targets.size !in 1..128 || targets.distinct().size != targets.size) {
            throw BenchmarkAbort("configuration_unavailable_or_out_of_bounds")
        }
        val started = SystemClock.elapsedRealtimeNanos()
        // Fixed, small warmup uses the same exact-identity reads as each measured workload.
        for (mode in Mode.entries) sample(context, targets.take(2), 2, mode, targets)
        val samples = JSONArray()
        for (round in orders.indices) {
            val modes = if (round % 2 == 0) Mode.entries.toList() else Mode.entries.reversed()
            for (mode in modes) {
                for (parallel in orders[round]) {
                    val sample = sample(context, targets, parallel, mode, targets)
                    samples.put(sample.put("round", round + 1))
                }
            }
        }
        requireCurrentConfiguration(targets)
        return JSONObject().put("status", "ok").put("targets", targets.size)
            .put("failureCount", 0).put("elapsedMs", elapsedMillis(started)).put("samples", samples)
    }

    private suspend fun sample(
        context: Context,
        targets: List<SelectedTargetIdentity>,
        parallel: Int,
        mode: Mode,
        baseline: List<SelectedTargetIdentity>,
    ): JSONObject {
        coroutineContext.ensureActive()
        requireCurrentConfiguration(baseline)
        val probe = TargetPackageProbe(context) // Fresh adapter for each measurement; no cached PM evidence.
        val started = SystemClock.elapsedRealtimeNanos()
        var stopped = 0
        var notStopped = 0
        for (chunk in targets.chunked(parallel)) {
            coroutineContext.ensureActive()
            requireCurrentConfiguration(baseline)
            val observations = reader.readChunk(chunk) { identity ->
                val qualified = mode == Mode.STOPPED || probe.isRecoveryCandidate(identity)
                AutomaticTargetObservation(qualified, if (qualified) probe.stoppedSnapshot(identity) else null)
            }
            coroutineContext.ensureActive()
            requireCurrentConfiguration(baseline)
            for (observation in observations) {
                if (observation.failure != null) throw BenchmarkAbort("read_${observation.failure.name.lowercase()}")
                if (observation.qualified != true) throw BenchmarkAbort("qualification_failed")
                when (observation.stopped?.status) {
                    TargetStoppedStatus.STOPPED -> stopped++
                    TargetStoppedStatus.NOT_STOPPED -> notStopped++
                    TargetStoppedStatus.IDENTITY_CHANGED -> throw BenchmarkAbort("identity_changed")
                    else -> throw BenchmarkAbort("read_unknown")
                }
            }
        }
        return JSONObject().put("mode", mode.name.lowercase()).put("parallel", parallel)
            .put("targets", targets.size).put("elapsedMs", elapsedMillis(started))
            .put("failureCount", 0).put("stopped", stopped).put("notStopped", notStopped)
    }

    private fun requireCurrentConfiguration(baseline: List<SelectedTargetIdentity>) {
        val current = AutomaticProtectionRuntime.state.value
        if (!current.configurationAvailable || current.configuredIdentities != baseline) throw BenchmarkAbort("configuration_changed")
    }

    private fun elapsedMillis(started: Long): Double = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
}
