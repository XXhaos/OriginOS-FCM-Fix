package io.github.originosfcmguardian.recovery

import java.util.concurrent.atomic.AtomicBoolean

/** Source gate in both client and server. Host implementation is not device write acceptance. */
internal object AppOwnedRecoveryPolicy {
    const val ENABLED = false
    fun requireEnabled() { check(ENABLED) { "App-owned recovery awaits device acceptance and specific review" } }
}

/** A timed-out bind does not release this ticket; only actual teardown may release it. */
internal class RecoveryCandidateAdmission {
    private val held = AtomicBoolean(false)
    fun acquire(): Boolean = held.compareAndSet(false, true)
    fun finishTeardown() { held.set(false) }
    fun isHeld(): Boolean = held.get()
}
