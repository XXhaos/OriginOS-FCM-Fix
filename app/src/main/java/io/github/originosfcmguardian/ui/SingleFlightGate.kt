package io.github.originosfcmguardian.ui

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

internal class SingleFlightGate {
    private val entered = AtomicBoolean(false)

    fun tryEnter(): Boolean = entered.compareAndSet(false, true)

    fun releaseOnCompletion(job: Job) {
        job.invokeOnCompletion { exit() }
    }

    internal fun exit() {
        check(entered.compareAndSet(true, false)) { "Single-flight gate was not entered" }
    }
}
